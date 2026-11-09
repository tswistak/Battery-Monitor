import assert from 'node:assert/strict';
import test from 'node:test';
import { authorizeDeployment } from './website-deploy-context.mjs';
import { classifyPaths } from './website-ci.mjs';

const repository = 'tswistak/Battery-Monitor',
  sha = 'a'.repeat(40);
const context = {
  eventName: 'workflow_run',
  repository,
  checkoutCommit: sha,
  event: { repository: { full_name: repository }, workflow_run: { id: 7 } },
};
const release = {
  id: 8,
  tag_name: '2.0.0',
  draft: false,
  prerelease: false,
  published_at: '2026-11-09T00:00:00Z',
};
const run = {
  id: 7,
  workflow_id: 3,
  name: 'Build APK Release',
  event: 'push',
  status: 'completed',
  conclusion: 'success',
  head_sha: sha,
  head_branch: '2.0.0',
  repository: { full_name: repository },
  head_repository: { full_name: repository },
};
const services = {
  read: async (url) =>
    url.endsWith('/actions/runs/7')
      ? run
      : url.endsWith('/actions/workflows/release-apk.yml')
        ? { id: 3, path: '.github/workflows/release-apk.yml' }
        : url.includes('/branches/')
          ? { commit: { sha } }
          : { default_branch: 'master' },
  releases: async () => [release],
  commitForTag: async () => sha,
  assets: async () => [
    {
      id: 3,
      name: 'battery-monitor-2.0.0.apk',
      state: 'uploaded',
      size: 10,
      updated_at: '2026-11-09T00:00:00Z',
      browser_download_url:
        'https://github.com/tswistak/Battery-Monitor/releases/download/2.0.0/battery-monitor-2.0.0.apk',
    },
  ],
};

test('website media routes independently while imported app metadata remains shared', () => {
  for (const path of ['app/build.gradle', 'app/build.gradle.kts'])
    assert.equal(classifyPaths([path]).website, true);
  const media = [
    'website/src/assets/app/current.png',
    'website/src/assets/app/notification.png',
    'website/src/assets/app/backups.png',
  ];
  assert.deepEqual(classifyPaths(media), { android: false, website: true });
  assert.deepEqual(classifyPaths([...media, 'app/src/main/res/values/strings.xml']), {
    android: true,
    website: true,
  });
  assert.deepEqual(
    classifyPaths(['fastlane/metadata/android/en-US/images/phoneScreenshots/1.png']),
    { android: true, website: true },
  );
});

test('only a successful trusted APK tag run with a published stable asset authorizes deployment', async () => {
  assert.equal((await authorizeDeployment(context, services)).deploy, true);
  for (const override of [
    { id: 99 },
    { conclusion: 'failure' },
    { event: 'pull_request' },
    { workflow_id: 99 },
    { head_repository: { full_name: 'someone/fork' } },
  ]) {
    assert.equal(
      (
        await authorizeDeployment(context, {
          ...services,
          read: async (url) =>
            url.endsWith('/actions/runs/7') ? { ...run, ...override } : services.read(url),
        })
      ).deploy,
      false,
    );
  }
  assert.equal(
    (
      await authorizeDeployment(context, {
        ...services,
        releases: async () => [{ ...release, prerelease: true }],
      })
    ).deploy,
    false,
  );
  assert.equal(
    (
      await authorizeDeployment(context, {
        ...services,
        releases: async () => [{ ...release, draft: true }],
      })
    ).deploy,
    false,
  );
});

test('manual dispatch cannot choose an arbitrary implementation branch; stale checkouts fail', async () => {
  const manual = {
    ...context,
    eventName: 'workflow_dispatch',
    event: { repository: { full_name: repository }, ref: 'refs/heads/feature/untrusted' },
  };
  await assert.rejects(authorizeDeployment(manual, services), /production branch/);
  await assert.rejects(
    authorizeDeployment(
      {
        ...manual,
        event: { ...manual.event, ref: 'refs/heads/master' },
        checkoutCommit: 'b'.repeat(40),
      },
      services,
    ),
    /no longer the current/,
  );
  assert.equal(
    (
      await authorizeDeployment(
        { ...manual, event: { ...manual.event, ref: 'refs/heads/master' } },
        services,
      )
    ).deploy,
    true,
  );
});

test('unrelated pushes skip publication; API and mismatched tag failures cannot authorize it', async () => {
  const push = {
    ...context,
    eventName: 'push',
    event: { repository: { full_name: repository }, ref: 'refs/heads/master' },
    paths: { website: false },
  };
  assert.equal((await authorizeDeployment(push, services)).deploy, false);
  await assert.rejects(
    authorizeDeployment(context, { ...services, commitForTag: async () => 'b'.repeat(40) }),
    /does not match/,
  );
  await assert.rejects(
    authorizeDeployment(context, {
      ...services,
      read: async () => {
        throw new Error('API unavailable');
      },
    }),
    /API unavailable/,
  );
});
