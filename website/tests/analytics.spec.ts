import { test, expect, type BrowserContext, type Page, type Request } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { startAnalyticsHarness } from './fixtures/analytics-test-server.mjs';

const consentKey = 'bm.analytics-consent';
const version = 'analytics-v1';
const validity = 180 * 24 * 60 * 60 * 1000;
const tracker = await readFile(new URL('./fixtures/matomo/matomo.js', import.meta.url));
const pixel = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7', 'base64');
let harness: Awaited<ReturnType<typeof startAnalyticsHarness>>;

type Captured = { params: URLSearchParams; headers: Record<string, string> };

test.beforeAll(async () => {
  harness = await startAnalyticsHarness();
});
test.afterAll(async () => {
  await harness?.close();
});

function receipt(choice: 'accept' | 'reject', overrides: Record<string, unknown> = {}) {
  const now = Date.now();
  return JSON.stringify({
    choice,
    version,
    decidedAt: now,
    expiresAt: now + validity,
    ...overrides,
  });
}

async function seed(context: BrowserContext, value: string) {
  await context.addInitScript(({ key, value }) => localStorage.setItem(key, value), {
    key: consentKey,
    value,
  });
}

async function capture(
  context: BrowserContext,
  options: { blocked?: boolean; delayed?: boolean } = {},
) {
  const requests: Captured[] = [];
  const scripts: Request[] = [];
  let release: () => void = () => {};
  const gate = options.delayed
    ? new Promise<void>((resolve) => {
        release = resolve;
      })
    : Promise.resolve();
  context.on('request', (request) => {
    const url = new URL(request.url());
    if (url.hostname !== 'analytics.invalid') return;
    if (url.pathname === '/matomo.js') scripts.push(request);
    if (url.pathname === '/matomo.php') {
      requests.push({
        params: new URLSearchParams(request.postData() ?? url.search),
        headers: request.headers(),
      });
    }
  });
  await context.route('https://analytics.invalid/**', async (route) => {
    if (new URL(route.request().url()).pathname === '/matomo.js') {
      if (options.blocked) return route.abort('blockedbyclient');
      await gate;
      await route.fulfill({ status: 200, contentType: 'text/javascript', body: tracker });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'image/gif', body: pixel });
  });
  return { requests, scripts, release };
}

async function accept(page: Page) {
  await page.locator('[data-privacy-accept]:visible').first().focus();
  await page.keyboard.press('Enter');
}

async function reject(page: Page) {
  await page.locator('[data-privacy-reject]:visible').first().click();
}

async function withdraw(page: Page) {
  await page.locator('[data-privacy-settings]').first().click();
  await page.locator('[data-privacy-withdraw]:visible').click();
}

async function expectQuiet(page: Page, captured: Awaited<ReturnType<typeof capture>>, count = 0) {
  // A bounded observation window checks absence of network effects, not UI timing.
  await page.waitForTimeout(200);
  expect(captured.requests).toHaveLength(count);
}

test('fresh and rejected visits load no tracker or analytics cookies, including after reload', async ({
  context,
  page,
}) => {
  const captured = await capture(context);
  await page.goto(harness.url);
  await page.locator('[data-analytics-event="apk"]').click();
  await expectQuiet(page, captured);
  expect(captured.scripts).toHaveLength(0);
  expect(
    (await context.cookies()).filter((cookie) => cookie.name.startsWith('bm_pk_')),
  ).toHaveLength(0);
  await reject(page);
  expect(
    JSON.parse((await page.evaluate((key) => localStorage.getItem(key), consentKey)) ?? '{}')
      .choice,
  ).toBe('reject');
  await page.reload();
  await page.locator('[data-analytics-event="apk"]').click();
  await expectQuiet(page, captured);
  expect(captured.scripts).toHaveLength(0);
});

