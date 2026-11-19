import { content } from '../../data/content';

export function GET() {
  const { provenance: source } = content;
  const repository = 'https://github.com/tswistak/Battery-Monitor';
  const website = source.websiteDirty
    ? repository
    : `${repository}/tree/${source.websiteCommit}/website`;
  return new Response(
    `Battery Monitor source code\n\nWebsite: ${website}\nApplication: ${content.app.sourceUrl}\n\nOriginal website code and text: GPL-3.0-or-later.\nThird-party materials retain their applicable notices.\n`,
    { headers: { 'Content-Type': 'text/plain; charset=utf-8' } },
  );
}
