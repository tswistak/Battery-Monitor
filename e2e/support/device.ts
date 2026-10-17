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
import {createHash} from 'node:crypto';
import {mkdirSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {DatabaseSync} from 'node:sqlite';
import {browser} from '@wdio/globals';
import {XMLParser} from 'fast-xml-parser';
import type {PreparedApks} from '../prepare.js';

export const root = fileURLToPath(new URL('../../', import.meta.url));
export const reports = join(root, 'e2e/build/reports');
export const appId = 'codes.swistak.batterymonitor';
export const helperId = `${appId}.e2ehelper`;
export const directory = '/sdcard/Download/battery-monitor-e2e';
export const apks = (): PreparedApks => JSON.parse(readFileSync(join(root, 'e2e/build/apks.json'), 'utf8'));

export interface LogRow {
    status: number; charge: number | null; time: number; temperature: number | null;
    voltage: number | null; current: number | null; remaining_charge_uah: number | null;
}
export interface AppState {
    settings: Record<string, unknown>; main: Record<string, unknown>; predictor: Record<string, unknown>;
    alarms: {enabled: boolean; type: string; threshold: string}[];
    logs: LogRow[]; monitoringDesired: boolean;
}

export async function shell(command: string, args: string[] = []): Promise<string> {
    return browser.execute('mobile: shell', {command, args, timeout: 120_000}) as Promise<string>;
}

// These probes inspect private legacy storage/widgets; Mocha owns test results and reporting.
export async function helperProbe(kind: 'Storage' | 'Widget', phase: string, extra: Record<string, unknown> = {}) {
    const parameters = Object.entries({phase, ...extra}).flatMap(([key, value]) => ['-e', key, String(value)]);
    const output = await shell('am', ['instrument', '-w', '-r', ...parameters, `${helperId}/.${kind}Probe`]);
    writeFileSync(join(reports, `${kind}-${phase}.txt`), output);
    assert.match(output, /^INSTRUMENTATION_RESULT: probeSucceeded=true\s*$/m, `${kind} probe ${phase} failed:\n${output}`);
    return Object.fromEntries([...output.matchAll(/^INSTRUMENTATION_RESULT: (\w+)=(.*)$/gm)]
        .map(match => [match[1], match[2].trim()]));
}

export async function cleanScenario() {
    // Uninstall only between independent scenarios; upgrades always use Appium replace=true.
    if (await browser.isAppInstalled(appId)) await browser.removeApp(appId);
    await shell('pm', ['clear', helperId]);
    await shell('appwidget', ['grantbind', '--package', helperId, '--user', '0']);
    await shell('rm', ['-rf', directory]);
    await shell('mkdir', ['-p', directory]);
    await resetBattery();
}

async function ensureCurrentInstalled() {
    if (!await browser.isAppInstalled(appId)) {
        await browser.installApp(apks().current.path, {allowTestPackages: true, grantPermissions: true});
    }
}

export async function cleanInstallCurrent() {
    await cleanScenario();
    await ensureCurrentInstalled();
}

export async function launchApp({section, detail}: {section?: string; detail?: string} = {}) {
    const extras: string[][] = [];
    if (section) extras.push(['s', `${appId}.EXTRA_SECTION`, section]);
    if (detail) extras.push(['s', `${appId}.EXTRA_DETAIL`, detail]);
    await browser.execute('mobile: startActivity', {
        component: `${appId}/.app.BatteryInfoActivity`, wait: true,
        flags: '0x10008000', extras,
    });
}

export async function forceStopApp() {
    await browser.terminateApp(appId);
}

export async function clearAppData() {
    await browser.execute('mobile: clearApp', {appId});
}

export async function seedBackupData() {
    await ensureCurrentInstalled();
    await helperProbe('Storage', 'seedBackupData');
}

export async function seedSentinelData() {
    await ensureCurrentInstalled();
    await helperProbe('Storage', 'seedSentinelData');
    await stageHistoricalFixtures();
}

export async function readState(): Promise<AppState> {
    return JSON.parse((await helperProbe('Storage', 'readState')).stateJson);
}

export async function stopMonitoring() {
    await helperProbe('Storage', 'stopMonitoring');
}

export async function startMonitoringForAlarm() {
    await ensureCurrentInstalled();
    await stageHistoricalFixtures();
    await helperProbe('Storage', 'startMonitoringForAlarm');
    await launchApp();
}

export async function setAlarmInsertFailure(enabled: boolean) {
    await helperProbe('Storage', enabled ? 'installAlarmFailure' : 'dropAlarmFailure');
}

export async function setBattery({level, ac = 0, status = 3}: {level: number; ac?: number; status?: number}) {
    await shell('dumpsys', ['battery', 'unplug']);
    await shell('dumpsys', ['battery', 'set', 'ac', String(ac)]);
    await shell('dumpsys', ['battery', 'set', 'status', String(status)]);
    await shell('dumpsys', ['battery', 'set', 'level', String(level)]);
}

export async function resetBattery() {
    // Native ADB waits for transport readiness and executes cleanup once, even if UiAutomator2 has stopped.
    execFileSync(join(process.env.ANDROID_HOME!, 'platform-tools', 'adb'), [
        '-s', process.env.ANDROID_SERIAL!, 'wait-for-device', 'shell', 'dumpsys', 'battery', 'reset',
    ], {timeout: 15_000, encoding: 'utf8'});
}

export async function pullSharedFile(name: string): Promise<Buffer> {
    assert.equal(name.includes('/'), false, 'Expected a fixture filename');
    const bytes = Buffer.from(await browser.pullFile(`${directory}/${name}`), 'base64');
    writeFileSync(join(reports, name), bytes);
    return bytes;
}

export async function pushSharedFile(name: string, bytes: Buffer) {
    assert.equal(name.includes('/'), false, 'Expected a fixture filename');
    await shell('mkdir', ['-p', directory]);
    await browser.pushFile(`${directory}/${name}`, bytes.toString('base64'));
}

export async function stageHistoricalFixtures() {
    const source = join(root, 'e2e/fixtures/historical-1.7.3');
    const provenance = JSON.parse(readFileSync(join(source, 'historical-1.7.3-provenance.json'), 'utf8'));
    const fixture = JSON.parse(readFileSync(join(root, 'e2e/fixtures.json'), 'utf8'));
    assert.equal(provenance.producerVersion, '1.7.3');
    assert.equal(provenance.producerApkSha256, fixture.historicalApk.sha256);
    const names = ['settings.json', 'alarms.json', 'device.json', 'general.zip', 'history.csv']
        .map(name => `historical-1.7.3-${name}`).sort();
    assert.deepEqual(Object.keys(provenance.sha256).sort(), names);
    for (const name of names) {
        const bytes = readFileSync(join(source, name));
        assert.equal(createHash('sha256').update(bytes).digest('hex'), provenance.sha256[name], `Fixture integrity: ${name}`);
        await pushSharedFile(name, bytes);
    }
    writeFileSync(join(reports, 'historical-fixture-provenance.json'), JSON.stringify(provenance, null, 2));
}

export async function readLivePreferences(file = 'sp_store_main'): Promise<Record<string, unknown>> {
    let xml: string;
    try {
        xml = await shell('run-as', [appId, 'cat', `shared_prefs/${file}.xml`]);
    } catch (error) {
        if (String(error).includes('No such file')) return {};
        throw error;
    }
    const parsed = new XMLParser({ignoreAttributes: false, parseAttributeValue: true}).parse(xml).map ?? {};
    const result: Record<string, unknown> = {};
    for (const [kind, values] of Object.entries(parsed)) {
        for (const value of Array.isArray(values) ? values : [values]) {
            const entry = value as Record<string, unknown>;
            if (entry?.['@_name']) result[String(entry['@_name'])] = kind === 'string' ? entry['#text'] ?? '' : entry['@_value'];
        }
    }
    return result;
}

// Read the real current database without starting instrumentation or opening the updated Activity.
export async function readLogRows(): Promise<(LogRow & {_id: number})[]> {
    const snapshot = join(reports, 'live-observations.db');
    const content = await shell('run-as', [appId, 'base64', 'databases/logs.db']);
    writeFileSync(snapshot, Buffer.from(content.replace(/\s/g, ''), 'base64'));
    rmSync(`${snapshot}-shm`, {force: true});
    try {
        const wal = await shell('run-as', [appId, 'base64', 'databases/logs.db-wal']);
        writeFileSync(`${snapshot}-wal`, Buffer.from(wal.replace(/\s/g, ''), 'base64'));
    } catch (error) {
        if (!String(error).includes('No such file')) throw error;
        rmSync(`${snapshot}-wal`, {force: true});
    }
    const database = new DatabaseSync(snapshot);
    try {
        return database.prepare('SELECT * FROM logs ORDER BY time, _id').all() as unknown as (LogRow & {_id: number})[];
    } finally {
        database.close();
    }
}

export async function captureObservationBoundary(capture: Record<string, string>) {
    const lastLogId = Number(capture.latestLogId);
    assert(Number.isSafeInteger(lastLogId) && lastLogId >= 0, 'Historical capture needs a valid log ID');
    const cutoff = Number((await shell('date', ['+%s'])).trim()) * 1000;
    await pushSharedFile('upgrade-observation-cutoff.txt', Buffer.from(`${cutoff}\n`));
    return {cutoff, lastLogId};
}

export async function persistedObservation({cutoff, lastLogId, shouldRun}: {cutoff: number; lastLogId: number; shouldRun: boolean}) {
    const matchingRows = async () => (await readLogRows()).filter(row =>
        row.time >= cutoff && row._id > lastLogId && (shouldRun ? row.charge === 73 : row.charge !== null));
    if (shouldRun) {
        await browser.waitUntil(async () => {
            const rows = await matchingRows();
            if (rows.length) writeFileSync(join(reports, 'persisted-upgrade-observation.json'), JSON.stringify(rows, null, 2));
            return rows.length > 0;
        }, {timeout: 45_000, interval: 250, timeoutMsg: 'Package replacement did not persist charge73 before opening the updated Activity'});
    } else {
        // A bounded negative observation window uses the framework wait, failing immediately on a row.
        const deadline = Date.now() + 15_000;
        await browser.waitUntil(async () => {
            assert.deepEqual(await matchingRows(), [], 'Explicitly stopped monitoring recorded after upgrade');
            return Date.now() >= deadline;
        }, {timeout: 17_000, interval: 250, timeoutMsg: 'Could not verify stopped monitoring during the observation window'});
        assert(!(await shell('dumpsys', ['activity', 'services', appId])).includes(`${appId}/.monitoring.BatteryInfoService`));
    }
}

export async function verifyHistoricalMonitoringActive() {
    await launchApp();
    await browser.waitUntil(async () => (await shell('dumpsys', ['activity', 'services', appId]))
        .includes(`${appId}/.monitoring.BatteryInfoService`), {timeout: 15_000, timeoutMsg: 'Old monitoring did not resume before upgrade'});
}

export async function recordInstalledVersion(label: string) {
    writeFileSync(join(reports, `${label}-package.txt`), await shell('dumpsys', ['package', appId]));
}

export async function collectDiagnostics(label: string) {
    const destination = join(reports, 'diagnostics', label.replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 180));
    mkdirSync(destination, {recursive: true});
    const captures: [string, () => Promise<unknown>][] = [
        ['screenshot.png', () => browser.saveScreenshot(join(destination, 'screenshot.png'))],
        ['page.xml', () => browser.getPageSource()],
        ['logcat.txt', () => shell('logcat', ['-b', 'all', '-d', '-v', 'threadtime'])],
        ['last-anr.txt', () => shell('dumpsys', ['activity', 'lastanr'])],
        ['memory.txt', () => shell('dumpsys', ['meminfo'])],
        ['package.txt', () => shell('dumpsys', ['package', appId])],
        ['services.txt', () => shell('dumpsys', ['activity', 'services', appId])],
        ['widgets.txt', () => shell('dumpsys', ['appwidget'])],
    ];
    for (const [name, capture] of captures) {
        try {
            const value = await capture();
            if (typeof value === 'string') writeFileSync(join(destination, name), value);
        } catch (error) {
            writeFileSync(join(destination, `${name}.error.txt`), String(error));
        }
    }
}
