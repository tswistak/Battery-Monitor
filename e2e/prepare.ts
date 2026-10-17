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
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync} from 'node:fs';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const legacyWidgets = ['BatteryInfoAppWidgetProvider', 'FullAppWidgetProvider'];
const signingPassword = 'battery-monitor-e2e';

export interface ApkMetadata {
    path: string;
    applicationId: string;
    versionCode: number;
    versionName: string;
    signerSha256: string;
    sha256: string;
    size: number;
    legacyWidgetProviders: string[];
    sharedUserId: string | null;
    sharedUserMaxSdkVersion: string | null;
    originalPath?: string;
}

export interface PreparedApks {
    applicationId: string;
    preparedAt: string;
    historical: ApkMetadata;
    current: ApkMetadata;
    helper: ApkMetadata;
    newUiBaseline: ApkMetadata | null;
    newUiBaselineUnavailableReason: string | null;
}

function command(root: string, program: string, args: string[], timeout = 120_000): Buffer {
    return execFileSync(program, args, {cwd: root, timeout, maxBuffer: 64 * 1024 * 1024});
}

function sha256(bytes: Buffer): string {
    return createHash('sha256').update(bytes).digest('hex');
}

export function verifyPinnedFile(path: string, expected: {sha256: string; size?: number}): void {
    assert(existsSync(path), `Missing historical APK: ${path}`);
    assert.match(expected.sha256, /^[a-f\d]{64}$/i, 'Historical APK requires a pinned SHA-256.');
    const bytes = readFileSync(path);
    assert.equal(sha256(bytes), expected.sha256.toLowerCase(), `Historical APK SHA-256 mismatch: ${path}`);
    if (expected.size !== undefined) assert.equal(bytes.length, expected.size, 'Historical APK size mismatch.');
}

export function isJarSignatureEntry(name: string): boolean {
    return /^META-INF\/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))$/i.test(name);
}

export function parseApkBadging(output: string): Pick<ApkMetadata, 'applicationId' | 'versionCode' | 'versionName'> {
    const match = output.match(/package: name='([^']+)' versionCode='(\d*)' versionName='([^']*)'/);
    assert(match, 'Could not read APK package/version metadata.');
    return {applicationId: match[1], versionCode: Number(match[2]), versionName: match[3]};
}

export function parseSigningCertificate(output: string): string {
    const digests = [...new Set([...output.matchAll(/certificate SHA-256 digest: ([a-f\d]{64})/gi)]
        .map(match => match[1].toLowerCase()))];
    assert.equal(digests.length, 1, `Expected one verified signing identity:\n${output}`);
    return digests[0];
}

export function validateUpgrade(older: ApkMetadata, newer: ApkMetadata, applicationId: string): void {
    assert(older.versionCode > 0 && newer.versionCode > 0, 'Both application APKs require positive version codes.');
    assert.equal(older.applicationId, applicationId, 'Baseline must use the released application ID.');
    assert.equal(newer.applicationId, applicationId, 'Current .debug package is not a real upgrade; build with -Pe2e.');
    assert.equal(older.signerSha256, newer.signerSha256, 'APK signatures differ; use the generated E2E signing identity.');
    assert(newer.versionCode > older.versionCode,
        `Current versionCode ${newer.versionCode} must exceed baseline ${older.versionCode}; supply CI_VERSION_NAME=... .`);
    for (const provider of legacyWidgets) {
        assert(older.legacyWidgetProviders.includes(provider) && newer.legacyWidgetProviders.includes(provider),
            `Missing legacy widget provider ${provider}.`);
    }
    assert.equal(older.sharedUserId, newer.sharedUserId, 'Historical sharedUserId manifest contract changed.');
    assert.equal(older.sharedUserMaxSdkVersion, newer.sharedUserMaxSdkVersion,
        'Historical sharedUserMaxSdkVersion manifest contract changed.');
}

function sdkDirectory(root: string): string {
    const local = existsSync(join(root, 'local.properties'))
        ? readFileSync(join(root, 'local.properties'), 'utf8').match(/^sdk\.dir=(.+)$/m)?.[1]?.replaceAll('\\:', ':')
        : undefined;
    const sdk = process.env.ANDROID_SDK_ROOT || process.env.ANDROID_HOME || local;
    assert(sdk && existsSync(sdk), 'Set ANDROID_SDK_ROOT to an installed Android SDK.');
    return sdk;
}

