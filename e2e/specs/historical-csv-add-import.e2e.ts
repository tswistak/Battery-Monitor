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
import { cleanScenario, readState, seedSentinelData } from '../support/device.js';
import {
    acknowledgeMessage,
    chooseImportFile,
    clickText,
    openBackupAndRestoreScreen,
} from '../support/ui.js';

describe('Historical CSV add import', () => {
    it('adds legacy records while preserving existing history and other data', async () => {
        await cleanScenario();
        await seedSentinelData();
        await openBackupAndRestoreScreen();

        await chooseImportFile('Import logs from CSV', 'historical-1.7.3-history.csv');
        await clickText('Add to existing logs');
        await clickText('Okay');
        await acknowledgeMessage('CSV logs imported successfully');

        const state = await readState();
        // Historical CSV's Discharging label imports as an unplugged observation (100).
        expect(state.logs).toEqual([
            {
                status: 100,
                charge: 72,
                time: 1_700_000_000_000,
                temperature: 287,
                voltage: 4011,
                current: null,
                remaining_charge_uah: null,
            },
            {
                status: 100,
                charge: 71,
                time: 1_700_000_060_000,
                temperature: 291,
                voltage: 4007,
                current: null,
                remaining_charge_uah: null,
            },
            {
                status: 3,
                charge: 33,
                time: 1_710_000_000_000,
                temperature: 299,
                voltage: 3999,
                current: null,
                remaining_charge_uah: null,
            },
        ]);
        expect(state.alarms).toEqual([
            { enabled: false, type: 'temp_rises', threshold: '444' },
        ]);
        expect(state.settings.temperature_unit).toBe('celsius');
        expect(state.predictor.key_ave_recharge_ac).toBe(999_999);
    });
});
