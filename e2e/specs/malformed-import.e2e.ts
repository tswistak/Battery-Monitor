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
import { cleanScenario, pushSharedFile, readState, seedSentinelData } from '../support/device.js';
import {
    acknowledgeMessage,
    chooseImportFile,
    openBackupAndRestoreScreen,
} from '../support/ui.js';

describe('Malformed general backup import', () => {
    beforeEach(async () => {
        await cleanScenario();
    });

    it('shows validation failure without changing any existing category', async () => {
        await seedSentinelData();
        await pushSharedFile('invalid-general.zip', Buffer.from('This is not a ZIP archive.'));
        await openBackupAndRestoreScreen();
        const before = await readState();
        await openBackupAndRestoreScreen();

        await chooseImportFile('Import general backup', 'invalid-general.zip');
        await acknowledgeMessage('Invalid or incompatible general backup file');

        const after = await readState();
        await expect(after.settings).toEqual(before.settings);
        await expect(after.alarms).toEqual(before.alarms);
        await expect(after.logs).toEqual(before.logs);
        await expect(after.predictor).toEqual(before.predictor);
        await expect(after.predictor.key_ave_recharge_ac).toBe(999_999);
        await expect(after.alarms).toEqual([{ enabled: false, type: 'temp_rises', threshold: '444' }]);
        await expect(after.logs).toEqual([
            {
                status: 3, charge: 33, time: 1_710_000_000_000,
                temperature: 299, voltage: 3999, current: null, remaining_charge_uah: null,
            },
        ]);
    });
});
