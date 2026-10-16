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
import { expect } from '@wdio/globals';
import AdmZip from 'adm-zip';
import {
    cleanScenario,
    clearAppData,
    forceStopApp,
    pullSharedFile,
    readState,
    seedBackupData,
    stopMonitoring,
} from '../support/device.js';
import {
    acknowledgeMessage,
    chooseImportFile,
    chooseReplaceHistory,
    clickText,
    confirmAllAvailableCategories,
    openBackupAndRestoreScreen,
    saveBackupThroughFilePicker,
} from '../support/ui.js';

describe('Complete general backup round trip', () => {
    beforeEach(async () => {
        await cleanScenario();
    });

    it('restores independently expected settings, alarms, history and predictor data after clearing the app', async () => {
        const expectedSettings = {
            temperature_unit: 'fahrenheit',
            autostart: 'never',
            enable_logging: false,
            max_log_age: '-1',
            ui_brightness: 'Dark',
            charging_target_mode: 'custom',
            custom_charging_target: 87,
            discharging_target: 13,
            battery_current_multiplier: '1',
        };
        const expectedAlarms = [
            { enabled: true, type: 'charge_rises', threshold: '83' },
            { enabled: false, type: 'charge_drops', threshold: '17' },
        ];
        const expectedLogs = [
            {
                status: 3, charge: 72, time: 1_700_000_000_000,
                temperature: 287, voltage: 4011, current: -120_000,
                remaining_charge_uah: 2_100_000,
            },
            {
                status: 3, charge: 71, time: 1_700_000_060_000,
                temperature: 291, voltage: 4007, current: -140_000,
                remaining_charge_uah: 2_070_000,
            },
        ];
        const expectedPredictor = {
            key_ave_discharge: 456_789,
            key_ave_recharge_ac: 123_456,
            key_ave_recharge_wl: 234_567,
            key_ave_recharge_usb: 345_678,
            key_predictor_state_version: 2,
        };
        await seedBackupData();
        await openBackupAndRestoreScreen();

        await clickText('Export general backup');
        await saveBackupThroughFilePicker('current-general.zip');
        await acknowledgeMessage('General backup exported successfully');

        const bytes = await pullSharedFile('current-general.zip');
        await expect(bytes.length).toBeGreaterThan(0);
        const archive = new AdmZip(bytes);
        await expect(archive.getEntries().map(entry => entry.entryName).sort()).toEqual([
            'alarms.json', 'device-specific.json', 'metadata.json', 'settings.json',
        ]);
        await expect(JSON.parse(archive.readAsText('metadata.json'))).toEqual({
            version: 1,
            files: ['settings.json', 'alarms.json', 'device-specific.json'],
        });
        await expect(JSON.parse(archive.readAsText('settings.json')).settings).toMatchObject(expectedSettings);
        await expect(JSON.parse(archive.readAsText('alarms.json')).alarms).toEqual(expectedAlarms);
        const data = JSON.parse(archive.readAsText('device-specific.json'));
        await expect(data.version).toBe(2);
        await expect(data.logs).toEqual([
            {
                status: 3, charge: 72, time: 1_700_000_000_000,
                temperature: 287, voltage: 4011, currentMicroAmps: -120_000,
                remainingChargeMicroampHours: 2_100_000,
            },
            {
                status: 3, charge: 71, time: 1_700_000_060_000,
                temperature: 291, voltage: 4007, currentMicroAmps: -140_000,
                remainingChargeMicroampHours: 2_070_000,
            },
        ]);
        await expect(data.predictor).toEqual({
            averageDischarge: 456_789,
            averageRechargeAc: 123_456,
            averageRechargeWireless: 234_567,
            averageRechargeUsb: 345_678,
            stateVersion: 2,
        });

        await clearAppData();
        const cleared = await readState();
        await expect(cleared.alarms).toEqual([]);
        await expect(cleared.logs).toEqual([]);
        await expect(await pullSharedFile('current-general.zip')).toEqual(bytes);
        await stopMonitoring();
        await openBackupAndRestoreScreen();
        await chooseImportFile('Import general backup', 'current-general.zip');
        await confirmAllAvailableCategories();
        await chooseReplaceHistory();
        await acknowledgeMessage('General backup imported successfully');

        const restored = await readState();
        await expect(restored.settings).toMatchObject(expectedSettings);
        await expect(restored.alarms).toEqual(expectedAlarms);
        await expect(restored.logs).toEqual(expectedLogs);
        await expect(restored.predictor).toMatchObject(expectedPredictor);

        await forceStopApp();
        await openBackupAndRestoreScreen();
        const restarted = await readState();
        await expect(restarted.settings).toMatchObject(expectedSettings);
        await expect(restarted.alarms).toEqual(expectedAlarms);
        await expect(restarted.logs).toEqual(expectedLogs);
        await expect(restarted.predictor).toMatchObject(expectedPredictor);
    });
});
