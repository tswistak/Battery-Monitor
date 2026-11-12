import {browser, expect} from '@wdio/globals';
import {
    appId, cleanScenario, readLivePreferences, readLogRows, resetBattery, setBattery, shell,
    startMonitoringForAlarm, stopMonitoring,
} from '../support/device.js';
import {completeOnboarding, launchApplication, requireText} from '../support/ui.js';

describe('Charging-limit owner lifecycle', () => {
    afterEach(async () => {
        try {
            if (await browser.isAppInstalled(appId)) await stopMonitoring();
        } finally {
            await resetBattery();
        }
    });

    it('keeps recording after owner death and reads targets with monitoring stopped', async () => {
        await cleanScenario();
        await setBattery({level: 61, ac: 1, status: 2});
        await startMonitoringForAlarm();
        await completeOnboarding();
        await browser.waitUntil(async () => (await readLogRows()).some(row => row.charge === 61), {
            timeout: 20_000, timeoutMsg: 'Monitoring did not record the initial battery sample',
        });
        const monitor = (await shell('pidof', [`${appId}.BIS`])).trim();
        const owner = (await shell('pidof', [appId])).trim();
        expect(monitor).toMatch(/^\d+$/);
        expect(owner).toMatch(/^\d+$/);
        expect(owner).not.toBe(monitor);
        await browser.pressKeyCode(3);
        await shell('run-as', [appId, 'kill', '-9', owner]);
        await setBattery({level: 62, ac: 1, status: 2});
        await browser.waitUntil(async () => (await readLogRows()).some(row => row.charge === 62), {
            timeout: 20_000, timeoutMsg: 'Owner death stopped battery history collection',
        });
        expect((await shell('pidof', [`${appId}.BIS`])).trim()).toBe(monitor);
        let nextLevel = 63;
        await browser.waitUntil(async () => {
            // New observations request a refresh once the existing five-second cache expires.
            await setBattery({level: nextLevel, ac: 1, status: 2});
            nextLevel = nextLevel === 63 ? 64 : 63;
            const processes = await shell('ps', ['-A', '-o', 'PID,NAME']);
            return processes.split('\n').some(line => line.trim().endsWith(` ${appId}`)
                && line.trim().split(/\s+/)[0] !== owner);
        }, {timeout: 20_000, interval: 1_000, timeoutMsg: 'The shared reader process was not recreated'});
        expect((await shell('pidof', [`${appId}.BIS`])).trim()).toBe(monitor);

        await stopMonitoring();
        const stoppedRows = await readLogRows();
        await launchApplication('settings', 'settings:time');
        await completeOnboarding();
        await requireText('Charging target');
        await launchApplication('alarms');
        await completeOnboarding();
        await browser.$('android=new UiScrollable(new UiSelector().scrollable(true))'
            + '.scrollIntoView(new UiSelector().text("Charging target reached"))');
        await requireText('Charging target reached');
        expect((await readLivePreferences()).serviceDesired).toBe(false);
        await setBattery({level: 65, ac: 1, status: 2});
        const observationEnds = Date.now() + 15_000;
        await browser.waitUntil(async () => {
            expect(await readLogRows()).toEqual(stoppedRows);
            return Date.now() >= observationEnds;
        }, {timeout: 17_000, interval: 250, timeoutMsg: 'Stopped monitoring wrote new history'});
        // Android can retain an inactive binding record with app=null after a non-creating bind.
        const service = (await shell('dumpsys', ['activity', 'services', appId]))
            .split(/\n  \* ServiceRecord/).find(block =>
                block.includes(`${appId}/.monitoring.BatteryInfoService`)) ?? '';
        expect(service).not.toContain('startRequested=true');
        expect(service).not.toContain('isForeground=true');
    });
});
