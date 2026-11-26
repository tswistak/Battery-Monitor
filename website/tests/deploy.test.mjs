import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { mkdtemp, mkdir, readFile, readdir, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  assertCurrent,
  configFromEnv,
  createManifest,
  destination,
  decodeState,
  decodePending,
  encodeState,
  encodePending,
  planDeployment,
  publishedOwnership,
  publishSite,
  request,
  safePath,
  scanDist,
  sha256,
  storageUrl,
  stateUrl,
  validateManifest,
} from '../scripts/deploy/core.mjs';
import { smokeSite } from '../scripts/deploy/smoke.mjs';
import { runPublish } from '../scripts/deploy/publish.mjs';

const WEBSITE = 'a'.repeat(40);
const APP = 'b'.repeat(40);
const TAG = 'c'.repeat(40);
const indexNowEndpoint = 'https://api.indexnow.org/indexnow';
const testIndexNowKey = 'synthetic-test-key-1';
const provenance = {
  mode: 'production',
  websiteCommit: WEBSITE,
  appCommit: APP,
  version: '2.0.0',
  releaseId: 42,
  tag: 'v2.0.0',
  publishedAt: '2026-01-01T00:00:00Z',
  releaseAssets: {
    apk: {
      id: 3,
      name: 'battery-monitor-2.0.0.apk',
      size: 500,
      updatedAt: '2026-01-01T00:00:00.000Z',
      url: 'https://github.com/tswistak/Battery-Monitor/releases/download/v2.0.0/battery-monitor-2.0.0.apk',
      digest: null,
    },
    signature: null,
  },
};
const env = {
  WEBSITE_DEPLOY_ENABLED: 'true',
  WEBSITE_URL: 'https://batterymonitor.swistak.codes/',
  BUNNY_STORAGE_ENDPOINT: 'https://storage.bunnycdn.com/',
  BUNNY_STORAGE_ZONE: 'website-zone',
  BUNNY_STORAGE_PREFIX: '/battery/',
  BUNNY_PULL_ZONE_ID: '123',
  BUNNY_STORAGE_ACCESS_KEY: 'test-storage-password',
  BUNNY_API_KEY: 'test-cdn-api-key',
  GITHUB_TOKEN: 'test-github-read-token',
  WEBSITE_DEPLOY_STATE_KEY: '9'.repeat(64),
  WEBSITE_DEPLOY_BOOTSTRAP: 'true',
};
const config = configFromEnv(env, { publishing: true });
const json = (value) =>
  new Response(JSON.stringify(value), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  });

