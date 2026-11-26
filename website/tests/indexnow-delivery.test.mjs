import assert from 'node:assert/strict';
import test from 'node:test';
import { deliverIndexNow, isIndexNowKey } from '../scripts/deploy/indexnow-delivery.mjs';
import { INDEXNOW_ORIGIN } from '../scripts/deploy/indexnow-pages.mjs';

const key = 'abc123-IndexNow-fixture-key';
const keyLocation = INDEXNOW_ORIGIN + '/' + key + '.txt';
const endpoint = 'https://api.indexnow.org/indexnow';
const page = (name) => INDEXNOW_ORIGIN + '/' + name + '/';
const updatedUrl = page('help');
const update = (url) => ({ url, kind: 'update' });
const removal = (url) => ({ url, kind: 'removal' });
const html = (body) => new Response(body, { headers: { 'content-type': 'text/html' } });
const text = (body) => new Response(body, { headers: { 'content-type': 'text/plain' } });

async function simulate({
  pending = [update(updatedUrl)],
  pages = { [updatedUrl]: 'hash' },
  serve = () => undefined,
  ...options
} = {}) {
  const calls = [];
  const posts = [];
  const waits = [];
  const http = {
    fetchFn: async (url, init) => {
      calls.push(url);
      assert.equal(init.redirect, 'manual');
      assert.ok(init.signal instanceof AbortSignal);
      if (url === endpoint) {
        assert.equal(init.method, 'POST');
        assert.deepEqual(init.headers, { 'Content-Type': 'application/json; charset=utf-8' });
        posts.push(JSON.parse(init.body));
      } else assert.equal(init.headers, undefined);
      const override = serve(url, posts.length);
      if (override) return override;
      if (url === keyLocation) return text(key + '\r\n');
      if (url === INDEXNOW_ORIGIN + '/robots.txt')
        return text('User-agent: *\nDisallow: /private/');
      if (url === endpoint) return new Response(null, { status: 200 });
      throw new Error('Unexpected mock URL ' + url);
    },
    delay: async (ms) => waits.push(ms),
  };
  const report = await deliverIndexNow({
    websiteUrl: INDEXNOW_ORIGIN + '/',
    indexNowKey: key,
    pages,
    pending,
    http,
    ...options,
  });
  return { report, calls, posts, waits };
}

test('submits sorted public URLs and verification fields, distinguishing receipt from pending verification', async () => {
  for (const status of [200, 202]) {
    const second = page('about');
    const { report, posts } = await simulate({
      pending: [update(updatedUrl), update(second)],
      pages: { [updatedUrl]: 'hash', [second]: 'second-hash' },
      serve: (url) => url === endpoint && new Response(null, { status }),
    });
    assert.deepEqual(posts, [
      { host: new URL(INDEXNOW_ORIGIN).host, key, keyLocation, urlList: [second, updatedUrl] },
    ]);
    assert.equal(report.mode, 'submitted');
    assert.equal(report.updates, 2);
    assert.deepEqual(report.acceptedUrls, [second, updatedUrl]);
    assert.equal(report.accepted200, status === 200 ? 2 : 0);
    assert.equal(report.accepted202, status === 202 ? 2 : 0);
    assert.equal(report.pending, 0);
    assert.deepEqual(report.warnings, []);
  }
});

test('skips all network calls without pending work or outside the production origin', async () => {
  for (const setup of [{ pending: [] }, { websiteUrl: 'https://preview.invalid/' }]) {
    const { report, calls } = await simulate(setup);
    assert.equal(report.mode, 'skip');
    assert.deepEqual(calls, []);
  }
});

