import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
const routes = [
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
test('static routes, download navigation and essential images work without JavaScript', async ({
  browser,
}) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();
  const forbidden: string[] = [];
  page.on('request', (request) => {
    if (/radioactive-toy|api\.github\.com/.test(request.url())) forbidden.push(request.url());
  });
  for (const route of routes) {
    const response = await page.goto(route);
    expect(response?.status()).toBe(200);
    const canonical = await page.locator('link[rel="canonical"]').getAttribute('href');
    expect(new URL(canonical!).pathname).toBe(route);
    const broken = await page
      .locator('img')
      .evaluateAll((images) =>
        images
          .filter(
            (image) =>
              !(image as HTMLImageElement).complete || !(image as HTMLImageElement).naturalWidth,
          )
          .map((image) => (image as HTMLImageElement).src),
      );
    expect(broken).toEqual([]);
    if (route === '/privacy/')
      await expect(page.locator('button[data-privacy-settings]')).toBeHidden();
  }
  await page.goto('/');
  expect(
    await page
      .locator('video')
      .evaluateAll((videos) => videos.every((video) => (video as HTMLVideoElement).controls)),
  ).toBe(true);
  await expect(page.locator('[data-clip-toggle]').first()).toBeHidden();
  await page.locator('#story-widgets a').click();
  await expect(page).toHaveURL(/#story-media-widgets$/);
  await page.locator('header a[href="/download/"]').click();
  await expect(page).toHaveURL(/\/download\/$/);
  expect(forbidden).toEqual([]);
  await context.close();
});
test('analytics-disabled output works under the deployment CSP without analytics activity', async ({
  page,
  context,
}) => {
  const forbidden: string[] = [];
  const blocked: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && /Content Security Policy/i.test(message.text()))
      blocked.push(message.text());
  });
  await page.route('**/*', async (route) => {
    if (route.request().resourceType() !== 'document') return route.continue();
    const response = await route.fetch();
    await route.fulfill({
      response,
      headers: {
        ...response.headers(),
        'Content-Security-Policy': "default-src 'self'; object-src 'none'; base-uri 'self'",
      },
    });
  });
  page.on('request', (r) => {
    if (/matomo\.(?:js|php)|radioactive-toy/.test(r.url())) forbidden.push(r.url());
  });
  await page.goto('/');
  await page.locator('[data-privacy-settings]').click();
  await expect(page.locator('[data-privacy-dialog]')).toBeVisible();
  await page.waitForTimeout(100);
  expect(forbidden).toEqual([]);
  expect(blocked).toEqual([]);
  expect((await context.cookies()).filter((c) => c.name.startsWith('bm_pk_'))).toEqual([]);
});
test('all routes have no automated accessibility violations', async ({ page }) => {
  for (const route of [...routes, '/404.html']) {
    await page.goto(route);
    const result = await new AxeBuilder({ page }).analyze();
    expect(result.violations, route).toEqual([]);
    if (route === '/') {
      for (const chapter of ['widgets', 'backups', 'advanced']) {
        await page.locator('#story-' + chapter + ' a').click();
        await expect(page.locator('#story-media-' + chapter)).toHaveCSS('opacity', '1');
        const state = await new AxeBuilder({ page }).analyze();
        expect(state.violations, chapter).toEqual([]);
      }
    }
  }
});
test('skip link moves keyboard focus to the main content', async ({ page }) => {
  await page.goto('/');
  await page.keyboard.press('Tab');
  await expect(page.locator('.skip-link')).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.locator('#main-content')).toBeFocused();
  const settings = page.locator('footer [data-privacy-settings]');
  await settings.focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('[data-privacy-dialog]')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.locator('[data-privacy-dialog]')).not.toBeVisible();
  await expect(settings).toBeFocused();
});
test('pages reflow at a 320 CSS-pixel viewport without page-level horizontal scrolling', async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 800 });
  for (const route of [...routes, '/404.html']) {
    await page.goto(route);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      route,
    ).toBe(true);
  }
});
test('nonexistent routes preserve an actual 404 response', async ({ request }) => {
  const response = await request.get('/not-a-real-route/');
  expect(response.status()).toBe(404);
});

