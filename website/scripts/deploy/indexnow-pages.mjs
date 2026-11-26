import { XMLParser, XMLValidator } from 'fast-xml-parser';
import sanitizeHtml from 'sanitize-html';
import { safePath, sha256 } from './core.mjs';

export const INDEXNOW_ORIGIN = 'https://batterymonitor.swistak.codes';
const site = INDEXNOW_ORIGIN + '/';

export function isIndexNowPageUrl(value) {
  if (typeof value !== 'string' || !value.startsWith(site) || !value.endsWith('/')) return false;
  const route = value.slice(site.length);
  if (/^(?:404|site|website-deploy-state|_astro)\//i.test(route)) return false;
  try {
    safePath(route + 'index.html');
    return true;
  } catch {
    return false;
  }
}

function pageMetadata(html) {
  const canonicals = new Set();
  let noindex = false;
  let inHead = true;
  let base;
  sanitizeHtml(html, {
    onOpenTag(name, attrs) {
      if (name === 'body') inHead = false;
      if (!inHead) return;
      if (name === 'base' && base === undefined) base = attrs.href;
      if (name === 'link' && attrs.rel?.toLowerCase().split(/\s+/).includes('canonical'))
        canonicals.add(attrs.href);
      if (name === 'meta' && attrs.name?.toLowerCase() === 'robots')
        noindex ||= /(?:^|[\s,])(?:noindex|none)(?:$|[\s,])/i.test(attrs.content ?? '');
    },
    onCloseTag(name) {
      if (name === 'head') inHead = false;
    },
  });
  return { canonicals, noindex, base };
}

export function hasRobotsNoindex(html) {
  return pageMetadata(html).noindex;
}

function pageVersion(html, fileHash) {
  const normalized = html.replace(
    /(<script id="analytics-config" type="application\/json">\{[^<]*?"appVersion":)"(?:\\.|[^"\\])*"(?=\}<\/script>)/,
    '$1""',
  );
  return normalized === html
    ? fileHash
    : { sha256: fileHash, comparisonSha256: sha256(normalized) };
}

export async function readIndexNowPages(manifest, readFileBytes, previousPages = {}) {
  const files = new Map(manifest.files.map((file) => [file.path, file]));
  async function read(path) {
    const file = files.get(path);
    if (!file) throw new Error(`Missing IndexNow sitemap file: ${path}`);
    const bytes = await readFileBytes(path, file.sha256);
    if (sha256(bytes) !== file.sha256)
      throw new Error(`IndexNow baseline checksum mismatch: ${path}`);
    return Buffer.from(bytes).toString('utf8');
  }
  const urls = new Set();
  const visited = new Set();
  const parser = new XMLParser({
    parseTagValue: false,
    isArray: (name) => name === 'url' || name === 'sitemap',
  });
  async function visit(path) {
    if (visited.has(path)) return;
    visited.add(path);
    const xml = await read(path);
    if (/<!\s*(?:DOCTYPE|ENTITY)\b/i.test(xml) || XMLValidator.validate(xml) !== true)
      throw new Error(`Invalid IndexNow sitemap: ${path}`);
    const tree = parser.parse(xml);
    if (Object.hasOwn(tree, 'urlset')) {
      for (const { loc } of tree.urlset.url ?? []) if (isIndexNowPageUrl(loc)) urls.add(loc);
    } else if (Object.hasOwn(tree, 'sitemapindex')) {
      for (const { loc } of tree.sitemapindex.sitemap ?? []) {
        if (typeof loc !== 'string' || !loc.startsWith(site) || !loc.endsWith('.xml')) continue;
        await visit(safePath(loc.slice(site.length)));
      }
    } else throw new Error(`Unrecognized IndexNow sitemap: ${path}`);
  }
  await visit(files.has('sitemap-index.xml') ? 'sitemap-index.xml' : 'sitemap.xml');
  const pages = {};
  for (const url of [...urls].sort()) {
    const path = url.slice(site.length) + 'index.html';
    const file = files.get(path);
    if (!file) continue;
    const html = await read(path);
    const previous = previousPages[url];
    if ((previous?.sha256 ?? previous) === file.sha256) {
      pages[url] = previous;
      continue;
    }
    const { canonicals, noindex, base } = pageMetadata(html);
    const canonicalBase = base === undefined ? url : URL.parse(base, url);
    if (
      !noindex &&
      canonicals.size &&
      canonicalBase &&
      [...canonicals].every((href) => href && URL.parse(href, canonicalBase)?.href === url)
    )
      pages[url] = pageVersion(html, file.sha256);
  }
  return pages;
}

export function planIndexNow(pages, previousState = null, { submitAll = false } = {}) {
  const previousPages = previousState?.pages ?? {};
  const pendingUrls = new Set(previousState?.pending.map(({ url }) => url));
  const urls = new Set([...Object.keys(pages), ...Object.keys(previousPages), ...pendingUrls]);
  const pending = [];
  for (const url of [...urls].sort()) {
    if (!isIndexNowPageUrl(url)) throw new Error('Invalid page URL in IndexNow state');
    const current = pages[url]?.comparisonSha256 ?? pages[url];
    const previous = previousPages[url]?.comparisonSha256 ?? previousPages[url];
    if ((submitAll && Object.hasOwn(pages, url)) || current !== previous || pendingUrls.has(url))
      pending.push({ url, kind: Object.hasOwn(pages, url) ? 'update' : 'removal' });
  }
  return { pages, pending };
}

export function planLegacyIndexNowPreview(pages, previousManifest) {
  const hashes = new Map(previousManifest.files.map((file) => [file.path, file.sha256]));
  const previousPages = {};
  for (const [url, page] of Object.entries(pages)) {
    const hash = hashes.get(url.slice(site.length) + 'index.html');
    if (hash) previousPages[url] = hash === (page?.sha256 ?? page) ? page : hash;
  }
  return planIndexNow(pages, { pages: previousPages, pending: [] });
}
