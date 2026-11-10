import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { loadSiteConfig, validatePublication } from '../scripts/config.mjs';
import { getPrivacySections } from '../src/content/en/privacy.ts';
import { makeReceipt, readReceipt, DAY_MS } from '../src/lib/consent.ts';
import { parse as parseYaml } from 'yaml';
import {
  listReleases,
  selectStableRelease,
  resolveTagCommit,
  selectReleaseAssets,
  REPOSITORY_URL,
} from '../scripts/content/github.mjs';
import {
  parseAndroidStrings,
  normalizeAndroid,
  normalizeUrl,
  markdownHtml,
  buildContent,
  buildArticles,
  capabilitiesFrom,
  changelogFrom,
} from '../scripts/content/extract.mjs';
import {
  fixtureSnapshot,
  importContent,
  safeRead,
  selectMediaInputs,
  loadWebsiteMedia,
  applyWebsiteFunding,
} from '../scripts/content/import.mjs';
const commit = 'a'.repeat(40);
const context = { commit, sourcePath: 'README.md' };
const release = (tag, id, published = '2026-01-01T12:00:00Z', other = {}) => ({
  tag_name: tag,
  id,
  published_at: published,
  draft: false,
  prerelease: false,
  html_url: `${REPOSITORY_URL}/releases/tag/${tag}`,
  body: '',
  ...other,
});
const response = (value) =>
  new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } });

test('published stable release selection ignores newer prereleases, drafts and unpublished tags', () => {
  const stable = release('1.7.3', 3);
  assert.equal(
    selectStableRelease([
      release('9.0.0', 9, '2026-03-01T00:00:00Z', { prerelease: true }),
      release('10.0.0', 10, '2026-04-01T00:00:00Z', { draft: true }),
      release('8.0.0', 8, null),
      stable,
    ]).id,
    3,
  );
  assert.throws(() => selectStableRelease([]), /No published stable/);
});

test('release pagination reaches stable metadata beyond a full first page', async () => {
  const calls = [];
  const result = await listReleases({
    fetchImpl: async (url) => {
      calls.push(url);
      return response(
        calls.length === 1
          ? Array.from({ length: 100 }, (_, i) =>
              release(`2.0.${i}`, i + 100, '2026-02-01T00:00:00Z', { prerelease: true }),
            )
          : [release('1.7.3', 3)],
      );
    },
  });
  assert.equal(selectStableRelease(result).tag_name, '1.7.3');
  assert.match(calls[1], /page=2$/);
});

test('annotated tags resolve through objects to an exact commit, never target_commitish', async () => {
  const tagSha = 'b'.repeat(40),
    nested = 'c'.repeat(40),
    calls = [];
  const resolved = await resolveTagCommit('1.7.3', {
    fetchImpl: async (url) => {
      calls.push(url);
      return response({
        object:
          calls.length === 1
            ? { type: 'tag', sha: tagSha }
            : calls.length === 2
              ? { type: 'tag', sha: nested }
              : { type: 'commit', sha: commit },
      });
    },
  });
  assert.equal(resolved, commit);
  assert.match(calls[1], new RegExp(`/git/tags/${tagSha}$`));
  await assert.rejects(
    resolveTagCommit('bad', {
      fetchImpl: async () => response({ object: { type: 'tree', sha: commit } }),
    }),
    /does not resolve/,
  );
});

