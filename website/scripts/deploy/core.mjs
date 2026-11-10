import {
  createCipheriv,
  createDecipheriv,
  createHash,
  createHmac,
  hkdfSync,
  randomBytes,
  timingSafeEqual,
} from 'node:crypto';
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
const STATE_DIRECTORY = 'website-deploy-state';
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
  /^(?:node_modules|src|scripts|tests|captures|\.git|\.github|\.generated|\.artifacts|\.env(?:\..*)?|package(?:-lock)?\.json|tsconfig(?:\..*)?\.json|astro\.config\..*|website-deploy-state)$/i;
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
    stateKey: env.WEBSITE_DEPLOY_STATE_KEY || null,
    bootstrap: env.WEBSITE_DEPLOY_BOOTSTRAP === 'true',
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
  if (env.WEBSITE_DEPLOY_BOOTSTRAP && !['true', 'false'].includes(env.WEBSITE_DEPLOY_BOOTSTRAP))
    throw new Error('WEBSITE_DEPLOY_BOOTSTRAP must be true or false');
  if (publishing && config.enabled) {
    for (const [key, value] of [
      ['BUNNY_STORAGE_ENDPOINT', config.endpoint],
      ['BUNNY_STORAGE_ZONE', config.zone],
      ['BUNNY_PULL_ZONE_ID', config.pullZoneId],
      ['BUNNY_STORAGE_ACCESS_KEY', config.storageKey],
      ['BUNNY_API_KEY', config.apiKey],
      ['WEBSITE_DEPLOY_STATE_KEY', config.stateKey],
    ])
      if (!value?.trim()) throw new Error(`Missing ${key}`);
    if (!config.prefix || config.prefix.split('/')[0] === STATE_DIRECTORY)
      throw new Error(
        'Publication requires a nonempty BUNNY_STORAGE_PREFIX outside deployment state',
      );
    if (!/^[a-f0-9]{64}$/.test(config.stateKey))
      throw new Error('WEBSITE_DEPLOY_STATE_KEY must be a separate random 64-character hex secret');
    if ([config.storageKey, config.apiKey, config.githubToken].includes(config.stateKey))
      throw new Error('Deployment state signing and service credentials must be distinct');
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
    ![1, 2].includes(manifest?.schemaVersion) ||
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
      (manifest.schemaVersion === 1
        ? !Number.isFinite(Date.parse(entry.lastReferencedAt))
        : entry.obsoleteSince !== null &&
          (typeof entry.obsoleteSince !== 'string' ||
            !Number.isFinite(Date.parse(entry.obsoleteSince))))
    )
      throw new Error('Invalid owned-file retention record');
    ownedPaths.add(entry.path);
  }
  if (manifest.schemaVersion === 2) {
    for (const file of manifest.files) {
      const owned = manifest.owned.find((entry) => entry.path === file.path);
      if (!owned || owned.sha256 !== file.sha256 || owned.obsoleteSince !== null)
        throw new Error('Active files cannot be obsolete or lack verified ownership');
    }
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
  const owned = new Map(
    (previous?.owned || []).map((entry) => [
      entry.path,
      {
        path: entry.path,
        sha256: entry.sha256,
        // Legacy publication dates cannot establish when a file stopped being active.
        obsoleteSince: previous.schemaVersion === 2 ? entry.obsoleteSince : null,
      },
    ]),
  );
  files.forEach((file) =>
    owned.set(file.path, { path: file.path, sha256: file.sha256, obsoleteSince: null }),
  );
  const recentBuilds = [
    { buildId, paths: files.map((file) => file.path) },
    ...(previous?.recentBuilds || []).filter((build) => build.buildId !== buildId),
  ].slice(0, 2);
  return {
    schemaVersion: 2,
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
            previous.schemaVersion === 2 &&
            prior.obsoleteSince !== null &&
            now - Date.parse(prior.obsoleteSince) >= RETAIN_MS
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

export function publishedOwnership(manifest, publishedAt, deleted = new Set()) {
  const activePaths = new Set(manifest.files.map((file) => file.path));
  return manifest.owned
    .filter((entry) => !deleted.has(entry.path))
    .map((entry) => ({
      path: entry.path,
      sha256: entry.sha256,
      obsoleteSince: activePaths.has(entry.path) ? null : (entry.obsoleteSince ?? publishedAt),
    }));
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
    pullZoneId: config.pullZoneId,
  };
}

export function stateUrl(config, { pending = false } = {}) {
  const name = sha256(JSON.stringify(destination(config))) + (pending ? '.pending.json' : '.json');
  return storageUrl({ ...config, prefix: STATE_DIRECTORY }, name);
}

function stateEncryptionKey(config) {
  return hkdfSync(
    'sha256',
    Buffer.from(config.stateKey, 'hex'),
    '',
    `${OWNER}:state-encryption`,
    32,
  );
}

function encodeRecord(kind, value, config) {
  const payload = JSON.stringify(value);
  if (
    [config.storageKey, config.apiKey, config.githubToken, config.stateKey]
      .filter(Boolean)
      .some((secret) => payload.includes(secret))
  )
    throw new Error('A deployment credential occurs in the deployment manifest');
  const signature = createHmac('sha256', config.stateKey)
    .update(`${kind}:${payload}`)
    .digest('hex');
  const signed = JSON.stringify({ schemaVersion: 2, owner: OWNER, kind, value, signature });
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', stateEncryptionKey(config), iv);
  cipher.setAAD(Buffer.from(OWNER));
  const encrypted = Buffer.concat([cipher.update(signed, 'utf8'), cipher.final()]);
  return Buffer.from(
    JSON.stringify({
      schemaVersion: 2,
      owner: OWNER,
      iv: iv.toString('hex'),
      tag: cipher.getAuthTag().toString('hex'),
      ciphertext: encrypted.toString('hex'),
    }),
  );
}

function decodeRecord(bytes, kind, config) {
  let state;
  try {
    state = JSON.parse(bytes.toString('utf8'));
  } catch {
    throw new Error(
      'Deployment state is corrupt; restore the last trusted state before publishing',
    );
  }
  if (state?.schemaVersion === 2) {
    if (
      state.owner !== OWNER ||
      !/^[a-f0-9]{24}$/.test(state.iv || '') ||
      !/^[a-f0-9]{32}$/.test(state.tag || '') ||
      typeof state.ciphertext !== 'string' ||
      !/^(?:[a-f0-9]{2})+$/.test(state.ciphertext)
    )
      throw new Error('Invalid encrypted deployment state');
    try {
      const decipher = createDecipheriv(
        'aes-256-gcm',
        stateEncryptionKey(config),
        Buffer.from(state.iv, 'hex'),
        { authTagLength: 16 },
      );
      decipher.setAAD(Buffer.from(OWNER));
      decipher.setAuthTag(Buffer.from(state.tag, 'hex'));
      state = JSON.parse(
        Buffer.concat([
          decipher.update(Buffer.from(state.ciphertext, 'hex')),
          decipher.final(),
        ]).toString('utf8'),
      );
    } catch {
      throw new Error('Deployment state integrity check failed');
    }
  }
  const legacy = state?.schemaVersion === 1 && kind === 'manifest';
  const value = legacy ? state.manifest : state?.value;
  if (
    (!legacy && (state?.schemaVersion !== 2 || state.kind !== kind)) ||
    state.owner !== OWNER ||
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    !HASH.test(state.signature || '')
  )
    throw new Error('Invalid signed deployment state');
  const expected = createHmac('sha256', config.stateKey)
    .update(legacy ? JSON.stringify(value) : `${kind}:${JSON.stringify(value)}`)
    .digest();
  if (!timingSafeEqual(Buffer.from(state.signature, 'hex'), expected))
    throw new Error('Deployment state integrity check failed');
  return value;
}

export function encodeState(manifest, config) {
  validateManifest(manifest, { successful: true });
  if (!isDeepStrictEqual(manifest.destination, destination(config)))
    throw new Error('Prior manifest belongs to a different deployment destination');
  return encodeRecord('manifest', manifest, config);
}

export function decodeState(bytes, config) {
  const manifest = decodeRecord(bytes, 'manifest', config);
  validateManifest(manifest, { successful: true });
  if (!isDeepStrictEqual(manifest.destination, destination(config)))
    throw new Error('Prior manifest belongs to a different deployment destination');
  return manifest;
}

function validatePending(pending, config) {
  if (pending?.schemaVersion !== 1 || !isDeepStrictEqual(pending.destination, destination(config)))
    throw new Error('Pending deployment belongs to a different deployment destination');
  validateManifest(pending.manifest);
  validateProvenance(pending.manifest.provenance, true);
  if (
    pending.manifest.publishedAt !== null ||
    !Array.isArray(pending.expected) ||
    !pending.expected.length
  )
    throw new Error('Invalid pending deployment record');
  const identities = new Set();
  for (const file of pending.expected) {
    safePath(file.path);
    const identity = `${file.path}:${file.sha256}`;
    if (
      !MIME[extname(file.path).toLowerCase()] ||
      !HASH.test(file.sha256 || '') ||
      !Number.isSafeInteger(file.size) ||
      file.size < 0 ||
      identities.has(identity)
    )
      throw new Error('Invalid pending deployment file');
    identities.add(identity);
  }
  for (const file of pending.manifest.files)
    if (
      !pending.expected.some(
        (expected) =>
          expected.path === file.path &&
          expected.sha256 === file.sha256 &&
          expected.size === file.size,
      )
    )
      throw new Error('Pending deployment does not authorize its build files');
  return pending;
}

export function encodePending(pending, config) {
  validatePending(pending, config);
  return encodeRecord('pending', pending, config);
}

export function decodePending(bytes, config) {
  return validatePending(decodeRecord(bytes, 'pending', config), config);
}

async function readState(config, http, { pending = false } = {}) {
  const response = await request(
    stateUrl(config, { pending }),
    { headers: { AccessKey: config.storageKey } },
    http,
  );
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`Cannot read deployment state (${response.status})`);
  const bytes = Buffer.from(await response.arrayBuffer());
  return { bytes, manifest: pending ? decodePending(bytes, config) : decodeState(bytes, config) };
}

