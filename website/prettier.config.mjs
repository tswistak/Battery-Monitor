export default {
  plugins: ['prettier-plugin-astro'],
  printWidth: 100,
  tabWidth: 2,
  useTabs: false,
  singleQuote: true,
  semi: true,
  trailingComma: 'all',
  proseWrap: 'always',
  astroCompressHTML: 'jsx',
  overrides: [{ files: '*.astro', options: { parser: 'astro' } }],
};