test('acceptance sends one current page view and only later actions, without duplicate conversions', async ({
  context,
  page,
}) => {
  const captured = await capture(context);
  await page.goto(harness.url);
  await page.locator('[data-analytics-event="apk"]').click();
  await accept(page);
  await expect
    .poll(
      () => captured.requests.filter((item) => item.params.get('e_a') === 'consent-granted').length,
    )
    .toBe(1);
  expect(captured.requests.filter((item) => !item.params.has('e_c'))).toHaveLength(1);
  expect(captured.requests.filter((item) => item.params.get('e_a') === 'apk')).toHaveLength(0);
  expect(captured.scripts).toHaveLength(1);
  await page.locator('[data-analytics-event="apk"]').click();
  await expect
    .poll(() => captured.requests.filter((item) => item.params.get('e_a') === 'apk').length)
    .toBe(1);
  const event = captured.requests.find((item) => item.params.get('e_a') === 'apk')!.params;
  expect(event.get('e_c')).toBe('Distribution');
  expect(event.get('e_n')).toBe('home|download|github|2.0.0');
  await expectQuiet(page, captured, 3);
});

test('a returning acceptance tracks the new page once without another consent-granted event', async ({
  context,
  page,
}) => {
  const captured = await capture(context);
  await seed(context, receipt('accept'));
  await page.goto(harness.url);
  await expect.poll(() => captured.requests.length).toBe(1);
  expect(captured.requests[0]!.params.has('e_c')).toBe(false);
  await page.reload();
  await expect.poll(() => captured.requests.length).toBe(2);
  expect(
    captured.requests.filter((item) => item.params.get('e_a') === 'consent-granted'),
  ).toHaveLength(0);
});

test('withdrawal blocks later events and removes only this site tracker cookies', async ({
  context,
  page,
}) => {
  const captured = await capture(context);
  await page.goto(harness.url);
  await context.addCookies([
    { name: 'bm_pk_id.99.keep', value: 'other-site', url: harness.url },
    { name: 'unrelated', value: 'keep', url: harness.url },
  ]);
  await accept(page);
  await expect.poll(() => captured.requests.length).toBe(2);
  const ownCookies = (await context.cookies()).filter((cookie) =>
    /^bm_pk_(?:id|ses|ref)\.7\./.test(cookie.name),
  );
  expect(ownCookies.length).toBeGreaterThan(0);
  expect(ownCookies.every((cookie) => cookie.domain === '127.0.0.1' && cookie.path === '/')).toBe(
    true,
  );
  const visitorCookie = ownCookies.find((cookie) => cookie.name.startsWith('bm_pk_id.7.'));
  expect(visitorCookie).toBeDefined();
  expect(visitorCookie!.expires).toBeLessThanOrEqual(Date.now() / 1000 + 15_552_005);
  const sessionCookie = ownCookies.find((cookie) => cookie.name.startsWith('bm_pk_ses.7.'));
  expect(sessionCookie).toBeDefined();
  expect(sessionCookie!.expires).toBeLessThanOrEqual(Date.now() / 1000 + 1805);
  await withdraw(page);
  await page.keyboard.press('Escape');
  await page.locator('[data-analytics-event="apk"]').click();
  await expectQuiet(page, captured, 2);
  const remaining = await context.cookies();
  expect(remaining.filter((cookie) => /^bm_pk_(?:id|ses|ref)\.7\./.test(cookie.name))).toHaveLength(
    0,
  );
  expect(remaining.some((cookie) => cookie.name === 'bm_pk_id.99.keep')).toBe(true);
  expect(remaining.some((cookie) => cookie.name === 'unrelated')).toBe(true);
  expect(remaining.some((cookie) => cookie.name.startsWith('mtm_consent'))).toBe(false);
  expect(
    JSON.parse((await page.evaluate((key) => localStorage.getItem(key), consentKey)) ?? '{}')
      .choice,
  ).toBe('reject');
});