async function assertUnchangedState(config, state, http, options = {}) {
  const latest = await readState(config, http, options);
  if ((latest && sha256(latest.bytes)) !== (state && sha256(state.bytes)))
    throw new Error(
      'Deployment state changed during publication; refusing stale cleanup or state update',
    );
}

async function assertPrivateState(config, http, options = {}) {
  const remote = new URL(stateUrl(config, options));
  const path = `/${STATE_DIRECTORY}/${remote.pathname.split('/').at(-1)}`;
  const url = new URL(path, config.websiteUrl);
  url.searchParams.set('state-check', randomBytes(12).toString('hex'));
  const response = await request(
    url.href,
    { cache: 'no-store', headers: { 'Cache-Control': 'no-cache' } },
    http,
  );
  await response.body?.cancel();
  if (![403, 404].includes(response.status))
    throw new Error(
      'Deployment state is publicly accessible; restrict the CDN origin to the website prefix or block the website-deploy-state path',
    );
}

async function storeState(config, bytes, http, options = {}) {
  const stored = await request(
    stateUrl(config, options),
    {
      method: 'PUT',
      headers: {
        AccessKey: config.storageKey,
        'Content-Type': 'application/json',
        Checksum: sha256(bytes).toUpperCase(),
      },
      body: bytes,
    },
    http,
  );
  if (stored.status !== 201)
    throw new Error(`Deployment state could not be persisted (${stored.status})`);
  const verified = await readState(config, http, options);
  if (!verified || sha256(verified.bytes) !== sha256(bytes))
    throw new Error('Persisted deployment state could not be verified');
  return verified;
}