test('APK selection chooses the published version, pairs its signature and rejects ambiguity', () => {
  const asset = (name) => ({
    id: name.endsWith('.asc') ? 2 : 1,
    name,
    state: 'uploaded',
    size: 100,
    updated_at: '2026-01-01T12:00:00Z',
    browser_download_url: `${REPOSITORY_URL}/releases/download/1.7.3/${name}`,
  });
  const selected = selectReleaseAssets(
    [
      asset('battery-monitor-1.7.3.apk'),
      asset('debug.apk'),
      asset('battery-monitor-1.7.3.apk.asc'),
    ],
    '1.7.3',
  );
  assert.match(selected.apkUrl, /battery-monitor-1.7.3.apk$/);
  assert.match(selected.signatureUrl, /\.asc$/);
  assert.deepEqual(selected.releaseAssets, {
    apk: {
      id: 1,
      name: 'battery-monitor-1.7.3.apk',
      size: 100,
      updatedAt: '2026-01-01T12:00:00.000Z',
      url: selected.apkUrl,
      digest: null,
    },
    signature: {
      id: 2,
      name: 'battery-monitor-1.7.3.apk.asc',
      size: 100,
      updatedAt: '2026-01-01T12:00:00.000Z',
      url: selected.signatureUrl,
      digest: null,
    },
  });
  const original = asset('battery-monitor-1.7.3.apk');
  const replaced = selectReleaseAssets([{ ...original, id: 3 }], '1.7.3');
  assert.notDeepEqual(replaced.releaseAssets.apk, selected.releaseAssets.apk);
  assert.throws(
    () => selectReleaseAssets([{ ...original, updated_at: undefined }], '1.7.3'),
    /incomplete identity metadata/,
  );
  assert.throws(
    () => selectReleaseAssets([{ ...original, digest: 'sha256:invalid' }], '1.7.3'),
    /invalid SHA-256 digest/,
  );
  assert.throws(
    () => selectReleaseAssets([asset('a.apk'), asset('b.apk')], '1.7.3'),
    /unambiguous/,
  );
  assert.throws(
    () =>
      selectReleaseAssets(
        [{ ...asset('a.apk'), browser_download_url: 'https://evil.example/app.apk' }],
        '1.7.3',
      ),
    /unexpected download URL/,
  );
});