async function fixture(t, asset = 'app.first123.js') {
  const root = await mkdtemp(join(tmpdir(), 'battery-site-deploy-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const dist = join(root, 'dist');
  await mkdir(join(dist, '_astro'), { recursive: true });
  await mkdir(join(dist, 'download'), { recursive: true });
  await writeFile(join(dist, 'index.html'), '<h1>Battery Monitor</h1>');
  await writeFile(join(dist, 'download/index.html'), '<h1>Get Battery Monitor 2.0.0</h1>');
  await writeFile(join(dist, '404.html'), '<h1>Page not found</h1>');
  await writeFile(
    join(dist, 'robots.txt'),
    'User-agent: *\nSitemap: https://batterymonitor.swistak.codes/sitemap.xml\n',
  );
  await writeFile(
    join(dist, 'sitemap.xml'),
    '<urlset><url><loc>https://batterymonitor.swistak.codes/</loc></url></urlset>',
  );
  await writeFile(join(dist, '_astro', asset), 'export const ready = true;');
  return { root, dist, asset };
}

function mockNetwork({
  currentProvenance = provenance,
  head = () => currentProvenance.websiteCommit,
  releaseId = currentProvenance.releaseId,
  failPut = false,
  badChecksum = false,
  assetId = currentProvenance.releaseAssets.apk.id,
  assetUpdatedAt = currentProvenance.releaseAssets.apk.updatedAt,
  assetDigest = null,
  initialStorage = new Map(),
  smokeFailure = false,
  purgeFailure = false,
  stateFailure = false,
  pendingFailure = false,
  publicState = false,
  websiteUrl = env.WEBSITE_URL,
  indexNowStatus = 200,
  indexNowHeaders,
  publicPagePaths = [],
} = {}) {
  const calls = [];
  const storage = new Map(initialStorage);
  let heads = 0;
  let assetReads = 0;
  async function fetchFn(input, options = {}) {
    const url = new URL(input);
    const method = options.method || 'GET';
    calls.push({
      url: url.href,
      method,
      headers: options.headers,
      body: options.body,
      redirect: options.redirect,
    });
    if (url.hostname === 'api.github.com') {
      if (url.pathname.endsWith('/Battery-Monitor')) return json({ default_branch: 'main' });
      if (url.pathname.includes('/git/ref/heads/'))
        return json({ object: { type: 'commit', sha: head(++heads) } });
      if (url.pathname.endsWith('/releases'))
        return json([
          {
            id: 999,
            draft: false,
            prerelease: true,
            tag_name: 'v3.0.0-beta',
            published_at: '2026-12-01T00:00:00Z',
          },
          {
            id: releaseId,
            draft: false,
            prerelease: false,
            tag_name: currentProvenance.tag,
            published_at: currentProvenance.publishedAt,
            html_url: `https://github.com/tswistak/Battery-Monitor/releases/tag/${currentProvenance.tag}`,
          },
        ]);
      if (url.pathname.includes('/git/ref/tags/'))
        return json({ object: { type: 'tag', sha: TAG } });
      if (url.pathname.includes('/git/tags/'))
        return json({ object: { type: 'commit', sha: currentProvenance.appCommit } });
      if (url.pathname.endsWith('/assets'))
        return json([
          {
            id: typeof assetId === 'function' ? assetId(++assetReads) : assetId,
            name: currentProvenance.releaseAssets.apk.name,
            state: 'uploaded',
            size: currentProvenance.releaseAssets.apk.size,
            updated_at: assetUpdatedAt,
            digest: assetDigest,
            browser_download_url: currentProvenance.releaseAssets.apk.url,
          },
        ]);
      throw new Error('Unexpected GitHub request');
    }
    if (url.hostname === 'api.bunny.net')
      return new Response(null, { status: purgeFailure ? 400 : 204 });
    if (url.href === indexNowEndpoint)
      return new Response(null, { status: indexNowStatus, headers: indexNowHeaders });
    if (url.origin === new URL(websiteUrl).origin) {
      if (url.pathname.startsWith('/website-deploy-state/')) {
        assert.equal(options.headers?.AccessKey, undefined);
        const bytes = storage.get('/website-zone' + url.pathname);
        return new Response(publicState && bytes ? bytes : null, {
          status: publicState && bytes ? 200 : 404,
        });
      }
      if (smokeFailure || url.pathname.includes('__battery-monitor-smoke'))
        return new Response('missing', { status: 404 });
      const path =
        '/website-zone/battery' +
        (url.pathname.endsWith('/') ? url.pathname + 'index.html' : url.pathname);
      if (publicPagePaths.includes(url.pathname) && !storage.has(path))
        return new Response(null, { status: 404 });
      const bytes = storage.get(path) || Buffer.from('<h1>Battery Monitor 2.0.0</h1>');
      return new Response(bytes, {
        headers: {
          'content-type': url.pathname.endsWith('/') ? 'text/html' : 'application/octet-stream',
          'referrer-policy': 'no-referrer',
          'x-content-type-options': 'nosniff',
          'content-security-policy': "default-src 'self'; object-src 'none'; base-uri 'self'",
          'cache-control': url.pathname.startsWith('/_astro/')
            ? 'public,max-age=31536000,immutable'
            : 'public,max-age=300',
        },
      });
    }
    if (url.hostname.endsWith('storage.bunnycdn.com')) {
      if (method === 'PUT') {
        if (
          (typeof failPut === 'function' ? failPut(url) : failPut) ||
          (stateFailure &&
            url.pathname.includes('/website-deploy-state/') &&
            !url.pathname.endsWith('.pending.json')) ||
          (pendingFailure && url.pathname.endsWith('.pending.json'))
        )
          return new Response(null, { status: 400 });
        assert.ok(Buffer.isBuffer(options.body));
        assert.equal(options.headers.Checksum, sha256(options.body).toUpperCase());
        assert.equal(options.headers.AccessKey, env.BUNNY_STORAGE_ACCESS_KEY);
        storage.set(url.pathname, options.body);
        return new Response(null, { status: 201 });
      }
      if (method === 'DELETE') {
        storage.delete(url.pathname);
        return new Response(null, { status: 200 });
      }
      if (url.pathname.endsWith('/')) {
        const entries = new Map();
        for (const [path, bytes] of storage) {
          if (!path.startsWith(url.pathname)) continue;
          const relative = path.slice(url.pathname.length);
          const name = relative.split('/')[0];
          const directory = relative.includes('/');
          entries.set(name, {
            ObjectName: name,
            IsDirectory: directory,
            Length: directory ? 0 : bytes.length,
            Path: url.pathname,
            StorageZoneName: env.BUNNY_STORAGE_ZONE,
          });
        }
        return json([...entries.values()]);
      }
      if (!storage.has(url.pathname)) return new Response(null, { status: 404 });
      return new Response(badChecksum ? Buffer.from('wrong content') : storage.get(url.pathname), {
        status: 200,
      });
    }
    throw new Error(`Unexpected network destination ${url.hostname}`);
  }
  return { calls, storage, fetchFn, delay: async () => {} };
}

async function history(t) {
  const f = await fixture(t, 'app.old123.js');
  let previous = await createManifest(f.dist, provenance, { now: '2026-01-01T00:00:00Z' });
  previous = {
    ...previous,
    publishedAt: '2026-01-01T00:00:01Z',
    destination: destination(config),
    owned: publishedOwnership(previous, '2026-01-01T00:00:01Z'),
  };
  await rm(join(f.dist, '_astro/app.old123.js'));
  await writeFile(join(f.dist, '_astro/app.previous456.js'), 'previous');
  let second = await createManifest(f.dist, provenance, { previous, now: '2026-01-09T00:00:00Z' });
  second = {
    ...second,
    publishedAt: '2026-01-09T00:00:01Z',
    destination: destination(config),
    owned: publishedOwnership(second, '2026-01-09T00:00:01Z'),
  };
  await rm(join(f.dist, '_astro/app.previous456.js'));
  await writeFile(join(f.dist, '_astro/app.current789.js'), 'current');
  const current = await createManifest(f.dist, provenance, {
    previous: second,
    now: '2026-01-17T00:00:00Z',
  });
  return { ...f, previous: second, current };
}

async function indexNowSite(t, websiteUrl = env.WEBSITE_URL) {
  const { root, dist } = await fixture(t);
  const artifacts = join(root, '.artifacts');
  await mkdir(artifacts);
  const summaryPath = join(root, 'summary.md');
  const absolute = (path) => new URL(path, websiteUrl).href;
  async function page(path, title) {
    const dir = join(dist, path);
    await mkdir(dir, { recursive: true });
    await writeFile(
      join(dir, 'index.html'),
      `<html><head><link rel="canonical" href="${absolute(path)}"></head><body><h1>${title}</h1></body></html>`,
    );
  }
  async function sitemap(...paths) {
    const urls = paths.map((path) => `<url><loc>${absolute(path)}</loc></url>`).join('');
    await writeFile(join(dist, 'sitemap.xml'), `<urlset>${urls}</urlset>`);
  }
  async function deploy(network, overrides = {}, args = ['--publish']) {
    const manifest = await createManifest(dist, provenance);
    await writeFile(join(root, '.artifacts/deployment-manifest.json'), JSON.stringify(manifest));
    return runPublish({
      root,
      args,
      env: {
        ...env,
        WEBSITE_URL: websiteUrl,
        GITHUB_ACTIONS: 'true',
        WEBSITE_DEPLOY_AUTHORIZED: 'true',
        INDEXNOW_KEY: testIndexNowKey,
        GITHUB_STEP_SUMMARY: summaryPath,
        ...overrides,
      },
      http: network,
      validatePolicy: async () => {},
    });
  }
  await page('/', 'Home');
  await sitemap('/');
  return { dist, deploy, page, sitemap, absolute, summaryPath, artifacts };
}

test('IndexNow migrates trusted pages, retries an unchanged deployment and waits for public removal', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: Date.parse('2026-01-01T00:00:00Z') });
  const site = await indexNowSite(t);
  await site.page('/retired/', 'Old article');
  await site.sitemap('/', '/retired/');
  const initial = mockNetwork();
  await site.deploy(initial, { GITHUB_ACTIONS: undefined, WEBSITE_DEPLOY_AUTHORIZED: undefined });
  const statePath = new URL(stateUrl(config)).pathname;
  const state = (network) => decodeState(network.storage.get(statePath), config);
  const original = state(initial);
  delete original.indexNow;
  initial.storage.set(statePath, encodeState(original, config));
  assert.ok(initial.calls.every((call) => call.url !== indexNowEndpoint));

  await rm(join(site.dist, 'retired'), { recursive: true });
  await site.page('/replacement/', 'Moved article');
  await site.sitemap('/', '/replacement/');
  const nextRun = (after, options = {}) =>
    mockNetwork({
      initialStorage: after.storage,
      publicPagePaths: ['/retired/'],
      ...options,
    });
  const submitted = (network) =>
    network.calls
      .filter((call) => call.url === indexNowEndpoint)
      .map((call) => JSON.parse(call.body).urlList);
  const replacement = site.absolute('/replacement/');
  const retired = site.absolute('/retired/');

  const rejected = nextRun(initial, { indexNowStatus: 422 });
  const published = await site.deploy(rejected);
  assert.equal(published.status, 'published');
  assert.deepEqual(submitted(rejected), [[replacement]]);
  assert.deepEqual(
    state(rejected)
      .indexNow.pending.map(({ url }) => url)
      .sort(),
    [replacement, retired],
  );
  assert.equal(published.indexNow.deferred, 1);
  assert.match(await readFile(site.summaryPath, 'utf8'), /422/);
  const firstPost = rejected.calls.findIndex((call) => call.url === indexNowEndpoint);
  const priorCalls = rejected.calls.slice(0, firstPost);
  const write = priorCalls.findLastIndex(
    (call) => call.url === stateUrl(config) && call.method === 'PUT',
  );
  const verified = priorCalls.findLastIndex(
    (call) => call.url === stateUrl(config) && call.method === 'GET',
  );
  const smoke = priorCalls.findIndex((call) => call.url.includes('__battery-monitor-smoke'));
  assert.ok(smoke >= 0 && smoke < write && write < verified);

  const retry = nextRun(rejected, { indexNowStatus: 202 });
  await site.deploy(retry);
  assert.deepEqual(submitted(retry), [[replacement]]);
  assert.deepEqual(
    state(retry).indexNow.pending.map(({ url }) => url),
    [retired],
  );
  const unchanged = nextRun(retry);
  await site.deploy(unchanged);
  assert.deepEqual(submitted(unchanged), []);
  assert.ok(unchanged.storage.has('/website-zone/battery/retired/index.html'));

  t.mock.timers.setTime(Date.parse('2026-01-09T00:00:00Z'));
  await writeFile(join(site.dist, '_astro/another.js'), 'another build');
  const cleanup = nextRun(unchanged);
  await site.deploy(cleanup);
  assert.deepEqual(submitted(cleanup), [[retired]]);
  assert.deepEqual(state(cleanup).indexNow.pending, []);
  const deletion = cleanup.calls.findIndex(
    (call) => call.method === 'DELETE' && call.url.endsWith('/retired/index.html'),
  );
  const purge = cleanup.calls.findLastIndex((call) =>
    call.url.startsWith('https://api.bunny.net/'),
  );
  const probe = cleanup.calls.findLastIndex((call) => call.url === retired);
  const post = cleanup.calls.findIndex((call) => call.url === indexNowEndpoint);
  assert.ok(deletion >= 0 && deletion < purge && purge < probe && probe < post);
});

test('only a successful authorized production publication can send IndexNow requests', async (t) => {
  const site = await indexNowSite(t);
  for (const [overrides, args] of [
    [{ WEBSITE_DEPLOY_ENABLED: 'false' }, ['--publish']],
    [{ WEBSITE_DEPLOY_AUTHORIZED: 'false' }, ['--publish']],
    [{ GITHUB_ACTIONS: undefined }, ['--publish']],
    [{}, ['--dry-run']],
  ]) {
    const network = mockNetwork();
    const result = await site.deploy(network, overrides, args);
    assert.ok(network.calls.every((call) => call.url !== indexNowEndpoint));
    assert.ok(network.calls.every((call) => !call.url.includes(testIndexNowKey)));
    assert.ok(!(await readdir(site.dist)).includes(testIndexNowKey + '.txt'));
    if (args.includes('--dry-run') || overrides.WEBSITE_DEPLOY_ENABLED === 'false')
      assert.equal(network.calls.length, 0);
    if (args.includes('--dry-run')) {
      assert.equal(result.indexNow.mode, 'dry-run');
      assert.deepEqual(result.indexNow.planned, [{ kind: 'update', url: site.absolute('/') }]);
    }
  }
  for (const failure of [{ smokeFailure: true }, { stateFailure: true }]) {
    const network = mockNetwork(failure);
    await assert.rejects(site.deploy(network));
    assert.ok(network.calls.every((call) => call.url !== indexNowEndpoint));
  }
  const previewOrigin = 'https://preview.example/';
  const preview = await indexNowSite(t, previewOrigin);
  const network = mockNetwork({ websiteUrl: previewOrigin });
  assert.equal((await preview.deploy(network)).status, 'published');
  assert.ok(
    network.calls.every(
      (call) => call.url !== indexNowEndpoint && !call.url.includes(testIndexNowKey),
    ),
  );
});