async function verifyInitialFiles(config, pending, http) {
  const verified = [];
  async function visit(directory = '') {
    const url = storageUrl(config, `${directory}index.html`).replace(/index\.html$/, '');
    const listing = await request(url, { headers: { AccessKey: config.storageKey } }, http);
    if (listing.status === 404) return;
    if (!listing.ok)
      throw new Error(`Cannot inspect initial deployment destination (${listing.status})`);
    const entries = await listing.json();
    if (!Array.isArray(entries)) throw new Error('Invalid Storage directory listing');
    const names = new Set();
    for (const entry of entries) {
      if (
        !entry ||
        typeof entry.ObjectName !== 'string' ||
        entry.ObjectName.includes('/') ||
        typeof entry.IsDirectory !== 'boolean' ||
        !Number.isSafeInteger(entry.Length) ||
        entry.Length < 0 ||
        names.has(entry.ObjectName)
      )
        throw new Error('Invalid Storage directory entry');
      const path = safePath(directory + entry.ObjectName);
      names.add(entry.ObjectName);
      const expected =
        pending?.expected.filter((file) =>
          entry.IsDirectory ? file.path.startsWith(path + '/') : file.path === path,
        ) || [];
      if (!expected.length)
        throw new Error(
          'Initial deployment requires an empty website destination or authenticated pending ownership; unrelated files were preserved',
        );
      if (entry.IsDirectory) {
        await visit(path + '/');
        continue;
      }
      const existing = await request(
        storageUrl(config, path),
        { headers: { AccessKey: config.storageKey } },
        http,
      );
      if (!existing.ok) throw new Error(`Pending deployment file could not be verified: ${path}`);
      const bytes = Buffer.from(await existing.arrayBuffer());
      const file = expected.find(
        (file) => file.size === bytes.length && file.sha256 === sha256(bytes),
      );
      if (!file)
        throw new Error(
          `Pending deployment ownership could not be verified for ${path}; it was preserved`,
        );
      verified.push(file);
    }
  }
  await visit();
  return verified;
}

async function purgeSite(config, http) {
  const response = await request(
    `https://api.bunny.net/pullzone/${config.pullZoneId}/purgeCache`,
    {
      method: 'POST',
      headers: { AccessKey: config.apiKey, 'Content-Type': 'application/json' },
      body: '{}',
    },
    http,
  );
  if (response.status !== 204)
    throw new Error(`Site files uploaded, but CDN invalidation failed (${response.status})`);
}

