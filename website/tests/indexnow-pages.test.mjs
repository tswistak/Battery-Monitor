import assert from 'node:assert/strict';
import test from 'node:test';
import { sha256 } from '../scripts/deploy/core.mjs';
import {
  INDEXNOW_ORIGIN,
  hasRobotsNoindex,
  isIndexNowPageUrl,
  planIndexNow,
  planLegacyIndexNowPreview,
  readIndexNowPages,
} from '../scripts/deploy/indexnow-pages.mjs';

const home = INDEXNOW_ORIGIN + '/';
const help = home + 'help/';
const alarms = help + 'battery-alarms/';
const sitemap = (urls) =>
  '<urlset>' + urls.map((url) => `<url><loc>${url}</loc></url>`).join('') + '</urlset>';
const html = (url, meta = '') =>
  `<html><head><link href="${url}" rel=canonical>${meta}</head><body></body></html>`;
const manifestFor = (files) => ({
  files: Object.entries(files).map(([path, bytes]) => ({ path, sha256: sha256(bytes) })),
});
const inventory = (files) => readIndexNowPages(manifestFor(files), (path) => files[path]);

test('only canonical indexable directory pages in referenced sitemaps enter the inventory', async () => {
  const files = {
    'sitemap-index.xml': `<sitemapindex><sitemap><loc>${home}sitemap-0.xml</loc></sitemap><sitemap><loc>${home}sitemap-1.xml</loc></sitemap><sitemap><loc>https://preview.example/sitemap.xml</loc></sitemap></sitemapindex>`,
    'sitemap-0.xml': sitemap([
      help,
      home,
      home + 'alias/',
      home + 'private/',
      home + '404/',
      home + 'site/hidden/',
    ]),
    'sitemap-1.xml': sitemap([
      alarms,
      help,
      home + 'llms.txt',
      home + '_astro/script.js',
      home + 'missing/',
    ]),
    'unreferenced.xml': sitemap([home + 'unlisted/']),
    'index.html': html(home),
    'help/index.html': html('/help/'),
    'help/battery-alarms/index.html': html(alarms),
    'alias/index.html': html(help),
    'private/index.html': html(home + 'private/', "<meta CONTENT='noindex, follow' NAME='ROBOTS'>"),
    'unlisted/index.html': html(home + 'unlisted/'),
    'site/hidden/index.html': html(home + 'site/hidden/'),
    '404.html': html(home + '404/'),
    'robots.txt': 'User-agent: *\nAllow: /\n',
  };
  assert.deepEqual(await inventory(files), {
    [home]: sha256(files['index.html']),
    [help]: sha256(files['help/index.html']),
    [alarms]: sha256(files['help/battery-alarms/index.html']),
  });
  assert.deepEqual(Object.keys(await inventory(files)), [home, help, alarms]);
});

test('public page URLs exclude technical hosts, path aliases, internal prefixes and files', () => {
  for (const url of [
    'https://cdn.b-cdn.net/help/',
    'https://preview.example/help/',
    'http://localhost/',
    'https://user@batterymonitor.swistak.codes/',
    home + '#',
    home + '?',
    help + '?t=1',
    home + 'download/app.apk',
    home + '404.html',
    home + '404/',
    home + 'site/',
    home + 'website-deploy-state/record/',
    home + '_astro/script/',
    home + 'sitemap.xml',
    home + 'robots.txt',
    home + 'llms.txt',
    home + 'key.txt',
    home + 'help',
    home + 'a/../help/',
    home + '%68elp/',
    home + '/help/',
  ])
    assert.equal(isIndexNowPageUrl(url), false, url);
  for (const url of [home, help, alarms]) assert.equal(isIndexNowPageUrl(url), true);
});

test('robots metadata inspection handles directive lists and ignores examples in comments and scripts', () => {
  for (const directives of ['none', 'nofollow, noindex', 'NOINDEX,follow']) {
    assert.equal(
      hasRobotsNoindex(html(home, `<meta name='robots' content='${directives}'>`)),
      true,
    );
  }
  const example = '<meta name="robots" content="noindex">';
  for (const contents of [
    `<!-- ${example} -->`,
    `<script>const example = '${example}';</script>`,
    '<meta name="robots" content="nofollow">',
  ])
    assert.equal(hasRobotsNoindex(html(home, contents)), false);
});

