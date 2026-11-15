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
import { cleanInstallCurrent, clearAppData, helperProbe, readState, setBattery } from '../support/device.js';
import {
    acknowledgeMessage,
    chooseImportFile,
    clickText,
    openBackupAndRestoreScreen,
    saveBackupThroughFilePicker,
} from '../support/ui.js';

function normalize(value: unknown) {
    // Membership preferences are stored as sets; display order is stored separately.
    return Array.isArray(value) ? [...value].sort() : value;
}

describe('Standalone settings backup round trip', () => {
    it('restores every portable setting from a non-default value after clearing app data', async () => {
        await cleanInstallCurrent();
        const { defaultsJson } = await helperProbe('Storage', 'settingsDefaults');
        const defaults: Record<string, unknown> = JSON.parse(defaultsJson);
        const expected = {
            temperature_unit: defaults.temperature_unit === 'fahrenheit' ? 'celsius' : 'fahrenheit',
            long_duration_format: 'hours_only',
            autostart: 'never',
            show_remaining_charge: false,
            battery_current_multiplier: '-1000',
            prefer_average_battery_current: true,
            prediction_type: '300000',
            charging_target_mode: 'custom',
            custom_charging_target: 87,
            discharging_target: 13,
            dismiss_low_battery_on_recovery: true,
            top_line: 'since',
            bottom_line: 'remaining',
            time_remaining_verbosity: 'verbose',
            expanded_notification_details: false,
            icon_content: 'temperature',
            show_icon_unit: false,
            indicate_charging: false,
            live_update_display: 'always',
            live_update_keep_main_notification: true,
            expanded_live_update_details: true,
            chip_switching_interval: '10',
            chip_indicate_charging: false,
            use_privileged_access: true,
            enable_logging: false,
            history_sampling_mode: 'keep_cpu_awake',
            chart_gap_threshold_minutes: '10',
            max_log_age: '-1',
            ui_color_source: 'BatteryBlue',
            ui_brightness: 'TrueBlack',
            vital_signs_content: ['charge', 'current', 'status_duration'],
            vital_signs_order: 'status_duration,charge,current,voltage,temperature,health',
            chip_content: ['charge', 'current'],
            chip_content_order: 'charge,current,voltage,temperature,percentage',
        };
        assert.deepEqual(Object.keys(expected).sort(), Object.keys(defaults).sort(),
            'The fixture must include every portable setting from the app');
        for (const [key, value] of Object.entries(expected)) {
            assert.notDeepEqual(normalize(value), normalize(defaults[key]), `Fixture uses the default for ${key}`);
        }
        await helperProbe('Storage', 'seedPortableSettings', {
            fixture: Buffer.from(JSON.stringify(expected)).toString('base64url'),
        });

        const filename = 'current-settings.json';
        await openBackupAndRestoreScreen();
        await clickText('Export settings');
        await saveBackupThroughFilePicker(filename);
        await acknowledgeMessage('Settings exported successfully.');

        await clearAppData();
        const cleared = (await readState()).settings;
        for (const key of Object.keys(expected)) {
            assert.deepEqual(normalize(cleared[key] ?? defaults[key]), normalize(defaults[key]),
                `Clearing app data did not restore the default for ${key}`);
        }

        // A full battery prevents startup from auto-detecting the multiplier before import.
        await setBattery({ level: 100, ac: 1, status: 5 });
        await openBackupAndRestoreScreen();
        await chooseImportFile('Import settings', filename);
        await acknowledgeMessage('Settings imported successfully.');

        const restored = (await readState()).settings;
        for (const [key, value] of Object.entries(expected)) {
            assert.deepEqual(normalize(restored[key]), normalize(value), `Did not restore ${key}`);
        }
    });
});
