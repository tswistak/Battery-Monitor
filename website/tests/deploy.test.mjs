import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  assertCurrent,
  configFromEnv,
  createManifest,
  destination,
  planDeployment,
  publishSite,
  request,
  safePath,
  scanDist,
  sha256,
  storageUrl,
  validateManifest,
} from '../scripts/deploy/core.mjs';
import { smokeSite } from '../scripts/deploy/smoke.mjs';

const WEBSITE = 'a'.repeat(40);
const APP = 'b'.repeat(40);
const TAG = 'c'.repeat(40);
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
  head = () => WEBSITE,
  releaseId = 42,
  failPut = false,
  badChecksum = false,
  assetId = 3,
  assetUpdatedAt = provenance.releaseAssets.apk.updatedAt,
  assetDigest = null,
  initialStorage = new Map(),
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
            tag_name: 'v2.0.0',
            published_at: provenance.publishedAt,
            html_url: 'https://github.com/tswistak/Battery-Monitor/releases/tag/v2.0.0',
          },
        ]);
      if (url.pathname.includes('/git/ref/tags/'))
        return json({ object: { type: 'tag', sha: TAG } });
      if (url.pathname.includes('/git/tags/'))
        return json({ object: { type: 'commit', sha: APP } });
      if (url.pathname.endsWith('/assets'))
        return json([
          {
            id: typeof assetId === 'function' ? assetId(++assetReads) : assetId,
            name: 'battery-monitor-2.0.0.apk',
            state: 'uploaded',
            size: 500,
            updated_at: assetUpdatedAt,
            digest: assetDigest,
            browser_download_url:
              'https://github.com/tswistak/Battery-Monitor/releases/download/v2.0.0/battery-monitor-2.0.0.apk',
          },
        ]);
      throw new Error('Unexpected GitHub request');
    }
    if (url.hostname === 'api.bunny.net') return new Response(null, { status: 204 });
    if (url.hostname.endsWith('storage.bunnycdn.com')) {
      if (method === 'PUT') {
        if (failPut) return new Response(null, { status: 400 });
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
  previous = { ...previous, publishedAt: '2026-01-01T00:00:01Z', destination: destination(config) };
  await rm(join(f.dist, '_astro/app.old123.js'));
  await writeFile(join(f.dist, '_astro/app.previous456.js'), 'previous');
  let second = await createManifest(f.dist, provenance, { previous, now: '2026-01-09T00:00:00Z' });
  second = { ...second, publishedAt: '2026-01-09T00:00:01Z', destination: destination(config) };
  await rm(join(f.dist, '_astro/app.previous456.js'));
  await writeFile(join(f.dist, '_astro/app.current789.js'), 'current');
  const current = await createManifest(f.dist, provenance, {
    previous: second,
    now: '2026-01-17T00:00:00Z',
  });
  return { ...f, previous: second, current };
}

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
  const puts = network.calls.filter((call) => call.method === 'PUT');
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
    ]),
  });
  const result = await publishSite({
    dist,
    manifest: current,
    previous,
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
    initialStorage: new Map([[oldPath, Buffer.from('manually replaced content')]]),
  });
  await assert.rejects(
    publishSite({
      dist,
      manifest: current,
      previous,
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
      previous: other,
      config,
      http: mockNetwork(),
      validatePolicy: async () => {},
    }),
    /different deployment destination/,
  );
});

test('fresh guard resolves an annotated stable tag despite newer prereleases', async () => {
  const network = mockNetwork();
  assert.deepEqual(await assertCurrent(provenance, config, network), {
    branch: 'main',
    releaseId: 42,
  });
  assert.ok(network.calls.some((call) => call.url.includes('/git/tags/')));
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
