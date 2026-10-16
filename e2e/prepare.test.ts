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
import {createHash} from 'node:crypto';
import {existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {test} from 'node:test';
import {buildCurrentApks, isJarSignatureEntry, parseApkBadging, parseSigningCertificate,
    prepareApks, validateUpgrade, verifyPinnedFile} from './prepare.js';
import type {ApkMetadata} from './prepare.js';

test('historical APK integrity rejects changed bytes, size and unpinned input', () => {
    const directory = mkdtempSync(join(tmpdir(), 'battery-monitor-apk-integrity-'));
    try {
        const path = join(directory, 'release.apk');
        const bytes = Buffer.from('controlled APK test input');
        writeFileSync(path, bytes);
        const digest = createHash('sha256').update(bytes).digest('hex');
        verifyPinnedFile(path, {sha256: digest, size: bytes.length});
        assert.throws(() => verifyPinnedFile(path, {sha256: digest, size: bytes.length + 1}), /size mismatch/);
        assert.throws(() => verifyPinnedFile(path, {sha256: ''}), /pinned SHA-256/);
        writeFileSync(path, 'modified input');
        assert.throws(() => verifyPinnedFile(path, {sha256: digest}), /SHA-256 mismatch/);
    } finally {
        rmSync(directory, {recursive: true, force: true});
    }
});

test('APK metadata accepts application and test APK badging without inventing a version', () => {
    assert.deepEqual(parseApkBadging("package: name='codes.swistak.batterymonitor' versionCode='1007003' versionName='1.7.3'"),
        {applicationId: 'codes.swistak.batterymonitor', versionCode: 1007003, versionName: '1.7.3'});
    assert.deepEqual(parseApkBadging("package: name='codes.swistak.batterymonitor.test' versionCode='' versionName=''"),
        {applicationId: 'codes.swistak.batterymonitor.test', versionCode: 0, versionName: ''});
    assert.throws(() => parseApkBadging('missing package metadata'));
});

test('signing checks accept verified legacy and modern labels and reject ambiguous identities', () => {
    const digest = 'a'.repeat(64);
    assert.equal(parseSigningCertificate(`Signer #1 certificate SHA-256 digest: ${digest}`), digest);
    assert.equal(parseSigningCertificate(`V3.0 Signer: certificate SHA-256 digest: ${digest}\nV2 Signer: certificate SHA-256 digest: ${digest}`), digest);
    assert.throws(() => parseSigningCertificate('no certificate'));
    assert.throws(() => parseSigningCertificate(`Signer #1 certificate SHA-256 digest: ${digest}\nSigner #2 certificate SHA-256 digest: ${'b'.repeat(64)}`));
});

test('payload preservation excludes only JAR signature envelopes, retaining META-INF services and metadata', () => {
    for (const name of ['META-INF/MANIFEST.MF', 'META-INF/CERT.SF', 'META-INF/CERT.RSA',
        'META-INF/CERT.DSA', 'META-INF/CERT.EC', 'meta-inf/cert.sf']) {
        assert.equal(isJarSignatureEntry(name), true, name);
    }
    for (const name of ['META-INF/', 'META-INF/services/example.Service',
        'META-INF/com/android/build/gradle/app-metadata.properties', 'META-INF/example.kotlin_module',
        'META-INF/nested/CERT.RSA', 'classes.dex', 'AndroidManifest.xml']) {
        assert.equal(isJarSignatureEntry(name), false, name);
    }
});

function apk(versionCode: number): ApkMetadata {
    return {path: '/controlled/application.apk', applicationId: 'codes.swistak.batterymonitor',
        versionCode, versionName: '1.7.3', signerSha256: 'a'.repeat(64), sha256: 'b'.repeat(64), size: 100,
        legacyWidgetProviders: ['BatteryInfoAppWidgetProvider', 'FullAppWidgetProvider'],
        sharedUserId: 'historical shared UID', sharedUserMaxSdkVersion: 'historical maximum SDK'};
}

test('upgrade preflight rejects incompatible package, signing, versions and historical manifest changes', () => {
    const older = apk(1007003);
    const current = apk(1007004);
    validateUpgrade(older, current, older.applicationId);
    for (const change of [
        {applicationId: `${older.applicationId}.debug`},
        {signerSha256: 'c'.repeat(64)},
        {versionCode: 1007003},
        {versionCode: 1007002},
        {versionCode: 0},
        {legacyWidgetProviders: ['FullAppWidgetProvider']},
        {sharedUserId: null},
        {sharedUserMaxSdkVersion: null},
    ]) {
        assert.throws(() => validateUpgrade(older, {...current, ...change}, older.applicationId),
            `Unexpected accepted upgrade metadata: ${JSON.stringify(change)}`);
    }
});

test('fresh build invokes repository Gradle with bounded resources and propagates a real subprocess failure', () => {
    const directory = mkdtempSync(join(tmpdir(), 'battery-monitor-apk-build-'));
    try {
        writeFileSync(join(directory, 'gradlew'), `#!${process.execPath}
require('node:fs').writeFileSync('build-arguments.json', JSON.stringify(process.argv.slice(2)));
console.error('controlled build failure');
process.exit(17);
`, {mode: 0o755});
        assert.throws(() => buildCurrentApks(directory));
        assert.deepEqual(JSON.parse(readFileSync(join(directory, 'build-arguments.json'), 'utf8')),
            ['--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=1g',
                '-Pe2e', ':app:assembleDebug', ':e2eHelper:assembleDebug']);
        assert.match(readFileSync(join(directory, 'e2e/build/reports/preparation-build.txt'), 'utf8'), /controlled build failure/);
    } finally {
        rmSync(directory, {recursive: true, force: true});
    }
});

test('failed preparation removes stale APK metadata before checking prerequisites', async () => {
    const directory = mkdtempSync(join(tmpdir(), 'battery-monitor-apk-preflight-'));
    const previousSdk = process.env.ANDROID_SDK_ROOT;
    const previousHome = process.env.ANDROID_HOME;
    try {
        mkdirSync(join(directory, 'e2e/build'), {recursive: true});
        writeFileSync(join(directory, 'e2e/build/apks.json'), '{"current":"stale"}');
        process.env.ANDROID_SDK_ROOT = join(directory, 'missing-sdk');
        process.env.ANDROID_HOME = join(directory, 'missing-sdk');
        await assert.rejects(prepareApks(directory), /installed Android SDK/);
        assert.equal(existsSync(join(directory, 'e2e/build/apks.json')), false);
    } finally {
        if (previousSdk === undefined) delete process.env.ANDROID_SDK_ROOT;
        else process.env.ANDROID_SDK_ROOT = previousSdk;
        if (previousHome === undefined) delete process.env.ANDROID_HOME;
        else process.env.ANDROID_HOME = previousHome;
        rmSync(directory, {recursive: true, force: true});
    }
});
