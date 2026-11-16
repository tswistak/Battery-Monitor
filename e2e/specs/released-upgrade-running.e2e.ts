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
import {browser, expect} from '@wdio/globals';
import {
    appId, apks, captureObservationBoundary, cleanScenario, helperProbe,
    persistedObservation, readLivePreferences, readState, recordInstalledVersion,
    setBattery, stageHistoricalFixtures,
    verifyHistoricalMonitoringActive,
} from '../support/device.js';
import type {AppState} from '../support/device.js';
import {
    assertCompletedStartupHasNoChangelog, assertUpgradeChangelog, launchApplication,
} from '../support/ui.js';

describe('Released upgrade with active monitoring', function () {
    it('preserves user data and widgets, records before Activity launch, and acknowledges the installed changelog', async function () {
        const {historical, current} = apks();
        const historicalRecords = [
            {status: 3, charge: 72, time: 1_700_000_000_000, temperature: 287,
                voltage: 4_011, current: null, remaining_charge_uah: null},
            {status: 3, charge: 71, time: 1_700_000_060_000, temperature: 291,
                voltage: 4_007, current: null, remaining_charge_uah: null},
        ];

        // 1. Initialize the actual 1.7.3 APK in an independent clean application state.
        await cleanScenario();
        await setBattery({level: 62});
        await browser.installApp(historical.path, {allowTestPackages: true, grantPermissions: true});
        await launchApplication();
        await helperProbe('Widget', 'bind');
        await helperProbe('Storage', 'seedRunning');
        await stageHistoricalFixtures();
        const capture = await helperProbe('Storage', 'capture');
        const boundary = await captureObservationBoundary(capture);

        // 2. Resume the old service after the storage probe ends its target process.
        await verifyHistoricalMonitoringActive();
        await browser.installApp(current.path, {replace: true, allowTestPackages: true});
        await recordInstalledVersion('released-upgrade-running');

        // 3. Read persisted SQLite observations before launching the updated Activity or probe.
        await setBattery({level: 73});
        await persistedObservation({...boundary, shouldRun: true});
        await helperProbe('Widget', 'verify', {expectedLevel: 73});
        await setBattery({level: 74});
        await helperProbe('Widget', 'verifyUpdated', {expectedLevel: 74});
        const preserved = JSON.parse((await helperProbe('Storage', 'verifyRunning')).stateJson) as AppState;
        expect(preserved.monitoringDesired).toBe(true);
        expect(preserved.logs).toEqual(expect.arrayContaining(historicalRecords));

        // 4. The real upgraded startup shows this version's release content and accepts Got it.
        await launchApplication();
        await assertUpgradeChangelog();

        // 5. Acknowledgement survives two full process stops, and migrations keep the data.
        await browser.terminateApp(appId);
        expect((await readLivePreferences()).last_acknowledged_version_code).toBe(current.versionCode);
        await launchApplication();
        await assertCompletedStartupHasNoChangelog();
        await browser.terminateApp(appId);
        expect((await readLivePreferences()).last_acknowledged_version_code).toBe(current.versionCode);
        await launchApplication();
        await assertCompletedStartupHasNoChangelog();
        const restored = await readState();
        expect(restored.monitoringDesired).toBe(true);
        expect(restored.settings).toMatchObject({
            temperature_unit: 'fahrenheit', autostart: 'always', max_log_age: '-1',
            enable_logging: true, icon_content: 'temperature', show_icon_unit: true,
        });
        expect(restored.alarms).toEqual([
            {enabled: true, type: 'charge_rises', threshold: '83'},
            {enabled: false, type: 'charge_drops', threshold: '17'},
        ]);
        expect(restored.predictor).toMatchObject({
            key_ave_recharge_ac: 123_456, key_ave_recharge_wl: 234_567,
            key_ave_recharge_usb: 345_678,
        });
        expect(restored.logs).toEqual(expect.arrayContaining(historicalRecords));
        expect(restored.logs.some(row => row.time >= boundary.cutoff && row.charge === 73)).toBe(true);
    });
});