test('deployment-only keys rotate through verified uploads without leaking into artifacts or errors', async (t) => {
  const site = await indexNowSite(t);
  const rotatedKey = 'synthetic-test-key-2';
  const keys = [testIndexNowKey, rotatedKey];
  const logs = [];
  for (const method of ['log', 'warn', 'error'])
    t.mock.method(console, method, (...args) => logs.push(args.join(' ')));
  async function assertKeyFreeOutputs() {
    const entries = await readdir(site.artifacts, { recursive: true, withFileTypes: true });
    const outputs = [logs.join('\n'), await readFile(site.summaryPath, 'utf8')];
    for (const entry of entries) {
      outputs.push(entry.name);
      if (entry.isFile()) outputs.push(await readFile(join(entry.parentPath, entry.name), 'utf8'));
    }
    for (const key of keys) {
      assert.ok(outputs.every((output) => !output.includes(key)));
      assert.ok(!(await readdir(site.dist)).includes(key + '.txt'));
    }
    validateManifest(
      JSON.parse(await readFile(join(site.artifacts, 'deployment-manifest.json'), 'utf8')),
    );
  }
  const statePath = new URL(stateUrl(config)).pathname;
  const home = site.absolute('/');
  const first = mockNetwork();
  await site.deploy(first);
  assert.equal(
    first.storage.get('/website-zone/battery/' + testIndexNowKey + '.txt').toString(),
    testIndexNowKey,
  );
  await assertKeyFreeOutputs();

  await site.page('/', 'Edited while no key was configured');
  const missing = mockNetwork({ initialStorage: first.storage });
  const skipped = await site.deploy(missing, { INDEXNOW_KEY: undefined });
  assert.equal(skipped.status, 'published');
  assert.match(skipped.indexNow.warnings.join(' '), /INDEXNOW_KEY/);
  assert.ok(
    missing.calls.every(
      (call) => call.url !== indexNowEndpoint && !call.url.includes(testIndexNowKey),
    ),
  );
  assert.deepEqual(decodeState(missing.storage.get(statePath), config).indexNow.pending, [
    { url: home, kind: 'update' },
  ]);

  const failed = mockNetwork({
    initialStorage: missing.storage,
    failPut: (url) => url.pathname.endsWith('/' + rotatedKey + '.txt'),
  });
  await assert.rejects(
    site.deploy(failed, { INDEXNOW_KEY: rotatedKey }),
    (error) => error instanceof Error && keys.every((key) => !error.stack.includes(key)),
  );
  assert.ok(
    failed.calls.some(
      (call) => call.method === 'PUT' && call.url.endsWith('/' + rotatedKey + '.txt'),
    ),
  );
  assert.ok(failed.calls.every((call) => call.url !== indexNowEndpoint));
  assert.deepEqual(failed.storage.get(statePath), missing.storage.get(statePath));
  await assertKeyFreeOutputs();

  const retry = mockNetwork({ initialStorage: failed.storage });
  await site.deploy(retry, { INDEXNOW_KEY: rotatedKey });
  const filename = rotatedKey + '.txt';
  const remotePath = '/website-zone/battery/' + filename;
  const body = JSON.parse(retry.calls.find((call) => call.url === indexNowEndpoint).body);
  assert.deepEqual(body, {
    key: rotatedKey,
    keyLocation: site.absolute('/' + filename),
    host: new URL(home).host,
    urlList: [home],
  });
  assert.equal(retry.storage.get(remotePath).toString(), rotatedKey);
  const privateState = decodeState(retry.storage.get(statePath), config);
  assert.equal(
    privateState.files.find((file) => file.path === filename).sha256,
    sha256(rotatedKey),
  );
  assert.deepEqual(privateState.indexNow.pending, []);
  await assertKeyFreeOutputs();
});

test('unpersisted IndexNow acknowledgements survive a skipped run and can be retried', async (t) => {
  const site = await indexNowSite(t);
  const statePath = new URL(stateUrl(config)).pathname;
  let stateWrites = 0;
  const failedAck = mockNetwork({
    failPut: (url) => url.pathname === statePath && ++stateWrites === 2,
  });
  const first = await site.deploy(failedAck);
  const home = site.absolute('/');
  assert.equal(first.status, 'published');
  assert.deepEqual(first.indexNow.acceptedUrls, [home]);
  assert.equal(first.indexNow.pending, 1);
  assert.match(first.indexNow.warnings.join(' '), /acknowledgement/);
  const persisted = decodeState(failedAck.storage.get(statePath), config);
  assert.deepEqual(persisted.indexNow.pending, [{ kind: 'update', url: home }]);

  const skipped = mockNetwork({ initialStorage: failedAck.storage });
  await site.deploy(skipped, { WEBSITE_DEPLOY_AUTHORIZED: 'false' });
  assert.ok(skipped.calls.every((call) => call.url !== indexNowEndpoint));
  assert.deepEqual(
    decodeState(skipped.storage.get(statePath), config).indexNow,
    persisted.indexNow,
  );
  const retry = mockNetwork({ initialStorage: skipped.storage });
  const next = await site.deploy(retry);
  assert.deepEqual(next.indexNow.acceptedUrls, [home]);
  assert.deepEqual(decodeState(retry.storage.get(statePath), config).indexNow.pending, []);
});

test('an unavailable historical page initializes a reported baseline and later edits still notify', async (t) => {
  const site = await indexNowSite(t);
  const legacy = mockNetwork();
  await site.deploy(legacy, { WEBSITE_DEPLOY_AUTHORIZED: 'false' });
  legacy.storage.set('/website-zone/battery/index.html', Buffer.from('interrupted upload'));
  await site.page('/', 'New home page');
  const rollout = mockNetwork({ initialStorage: legacy.storage });
  const initial = await site.deploy(rollout);
  assert.equal(initial.status, 'published');
  assert.ok(rollout.calls.every((call) => call.url !== indexNowEndpoint));
  assert.match(initial.indexNow.baseline, /initialized a baseline/i);
  assert.match(initial.indexNow.warnings.join(' '), /checksum mismatch/i);
  await site.page('/', 'Revised home page');
  const next = mockNetwork({ initialStorage: rollout.storage });
  assert.deepEqual((await site.deploy(next)).indexNow.acceptedUrls, [site.absolute('/')]);
});

test('an unchanged deployment honors the IndexNow Retry-After deadline from an earlier run', async (t) => {
  const start = Date.parse('2026-01-01T00:00:00Z');
  t.mock.timers.enable({ apis: ['Date'], now: start });
  const site = await indexNowSite(t);
  const limited = mockNetwork({ indexNowStatus: 429, indexNowHeaders: { 'Retry-After': '120' } });
  const result = await site.deploy(limited);
  assert.equal(result.status, 'published');
  assert.equal(limited.calls.filter((call) => call.url === indexNowEndpoint).length, 1);
  const deadline = new Date(start + 120_000).toISOString();
  const statePath = new URL(stateUrl(config)).pathname;
  assert.equal(
    decodeState(limited.storage.get(statePath), config).indexNow.retryAfterUntil,
    deadline,
  );

  t.mock.timers.setTime(start + 60_000);
  const early = mockNetwork({ initialStorage: limited.storage });
  assert.equal((await site.deploy(early)).indexNow.pending, 1);
  assert.ok(early.calls.every((call) => call.url !== indexNowEndpoint));
  assert.equal(
    decodeState(early.storage.get(statePath), config).indexNow.retryAfterUntil,
    deadline,
  );
  t.mock.timers.setTime(start + 121_000);
  const retry = mockNetwork({ initialStorage: early.storage });
  assert.deepEqual((await site.deploy(retry)).indexNow.acceptedUrls, [site.absolute('/')]);
  assert.deepEqual(decodeState(retry.storage.get(statePath), config).indexNow.pending, []);
});

function manualIndexNow(root, http, overrides = {}) {
  return runPublish({
    root,
    args: ['--indexnow-only'],
    env: {
      ...env,
      GITHUB_ACTIONS: 'true',
      GITHUB_EVENT_NAME: 'workflow_dispatch',
      WEBSITE_DEPLOY_AUTHORIZED: 'true',
      INDEXNOW_KEY: testIndexNowKey,
      ...overrides,
    },
    http,
  });
}