test('withdrawing immediately after the first page view prevents delayed tracker requests', async ({
  context,
  page,
}) => {
  const captured = await capture(context);
  await page.goto(harness.url);
  const firstPageView = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return (
      url.hostname === 'analytics.invalid' &&
      url.pathname === '/matomo.php' &&
      !url.searchParams.has('e_c')
    );
  });
  await accept(page);
  await firstPageView;
  await withdraw(page);
  const sentBeforeWithdrawal = captured.requests.length;
  // The real tracker delays early follow-up requests; observe beyond that window after withdrawing.
  await page.waitForTimeout(1200);
  expect(captured.requests).toHaveLength(sentBeforeWithdrawal);
  expect(captured.requests.filter((request) => !request.params.has('e_c'))).toHaveLength(1);
  expect(
    captured.requests.every(
      (request) => request.params.get('idsite') === '7' && request.params.get('rec') === '1',
    ),
  ).toBe(true);
});

test('granting and renewing consent never reads high-entropy browser features', async ({
  context,
  page,
}) => {
  const captured = await capture(context);
  await context.addInitScript(() => {
    let reads = 0;
    Object.defineProperty(window, '__analyticsFeatureReads', { get: () => reads });
    Object.defineProperty(navigator, 'userAgentData', {
      configurable: true,
      value: {
        brands: [],
        platform: 'Test',
        getHighEntropyValues: async () => {
          reads++;
          return { brands: [], platform: 'Test' };
        },
      },
    });
  });
  await page.goto(harness.url);
  await accept(page);
  await expect.poll(() => captured.requests.length).toBe(2);
  await page.locator('[data-analytics-event="apk"]').click();
  await expect
    .poll(() => captured.requests.filter((request) => request.params.get('e_a') === 'apk').length)
    .toBe(1);
  await withdraw(page);
  await page.locator('[data-privacy-settings]').first().click();
  await accept(page);
  await expect
    .poll(
      () =>
        captured.requests.filter((request) => request.params.get('e_a') === 'consent-granted')
          .length,
    )
    .toBe(2);
  await page.locator('[data-analytics-event="apk"]').click();
  await expect
    .poll(() => captured.requests.filter((request) => request.params.get('e_a') === 'apk').length)
    .toBe(2);
  expect(await page.evaluate(() => Number(Reflect.get(window, '__analyticsFeatureReads')))).toBe(0);
  expect(captured.requests.every((request) => !request.params.has('uadata'))).toBe(true);
});

test('withdrawal while the real tracker is loading cannot grant it consent afterward', async ({
  context,
  page,
}) => {
  const captured = await capture(context, { delayed: true });
  await page.goto(harness.url);
  await accept(page);
  await expect.poll(() => captured.scripts.length).toBe(1);
  await withdraw(page);
  captured.release();
  await page.keyboard.press('Escape');
  await page.locator('[data-analytics-event="apk"]').click();
  await expectQuiet(page, captured);
  expect(
    (await context.cookies()).filter((cookie) => cookie.name.startsWith('bm_pk_')),
  ).toHaveLength(0);
});

test('a new explicit acceptance works after withdrawal during tracker loading', async ({
  context,
  page,
}) => {
  const captured = await capture(context, { delayed: true });
  await page.goto(harness.url);
  await accept(page);
  await expect.poll(() => captured.scripts.length).toBe(1);
  await withdraw(page);
  captured.release();
  await expect.poll(() => page.evaluate(() => 'Matomo' in window)).toBe(true);
  await expectQuiet(page, captured);
  await page.locator('[data-privacy-settings]').first().click();
  await accept(page);
  await expect.poll(() => captured.requests.length).toBe(2);
  expect(captured.requests.filter((item) => !item.params.has('e_c'))).toHaveLength(1);
  expect(
    captured.requests.filter((item) => item.params.get('e_a') === 'consent-granted'),
  ).toHaveLength(1);
  expect(captured.scripts).toHaveLength(1);
});

