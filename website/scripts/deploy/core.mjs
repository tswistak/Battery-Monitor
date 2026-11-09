import { createHash } from 'node:crypto';
import { lstat, readdir, readFile, realpath } from 'node:fs/promises';
import { extname, relative, resolve, sep } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import {
  API_URL,
  REPOSITORY,
  request as githubRequest,
  resolveLatestRelease,
  listReleaseAssets,
  selectReleaseAssets,
} from '../content/github.mjs';

export const OWNER = 'battery-monitor-website';
export const RETAIN_MS = 7 * 24 * 60 * 60 * 1000;
const REGIONS = new Set([
  'storage.bunnycdn.com',
  ...['uk', 'ny', 'la', 'sg', 'se', 'br', 'jh', 'syd'].map(
    (region) => `${region}.storage.bunnycdn.com`,
  ),
]);
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.xml': 'application/xml; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.avif': 'image/avif',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.webm': 'video/webm',
  '.mp4': 'video/mp4',
  '.webmanifest': 'application/manifest+json',
};
const FORBIDDEN =
  /^(?:node_modules|src|scripts|tests|captures|\.git|\.github|\.generated|\.artifacts|\.env(?:\..*)?|package(?:-lock)?\.json|tsconfig(?:\..*)?\.json|astro\.config\..*)$/i;
const HASH = /^[a-f0-9]{64}$/;
const COMMIT = /^[a-f0-9]{40}$/;
export const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