test('manual IndexNow sends the published inventory, retaining failed deliveries and deferred removals', async (t) => {
  const site = await indexNowSite(t);
  await site.page('/z-last/', 'Another page');
  await site.page('/retired/', 'Previous page');
  await site.sitemap('/z-last/', '/', '/retired/');
  const initial = mockNetwork();
  await site.deploy(initial);
  await rm(join(site.dist, 'retired'), { recursive: true });
  await site.sitemap('/z-last/', '/');
  const published = mockNetwork({ initialStorage: initial.storage });
  await site.deploy(published);
  const urls = [site.absolute('/'), site.absolute('/z-last/')];
  const removal = { url: site.absolute('/retired/'), kind: 'removal' };
  const statePath = new URL(stateUrl(config)).pathname;
  const readState = (network) => decodeState(network.storage.get(statePath), config);
  const { indexNow, ...publication } = readState(published);
  assert.deepEqual(indexNow.pending, [removal]);
  const root = await mkdtemp(join(tmpdir(), 'manual-indexnow-'));
  t.after(() => rm(root, { recursive: true, force: true }));

  const alteredSite = mockNetwork({ initialStorage: published.storage });
  alteredSite.storage.set('/website-zone/battery/z-last/index.html', Buffer.from('partial upload'));
  await assert.rejects(manualIndexNow(root, alteredSite), /checksum/i);
  assert.ok(
    alteredSite.calls.every((call) => call.method === 'GET' && call.url !== indexNowEndpoint),
  );

  const rejected = mockNetwork({ initialStorage: published.storage, indexNowStatus: 422 });
  await assert.rejects(manualIndexNow(root, rejected), /IndexNow|422/i);
  const postIndex = rejected.calls.findIndex((call) => call.url === indexNowEndpoint);
  assert.ok(postIndex >= 0);
  const queueWrite = rejected.calls
    .slice(0, postIndex)
    .find((call) => call.url === stateUrl(config) && call.method === 'PUT');
  assert.ok(queueWrite, 'Queue updates must be durable before submission');
  const queued = decodeState(queueWrite.body, config).indexNow.pending;
  assert.deepEqual(
    queued
      .filter(({ kind }) => kind === 'update')
      .map(({ url }) => url)
      .sort(),
    urls,
  );
  assert.deepEqual(
    queued.filter(({ kind }) => kind === 'removal'),
    [removal],
  );
  assert.deepEqual(readState(rejected).indexNow.pending, queued);
  assert.deepEqual(JSON.parse(rejected.calls[postIndex].body).urlList, urls);

  const accepted = mockNetwork({ initialStorage: rejected.storage, indexNowStatus: 202 });
  await manualIndexNow(root, accepted);
  assert.deepEqual(
    accepted.calls
      .filter((call) => call.url === indexNowEndpoint)
      .map((call) => JSON.parse(call.body).urlList),
    [urls],
  );
  for (const network of [rejected, accepted]) {
    assert.ok(network.calls.every((call) => !call.url.startsWith('https://api.bunny.net/')));
    assert.ok(
      network.calls.every(
        (call) =>
          !['DELETE', 'PUT'].includes(call.method) ||
          (call.method === 'PUT' && call.url === stateUrl(config)),
      ),
    );
    const { indexNow: bookkeeping, ...after } = readState(network);
    assert.deepEqual(after, publication);
    assert.deepEqual(bookkeeping.pages, indexNow.pages);
  }
  assert.deepEqual(readState(accepted).indexNow.pending, [removal]);
  const next = mockNetwork({ initialStorage: accepted.storage });
  await site.deploy(next);
  assert.ok(next.calls.every((call) => call.url !== indexNowEndpoint));
});

test('manual IndexNow requires an authorized dispatch and trusted successful publication', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'manual-indexnow-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  for (const rejectedContext of [
    { GITHUB_EVENT_NAME: 'push' },
    { WEBSITE_DEPLOY_AUTHORIZED: 'false' },
    { GITHUB_ACTIONS: undefined },
    { WEBSITE_DEPLOY_ENABLED: 'false' },
    { WEBSITE_URL: 'https://preview.example/' },
  ]) {
    const network = mockNetwork();
    await assert.rejects(manualIndexNow(root, network, rejectedContext));
    assert.equal(network.calls.length, 0);
  }
  const empty = mockNetwork();
  await assert.rejects(manualIndexNow(root, empty), /state|publication|deploy/i);
  assert.ok(empty.calls.every((call) => call.method === 'GET' && call.url !== indexNowEndpoint));
});

test('configuration is disabled by default and rejects unsafe destinations or ambiguous credentials', async () => {
  assert.equal(configFromEnv({}).enabled, false);
  assert.equal(config.prefix, 'battery');
  assert.equal(
    storageUrl(config, '_astro/app.hash.js'),
    'https://storage.bunnycdn.com/website-zone/battery/_astro/app.hash.js',
  );
  for (const endpoint of [
    'http://storage.bunnycdn.com/',
    'https://evil.example/',
    'https://storage.bunnycdn.com/a/',
    'https://key@storage.bunnycdn.com/',
  ])
    assert.throws(() =>
      configFromEnv({ ...env, BUNNY_STORAGE_ENDPOINT: endpoint }, { publishing: true }),
    );
  for (const path of [
    '../x',
    'a/../x',
    'a/%2e%2e/x',
    'a\\x',
    'a//x',
    '/index.html',
    'node_modules/x.js',
    '.env',
  ])
    assert.throws(() => safePath(path));
  assert.throws(
    () =>
      configFromEnv({ ...env, BUNNY_STORAGE_ACCESS_KEY: env.BUNNY_API_KEY }, { publishing: true }),
    /distinct/,
  );
  assert.throws(
    () => configFromEnv({ ...env, BUNNY_API_KEY: '' }, { publishing: true }),
    /BUNNY_API_KEY/,
  );
  assert.equal((await publishSite({ config: configFromEnv({}) })).status, 'disabled');
  assert.deepEqual(
    configFromEnv(
      { WEBSITE_DEPLOY_ENABLED: 'false', BUNNY_STORAGE_ENDPOINT: 'not configured yet' },
      { publishing: true },
    ),
    { enabled: false },
  );
});

test('publication requires separate state signing, a bounded website prefix and explicit initial bootstrap', () => {
  for (const changes of [
    { WEBSITE_DEPLOY_STATE_KEY: '' },
    { WEBSITE_DEPLOY_STATE_KEY: 'weak' },
    { WEBSITE_DEPLOY_STATE_KEY: env.BUNNY_STORAGE_ACCESS_KEY },
    { BUNNY_STORAGE_ACCESS_KEY: env.WEBSITE_DEPLOY_STATE_KEY },
    { BUNNY_STORAGE_PREFIX: '' },
    { BUNNY_STORAGE_PREFIX: 'website-deploy-state' },
    { WEBSITE_DEPLOY_BOOTSTRAP: 'yes' },
  ])
    assert.throws(() => configFromEnv({ ...env, ...changes }, { publishing: true }));
  assert.equal(
    configFromEnv({ ...env, WEBSITE_DEPLOY_BOOTSTRAP: undefined }, { publishing: true }).bootstrap,
    false,
  );
});

test('dist scanning excludes secrets, source directories, and symlinks', async (t) => {
  const f = await fixture(t);
  await writeFile(join(f.dist, 'leak.txt'), env.BUNNY_API_KEY);
  await assert.rejects(scanDist(f.dist, [env.BUNNY_API_KEY]), /credential/);
  await rm(join(f.dist, 'leak.txt'));
  await symlink(join(f.root, 'outside.txt'), join(f.dist, 'escape.txt'));
  await assert.rejects(scanDist(f.dist), /Symlinks/);
  await rm(join(f.dist, 'escape.txt'));
  await mkdir(join(f.dist, 'src'));
  await writeFile(join(f.dist, 'src/private.js'), 'private');
  await assert.rejects(scanDist(f.dist), /forbidden/);
  await rm(join(f.dist, 'src'), { recursive: true });
  await writeFile(join(f.dist, '.env'), 'secret');
  await assert.rejects(scanDist(f.dist), /Unsafe|forbidden/);
});

test('manifest planning orders assets before entry points and protects two builds plus seven days', async (t) => {
  const { current, previous } = await history(t);
  const plan = planDeployment(current, { previous, now: Date.parse('2026-01-17T00:00:00Z') });
  assert.equal(plan.uploads[0].phase, 0);
  assert.equal(plan.uploads.at(-1).phase, 2);
  assert.deepEqual(plan.cleanup, ['_astro/app.old123.js']);
  assert.deepEqual(planDeployment(current).cleanup, []);
  assert.deepEqual(
    planDeployment(current, { previous, now: Date.parse('2026-01-03T00:00:00Z') }).cleanup,
    [],
  );
  const tampered = structuredClone(current);
  tampered.files[0].path = '../unrelated';
  assert.throws(() => validateManifest(tampered));
  assert.throws(
    () => planDeployment(current, { previous: { ...previous, publishedAt: null } }),
    /successful/,
  );
  const obsoleteActive = structuredClone(current);
  obsoleteActive.owned.find((entry) => entry.path === 'index.html').obsoleteSince =
    '2026-01-01T00:00:00Z';
  assert.throws(() => validateManifest(obsoleteActive), /Active files cannot be obsolete/);
});

test('HTTP retries are bounded, honor Retry-After, and never follow redirects', async () => {
  const delays = [];
  let count = 0;
  const response = await request(
    'https://storage.bunnycdn.com/zone/file.txt',
    {},
    {
      fetchFn: async (_url, options) => {
        assert.equal(options.redirect, 'error');
        return ++count < 3
          ? new Response(null, { status: 429, headers: { 'retry-after': '2' } })
          : new Response(null, { status: 201 });
      },
      delay: async (ms) => delays.push(ms),
    },
  );
  assert.equal(response.status, 201);
  assert.equal(count, 3);
  assert.deepEqual(delays, [2000, 2000]);
  count = 0;
  assert.equal(
    (
      await request(
        'https://storage.bunnycdn.com/',
        {},
        {
          fetchFn: async () => {
            count++;
            return new Response(null, { status: 401 });
          },
        },
      )
    ).status,
    401,
  );
  assert.equal(count, 1);
  count = 0;
  await assert.rejects(
    request(
      'https://storage.bunnycdn.com/',
      {},
      {
        fetchFn: async () => {
          count++;
          throw new Error('offline');
        },
        delay: async () => {},
      },
    ),
    /bounded retries/,
  );
  assert.equal(count, 4);
});

