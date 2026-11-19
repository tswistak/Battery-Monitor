import { content } from '../data/content';
import { guideIntro } from '../content/en/guides';
import { pageMeta } from '../content/en/ui';
import { getSiteConfig } from '../lib/site-config';

export function GET() {
  const { siteUrl } = getSiteConfig();
  const link = (title: string, path: string, description: string) =>
    `- [${title}](${new URL(path, siteUrl).href}): ${description}`;
  const version = `App version: ${content.release.version}.`;
  const text = [
    '# Battery Monitor',
    '',
    `> ${pageMeta.home.description}`,
    '',
    `Requires Android ${content.requirements.androidMin}.0 or later. ${version}`,
    'Battery readings stay on the device unless the user chooses to export or share them. Available measurements depend on the device. Battery estimates and alarms do not control charging hardware.',
    '',
    '## Start here',
    link('Overview', '/', pageMeta.home.description),
    link(pageMeta.download.title, '/download/', pageMeta.download.description),
    link(pageMeta.help.title, '/help/', pageMeta.help.description),
    '',
    '## App guides',
    ...content.articles.map((article) =>
      link(article.title, `/help/${article.id}/`, guideIntro[article.id]),
    ),
    '',
    '## Optional',
    link(pageMeta.changelog.title, '/changelog/', pageMeta.changelog.description),
    link(pageMeta.privacy.title, '/privacy/', pageMeta.privacy.description),
    link(pageMeta.about.title, '/about/', pageMeta.about.description),
    link('Source code and licenses', '/about/#licenses', 'Source availability and attribution.'),
    '',
  ].join('\n');
  return new Response(text, { headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
}