export async function publishSite({ dist, manifest, config, http = {}, validatePolicy }) {
  if (!config.enabled) return { status: 'disabled', uploads: 0, deleted: 0 };
  validateManifest(manifest);
  validateProvenance(manifest.provenance, true);
  if (typeof validatePolicy !== 'function')
    throw new Error('A production privacy/configuration validator is required');
  await validatePolicy();
  const secrets = [config.storageKey, config.apiKey, config.githubToken, config.stateKey];
  const actual = await scanDist(dist, secrets);
  if (
    JSON.stringify(actual) !==
    JSON.stringify(
      [...manifest.files].sort((a, b) => a.phase - b.phase || a.path.localeCompare(b.path)),
    )
  )
    throw new Error('dist no longer matches its deployment manifest');
  const state = await readState(config, http);
  const previous = state?.manifest || null;
  let pending = null;
  let recovered = [];
  if (!previous) {
    if (!config.bootstrap)
      throw new Error(
        'Deployment state is missing; use WEBSITE_DEPLOY_BOOTSTRAP=true only for an empty initial website destination or authenticated pending recovery',
      );
    pending = await readState(config, http, { pending: true });
    recovered = await verifyInitialFiles(config, pending?.manifest, http);
  } else await assertPrivateState(config, http);
  // Regenerate at the real publish entry point: local artifacts cannot authorize remote deletion.
  manifest = await createManifest(dist, manifest.provenance, {
    previous,
    secrets,
  });
  for (const file of recovered)
    if (!manifest.owned.some((entry) => entry.path === file.path))
      manifest.owned.push({ path: file.path, sha256: file.sha256, obsoleteSince: null });
  manifest.owned.sort((a, b) => a.path.localeCompare(b.path));
  const plan = planDeployment(manifest, { previous });
  await assertCurrent(manifest.provenance, config, http);
  if (!previous) {
    const expected = new Map(
      [...recovered, ...manifest.files].map((file) => [
        `${file.path}:${file.sha256}`,
        { path: file.path, size: file.size, sha256: file.sha256 },
      ]),
    );
    const record = {
      schemaVersion: 1,
      destination: destination(config),
      manifest,
      expected: [...expected.values()],
    };
    await assertUnchangedState(config, state, http);
    await assertUnchangedState(config, pending, http, { pending: true });
    pending = await storeState(config, encodePending(record, config), http, { pending: true });
    await assertPrivateState(config, http, { pending: true });
  }
  let phase = 0;
  for (const file of plan.uploads) {
    if (file.phase > phase) {
      await assertCurrent(manifest.provenance, config, http);
      if (file.phase === 2) {
        await assertUnchangedState(config, state, http);
        if (pending) await assertUnchangedState(config, pending, http, { pending: true });
      }
      phase = file.phase;
    }
    const bytes = await readFile(resolve(dist, file.path));
    if (sha256(bytes) !== file.sha256) throw new Error('A build file changed during publication');
    if (pending) {
      const existing = await request(
        storageUrl(config, file.path),
        { headers: { AccessKey: config.storageKey } },
        http,
      );
      if (existing.status !== 404) {
        if (!existing.ok) throw new Error(`Cannot verify pending deployment target: ${file.path}`);
        const actual = Buffer.from(await existing.arrayBuffer());
        if (
          !pending.manifest.expected.some(
            (expected) =>
              expected.path === file.path &&
              expected.size === actual.length &&
              expected.sha256 === sha256(actual),
          )
        )
          throw new Error(
            `Pending deployment ownership could not be verified for ${file.path}; it was preserved`,
          );
      }
    }
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
  await purgeSite(config, http);
  const { smokeSite } = await import('./smoke.mjs');
  await smokeSite({ url: config.websiteUrl, manifest, http });
  await assertCurrent(manifest.provenance, config, http);
  await assertUnchangedState(config, state, http);
  if (pending) {
    await assertUnchangedState(config, pending, http, { pending: true });
    await verifyInitialFiles(config, pending.manifest, http);
  }
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
  if (plan.cleanup.length) await purgeSite(config, http);
  const deleted = new Set(plan.cleanup);
  const publishedAt = new Date().toISOString();
  const successful = {
    ...manifest,
    publishedAt,
    destination: destination(config),
    owned: publishedOwnership(manifest, publishedAt, deleted),
  };
  const bytes = encodeState(successful, config);
  await assertUnchangedState(config, state, http);
  await storeState(config, bytes, http);
  // A verified successful state retires bootstrap authority; a leftover pending record is ignored.
  return {
    status: 'published',
    uploads: plan.uploads.length,
    deleted: deleted.size,
    manifest: successful,
  };
}
