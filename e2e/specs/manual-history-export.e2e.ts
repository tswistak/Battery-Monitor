/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>.
*/
import { browser, expect } from '@wdio/globals';
import {
    appId,
    cleanScenario,
    pullSharedFile,
    pushSharedFile,
    readLivePreferences,
    seedBackupData,
} from '../support/device.js';
import {
    chooseImportFile,
    clickText,
    completeOnboarding,
    launchApplication,
    requireText,
    saveBackupThroughFilePicker,
} from '../support/ui.js';

const settingsFile = 'codes.swistak.batterymonitor_preferences';
const oldRecord = {
    status: 3, charge: 62, time: 1_600_000_000_000, temperature: null, voltage: null,
    currentMicroAmps: null, remainingChargeMicroampHours: null,
};
const currentRecords = [
    {
        status: 3, charge: 72, time: 1_700_000_000_000, temperature: 287, voltage: 4011,
        currentMicroAmps: -120_000, remainingChargeMicroampHours: 2_100_000,
    },
    {
        status: 3, charge: 71, time: 1_700_000_060_000, temperature: 291, voltage: 4007,
        currentMicroAmps: -140_000, remainingChargeMicroampHours: 2_070_000,
    },
];
const csvHeader = 'Date,Time,Status,Charge,Temperature,Temperature F,Voltage,Current (µA),Remaining charge (µAh),Power (W)';
const oldCsvRow = '9/13/20,2:26:40 PM,Discharging,62,,,,,,';

async function openExport(format: string) {
    await browser.$('~More actions').click();
    await clickText('Export logs');
    await requireText('Export format');
    await clickText(format);
}

async function exported(previousWatermark?: number) {
    // The action becomes enabled when the export finishes; file assertions below verify its result.
    await browser.$('~More actions').waitForEnabled({ timeout: 20_000 });
    if (previousWatermark !== undefined) await browser.waitUntil(async () =>
        await watermark() > previousWatermark,
    { timeout: 20_000, timeoutMsg: 'The completed export did not advance its manual watermark' });
}

async function watermark() {
    return Number((await readLivePreferences(settingsFile)).last_log_export_time ?? 0);
}

function csvRows(bytes: Buffer) {
    return bytes.toString('utf8').trimEnd().split(/\r?\n/).slice(1).map(row => row.split(','));
}

describe('Manual history export format and scope', () => {
    for (const format of ['CSV', 'JSON']) {
        it(`uses ${format} for append and every new-file scope through DocumentsUI`, async () => {
            await cleanScenario();
            await seedBackupData();
            const filename = `manual-append.${format.toLowerCase()}`;
            const metadata = { note: 'older history retained outside the current database' };
            const predictor = { averageDischarge: 456_789, stateVersion: 2 };
            const original = Buffer.from(format === 'JSON'
                ? JSON.stringify({ version: 2, logs: [oldRecord], predictor, metadata })
                : `${csvHeader}\r\n${oldCsvRow}\r\n`);
            await pushSharedFile(filename, original);
            await launchApplication('history');
            await completeOnboarding();
            const before = await watermark();

            await openExport(format);
            await clickText('Everything');
            await browser.waitUntil(async () => ['com.android.documentsui', 'com.google.android.documentsui']
                .includes(await browser.getCurrentPackage()), { timeout: 20_000 });
            if (await browser.isKeyboardShown()) {
                await browser.hideKeyboard();
                await browser.waitUntil(async () => !await browser.isKeyboardShown(), { timeout: 20_000 });
            }
            // Back first leaves nested fixture folders; exit the picker without saving.
            let parentFolders = 0;
            while (['com.android.documentsui', 'com.google.android.documentsui'].includes(await browser.getCurrentPackage())) {
                expect(parentFolders++).toBeLessThan(4);
                await browser.back();
            }
            expect(await browser.getCurrentPackage()).toBe(appId);
            expect(await watermark()).toBe(before);

            await openExport(format);
            await chooseImportFile('Append to an existing file', filename);
            await exported(before);
            expect(await watermark()).toBeGreaterThan(before);
            const appended = await pullSharedFile(filename);
            if (format === 'JSON') {
                const document = JSON.parse(appended.toString('utf8'));
                expect(document.version).toBe(2);
                expect(document.logs).toEqual([oldRecord, ...currentRecords]);
                expect(document.predictor).toEqual(predictor);
                expect(document.metadata).toEqual(metadata);
            } else {
                expect(appended.toString('utf8').startsWith(`${csvHeader}\r\n${oldCsvRow}\r\n`)).toBe(true);
                const rows = csvRows(appended);
                expect(rows).toHaveLength(3);
                expect(rows.slice(1).map(row => row.slice(3, 9))).toEqual([
                    ['72', '28.7', '83.7', '4.011', '-120000', '2100000'],
                    ['71', '29.1', '84.4', '4.007', '-140000', '2070000'],
                ]);
                expect(Number(rows[1][9])).toBeCloseTo(-0.48132, 8);
                expect(Number(rows[2][9])).toBeCloseTo(-0.56098, 8);
            }

            const beforeRepeat = await watermark();
            await openExport(format);
            await chooseImportFile('Append to an existing file', filename);
            await exported(beforeRepeat);
            expect(await pullSharedFile(filename)).toEqual(appended);

            // The default selected range is the last 24 hours; seeded records are historical.
            for (const [action, count] of [
                ['Export selected range', 0], ['Everything', 2], ['From last export', 0],
            ] as const) {
                const output = `manual-${action.replaceAll(' ', '-').toLowerCase()}.${format.toLowerCase()}`;
                const beforeScope = await watermark();
                await openExport(format);
                await clickText(action);
                await saveBackupThroughFilePicker(output);
                await exported(action === 'Export selected range' ? undefined : beforeScope);
                if (action === 'Export selected range') expect(await watermark()).toBe(beforeScope);
                else expect(await watermark()).toBeGreaterThan(beforeScope);
                const bytes = await pullSharedFile(output);
                if (format === 'JSON') {
                    const document = JSON.parse(bytes.toString('utf8'));
                    expect(document.version).toBe(2);
                    expect(document.logs).toEqual(count ? currentRecords : []);
                } else {
                    expect(bytes.toString('utf8').split(/\r?\n/)[0]).toContain('µA');
                    expect(csvRows(bytes)).toHaveLength(count);
                    if (count) expect(csvRows(bytes).map(row => row[3])).toEqual(['72', '71']);
                }
            }
        });
    }
});
