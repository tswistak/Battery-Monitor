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
import { browser, expect } from '@wdio/globals';
import {
    appId,
    cleanScenario,
    readLogRows,
    persistedObservation,
    readLivePreferences,
    shell,
    readState,
    resetBattery,
    setBattery,
    startMonitoringForAlarm,
    stopMonitoring,
} from '../support/device.js';
import {
    clickText,
    completeOnboarding,
    launchApplication,
    acknowledgeMessage,
    chooseImportFile,
    openBackupAndRestoreScreen,
    requireText,
} from '../support/ui.js';

type NotificationSnapshot = {
    statusBarNotifications: Array<{
        key: string;
        packageName: string;
        isRemoved: boolean;
        notification: { title: string | null };
    }>;
};

const alarmTitle = 'Battery charge reached or rose above 83%';

async function activeRisingAlarms(title = alarmTitle) {
    const notifications = await browser.execute('mobile: getNotifications') as NotificationSnapshot;
    expect(Array.isArray(notifications.statusBarNotifications)).toBe(true);
    const latestByKey = new Map<string, NotificationSnapshot['statusBarNotifications'][number]>();
    // Appium returns newest events first, including removed events and updates of the same notification.
    for (const notification of notifications.statusBarNotifications) {
        expect(typeof notification.key).toBe('string');
        if (!latestByKey.has(notification.key)) latestByKey.set(notification.key, notification);
    }
    return [...latestByKey.values()].filter((notification) =>
        notification.packageName === appId && !notification.isRemoved &&
        notification.notification.title === title,
    );
}

