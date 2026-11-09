import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// Build chunks run from dist, so configuration stays relative to the project command's cwd.
const websiteRoot = process.cwd();
const placeholder =
  /\b(?:todo|tbd|placeholder|unconfirmed|unknown|pending|changeme)\b|example\.(?:com|org)|\[[^\]]+\]/i;
const datePattern = /^\d{4}-\d{2}-\d{2}$/;

function text(value) {
  return typeof value === 'string' && value.trim().length > 0 && !placeholder.test(value);
}

function date(value) {
  return (
    typeof value === 'string' &&
    datePattern.test(value) &&
    !Number.isNaN(Date.parse(value)) &&
    new Date(value).toISOString().slice(0, 10) === value
  );
}

function flag(value, name) {
  if (value === undefined || value === '') return false;
  if (value !== 'true' && value !== 'false') throw new Error(`${name} must be true or false.`);
  return value === 'true';
}

function integer(value, fallback, name, max) {
  if (value === undefined || value === '') return fallback;
  if (!/^\d+$/.test(String(value)) || Number(value) < 1 || Number(value) > max) {
    throw new Error(`${name} must be an integer between 1 and ${max}.`);
  }
  return Number(value);
}

function httpsUrl(value, name, allowLocal = false) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`${name} must be a valid absolute URL.`);
  }
  const local =
    allowLocal &&
    url.protocol === 'http:' &&
    ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  if (
    (!local && url.protocol !== 'https:') ||
    url.username ||
    url.password ||
    url.search ||
    url.hash
  ) {
    throw new Error(`${name} must use HTTPS without credentials, query or fragment.`);
  }
  if (!url.pathname.endsWith('/')) url.pathname += '/';
  return url.href;
}

function field(object, path) {
  return path
    .split('.')
    .reduce((value, key) => (value && typeof value === 'object' ? value[key] : undefined), object);
}

/** Return missing operational facts; a checked box alone never makes a policy publishable. */
export function publicationIssues(privacy, analytics) {
  const issues = [];
  const requireText = (path) => {
    if (!text(field(privacy, path))) issues.push(`privacy.${path}`);
  };
  const requireDate = (path) => {
    if (!date(field(privacy, path))) issues.push(`privacy.${path}`);
  };
  const requireDays = (path, maximum, minimum = 0) => {
    const value = field(privacy, path);
    if (!Number.isInteger(value) || value < minimum || value > maximum)
      issues.push(`privacy.${path}`);
  };
  for (const path of [
    'policyVersion',
    'controller.name',
    'controller.email',
    'hosting.provider',
    'hosting.processingLocations',
    'hosting.processorAgreement',
    'hosting.subprocessorInformation',
    'hosting.transferSafeguards',
    'hosting.deliveryLegalBasis',
    'hosting.deliveryInterests',
    'hosting.accessLogData',
    'hosting.accessLogAccessControls',
    'contact.providerInformation',
    'contact.legalBasis',
    'contact.retentionCriteria',
    'contact.transferSafeguards',
    'review.reviewedBy',
    'review.assessmentRecord',
  ])
    requireText(path);
  requireDate('effectiveDate');
  requireDate('review.reviewedAt');
  if (field(privacy, 'review.policyVersion') !== privacy.policyVersion)
    issues.push('privacy.review.policyVersion');
  requireDays('hosting.accessLogRetentionDays', 365);
  requireDays('hosting.backupRetentionDays', 365);
  if (analytics.enabled) {
    for (const path of [
      'analytics.hostingProvider',
      'analytics.processingLocations',
      'analytics.processorAgreement',
      'analytics.subprocessorInformation',
      'analytics.transferSafeguards',
      'analytics.matomoVersion',
      'analytics.proxyLogData',
      'analytics.accessControls',
      'analytics.serverSettingsEvidence',
      'analytics.trackerResponseCookiePolicy',
      'analytics.retentionEvidence',
      'analytics.consentEvidenceProcedure',
    ])
      requireText(path);
    requireDays('analytics.rawRetentionDays', 365, 1);
    requireDays('analytics.aggregatedRetentionDays', 1096, 1);
    requireDays('analytics.proxyLogRetentionDays', 365);
    requireDays('analytics.backupRetentionDays', 365);
    const a = privacy.analytics;
    if (![2, 3, 4].includes(a.ipMaskingBytes)) issues.push('privacy.analytics.ipMaskingBytes');
    for (const name of [
      'maskedIpGeolocation',
      'dedicatedSiteIdConfirmed',
      'intrusiveFeaturesDisabled',
    ]) {
      if (a[name] !== true) issues.push(`privacy.analytics.${name}`);
    }
    if (a.visitorCookieSeconds !== analytics.cookieVisitorSeconds)
      issues.push('privacy.analytics.visitorCookieSeconds');
    if (a.sessionCookieSeconds !== analytics.cookieSessionSeconds)
      issues.push('privacy.analytics.sessionCookieSeconds');
    const version = /^(\d+)\.(\d+)(?:\.\d+)?(?:[-+].*)?$/.exec(a.matomoVersion ?? '');
    if (
      !version ||
      Number(version[1]) < 5 ||
      (Number(version[1]) === 5 && Number(version[2]) < 1)
    ) {
      issues.push('privacy.analytics.matomoVersion (requires Matomo 5.1 or newer)');
    }
  }
  return [...new Set(issues)];
}

