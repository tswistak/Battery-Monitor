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
    readState,
    resetBattery,
    setBattery,
    startMonitoringForAlarm,
    stopMonitoring,
} from '../support/device.js';
import {
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

async function activeRisingAlarms() {
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
        notification.notification.title === alarmTitle,
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
        await acknowledgeMessage('Alarms imported successfully');
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
    });
});
