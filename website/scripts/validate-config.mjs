import { loadSiteConfig, validatePublication } from './config.mjs';

try {
  if (process.argv.slice(2).some((argument) => argument !== '--publish'))
    throw new Error(
      'Unknown configuration-validator argument. Use --publish to validate publication.',
    );
  const config = loadSiteConfig();
  if (process.argv.includes('--publish')) validatePublication(config);
  console.log(
    `Website configuration valid; analytics ${config.analytics.enabled ? 'enabled' : 'disabled'}.`,
  );
  if (!config.publicationReady)
    console.log(
      `Local/build preview only: ${config.unresolved.length} operational privacy facts require owner confirmation.`,
    );
} catch (error) {
  console.error(error instanceof Error ? error.message : 'Website configuration is invalid.');
  process.exitCode = 1;
}
