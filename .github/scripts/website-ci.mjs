import { appendFileSync, readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const websiteOnly = (path) =>
  path.startsWith('website/') ||
  /^\.github\/workflows\/website-(checks|deploy)\.yml$/.test(path) ||
  /^\.github\/scripts\/website-(ci|deploy-context)(\.test)?\.mjs$/.test(path);
const sharedWebsiteSource = (path) =>
  /^(README\.md|CHANGELOG\.md|fdroid-version\.properties|LICENSE|COPYING|CONTRIBUTING\.MD|\.gitignore)$/.test(
    path,
  ) ||
  path.startsWith('LICENSES/') ||
  path === '.github/FUNDING.yml' ||
  path === '.github/workflows/release-apk.yml' ||
  /^app\/build\.gradle(?:\.kts)?$/.test(path) ||
  path === 'app/src/main/AndroidManifest.xml' ||
  path.startsWith('fastlane/metadata/android/en-US/') ||
  /^app\/src\/main\/res\/values\/(strings|changelog)\.xml$/.test(path);

export function classifyPaths(paths) {
  return {
    android: paths.some((path) => !websiteOnly(path)),
    website: paths.some((path) => websiteOnly(path) || sharedWebsiteSource(path)),
  };
}

export function changedPaths(
  eventName,
  event,
  runGit = (args) => execFileSync('git', args, { encoding: 'utf8' }),
) {
  if (eventName === 'workflow_dispatch') return null;
  const base = eventName === 'pull_request' ? event.pull_request?.base?.sha : event.before;
  const head = eventName === 'pull_request' ? event.pull_request?.head?.sha : event.after;
  if (!/^[a-f0-9]{40}$/.test(head ?? ''))
    throw new Error('Missing valid changed-file head revision');
  const args =
    /^[a-f0-9]{40}$/.test(base ?? '') && !/^0+$/.test(base)
      ? [
          'diff',
          '--name-only',
          '-z',
          eventName === 'pull_request' ? `${base}...${head}` : `${base}..${head}`,
        ]
      : ['diff-tree', '--root', '--no-commit-id', '--name-only', '-r', '-z', head];
  return runGit(args).split('\0').filter(Boolean);
}

export function detectChanges(eventName, event, runGit) {
  if (eventName === 'workflow_dispatch') return { android: true, website: true };
  try {
    return classifyPaths(changedPaths(eventName, event, runGit));
  } catch {
    // An incomplete history must run checks, never silently skip them.
    console.warn(
      '::warning::Changed files could not be resolved; running Android and website checks.',
    );
    return { android: true, website: true };
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  const flags = detectChanges(process.env.GITHUB_EVENT_NAME, event);
  const output = `android=${flags.android}\nwebsite=${flags.website}\n`;
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, output);
  process.stdout.write(output);
}