function sdkTool(root: string, name: string): string {
    const directory = join(sdkDirectory(root), 'build-tools');
    assert(existsSync(directory), 'Missing Android SDK Build Tools; install Build Tools 37.0.0.');
    const versions = readdirSync(directory).filter(version => /^\d+\.\d+\.\d+$/.test(version))
        .sort((left, right) => right.localeCompare(left, undefined, {numeric: true}));
    const tool = versions.map(version => join(directory, version, name)).find(path => existsSync(path));
    assert(tool, `Missing ${name}; install Android SDK Build Tools 37.0.0.`);
    return tool;
}

export function apkPayload(root: string, path: string): {entries: string[]; sha256: string} {
    const allEntries = command(root, 'unzip', ['-Z1', path]).toString().split('\n').filter(Boolean);
    const signatures = allEntries.filter(isJarSignatureEntry);
    const entries = allEntries.filter(name => !isJarSignatureEntry(name));
    return {entries, sha256: sha256(command(root, 'unzip',
        ['-p', path, ...(signatures.length ? ['-x', ...signatures] : [])]))};
}

function resign(root: string, original: string, output: string): void {
    command(root, sdkTool(root, 'apksigner'), ['sign', '--ks', join(root, 'e2e/build/test-signing.p12'),
        '--ks-key-alias', 'e2e', '--ks-pass', `pass:${signingPassword}`, '--key-pass', `pass:${signingPassword}`,
        '--out', output, original]);
    assert.deepEqual(apkPayload(root, output), apkPayload(root, original), 'Re-signing changed historical APK payload.');
}

function readApk(root: string, path: string): ApkMetadata {
    assert(existsSync(path), `Missing APK ${path}; the fresh Gradle build did not produce it.`);
    const badging = command(root, sdkTool(root, 'aapt2'), ['dump', 'badging', path]).toString();
    const manifest = command(root, sdkTool(root, 'aapt2'), ['dump', 'xmltree', path, '--file', 'AndroidManifest.xml']).toString();
    const certificate = command(root, sdkTool(root, 'apksigner'), ['verify', '--print-certs', path]).toString();
    const attribute = (name: string): string | null => manifest.split('\n')
        .find(value => value.includes(`:${name}(`))?.trim() ?? null;
    const bytes = readFileSync(path);
    return {path, ...parseApkBadging(badging), signerSha256: parseSigningCertificate(certificate),
        sha256: sha256(bytes), size: bytes.length,
        legacyWidgetProviders: legacyWidgets.filter(provider => manifest.includes(provider)),
        sharedUserId: attribute('sharedUserId'), sharedUserMaxSdkVersion: attribute('sharedUserMaxSdkVersion')};
}

export function buildCurrentApks(root: string): void {
    const reports = join(root, 'e2e/build/reports');
    mkdirSync(reports, {recursive: true});
    const started = performance.now();
    console.log('Building current E2E APKs from the working checkout.');
    try {
        const output = command(root, join(root, 'gradlew'), ['--max-workers=2',
            '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=1g', '-Pe2e', ':app:assembleDebug',
            ':e2eHelper:assembleDebug'], 30 * 60_000);
        writeFileSync(join(reports, 'preparation-build.txt'), output);
        process.stdout.write(output);
    } catch (error) {
        const failure = error as Error & {stdout?: Buffer; stderr?: Buffer};
        writeFileSync(join(reports, 'preparation-build.txt'),
            Buffer.concat([failure.stdout ?? Buffer.alloc(0), failure.stderr ?? Buffer.alloc(0)]));
        throw error;
    } finally {
        console.log(`Current APK build duration: ${Math.round((performance.now() - started) / 100) / 10}s`);
    }
}

