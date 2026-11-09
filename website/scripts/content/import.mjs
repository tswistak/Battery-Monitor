import { readFile, writeFile, mkdir, lstat, realpath, rename, rm } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildContent, fundingFrom } from './extract.mjs';
import {
  request,
  resolveLatestRelease,
  listReleaseAssets,
  selectReleaseAssets,
  stableReleases,
  REPOSITORY_URL,
  REPOSITORY,
} from './github.mjs';

export const SCHEMA_VERSION = 3;
export const WEBSITE_ROOT = fileURLToPath(new URL('../../', import.meta.url));
const SOURCE_ROOT = path.resolve(WEBSITE_ROOT, '..');
const WEBSITE_MEDIA_PATH = 'website/src/assets/app';
const imageNames = [
  'current',
  'history',
  'alarms',
  'widgets',
  'notification',
  'backups',
  'advanced',
];
const videoNames = ['notification', 'backups', 'advanced', 'widgets'];
const sourcePaths = [
  'README.md',
  'CHANGELOG.md',
  'fdroid-version.properties',
  'app/build.gradle',
  'app/build.gradle.kts',
  'app/src/main/res/values/strings.xml',
  'LICENSE',
  ...Array.from(
    { length: 6 },
    (_, i) => `fastlane/metadata/android/en-US/images/phoneScreenshots/${i + 1}.png`,
  ),
];
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const sha = (value) => typeof value === 'string' && /^[a-f0-9]{40}$/i.test(value);

export async function safeRead(root, relative, optional = false) {
  if (
    path.isAbsolute(relative) ||
    relative.split(/[\\/]/).includes('..') ||
    relative.includes('\\') ||
    relative.includes('\0')
  )
    throw new Error('Invalid source path');
  const destination = path.join(root, relative);
  try {
    if ((await lstat(destination)).isSymbolicLink())
      throw new Error(`Source symlinks are not permitted: ${relative}`);
    const resolved = await realpath(destination);
    if (!resolved.startsWith(`${await realpath(root)}${path.sep}`))
      throw new Error('Source path escapes its snapshot');
    const content = await readFile(destination);
    if (content.length > 20 * 1024 * 1024)
      throw new Error(`Source file exceeds size limit: ${relative}`);
    return content;
  } catch (error) {
    if (optional && error.code === 'ENOENT') return null;
    throw error;
  }
}

async function writeJson(destination, value) {
  await mkdir(path.dirname(destination), { recursive: true });
  const temporary = `${destination}.tmp`;
  await writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`);
  await rename(temporary, destination);
}

function dirty(root, paths) {
  return Boolean(
    execFileSync('git', ['status', '--porcelain', '--', ...paths], {
      cwd: root,
      encoding: 'utf8',
    }).trim(),
  );
}

function currentCommit(root) {
  const commit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
  if (!sha(commit)) throw new Error('Current website checkout is not a valid Git commit');
  return commit;
}

async function localSnapshot() {
  const files = new Map();
  for (const relative of sourcePaths) {
    const bytes = await safeRead(SOURCE_ROOT, relative, true);
    if (bytes) files.set(relative, bytes);
  }
  const commit = currentCommit(SOURCE_ROOT);
  return {
    files,
    release: null,
    releases: [],
    provenance: {
      mode: 'local',
      websiteCommit: commit,
      websiteDirty: dirty(SOURCE_ROOT, ['website', '.github/FUNDING.yml']),
      appDirty: dirty(SOURCE_ROOT, sourcePaths),
      appCommit: commit,
      releaseId: null,
      tag: null,
      publishedAt: null,
      releaseAssets: null,
      sourceUrl: `${REPOSITORY_URL}/tree/${commit}`,
      omitted: [],
    },
  };
}

async function productionSnapshot({ fetchImpl, token } = {}) {
  const options = { fetchImpl, token };
  const { release: metadata, appCommit, releases } = await resolveLatestRelease(options);
  const release = {
    ...metadata,
    ...selectReleaseAssets(await listReleaseAssets(metadata, options), metadata.tag_name),
  };
  const root = path.join(WEBSITE_ROOT, '.cache', 'content', String(SCHEMA_VERSION), appCommit);
  await mkdir(root, { recursive: true });
  const files = new Map();
  const omitted = [];
  let manifest = {};
  const existing = await safeRead(root, 'snapshot.json', true);
  if (existing) {
    const cached = JSON.parse(existing.toString('utf8'));
    if (
      cached.schemaVersion !== SCHEMA_VERSION ||
      cached.appCommit !== appCommit ||
      typeof cached.files !== 'object'
    )
      throw new Error('Invalid immutable content cache manifest');
    manifest = cached.files;
  }
  for (const relative of sourcePaths) {
    let bytes;
    if (Object.hasOwn(manifest, relative)) {
      if (manifest[relative] === null) {
        omitted.push(`${relative}: absent from selected application release`);
        continue;
      }
      bytes = await safeRead(root, relative);
      if (hash(bytes) !== manifest[relative])
        throw new Error(`Cached application source checksum mismatch: ${relative}`);
    } else {
      const source = `https://raw.githubusercontent.com/${REPOSITORY}/${appCommit}/${relative.split('/').map(encodeURIComponent).join('/')}`;
      bytes = await request(source, { ...options, optional: true, binary: true });
      manifest[relative] = bytes ? hash(bytes) : null;
      if (bytes) {
        await mkdir(path.dirname(path.join(root, relative)), { recursive: true });
        await writeFile(path.join(root, relative), bytes);
      }
    }
    if (bytes) files.set(relative, bytes);
    else omitted.push(`${relative}: absent from selected application release`);
  }
  await writeJson(path.join(root, 'snapshot.json'), {
    schemaVersion: SCHEMA_VERSION,
    appCommit,
    files: manifest,
  });
  return {
    files,
    release,
    releases: stableReleases(releases),
    provenance: {
      mode: 'production',
      websiteCommit: currentCommit(SOURCE_ROOT),
      websiteDirty: dirty(SOURCE_ROOT, ['website', '.github/FUNDING.yml']),
      appCommit,
      releaseId: release.id,
      tag: release.tag_name,
      publishedAt: release.published_at,
      releaseAssets: release.releaseAssets,
      sourceUrl: `${REPOSITORY_URL}/tree/${appCommit}`,
      omitted,
    },
  };
}

