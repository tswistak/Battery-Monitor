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
import {
    cleanScenario,
    readState,
    seedSentinelData,
    setAlarmInsertFailure,
    stageGeneralImportFixture,
} from '../support/device.js';
import {
    chooseImportFile,
    chooseReplaceHistory,
    clickText,
    confirmAllAvailableCategories,
    openBackupAndRestoreScreen,
    requireText,
} from '../support/ui.js';

describe('Partial general backup restore failure', () => {
    let alarmFailureInstalled = false;

    beforeEach(async () => {
        alarmFailureInstalled = false;
        await cleanScenario();
    });

    afterEach(async () => {
        if (alarmFailureInstalled) await setAlarmInsertFailure(false);
    });

    it('reports completed settings and failed alarms while preserving the transaction and skipping later stages', async () => {
        await seedSentinelData();
        await stageGeneralImportFixture();
        const before = await readState();
        alarmFailureInstalled = true;
        await setAlarmInsertFailure(true);

        await openBackupAndRestoreScreen();
        await chooseImportFile('Import general backup', 'prepared-general.zip');
        await confirmAllAvailableCategories();
        await chooseReplaceHistory();
        await requireText(
            'Some data could not be restored. Successfully restored: Settings. '
            + 'Data that may have been partly restored: Alarms. '
            + 'Please check these sections before trying again.',
        );
        await clickText('Okay');

        const after = await readState();
        await expect(after.settings.temperature_unit).toBe('fahrenheit');
        await expect(after.settings.max_log_age).toBe('-1');
        await expect(after.alarms).toEqual([{ enabled: false, type: 'temp_rises', threshold: '444' }]);
        await expect(after.alarms).toEqual(before.alarms);
        await expect(after.logs).toEqual(before.logs);
        await expect(after.logs).toEqual([
            {
                status: 3, charge: 33, time: 1_710_000_000_000,
                temperature: 299, voltage: 3999, current: null, remaining_charge_uah: null,
            },
        ]);
        await expect(after.predictor).toEqual(before.predictor);
    });
});
