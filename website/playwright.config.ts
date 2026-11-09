import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests',
  testMatch: '**/*.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: { baseURL: 'http://127.0.0.1:4322', headless: true, trace: 'retain-on-failure' },
  webServer: {
    command: 'npm run preview -- --port 4322 --ignore-lock',
    url: 'http://127.0.0.1:4322/',
    reuseExistingServer: !process.env.CI,
    timeout: 30000,
  },
});
