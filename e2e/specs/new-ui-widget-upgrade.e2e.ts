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
import assert from 'node:assert/strict';
import {
    appId, apks, cleanScenario, helperProbe, readLivePreferences, recordInstalledVersion,
    setBattery,
} from '../support/device.js';
import {
    assertCompletedStartupHasNoChangelog, assertUpgradeChangelog, completeOnboarding,
    launchApplication,
} from '../support/ui.js';

describe('Upgrade between genuine redesigned UI versions', function () {
    it('preserves two independent Status widgets and settings across a genuine prior new-UI upgrade', async function () {
        const {newUiBaseline, current} = apks();
        assert(newUiBaseline, 'A genuine pinned prior new-UI APK is required; current=current is not cross-version coverage.');

        // 1. Preparation validates the pinned payload, signature and ordered version codes.
        await cleanScenario();
        await setBattery({level: 62});
        await browser.installApp(newUiBaseline.path, {allowTestPackages: true, grantPermissions: true});
        await launchApplication();
        await completeOnboarding({verifyFirstRunPreference: false});
        await helperProbe('Widget', 'bindNew');
        await helperProbe('Storage', 'seedNewUi');
        await helperProbe('Storage', 'capture');

        // 2. Resume the real baseline before the genuine in-place replacement.
        await launchApplication();
        await browser.installApp(current.path, {replace: true, allowTestPackages: true});
        await recordInstalledVersion('new-ui-widget-upgrade');

        // 3. Original widget IDs retain their providers and distinct effective configurations.
        await helperProbe('Widget', 'verifyNew', {expectedLevel: 62});
        await helperProbe('Storage', 'verifyNewUi');
        await launchApplication();
        await assertUpgradeChangelog();
        await setBattery({level: 74});
        await helperProbe('Widget', 'verifyNew', {expectedLevel: 74});

        // 4. A new process keeps each widget's choices and the application's new UI settings.
        await browser.terminateApp(appId);
        expect((await readLivePreferences()).last_acknowledged_version_code).toBe(current.versionCode);
        await launchApplication();
        await assertCompletedStartupHasNoChangelog();
        await helperProbe('Storage', 'verifyNewUi');
    });
});