test('feature recordings play only in view and respect reduced motion', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/');
  const clips = page.locator('[data-feature-video]');
  const notification = page.locator('[data-feature-video="notification"]');
  const widgets = page.locator('[data-feature-video="widgets"]');
  const backups = page.locator('[data-feature-video="backups"]');
  const notificationToggle = page.locator('#story-media-notifications [data-clip-toggle]');
  await expect(clips).toHaveCount(4);
  expect(
    await clips.evaluateAll((videos) => videos.every((v) => !(v as HTMLVideoElement).controls)),
  ).toBe(true);
  expect(
    await clips.evaluateAll((videos) => videos.every((v) => (v as HTMLVideoElement).paused)),
  ).toBe(true);
  await page.goto('/#feature-story');
  await expect
    .poll(() =>
      notification.evaluate((v) => {
        const video = v as HTMLVideoElement;
        return !video.paused && video.currentTime > 0.1 && video.readyState >= 2;
      }),
    )
    .toBe(true);
  expect(await notification.evaluate((v) => (v as HTMLVideoElement).loop)).toBe(true);
  await expect(notification).toHaveAccessibleDescription(/\S/);
  await expect(notificationToggle).toHaveAccessibleDescription(/\S/);
  expect(await backups.evaluate((v) => (v as HTMLVideoElement).loop)).toBe(true);
  await notification.evaluate((v) => {
    const video = v as HTMLVideoElement;
    video.currentTime = video.duration - 0.15;
  });
  await expect
    .poll(() =>
      notification.evaluate((v) => {
        const video = v as HTMLVideoElement;
        return !video.paused && video.currentTime < 1;
      }),
    )
    .toBe(true);
  await page.keyboard.press('End');
  await expect
    .poll(() => clips.evaluateAll((videos) => videos.every((v) => (v as HTMLVideoElement).paused)))
    .toBe(true);
  await page.locator('#story-notifications a').click();
  await expect.poll(() => notification.evaluate((v) => (v as HTMLVideoElement).paused)).toBe(false);
  await notificationToggle.click();
  await expect(notificationToggle).toHaveAccessibleName('Play recording');
  await notificationToggle.focus();
  await page.keyboard.press('Enter');
  await expect(notificationToggle).toHaveAccessibleName('Pause recording');
  await notificationToggle.click();
  await page.keyboard.press('End');
  await expect
    .poll(() =>
      page.evaluate(() => Math.abs(scrollY + innerHeight - document.documentElement.scrollHeight)),
    )
    .toBeLessThan(1);
  await page.locator('#story-notifications a').click();
  expect(await notification.evaluate((v) => (v as HTMLVideoElement).paused)).toBe(true);

  await page.locator('#story-widgets a').click();
  await expect(widgets).toHaveAccessibleDescription(/\S/);
  await expect
    .poll(() =>
      widgets.evaluate((v) => {
        const video = v as HTMLVideoElement;
        return video.loop && !video.paused && video.currentTime > 0.1 && video.readyState >= 2;
      }),
    )
    .toBe(true);
  await widgets.evaluate((v) => {
    const video = v as HTMLVideoElement;
    video.currentTime = video.duration - 0.15;
  });
  await expect
    .poll(() =>
      widgets.evaluate((v) => {
        const video = v as HTMLVideoElement;
        return !video.paused && video.currentTime < 1;
      }),
    )
    .toBe(true);

  const exportPosition = await page.locator('#feature-story').evaluate((element) => {
    const story = element as HTMLElement;
    const pin = story.querySelector('.story-pin') as HTMLElement;
    return story.offsetTop + (story.offsetHeight - pin.offsetHeight) * 0.625;
  });
  await page.evaluate((top) => window.scrollTo(0, top), exportPosition);
  await expect
    .poll(() =>
      backups.evaluate((v) => {
        const video = v as HTMLVideoElement;
        return !video.paused && video.currentTime > 0.1 && video.readyState >= 2;
      }),
    )
    .toBe(true);
  await expect(backups).toHaveAccessibleDescription(/\S/);
  await page.keyboard.press('End');
  await expect.poll(() => backups.evaluate((v) => (v as HTMLVideoElement).paused)).toBe(true);
  await page.evaluate((top) => window.scrollTo(0, top), exportPosition);
  await expect.poll(() => backups.evaluate((v) => (v as HTMLVideoElement).paused)).toBe(false);
  await backups.evaluate((v) => {
    const video = v as HTMLVideoElement;
    video.currentTime = video.duration - 0.15;
  });
  await expect
    .poll(() =>
      backups.evaluate((v) => {
        const video = v as HTMLVideoElement;
        return !video.paused && video.currentTime < 1;
      }),
    )
    .toBe(true);

  await page.locator('#story-advanced a').click();
  const advanced = page.locator('[data-feature-video="advanced"]');
  await expect(advanced).toHaveAccessibleDescription(/\S/);
  await expect
    .poll(() =>
      advanced.evaluate((v) => {
        const video = v as HTMLVideoElement;
        return video.loop && !video.paused && video.currentTime > 0.1 && video.readyState >= 2;
      }),
    )
    .toBe(true);
  await advanced.evaluate((v) => {
    const video = v as HTMLVideoElement;
    video.currentTime = video.duration - 0.15;
  });
  await expect
    .poll(() =>
      advanced.evaluate((v) => {
        const video = v as HTMLVideoElement;
        return !video.paused && video.currentTime < 1;
      }),
    )
    .toBe(true);

  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.reload();
  await notification.scrollIntoViewIfNeeded();
  await page.waitForTimeout(300);
  expect(
    await clips.evaluateAll((videos) =>
      videos.every(
        (v) => (v as HTMLVideoElement).paused && (v as HTMLVideoElement).currentTime === 0,
      ),
    ),
  ).toBe(true);
  await notificationToggle.click();
  await expect.poll(() => notification.evaluate((v) => (v as HTMLVideoElement).paused)).toBe(false);
});