test('missing or invalid keys retain pending work and the endpoint cooldown without public requests', async () => {
  for (const value of [
    undefined,
    '',
    'short',
    key + '\n',
    key + '/path',
    '-leading-hyphen',
    'a'.repeat(129),
  ]) {
    assert.equal(isIndexNowKey(value), false);
    const retryAfterUntil = '2098-01-01T00:00:00.000Z';
    const { report, calls } = await simulate({
      indexNowKey: value,
      retryAfterUntil,
      pending: [update(updatedUrl), removal(page('gone'))],
    });
    assert.deepEqual(calls, []);
    assert.equal(report.mode, 'skip');
    assert.equal(report.pending, 2);
    assert.equal(report.updates, 1);
    assert.equal(report.deferred, 1);
    assert.equal(report.retryAfterUntil, retryAfterUntil);
    assert.match(report.warnings.join(' '), /INDEXNOW_KEY is missing or invalid/);
    if (value) assert.ok(!JSON.stringify(report).includes(value));
  }
  for (const value of ['A12b-456', 'z'.repeat(128)]) assert.equal(isIndexNowKey(value), true);
});

test('checks the anonymous key response and crawler access before posting without reporting the key', async () => {
  const probes = [
    { serve: (url) => url === keyLocation && new Response(null, { status: 403 }) },
    { serve: (url) => url === keyLocation && new Response(null, { status: 302 }) },
    { serve: (url) => url === keyLocation && html(key) },
    { serve: (url) => url === keyLocation && text('<h1>Cloud challenge</h1>') },
    { serve: (url) => url === keyLocation && text(key + '\n\n') },
    {
      serve: (url) =>
        url === keyLocation &&
        new Response(
          new ReadableStream({
            start(controller) {
              controller.error(new Error('Could not read ' + url));
            },
          }),
        ),
    },
    { serve: (url) => url.endsWith('/robots.txt') && text('User-agent: *\nDisallow: /*.txt$') },
  ];
  for (const setup of probes) {
    const { report, posts } = await simulate(setup);
    assert.deepEqual(posts, []);
    assert.equal(report.pending, 1);
    assert.match(report.warnings.join(' '), /key check failed/);
    assert.ok(!JSON.stringify(report).includes(key));
  }
});

test('notifies verified public removals and moves while retaining still-indexable URLs and unverifiable redirects', async () => {
  const responses = {
    gone: new Response(null, { status: 410 }),
    missing: new Response(null, { status: 404 }),
    moved: new Response(null, { status: 301, headers: { location: '/help/' } }),
    hidden: html('<html><head><meta content="nofollow, NOINDEX" name="robots"></head></html>'),
    headers: new Response(null, { headers: { 'x-robots-tag': 'none' } }),
    retained: html('<h1>Still public</h1>'),
    outside: new Response(null, { status: 302, headers: { location: 'https://auth.invalid/' } }),
    challenge: new Response(null, { status: 302, headers: { location: '/login/' } }),
    template: html('<body><code>&lt;meta name="robots" content="noindex"&gt;</code></body>'),
    unavailable: new Response(null, { status: 403 }),
  };
  const originals = new Map(
    Object.entries(responses).map(([name, response]) => [page(name), response]),
  );
  const { posts, report } = await simulate({
    pending: [...originals.keys()].map(removal),
    serve: (url) => originals.get(url),
  });
  assert.deepEqual(posts[0].urlList, ['gone', 'headers', 'hidden', 'missing', 'moved'].map(page));
  assert.equal(report.removals, 5);
  assert.equal(report.deferred, 5);
  assert.equal(report.pending, 5);
  assert.match(report.warnings.join(' '), /HTTP 403/);
});

test('permanent failures and redirects leave work pending without immediate retries', async () => {
  for (const status of [400, 403, 422, 307, 204]) {
    const { posts, report, waits } = await simulate({
      serve: (url) => url === endpoint && new Response(null, { status }),
    });
    assert.equal(posts.length, 1);
    assert.deepEqual(waits, []);
    assert.equal(report.pending, 1);
    assert.deepEqual(report.acceptedUrls, []);
    assert.match(report.warnings.join(' '), new RegExp('HTTP ' + status));
  }
});

