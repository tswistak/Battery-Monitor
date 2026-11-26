import { cp, lstat, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { parseArgs } from 'node:util';
import { createManifest, isIndexNowVerificationFile, sha256, validateManifest } from './core.mjs';
import { isIndexNowKey } from './indexnow-delivery.mjs';

export function artifactManifest(manifest) {
  validateManifest(manifest);
  const records = [...manifest.files, ...manifest.owned];
  const privatePaths = new Set(records.filter(isIndexNowVerificationFile).map((file) => file.path));
  const knownPaths = new Set(records.map((file) => file.path));
  const files = manifest.files.filter((file) => !privatePaths.has(file.path));
  const buildId = sha256(JSON.stringify({ provenance: manifest.provenance, files }));
  const recentBuilds = [
    { buildId, paths: files.map((file) => file.path) },
    ...manifest.recentBuilds.slice(1).map((build) => ({
      ...build,
      paths: build.paths.filter(
        (path) =>
          !privatePaths.has(path) &&
          (knownPaths.has(path) || !path.endsWith('.txt') || !isIndexNowKey(path.slice(0, -4))),
      ),
    })),
  ];
  return validateManifest({
    ...manifest,
    files,
    buildId,
    recentBuilds,
    owned: manifest.owned.filter((file) => !privatePaths.has(file.path)),
  });
}

export async function packageSite({
  root = fileURLToPath(new URL('../../', import.meta.url)),
  previous = null,
  env = process.env,
} = {}) {
  const { provenance } = JSON.parse(
    await readFile(resolve(root, '.generated/content.json'), 'utf8'),
  );
  const manifest = await createManifest(resolve(root, 'dist'), provenance, {
    previous: previous && artifactManifest(previous),
    secrets: [
      env.BUNNY_STORAGE_ACCESS_KEY,
      env.BUNNY_API_KEY,
      env.GITHUB_TOKEN,
      env.WEBSITE_DEPLOY_STATE_KEY,
    ],
  });
  if (manifest.files.some(isIndexNowVerificationFile))
    throw new Error(
      'A generated IndexNow verification file remains in the build. Rebuild before packaging.',
    );
  const artifacts = resolve(root, '.artifacts');
  for (const path of [artifacts, resolve(artifacts, 'site')]) {
    const stat = await lstat(path).catch((error) => {
      if (error.code === 'ENOENT') return null;
      throw error;
    });
    if (stat?.isSymbolicLink()) throw new Error('Deployment artifact paths must not be symlinks');
  }
  await mkdir(artifacts, { recursive: true });
  await rm(resolve(artifacts, 'site'), { recursive: true, force: true });
  await cp(resolve(root, 'dist'), resolve(artifacts, 'site'), { recursive: true });
  await writeFile(
    resolve(artifacts, 'deployment-manifest.json'),
    JSON.stringify(manifest, null, 2) + '\n',
  );
  console.log(
    `Prepared dist-only deployment artifact: ${manifest.files.length} files, build ${manifest.buildId.slice(0, 12)}.`,
  );
  return manifest;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const { values } = parseArgs({
    options: { 'previous-manifest': { type: 'string' } },
    strict: true,
  });
  const previous = values['previous-manifest']
    ? JSON.parse(await readFile(values['previous-manifest'], 'utf8'))
    : null;
  await packageSite({ previous });
}
