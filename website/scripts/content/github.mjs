export const REPOSITORY = 'tswistak/Battery-Monitor';
export const REPOSITORY_URL = `https://github.com/${REPOSITORY}`;
export const API_URL = `https://api.github.com/repos/${REPOSITORY}`;
const SHA = /^[a-f0-9]{40}$/i;
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export async function request(
  url,
  { fetchImpl = fetch, token, optional = false, binary = false } = {},
) {
  const endpoint = new URL(url);
  if (
    !['api.github.com', 'raw.githubusercontent.com'].includes(endpoint.hostname) ||
    endpoint.protocol !== 'https:' ||
    endpoint.username ||
    endpoint.password ||
    endpoint.port
  )
    throw new Error('Unapproved application source URL');
  const headers = { 'User-Agent': 'Battery-Monitor-website-importer' };
  if (endpoint.hostname === 'api.github.com') {
    headers.Accept = 'application/vnd.github+json';
    headers['X-GitHub-Api-Version'] = '2026-03-10';
    if (token) headers.Authorization = `Bearer ${token}`;
  }
  for (let attempt = 0; attempt < 4; attempt++) {
    let response;
    try {
      response = await fetchImpl(url, {
        headers,
        signal: AbortSignal.timeout(20000),
        redirect: 'error',
      });
    } catch (error) {
      if (attempt === 3)
        throw new Error(`Application source request failed: ${endpoint.pathname}`, {
          cause: error,
        });
      await pause(250 * 2 ** attempt);
      continue;
    }
    if (optional && response.status === 404) return null;
    const retryable =
      response.status === 429 ||
      response.status >= 500 ||
      (response.status === 403 && response.headers.get('x-ratelimit-remaining') === '0');
    if (retryable && attempt < 3) {
      const retryAfter = Number(response.headers.get('retry-after')) * 1000;
      await pause(
        Math.min(10000, Math.max(250 * 2 ** attempt, Number.isFinite(retryAfter) ? retryAfter : 0)),
      );
      continue;
    }
    if (!response.ok)
      throw new Error(
        `Application source request returned ${response.status}: ${endpoint.pathname}`,
      );
    const declared = Number(response.headers.get('content-length'));
    if (declared > 20 * 1024 * 1024)
      throw new Error('Application source exceeds the importer size limit');
    const chunks = [];
    let total = 0;
    for await (const chunk of response.body ?? []) {
      total += chunk.length;
      if (total > 20 * 1024 * 1024)
        throw new Error('Application source exceeds the importer size limit');
      chunks.push(chunk);
    }
    const bytes = Buffer.concat(chunks);
    if (binary) return bytes;
    try {
      return JSON.parse(bytes.toString('utf8'));
    } catch (error) {
      throw new Error(`Invalid JSON returned by GitHub: ${endpoint.pathname}`, { cause: error });
    }
  }
}

export async function listReleases(options = {}) {
  const releases = [];
  for (let page = 1; page <= 100; page++) {
    const result = await request(`${API_URL}/releases?per_page=100&page=${page}`, options);
    if (!Array.isArray(result)) throw new Error('GitHub release list is not an array');
    releases.push(...result);
    if (result.length < 100) return releases;
  }
  throw new Error('GitHub release pagination exceeded the importer limit');
}

export function stableReleases(releases) {
  return releases
    .filter(
      (release) =>
        release &&
        release.draft === false &&
        release.prerelease === false &&
        typeof release.tag_name === 'string' &&
        release.tag_name.length &&
        Number.isFinite(Date.parse(release.published_at)),
    )
    .sort((a, b) => Date.parse(b.published_at) - Date.parse(a.published_at) || b.id - a.id);
}

export function selectStableRelease(releases) {
  const release = stableReleases(releases)[0];
  if (!release) throw new Error('No published stable application release is available');
  if (!Number.isSafeInteger(release.id) || release.id < 1)
    throw new Error('Selected stable release has an invalid ID');
  const expected = `${REPOSITORY_URL}/releases/tag/${encodeURIComponent(release.tag_name)}`;
  if (release.html_url !== expected && release.html_url !== expected.replaceAll('%2F', '/'))
    throw new Error('Selected stable release has an unexpected URL');
  return release;
}

