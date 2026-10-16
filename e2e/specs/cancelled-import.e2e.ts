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
import { cleanScenario, readState, seedBackupData, seedSentinelData } from '../support/device.js';
import {
    acknowledgeMessage,
    chooseImportFile,
    clickText,
    openBackupAndRestoreScreen,
    requireText,
    saveBackupThroughFilePicker,
} from '../support/ui.js';

describe('Cancelled general backup import', () => {
    beforeEach(async () => {
        await cleanScenario();
    });

    it('preserves all data when the category selection is cancelled', async () => {
        await seedBackupData();
        await openBackupAndRestoreScreen();
        await clickText('Export general backup');
        await saveBackupThroughFilePicker('current-general.zip');
        await acknowledgeMessage('General backup exported successfully');
        await seedSentinelData();
        const before = await readState();
        await openBackupAndRestoreScreen();

        await chooseImportFile('Import general backup', 'current-general.zip');
        await requireText('Choose what you want to restore.');
        await clickText('Cancel');

        const after = await readState();
        await expect(after.settings).toEqual(before.settings);
        await expect(after.alarms).toEqual(before.alarms);
        await expect(after.logs).toEqual(before.logs);
        await expect(after.predictor).toEqual(before.predictor);
        await expect(after.settings).toMatchObject({
            temperature_unit: 'celsius',
            autostart: 'never',
            enable_logging: false,
            max_log_age: '24',
            icon_content: 'percentage',
            show_icon_unit: false,
            ui_brightness: 'Dark',
            charging_target_mode: 'custom',
            custom_charging_target: 87,
            discharging_target: 13,
            battery_current_multiplier: '1',
        });
        await expect(after.alarms).toEqual([
            { enabled: false, type: 'temp_rises', threshold: '444' },
        ]);
        await expect(after.logs).toEqual([
            {
                status: 3, charge: 33, time: 1_710_000_000_000,
                temperature: 299, voltage: 3999, current: null,
                remaining_charge_uah: null,
            },
        ]);
        await expect(after.predictor).toMatchObject({
            key_ave_discharge: 456_789,
            key_ave_recharge_ac: 999_999,
            key_ave_recharge_wl: 234_567,
            key_ave_recharge_usb: 345_678,
            key_predictor_state_version: 2,
        });
    });
});