test('publication verifies checksums, asset ordering, separate credentials and fresh source before HTML', async (t) => {
  const f = await fixture(t);
  const manifest = await createManifest(f.dist, provenance);
  const network = mockNetwork();
  let policies = 0;
  const result = await publishSite({
    dist: f.dist,
    manifest,
    config,
    http: network,
    validatePolicy: async () => {
      policies++;
    },
  });
  assert.equal(policies, 1);
  assert.equal(result.status, 'published');
  const puts = network.calls.filter(
    (call) => call.method === 'PUT' && !call.url.includes('/website-deploy-state/'),
  );
  assert.ok(puts[0].url.includes('/_astro/'));
  assert.ok(puts.at(-1).url.endsWith('.html'));
  assert.ok(network.calls.filter((call) => call.url.endsWith('/git/ref/heads/main')).length >= 2);
  const purge = network.calls.find((call) => call.url.includes('api.bunny.net'));
  assert.equal(purge.headers.AccessKey, env.BUNNY_API_KEY);
  assert.equal(network.calls.filter((call) => call.method === 'DELETE').length, 0);
  assert.deepEqual(result.manifest.destination, destination(config));
});

test('stale branch, release or asset metadata, policy failure and altered output prevent uploads', async (t) => {
  const f = await fixture(t);
  const manifest = await createManifest(f.dist, provenance);
  const dirtyManifest = await createManifest(f.dist, { ...provenance, websiteDirty: true });
  const dirtyNetwork = mockNetwork();
  await assert.rejects(
    publishSite({
      dist: f.dist,
      manifest: dirtyManifest,
      config,
      http: dirtyNetwork,
      validatePolicy: async () => {},
    }),
    /clean website checkout/,
  );
  assert.equal(dirtyNetwork.calls.length, 0);
  for (const options of [
    { head: () => 'd'.repeat(40) },
    { releaseId: 43 },
    { assetId: 99 },
    { assetUpdatedAt: '2026-01-02T00:00:00Z' },
    { assetDigest: 'sha256:' + 'e'.repeat(64) },
  ]) {
    const network = mockNetwork(options);
    await assert.rejects(
      publishSite({
        dist: f.dist,
        manifest,
        config,
        http: network,
        validatePolicy: async () => {},
      }),
      /Stale/,
    );
    assert.equal(network.calls.filter((call) => call.method === 'PUT').length, 0);
  }
  const signatureManifest = await createManifest(f.dist, {
    ...provenance,
    releaseAssets: {
      ...provenance.releaseAssets,
      signature: {
        id: 4,
        name: 'battery-monitor-2.0.0.apk.asc',
        size: 100,
        updatedAt: '2026-01-01T00:00:00.000Z',
        url: provenance.releaseAssets.apk.url + '.asc',
        digest: null,
      },
    },
  });
  const missingSignature = mockNetwork();
  await assert.rejects(
    publishSite({
      dist: f.dist,
      manifest: signatureManifest,
      config,
      http: missingSignature,
      validatePolicy: async () => {},
    }),
    /Stale/,
  );
  assert.equal(missingSignature.calls.filter((call) => call.method === 'PUT').length, 0);
  const network = mockNetwork();
  await assert.rejects(
    publishSite({
      dist: f.dist,
      manifest,
      config,
      http: network,
      validatePolicy: async () => {
        throw new Error('policy incomplete');
      },
    }),
    /policy incomplete/,
  );
  assert.equal(network.calls.length, 0);
  await writeFile(join(f.dist, 'index.html'), 'changed');
  await assert.rejects(
    publishSite({ dist: f.dist, manifest, config, http: network, validatePolicy: async () => {} }),
    /no longer matches/,
  );
  assert.equal(network.calls.length, 0);
});

test('failed uploads, failed checksum and source changes during asset or metadata upload never publish HTML or purge', async (t) => {
  const f = await fixture(t);
  const manifest = await createManifest(f.dist, provenance);
  for (const options of [
    { failPut: true },
    { badChecksum: true },
    { head: (count) => (count === 1 ? WEBSITE : 'd'.repeat(40)) },
    { head: (count) => (count <= 2 ? WEBSITE : 'd'.repeat(40)) },
    { assetId: (count) => (count <= 2 ? 3 : 99) },
  ]) {
    const network = mockNetwork(options);
    await assert.rejects(
      publishSite({
        dist: f.dist,
        manifest,
        config,
        http: network,
        validatePolicy: async () => {},
      }),
    );
    assert.equal(
      network.calls.filter((call) => call.method === 'PUT' && call.url.endsWith('.html')).length,
      0,
    );
    assert.equal(network.calls.filter((call) => call.url.includes('api.bunny.net')).length, 0);
  }
});

test('cleanup only deletes checksum-verified old owned files and rejects another destination', async (t) => {
  const { dist, current, previous } = await history(t);
  const oldPath = '/website-zone/battery/_astro/app.old123.js';
  const unrelated = '/website-zone/battery/unrelated.txt';
  const network = mockNetwork({
    initialStorage: new Map([
      [oldPath, Buffer.from('export const ready = true;')],
      [unrelated, Buffer.from('unrelated owner data')],
      [new URL(stateUrl(config)).pathname, encodeState(previous, config)],
    ]),
  });
  const result = await publishSite({
    dist,
    manifest: current,
    config,
    http: network,
    validatePolicy: async () => {},
  });
  assert.equal(result.deleted, 1);
  assert.ok(network.storage.has(unrelated));
  assert.deepEqual(
    network.calls
      .filter((call) => call.method === 'DELETE')
      .map((call) => new URL(call.url).pathname),
    [oldPath],
  );
  const modified = mockNetwork({
    initialStorage: new Map([
      [oldPath, Buffer.from('manually replaced content')],
      [new URL(stateUrl(config)).pathname, encodeState(previous, config)],
    ]),
  });
  await assert.rejects(
    publishSite({
      dist,
      manifest: current,
      config,
      http: modified,
      validatePolicy: async () => {},
    }),
    /ownership could not be verified/,
  );
  assert.equal(modified.calls.filter((call) => call.method === 'DELETE').length, 0);
  const other = { ...previous, destination: { ...destination(config), prefix: 'another-site' } };
  await assert.rejects(
    publishSite({
      dist,
      manifest: current,
      config,
      http: mockNetwork({
        initialStorage: new Map([
          [
            new URL(stateUrl(config)).pathname,
            encodeState(other, { ...config, prefix: 'another-site' }),
          ],
        ]),
      }),
      validatePolicy: async () => {},
    }),
    /different deployment destination/,
  );
});

test('real publisher retains long-lived active assets for seven days after successful removal', async (t) => {
  const f = await fixture(t, 'app.old123.js');
  await mkdir(join(f.dist, 'retired'));
  await writeFile(join(f.dist, 'retired/index.html'), '<h1>Old route</h1>');
  await mkdir(join(f.root, '.artifacts'));
  const unrelated = '/website-zone/unrelated.txt';
  let storage = new Map([[unrelated, Buffer.from('not owned by the website')]]);
  t.mock.timers.enable({ apis: ['Date'], now: Date.parse('2026-01-01T00:00:00Z') });
  async function deploy() {
    // Each workflow starts with a fresh local manifest and gets history only from Bunny Storage.
    const manifest = await createManifest(f.dist, provenance);
    await writeFile(join(f.root, '.artifacts/deployment-manifest.json'), JSON.stringify(manifest));
    const network = mockNetwork({ initialStorage: storage });
    const result = await runPublish({
      root: f.root,
      args: ['--publish'],
      env,
      http: network,
      validatePolicy: async () => {},
    });
    storage = new Map(network.storage);
    const persisted = decodeState(storage.get(new URL(stateUrl(config)).pathname), config);
    assert.deepEqual(persisted, result.manifest);
    assert.equal(network.calls.at(-1).url, stateUrl(config));
    return { result, network };
  }
  const first = await deploy();
  assert.equal(first.result.deleted, 0);
  assert.ok(first.result.manifest.owned.every((file) => file.obsoleteSince === null));
  t.mock.timers.setTime(Date.parse('2026-01-31T12:00:00Z'));
  assert.deepEqual(
    planDeployment(first.result.manifest, { previous: first.result.manifest }).cleanup,
    [],
  );
  await rm(join(f.dist, 'retired'), { recursive: true });
  await rm(join(f.dist, '_astro/app.old123.js'));
  await writeFile(join(f.dist, '_astro/app.second456.js'), 'second');
  const second = await deploy();
  assert.equal(second.result.manifest.recentBuilds[1].buildId, first.result.manifest.buildId);
  assert.ok(second.result.manifest.owned.some((file) => file.path === 'retired/index.html'));
  assert.ok(storage.has('/website-zone/battery/retired/index.html'));
  assert.ok(storage.has('/website-zone/battery/_astro/app.old123.js'));
  assert.equal(
    second.result.manifest.owned.find((file) => file.path === '_astro/app.old123.js').obsoleteSince,
    '2026-01-31T12:00:00.000Z',
  );
  await rm(join(f.dist, '_astro/app.second456.js'));
  await writeFile(join(f.dist, '_astro/app.third789.js'), 'third');
  t.mock.timers.setTime(Date.parse('2026-01-31T12:05:00Z'));
  const third = await deploy();
  assert.equal(third.result.deleted, 0);
  assert.ok(storage.has('/website-zone/battery/retired/index.html'));
  assert.ok(storage.has('/website-zone/battery/_astro/app.old123.js'));
  assert.equal(
    third.result.manifest.owned.find((file) => file.path === '_astro/app.old123.js').obsoleteSince,
    second.result.manifest.owned.find((file) => file.path === '_astro/app.old123.js').obsoleteSince,
  );
  await rm(join(f.dist, '_astro/app.third789.js'));
  await writeFile(join(f.dist, '_astro/app.fourth012.js'), 'fourth');
  t.mock.timers.setTime(Date.parse('2026-02-08T12:00:00Z'));
  const fourth = await deploy();
  assert.equal(fourth.result.deleted, 3);
  assert.ok(!storage.has('/website-zone/battery/retired/index.html'));
  assert.ok(!storage.has('/website-zone/battery/_astro/app.old123.js'));
  assert.ok(storage.has('/website-zone/battery/_astro/app.third789.js'));
  assert.ok(storage.has(unrelated));
  assert.ok(
    fourth.network.calls
      .filter((call) => call.method === 'DELETE')
      .every((call) => new URL(call.url).pathname.startsWith('/website-zone/battery/')),
  );
  assert.equal(fourth.network.calls.filter((call) => call.method === 'POST').length, 2);
});

