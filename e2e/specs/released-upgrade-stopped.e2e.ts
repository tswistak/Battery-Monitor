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
    persistedObservation, readLivePreferences, readState, recordInstalledVersion, setBattery,
} from '../support/device.js';
import type {AppState} from '../support/device.js';
import {
    assertCompletedStartupHasNoChangelog, assertUpgradeChangelog, launchApplication,
} from '../support/ui.js';

describe('Released upgrade with explicitly stopped monitoring', function () {
    it('preserves the stop choice and data before launch and after two acknowledged startup restarts', async function () {
        const {historical, current} = apks();
        const historicalRecords = [
            {status: 3, charge: 72, time: 1_700_000_000_000, temperature: 287,
                voltage: 4_011, current: null, remaining_charge_uah: null},
            {status: 3, charge: 71, time: 1_700_000_060_000, temperature: 291,
                voltage: 4_007, current: null, remaining_charge_uah: null},
        ];

        // 1. Seed independent 1.7.3 data and an explicit stop with auto autostart.
        await cleanScenario();
        await setBattery({level: 62});
        await browser.installApp(historical.path, {allowTestPackages: true, grantPermissions: true});
        await launchApplication();
        await helperProbe('Widget', 'bind');
        await helperProbe('Storage', 'seedStopped');
        const capture = await helperProbe('Storage', 'capture');
        const boundary = await captureObservationBoundary(capture);

        // 2. Replace the installed APK without clearing data or uninstalling on failure.
        await browser.installApp(current.path, {replace: true, allowTestPackages: true});
        await recordInstalledVersion('released-upgrade-stopped');

        // 3. Package replacement must leave monitoring stopped before any updated Activity opens.
        await setBattery({level: 73});
        await persistedObservation({...boundary, shouldRun: false});
        await helperProbe('Widget', 'verifyStopped');
        const preserved = JSON.parse((await helperProbe('Storage', 'verifyStopped')).stateJson) as AppState;
        expect(preserved.monitoringDesired).toBe(false);
        expect(preserved.logs.filter(row => row.status !== -1)).toEqual(historicalRecords);
        expect(preserved.logs.filter(row => row.status === -1).every(row =>
            row.time >= boundary.cutoff && row.charge === null && row.temperature === null &&
            row.voltage === null && row.current === null && row.remaining_charge_uah === null,
        )).toBe(true);

        // 4. Show and acknowledge the actual installed release notes through startup UI.
        await launchApplication();
        await assertUpgradeChangelog();

        // 5. Process death must preserve acknowledgement, settings and the explicit stop.
        await browser.terminateApp(appId);
        expect((await readLivePreferences()).last_acknowledged_version_code).toBe(current.versionCode);
        await launchApplication();
        await assertCompletedStartupHasNoChangelog();
        await browser.terminateApp(appId);
        expect((await readLivePreferences()).last_acknowledged_version_code).toBe(current.versionCode);
        await launchApplication();
        await assertCompletedStartupHasNoChangelog();
        const restored = await readState();
        expect(restored.monitoringDesired).toBe(false);
        expect(restored.settings).toMatchObject({
            temperature_unit: 'fahrenheit', autostart: 'auto', max_log_age: '-1',
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
        expect(restored.logs.filter(row => row.status !== -1)).toEqual(historicalRecords);
        expect(restored.logs.filter(row => row.status === -1).every(row =>
            row.time >= boundary.cutoff && row.charge === null && row.temperature === null &&
            row.voltage === null && row.current === null && row.remaining_charge_uah === null,
        )).toBe(true);
        await persistedObservation({...boundary, shouldRun: false});
    });
});