export function safePath(value, { prefix = false } = {}) {
  // eslint-disable-next-line no-control-regex -- Control characters are invalid in publication paths.
  if (typeof value !== 'string' || /[\\%?#\x00-\x20\x7f]/.test(value))
    throw new Error('Unsafe deployment path');
  const path = prefix ? value.replace(/^\/+|\/+$/g, '') : value;
  if (prefix && path === '') return '';
  if (!path || path.startsWith('/') || path.endsWith('/'))
    throw new Error('Deployment paths must identify a relative file');
  if (
    path
      .split('/')
      .some((part) => !/^[A-Za-z0-9_][A-Za-z0-9._-]*$/.test(part) || FORBIDDEN.test(part))
  )
    throw new Error('Unsafe or forbidden deployment path');
  return path;
}

export function publicUrl(value = 'https://batterymonitor.swistak.codes/') {
  const url = new URL(value);
  if (
    url.protocol !== 'https:' ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname !== '/'
  )
    throw new Error('WEBSITE_URL must be an HTTPS origin with a trailing slash');
  return url.href;
}

export function configFromEnv(env = process.env, { publishing = false } = {}) {
  if (env.WEBSITE_DEPLOY_ENABLED && !['true', 'false'].includes(env.WEBSITE_DEPLOY_ENABLED))
    throw new Error('WEBSITE_DEPLOY_ENABLED must be true or false');
  if (publishing && env.WEBSITE_DEPLOY_ENABLED !== 'true') return { enabled: false };
  const config = {
    enabled: env.WEBSITE_DEPLOY_ENABLED === 'true',
    websiteUrl: publicUrl(env.WEBSITE_URL),
    prefix: safePath(env.BUNNY_STORAGE_PREFIX || '', { prefix: true }),
    repository: env.GITHUB_REPOSITORY || 'tswistak/Battery-Monitor',
    branch: env.WEBSITE_PRODUCTION_BRANCH || null,
    endpoint: null,
    zone: env.BUNNY_STORAGE_ZONE || null,
    pullZoneId: env.BUNNY_PULL_ZONE_ID || null,
    storageKey: env.BUNNY_STORAGE_ACCESS_KEY || null,
    apiKey: env.BUNNY_API_KEY || null,
    githubToken: env.GITHUB_TOKEN || null,
  };
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(config.repository))
    throw new Error('Invalid GitHub repository');
  if (config.branch && (!/^[A-Za-z0-9_./-]+$/.test(config.branch) || config.branch.includes('..')))
    throw new Error('Invalid production branch');
  if (env.BUNNY_STORAGE_ENDPOINT) {
    const url = new URL(env.BUNNY_STORAGE_ENDPOINT);
    if (
      url.protocol !== 'https:' ||
      !REGIONS.has(url.hostname) ||
      url.port ||
      url.username ||
      url.password ||
      url.search ||
      url.hash ||
      url.pathname !== '/'
    )
      throw new Error('BUNNY_STORAGE_ENDPOINT must be a documented HTTPS regional Storage origin');
    config.endpoint = url.origin;
  }
  if (config.zone && !/^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$/.test(config.zone))
    throw new Error('Invalid BUNNY_STORAGE_ZONE');
  if (config.pullZoneId && !/^[1-9][0-9]*$/.test(config.pullZoneId))
    throw new Error('BUNNY_PULL_ZONE_ID must be a positive integer');
  if (publishing && config.enabled) {
    for (const [key, value] of [
      ['BUNNY_STORAGE_ENDPOINT', config.endpoint],
      ['BUNNY_STORAGE_ZONE', config.zone],
      ['BUNNY_PULL_ZONE_ID', config.pullZoneId],
      ['BUNNY_STORAGE_ACCESS_KEY', config.storageKey],
      ['BUNNY_API_KEY', config.apiKey],
    ])
      if (!value?.trim()) throw new Error(`Missing ${key}`);
    if (config.storageKey === config.apiKey)
      throw new Error('Storage and CDN API credentials must be distinct');
  }
  return config;
}

export function validateProvenance(provenance, production = false) {
  if (!provenance || typeof provenance !== 'object' || Array.isArray(provenance))
    throw new Error('Missing build provenance');
  if (!provenance.websiteCommit || !provenance.appCommit || !provenance.version)
    throw new Error('Incomplete build provenance');
  if (production && provenance.websiteDirty)
    throw new Error('Publication requires a clean website checkout');
  if (
    production &&
    (provenance.mode !== 'production' ||
      !COMMIT.test(provenance.websiteCommit) ||
      !COMMIT.test(provenance.appCommit) ||
      !provenance.releaseId ||
      !provenance.tag ||
      !provenance.releaseAssets?.apk ||
      !Number.isFinite(Date.parse(provenance.publishedAt)))
  )
    throw new Error('Publication requires validated production release provenance');
  return provenance;
}

function phase(path) {
  const ext = extname(path).toLowerCase();
  return ext === '.html' ? 2 : ['.json', '.xml', '.txt', '.webmanifest'].includes(ext) ? 1 : 0;
}

export async function scanDist(dist, secrets = []) {
  const requested = resolve(dist);
  if ((await lstat(requested)).isSymbolicLink())
    throw new Error('The dist directory must not be a symlink');
  const root = await realpath(requested);
  const files = [];
  async function visit(directory) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const absolute = resolve(directory, entry.name);
      const path = safePath(relative(root, absolute).split(sep).join('/'));
      const stat = await lstat(absolute);
      if (stat.isSymbolicLink()) throw new Error(`Symlinks cannot be published: ${path}`);
      if (stat.isDirectory()) {
        await visit(absolute);
        continue;
      }
      if (!stat.isFile() || (await realpath(absolute)) !== absolute)
        throw new Error(`Non-regular file cannot be published: ${path}`);
      const mime = MIME[extname(path).toLowerCase()];
      if (!mime) throw new Error(`Unsupported public file type: ${path}`);
      const bytes = await readFile(absolute);
      if (
        secrets
          .filter((value) => typeof value === 'string' && value.length > 0)
          .some((secret) => bytes.includes(Buffer.from(secret)))
      )
        throw new Error(`A deployment credential occurs in public output: ${path}`);
      files.push({ path, size: bytes.length, sha256: sha256(bytes), mime, phase: phase(path) });
    }
  }
  await visit(root);
  if (!files.some((file) => file.path === 'index.html'))
    throw new Error('dist/index.html is required');
  return files.sort((a, b) => a.phase - b.phase || a.path.localeCompare(b.path));
}