test('successful reintroduction clears obsolescence and a failed removal cannot start its next window', async (t) => {
  const f = await fixture(t, 'app.old123.js');
  await mkdir(join(f.root, '.artifacts'));
  let storage = new Map();
  const statePath = new URL(stateUrl(config)).pathname;
  const oldPath = '/website-zone/battery/_astro/app.old123.js';
  t.mock.timers.enable({ apis: ['Date'], now: Date.parse('2026-01-01T00:00:00Z') });
  async function deploy(options = {}) {
    const manifest = await createManifest(f.dist, provenance);
    await writeFile(join(f.root, '.artifacts/deployment-manifest.json'), JSON.stringify(manifest));
    const network = mockNetwork({ initialStorage: storage, ...options });
    try {
      return await runPublish({
        root: f.root,
        args: ['--publish'],
        env,
        http: network,
        validatePolicy: async () => {},
      });
    } finally {
      storage = new Map(network.storage);
    }
  }
  await deploy();
  await rm(join(f.dist, '_astro/app.old123.js'));
  await writeFile(join(f.dist, '_astro/app.second456.js'), 'second');
  t.mock.timers.setTime(Date.parse('2026-01-31T12:00:00Z'));
  let result = await deploy();
  assert.equal(
    result.manifest.owned.find((file) => file.path === '_astro/app.old123.js').obsoleteSince,
    '2026-01-31T12:00:00.000Z',
  );
  await rm(join(f.dist, '_astro/app.second456.js'));
  await writeFile(join(f.dist, '_astro/app.old123.js'), 'export const ready = true;');
  t.mock.timers.setTime(Date.parse('2026-02-01T12:00:00Z'));
  result = await deploy();
  assert.equal(
    result.manifest.owned.find((file) => file.path === '_astro/app.old123.js').obsoleteSince,
    null,
  );
  const activeState = storage.get(statePath);
  await rm(join(f.dist, '_astro/app.old123.js'));
  await writeFile(join(f.dist, '_astro/app.third789.js'), 'third');
  t.mock.timers.setTime(Date.parse('2026-02-02T12:00:00Z'));
  await assert.rejects(deploy({ smokeFailure: true }));
  assert.deepEqual(storage.get(statePath), activeState);
  assert.equal(
    decodeState(activeState, config).owned.find((file) => file.path === '_astro/app.old123.js')
      .obsoleteSince,
    null,
  );
  t.mock.timers.setTime(Date.parse('2026-02-03T12:00:00Z'));
  result = await deploy();
  assert.equal(
    result.manifest.owned.find((file) => file.path === '_astro/app.old123.js').obsoleteSince,
    '2026-02-03T12:00:00.000Z',
  );
  await writeFile(join(f.dist, 'index.html'), '<h1>Website after another update</h1>');
  t.mock.timers.setTime(Date.parse('2026-02-09T12:00:00Z'));
  result = await deploy();
  assert.ok(storage.has(oldPath));
  assert.equal(
    result.manifest.owned.find((file) => file.path === '_astro/app.old123.js').obsoleteSince,
    '2026-02-03T12:00:00.000Z',
  );
  await writeFile(join(f.dist, 'index.html'), '<h1>Website after retention expiry</h1>');
  t.mock.timers.setTime(Date.parse('2026-02-10T12:00:01Z'));
  result = await deploy();
  assert.ok(!storage.has(oldPath));
  assert.ok(result.manifest.owned.every((file) => file.path !== '_astro/app.old123.js'));
});

test('legacy successful manifests migrate conservatively at the next successful publication', async (t) => {
  const f = await fixture(t, 'app.old123.js');
  await mkdir(join(f.root, '.artifacts'));
  const initial = await createManifest(f.dist, provenance, { now: '2026-01-01T00:00:00Z' });
  const orphan = { path: '_astro/legacy.orphan123.js', sha256: sha256('legacy orphan') };
  const legacy = {
    ...initial,
    schemaVersion: 1,
    publishedAt: '2026-01-01T00:00:00Z',
    destination: destination(config),
    owned: [...initial.owned, orphan].map(({ path, sha256 }) => ({
      path,
      sha256,
      lastReferencedAt: '2000-01-01T00:00:00Z',
    })),
  };
  const statePath = new URL(stateUrl(config)).pathname;
  const orphanPath = '/website-zone/battery/' + orphan.path;
  const oldPath = '/website-zone/battery/_astro/app.old123.js';
  let storage = new Map([
    [statePath, encodeState(legacy, config)],
    [orphanPath, Buffer.from('legacy orphan')],
    [oldPath, Buffer.from('export const ready = true;')],
  ]);
  t.mock.timers.enable({ apis: ['Date'], now: Date.parse('2026-01-31T12:00:00Z') });
  async function deploy() {
    const manifest = await createManifest(f.dist, provenance);
    await writeFile(join(f.root, '.artifacts/deployment-manifest.json'), JSON.stringify(manifest));
    const network = mockNetwork({ initialStorage: storage });
    const result = await runPublish({
      root: f.root,
      args: ['--publish'],
      env,
      http: network,
      validatePolicy: async () => {},
    });
    storage = new Map(network.storage);
    return result;
  }
  await rm(join(f.dist, '_astro/app.old123.js'));
  await writeFile(join(f.dist, '_astro/app.second456.js'), 'second');
  let result = await deploy();
  assert.equal(result.manifest.schemaVersion, 2);
  assert.equal(result.deleted, 0);
  assert.equal(
    result.manifest.owned.find((file) => file.path === orphan.path).obsoleteSince,
    '2026-01-31T12:00:00.000Z',
  );
  assert.ok(storage.has(orphanPath));
  await writeFile(join(f.dist, 'index.html'), '<h1>Rapid update after migration</h1>');
  t.mock.timers.setTime(Date.parse('2026-01-31T12:05:00Z'));
  result = await deploy();
  assert.equal(result.deleted, 0);
  assert.ok(storage.has(orphanPath));
  assert.ok(storage.has(oldPath));
  await writeFile(join(f.dist, 'index.html'), '<h1>After conservative retention expiry</h1>');
  t.mock.timers.setTime(Date.parse('2026-02-08T12:00:00Z'));
  await deploy();
  assert.ok(!storage.has(orphanPath));
  assert.ok(!storage.has(oldPath));
});

test('missing, tampered or mismatched durable state cannot reset history or authorize cleanup', async (t) => {
  const f = await fixture(t);
  const manifest = await createManifest(f.dist, provenance);
  const prior = {
    ...manifest,
    publishedAt: new Date().toISOString(),
    destination: destination(config),
  };
  const statePath = new URL(stateUrl(config)).pathname;
  const signed = encodeState(prior, config);
  const altered = JSON.parse(signed);
  altered.ciphertext =
    (altered.ciphertext.startsWith('00') ? '01' : '00') + altered.ciphertext.slice(2);
  const otherConfig = { ...config, prefix: 'other-site' };
  const cases = [
    { storage: new Map(), config: { ...config, bootstrap: false }, error: /state is missing/ },
    {
      storage: new Map([['/website-zone/battery/unrelated.txt', Buffer.from('existing')]]),
      config,
      error: /empty website destination/,
    },
    { storage: new Map([[statePath, Buffer.from('{')]]), config, error: /corrupt/ },
    {
      storage: new Map([[statePath, Buffer.from(JSON.stringify(altered))]]),
      config,
      error: /integrity/,
    },
    {
      storage: new Map([
        [statePath, encodeState({ ...prior, destination: destination(otherConfig) }, otherConfig)],
      ]),
      config,
      error: /different deployment destination/,
    },
  ];
  for (const entry of cases) {
    const network = mockNetwork({ initialStorage: entry.storage });
    await assert.rejects(
      publishSite({
        dist: f.dist,
        manifest,
        config: entry.config,
        http: network,
        validatePolicy: async () => {},
      }),
      entry.error,
    );
    assert.equal(network.calls.filter((call) => ['PUT', 'DELETE'].includes(call.method)).length, 0);
    assert.deepEqual(network.storage, entry.storage);
  }
  assert.throws(() => encodeState({ ...prior, secret: config.stateKey }, config), /credential/);
  assert.throws(() => safePath('website-deploy-state/forged.json'), /forbidden/);
});

