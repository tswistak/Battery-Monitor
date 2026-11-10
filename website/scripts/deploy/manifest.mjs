import { cp, lstat, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { parseArgs } from 'node:util';
import { createManifest } from './core.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const { values } = parseArgs({
  options: { 'previous-manifest': { type: 'string' } },
  strict: true,
});
const { provenance } = JSON.parse(await readFile(resolve(root, '.generated/content.json'), 'utf8'));
const previous = values['previous-manifest']
  ? JSON.parse(await readFile(values['previous-manifest'], 'utf8'))
  : null;
const manifest = await createManifest(resolve(root, 'dist'), provenance, {
  previous,
  secrets: [
    process.env.BUNNY_STORAGE_ACCESS_KEY,
    process.env.BUNNY_API_KEY,
    process.env.GITHUB_TOKEN,
    process.env.WEBSITE_DEPLOY_STATE_KEY,
  ],
});
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