export function validateManifest(manifest, { successful = false } = {}) {
  if (
    manifest?.schemaVersion !== 1 ||
    manifest.owner !== OWNER ||
    !HASH.test(manifest.buildId || '') ||
    !Number.isFinite(Date.parse(manifest.generatedAt))
  )
    throw new Error('Invalid website deployment manifest');
  validateProvenance(manifest.provenance);
  if (successful && !Number.isFinite(Date.parse(manifest.publishedAt)))
    throw new Error('Cleanup requires a previously successful deployment manifest');
  if (
    !Array.isArray(manifest.files) ||
    !manifest.files.length ||
    !Array.isArray(manifest.owned) ||
    !Array.isArray(manifest.recentBuilds)
  )
    throw new Error('Incomplete deployment manifest');
  const paths = new Set();
  for (const file of manifest.files) {
    safePath(file.path);
    if (
      paths.has(file.path) ||
      !HASH.test(file.sha256 || '') ||
      !Number.isSafeInteger(file.size) ||
      file.size < 0 ||
      file.mime !== MIME[extname(file.path).toLowerCase()] ||
      file.phase !== phase(file.path)
    )
      throw new Error('Invalid or duplicate manifest file');
    paths.add(file.path);
  }
  if (
    sha256(JSON.stringify({ provenance: manifest.provenance, files: manifest.files })) !==
      manifest.buildId ||
    manifest.recentBuilds[0]?.buildId !== manifest.buildId ||
    JSON.stringify(manifest.recentBuilds[0]?.paths) !==
      JSON.stringify(manifest.files.map((file) => file.path))
  )
    throw new Error('Manifest identity or current-file history changed');
  const ownedPaths = new Set();
  for (const entry of manifest.owned) {
    safePath(entry.path);
    if (
      ownedPaths.has(entry.path) ||
      !MIME[extname(entry.path).toLowerCase()] ||
      !HASH.test(entry.sha256 || '') ||
      !Number.isFinite(Date.parse(entry.lastReferencedAt))
    )
      throw new Error('Invalid owned-file retention record');
    ownedPaths.add(entry.path);
  }
  for (const build of manifest.recentBuilds) {
    if (!HASH.test(build.buildId || '') || !Array.isArray(build.paths))
      throw new Error('Invalid retained build history');
    build.paths.forEach((path) => safePath(path));
  }
  return manifest;
}

export async function createManifest(
  dist,
  provenance,
  { previous = null, now = new Date().toISOString(), secrets = [] } = {},
) {
  validateProvenance(provenance);
  if (!Number.isFinite(Date.parse(now))) throw new Error('Invalid manifest date');
  if (previous) validateManifest(previous, { successful: true });
  const files = await scanDist(dist, secrets);
  const buildId = sha256(JSON.stringify({ provenance, files }));
  const owned = new Map((previous?.owned || []).map((entry) => [entry.path, { ...entry }]));
  files.forEach((file) =>
    owned.set(file.path, { path: file.path, sha256: file.sha256, lastReferencedAt: now }),
  );
  const recentBuilds = [
    { buildId, paths: files.map((file) => file.path) },
    ...(previous?.recentBuilds || []).filter((build) => build.buildId !== buildId),
  ].slice(0, 2);
  return {
    schemaVersion: 1,
    owner: OWNER,
    buildId,
    generatedAt: now,
    publishedAt: null,
    provenance,
    files,
    owned: [...owned.values()].sort((a, b) => a.path.localeCompare(b.path)),
    recentBuilds,
  };
}

