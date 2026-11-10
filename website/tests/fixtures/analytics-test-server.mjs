import { createServer } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
const astroRequire = createRequire(require.resolve('astro/package.json'));
const { createServer: createViteServer } = await import(
  pathToFileURL(astroRequire.resolve('vite')).href
);
const projectRoot = fileURLToPath(new URL('../../', import.meta.url));

export async function startAnalyticsHarness() {
  let vite;
  const server = createServer((request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1');
    response.setHeader('Referrer-Policy', 'no-referrer');
    if (
      url.pathname.startsWith('/src/') ||
      url.pathname.startsWith('/@') ||
      url.pathname.startsWith('/node_modules/')
    ) {
      vite.middlewares(request, response);
      return;
    }
    const payload = {
      config: {
        enabled: url.pathname !== '/disabled/',
        matomoUrl: 'https://analytics.invalid/',
        siteId: '7',
        consentVersion: 'analytics-v1',
        consentDimensionId: url.pathname === '/no-dimension/' ? null : 4,
        consentDays: 180,
        cookieVisitorSeconds: 15_552_000,
        cookieSessionSeconds: 1800,
      },
      page: { id: url.pathname.startsWith('/private/') ? 'not-found' : 'home' },
      appVersion: '2.0.0',
    };
    const json = JSON.stringify(payload).replaceAll('<', '\\u003c');
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    response.end(`<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Analytics test harness</title></head><body>
      <main><h1>Local analytics test</h1>
        <a data-analytics-event="apk" data-analytics-placement="download" data-analytics-channel="github" href="#download-target">Direct APK</a>
        <a data-analytics-event="fdroid" data-analytics-placement="hero" data-analytics-channel="fdroid" href="#download-target">F-Droid</a>
        <a data-analytics-event="release" data-analytics-placement="download" data-analytics-channel="github" href="https://downloads.invalid/">GitHub release</a>
        <span id="download-target">Download target</span>
        <a data-privacy-settings href="/privacy/#preferences">Privacy settings</a>
      </main>
      <section data-privacy-banner hidden aria-label="Website analytics">
        <p>Optional website analytics.</p><button data-privacy-accept>Accept analytics</button><button data-privacy-reject>Reject analytics</button>
      </section>
      <dialog data-privacy-dialog aria-label="Privacy settings">
        <p data-privacy-status></p><button data-privacy-accept>Accept analytics</button><button data-privacy-reject>Reject analytics</button><button data-privacy-withdraw>Withdraw analytics consent</button><button data-privacy-close>Close</button>
      </dialog>
      <script id="analytics-config" type="application/json">${json}</script>
      <script type="module" src="/src/scripts/analytics.ts"></script>
    </body></html>`);
  });
  vite = await createViteServer({
    configFile: false,
    root: projectRoot,
    server: { middlewareMode: true, host: '127.0.0.1', hmr: { server } },
    appType: 'custom',
    logLevel: 'error',
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  return {
    url: `http://127.0.0.1:${server.address().port}`,
    close: async () => {
      await vite.close();
      if (server.listening)
        await new Promise((resolve, reject) =>
          server.close((error) => (error ? reject(error) : resolve())),
        );
    },
  };
}

export async function startAnalyticsReceiver() {
  const received = [];
  const completed = [];
  const timers = new Set();
  const server = createHttpsServer(
    {
      // Self-signed localhost-only test credentials, never used by the website or deployment.
      key: await readFile(new URL('./localhost-key.pem', import.meta.url)),
      cert: await readFile(new URL('./localhost-cert.pem', import.meta.url)),
    },
    (request, response) => {
      const url = new URL(request.url, 'http://127.0.0.1');
      if (url.pathname === '/matomo.php') {
        const params = new URLSearchParams(url.search);
        received.push(params);
        response.once('finish', () => completed.push(params));
        const timer = setTimeout(() => {
          timers.delete(timer);
          response.writeHead(204);
          response.end();
        }, 750);
        timers.add(timer);
        return;
      }
      response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      response.end(
        '<!doctype html><html lang="en"><title>Release destination</title><h1>Release destination</h1></html>',
      );
    },
  );
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  return {
    url: `https://127.0.0.1:${server.address().port}`,
    received,
    completed,
    close: async () => {
      for (const timer of timers) clearTimeout(timer);
      server.closeAllConnections();
      await new Promise((resolve, reject) =>
        server.close((error) => (error ? reject(error) : resolve())),
      );
    },
  };
}
