import { loadSiteConfig } from '../../scripts/config.mjs';

export interface AnalyticsConfig {
  enabled: boolean;
  matomoUrl: string;
  siteId: string | null;
  consentVersion: string;
  consentDimensionId: number | null;
  consentDays: number;
  cookieVisitorSeconds: number;
  cookieSessionSeconds: number;
}

export interface PrivacyConfig {
  policyVersion: string;
  effectiveDate: string | null;
  controller: { name: string; email: string };
  hosting: {
    provider: string;
    processingLocations: string | null;
    processorAgreement: string | null;
    subprocessorInformation: string | null;
    transferSafeguards: string | null;
    deliveryLegalBasis: string | null;
    deliveryInterests: string | null;
    accessLogData: string | null;
    accessLogRetentionDays: number | null;
    accessLogAccessControls: string | null;
    backupRetentionDays: number | null;
  };
  contact: {
    providerInformation: string | null;
    legalBasis: string | null;
    retentionCriteria: string | null;
    transferSafeguards: string | null;
  };
  analytics: {
    hostingProvider: string | null;
    processingLocations: string | null;
    processorAgreement: string | null;
    subprocessorInformation: string | null;
    transferSafeguards: string | null;
    matomoVersion: string | null;
    ipMaskingBytes: number | null;
    maskedIpGeolocation: boolean | null;
    rawRetentionDays: number | null;
    aggregatedRetentionDays: number | null;
    proxyLogData: string | null;
    proxyLogRetentionDays: number | null;
    backupRetentionDays: number | null;
    accessControls: string | null;
    dedicatedSiteIdConfirmed: boolean;
    intrusiveFeaturesDisabled: boolean;
    visitorCookieSeconds: number | null;
    sessionCookieSeconds: number | null;
    serverSettingsEvidence: string | null;
    trackerResponseCookiePolicy: string | null;
    retentionEvidence: string | null;
    consentEvidenceProcedure: string | null;
  };
  review: {
    reviewedBy: string | null;
    reviewedAt: string | null;
    policyVersion: string | null;
    assessmentRecord: string | null;
  };
}

export interface SiteConfig {
  siteUrl: string;
  analytics: AnalyticsConfig;
  privacy: PrivacyConfig;
  publicationReady: boolean;
  unresolved: string[];
}

export function getSiteConfig(): SiteConfig {
  return loadSiteConfig() as SiteConfig;
}