export async function fixtureSnapshot(directory) {
  if (!directory) throw new Error('Fixture mode requires an explicit --fixture directory');
  const root = path.resolve(WEBSITE_ROOT, directory);
  const manifest = JSON.parse((await safeRead(root, 'snapshot.json')).toString('utf8'));
  if (
    manifest.schemaVersion !== SCHEMA_VERSION ||
    !sha(manifest.appCommit) ||
    !manifest.files ||
    typeof manifest.files !== 'object'
  )
    throw new Error('Invalid pinned fixture manifest');
  const files = new Map();
  for (const [relative, digest] of Object.entries(manifest.files)) {
    if (
      (!sourcePaths.includes(relative) && relative !== '.github/FUNDING.yml') ||
      !/^[a-f0-9]{64}$/.test(digest)
    )
      throw new Error('Fixture contains an unapproved file or checksum');
    const bytes = await safeRead(path.join(root, 'files'), relative);
    if (hash(bytes) !== digest) throw new Error(`Fixture checksum mismatch: ${relative}`);
    if (relative !== '.github/FUNDING.yml') files.set(relative, bytes);
  }
  const stable = stableReleases(manifest.releases ?? []);
  const metadata = stable.find((release) => release.id === manifest.releaseId);
  if (!metadata) throw new Error('Fixture does not identify a published stable release');
  const release = { ...metadata, ...selectReleaseAssets(metadata.assets ?? [], metadata.tag_name) };
  return {
    files,
    release,
    releases: stable,
    provenance: {
      mode: 'fixture',
      websiteCommit: currentCommit(SOURCE_ROOT),
      websiteDirty: dirty(SOURCE_ROOT, ['website', '.github/FUNDING.yml']),
      appCommit: manifest.appCommit,
      releaseId: release.id,
      tag: release.tag_name,
      publishedAt: release.published_at,
      releaseAssets: release.releaseAssets,
      sourceUrl: `${REPOSITORY_URL}/tree/${manifest.appCommit}`,
      omitted: [],
    },
  };
}

export function selectMediaInputs(files, modern, omitted = []) {
  const positions = modern
    ? { current: 1, history: 2, alarms: 3, widgets: 4, notification: 5 }
    : { current: 1, history: 5, alarms: 6, widgets: 3, notification: 2 };
  return Object.entries(positions).flatMap(([name, position]) => {
    const source = `fastlane/metadata/android/en-US/images/phoneScreenshots/${position}.png`;
    const bytes = files.get(source);
    if (!bytes) {
      omitted.push(`media/${name}: screenshot unavailable in selected application revision`);
      return [];
    }
    return [{ name, source, bytes }];
  });
}

