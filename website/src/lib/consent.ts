export const CONSENT_KEY = 'bm.analytics-consent';
export const DAY_MS = 86_400_000;
export const consentCopyEn = {
  bannerTitle: 'Help improve this website',
  settingsTitle: 'Privacy settings',
  accept: 'Accept analytics',
  reject: 'Reject analytics',
  withdraw: 'Withdraw analytics consent',
  close: 'Close',
  disabled: 'Website analytics is disabled.',
  signal: 'Your browser privacy signal keeps website analytics off.',
  storageError: 'Your preference could not be saved. Analytics remains off.',
  trackerError: 'Analytics is unavailable. The website and downloads still work.',
  accepted: 'You have accepted website analytics. You can withdraw at any time.',
  rejected: 'You have rejected website analytics.',
  undecided: 'Website analytics is off until you choose to accept it.',
} as const;

export interface ConsentReceipt {
  choice: 'accept' | 'reject';
  version: string;
  decidedAt: number;
  expiresAt: number;
}

export function readReceipt(
  raw: string | null,
  version: string,
  days: number,
  now = Date.now(),
): ConsentReceipt | null {
  if (!raw || !Number.isInteger(days) || days < 1 || days > 365) return null;
  try {
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
    const item = value as Record<string, unknown>;
    if (
      Object.keys(item).sort().join(',') !== 'choice,decidedAt,expiresAt,version' ||
      (item.choice !== 'accept' && item.choice !== 'reject') ||
      item.version !== version ||
      !Number.isSafeInteger(item.decidedAt) ||
      !Number.isSafeInteger(item.expiresAt)
    )
      return null;
    const receipt = item as unknown as ConsentReceipt;
    if (
      receipt.decidedAt < 0 ||
      receipt.decidedAt > now ||
      receipt.expiresAt <= now ||
      receipt.expiresAt <= receipt.decidedAt ||
      receipt.expiresAt > receipt.decidedAt + days * DAY_MS
    )
      return null;
    return receipt;
  } catch {
    return null;
  }
}

export function makeReceipt(
  choice: ConsentReceipt['choice'],
  version: string,
  days: number,
  now = Date.now(),
): ConsentReceipt {
  return { choice, version, decidedAt: now, expiresAt: now + days * DAY_MS };
}

export function privacySignal(
  navigatorValue: Pick<Navigator, 'doNotTrack'> & {
    globalPrivacyControl?: boolean;
    msDoNotTrack?: string;
  },
  windowDnt?: string,
): boolean {
  return (
    navigatorValue.globalPrivacyControl === true ||
    [navigatorValue.doNotTrack, navigatorValue.msDoNotTrack, windowDnt].some(
      (value) => value === '1' || value === 'yes',
    )
  );
}

const pageInfo: Record<string, readonly [string, string]> = {
  home: ['/', 'Battery Monitor'],
  download: ['/download/', 'Download Battery Monitor'],
  help: ['/help/', 'Battery Monitor help'],
  'battery-alarms': ['/help/battery-alarms/', 'Battery alarms'],
  notifications: ['/help/notifications/', 'Notifications'],
  'background-monitoring': ['/help/background-monitoring/', 'Background monitoring'],
  'battery-readings': ['/help/battery-readings/', 'Battery readings'],
  'charging-targets': ['/help/charging-targets/', 'Charging targets'],
  'history-and-backups': ['/help/history-and-backups/', 'History and backups'],
  changelog: ['/changelog/', 'Battery Monitor changelog'],
  privacy: ['/privacy/', 'Privacy policy'],
  about: ['/about/', 'About Battery Monitor'],
  'not-found': ['/404/', 'Page not found'],
};

export function safePage(id: string) {
  const known = Object.hasOwn(pageInfo, id) ? id : 'not-found';
  const [path, title] = pageInfo[known]!;
  return { id: known, path, title };
}

export function referrerOrigin(value: string): string {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.origin + '/' : '';
  } catch {
    return '';
  }
}

export const eventActions = [
  'apk',
  'release',
  'fdroid',
  'source',
  'support',
  'report-issue',
] as const;
const placements = new Set(['header', 'hero', 'download', 'help', 'footer', 'support']);
const channels = new Set(['github', 'fdroid', 'github-sponsors', 'liberapay', 'suppi']);

export function eventDetails(
  action: string,
  placement: string,
  channel: string,
  pageId: string,
  version: string,
) {
  if (
    !(eventActions as readonly string[]).includes(action) ||
    !placements.has(placement) ||
    !channels.has(channel)
  )
    return null;
  const appVersion = /^[a-zA-Z0-9][a-zA-Z0-9._+-]{0,40}$/.test(version) ? version : 'unknown';
  return {
    category: ['apk', 'release', 'fdroid'].includes(action) ? 'Distribution' : 'Links',
    action,
    name: `${safePage(pageId).id}|${placement}|${channel}|${appVersion}`,
  };
}

const requestKeys = new Set([
  'idsite',
  'rec',
  'r',
  'rand',
  '_id',
  '_idn',
  '_idts',
  '_viewts',
  '_ects',
  '_refts',
  'pv_id',
  'send_image',
  'cookie',
  'h',
  'm',
  's',
  'lang',
  'res',
  'e_c',
  'e_a',
  'e_n',
  'e_v',
  'new_visit',
]);
const campaigns = new Set(['app-release', 'website-launch', 'project-update']);

export function campaignName(search: string): string | undefined {
  const value = new URLSearchParams(search).get('utm_campaign') ?? '';
  return campaigns.has(value) ? value : undefined;
}

export function cleanTrackingRequest(
  request: string,
  canonicalUrl: string,
  title: string,
  referrer: string,
  campaign?: string,
  consentDimension?: { id: number; version: string },
): string {
  const output = new URLSearchParams();
  const source = new URLSearchParams(request);
  for (const [key, value] of source) if (requestKeys.has(key)) output.set(key, value);
  const trackingUrl = new URL(canonicalUrl);
  trackingUrl.search = '';
  trackingUrl.hash = '';
  if (campaign && campaigns.has(campaign)) trackingUrl.searchParams.set('mtm_campaign', campaign);
  output.set('url', trackingUrl.href);
  output.set('action_name', title);
  if (referrer) output.set('urlref', referrerOrigin(referrer));
  if (consentDimension && !source.has('e_c')) {
    const key = `dimension${consentDimension.id}`;
    if (source.get(key) === consentDimension.version) output.set(key, consentDimension.version);
  }
  return output.toString();
}
