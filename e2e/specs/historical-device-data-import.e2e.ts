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
    chooseReplaceHistory,
    confirmAllAvailableCategories,
    openBackupAndRestoreScreen,
} from '../support/ui.js';

describe('Historical device data import', () => {
    it('restores legacy history and predictor while preserving settings and alarms', async () => {
        await cleanScenario();
        await seedSentinelData();
        await openBackupAndRestoreScreen();

        await chooseImportFile('Import device-specific data', 'historical-1.7.3-device.json');
        await confirmAllAvailableCategories();
        await chooseReplaceHistory();
        await acknowledgeMessage('Device-specific data imported successfully');

        const state = await readState();
        expect(state.logs).toEqual([
            {
                status: 3,
                charge: 72,
                time: 1_700_000_000_000,
                temperature: 287,
                voltage: 4011,
                current: null,
                remaining_charge_uah: null,
            },
            {
                status: 3,
                charge: 71,
                time: 1_700_000_060_000,
                temperature: 291,
                voltage: 4007,
                current: null,
                remaining_charge_uah: null,
            },
        ]);
        expect(state.predictor).toMatchObject({
            key_ave_recharge_ac: 123_456,
            key_ave_recharge_wl: 234_567,
            key_ave_recharge_usb: 345_678,
        });
        expect(state.settings.temperature_unit).toBe('celsius');
        expect(state.alarms).toEqual([
            { enabled: false, type: 'temp_rises', threshold: '444' },
        ]);
    });
});