test('Android XML preserves safe formatting and escapes but refuses DTDs and unresolved placeholders', () => {
  const strings = parseAndroidStrings(
    '<resources><string name="text">It\\\'s &amp; &lt;literal&gt;\\n\\n<b>Bold</b> <a href="../LICENSE">license</a> 100%%</string></resources>',
    { commit, sourcePath: 'app/strings.xml' },
  );
  const normalized = normalizeAndroid(strings.get('text'));
  assert.match(normalized, /It's &amp; &lt;literal&gt;\n\n<b>Bold<\/b>/);
  assert.match(normalized, new RegExp(`/blob/${commit}/LICENSE`));
  assert.match(normalized, /100%$/);
  const quoted = parseAndroidStrings(
    String.raw`<resources><string name="quoted">Press \"Alert\".</string><string name="wrapped">"Quoted resource"</string></resources>`,
    context,
  );
  assert.equal(normalizeAndroid(quoted.get('quoted')), 'Press "Alert".');
  assert.equal(normalizeAndroid(quoted.get('wrapped')), 'Quoted resource');
  assert.throws(() => normalizeAndroid('Unresolved %1$s'), /placeholder/);
  assert.equal(normalizeAndroid('100% charged'), '100% charged');
  assert.throws(
    () =>
      parseAndroidStrings(
        '<!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><resources/>',
        context,
      ),
    /DTD/,
  );
  assert.throws(
    () =>
      parseAndroidStrings(
        '<resources><string name="same">A</string><string name="same">B</string></resources>',
        context,
      ),
    /duplicate/,
  );
});

test('imported Markdown strips executable markup and normalizes relative links to the app commit', () => {
  const html = markdownHtml(
    '[License](LICENSE) [unsafe](javascript:alert(1))\n<script>alert(2)</script>\n<img src="https://example.com/tracker.png" onerror="alert(3)">',
    context,
  );
  assert.match(html, new RegExp(`/blob/${commit}/LICENSE`));
  assert.doesNotMatch(html, /javascript:|<script|<img|onerror/);
  assert.equal(normalizeUrl('//example.com', context), null);
  assert.equal(normalizeUrl('mailto:test@example.com?body=secret', context), null);
  assert.equal(normalizeUrl('../LICENSE', context), null);
  assert.equal(normalizeUrl('/LICENSE', context), `${REPOSITORY_URL}/blob/${commit}/LICENSE`);
});

test('pinned older stable app content stays independent of website-only revisions', async () => {
  const snapshot = await fixtureSnapshot('tests/fixtures/content');
  const make = (websiteCommit) =>
    buildContent({
      ...snapshot,
      provenance: {
        ...snapshot.provenance,
        websiteCommit,
        schemaVersion: 3,
        importedAt: '2026-11-09T00:00:00Z',
        sources: [],
      },
    });
  const first = make('1'.repeat(40)),
    second = make('2'.repeat(40));
  assert.equal(first.release.version, '1.7.3');
  assert.equal(first.provenance.appCommit, '790e0033c3508d163ea5ec5ddb7feb284f7b93d2');
  assert.deepEqual(first.articles, second.articles);
  assert.equal(first.capabilities.historyCharts, false);
  assert.equal(first.capabilities.modernWidgets, false);
  assert.equal(first.articles.length, 6);
  assert(first.omitted.some((reason) => reason.includes('source section not available')));
  for (const source of ['app/src/main/res/values/strings.xml', 'CHANGELOG.md']) {
    const incomplete = { ...snapshot, files: new Map(snapshot.files) };
    incomplete.files.delete(source);
    assert.throws(
      () => buildContent({ ...incomplete, provenance: first.provenance }),
      /Required application source/,
    );
  }
  const missingEntry = { ...snapshot, files: new Map(snapshot.files) };
  missingEntry.files.set('CHANGELOG.md', Buffer.from('# Changelog\n\n## 1.7.2\nOlder notes.\n'));
  assert.throws(
    () => buildContent({ ...missingEntry, provenance: first.provenance }),
    /no entry for the selected application release/,
  );
});

test('unsupported optional features cannot inject their available source explanations', () => {
  const strings = new Map([['pref_live_update_display_help', 'EXAMPLE_CAPABILITY_TEXT']]);
  const omitted = [];
  const articles = buildArticles({
    strings,
    readme: '',
    capabilities: { liveUpdates: false },
    commit,
    omitted,
  });
  assert(
    !articles
      .find((article) => article.id === 'notifications')
      .sources.includes('pref_live_update_display_help'),
  );
  assert(omitted.some((reason) => reason.includes('unsupported feature')));
});

test('shared help keeps settings labeled across current and legacy resource structures', async () => {
  const older = await fixtureSnapshot('tests/fixtures/content');
  const currentStrings = await readFile(
    new URL('../../app/src/main/res/values/strings.xml', import.meta.url),
    'utf8',
  );
  const currentReadme = await readFile(new URL('../../README.md', import.meta.url), 'utf8');
  for (const [xml, readme, legacy] of [
    [
      older.files.get('app/src/main/res/values/strings.xml').toString(),
      older.files.get('README.md').toString(),
      true,
    ],
    [currentStrings, currentReadme, false],
  ]) {
    const strings = parseAndroidStrings(xml, context);
    const articles = buildArticles({
      strings,
      readme,
      capabilities: capabilitiesFrom(strings, readme),
      commit,
      omitted: [],
    });
    const article = (id) => articles.find((value) => value.id === id);
    const headingBefore = (id, key) => {
      const html = article(id).html;
      const firstParagraph = normalizeAndroid(strings.get(key))
        .split(/\n\s*\n/)[0]
        .replace(/\s+/g, ' ')
        .trim();
      const start = html.lastIndexOf(firstParagraph);
      assert(start >= 0, `${key} must remain imported`);
      return [...html.slice(0, start).matchAll(/<h[23]>(.*?)<\/h[23]>/g)].at(-1)?.[1];
    };
    for (const [id, helpKey, titleKey] of [
      ['notifications', 'pref_expanded_notification_help', 'pref_expanded_notification'],
      ['notifications', 'pref_show_icon_unit_help', 'pref_show_icon_unit'],
      ['battery-readings', 'pref_prefer_current_avg_help', 'pref_prefer_average_battery_current'],
      ['history-and-backups', 'pref_enable_logging_help', 'pref_enable_logging'],
    ]) {
      assert.equal(headingBefore(id, helpKey), normalizeAndroid(strings.get(titleKey)));
    }
    const iconHelp = legacy ? 'status_bar_icon_settings_help' : 'status_bar_icon_availability_help';
    assert.equal(
      headingBefore('notifications', iconHelp),
      normalizeAndroid(strings.get('status_bar_icon_settings')),
    );
    const capacityHelp = legacy ? 'advanced_help_capacity' : 'diag_help_capacity';
    const capacityTitle = legacy ? 'advanced_section_capacity' : 'diag_section_capacity';
    assert.equal(
      headingBefore('battery-readings', capacityHelp),
      normalizeAndroid(strings.get(capacityTitle)),
    );
    const backups = article('history-and-backups');
    if (legacy) {
      assert(backups.sources.includes('settings_backup_help'));
      assert(!backups.sources.includes('pref_import_settings_help'));
      assert(article('battery-readings').sources.includes('advanced_help_overview'));
      assert.equal(
        backups.html.split(normalizeAndroid(strings.get('settings_backup_help'))).length - 1,
        1,
      );
    } else {
      assert(backups.sources.includes('pref_export_settings_help'));
      assert(backups.sources.includes('pref_import_settings_help'));
      assert(!article('battery-readings').sources.includes('advanced_help_overview'));
    }
    assert(
      article('charging-targets').html.includes(
        [strings.get('settings_activity_subtitle'), strings.get('time_estimates_settings')]
          .map(normalizeAndroid)
          .join(' → '),
      ),
    );
    strings.delete('time_estimates_settings');
    assert(
      !buildArticles({
        strings,
        readme,
        capabilities: capabilitiesFrom(strings, readme),
        commit,
        omitted: [],
      })
        .find((value) => value.id === 'charging-targets')
        .html.includes('In the app:'),
    );
  }
});

test('pinned fixture checksum mismatches fail before extraction', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'battery-fixture-'));
  try {
    await mkdir(path.join(root, 'files'));
    await writeFile(path.join(root, 'files', 'README.md'), 'tampered');
    await writeFile(
      path.join(root, 'snapshot.json'),
      JSON.stringify({
        schemaVersion: 3,
        appCommit: commit,
        files: { 'README.md': '0'.repeat(64) },
      }),
    );
    await assert.rejects(fixtureSnapshot(root), /checksum mismatch/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('changelog includes only metadata matched to published stable releases', () => {
  const rows = changelogFrom(
    [release('1.7.3', 3), release('1.7.2', 2, undefined, { body: 'Not CHANGELOG.md' })],
    '# Changelog\n\n## 2.0.0\nUnreleased promise\n\n## 1.7.3\nPublished fix\n',
    commit,
  );
  assert.equal(rows.length, 1);
  assert.match(rows[0].html, /Published fix/);
  assert.doesNotMatch(rows[0].html, /Unreleased promise/);
  assert.equal(rows[0].date, '2026-01-01T12:00:00Z');
});

test('local content imports checkout changelog sections without claiming published dates or releases', async () => {
  const snapshot = await fixtureSnapshot('tests/fixtures/content');
  const files = new Map(snapshot.files);
  files.set(
    'CHANGELOG.md',
    Buffer.from(
      '# Changelog\n\n## Unreleased\nCurrent work.\n\n## 2.0.0\nBranch changes.\n\n## 1.7.3\nPast fix.\n',
    ),
  );
  const local = buildContent({
    files,
    provenance: { ...snapshot.provenance, mode: 'local' },
    release: null,
    releases: [],
  });
  assert.deepEqual(
    local.changelog.map((entry) => entry.version),
    ['Unreleased', '2.0.0', '1.7.3'],
  );
  assert(local.changelog.every((entry) => entry.date === null && entry.url === null));
  assert.match(local.changelog[1].html, /Branch changes/);
  assert.match(local.changelog[2].html, /Past fix/);
});

test('production API failure cannot fall back to local content', async () => {
  await assert.rejects(
    importContent({ mode: 'production', fetchImpl: async () => new Response('', { status: 404 }) }),
    /returned 404/,
  );
  await assert.rejects(fixtureSnapshot(), /explicit --fixture/);
});

test('legacy screenshot mappings never guess optional website-owned features', () => {
  const files = new Map([
    ['fastlane/metadata/android/en-US/images/phoneScreenshots/1.png', Buffer.from('current')],
    ['fastlane/metadata/android/en-US/images/phoneScreenshots/6.png', Buffer.from('unrelated')],
  ]);
  const omitted = [];
  const selected = selectMediaInputs(files, true, omitted);
  assert.deepEqual(
    selected.map((media) => media.name),
    ['current'],
  );
  assert.equal(selected[0].bytes.toString(), 'current');
  assert(omitted.some((reason) => reason.startsWith('media/history:')));
});

async function syntheticWebsiteMedia(t) {
  const directory = await mkdtemp(path.join(tmpdir(), 'battery-website-media-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const filenames = [
    'current.png',
    'history.png',
    'alarms.png',
    'notification.png',
    'widgets.png',
    'backups.png',
    'advanced.png',
    'notification.mp4',
    'backups.mp4',
    'advanced.mp4',
    'widgets.mp4',
  ];
  const png = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/tasAAAAASUVORK5CYII=',
    'base64',
  );
  const mp4 = Buffer.from([
    0, 0, 0, 20, 102, 116, 121, 112, 105, 115, 111, 109, 0, 0, 0, 0, 105, 115, 111, 109,
  ]);
  const expected = new Map();
  for (const filename of filenames) {
    const bytes = Buffer.concat([filename.endsWith('.mp4') ? mp4 : png, Buffer.from(filename)]);
    expected.set(filename, bytes);
    await writeFile(path.join(directory, filename), bytes);
  }
  return { directory, expected };
}

test('website media discovers fixed files and gates future captures outside local preview', async (t) => {
  const fixture = await syntheticWebsiteMedia(t);
  const load = (mode, version) => loadWebsiteMedia({ mode, version, directory: fixture.directory });
  const local = await load('local', '1.7.3');
  assert.equal(local.images.length, 7);
  assert.equal(local.videos.length, 4);
  for (const mode of ['production', 'fixture']) {
    const old = await load(mode, '1.7.3');
    assert.deepEqual(old.images, []);
    assert.deepEqual(old.videos, []);
    assert.deepEqual(old.sources, []);
  }
  for (const version of ['2.0', '2.0.0', '2.1.0', '10.0.0'])
    assert.equal((await load('production', version)).videos.length, 4);
  assert.equal((await load('production', '1.99.99')).videos.length, 0);
  await writeFile(path.join(fixture.directory, 'unlisted.mp4'), 'not selected');
  assert.equal((await load('local', '1.7.3')).videos.length, 4);
  const absent = await loadWebsiteMedia({
    mode: 'local',
    version: '1.7.3',
    directory: path.join(fixture.directory, 'missing'),
  });
  assert.deepEqual(absent, { images: [], videos: [], sources: [] });
  const changed = Buffer.from(fixture.expected.get('current.png'));
  changed[changed.length - 1] ^= 1;
  await writeFile(path.join(fixture.directory, 'current.png'), changed);
  const updated = await load('local', '1.7.3');
  assert.deepEqual(updated.images.find((image) => image.name === 'current').bytes, changed);
  assert.equal(
    updated.sources.find((item) => item.path.endsWith('/current.png')).sha256,
    createHash('sha256').update(changed).digest('hex'),
  );
  changed[0] ^= 1;
  await writeFile(path.join(fixture.directory, 'current.png'), changed);
  await assert.rejects(load('local', '1.7.3'), /invalid file signature/);
});

test('website media copies exact bytes and separate provenance while old stable builds remove it', async (t) => {
  const fixture = await syntheticWebsiteMedia(t);
  const outputDirectory = await mkdtemp(path.join(tmpdir(), 'battery-media-output-'));
  t.after(() => rm(outputDirectory, { recursive: true, force: true }));
  const selected = await importContent({
    mode: 'local',
    mediaDirectory: fixture.directory,
    outputDirectory,
  });
  for (const [filename, bytes] of fixture.expected) {
    const name = path.basename(filename, path.extname(filename));
    const map = filename.endsWith('.mp4') ? selected.videos : selected.media;
    assert.equal(path.dirname(map[name]), path.join(outputDirectory, 'assets'));
    assert.deepEqual(await readFile(map[name]), bytes);
    assert.equal(
      selected.provenance.websiteSources.find(
        (item) => item.path === `website/src/assets/app/${filename}`,
      ).sha256,
      createHash('sha256').update(bytes).digest('hex'),
    );
  }
  assert(!selected.provenance.sources.some((item) => item.path.startsWith('website/')));
  const newImage = Buffer.concat([fixture.expected.get('current.png'), Buffer.from('retake')]);
  const newAdvanced = Buffer.concat([fixture.expected.get('advanced.mp4'), Buffer.from('retake')]);
  const newVideo = Buffer.concat([fixture.expected.get('notification.mp4'), Buffer.from('retake')]);
  const newWidgets = Buffer.concat([fixture.expected.get('widgets.mp4'), Buffer.from('retake')]);
  await writeFile(path.join(fixture.directory, 'current.png'), newImage);
  await writeFile(path.join(fixture.directory, 'advanced.mp4'), newAdvanced);
  await writeFile(path.join(fixture.directory, 'notification.mp4'), newVideo);
  await writeFile(path.join(fixture.directory, 'widgets.mp4'), newWidgets);
  const retaken = await importContent({
    mode: 'local',
    mediaDirectory: fixture.directory,
    outputDirectory,
  });
  assert.notEqual(retaken.media.current, selected.media.current);
  assert.notEqual(retaken.videos.advanced, selected.videos.advanced);
  assert.notEqual(retaken.videos.notification, selected.videos.notification);
  assert.notEqual(retaken.videos.widgets, selected.videos.widgets);
  assert.equal(retaken.media.history, selected.media.history);
  assert.deepEqual(await readFile(retaken.media.current), newImage);
  assert.deepEqual(await readFile(retaken.videos.advanced), newAdvanced);
  assert.deepEqual(await readFile(retaken.videos.notification), newVideo);
  assert.deepEqual(await readFile(retaken.videos.widgets), newWidgets);
  for (const obsolete of [
    selected.media.current,
    selected.videos.advanced,
    selected.videos.notification,
    selected.videos.widgets,
  ])
    await assert.rejects(readFile(obsolete), { code: 'ENOENT' });
  const older = await importContent({
    mode: 'fixture',
    fixture: 'tests/fixtures/content',
    mediaDirectory: fixture.directory,
    outputDirectory,
  });
  assert.deepEqual(older.videos, {});
  assert.deepEqual(older.media, {});
  assert(!older.provenance.websiteSources.some((item) => /\.(png|mp4)$/.test(item.path)));
  for (const generated of [...Object.values(retaken.media), ...Object.values(retaken.videos)])
    await assert.rejects(readFile(generated), {
      code: 'ENOENT',
    });
});

test('website funding changes independently of the pinned app release with explicit provenance', async () => {
  const snapshot = await fixtureSnapshot('tests/fixtures/content');
  const make = (funding) =>
    applyWebsiteFunding(
      buildContent({ ...snapshot, provenance: { ...snapshot.provenance, sources: [] } }),
      Buffer.from(funding),
    );
  const initial = make('github: [tswistak]\ncustom: [https://suppi.pl/tswistak]\n');
  const updated = make(
    'github: [tswistak]\nliberapay: configured-profile\ncustom: [https://suppi.pl/tswistak]\n',
  );
  assert.equal(initial.funding.liberapay, null);
  assert.equal(updated.funding.liberapay, 'https://liberapay.com/configured-profile/donate');
  assert.equal(updated.provenance.appCommit, initial.provenance.appCommit);
  assert.deepEqual(updated.release, initial.release);
  assert.equal(updated.provenance.websiteSources[0].path, '.github/FUNDING.yml');
  assert.notEqual(
    updated.provenance.websiteSources[0].sha256,
    initial.provenance.websiteSources[0].sha256,
  );
  assert(!snapshot.files.has('.github/FUNDING.yml'));
});

test('snapshot reading rejects traversal and symlink escapes', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'battery-content-'));
  try {
    await writeFile(path.join(root, 'safe.txt'), 'safe');
    await symlink(path.join(root, 'safe.txt'), path.join(root, 'link.txt'));
    await assert.rejects(safeRead(root, '../safe.txt'), /Invalid source path/);
    await assert.rejects(safeRead(root, 'link.txt'), /symlinks/);
    assert.equal((await safeRead(root, 'safe.txt')).toString(), 'safe');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('publication requires operational disclosures even when local policy omits unresolved facts', async () => {
  const fixture = JSON.parse(await readFile('tests/fixtures/privacy.json', 'utf8'));
  const env = { MATOMO_ENABLED: 'true', MATOMO_SITE_ID: '7' };
  assert.equal(
    validatePublication(loadSiteConfig({ env, privacy: fixture })).publicationReady,
    true,
  );
  for (const [group, key] of [
    ['hosting', 'processingLocations'],
    ['hosting', 'transferSafeguards'],
    ['hosting', 'accessLogRetentionDays'],
    ['contact', 'providerInformation'],
    ['contact', 'transferSafeguards'],
    ['analytics', 'processingLocations'],
    ['analytics', 'transferSafeguards'],
    ['analytics', 'proxyLogData'],
    ['analytics', 'rawRetentionDays'],
    ['analytics', 'trackerResponseCookiePolicy'],
    ['analytics', 'consentEvidenceProcedure'],
    ['review', 'assessmentRecord'],
  ]) {
    const privacy = structuredClone(fixture);
    privacy[group][key] = null;
    assert.throws(
      () => loadSiteConfig({ env, privacy }),
      new RegExp(`privacy\\.${group}\\.${key}`),
    );
    if (group !== 'analytics') {
      const local = loadSiteConfig({ env: {}, privacy });
      assert.equal(local.analytics.enabled, false);
      assert.throws(() => validatePublication(local), /Publication blocked/);
    }
  }
});

test('validated privacy facts reach the policy in both modes and optional notice dimensions are bounded', async () => {
  const privacy = JSON.parse(await readFile('tests/fixtures/privacy.json', 'utf8'));
  privacy.hosting.provider = 'Synthetic host <unsafe> & partners';
  for (const enabled of ['false', 'true']) {
    const config = loadSiteConfig({
      env: { MATOMO_ENABLED: enabled, MATOMO_SITE_ID: '7', MATOMO_CONSENT_DIMENSION_ID: '3' },
      privacy,
    });
    const sections = getPrivacySections(config);
    const delivery = sections.find((section) => section.id === 'website-delivery').html;
    assert(delivery.includes('<dl class="privacy-facts">'));
    assert(delivery.includes('Synthetic host &lt;unsafe&gt; &amp; partners'));
    for (const section of [
      privacy.hosting,
      privacy.contact,
      ...(enabled === 'true' ? [privacy.analytics] : []),
    ]) {
      const html = sections.map((section) => section.html).join('');
      for (const key of [
        'processingLocations',
        'processorAgreement',
        'subprocessorInformation',
        'transferSafeguards',
      ]) {
        if (typeof section[key] === 'string') assert(html.includes(section[key]));
      }
    }
    assert.equal(config.analytics.consentDimensionId, 3);
  }
  assert.equal(loadSiteConfig({ env: {}, privacy }).analytics.consentDimensionId, null);
  for (const value of ['0', '1000', '-1', '1.5', 'arbitrary']) {
    assert.throws(
      () => loadSiteConfig({ env: { MATOMO_CONSENT_DIMENSION_ID: value }, privacy }),
      /MATOMO_CONSENT_DIMENSION_ID/,
    );
  }
});

test('workflow consent variables configure the site consistently and reject incompatible lifetimes', async () => {
  const privacy = JSON.parse(await readFile('tests/fixtures/privacy.json', 'utf8'));
  const variables = {
    ANALYTICS_CONSENT_VERSION: 'analytics-fixture-short-v2',
    CONSENT_VALIDITY_DAYS: '30',
    MATOMO_VISITOR_COOKIE_SECONDS: '1209600',
    MATOMO_SESSION_COOKIE_SECONDS: '900',
  };
  const workflow = parseYaml(await readFile('../.github/workflows/website-deploy.yml', 'utf8'));
  for (const key of Object.keys(variables))
    assert.equal(workflow.jobs.website.env[key], '${{ vars.' + key + ' }}');

  privacy.analytics.visitorCookieSeconds = 1209600;
  privacy.analytics.sessionCookieSeconds = 900;
  const env = {
    ...variables,
    MATOMO_ENABLED: 'true',
    MATOMO_SITE_ID: '7',
    MATOMO_URL: 'https://analytics.invalid/',
  };
  const config = validatePublication(loadSiteConfig({ env, privacy }));
  assert.deepEqual(config.analytics, {
    enabled: true,
    matomoUrl: 'https://analytics.invalid/',
    siteId: '7',
    consentDimensionId: null,
    consentVersion: 'analytics-fixture-short-v2',
    consentDays: 30,
    cookieVisitorSeconds: 1209600,
    cookieSessionSeconds: 900,
  });
  const storage = getPrivacySections(config).find((section) => section.id === 'storage').html;
  for (const value of ['30 days of decision validity', 'Up to 14 days', 'Up to 15 minutes'])
    assert(storage.includes(value));

  const defaults = loadSiteConfig({ env: {}, privacy }).analytics;
  assert.equal(defaults.enabled, false);
  assert.equal(defaults.consentVersion, 'analytics-2026-11-09-v1');
  assert.equal(defaults.consentDays, 180);
  assert.equal(defaults.cookieVisitorSeconds, 15552000);
  assert.equal(defaults.cookieSessionSeconds, 1800);
  assert.deepEqual(
    loadSiteConfig({
      env: Object.fromEntries(Object.keys(variables).map((key) => [key, ''])),
      privacy,
    }).analytics,
    defaults,
  );
  assert.equal(
    loadSiteConfig({ env: { ...env, MATOMO_ENABLED: 'false' }, privacy }).analytics.enabled,
    false,
  );

  const now = Date.parse('2026-11-10T12:00:00Z');
  const previous = makeReceipt('accept', defaults.consentVersion, 30, now);
  assert.equal(
    readReceipt(JSON.stringify(previous), config.analytics.consentVersion, 30, now),
    null,
  );
  const current = makeReceipt('accept', config.analytics.consentVersion, 30, now);
  assert.equal(current.expiresAt - current.decidedAt, 30 * DAY_MS);
  assert.deepEqual(
    readReceipt(JSON.stringify(current), config.analytics.consentVersion, 30, now),
    current,
  );

  for (const [key, value] of [
    ['ANALYTICS_CONSENT_VERSION', 'invalid notice version'],
    ['CONSENT_VALIDITY_DAYS', '0'],
    ['CONSENT_VALIDITY_DAYS', '366'],
    ['CONSENT_VALIDITY_DAYS', '1.5'],
    ['MATOMO_VISITOR_COOKIE_SECONDS', '0'],
    ['MATOMO_VISITOR_COOKIE_SECONDS', '2592001'],
    ['MATOMO_SESSION_COOKIE_SECONDS', '0'],
    ['MATOMO_SESSION_COOKIE_SECONDS', '86401'],
  ])
    assert.throws(
      () => loadSiteConfig({ env: { ...env, [key]: value }, privacy }),
      new RegExp(key),
    );
  assert.throws(
    () => loadSiteConfig({ env: { CONSENT_VALIDITY_DAYS: '30' }, privacy }),
    /MATOMO_VISITOR_COOKIE_SECONDS cannot outlive the consent validity period/,
  );
});