test('old sitemap and HTML must match trusted hashes when reconstructing a legacy baseline', async () => {
  const files = { 'sitemap.xml': sitemap([home]), 'index.html': html(home) };
  for (const changed of Object.keys(files)) {
    await assert.rejects(
      readIndexNowPages(
        manifestFor(files),
        (path) => files[path] + (path === changed ? 'changed' : ''),
      ),
      /checksum mismatch/,
    );
  }
  const previous = { pages: await inventory(files), pending: [] };
  const next = { ...files, 'sitemap.xml': sitemap([help, home]), 'help/index.html': html(help) };
  assert.deepEqual(planIndexNow(await inventory(next), previous).pending, [
    { url: help, kind: 'update' },
  ]);
  const assetOnly = { ...files, 'some-key.txt': 'public key', 'asset.css': 'body { margin: 0 }' };
  assert.deepEqual(planIndexNow(await inventory(assetOnly), previous).pending, []);
  assert.deepEqual(planIndexNow(await inventory(files)).pending, [{ url: home, kind: 'update' }]);
});

test('moved and newly noindex pages stay pending until delivery accepts the original URLs', async () => {
  const oldFiles = {
    'sitemap.xml': sitemap([home, help, alarms]),
    'index.html': html(home),
    'help/index.html': html(help),
    'help/battery-alarms/index.html': html(alarms),
  };
  const previous = { pages: await inventory(oldFiles), pending: [] };
  const moved = home + 'alarms/';
  const next = await inventory({
    'sitemap.xml': sitemap([home, help, moved]),
    'index.html': html(home),
    'help/index.html': html(help, '<meta name=robots content=none>'),
    'alarms/index.html': html(moved),
    'help/battery-alarms/index.html': oldFiles['help/battery-alarms/index.html'],
  });
  const plan = planIndexNow(next, previous);
  const removals = [
    { url: help, kind: 'removal' },
    { url: alarms, kind: 'removal' },
  ];
  assert.deepEqual(plan.pending, [{ url: moved, kind: 'update' }, ...removals]);
  const partial = { ...plan, pending: removals };
  assert.deepEqual(planIndexNow(next, partial).pending, removals);
  const restored = { ...next, [help]: previous.pages[help] };
  assert.deepEqual(planIndexNow(restored, partial).pending, [
    { url: help, kind: 'update' },
    removals[1],
  ]);
});

test('changed page versions retry without another content change, including after a later revision', async () => {
  const files = { 'sitemap.xml': sitemap([home]), 'index.html': html(home) };
  const previous = { pages: await inventory(files), pending: [] };
  const firstChange = await inventory({
    ...files,
    'index.html': html(home).replace('<body>', '<body>changed'),
  });
  const plan = planIndexNow(firstChange, previous);
  assert.deepEqual(plan.pending, [{ url: home, kind: 'update' }]);
  assert.deepEqual(planIndexNow(firstChange, plan), plan);
  const accepted = { ...plan, pending: [] };
  assert.deepEqual(planIndexNow(firstChange, accepted).pending, []);
  assert.deepEqual(planIndexNow(previous.pages, accepted).pending, [{ url: home, kind: 'update' }]);
  assert.deepEqual(
    planLegacyIndexNowPreview(firstChange, manifestFor(files)).pending,
    plan.pending,
  );
});

test('release-only analytics metadata retains its page version while visible and structured changes select it', async () => {
  function files(
    analyticsVersion,
    listedVersion = '2.0',
    structuredVersion = '2.0',
    enabled = false,
  ) {
    const analytics = JSON.stringify({
      config: { enabled },
      page: { id: 'home' },
      appVersion: analyticsVersion,
    });
    return {
      'sitemap.xml': sitemap([home]),
      'index.html': html(home)
        .replace(
          '</head>',
          `<script type="application/ld+json">{"softwareVersion":"${structuredVersion}"}</script></head>`,
        )
        .replace(
          '<body>',
          `<body>Version ${listedVersion}<script id="analytics-config" type="application/json">${analytics}</script>`,
        ),
    };
  }
  const original = files('2.0');
  const previous = { pages: await inventory(original), pending: [] };
  const release = files('2.1');
  const current = await inventory(release);
  assert.equal(current[home].sha256, sha256(release['index.html']));
  assert.notEqual(current[home].sha256, previous.pages[home].sha256);
  assert.deepEqual(planIndexNow(current, previous).pending, []);
  assert.deepEqual(planLegacyIndexNowPreview(current, manifestFor(release)).pending, []);
  assert.deepEqual(planLegacyIndexNowPreview(current, manifestFor(original)).pending, [
    { url: home, kind: 'update' },
  ]);
  for (const changed of [
    files('2.1', '2.1'),
    files('2.1', '2.0', '2.1'),
    files('2.1', '2.0', '2.0', true),
  ]) {
    assert.deepEqual(planIndexNow(await inventory(changed), previous).pending, [
      { url: home, kind: 'update' },
    ]);
  }
  assert.deepEqual(
    await readIndexNowPages(manifestFor(release), (path) => release[path], current),
    current,
  );
  const unlisted = { ...release, 'sitemap.xml': sitemap([]) };
  assert.deepEqual(
    await readIndexNowPages(manifestFor(unlisted), (path) => unlisted[path], current),
    {},
  );
});
