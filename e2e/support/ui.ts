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
import { readFileSync } from 'node:fs';
import { browser, expect } from '@wdio/globals';
import { XMLParser } from 'fast-xml-parser';
import { appId, apks, directory, launchApp, readLivePreferences, shell } from './device.js';

const timeout = 20_000;
const pickerPackages = ['com.google.android.documentsui', 'com.android.documentsui'];

export const textSelector = (text: string) =>
    `android=new UiSelector().text(${JSON.stringify(text)})`;

const textStartsWithSelector = (text: string) =>
    `android=new UiSelector().textStartsWith(${JSON.stringify(text)})`;

const resourceSelector = (resource: string) =>
    `android=new UiSelector().resourceId(${JSON.stringify(resource)})`;

const documentRowSelector = (text: string) =>
    `android=new UiSelector().resourceId("android:id/title").text(${JSON.stringify(text)}).enabled(true)`;

export async function requireText(text: string) {
    const element = await browser.$(textSelector(text));
    await element.waitForDisplayed({ timeout, timeoutMsg: `Expected visible text: ${text}` });
    return element;
}

async function scrollToText(selector: string) {
    const element = await browser.$(selector);
    if (!await element.isDisplayed()) {
        const target = selector.replace(/^android=/, '');
        await browser.$(
            `android=new UiScrollable(new UiSelector().scrollable(true)).setMaxSearchSwipes(12).scrollIntoView(${target})`,
        );
    }
    await element.waitForDisplayed({ timeout });
    return element;
}

export async function clickText(text: string) {
    await (await scrollToText(textSelector(text))).click();
}

export async function clickTextStartingWith(text: string) {
    await (await scrollToText(textStartsWithSelector(text))).click();
}

export async function checkSystemAppError({dismissBootLauncherAnr = false} = {}) {
    const close = await browser.$('android=new UiSelector().packageName("android").resourceId("android:id/aerr_close")');
    if (!await close.isDisplayed()) return;
    const title = await browser.$('android=new UiSelector().packageName("android").resourceId("android:id/alertTitle")');
    const message = await title.getText();
    if (!dismissBootLauncherAnr || message !== "Pixel Launcher isn't responding") {
        throw new Error(`Android app error blocks E2E: ${message}`);
    }
    // A cold-boot launcher ANR can outlive every APK installation; never dismiss the app under test's errors.
    console.warn(`Closing unrelated emulator dialog: ${message}`);
    await close.click();
    await close.waitForDisplayed({reverse: true, timeout, timeoutMsg: 'Pixel Launcher ANR dialog did not close'});
}

async function allowStartupPermission() {
    const allow = await browser.$(resourceSelector('com.android.permissioncontroller:id/permission_allow_button'));
    if (!await allow.isDisplayed()) return false;
    await allow.click();
    return true;
}

export async function launchApplication(section?: string, detail?: string) {
    await launchApp({ section, detail });
    await browser.waitUntil(async () => {
        await checkSystemAppError();
        if (await allowStartupPermission()) return false;
        return await browser.getCurrentPackage() === appId;
    }, {
        timeout,
        timeoutMsg: 'Battery Monitor Activity did not become foreground',
    });
}

async function applicationWindowHasFocus() {
    // Activity.dump reports its own DecorView focus; a dialog can share the Activity's window title.
    const activity = await shell('dumpsys', ['activity', `${appId}/.app.BatteryInfoActivity`]);
    return activity.includes('mResumed=true') && activity.includes('mHasWindowFocus=true');
}

export async function completeOnboarding(
    { verifyFirstRunPreference = true }: { verifyFirstRunPreference?: boolean } = {},
) {
    await browser.waitUntil(async () => {
        await checkSystemAppError();
        if (await browser.$(textSelector("What's new in Battery Monitor")).isDisplayed()) {
            throw new Error('Unexpected changelog interrupted startup');
        }
        if (await allowStartupPermission()) return false;
        for (const selector of [
            textSelector('Cancel'),
            textSelector('Got it'),
        ]) {
            const action = await browser.$(selector);
            if (await action.isDisplayed()) {
                await action.click();
                return false;
            }
        }
        const firstRunFinished = !verifyFirstRunPreference || (await readLivePreferences()).first_run === false;
        return firstRunFinished && await applicationWindowHasFocus() &&
            (await browser.$('~Open menu').isDisplayed() || await browser.$('~Navigate up').isDisplayed());
    }, {
        timeout,
        timeoutMsg: 'Startup did not finish onboarding and return focus to the real Activity',
    });
}

