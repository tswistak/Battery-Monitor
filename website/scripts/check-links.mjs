import { readFile, readdir, stat } from 'node:fs/promises';
import path from 'node:path';
const root = path.resolve('dist');
async function files(dir) {
  return (
    await Promise.all(
      (await readdir(dir, { withFileTypes: true })).map((e) =>
        e.isDirectory() ? files(path.join(dir, e.name)) : path.join(dir, e.name),
      ),
    )
  ).flat();
}
const all = await files(root);
const html = all.filter((file) => file.endsWith('.html'));
const errors = [];
for (const file of html) {
  const source = await readFile(file, 'utf8');
  const pagePath =
    '/' +
    path
      .relative(root, file)
      .replace(/index\.html$/, '')
      .replaceAll(path.sep, '/');
  for (const match of source.matchAll(/(?:href|src|poster)="([^"]+)"/g)) {
    const raw = match[1].replaceAll('&amp;', '&');
    if (/^(?:https?:|mailto:|tel:|data:|javascript:)/i.test(raw)) continue;
    const url = new URL(raw, 'https://local.invalid' + pagePath);
    let target = path.resolve(root, '.' + decodeURIComponent(url.pathname));
    if (!target.startsWith(root + path.sep) && target !== root) {
      errors.push(`${pagePath}: unsafe ${raw}`);
      continue;
    }
    if (url.pathname.endsWith('/')) target = path.join(target, 'index.html');
    try {
      if (!(await stat(target)).isFile()) throw new Error('not a file');
      if (url.hash && target.endsWith('.html')) {
        const body = target === file ? source : await readFile(target, 'utf8');
        const id = decodeURIComponent(url.hash.slice(1));
        if (![...body.matchAll(/\bid="([^"]+)"/g)].some((m) => m[1] === id))
          errors.push(`${pagePath}: missing fragment ${raw}`);
      }
    } catch {
      errors.push(`${pagePath}: missing ${raw}`);
    }
  }
}
if (errors.length)
  throw new Error('Internal-link validation failed:\n' + [...new Set(errors)].join('\n'));
console.log(`Internal links and assets: ${html.length} HTML files checked.`);