test('failed publication checks and state writes preserve the last successful manifest', async (t) => {
  const f = await fixture(t);
  const firstManifest = await createManifest(f.dist, provenance);
  const baseline = mockNetwork();
  await publishSite({
    dist: f.dist,
    manifest: firstManifest,
    config,
    http: baseline,
    validatePolicy: async () => {},
  });
  const statePath = new URL(stateUrl(config)).pathname;
  const lastSuccessful = baseline.storage.get(statePath);
  await writeFile(join(f.dist, 'index.html'), '<h1>Changed website</h1>');
  const nextManifest = await createManifest(f.dist, provenance);
  for (const failure of [
    { failPut: true },
    { smokeFailure: true },
    { purgeFailure: true },
    { stateFailure: true },
  ]) {
    const network = mockNetwork({ ...failure, initialStorage: baseline.storage });
    await assert.rejects(
      publishSite({
        dist: f.dist,
        manifest: nextManifest,
        config: { ...config, bootstrap: false },
        http: network,
        validatePolicy: async () => {},
      }),
    );
    assert.deepEqual(network.storage.get(statePath), lastSuccessful);
    assert.equal(network.calls.filter((call) => call.method === 'DELETE').length, 0);
  }
  await assert.rejects(
    runPublish({ root: f.root, args: ['--publish', '--previous-manifest=untrusted.json'], env }),
    /dry-run planning/,
  );
});

test('state changes during publication stop a stale deployment before HTML or cleanup', async (t) => {
  const f = await fixture(t);
  const manifest = await createManifest(f.dist, provenance);
  const first = mockNetwork();
  const prior = await publishSite({
    dist: f.dist,
    manifest,
    config,
    http: first,
    validatePolicy: async () => {},
  });
  const statePath = new URL(stateUrl(config)).pathname;
  const network = mockNetwork({ initialStorage: first.storage });
  const concurrent = encodeState(
    { ...prior.manifest, publishedAt: new Date(Date.now() + 1).toISOString() },
    config,
  );
  let stateReads = 0;
  const fetchFn = async (input, options) => {
    if (String(input) === stateUrl(config) && ++stateReads === 2)
      network.storage.set(statePath, concurrent);
    return network.fetchFn(input, options);
  };
  await assert.rejects(
    publishSite({
      dist: f.dist,
      manifest,
      config,
      http: { ...network, fetchFn },
      validatePolicy: async () => {},
    }),
    /state changed/,
  );
  assert.equal(
    network.calls.filter(
      (call) =>
        call.method === 'DELETE' ||
        (call.method === 'PUT' && (call.url.endsWith('.html') || call.url === stateUrl(config))),
    ).length,
    0,
  );
  assert.deepEqual(network.storage.get(statePath), concurrent);
});

test('fresh guard resolves an annotated stable tag despite newer prereleases', async () => {
  const network = mockNetwork();
  assert.deepEqual(await assertCurrent(provenance, config, network), {
    branch: 'main',
    releaseId: 42,
  });
  assert.ok(network.calls.some((call) => call.url.includes('/git/tags/')));
});

test('initial publication resumes authenticated pending files after ordinary failures', async (t) => {
  for (const stage of [
    'pending-only',
    'partial asset',
    'partial HTML',
    'checksum',
    'purge',
    'smoke',
    'successful state write',
  ]) {
    await t.test(stage, async (t) => {
      const f = await fixture(t);
      await mkdir(join(f.root, '.artifacts'));
      const manifest = await createManifest(f.dist, provenance);
      await writeFile(
        join(f.root, '.artifacts/deployment-manifest.json'),
        JSON.stringify(manifest),
      );
      let sitePuts = 0;
      const failed = mockNetwork({
        failPut: (url) => {
          if (!url.pathname.startsWith('/website-zone/battery/')) return false;
          sitePuts++;
          return (
            (stage === 'pending-only' && sitePuts === 1) ||
            (stage === 'partial asset' && sitePuts === 2) ||
            (stage === 'partial HTML' && url.pathname.endsWith('/download/index.html'))
          );
        },
        purgeFailure: stage === 'purge',
        smokeFailure: stage === 'smoke',
        stateFailure: stage === 'successful state write',
      });
      let checksumFailed = false;
      const fetchFn = async (input, options = {}) => {
        const response = await failed.fetchFn(input, options);
        if (
          stage === 'checksum' &&
          !checksumFailed &&
          (options.method || 'GET') === 'GET' &&
          String(input).endsWith('/_astro/app.first123.js') &&
          response.ok
        ) {
          checksumFailed = true;
          return new Response('bad verification response');
        }
        return response;
      };
      await assert.rejects(
        runPublish({
          root: f.root,
          args: ['--publish'],
          env,
          http: { ...failed, fetchFn },
          validatePolicy: async () => {},
        }),
      );
      const statePath = new URL(stateUrl(config)).pathname;
      const pendingPath = new URL(stateUrl(config, { pending: true })).pathname;
      assert.equal(failed.storage.has(statePath), false);
      const pending = decodePending(failed.storage.get(pendingPath), config);
      assert.equal(pending.manifest.publishedAt, null);
      assert.equal(pending.manifest.buildId, manifest.buildId);
      const uploaded = [...failed.storage.keys()].filter((path) =>
        path.startsWith('/website-zone/battery/'),
      );
      if (stage === 'pending-only') assert.equal(uploaded.length, 0);
      else assert.ok(uploaded.length > 0);
      if (['purge', 'smoke', 'successful state write'].includes(stage))
        assert.equal(uploaded.length, manifest.files.length);
      const resumed = mockNetwork({ initialStorage: failed.storage });
      const result = await runPublish({
        root: f.root,
        args: ['--publish'],
        env,
        http: resumed,
        validatePolicy: async () => {},
      });
      assert.equal(result.status, 'published');
      assert.equal(result.deleted, 0);
      assert.equal(decodeState(resumed.storage.get(statePath), config).buildId, manifest.buildId);
      assert.equal(resumed.calls.filter((call) => call.method === 'DELETE').length, 0);
      const pendingPut = resumed.calls.findIndex(
        (call) => call.method === 'PUT' && call.url === stateUrl(config, { pending: true }),
      );
      const filePut = resumed.calls.findIndex(
        (call) =>
          call.method === 'PUT' && new URL(call.url).pathname.startsWith('/website-zone/battery/'),
      );
      assert.ok(pendingPut >= 0 && pendingPut < filePut);
    });
  }
});

test('initial recovery accepts a fresh website or release only after verifying all earlier objects', async (t) => {
  for (const change of ['website', 'release']) {
    await t.test(change, async (t) => {
      const f = await fixture(t, 'app.old123.js');
      await mkdir(join(f.root, '.artifacts'));
      const artifact = join(f.root, '.artifacts/deployment-manifest.json');
      const firstManifest = await createManifest(f.dist, provenance);
      await writeFile(artifact, JSON.stringify(firstManifest));
      let puts = 0;
      const first = mockNetwork({
        failPut: (url) => url.pathname.startsWith('/website-zone/battery/') && ++puts === 3,
      });
      await assert.rejects(
        runPublish({
          root: f.root,
          args: ['--publish'],
          env,
          http: first,
          validatePolicy: async () => {},
        }),
      );
      const next = structuredClone(provenance);
      if (change === 'website') next.websiteCommit = 'd'.repeat(40);
      else {
        next.appCommit = 'e'.repeat(40);
        next.releaseId = 43;
        next.tag = 'v2.1.0';
        next.version = '2.1.0';
        next.publishedAt = '2026-02-01T00:00:00Z';
        next.releaseAssets.apk = {
          ...next.releaseAssets.apk,
          id: 4,
          name: 'battery-monitor-2.1.0.apk',
          url: 'https://github.com/tswistak/Battery-Monitor/releases/download/v2.1.0/battery-monitor-2.1.0.apk',
        };
        await writeFile(join(f.dist, 'download/index.html'), '<h1>Get Battery Monitor 2.1.0</h1>');
      }
      await rm(join(f.dist, '_astro/app.old123.js'));
      await writeFile(join(f.dist, '_astro/app.new456.js'), 'new asset');
      await writeFile(join(f.dist, 'index.html'), '<h1>Updated Battery Monitor</h1>');
      const manifest = await createManifest(f.dist, next);
      await writeFile(artifact, JSON.stringify(manifest));
      const resumed = mockNetwork({ initialStorage: first.storage, currentProvenance: next });
      const result = await runPublish({
        root: f.root,
        args: ['--publish'],
        env,
        http: resumed,
        validatePolicy: async () => {},
      });
      assert.deepEqual(result.manifest.provenance, next);
      const old = result.manifest.owned.find((file) => file.path === '_astro/app.old123.js');
      assert.equal(old.obsoleteSince, result.manifest.publishedAt);
      assert.ok(resumed.storage.has('/website-zone/battery/_astro/app.old123.js'));
      assert.equal(result.manifest.recentBuilds.length, 1);
      assert.equal(result.deleted, 0);
    });
  }
});

