/*
 * Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {appendFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';
import mergeResults from '@wdio/json-reporter/mergeResults';
import {prepareApks} from './prepare.js';
import {collectDiagnostics, reports, root} from './support/device.js';

export const config: WebdriverIO.Config = {
    runner: 'local',
    specs: ['./specs/*.e2e.ts'],
    exclude: process.env.NEW_UI_BASELINE_APK ? [] : ['./specs/new-ui-widget-upgrade.e2e.ts'],
    maxInstances: 1,
    hostname: '127.0.0.1',
    port: 4723,
    logLevel: 'warn',
    outputDir: join(reports, 'wdio'),
    connectionRetryCount: 0,
    connectionRetryTimeout: 180_000,
    waitforTimeout: 20_000,
    waitforInterval: 250,
    specFileRetries: 0,
    framework: 'mocha',
    mochaOpts: {ui: 'bdd', timeout: 600_000, retries: 0, failZero: true, forbidPending: true, require: ['./support/hooks.ts']},
    reporters: ['spec', ['junit', {
        outputDir: join(reports, 'junit'),
        outputFileFormat: options => `results-${options.cid}.xml`,
    }], ['json', {
        outputDir: join(reports, 'json'),
        outputFileFormat: options => `results-${options.cid}.json`,
    }]],
    services: [['appium', {
        logPath: join(reports, 'appium'),
        args: {address: '127.0.0.1', port: 4723, allowInsecure: 'uiautomator2:adb_shell'},
    }]],
    capabilities: [{
        platformName: 'Android',
        'appium:automationName': 'UiAutomator2',
        'appium:deviceName': 'Battery Monitor disposable emulator',
        'appium:noReset': true,
        'appium:fullReset': false,
        'appium:autoLaunch': false,
        'appium:skipUnlock': true,
        'appium:disableWindowAnimation': true,
        'appium:clearDeviceLogsOnStart': true,
        'appium:allowTestPackages': true,
        'appium:printPageSourceOnFindFailure': true,
        'appium:newCommandTimeout': 180,
    }],
    async onPrepare() {
        assert.equal(process.env.E2E_ALLOW_WIPE, 'true', 'E2E wipes application data. Use a disposable emulator and set E2E_ALLOW_WIPE=true.');
        const local = existsSync(join(root, 'local.properties'))
            ? readFileSync(join(root, 'local.properties'), 'utf8').match(/^sdk\.dir=(.+)$/m)?.[1]?.replaceAll('\\:', ':') : undefined;
        const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || local;
        assert(sdk, 'Set ANDROID_HOME to an installed Android SDK');
        process.env.ANDROID_HOME = sdk;
        const adb = join(sdk, 'platform-tools/adb');
        const devices = execFileSync(adb, ['devices'], {encoding: 'utf8'})
            .split('\n').filter(line => /\tdevice$/.test(line)).map(line => line.split('\t')[0]);
        const serial = process.env.ANDROID_SERIAL;
        assert(serial ? devices.includes(serial) : devices.length === 1, 'Connect exactly one emulator, or select an online emulator with ANDROID_SERIAL.');
        process.env.ANDROID_SERIAL = serial || devices[0];
        const property = (name: string) => execFileSync(adb, ['-s', process.env.ANDROID_SERIAL!, 'shell', 'getprop', name], {encoding: 'utf8'}).trim();
        assert.equal(property('ro.kernel.qemu'), '1', 'Physical devices are rejected; use a disposable emulator.');
        assert.equal(property('sys.boot_completed'), '1', 'Emulator has not completed booting.');
        assert.match(property('persist.sys.locale') || property('ro.product.locale'), /^en(?:-US)?$/i, 'Use an en-US emulator for system UI selectors.');
        rmSync(reports, {recursive: true, force: true});
        mkdirSync(join(reports, 'json'), {recursive: true});
        const prepared = await prepareApks();
        if (prepared.newUiBaselineUnavailableReason) {
            writeFileSync(join(reports, 'new-ui-baseline-unavailable.txt'), `${prepared.newUiBaselineUnavailableReason}\n`);
            if (process.env.GITHUB_STEP_SUMMARY) {
                appendFileSync(process.env.GITHUB_STEP_SUMMARY, `\n${prepared.newUiBaselineUnavailableReason}\n`);
            }
        }
    },
    beforeSession(_config, capabilities) {
        Object.assign(capabilities, {'appium:udid': process.env.ANDROID_SERIAL});
    },
    async afterTest(test, _context, {passed}) {
        if (!passed) await collectDiagnostics(`failed-${test.title}`);
    },
    async afterHook(_test, _context, {passed}) {
        if (!passed) await collectDiagnostics('failed-hook');
    },
    async onComplete(exitCode) {
        if (exitCode !== 0) return;
        const summary = await mergeResults(join(reports, 'json'), '^results-.*\\.json$');
        assert((summary.state?.passed ?? 0) > 0, 'No passing E2E tests executed; inspect the selected specs and reports.');
    },
};