export function loadSiteConfig({
  env = process.env,
  privacy: suppliedPrivacy,
  root = websiteRoot,
} = {}) {
  if (env === process.env && existsSync(resolve(root, '.env')))
    process.loadEnvFile(resolve(root, '.env'));
  const privacyPath = resolve(root, env.WEBSITE_PRIVACY_CONFIG || 'config/privacy.json');
  let privacy;
  try {
    privacy = suppliedPrivacy ?? JSON.parse(readFileSync(privacyPath, 'utf8'));
  } catch {
    throw new Error(
      'Cannot read the website privacy configuration. Check WEBSITE_PRIVACY_CONFIG and JSON syntax.',
    );
  }
  for (const key of ['controller', 'hosting', 'contact', 'analytics', 'review']) {
    if (
      !privacy ||
      typeof privacy !== 'object' ||
      !privacy[key] ||
      typeof privacy[key] !== 'object' ||
      Array.isArray(privacy[key])
    ) {
      throw new Error(`Privacy configuration requires a ${key} object.`);
    }
  }
  const stringFields = [
    'effectiveDate',
    'hosting.provider',
    'hosting.processingLocations',
    'hosting.processorAgreement',
    'hosting.subprocessorInformation',
    'hosting.transferSafeguards',
    'hosting.deliveryLegalBasis',
    'hosting.deliveryInterests',
    'hosting.accessLogData',
    'hosting.accessLogAccessControls',
    'contact.providerInformation',
    'contact.legalBasis',
    'contact.retentionCriteria',
    'contact.transferSafeguards',
    'analytics.hostingProvider',
    'analytics.processingLocations',
    'analytics.processorAgreement',
    'analytics.subprocessorInformation',
    'analytics.transferSafeguards',
    'analytics.matomoVersion',
    'analytics.proxyLogData',
    'analytics.accessControls',
    'analytics.serverSettingsEvidence',
    'analytics.trackerResponseCookiePolicy',
    'analytics.retentionEvidence',
    'analytics.consentEvidenceProcedure',
    'review.reviewedBy',
    'review.reviewedAt',
    'review.policyVersion',
    'review.assessmentRecord',
  ];
  for (const path of stringFields) {
    const value = field(privacy, path);
    if (value != null && (typeof value !== 'string' || value.length > 5000))
      throw new Error(`privacy.${path} must be text or null.`);
  }
  for (const path of [
    'hosting.accessLogRetentionDays',
    'hosting.backupRetentionDays',
    'analytics.ipMaskingBytes',
    'analytics.rawRetentionDays',
    'analytics.aggregatedRetentionDays',
    'analytics.proxyLogRetentionDays',
    'analytics.backupRetentionDays',
    'analytics.visitorCookieSeconds',
    'analytics.sessionCookieSeconds',
  ]) {
    const value = field(privacy, path);
    if (value != null && (!Number.isSafeInteger(value) || value < 0))
      throw new Error(`privacy.${path} must be a nonnegative integer or null.`);
  }
  for (const path of [
    'analytics.maskedIpGeolocation',
    'analytics.dedicatedSiteIdConfirmed',
    'analytics.intrusiveFeaturesDisabled',
  ]) {
    const value = field(privacy, path);
    if (value != null && typeof value !== 'boolean')
      throw new Error(`privacy.${path} must be a boolean or null.`);
  }
  if (
    !text(privacy.controller.name) ||
    !/^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$/.test(privacy.controller.email ?? '')
  ) {
    throw new Error('Privacy configuration requires the real controller name and contact email.');
  }
  if (!text(privacy.policyVersion))
    throw new Error('Privacy configuration requires a policyVersion.');
  const enabled = flag(env.MATOMO_ENABLED, 'MATOMO_ENABLED');
  const consentDays = integer(env.CONSENT_VALIDITY_DAYS, 180, 'CONSENT_VALIDITY_DAYS', 365);
  const siteId = env.MATOMO_SITE_ID || null;
  if (siteId !== null && !/^[1-9]\d*$/.test(siteId))
    throw new Error('MATOMO_SITE_ID must be a positive numeric site identifier.');
  if (enabled && siteId === null)
    throw new Error('MATOMO_SITE_ID is required when MATOMO_ENABLED=true.');
  const consentVersion = env.ANALYTICS_CONSENT_VERSION || 'analytics-2026-11-09-v1';
  if (!/^[a-zA-Z0-9._-]{1,64}$/.test(consentVersion))
    throw new Error('ANALYTICS_CONSENT_VERSION must be a short stable identifier.');
  const analytics = {
    enabled,
    matomoUrl: httpsUrl(env.MATOMO_URL || 'https://radioactive-toy.swistak.codes/', 'MATOMO_URL'),
    siteId,
    consentVersion,
    consentDays,
    cookieVisitorSeconds: integer(
      env.MATOMO_VISITOR_COOKIE_SECONDS,
      15552000,
      'MATOMO_VISITOR_COOKIE_SECONDS',
      consentDays * 86400,
    ),
    cookieSessionSeconds: integer(
      env.MATOMO_SESSION_COOKIE_SECONDS,
      1800,
      'MATOMO_SESSION_COOKIE_SECONDS',
      86400,
    ),
  };
  if (analytics.cookieVisitorSeconds > consentDays * 86400) {
    throw new Error('MATOMO_VISITOR_COOKIE_SECONDS cannot outlive the consent validity period.');
  }
  const siteUrl = httpsUrl(
    env.WEBSITE_URL || 'https://batterymonitor.swistak.codes/',
    'WEBSITE_URL',
    true,
  );
  if (new URL(siteUrl).pathname !== '/')
    throw new Error('WEBSITE_URL must identify the site root.');
  const unresolved = publicationIssues(privacy, analytics);
  if (enabled && unresolved.length) {
    throw new Error(
      `Analytics remains disabled until operational facts are complete: ${unresolved.join(', ')}.`,
    );
  }
  return { siteUrl, analytics, privacy, publicationReady: unresolved.length === 0, unresolved };
}

export function validatePublication(config) {
  if (new URL(config.siteUrl).protocol !== 'https:')
    throw new Error('Publishing requires an HTTPS WEBSITE_URL.');
  if (!config.publicationReady)
    throw new Error(
      `Publication blocked by unresolved privacy facts: ${config.unresolved.join(', ')}.`,
    );
  return config;
}