test('pending recovery rejects unknown objects, conflicting bytes and untrusted state before mutation', async (t) => {
  const f = await fixture(t);
  await mkdir(join(f.root, '.artifacts'));
  const manifest = await createManifest(f.dist, provenance);
  await writeFile(join(f.root, '.artifacts/deployment-manifest.json'), JSON.stringify(manifest));
  let puts = 0;
  const first = mockNetwork({
    failPut: (url) => url.pathname.startsWith('/website-zone/battery/') && ++puts === 2,
  });
  await assert.rejects(
    runPublish({
      root: f.root,
      args: ['--publish'],
      env,
      http: first,
      validatePolicy: async () => {},
    }),
  );
  const pendingPath = new URL(stateUrl(config, { pending: true })).pathname;
  const valid = decodePending(first.storage.get(pendingPath), config);
  const otherConfig = { ...config, prefix: 'another-site' };
  const tampered = JSON.parse(first.storage.get(pendingPath));
  tampered.tag = '0'.repeat(32);
  const cases = [
    new Map([...first.storage, ['/website-zone/battery/unrelated.txt', Buffer.from('unrelated')]]),
    new Map([
      ...first.storage,
      ['/website-zone/battery/_astro/app.first123.js', Buffer.from('conflicting bytes')],
    ]),
    new Map([...first.storage, [pendingPath, Buffer.from(JSON.stringify(tampered))]]),
    new Map([
      ...first.storage,
      [
        pendingPath,
        encodePending({ ...valid, destination: destination(otherConfig) }, otherConfig),
      ],
    ]),
  ];
  for (const storage of cases) {
    const failed = mockNetwork({ initialStorage: storage });
    await assert.rejects(
      runPublish({
        root: f.root,
        args: ['--publish'],
        env,
        http: failed,
        validatePolicy: async () => {},
      }),
    );
    assert.equal(failed.calls.filter((call) => ['PUT', 'DELETE'].includes(call.method)).length, 0);
    assert.deepEqual(failed.storage, storage);
  }
  const emptyDirectory = mockNetwork();
  const fetchFn = (input, options) =>
    String(input).endsWith('/website-zone/battery/')
      ? json([{ ObjectName: 'unknown-directory', IsDirectory: true, Length: 0 }])
      : emptyDirectory.fetchFn(input, options);
  await assert.rejects(
    runPublish({
      root: f.root,
      args: ['--publish'],
      env,
      http: { ...emptyDirectory, fetchFn },
      validatePolicy: async () => {},
    }),
    /empty website destination/,
  );
  assert.equal(emptyDirectory.calls.filter((call) => call.method === 'PUT').length, 0);
});

test('new state is encrypted and typed, legacy signed state migrates, and successful state retires pending authority', async (t) => {
  const f = await fixture(t);
  await mkdir(join(f.root, '.artifacts'));
  const manifest = await createManifest(f.dist, provenance);
  await writeFile(join(f.root, '.artifacts/deployment-manifest.json'), JSON.stringify(manifest));
  const first = mockNetwork();
  const result = await runPublish({
    root: f.root,
    args: ['--publish'],
    env,
    http: first,
    validatePolicy: async () => {},
  });
  const statePath = new URL(stateUrl(config)).pathname;
  const encrypted = first.storage.get(statePath);
  const text = encrypted.toString('utf8');
  for (const value of [
    'manifest',
    config.zone,
    config.websiteUrl,
    provenance.websiteCommit,
    ...result.manifest.files.map((file) => file.path),
  ])
    assert.equal(text.includes(value), false);
  assert.throws(() => decodePending(encrypted, config), /Invalid signed/);
  assert.throws(
    () =>
      decodeState(first.storage.get(new URL(stateUrl(config, { pending: true })).pathname), config),
    /Invalid signed/,
  );
  const legacyManifest = {
    ...result.manifest,
    schemaVersion: 1,
    owned: result.manifest.owned.map((entry) => ({
      path: entry.path,
      sha256: entry.sha256,
      lastReferencedAt: result.manifest.publishedAt,
    })),
  };
  const legacy = Buffer.from(
    JSON.stringify({
      schemaVersion: 1,
      owner: legacyManifest.owner,
      manifest: legacyManifest,
      signature: createHmac('sha256', config.stateKey)
        .update(JSON.stringify(legacyManifest))
        .digest('hex'),
    }),
  );
  assert.deepEqual(decodeState(legacy, config), legacyManifest);
  first.storage.set(statePath, legacy);
  first.storage.set(
    new URL(stateUrl(config, { pending: true })).pathname,
    Buffer.from('retired corrupt pending data'),
  );
  const migrated = mockNetwork({ initialStorage: first.storage });
  const completed = await runPublish({
    root: f.root,
    args: ['--publish'],
    env: { ...env, WEBSITE_DEPLOY_BOOTSTRAP: 'false' },
    http: migrated,
    validatePolicy: async () => {},
  });
  assert.equal(completed.manifest.schemaVersion, 2);
  assert.equal(JSON.parse(migrated.storage.get(statePath)).schemaVersion, 2);
  assert.equal(
    migrated.calls.some((call) => call.url === stateUrl(config, { pending: true })),
    false,
  );
  const publicDestination = mockNetwork({ publicState: true });
  await assert.rejects(
    runPublish({
      root: f.root,
      args: ['--publish'],
      env,
      http: publicDestination,
      validatePolicy: async () => {},
    }),
    /publicly accessible/,
  );
  assert.equal(
    [...publicDestination.storage.keys()].some((path) => path.startsWith('/website-zone/battery/')),
    false,
  );
  const collisionEnv = { ...env, BUNNY_STORAGE_ZONE: 'website-deploy-state' };
  const collisionConfig = configFromEnv(collisionEnv, { publishing: true });
  const collision = mockNetwork();
  let probes = 0;
  const fetchFn = (input, options = {}) => {
    const url = new URL(input);
    if (url.origin === new URL(env.WEBSITE_URL).origin) {
      probes++;
      assert.equal(
        url.pathname,
        `/website-deploy-state/${sha256(JSON.stringify(destination(collisionConfig)))}.pending.json`,
      );
      assert.equal(options.headers?.AccessKey, undefined);
      return new Response(null, { status: 200 });
    }
    return collision.fetchFn(input, options);
  };
  await assert.rejects(
    runPublish({
      root: f.root,
      args: ['--publish'],
      env: collisionEnv,
      http: { ...collision, fetchFn },
      validatePolicy: async () => {},
    }),
    /publicly accessible/,
  );
  assert.equal(probes, 1);
  assert.equal(
    [...collision.storage.keys()].some((path) => path.startsWith('/website-deploy-state/battery/')),
    false,
  );
});

test('smoke checks enforce stable version, required assets, headers and actual 404', async (t) => {
  const f = await fixture(t);
  const manifest = await createManifest(f.dist, provenance);
  const bytes = await readFile(join(f.dist, '_astro', f.asset));
  const fetchFn = async (input) => {
    const path = new URL(input).pathname;
    if (path.includes('__battery-monitor-smoke')) return new Response('missing', { status: 404 });
    if (path === '/robots.txt')
      return new Response('Sitemap: https://batterymonitor.swistak.codes/sitemap.xml');
    if (path === '/sitemap.xml')
      return new Response(
        '<urlset><url><loc>https://batterymonitor.swistak.codes/</loc></url></urlset>',
      );
    if (path.startsWith('/_astro/'))
      return new Response(bytes, {
        headers: { 'cache-control': 'public,max-age=31536000,immutable' },
      });
    return new Response('<h1>Battery Monitor 2.0.0</h1>', {
      headers: {
        'content-type': 'text/html',
        'referrer-policy': 'no-referrer',
        'x-content-type-options': 'nosniff',
        'content-security-policy': "default-src 'self'; object-src 'none'; base-uri 'self'",
        'cache-control': 'public,max-age=300',
      },
    });
  };
  const result = await smokeSite({ url: env.WEBSITE_URL, manifest, http: { fetchFn } });
  assert.equal(result.productionHeadersChecked, true);
  assert.ok(result.checked.includes('actual 404'));
  const alternateOrigin = 'https://preview.example';
  await smokeSite({
    url: alternateOrigin + '/',
    manifest,
    http: {
      fetchFn: async (input, options) => {
        const path = new URL(input).pathname;
        if (path === '/robots.txt') return new Response(`Sitemap: ${alternateOrigin}/sitemap.xml`);
        if (path === '/sitemap.xml')
          return new Response(`<urlset><url><loc>${alternateOrigin}/</loc></url></urlset>`);
        return fetchFn(input, options);
      },
    },
  });
  await assert.rejects(
    smokeSite({
      url: env.WEBSITE_URL,
      manifest,
      http: {
        fetchFn: async (url, options) =>
          new URL(url).pathname.includes('__battery-monitor-smoke')
            ? new Response('<h1>SPA fallback</h1>')
            : fetchFn(url, options),
      },
    }),
    /expected actual 404/,
  );
  await assert.rejects(
    smokeSite({
      url: env.WEBSITE_URL,
      manifest,
      http: {
        fetchFn: async (url) =>
          new URL(url).pathname === '/'
            ? new Response('<h1>Page</h1>', { headers: { 'content-type': 'text/html' } })
            : fetchFn(url),
      },
    }),
    /headers/,
  );
});