test('rate limits honor Retry-After and defer an excessive wait', async () => {
  for (const [retryAfter, wait] of [
    ['2', 2000],
    ['60', 0],
    ['Wed, 01 Jan 2098 00:00:00 GMT', 0],
  ]) {
    const { report, waits, posts } = await simulate({
      serve: (url, count) =>
        url === endpoint &&
        count === 1 &&
        new Response(null, { status: 429, headers: { 'Retry-After': retryAfter } }),
    });
    assert.deepEqual(waits, wait ? [wait] : []);
    assert.equal(posts.length, wait ? 2 : 1);
    assert.equal(report.pending, wait ? 0 : 1);
    if (!wait) {
      assert.match(report.warnings.join(' '), /429.*Retry-After/);
      assert.ok(Date.parse(report.retryAfterUntil) > Date.now());
    }
  }
});

test('honors a persisted endpoint cooldown while rechecking removals, then submits after expiry', async () => {
  const retryAfterUntil = '2098-01-01T00:00:00.000Z';
  const gone = page('gone');
  const setup = {
    pending: [update(updatedUrl), removal(gone)],
    serve: (url) => url === gone && new Response(null, { status: 410 }),
  };
  const paused = await simulate({ ...setup, retryAfterUntil });
  assert.deepEqual(paused.calls, [gone]);
  assert.equal(paused.report.mode, 'skip');
  assert.equal(paused.report.retryAfterUntil, retryAfterUntil);
  assert.equal(paused.report.updates, 1);
  assert.equal(paused.report.removals, 1);
  assert.equal(paused.report.pending, 2);
  const resumed = await simulate({ ...setup, retryAfterUntil: '2000-01-01T00:00:00.000Z' });
  assert.equal(resumed.report.retryAfterUntil, undefined);
  assert.equal(resumed.report.accepted200, 2);

  const exhausted = await simulate({
    serve: (url) =>
      url === endpoint && new Response(null, { status: 429, headers: { 'retry-after': '1' } }),
  });
  assert.equal(exhausted.posts.length, 3);
  assert.ok(Date.parse(exhausted.report.retryAfterUntil) > Date.now());
  const publicKeyThrottled = await simulate({
    serve: (url) =>
      url === keyLocation && new Response(null, { status: 429, headers: { 'retry-after': '60' } }),
  });
  assert.equal(publicKeyThrottled.report.retryAfterUntil, undefined);
  assert.deepEqual(publicKeyThrottled.posts, []);
});

test('network errors and server errors use bounded backoff, preserving exhausted work', async () => {
  const success = await simulate({
    serve(url, count) {
      if (url !== endpoint) return;
      if (count === 1) throw new Error('offline');
      if (count === 2) return new Response(null, { status: 501 });
    },
  });
  assert.equal(success.report.pending, 0);
  assert.deepEqual(success.waits, [250, 500]);
  for (const status of [0, 503]) {
    const { posts, report, waits } = await simulate({
      serve(url) {
        if (url !== endpoint) return;
        if (!status) throw new Error('still offline');
        return new Response(null, { status });
      },
    });
    assert.deepEqual(waits, [250, 500]);
    assert.equal(posts.length, 3);
    assert.equal(report.pending, 1);
    assert.equal(report.warnings.length, 1);
  }
});

test('only accepted batches are acknowledged when another batch fails', async () => {
  const urls = Array.from({ length: 10_001 }, (_, n) => page('page-' + n));
  const { posts, report } = await simulate({
    pages: Object.fromEntries(urls.map((url) => [url, 'hash'])),
    pending: urls.map(update),
    serve: (url, count) =>
      url === endpoint && new Response(null, { status: count === 1 ? 422 : 202 }),
  });
  assert.deepEqual(
    posts.map(({ urlList }) => urlList.length),
    [10_000, 1],
  );
  assert.deepEqual(report.acceptedUrls, posts[1].urlList);
  assert.equal(report.accepted202, 1);
  assert.equal(report.pending, 10_000);
});
