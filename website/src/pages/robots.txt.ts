import { getSiteConfig } from '../lib/site-config';
import { content } from '../data/content';
export function GET() {
  const production = content.provenance.mode === 'production';
  return new Response(
    `User-agent: *\n${production ? 'Allow: /' : 'Disallow: /'}\nSitemap: ${new URL('/sitemap-index.xml', getSiteConfig().siteUrl).href}\n`,
    { headers: { 'Content-Type': 'text/plain; charset=utf-8' } },
  );
}
