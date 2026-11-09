import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { request, sha256, validateManifest } from './core.mjs';

const ROUTES = [
  '/',
  '/download/',
  '/help/',
  '/help/battery-alarms/',
  '/help/notifications/',
  '/help/background-monitoring/',
  '/help/battery-readings/',
  '/help/charging-targets/',
  '/help/history-and-backups/',
  '/changelog/',
  '/privacy/',
  '/about/',
];

export async function smokeSite({ url, manifest, http = {}, local = false }) {
  validateManifest(manifest);
  const base = new URL(url);
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(base.hostname);
  if (
    base.username ||
    base.password ||
    base.pathname !== '/' ||
    base.search ||
    base.hash ||
    (base.protocol !== 'https:' && !(local && loopback && base.protocol === 'http:'))
  )
    throw new Error('Smoke URL must be an HTTPS origin, or explicit local loopback origin');
  const checked = [];
  let download = '';
  for (const path of ROUTES) {
    const response = await request(new URL(path, base), {}, http);
    if (response.status !== 200 || !response.headers.get('content-type')?.includes('text/html'))
      throw new Error(`Smoke route failed: ${path} (${response.status})`);
    const html = await response.text();
    if (!/<h1[\s>]/i.test(html)) throw new Error(`Smoke route lacks useful HTML: ${path}`);
    if (!local) {
      if (
        response.headers.get('referrer-policy') !== 'no-referrer' ||
        response.headers.get('x-content-type-options') !== 'nosniff'
      )
        throw new Error(`Missing required privacy/security response headers: ${path}`);
      const csp = response.headers.get('content-security-policy') || '';
      if (
        !csp.includes("default-src 'self'") ||
        !csp.includes("object-src 'none'") ||
        !csp.includes("base-uri 'self'")
      )
        throw new Error(`Missing required CSP: ${path}`);
      if (!response.headers.get('cache-control'))
        throw new Error(`Missing explicit caching policy: ${path}`);
    }
    if (path === '/download/') download = html;
    checked.push(path);
  }
  if (!download.includes(manifest.provenance.version))
    throw new Error('Deployed download page does not identify the expected stable version');
  const robots = await request(new URL('/robots.txt', base), {}, http);
  const robotsText = await robots.text();
  if (robots.status !== 200 || !/Sitemap:\s*https:\/\//i.test(robotsText))
    throw new Error('robots.txt is missing its canonical sitemap');
  checked.push('/robots.txt');
  const sitemapPaths = manifest.files
    .filter((file) => /(?:^|\/)sitemap(?:-index|-\d+)?\.xml$/.test(file.path))
    .map((file) => '/' + file.path);
  if (!sitemapPaths.length) throw new Error('Deployment manifest contains no sitemap');
  const sitemapUrls = new Set();
  for (const path of sitemapPaths) {
    const response = await request(new URL(path, base), {}, http);
    if (response.status !== 200) throw new Error(`Sitemap failed: ${path}`);
    const body = await response.text();
    for (const match of body.matchAll(/<loc>\s*([^<]+)\s*<\/loc>/g)) {
      const canonical = new URL(match[1].replaceAll('&amp;', '&'));
      if (canonical.origin !== base.origin || canonical.search || canonical.hash)
        throw new Error('Sitemap contains a noncanonical URL');
      sitemapUrls.add(canonical.pathname);
    }
    checked.push(path);
  }
  if (!sitemapUrls.has('/') || sitemapUrls.has('/404/'))
    throw new Error('Sitemap indexable page set is invalid');
  for (const asset of manifest.files.filter((file) => file.phase === 0)) {
    const response = await request(new URL('/' + asset.path, base), {}, http);
    if (
      response.status !== 200 ||
      sha256(Buffer.from(await response.arrayBuffer())) !== asset.sha256
    )
      throw new Error(`Deployed asset checksum failed: ${asset.path}`);
    if (
      !local &&
      asset.path.startsWith('_astro/') &&
      !response.headers.get('cache-control')?.includes('immutable')
    )
      throw new Error(`Hashed asset lacks immutable caching: ${asset.path}`);
    checked.push('/' + asset.path);
  }
  const missing = await request(
    new URL('/__battery-monitor-smoke-nonexistent-route__/', base),
    {},
    http,
  );
  if (missing.status !== 404)
    throw new Error(`A nonexistent route returned ${missing.status}, expected actual 404`);
  checked.push('actual 404');
  return { checked, productionHeadersChecked: !local, version: manifest.provenance.version };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const { values } = parseArgs({
    options: { url: { type: 'string' }, local: { type: 'boolean' } },
    strict: true,
  });
  const root = fileURLToPath(new URL('../../', import.meta.url));
  const manifest = JSON.parse(
    await readFile(resolve(root, '.artifacts/deployment-manifest.json'), 'utf8'),
  );
  const result = await smokeSite({
    url: values.url || process.env.WEBSITE_URL || 'https://batterymonitor.swistak.codes/',
    manifest,
    local: values.local === true,
  });
  console.log(
    `Smoke checks passed: ${result.checked.length}; version ${result.version}; production headers ${result.productionHeadersChecked ? 'checked' : 'not checked (local mode)'}.`,
  );
}