test('feature navigation and scroll progress work with pointer and keyboard', async ({ page }) => {
  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 390, height: 844 },
  ]) {
    await page.setViewportSize(viewport);
    await page.goto('/#feature-story');
    const story = page.locator('#feature-story');
    const links = story.locator('[data-story-link]');
    for (const index of [2, 0, 3, 1]) {
      await story.locator('[data-story-step]').nth(index).click();
      await expect(story).toHaveAttribute('data-step', String(index));
      await expect(links.nth(index)).toHaveAttribute('aria-current', 'true');
      await expect(story.locator('[data-story-media]').nth(index)).toHaveAttribute(
        'aria-hidden',
        'false',
      );
    }
    await links.first().focus();
    await page.keyboard.press('Enter');
    await expect(story).toHaveAttribute('data-step', '0');
    const runway = await story.evaluate((element) => {
      const section = element as HTMLElement;
      const pin = section.querySelector('.story-pin') as HTMLElement;
      return {
        top: scrollY + section.getBoundingClientRect().top - parseFloat(getComputedStyle(pin).top),
        chapter:
          (section.offsetHeight - pin.offsetHeight) /
          section.querySelectorAll('[data-story-step]').length,
      };
    });
    for (const [chapter, progress] of [
      [1, 0.2],
      [1, 0.8],
      [2, 0.1],
      [1, 0.6],
      [3, 1],
    ] as const) {
      await page.evaluate(
        (top) => window.scrollTo({ top, behavior: 'instant' }),
        runway.top + runway.chapter * (chapter + progress),
      );
      await expect(story).toHaveAttribute('data-step', String(chapter));
      await expect
        .poll(() =>
          story.evaluate((element) =>
            Number(getComputedStyle(element).getPropertyValue('--story-progress')),
          ),
        )
        .toBeCloseTo(progress, 2);
    }
  }
});
