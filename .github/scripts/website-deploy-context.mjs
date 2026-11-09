import { appendFileSync, readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { detectChanges } from './website-ci.mjs';
import {
  API_URL,
  REPOSITORY,
  request,
  listReleases,
  stableReleases,
  resolveTagCommit,
  listReleaseAssets,
  selectReleaseAssets,
} from '../../website/scripts/content/github.mjs';

const validSha = (value) => /^[a-f0-9]{40}$/.test(value ?? '');

export async function authorizeDeployment(
  { eventName, event, repository, configuredBranch, checkoutCommit, paths },
  services = {},
) {
  if (repository !== REPOSITORY || event.repository?.full_name !== REPOSITORY)
    throw new Error('Website deployment is restricted to the owning repository');
  const read = services.read ?? ((url) => request(url, { token: process.env.GITHUB_TOKEN }));
  const getReleases =
    services.releases ?? (() => listReleases({ token: process.env.GITHUB_TOKEN }));
  const commitForTag =
    services.commitForTag ?? ((tag) => resolveTagCommit(tag, { token: process.env.GITHUB_TOKEN }));
  const getAssets =
    services.assets ??
    ((release) => listReleaseAssets(release, { token: process.env.GITHUB_TOKEN }));
  const repo = await read(API_URL);
  const branch = configuredBranch || repo.default_branch;
  if (
    typeof branch !== 'string' ||
    !/^[A-Za-z0-9][A-Za-z0-9._/-]*$/.test(branch) ||
    branch.includes('..') ||
    branch.endsWith('/')
  )
    throw new Error('Invalid production branch');
  const skip = (reason) => ({ deploy: false, branch, reason });
  if (eventName === 'workflow_dispatch') {
    if (event.ref !== `refs/heads/${branch}`)
      throw new Error('Manual website deployment must run from the production branch');
  } else if (eventName === 'push') {
    if (event.ref !== `refs/heads/${branch}`) return skip('Push is not on the production branch');
    const flags = paths ? { website: paths.website } : detectChanges(eventName, event);
    if (!flags.website) return skip('Push contains no website or shared content changes');
  } else if (eventName === 'workflow_run') {
    const trigger = event.workflow_run;
    if (!Number.isSafeInteger(trigger?.id) || trigger.id < 1)
      throw new Error('Missing upstream workflow-run ID');
    const [run, workflow] = await Promise.all([
      read(`${API_URL}/actions/runs/${trigger.id}`),
      read(`${API_URL}/actions/workflows/release-apk.yml`),
    ]);
    if (
      run.id !== trigger.id ||
      !Number.isSafeInteger(workflow.id) ||
      workflow.id < 1 ||
      run.workflow_id !== workflow.id ||
      workflow.path !== '.github/workflows/release-apk.yml' ||
      run.name !== 'Build APK Release' ||
      run.event !== 'push' ||
      run.status !== 'completed' ||
      run.conclusion !== 'success' ||
      run.repository?.full_name !== REPOSITORY ||
      run.head_repository?.full_name !== REPOSITORY ||
      !validSha(run.head_sha)
    )
      return skip('Upstream run is not a successful trusted APK tag publication');
    const release = stableReleases(await getReleases()).find(
      (item) => item.tag_name === run.head_branch,
    );
    if (!release) return skip('Upstream tag has no published stable release');
    if ((await commitForTag(release.tag_name)) !== run.head_sha)
      throw new Error('Published release tag does not match the trusted APK workflow revision');
    selectReleaseAssets(await getAssets(release), release.tag_name);
  } else throw new Error('Unsupported website deployment trigger');
  const current = (await read(`${API_URL}/branches/${encodeURIComponent(branch)}`))?.commit?.sha;
  if (!validSha(current) || current !== checkoutCommit)
    throw new Error(
      'Website checkout is no longer the current production revision; retry the workflow',
    );
  return {
    deploy: true,
    branch,
    websiteCommit: current,
    reason:
      'Authorized current production code; the build independently resolves current stable app content',
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const event = {
    ...JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8')),
    ref: process.env.GITHUB_REF,
  };
  const context = await authorizeDeployment({
    eventName: process.env.GITHUB_EVENT_NAME,
    event,
    repository: process.env.GITHUB_REPOSITORY,
    configuredBranch: process.env.WEBSITE_PRODUCTION_BRANCH,
    checkoutCommit: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
  });
  if (process.env.GITHUB_OUTPUT)
    appendFileSync(
      process.env.GITHUB_OUTPUT,
      `deploy=${context.deploy}\nbranch=${context.branch}\n`,
    );
  if (process.env.GITHUB_STEP_SUMMARY)
    appendFileSync(
      process.env.GITHUB_STEP_SUMMARY,
      `### Website deployment context\n${context.reason}.\n`,
    );
  console.log(context.reason);
}
