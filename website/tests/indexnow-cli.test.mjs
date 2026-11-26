import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { createManifest, validateManifest } from '../scripts/deploy/core.mjs';
import { INDEXNOW_ORIGIN } from '../scripts/deploy/indexnow-pages.mjs';
import { artifactManifest, packageSite } from '../scripts/deploy/manifest.mjs';
import { runPublish } from '../scripts/deploy/publish.mjs';

test('offline deployment preview reports pending changes without advancing the supplied baseline', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'battery-indexnow-preview-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const artifacts = join(root, '.artifacts');
  const dist = join(root, 'dist');
  await mkdir(artifacts);
  await mkdir(dist);
  const home = INDEXNOW_ORIGIN + '/';
  const old = home + 'retired/';
  await writeFile(join(dist, 'index.html'), `<head><link rel="canonical" href="${home}"></head>`);
  await writeFile(join(dist, 'sitemap.xml'), `<urlset><url><loc>${home}</loc></url></urlset>`);
  const manifest = await createManifest(dist, {
    mode: 'production',
    websiteCommit: 'abc',
    appCommit: 'def',
    version: '1',
  });
  await writeFile(join(artifacts, 'deployment-manifest.json'), JSON.stringify(manifest));
  const hash = manifest.files.find((file) => file.path === 'index.html').sha256;
  const prior = {
    ...manifest,
    publishedAt: manifest.generatedAt,
    indexNow: {
      pages: { [home]: hash, [old]: hash },
      pending: [{ url: home, kind: 'update' }],
      retryAfterUntil: new Date(Date.now() + 60_000).toISOString(),
    },
  };
  const file = join(root, 'previous.json');
  await writeFile(file, JSON.stringify(prior));
  const fetchFn = t.mock.fn(() => assert.fail('dry-run attempted HTTP'));
  const log = t.mock.method(console, 'log', () => {});
  const summary = join(root, 'summary.md');
  const preview = () =>
    runPublish({
      root,
      args: ['--dry-run', '--previous-manifest', file],
      env: { GITHUB_STEP_SUMMARY: summary },
      http: { fetchFn },
    });
  const { indexNow } = await preview();
  assert.equal(fetchFn.mock.callCount(), 0);
  assert.equal(indexNow.mode, 'dry-run');
  assert.equal(indexNow.deferred, 1);
  assert.equal(indexNow.retryAfterUntil, prior.indexNow.retryAfterUntil);
  assert.deepEqual(indexNow.planned, [
    { url: home, kind: 'update' },
    { url: old, kind: 'removal' },
  ]);
  assert.deepEqual(JSON.parse(log.mock.calls[0].arguments[0]).indexNow, indexNow);
  assert.deepEqual(
    JSON.parse(await readFile(join(artifacts, 'indexnow-report.json'), 'utf8')),
    indexNow,
  );
  assert.match(await readFile(summary, 'utf8'), /2 pending/);
  assert.deepEqual(JSON.parse(await readFile(file, 'utf8')), prior);
  await writeFile(
    join(dist, 'index.html'),
    `<head><meta name="robots" content="noindex"><link rel="canonical" href="${home}"></head>`,
  );
  for (const mode of ['local', 'fixture']) {
    const nonProduction = await createManifest(dist, { ...manifest.provenance, mode });
    await writeFile(join(artifacts, 'deployment-manifest.json'), JSON.stringify(nonProduction));
    const skipped = (await preview()).indexNow;
    assert.equal(skipped.mode, 'dry-run');
    assert.deepEqual(skipped.planned, []);
    assert.ok(skipped.reasons.some((reason) => reason.includes('build:production')));
  }
  assert.equal(fetchFn.mock.callCount(), 0);
});

test('packaging and offline previews omit deployed verification files across key rotations', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'battery-indexnow-artifact-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const dist = join(root, 'dist');
  const artifacts = join(root, '.artifacts');
  const manifestPath = join(artifacts, 'deployment-manifest.json');
  await mkdir(dist);
  await mkdir(join(root, '.generated'));
  const provenance = { mode: 'fixture', websiteCommit: 'abc', appCommit: 'def', version: '1' };
  await writeFile(join(root, '.generated/content.json'), JSON.stringify({ provenance }));
  await writeFile(join(dist, 'index.html'), '<h1>Home</h1>');
  await writeFile(join(dist, 'operator-notes.txt'), 'Ordinary public text.');
  const log = t.mock.method(console, 'log', () => {});
  const keys = ['old-test-key-4812', 'new-test-key-1832', 'history-only-4011'];
  const [oldKey, newKey, historyOnly] = keys;
  const isClean = (value) => keys.every((key) => !JSON.stringify(value).includes(key));
  await writeFile(join(dist, oldKey + '.txt'), oldKey);
  const prior = await createManifest(dist, provenance);
  prior.publishedAt = prior.generatedAt;
  await rm(join(dist, oldKey + '.txt'));
  await writeFile(join(dist, newKey + '.txt'), newKey);
  const operational = await createManifest(dist, provenance, { previous: prior });
  operational.publishedAt = operational.generatedAt;
  operational.indexNow = { pages: {}, pending: [] };
  const historical = operational.recentBuilds[1];
  operational.recentBuilds[1] = {
    ...historical,
    paths: [...historical.paths, historyOnly + '.txt'],
  };

  await assert.rejects(
    packageSite({ root, previous: prior, env: {} }),
    (error) => /verification file.*before packaging/i.test(error.message) && isClean(error.message),
  );
  assert.ok(!(await readdir(root)).includes('.artifacts'));
  await rm(join(dist, newKey + '.txt'));
  const staged = await packageSite({ root, previous: operational, env: {} });
  assert.ok(isClean(staged));
  assert.ok(isClean(await readdir(join(artifacts, 'site'))));
  assert.ok(isClean(await readFile(manifestPath, 'utf8')));
  const successful = artifactManifest(operational);
  validateManifest(successful, { successful: true });
  assert.equal(successful.buildId, staged.buildId);
  assert.equal(successful.publishedAt, operational.publishedAt);
  assert.deepEqual(successful.files, staged.files);
  assert.deepEqual(successful.indexNow, operational.indexNow);
  assert.ok(successful.owned.some((file) => file.path === 'operator-notes.txt'));
  assert.ok(isClean(successful));
  assert.ok(!isClean(operational));

  // The CLI may receive a full protected legacy snapshot as a local dry-run input.
  const previousPath = join(root, 'previous.json');
  await writeFile(previousPath, JSON.stringify(prior));
  await writeFile(manifestPath, JSON.stringify(operational));
  const preview = await runPublish({
    root,
    env: {},
    args: ['--dry-run', '--previous-manifest', previousPath],
  });
  assert.ok(isClean(preview));
  assert.ok(isClean(log.mock.calls.map(({ arguments: args }) => args)));
});