export async function loadWebsiteMedia(
  { mode, version, directory = path.join(WEBSITE_ROOT, 'src/assets/app') },
  omitted = [],
) {
  const result = { images: [], videos: [], sources: [] };
  if (mode !== 'local') {
    const release = /^(\d{1,6})(?:\.\d{1,6}){1,3}(?:\+[a-z0-9.-]{1,100})?$/i.exec(version);
    if (!release) throw new Error('Website media compatibility requires a numeric app version');
    if (Number(release[1]) < 2) {
      omitted.push(`website media: requires app 2.0 or newer; selected release is ${version}`);
      return result;
    }
  }
  for (const [extension, names] of [
    ['png', imageNames],
    ['mp4', videoNames],
  ]) {
    for (const name of names) {
      const filename = `${name}.${extension}`;
      const data = await safeRead(directory, filename, true);
      if (!data) continue;
      const video = extension === 'mp4';
      if (
        video
          ? data.length < 12 || data.subarray(4, 8).toString('ascii') !== 'ftyp'
          : !data.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
      )
        throw new Error(`Website media has an invalid file signature: ${filename}`);
      const source = `${WEBSITE_MEDIA_PATH}/${filename}`;
      result[video ? 'videos' : 'images'].push({ name, source, bytes: data });
      result.sources.push({ path: source, sha256: hash(data) });
    }
  }
  return result;
}

export function applyWebsiteFunding(content, bytes) {
  content.funding = fundingFrom(bytes?.toString('utf8') ?? '');
  content.provenance.websiteSources = bytes
    ? [
        {
          path: '.github/FUNDING.yml',
          sha256: hash(bytes),
          note: 'Operational funding configuration from the website checkout.',
        },
      ]
    : [];
  return content;
}

export async function importContent({
  mode = 'local',
  fixture,
  fetchImpl,
  token = process.env.GITHUB_TOKEN,
  outputDirectory = path.join(WEBSITE_ROOT, '.generated'),
  mediaDirectory = path.join(WEBSITE_ROOT, 'src/assets/app'),
} = {}) {
  if (!['local', 'production', 'fixture'].includes(mode))
    throw new Error(`Unknown content mode: ${mode}`);
  const snapshot =
    mode === 'local'
      ? await localSnapshot()
      : mode === 'production'
        ? await productionSnapshot({ fetchImpl, token })
        : await fixtureSnapshot(fixture);
  const provenance = {
    ...snapshot.provenance,
    schemaVersion: SCHEMA_VERSION,
    importedAt: new Date().toISOString(),
    sources: [...snapshot.files].map(([source, bytes]) => ({ path: source, sha256: hash(bytes) })),
  };
  const content = buildContent({ ...snapshot, provenance });
  applyWebsiteFunding(content, await safeRead(SOURCE_ROOT, '.github/FUNDING.yml', true));
  const owned = await loadWebsiteMedia(
    { mode, version: content.release.version, directory: mediaDirectory },
    content.omitted,
  );
  content.provenance.websiteSources.push(...owned.sources);
  const images = new Map(
    selectMediaInputs(snapshot.files, content.capabilities.historyCharts).map((media) => [
      media.name,
      media,
    ]),
  );
  for (const media of owned.images) images.set(media.name, media);
  const mediaInputs = [...images.values()];
  const videoInputs = owned.videos;
  for (const name of imageNames)
    if (!images.has(name))
      content.omitted.push(`media/${name}: screenshot unavailable for this build`);
  for (const { source, bytes } of mediaInputs) {
    if (!bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])))
      throw new Error(`Application screenshot is not a PNG: ${source}`);
  }
  const generated = path.resolve(outputDirectory);
  const assets = path.join(generated, 'assets');
  await rm(assets, { recursive: true, force: true });
  await mkdir(assets, { recursive: true });
  for (const { name, bytes } of mediaInputs) {
    const destination = path.join(assets, `${name}.${hash(bytes).slice(0, 12)}.png`);
    await writeFile(destination, bytes);
    content.media[name] = destination;
  }
  for (const { name, bytes } of videoInputs) {
    const destination = path.join(assets, `${name}.${hash(bytes).slice(0, 12)}.mp4`);
    await writeFile(destination, bytes);
    content.videos[name] = destination;
  }
  await writeJson(path.join(generated, 'content.json'), content);
  return content;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const option = (name) => {
    const index = args.indexOf(name);
    return index < 0 ? undefined : args[index + 1];
  };
  try {
    const content = await importContent({
      mode: option('--mode') ?? process.env.WEBSITE_CONTENT_MODE ?? 'local',
      fixture: option('--fixture') ?? process.env.WEBSITE_CONTENT_FIXTURE,
    });
    console.log(
      `Imported ${content.provenance.mode} app content ${content.release.version} at ${content.provenance.appCommit}; ${content.omitted.length} optional omissions.`,
    );
  } catch (error) {
    console.error(`Website content import failed: ${error.message}`);
    process.exitCode = 1;
  }
}
