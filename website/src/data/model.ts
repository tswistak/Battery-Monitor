export type ContentMode = 'local' | 'production' | 'fixture';
export type ArticleId =
  | 'battery-alarms'
  | 'notifications'
  | 'background-monitoring'
  | 'battery-readings'
  | 'charging-targets'
  | 'history-and-backups';
export interface ReleaseAsset {
  id: number;
  name: string;
  size: number;
  updatedAt: string;
  url: string;
  digest: string | null;
}
export interface Provenance {
  schemaVersion: 3;
  mode: ContentMode;
  websiteCommit: string;
  websiteDirty?: boolean;
  appDirty?: boolean;
  appCommit: string;
  releaseId: number | null;
  tag: string | null;
  version: string;
  publishedAt: string | null;
  releaseAssets: { apk: ReleaseAsset; signature: ReleaseAsset | null } | null;
  importedAt: string;
  sourceUrl: string;
  omitted: string[];
  sources: Array<{ path: string; sha256: string; note?: string }>;
  websiteSources?: Array<{ path: string; sha256: string; note?: string }>;
}
export interface SiteContent {
  schemaVersion: 3;
  locale: 'en';
  provenance: Provenance;
  release: {
    version: string;
    publishedAt: string | null;
    url: string;
    apkUrl: string | null;
    signatureUrl: string | null;
    apkSha256: string | null;
  };
  capabilities: {
    alarms: boolean;
    history: boolean;
    widgets: boolean;
    liveUpdates: boolean;
    backups: boolean;
    privileged: boolean;
    historyCharts: boolean;
    modernWidgets: boolean;
    targets: boolean;
  };
  requirements: { androidMin: number; apiMin: number };
  app: {
    name: string;
    privacyHtml: string;
    attributionHtml: string;
    contactEmail: string;
    sourceUrl: string;
    licenseUrl: string;
  };
  articles: Array<{ id: ArticleId; title: string; html: string; sources: string[] }>;
  changelog: Array<{ version: string; date: string | null; url: string | null; html: string }>;
  media: Partial<
    Record<
      'current' | 'history' | 'alarms' | 'widgets' | 'notification' | 'backups' | 'advanced',
      string
    >
  >;
  videos: Partial<Record<'notification' | 'backups' | 'advanced' | 'widgets', string>>;
  funding: { github: string | null; liberapay: string | null; suppi: string | null };
  omitted: string[];
}
