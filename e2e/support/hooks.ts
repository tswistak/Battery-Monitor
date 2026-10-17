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
import {writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {browser} from '@wdio/globals';
import {apks, directory, helperId, reports, resetBattery, shell} from './device.js';
import {checkSystemAppError} from './ui.js';

// Per-test root hooks run inside the scenario suite so JUnit includes setup and cleanup failures.
export const mochaHooks = {
    async beforeEach() {
        const started = Date.now();
        await browser.installApp(apks().helper.path, {replace: true, allowTestPackages: true});
        await shell('mkdir', ['-p', directory]);
        await shell('settings', ['put', 'global', 'auto_time_zone', '0']);
        await shell('cmd', ['alarm', 'set-timezone', 'Europe/Warsaw']);
        assert.equal((await shell('getprop', ['persist.sys.timezone'])).trim(), 'Europe/Warsaw');
        await shell('wm', ['user-rotation', 'lock', '0']);
        await shell('input', ['keyevent', 'KEYCODE_WAKEUP']);
        await shell('wm', ['dismiss-keyguard']);
        await checkSystemAppError({dismissBootLauncherAnr: true});
        console.log(`Test APK installation and device preparation: ${(Date.now() - started) / 1000}s`);
    },
    async afterEach() {
        const started = Date.now();
        await resetBattery();
        await checkSystemAppError();
        try {
            const bytes = await browser.pullFolder(`/sdcard/Android/data/${helperId}/files/diagnostics`);
            writeFileSync(join(reports, `widget-screenshots-${browser.sessionId}.zip`), Buffer.from(bytes, 'base64'));
        } catch (error) {
            writeFileSync(join(reports, 'widget-screenshot-collection.txt'), String(error));
        }
        console.log(`Artifact collection and battery cleanup: ${(Date.now() - started) / 1000}s`);
    },
};
