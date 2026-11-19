import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';
import { loadSiteConfig } from './scripts/config.mjs';

export default defineConfig({
  site: loadSiteConfig().siteUrl,
  output: 'static',
  trailingSlash: 'always',
  integrations: [sitemap({ filter: (url) => !url.endsWith('/404/') })],
  build: { format: 'directory', inlineStylesheets: 'never' },
  vite: { build: { assetsInlineLimit: 0 } },
  devToolbar: { enabled: false },
});