describe('Imported alarm threshold crossing', () => {
    afterEach(async () => {
        try {
            if (await browser.isAppInstalled(appId)) await stopMonitoring();
        } finally {
            await resetBattery();
        }
    });

    it('does not fire beyond an imported threshold, then fires after a new crossing and opens Current state', async () => {
        await cleanScenario();
        await setBattery({ level: 84, ac: 1, status: 2 });
        await startMonitoringForAlarm();

        await browser.waitUntil(async () =>
            (await readLogRows()).some((record) => record.charge === 84), {
            timeout: 20_000,
            timeoutMsg: 'The initial controlled 84% observation was not persisted',
        });
        await openBackupAndRestoreScreen();
        await chooseImportFile('Import alarms', 'historical-1.7.3-alarms.json');

        await acknowledgeMessage('Alarms imported successfully.');
        expect(await activeRisingAlarms()).toHaveLength(0);

        await setBattery({ level: 82, ac: 1, status: 2 });
        await browser.waitUntil(async () =>
            (await readLogRows()).some((record) => record.charge === 82), {
            timeout: 20_000,
            timeoutMsg: 'The controlled 82% baseline was not persisted',
        });
        expect(await activeRisingAlarms()).toHaveLength(0);

        await setBattery({ level: 84, ac: 1, status: 2 });
        await browser.waitUntil(async () => (await activeRisingAlarms()).length > 0, {
            timeout: 20_000,
            timeoutMsg: 'The imported 83% alarm did not fire after the real 82% to 84% crossing',
        });
        expect(await activeRisingAlarms()).toHaveLength(1);
        await browser.execute('mobile: openNotifications');
        await (await requireText(alarmTitle)).click();
        await browser.waitUntil(async () => await browser.getCurrentPackage() === appId, {
            timeout: 20_000,
            timeoutMsg: 'Tapping the real alarm notification did not open Battery Monitor',
        });
        await browser.$('~Open menu').click();
        await expect(browser.$(
            '//*[@checkable="true" and @checked="true" and .//*[@text="Current state"]]',
        )).toBeDisplayed();

        const restored = await readState();
        expect(restored.alarms).toEqual([
            { enabled: true, type: 'charge_rises', threshold: '83' },
            { enabled: false, type: 'charge_drops', threshold: '17' },
        ]);

        const recoveryLabel = 'Dismiss low battery alarm after recovery';
        const recoverySelector = 'android=new UiSelector().text('
            + JSON.stringify(recoveryLabel) + ')';
        const settingsFile = 'codes.swistak.batterymonitor_preferences';
        async function revealRecoveryOption() {
            // Compose's scroll events stop early here; drag the real list to its options footer.
            for (let pages = 0; pages < 8 && !await browser.$(recoverySelector).isDisplayed(); pages++) {
                const list = await browser.$('android=new UiSelector().scrollable(true)');
                await browser.execute('mobile: swipeGesture', {
                    elementId: list.elementId, direction: 'up', percent: 0.85,
                });
            }
            await requireText(recoveryLabel);
        }
        await launchApplication('alarms');
        await completeOnboarding();
        const lowRule = 'new UiSelector().checkable(true).childSelector(new UiSelector().description("When charge falls to 17 %"))';
        await browser.$(`android=new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(${lowRule})`);
        await browser.$(`android=${lowRule}`).click();
        await browser.$(`android=${lowRule}`).waitForEnabled({ timeout: 20_000 });
        const enabledAlarms = (await readState()).alarms;
        expect(enabledAlarms).toEqual([
            { enabled: true, type: 'charge_rises', threshold: '83' },
            { enabled: true, type: 'charge_drops', threshold: '17' },
        ]);
        await launchApplication('alarms');
        await completeOnboarding();
        await revealRecoveryOption();
        expect((await readLivePreferences(settingsFile)).dismiss_low_battery_on_recovery ?? false).toBe(false);
        const monitorProcess = (await shell('pidof', [`${appId}.BIS`])).trim();
        expect(monitorProcess).not.toBe('');
        await clickText(recoveryLabel);
        await browser.waitUntil(async () =>
            (await readLivePreferences(settingsFile)).dismiss_low_battery_on_recovery === true,
        { timeout: 20_000, timeoutMsg: 'Alarms did not persist the recovery option' });

        await setBattery({ level: 18 });
        await browser.waitUntil(async () => (await readLogRows()).some(row => row.charge === 18),
            { timeout: 20_000, timeoutMsg: 'The low-battery baseline was not observed' });
        await setBattery({ level: 16 });
        const lowTitle = 'Battery charge reached or fell below 17%';
        await browser.waitUntil(async () => (await activeRisingAlarms(lowTitle)).length === 1,
            { timeout: 20_000, timeoutMsg: 'The enabled 17% alarm did not fire after charge fell to 16%' });
        await setBattery({ level: 18 });
        await browser.waitUntil(async () => (await activeRisingAlarms(lowTitle)).length === 0,
            { timeout: 20_000, timeoutMsg: 'The running monitor did not dismiss the recovered low-battery notification' });
        expect((await shell('pidof', [`${appId}.BIS`])).trim()).toBe(monitorProcess);
        // The instrumentation snapshot closes the Activity, exercising saved state on the next opening.
        expect((await readState()).alarms).toEqual(enabledAlarms);
        await launchApplication('alarms');
        await completeOnboarding();
        await revealRecoveryOption();
        await browser.$(recoverySelector).waitForDisplayed({ timeout: 20_000 });
        expect((await readLivePreferences(settingsFile)).dismiss_low_battery_on_recovery).toBe(true);

        await stopMonitoring();
        const stoppedRows = await readLogRows();
        await launchApplication('alarms');
        await completeOnboarding();
        await revealRecoveryOption();
        // Toggling to false after recreation also verifies the row restored its saved true state.
        await clickText(recoveryLabel);
        await browser.waitUntil(async () =>
            (await readLivePreferences(settingsFile)).dismiss_low_battery_on_recovery === false,
        { timeout: 20_000, timeoutMsg: 'The stopped-monitor recovery option did not persist' });
        await persistedObservation({
            cutoff: Date.now(), lastLogId: Math.max(0, ...stoppedRows.map(row => row._id)), shouldRun: false,
        });
        expect((await shell('dumpsys', ['activity', 'services', appId]))
            .includes(`${appId}/.monitoring.BatteryInfoService`)).toBe(false);
        const stopped = await readState();
        expect(stopped.monitoringDesired).toBe(false);
        expect(stopped.alarms).toEqual(enabledAlarms);
    });
});
