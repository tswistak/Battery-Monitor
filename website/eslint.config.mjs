import js from '@eslint/js';
import astro from 'eslint-plugin-astro';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default [
  {
    ignores: [
      'node_modules/**',
      'dist/**',
      '.astro/**',
      '.generated/**',
      '.cache/**',
      '.artifacts/**',
      'test-results/**',
      'playwright-report/**',
      'lighthouse-results/**',
      'public/**',
      'src/assets/**',
      'tests/fixtures/content/**',
      'tests/fixtures/matomo/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...astro.configs.recommended,
  {
    files: ['**/*.{js,mjs,cjs,ts,mts,cts}'],
    languageOptions: { globals: globals.node },
  },
  {
    files: ['src/scripts/**/*.{js,mjs,ts}', 'src/lib/consent.ts'],
    languageOptions: { globals: globals.browser },
  },
];
