import { spawn } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { configFromEnv, planDeployment, publishSite } from './core.mjs';

export async function runPublish({
  root = fileURLToPath(new URL('../../', import.meta.url)),
  args = process.argv.slice(2),
  env = process.env,
  http = {},
  validatePolicy,
} = {}) {
  const { values } = parseArgs({
    args,
    options: {
      publish: { type: 'boolean' },
      'dry-run': { type: 'boolean' },
      'previous-manifest': { type: 'string' },
    },
    strict: true,
  });
  if (values.publish && values['dry-run']) throw new Error('Choose --publish or --dry-run');
  if (values.publish && values['previous-manifest'])
    throw new Error(
      '--previous-manifest is for dry-run planning; publication reads authenticated durable state',
    );
  const config = configFromEnv(env, { publishing: values.publish === true });
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
      const checkPolicy =
        validatePolicy ||
        (() =>
          new Promise((resolvePromise, reject) => {
            const child = spawn('npm', ['run', 'validate:publish'], {
              cwd: root,
              stdio: 'inherit',
              env,
            });
            child.once('error', reject);
            child.once('exit', (code) =>
              code === 0
                ? resolvePromise()
                : reject(new Error('Production privacy/configuration validation failed')),
            );
          }));
      const result = await publishSite({
        dist: resolve(root, 'dist'),
        manifest,
        config,
        http,
        validatePolicy: checkPolicy,
      });
      await writeFile(path, JSON.stringify(result.manifest, null, 2) + '\n');
      console.log(
        `Website published: ${result.uploads} verified files, ${result.deleted} obsolete owned files removed.`,
      );
      console.log('Successful deployment state was verified and persisted in Bunny Storage.');
      return result;
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href)
  await runPublish();
