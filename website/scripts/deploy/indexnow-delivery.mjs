import { setTimeout as sleep } from 'node:timers/promises';
import { hasRobotsNoindex, INDEXNOW_ORIGIN, isIndexNowPageUrl } from './indexnow-pages.mjs';

const ENDPOINT = 'https://api.indexnow.org/indexnow';
const MAX_BATCH = 10_000;
const RETRY_BUDGET_MS = 5_000;

export function isIndexNowKey(value) {
  return typeof value === 'string' && /^[a-zA-Z0-9][a-zA-Z0-9-]{7,127}$/.test(value);
}

class DeliveryError extends Error {}

function failureReason(error) {
  return error instanceof DeliveryError ? error.message : 'Could not process the public response';
}

function retryAfterTime(value, now) {
  if (!/^\d+$/.test(value)) return Date.parse(value);
  return Math.min(8.64e15, now + Number(value) * 1000);
}

async function request(url, options, { fetchFn = fetch, delay = sleep }) {
  let budget = RETRY_BUDGET_MS;
  for (let attempt = 0; ; attempt++) {
    let response;
    try {
      response = await fetchFn(url, {
        ...options,
        redirect: 'manual',
        signal: AbortSignal.timeout(10_000),
      });
    } catch {
      if (attempt === 2) throw new DeliveryError('Network request failed after 3 attempts');
    }
    const status = response?.status;
    if (response && (attempt === 2 || (status !== 429 && (status < 500 || status >= 600))))
      return response;
    const retryAfter = response?.headers.get('retry-after');
    const now = Date.now();
    const requested = retryAfterTime(retryAfter, now) - now;
    const wait = Math.max(250 * 2 ** attempt, Number.isNaN(requested) ? 0 : requested);
    if (wait > budget) {
      if (!response)
        throw new DeliveryError('Network request failed and exhausted the retry budget');
      return response;
    }
    await response?.body?.cancel();
    await delay(wait);
    budget -= wait;
  }
}

async function readPublicText(response, limit) {
  const chunks = [];
  let size = 0;
  for await (const chunk of response.body ?? []) {
    size += chunk.length;
    if (size > limit) throw new DeliveryError('Public response exceeded the expected size');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks, size).toString('utf8');
}