export async function resolveTagCommit(tag, options = {}) {
  // eslint-disable-next-line no-control-regex -- Git tag input must not contain control characters.
  if (typeof tag !== 'string' || !tag || tag.length > 200 || /[\x00-\x1f]/.test(tag))
    throw new Error('Invalid release tag');
  let object = (await request(`${API_URL}/git/ref/tags/${encodeURIComponent(tag)}`, options))
    ?.object;
  const seen = new Set();
  for (let depth = 0; depth < 8; depth++) {
    if (!object || !SHA.test(object.sha))
      throw new Error('Release tag did not resolve to a valid Git object');
    if (object.type === 'commit') return object.sha.toLowerCase();
    if (object.type !== 'tag' || seen.has(object.sha))
      throw new Error('Release tag does not resolve to a commit');
    seen.add(object.sha);
    object = (await request(`${API_URL}/git/tags/${object.sha}`, options))?.object;
  }
  throw new Error('Release tag annotation depth exceeds the importer limit');
}

export async function resolveLatestRelease(options = {}) {
  const releases = await listReleases(options);
  const release = selectStableRelease(releases);
  return { release, appCommit: await resolveTagCommit(release.tag_name, options), releases };
}

export async function listReleaseAssets(release, options = {}) {
  if (!Number.isSafeInteger(release.id) || release.id < 1)
    throw new Error('Invalid release ID for assets');
  const assets = [];
  for (let page = 1; page <= 100; page++) {
    const result = await request(
      `${API_URL}/releases/${release.id}/assets?per_page=100&page=${page}`,
      options,
    );
    if (!Array.isArray(result)) throw new Error('GitHub asset list is not an array');
    assets.push(...result);
    if (result.length < 100) return assets;
  }
  throw new Error('GitHub asset pagination exceeded the importer limit');
}

export function selectReleaseAssets(assets, tag) {
  const apks = assets.filter(
    (asset) =>
      asset?.state === 'uploaded' &&
      Number.isSafeInteger(asset.size) &&
      asset.size > 0 &&
      typeof asset.name === 'string' &&
      asset.name.endsWith('.apk'),
  );
  const exact = apks.filter(
    (asset) => asset.name === `battery-monitor-${tag.replace(/^v/, '')}.apk`,
  );
  const apk = exact.length === 1 ? exact[0] : apks.length === 1 ? apks[0] : null;
  if (!apk) throw new Error('Stable release must contain one unambiguous uploaded APK asset');
  const validUrl = (asset) => {
    const url = new URL(asset.browser_download_url);
    const expected = `${REPOSITORY_URL}/releases/download/${encodeURIComponent(tag)}/${encodeURIComponent(asset.name)}`;
    if (
      url.protocol !== 'https:' ||
      url.href !== expected ||
      url.search ||
      url.hash ||
      url.username ||
      url.password
    )
      throw new Error('Release asset has an unexpected download URL');
    return asset.browser_download_url;
  };
  const signatures = assets.filter(
    (asset) =>
      asset?.state === 'uploaded' &&
      asset.size > 0 &&
      [`${apk.name}.asc`, `${apk.name}.sig`].includes(asset.name),
  );
  const signature = signatures.find((asset) => asset.name.endsWith('.asc')) ?? signatures[0];
  const fingerprint = (asset) => {
    if (
      !Number.isSafeInteger(asset.id) ||
      asset.id < 1 ||
      !Number.isSafeInteger(asset.size) ||
      asset.size < 1 ||
      typeof asset.updated_at !== 'string' ||
      !Number.isFinite(Date.parse(asset.updated_at))
    )
      throw new Error('Release asset has incomplete identity metadata');
    if (asset.digest != null && !/^sha256:[a-f0-9]{64}$/i.test(asset.digest))
      throw new Error('Release asset has an invalid SHA-256 digest');
    return {
      id: asset.id,
      name: asset.name,
      size: asset.size,
      updatedAt: new Date(asset.updated_at).toISOString(),
      url: validUrl(asset),
      digest: asset.digest?.toLowerCase() ?? null,
    };
  };
  const releaseAssets = {
    apk: fingerprint(apk),
    signature: signature ? fingerprint(signature) : null,
  };
  return {
    apkUrl: releaseAssets.apk.url,
    signatureUrl: releaseAssets.signature?.url ?? null,
    apkSha256: releaseAssets.apk.digest?.slice(7) ?? null,
    releaseAssets,
  };
}