export function planDeployment(manifest, { previous = null, now = Date.now() } = {}) {
  validateManifest(manifest);
  if (previous) validateManifest(previous, { successful: true });
  const protectedPaths = new Set(manifest.recentBuilds.flatMap((build) => build.paths));
  const previouslyOwned = new Map((previous?.owned || []).map((entry) => [entry.path, entry]));
  const cleanup = previous
    ? manifest.owned
        .filter((entry) => {
          const prior = previouslyOwned.get(entry.path);
          return (
            prior &&
            !protectedPaths.has(entry.path) &&
            now - Date.parse(prior.lastReferencedAt) >= RETAIN_MS
          );
        })
        .map((entry) => entry.path)
        .sort()
    : [];
  return {
    uploads: [...manifest.files].sort((a, b) => a.phase - b.phase || a.path.localeCompare(b.path)),
    cleanup,
  };
}

export function storageUrl(config, path) {
  safePath(path);
  if (!config.endpoint || !config.zone) throw new Error('Storage destination is not configured');
  return `${config.endpoint}/${[config.zone, config.prefix, path].filter(Boolean).join('/').split('/').map(encodeURIComponent).join('/')}`;
}

export async function request(
  url,
  options = {},
  {
    fetchFn = fetch,
    delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    attempts = 4,
  } = {},
) {
  if (!Number.isInteger(attempts) || attempts < 1 || attempts > 4)
    throw new Error('Invalid retry bound');
  for (let attempt = 0; attempt < attempts; attempt++) {
    let response;
    try {
      response = await fetchFn(url, {
        ...options,
        redirect: 'error',
        signal: options.signal || AbortSignal.timeout(30_000),
      });
    } catch {
      if (attempt === attempts - 1)
        throw new Error('Deployment request failed after bounded retries');
      await delay(Math.min(5_000, 250 * 2 ** attempt));
      continue;
    }
    if (![408, 429, 500, 502, 503, 504].includes(response.status) || attempt === attempts - 1)
      return response;
    const retry = response.headers.get('retry-after');
    const seconds =
      retry && /^\d+$/.test(retry)
        ? Number(retry) * 1000
        : retry
          ? Date.parse(retry) - Date.now()
          : 0;
    await response.body?.cancel();
    await delay(
      Math.max(
        250,
        Math.min(30_000, Number.isFinite(seconds) && seconds > 0 ? seconds : 250 * 2 ** attempt),
      ),
    );
  }
  throw new Error('Unreachable retry state');
}

export async function assertCurrent(provenance, config, http = {}) {
  validateProvenance(provenance, true);
  if (config.repository !== REPOSITORY)
    throw new Error('Publication is restricted to the configured Battery Monitor repository');
  const options = { fetchImpl: http.fetchFn || fetch, token: config.githubToken };
  const repository = await githubRequest(API_URL, options);
  const branch = config.branch || repository.default_branch;
  if (!branch || !/^[A-Za-z0-9_./-]+$/.test(branch) || branch.includes('..'))
    throw new Error('GitHub did not provide a valid production branch');
  const head = await githubRequest(
    `${API_URL}/git/ref/heads/${branch.split('/').map(encodeURIComponent).join('/')}`,
    options,
  );
  if (head.object?.type !== 'commit' || head.object.sha !== provenance.websiteCommit)
    throw new Error('Stale deployment: production website revision changed');
  const { release, appCommit } = await resolveLatestRelease(options);
  if (
    String(release.id) !== String(provenance.releaseId) ||
    release.tag_name !== provenance.tag ||
    release.published_at !== provenance.publishedAt ||
    appCommit !== provenance.appCommit
  )
    throw new Error('Stale deployment: latest stable application source changed');
  const { releaseAssets } = selectReleaseAssets(
    await listReleaseAssets(release, options),
    release.tag_name,
  );
  if (!isDeepStrictEqual(releaseAssets, provenance.releaseAssets))
    throw new Error('Stale deployment: selected application release assets changed');
  return { branch, releaseId: release.id };
}