test('another tab withdrawing consent stops tracking in an already-open tab', async ({
  context,
  page,
}) => {
  const captured = await capture(context);
  await page.goto(harness.url);
  await accept(page);
  await expect.poll(() => captured.requests.length).toBe(2);
  const second = await context.newPage();
  await second.goto(harness.url);
  await expect.poll(() => captured.requests.length).toBe(3);
  await withdraw(page);
  await second.locator('[data-analytics-event="apk"]').click();
  await expectQuiet(second, captured, 3);
});

for (const [name, value] of [
  ['malformed', '{not-json'],
  ['old purpose', receipt('accept', { version: 'old-purpose' })],
  [
    'expired',
    receipt('accept', { decidedAt: Date.now() - validity - 1000, expiresAt: Date.now() - 1000 }),
  ],
  ['future decision', receipt('accept', { decidedAt: Date.now() + 86_400_000 })],
] as const) {
  test(`${name} preference does not enable analytics`, async ({ context, page }) => {
    const captured = await capture(context);
    await seed(context, value);
    await page.goto(harness.url);
    await expectQuiet(page, captured);
    expect(captured.scripts).toHaveLength(0);
  });
}

for (const signal of ['globalPrivacyControl', 'doNotTrack'] as const) {
  test(`${signal} takes precedence over a remembered acceptance`, async ({ context, page }) => {
    const captured = await capture(context);
    await seed(context, receipt('accept'));
    await context.addInitScript(
      (signal) =>
        Object.defineProperty(navigator, signal, {
          value: signal === 'globalPrivacyControl' ? true : '1',
        }),
      signal,
    );
    await page.goto(harness.url);
    await page.locator('[data-analytics-event="apk"]').click();
    await expectQuiet(page, captured);
    expect(captured.scripts).toHaveLength(0);
  });
}

test('expiry in a long-lived page stops subsequent analytics', async ({ context, page }) => {
  const captured = await capture(context);
  const now = Date.now();
  const expiry = now + 60_000;
  await page.clock.install({ time: new Date(now) });
  await seed(context, receipt('accept', { decidedAt: expiry - validity, expiresAt: expiry }));
  await page.goto(harness.url);
  await expect.poll(() => captured.requests.length).toBe(1);
  await page.clock.fastForward(61_000);
  await page.locator('[data-analytics-event="apk"]').click();
  await expectQuiet(page, captured, 1);
});

test('returning through browser history respects a withdrawal made in another tab', async ({
  context,
  page,
}) => {
  const captured = await capture(context);
  await page.goto(harness.url);
  await accept(page);
  await expect.poll(() => captured.requests.length).toBe(2);
  await page.goto(harness.url + '/second/');
  await expect.poll(() => captured.requests.length).toBe(3);
  const second = await context.newPage();
  await second.goto(harness.url);
  await expect.poll(() => captured.requests.length).toBe(4);
  await withdraw(second);
  await page.goBack();
  await page.locator('[data-analytics-event="apk"]').click();
  await expectQuiet(page, captured, 4);
});

test('unavailable storage and a blocked tracker do not break content or navigation', async ({
  context,
  page,
}) => {
  const captured = await capture(context, { blocked: true });
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await context.addInitScript(() => {
    Storage.prototype.getItem = () => {
      throw new DOMException('Storage unavailable', 'SecurityError');
    };
    Storage.prototype.setItem = () => {
      throw new DOMException('Storage unavailable', 'SecurityError');
    };
  });
  await page.goto(harness.url);
  await accept(page);
  await page.locator('[data-analytics-event="apk"]').click();
  expect(new URL(page.url()).hash).toBe('#download-target');
  await expectQuiet(page, captured);
  expect(captured.scripts).toHaveLength(0);
  expect(errors).toHaveLength(0);
});