export async function assertCompletedStartupHasNoChangelog() {
    await completeOnboarding();
    await browser.waitUntil(async () => {
        await browser.execute('mobile: resetAccessibilityCache');
        const main = await readLivePreferences();
        return main.last_acknowledged_version_code === apks().current.versionCode &&
            await applicationWindowHasFocus() &&
            !await browser.$(textSelector("What's new in Battery Monitor")).isDisplayed();
    }, {
        timeout,
        timeoutMsg: 'Startup did not settle with persistent acknowledgement and no changelog',
    });
    await expect(browser.$(textSelector("What's new in Battery Monitor"))).not.toBeDisplayed();
    expect(await applicationWindowHasFocus()).toBe(true);
    const main = await readLivePreferences();
    expect(main.last_acknowledged_version_code).toBe(apks().current.versionCode);
}

export async function assertUpgradeChangelog() {
    await requireText("What's new in Battery Monitor");
    await requireText(`Version ${apks().current.versionName}`);
    const xml = readFileSync(new URL('../../app/src/main/res/values/changelog.xml', import.meta.url), 'utf8');
    const resources = new XMLParser({ ignoreAttributes: false, trimValues: false }).parse(xml);
    const strings = Array.isArray(resources.resources.string)
        ? resources.resources.string : [resources.resources.string];
    const release = strings.find((entry: Record<string, string>) =>
        entry['@_name'] === 'latest_release_changelog');
    expect(release).toBeDefined();
    const expectedContent = String(release['#text']).replace(/\\n/g, '\n').replace(/\\'/g, "'");
    const content = browser.$(textStartsWithSelector(expectedContent.split('\n')[0]));
    await expect(content).toHaveText(expectedContent);
    await clickText('Got it');
    await completeOnboarding();
}

export async function openBackupAndRestoreScreen() {
    await launchApplication('settings', 'settings:backup');
    await completeOnboarding();
    await requireText('Export general backup');
}

export async function acknowledgeMessage(message: string) {
    await requireText(message);
    await clickText('Okay');
}

export async function confirmAllAvailableCategories() {
    await browser.$(textStartsWithSelector('Predictor data')).waitForDisplayed({ timeout });
    await clickText('Okay');
}

export async function chooseReplaceHistory() {
    await clickText('Replace existing logs');
    await clickText('Okay');
}

async function dismissDocumentKeyboard() {
    if (await browser.isKeyboardShown()) {
        await browser.hideKeyboard();
        await browser.waitUntil(async () => !await browser.isKeyboardShown(), {
            timeout,
            timeoutMsg: 'DocumentsUI keyboard did not close',
        });
    }
    expect(pickerPackages).toContain(await browser.getCurrentPackage());
}

async function clickDocumentControl(selector: string) {
    await dismissDocumentKeyboard();
    const element = await browser.$(selector);
    await element.waitForDisplayed({ timeout });
    await element.click();
    await browser.execute('mobile: resetAccessibilityCache');
}

async function openDocumentRoots() {
    await clickDocumentControl('android=new UiSelector().descriptionMatches("Show roots|Show navigation drawer")');
}

type Bounds = { left: number; top: number; right: number; bottom: number };

async function boundsOf(element: WebdriverIO.Element): Promise<Bounds> {
    const bounds = await element.getAttribute('bounds');
    const match = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(bounds ?? '');
    if (!match) throw new Error(`Missing accessibility bounds: ${bounds}`);
    return { left: Number(match[1]), top: Number(match[2]), right: Number(match[3]), bottom: Number(match[4]) };
}

async function documentDirectoryRow(name: string) {
    await dismissDocumentKeyboard();
    const pickerPackage = await browser.getCurrentPackage();
    const selector = documentRowSelector(name);
    let gestures = 0;
    await browser.waitUntil(async () => {
        await browser.execute('mobile: resetAccessibilityCache');
        const node = await browser.$(selector);
        const footer = await browser.$('android=new UiSelector().className("android.widget.EditText")');
        const footerBounds = await footer.isDisplayed() ? await boundsOf(footer) : null;
        if (await node.isDisplayed()) {
            const row = await boundsOf(node);
            if (row.bottom > row.top && (!footerBounds || row.bottom <= footerBounds.top)) return true;
        }
        if (gestures++ >= 12) throw new Error(`DocumentsUI directory '${name}' remains unavailable or obscured`);
        const list = await browser.$(resourceSelector(`${pickerPackage}:id/dir_list`));
        const listBounds = await boundsOf(list);
        const toolbar = await browser.$(resourceSelector(`${pickerPackage}:id/toolbar`));
        const toolbarBounds = await boundsOf(toolbar);
        // DocumentsUI reports a list extending behind the Save footer; derive its unobscured content area.
        const margin = Math.max(1, Math.floor((listBounds.right - listBounds.left) / 20));
        const top = Math.max(listBounds.top, toolbarBounds.bottom) + margin;
        const bottom = Math.min(listBounds.bottom, footerBounds?.top ?? listBounds.bottom) - margin;
        expect(bottom).toBeGreaterThan(top);
        await browser.execute('mobile: scrollGesture', {
            left: listBounds.left + margin,
            top,
            width: listBounds.right - listBounds.left - margin * 2,
            height: bottom - top,
            direction: 'down',
            percent: 0.75,
        });
        return false;
    }, { timeout, timeoutMsg: `DocumentsUI directory '${name}' was not accessible` });
    await clickDocumentControl(selector);
    const toolbarTitle = `//android.widget.TextView[@text=${xpathLiteral(name)}][ancestor::*[@resource-id='${pickerPackage}:id/toolbar']]`;
    await browser.$(toolbarTitle).waitForDisplayed({ timeout });
}

function xpathLiteral(value: string) {
    if (!value.includes("'")) return `'${value}'`;
    if (!value.includes('"')) return `"${value}"`;
    throw new Error('Directory name contains unsupported XPath quoting');
}

async function documentDirectory() {
    await browser.waitUntil(async () => pickerPackages.includes(await browser.getCurrentPackage()), {
        timeout,
        timeoutMsg: 'Android Storage Access Framework picker did not appear',
    });
    await openDocumentRoots();
    const model = (await shell('getprop', ['ro.product.model'])).trim();
    const internalStorage = documentRowSelector(model);
    if (!await browser.$(internalStorage).isDisplayed()) {
        await browser.back();
        await clickDocumentControl('~More options');
        await clickDocumentControl(textSelector('Show internal storage'));
        await openDocumentRoots();
    }
    await clickDocumentControl(internalStorage);
    await documentDirectoryRow('Download');
    await documentDirectoryRow('battery-monitor-e2e');
}

export async function chooseImportFile(action: string, filename: string) {
    const size = Number((await shell('stat', ['-c', '%s', `${directory}/${filename}`])).trim());
    expect(size).toBeGreaterThan(0);
    await clickText(action);
    await documentDirectory();
    const pickerPackage = await browser.getCurrentPackage();
    await clickDocumentControl('~Search');
    const search = await browser.$(resourceSelector(`${pickerPackage}:id/search_src_text`));
    await search.waitForDisplayed({ timeout });
    await search.setValue(filename);
    await browser.pressKeyCode(66);
    await dismissDocumentKeyboard();
    await clickDocumentControl(documentRowSelector(filename));
    await browser.waitUntil(async () => await browser.getCurrentPackage() === appId, {
        timeout,
        timeoutMsg: `Application did not receive selected file ${filename}`,
    });
}

export async function saveBackupThroughFilePicker(filename: string) {
    await documentDirectory();
    const pickerPackage = await browser.getCurrentPackage();
    const name = await browser.$('android=new UiSelector().className("android.widget.EditText")');
    await name.waitForDisplayed({ timeout });
    await name.setValue(filename);
    const saveSelector = `android=new UiSelector().resourceIdMatches(${JSON.stringify(
        `(?:${pickerPackage.replaceAll('.', '\\.')}):id/save_menu|android:id/button1`,
    )}).enabled(true)`;
    await clickDocumentControl(saveSelector);
    const replace = await browser.$(textSelector('Replace'));
    if (await replace.isDisplayed()) await replace.click();
    await browser.waitUntil(async () => await browser.getCurrentPackage() === appId, {
        timeout,
        timeoutMsg: `DocumentsUI did not save ${filename}`,
    });
}