export function destination(config) {
  return {
    websiteUrl: config.websiteUrl,
    endpoint: config.endpoint,
    zone: config.zone,
    prefix: config.prefix,
  };
}

export async function publishSite({
  dist,
  manifest,
  previous = null,
  config,
  http = {},
  validatePolicy,
}) {
  if (!config.enabled) return { status: 'disabled', uploads: 0, deleted: 0 };
  validateManifest(manifest);
  validateProvenance(manifest.provenance, true);
  if (typeof validatePolicy !== 'function')
    throw new Error('A production privacy/configuration validator is required');
  await validatePolicy();
  const actual = await scanDist(dist, [config.storageKey, config.apiKey, config.githubToken]);
  if (
    JSON.stringify(actual) !==
    JSON.stringify(
      [...manifest.files].sort((a, b) => a.phase - b.phase || a.path.localeCompare(b.path)),
    )
  )
    throw new Error('dist no longer matches its deployment manifest');
  if (previous && JSON.stringify(previous.destination) !== JSON.stringify(destination(config)))
    throw new Error('Prior manifest belongs to a different deployment destination');
  const plan = planDeployment(manifest, { previous });
  await assertCurrent(manifest.provenance, config, http);
  let phase = 0;
  for (const file of plan.uploads) {
    if (file.phase > phase) {
      await assertCurrent(manifest.provenance, config, http);
      phase = file.phase;
    }
    const bytes = await readFile(resolve(dist, file.path));
    if (sha256(bytes) !== file.sha256) throw new Error('A build file changed during publication');
    const headers = {
      AccessKey: config.storageKey,
      'Content-Type': file.mime,
      Checksum: file.sha256.toUpperCase(),
    };
    const uploaded = await request(
      storageUrl(config, file.path),
      { method: 'PUT', headers, body: bytes },
      http,
    );
    if (uploaded.status !== 201)
      throw new Error(`Storage upload failed (${uploaded.status}) for ${file.path}`);
    const verification = await request(
      storageUrl(config, file.path),
      { headers: { AccessKey: config.storageKey } },
      http,
    );
    if (!verification.ok || sha256(Buffer.from(await verification.arrayBuffer())) !== file.sha256)
      throw new Error(`Storage checksum verification failed for ${file.path}`);
  }
  const purged = await request(
    `https://api.bunny.net/pullzone/${config.pullZoneId}/purgeCache`,
    {
      method: 'POST',
      headers: { AccessKey: config.apiKey, 'Content-Type': 'application/json' },
      body: '{}',
    },
    http,
  );
  if (purged.status !== 204)
    throw new Error(`Site files uploaded, but CDN invalidation failed (${purged.status})`);
  for (const path of plan.cleanup) {
    const owned = previous.owned.find((entry) => entry.path === path);
    const existing = await request(
      storageUrl(config, path),
      { headers: { AccessKey: config.storageKey } },
      http,
    );
    if (existing.status === 404) continue;
    if (!existing.ok || sha256(Buffer.from(await existing.arrayBuffer())) !== owned.sha256)
      throw new Error(
        `Site published, but obsolete-file ownership could not be verified for ${path}; it was retained`,
      );
    const deleted = await request(
      storageUrl(config, path),
      { method: 'DELETE', headers: { AccessKey: config.storageKey } },
      http,
    );
    if (!deleted.ok && deleted.status !== 404)
      throw new Error(
        `Site published, but controlled cleanup failed (${deleted.status}) for ${path}`,
      );
  }
  const deleted = new Set(plan.cleanup);
  return {
    status: 'published',
    uploads: plan.uploads.length,
    deleted: deleted.size,
    manifest: {
      ...manifest,
      publishedAt: new Date().toISOString(),
      destination: destination(config),
      owned: manifest.owned.filter((entry) => !deleted.has(entry.path)),
    },
  };
}
