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
import { cleanInstallCurrent, forceStopApp, readLivePreferences } from '../support/device.js';
import { assertCompletedStartupHasNoChangelog, launchApplication } from '../support/ui.js';

describe('Changelog on a fresh installation', () => {
    it('finishes real onboarding without a changelog and keeps it absent after two process restarts', async () => {
        await cleanInstallCurrent();
        const emptyMainPreferences = await readLivePreferences();
        expect(emptyMainPreferences.first_run).toBeUndefined();

        await launchApplication();
        await assertCompletedStartupHasNoChangelog();

        await forceStopApp();
        await launchApplication();
        await assertCompletedStartupHasNoChangelog();

        await forceStopApp();
        await launchApplication();
        await assertCompletedStartupHasNoChangelog();
    });
});
