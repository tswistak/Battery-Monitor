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
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { browser, expect } from '@wdio/globals';
import { cleanScenario, readState, reports, seedSentinelData, stageGeneralImportFixture } from '../support/device.js';
import {
    chooseImportFile,
    clickText,
    openBackupAndRestoreScreen,
    requireText,
    textSelector,
} from '../support/ui.js';

function saveCancelledImportSnapshot(phase: string, state: unknown) {
    try {
        writeFileSync(join(reports, `cancelled-import-${phase}.json`), JSON.stringify(state, null, 2));
    } catch (error) {
        console.warn(`Could not save cancelled import ${phase} diagnostics:`, error);
    }
}

describe('Cancelled general backup import', () => {
    beforeEach(async () => {
        await cleanScenario();
    });

    it('preserves all data when the category selection is cancelled', async () => {
        const sentinelSettings = {
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
        };
        const sentinelAlarms = [
            { enabled: false, type: 'temp_rises', threshold: '444' },
        ];
        const sentinelLogs = [
            {
                status: 3, charge: 33, time: 1_710_000_000_000,
                temperature: 299, voltage: 3999, current: null,
                remaining_charge_uah: null,
            },
        ];
        const sentinelPredictor = {
            key_ave_discharge: 456_789,
            key_ave_recharge_ac: 999_999,
            key_ave_recharge_wl: 234_567,
            key_ave_recharge_usb: 345_678,
            key_predictor_state_version: 2,
        };
        await seedSentinelData();
        await stageGeneralImportFixture();
        // Opening Settings persists missing defaults before the cancellation baseline is captured.
        await openBackupAndRestoreScreen();
        const before = await readState();
        saveCancelledImportSnapshot('initialized-before', before);
        await expect(before.settings).toMatchObject(sentinelSettings);
        await expect(before.alarms).toEqual(sentinelAlarms);
        await expect(before.logs).toEqual(sentinelLogs);
        await expect(before.predictor).toMatchObject(sentinelPredictor);
        await expect(before.monitoringDesired).toBe(false);
        // The instrumentation snapshot stops the Activity, so reopen it before exercising import.
        await openBackupAndRestoreScreen();

        await chooseImportFile('Import general backup', 'prepared-general.zip');
        await requireText('Choose what you want to restore.');
        await clickText('Cancel');

        await expect(browser.$(textSelector('Choose what you want to restore.'))).not.toBeDisplayed();
        const importAction = await requireText('Import general backup');
        await expect(importAction).toBeEnabled();

        const after = await readState();
        saveCancelledImportSnapshot('cancelled-after', after);
        await expect(after.settings).toEqual(before.settings);
        await expect(after.alarms).toEqual(before.alarms);
        await expect(after.logs).toEqual(before.logs);
        await expect(after.predictor).toEqual(before.predictor);
        await expect(after.settings).toMatchObject(sentinelSettings);
        await expect(after.alarms).toEqual(sentinelAlarms);
        await expect(after.logs).toEqual(sentinelLogs);
        await expect(after.predictor).toMatchObject(sentinelPredictor);
        await expect(after.monitoringDesired).toBe(false);
    });
});