export async function prepareApks(root = projectRoot): Promise<PreparedApks> {
    const build = join(root, 'e2e/build');
    mkdirSync(build, {recursive: true});
    // A failed preparation must never leave apparently current metadata from an earlier run.
    rmSync(join(build, 'apks.json'), {force: true});
    assert(process.env.E2E_REQUIRE_NEW_UI_BASELINE !== 'true' || process.env.NEW_UI_BASELINE_APK,
        'E2E_REQUIRE_NEW_UI_BASELINE=true requires a genuine NEW_UI_BASELINE_APK and NEW_UI_BASELINE_SHA256.');
    sdkTool(root, 'apksigner');
    sdkTool(root, 'aapt2');
    const fixture = JSON.parse(readFileSync(join(root, 'e2e/fixtures.json'), 'utf8')).historicalApk as {
        applicationId: string; versionCode: number; versionName: string; url: string; sha256: string; size: number;
    };
    const original = join(build, 'battery-monitor-1.7.3.original.apk');
    if (process.env.HISTORICAL_APK) writeFileSync(original, readFileSync(resolve(process.env.HISTORICAL_APK)));
    else if (!existsSync(original)) {
        const response = await fetch(fixture.url, {signal: AbortSignal.timeout(120_000)});
        assert(response.ok, `Historical release download failed: HTTP ${response.status}`);
        writeFileSync(original, Buffer.from(await response.arrayBuffer()));
    }
    verifyPinnedFile(original, fixture);
    const key = join(build, 'test-signing.p12');
    if (!existsSync(key)) {
        const keytool = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin/keytool') : 'keytool';
        command(root, keytool, ['-genkeypair', '-keystore', key, '-storetype', 'PKCS12',
            '-storepass', signingPassword, '-keypass', signingPassword, '-alias', 'e2e',
            '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650',
            '-dname', 'CN=Battery Monitor Disposable E2E,OU=Tests,O=Battery Monitor,C=PL', '-noprompt']);
    }
    const signedHistorical = join(build, 'battery-monitor-1.7.3.apk');
    resign(root, original, signedHistorical);
    buildCurrentApks(root);

    const historical = {...readApk(root, signedHistorical), originalPath: original};
    const current = readApk(root, join(root, 'app/build/outputs/apk/debug/app-debug.apk'));
    const helperDirectory = join(root, 'e2e/helper/build/outputs/apk/debug');
    assert(existsSync(helperDirectory), 'The fresh build did not produce the helper APK directory.');
    const helperFiles = readdirSync(helperDirectory).filter(name => name.endsWith('.apk'));
    assert.equal(helperFiles.length, 1, 'Expected exactly one helper APK.');
    const helper = readApk(root, join(helperDirectory, helperFiles[0]));
    validateUpgrade(historical, current, fixture.applicationId);
    assert.equal(historical.versionCode, fixture.versionCode, 'Pinned historical APK versionCode changed.');
    assert.equal(historical.versionName, fixture.versionName, 'Pinned historical APK versionName changed.');
    assert.equal(helper.applicationId, `${fixture.applicationId}.e2ehelper`, 'Unexpected helper APK package.');
    assert.equal(helper.signerSha256, current.signerSha256, 'Helper signing identity differs.');

    let newUiBaseline: ApkMetadata | null = null;
    let newUiBaselineUnavailableReason: string | null =
        'The released 1.7.3 APK and freshly built/signed current APK are prepared for the released-upgrade tests. ' +
        'Only the additional older-new-UI → current-new-UI widget upgrade is NOT EXECUTED: ' +
        '1.7.3 predates the redesigned widgets, so this separate test needs an earlier APK that already contains them. ' +
        'Supply NEW_UI_BASELINE_APK and NEW_UI_BASELINE_SHA256 for that additional scenario.';
    if (process.env.NEW_UI_BASELINE_APK) {
        const source = resolve(process.env.NEW_UI_BASELINE_APK);
        assert.match(process.env.NEW_UI_BASELINE_SHA256 ?? '', /^[a-f\d]{64}$/i, 'Supply pinned NEW_UI_BASELINE_SHA256.');
        verifyPinnedFile(source, {sha256: process.env.NEW_UI_BASELINE_SHA256!});
        const signed = join(build, 'new-ui-baseline.apk');
        resign(root, source, signed);
        newUiBaseline = {...readApk(root, signed), originalPath: source};
        validateUpgrade(newUiBaseline, current, fixture.applicationId);
        assert.notEqual(apkPayload(root, signed).sha256, apkPayload(root, current.path).sha256,
            'Two identical APK payloads are not cross-version coverage.');
        newUiBaselineUnavailableReason = null;
    }
    const prepared: PreparedApks = {applicationId: fixture.applicationId, preparedAt: new Date().toISOString(),
        historical, current, helper, newUiBaseline, newUiBaselineUnavailableReason};
    writeFileSync(join(build, 'apks.json'), `${JSON.stringify(prepared, null, 2)}\n`);
    console.log(`Prepared E2E APK metadata: ${join(build, 'apks.json')}`);
    if (newUiBaselineUnavailableReason) console.log(newUiBaselineUnavailableReason);
    return prepared;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    prepareApks().catch(error => {
        console.error(error);
        process.exitCode = 1;
    });
}
