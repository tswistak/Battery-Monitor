import { spawn } from 'node:child_process';
import { mkdir, readFile, readdir, stat } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { chromium } from '@playwright/test';

const require = createRequire(import.meta.url);
const output = path.resolve('lighthouse-results');
await mkdir(output, { recursive: true });
const base = process.env.WEBSITE_AUDIT_URL || 'http://127.0.0.1:4322/';
const url = new URL(base);
if (!['127.0.0.1', 'localhost'].includes(url.hostname))
  throw new Error('Audits use a local server, never production analytics.');

for (const route of ['/', '/download/']) {
  const name = route === '/' ? 'home' : 'download';
  const code = await new Promise((resolve, reject) => {
    const child = spawn(
      process.execPath,
      [
        require.resolve('lighthouse/cli/index.js'),
        new URL(route, url).href,
        '--quiet',
        '--chrome-flags=--headless --no-sandbox',
        '--output=html',
        '--output=json',
        `--output-path=${path.join(output, name)}`,
      ],
      { stdio: 'inherit', env: { ...process.env, CHROME_PATH: chromium.executablePath() } },
    );
    child.once('error', reject);
    child.once('exit', resolve);
  });
  if (code !== 0) throw new Error(`Lighthouse ${name} failed.`);
  const report = JSON.parse(await readFile(path.join(output, name + '.report.json'), 'utf8'));
  console.log(
    name,
    Object.fromEntries(
      Object.entries(report.categories).map(([id, value]) => [id, Math.round(value.score * 100)]),
    ),
  );
}

async function files(dir) {
  return (
    await Promise.all(
      (await readdir(dir, { withFileTypes: true })).map((entry) =>
        entry.isDirectory() ? files(path.join(dir, entry.name)) : path.join(dir, entry.name),
      ),
    )
  ).flat();
}
const sizes = { html: 0, css: 0, javascript: 0, images: 0, videos: 0, fonts: 0, other: 0 };
for (const file of await files('dist')) {
  const ext = path.extname(file);
  const key =
    ext === '.html'
      ? 'html'
      : ext === '.css'
        ? 'css'
        : ext === '.js'
          ? 'javascript'
          : /\.(png|webp|svg|avif)$/.test(ext)
            ? 'images'
            : /\.(mp4|webm)$/.test(ext)
              ? 'videos'
              : ext === '.woff2'
                ? 'fonts'
                : 'other';
  sizes[key] += (await stat(file)).size;
}
console.log('Whole build bytes (not single-page transfer):', sizes);
