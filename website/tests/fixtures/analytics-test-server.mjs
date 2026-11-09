import { createServer } from 'node:http';
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
