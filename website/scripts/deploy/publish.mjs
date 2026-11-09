import { spawn } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { configFromEnv, planDeployment, publishSite } from './core.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const { values } = parseArgs({
  options: {
    publish: { type: 'boolean' },
    'dry-run': { type: 'boolean' },
    'previous-manifest': { type: 'string' },
  },
  strict: true,
});
if (values.publish && values['dry-run']) throw new Error('Choose --publish or --dry-run');
const config = configFromEnv(process.env, { publishing: values.publish === true });
if (values.publish && !config.enabled) {
  console.log(
    'Website publication skipped: WEBSITE_DEPLOY_ENABLED is false. Build validation remains enabled.',
  );
} else {
  const path = resolve(root, '.artifacts/deployment-manifest.json');
  const manifest = JSON.parse(await readFile(path, 'utf8'));
  const previous = values['previous-manifest']
    ? JSON.parse(await readFile(values['previous-manifest'], 'utf8'))
    : null;
  if (!values.publish) {
    const plan = planDeployment(manifest, { previous });
    console.log(
      JSON.stringify(
        {
          mode: 'dry-run',
          buildId: manifest.buildId,
          files: plan.uploads.map((file) => ({
            path: file.path,
            phase: file.phase,
            bytes: file.size,
          })),
          cleanup: plan.cleanup,
          cleanupNote: previous
            ? 'Only obsolete manifest-owned files outside the retained two builds and seven-day window.'
            : 'No successful prior manifest supplied; all previous Storage objects are preserved.',
        },
        null,
        2,
      ),
    );
  } else {
    const validatePolicy = () =>
      new Promise((resolvePromise, reject) => {
        const child = spawn('npm', ['run', 'validate:publish'], {
          cwd: root,
          stdio: 'inherit',
          env: process.env,
        });
        child.once('error', reject);
        child.once('exit', (code) =>
          code === 0
            ? resolvePromise()
            : reject(new Error('Production privacy/configuration validation failed')),
        );
      });
    const result = await publishSite({
      dist: resolve(root, 'dist'),
      manifest,
      previous,
      config,
      validatePolicy,
    });
    await writeFile(path, JSON.stringify(result.manifest, null, 2) + '\n');
    console.log(
      `Website published: ${result.uploads} verified files, ${result.deleted} obsolete owned files removed.`,
    );
    if (!previous)
      console.log(
        'Old Storage objects were retained because no successful prior manifest was supplied.',
      );
  }
}