for (const failure of ['throws', 'ignores'] as const) {
  test(`failed refusal storage ${failure} cannot reactivate an earlier acceptance after reload`, async ({
    context,
    page,
  }) => {
    const captured = await capture(context);
    await page.goto(harness.url);
    await page.evaluate(({ key, value }) => localStorage.setItem(key, value), {
      key: consentKey,
      value: receipt('accept'),
    });
    await page.reload();
    await expect.poll(() => captured.requests.length).toBe(1);
    await page.evaluate(
      ({ key, failure }) => {
        const original = Storage.prototype.setItem;
        Storage.prototype.setItem = function (name: string, value: string) {
          if (name === key && JSON.parse(value).choice === 'reject') {
            if (failure === 'throws')
              throw new DOMException('Preference writes unavailable', 'SecurityError');
            return;
          }
          original.call(this, name, value);
        };
      },
      { key: consentKey, failure },
    );
    await withdraw(page);
    expect(await page.evaluate((key) => localStorage.getItem(key), consentKey)).toBeNull();
    await page.reload();
    await expectQuiet(page, captured, 1);
    expect(captured.scripts).toHaveLength(1);
    expect((await context.cookies()).filter((cookie) => cookie.name.startsWith('bm_pk_'))).toEqual(
      [],
    );
  });
}

test('blocked Matomo remains optional after consent', async ({ context, page }) => {
  const captured = await capture(context, { blocked: true });
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(harness.url);
  await accept(page);
  await page.locator('[data-analytics-event="apk"]').click();
  expect(new URL(page.url()).hash).toBe('#download-target');
  await expectQuiet(page, captured);
  expect(captured.scripts).toHaveLength(1);
  expect(errors).toHaveLength(0);
});

for (const path of [
  '/?token=private-token&utm_campaign=private-campaign#private-fragment',
  '/private/private-path/?email=private-email#private-fragment',
]) {
  test(`real tracker excludes prohibited URL and referral data for ${path.startsWith('/private') ? '404' : 'known'} pages`, async ({
    context,
    page,
  }) => {
    const captured = await capture(context);
    await seed(context, receipt('accept'));
    await page.goto(harness.url + path, {
      referer: 'https://referrer.example/private-referrer/?token=private-referral',
    });
    await expect.poll(() => captured.requests.length).toBe(1);
    const params = captured.requests[0]!.params;
    const url = new URL(params.get('url')!);
    expect(url.pathname).toBe(path.startsWith('/private') ? '/404/' : '/');
    expect(url.search).toBe('');
    expect(url.hash).toBe('');
    expect(params.get('urlref') ?? '').toMatch(/^(https:\/\/referrer\.example\/?|)$/);
    for (const key of ['_rcn', '_rck', '_ref', 'uid', 'token_auth', 'search', 'campaign'])
      expect(params.has(key)).toBe(false);
    expect([...params.values()].join('|')).not.toContain('private-');
    expect(captured.requests.every((request) => !request.headers.referer)).toBe(true);
    expect(captured.scripts.every((request) => !request.headers().referer)).toBe(true);
  });
}

test('an analytics-disabled build stays silent even with a stored acceptance', async ({
  context,
  page,
}) => {
  const captured = await capture(context);
  await seed(context, receipt('accept'));
  await page.goto(harness.url + '/disabled/');
  await page.locator('[data-analytics-event="apk"]').click();
  await expectQuiet(page, captured);
  expect(captured.scripts).toHaveLength(0);
  expect(
    (await context.cookies()).filter((cookie) => cookie.name.startsWith('bm_pk_')),
  ).toHaveLength(0);
});

test('only an approved campaign slug reaches the real tracker request', async ({
  context,
  page,
}) => {
  const captured = await capture(context);
  await seed(context, receipt('accept'));
  await page.goto(
    harness.url +
      '/?utm_campaign=app-release&utm_source=private-source&utm_term=private-term&token=private-token',
  );
  await expect.poll(() => captured.requests.length).toBe(1);
  const params = captured.requests[0]!.params;
  expect(params.get('_rcn')).toBe('app-release');
  expect(params.has('_rck')).toBe(false);
  expect(new URL(params.get('url')!).search).toBe('');
  expect([...params.values()].join('|')).not.toContain('private-');
});
