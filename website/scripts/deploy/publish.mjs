import { spawn } from 'node:child_process';
import { appendFile, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { configFromEnv, planDeployment, publishSite } from './core.mjs';
import { artifactManifest } from './manifest.mjs';
import { skippedIndexNow } from './indexnow-delivery.mjs';
import {
  INDEXNOW_ORIGIN,
  planIndexNow,
  planLegacyIndexNowPreview,
  readIndexNowPages,
} from './indexnow-pages.mjs';

function warning(message) {
  const escaped = message.replaceAll('%', '%25').replaceAll('\r', '%0D').replaceAll('\n', '%0A');
  console.warn(`::warning::${escaped}`);
}

async function reportIndexNow(report, env, artifactPath) {
  report.warnings.forEach(warning);
  const counts = [
    `${report.updates} added or updated`,
    `${report.removals} removals or redirects selected`,
    `${report.deferred} deferred`,
    `${report.acceptedUrls.length} accepted (HTTP 200: ${report.accepted200}, HTTP 202 awaiting key verification: ${report.accepted202})`,
    report.pending === null ? 'pending count unavailable' : `${report.pending} pending`,
  ].join(', ');
  const summary = [
    `IndexNow ${report.mode} — ${counts}.`,
    report.baseline,
    ...new Set([...report.reasons, ...report.warnings]),
  ]
    .filter(Boolean)
    .join('\n\n');
  if (report.mode !== 'dry-run') console.log(summary);
  if (artifactPath) {
    try {
      await writeFile(artifactPath, JSON.stringify(report, null, 2) + '\n');
    } catch {
      warning('Could not write the IndexNow deployment artifact.');
    }
  }
  if (env.GITHUB_STEP_SUMMARY) {
    try {
      await appendFile(env.GITHUB_STEP_SUMMARY, `\n### IndexNow\n\n${summary}\n`);
    } catch {
      warning('Could not append the IndexNow result to GITHUB_STEP_SUMMARY.');
    }
  }
}

async function previewIndexNow(dist, manifest, previous, websiteUrl) {
  let skipReason;
  if (websiteUrl !== INDEXNOW_ORIGIN + '/')
    skipReason = 'The configured website origin is outside IndexNow production.';
  else if (['local', 'fixture'].includes(manifest.provenance.mode))
    skipReason =
      'Local and fixture builds mark their pages noindex. Run npm run build:production before previewing production notifications.';
  if (skipReason) return { ...skippedIndexNow(skipReason, null), mode: 'dry-run', planned: [] };
  const read = (path) => readFile(resolve(dist, path));
  const pages = await readIndexNowPages(manifest, read, previous?.indexNow?.pages);
  let planned;
  let baseline;
  if (previous && !previous.indexNow) {
    try {
      const oldPages = await readIndexNowPages(previous, read);
      planned = planIndexNow(pages, { pages: oldPages, pending: [] });
      baseline = 'Compared with the previous successful manifest and matching local files.';
    } catch {
      planned = planLegacyIndexNowPreview(pages, previous);
      baseline =
        'Compared available HTML hashes. The old sitemap or HTML differs from the local build. Prior indexing eligibility and removals require the authenticated deployment files.';
    }
  } else {
    planned = planIndexNow(pages, previous?.indexNow);
    baseline = previous
      ? 'Compared with the supplied successful deployment and pending notifications.'
      : 'Assumed an initial publication because no previous successful manifest was supplied.';
  }
  const removals = planned.pending.filter((entry) => entry.kind === 'removal').length;
  const report = skippedIndexNow(
    'Offline preview. All planned removals require a public check after publication.',
  );
  const retryAfter = Date.parse(previous?.indexNow?.retryAfterUntil);
  if (retryAfter > Date.now()) {
    report.retryAfterUntil = new Date(retryAfter).toISOString();
    report.reasons.push(
      `IndexNow requested waiting until ${report.retryAfterUntil} before submitting.`,
    );
  }
  return {
    ...report,
    mode: 'dry-run',
    baseline,
    updates: planned.pending.length - removals,
    deferred: removals,
    pending: planned.pending.length,
    planned: planned.pending,
  };
}

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
    await reportIndexNow(skippedIndexNow('Website publication is disabled.', null), env);
  } else {
    const path = resolve(root, '.artifacts/deployment-manifest.json');
    const manifest = artifactManifest(JSON.parse(await readFile(path, 'utf8')));
    const dist = resolve(root, 'dist');
    const reportPath = resolve(root, '.artifacts/indexnow-report.json');
    const previous = values['previous-manifest']
      ? artifactManifest(JSON.parse(await readFile(values['previous-manifest'], 'utf8')))
      : null;
    if (!values.publish) {
      const plan = planDeployment(manifest, { previous });
      const preview = {
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
        indexNow: await previewIndexNow(dist, manifest, previous, config.websiteUrl),
      };
      console.log(JSON.stringify(preview, null, 2));
      await reportIndexNow(preview.indexNow, env, reportPath);
      return preview;
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
        dist,
        manifest,
        config,
        http,
        indexNowAuthorized:
          env.GITHUB_ACTIONS === 'true' && env.WEBSITE_DEPLOY_AUTHORIZED === 'true',
        validatePolicy: checkPolicy,
      }).catch(async (error) => {
        await reportIndexNow(
          skippedIndexNow('Website publication failed before notification.', null),
          env,
          reportPath,
        );
        throw error;
      });
      await writeFile(path, JSON.stringify(artifactManifest(result.manifest), null, 2) + '\n');
      console.log(
        `Website published: ${result.uploads} verified files, ${result.deleted} obsolete owned files removed.`,
      );
      console.log('Successful deployment state was verified and persisted in Bunny Storage.');
      await reportIndexNow(result.indexNow, env, reportPath);
      return result;
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href)
  await runPublish();