async function verifyKey(key, http) {
  const filename = key + '.txt';
  const keyLocation = new URL(filename, INDEXNOW_ORIGIN).href;
  const response = await request(keyLocation, {}, http);
  const type = response.headers.get('content-type') || '';
  if (response.status !== 200 || /html/i.test(type)) {
    await response.body?.cancel();
    throw new DeliveryError(
      `Public key check failed (HTTP ${response.status}${/html/i.test(type) ? ', HTML response' : ''})`,
    );
  }
  const body = await readPublicText(response, key.length + 2);
  if (![key, key + '\n', key + '\r\n'].includes(body))
    throw new DeliveryError('Public verification file does not contain the configured key');

  const robots = await request(new URL('/robots.txt', INDEXNOW_ORIGIN).href, {}, http);
  if (robots.status !== 200) {
    await robots.body?.cancel();
    throw new DeliveryError(`Public robots.txt check failed (HTTP ${robots.status})`);
  }
  const rules = (await readPublicText(robots, 512_000)).replace(/#[^\n]*/g, '');
  for (const rule of rules.matchAll(/^\s*disallow:[ \t]*([^\r\n]+)/gim)) {
    const pattern = rule[1].trim();
    if (!pattern) continue;
    const anchor = pattern.endsWith('$');
    const prefix = anchor ? pattern.slice(0, -1) : pattern;
    const regex = prefix
      .split('*')
      .map((part) => RegExp.escape(part))
      .join('.*');
    if (new RegExp('^' + regex + (anchor ? '$' : '')).test('/' + filename))
      throw new DeliveryError(
        'Public robots.txt may disallow crawlers from fetching the IndexNow key',
      );
  }
  if (!/^\s*user-agent:\s*\*/im.test(rules))
    throw new DeliveryError('Public robots.txt is missing the website’s expected crawler policy');
  return { host: new URL(INDEXNOW_ORIGIN).host, key, keyLocation };
}

async function checkRemoval(url, pages, http) {
  const response = await request(url, {}, http);
  try {
    if ([404, 410].includes(response.status)) return true;
    if ([301, 302, 303, 307, 308].includes(response.status)) {
      const location = response.headers.get('location');
      if (!location || !URL.canParse(location, url)) return false;
      const target = new URL(location, url).href;
      return target !== url && isIndexNowPageUrl(target) && Object.hasOwn(pages, target);
    }
    if (response.status !== 200)
      throw new DeliveryError(`Public removal check returned HTTP ${response.status}`);
    const robots = response.headers.get('x-robots-tag') || '';
    if (!robots.includes(':') && /(?:^|[\s,])(?:noindex|none)(?:$|[\s,])/i.test(robots))
      return true;
    return (
      /(?:text\/html|application\/xhtml\+xml)/i.test(response.headers.get('content-type') || '') &&
      hasRobotsNoindex(await readPublicText(response, 1_000_000))
    );
  } finally {
    if (!response.bodyUsed) await response.body?.cancel();
  }
}

export function skippedIndexNow(reason, pending = 0) {
  return {
    mode: 'skip',
    updates: 0,
    removals: 0,
    deferred: 0,
    acceptedUrls: [],
    accepted200: 0,
    accepted202: 0,
    pending,
    reasons: reason ? [reason] : [],
    warnings: [],
  };
}

export async function deliverIndexNow({
  websiteUrl,
  indexNowKey,
  pages,
  pending,
  retryAfterUntil,
  http = {},
}) {
  const report = skippedIndexNow(null, pending.length);
  const warnings = new Set();
  if (![INDEXNOW_ORIGIN, INDEXNOW_ORIGIN + '/'].includes(websiteUrl)) {
    report.reasons.push('Configured website URL is outside the canonical production origin');
    return report;
  }
  const nextSubmission = Date.parse(retryAfterUntil);
  if (nextSubmission > Date.now()) report.retryAfterUntil = new Date(nextSubmission).toISOString();
  if (!isIndexNowKey(indexNowKey)) {
    const reason = 'INDEXNOW_KEY is missing or invalid. IndexNow delivery was skipped.';
    report.updates = pending.filter(({ kind }) => kind === 'update').length;
    report.deferred = pending.filter(({ kind }) => kind === 'removal').length;
    report.warnings.push(reason);
    report.reasons.push(reason);
    return report;
  }
  const selected = new Set();
  for (const { url, kind } of pending) {
    if (!isIndexNowPageUrl(url)) {
      warnings.add('Pending work contains a noncanonical URL');
      continue;
    }
    if (selected.has(url)) continue;
    if (kind === 'update' && Object.hasOwn(pages, url)) {
      report.updates++;
      selected.add(url);
    } else if (kind === 'removal' && !Object.hasOwn(pages, url)) {
      try {
        if (await checkRemoval(url, pages, http)) {
          report.removals++;
          selected.add(url);
          continue;
        }
      } catch (error) {
        warnings.add(`Removal check for ${url}: ${failureReason(error)}`);
      }
      report.deferred++;
    } else warnings.add('Pending work does not match the current page inventory');
  }
  if (report.deferred)
    report.reasons.push('Public removal, redirect or noindex remains unconfirmed');
  const urls = [...selected].sort();
  if (report.retryAfterUntil) {
    report.reasons.push(
      `IndexNow previously requested waiting until ${report.retryAfterUntil} before submitting`,
    );
  } else if (urls.length) {
    try {
      const keyFields = await verifyKey(indexNowKey, http);
      for (let start = 0; start < urls.length; start += MAX_BATCH) {
        const urlList = urls.slice(start, start + MAX_BATCH);
        report.mode = 'submitted';
        const response = await request(
          ENDPOINT,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json; charset=utf-8' },
            body: JSON.stringify({ ...keyFields, urlList }),
          },
          http,
        );
        const { status } = response;
        await response.body?.cancel();
        if (status === 200 || status === 202) {
          report.acceptedUrls.push(...urlList);
          if (status === 200) report.accepted200 += urlList.length;
          else report.accepted202 += urlList.length;
        } else if (status === 429) {
          const now = Date.now();
          const after = retryAfterTime(response.headers.get('retry-after'), now);
          if (after > now) report.retryAfterUntil = new Date(after).toISOString();
          warnings.add(
            `IndexNow rate limited further submissions (HTTP 429${report.retryAfterUntil ? ', Retry-After until ' + report.retryAfterUntil : ''})`,
          );
          break;
        } else if ([400, 403, 422].includes(status)) {
          warnings.add(`IndexNow configuration or URL validation failure (HTTP ${status})`);
          if (status === 403) break;
        } else warnings.add(`IndexNow did not accept a batch (HTTP ${status})`);
      }
    } catch (error) {
      warnings.add(
        `IndexNow ${report.mode === 'skip' ? 'key check' : 'submission'} failed: ${failureReason(error)}`,
      );
    }
  } else if (!pending.length) report.reasons.push('No pending changes');
  report.warnings = [...warnings];
  report.reasons.push(...warnings);
  report.pending -= report.acceptedUrls.length;
  return report;
}
