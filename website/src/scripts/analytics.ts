import {
  CONSENT_KEY,
  campaignName,
  cleanTrackingRequest,
  consentCopyEn,
  eventDetails,
  makeReceipt,
  privacySignal,
  readReceipt,
  referrerOrigin,
  safePage,
} from '../lib/consent';
import type { AnalyticsConfig } from '../lib/site-config';

export interface AnalyticsPayload {
  config: AnalyticsConfig;
  page: { id: string };
  appVersion: string;
}

interface Tracker {
  requireConsent(): void;
  setConsentGiven(): void;
  forgetConsentGiven(): void;
  deleteCookies(): void;
  disableBrowserFeatureDetection(): void;
  disableCampaignParameters(): void;
  disablePerformanceTracking(): void;
  disableHeartBeatTimer(): void;
  disableQueueRequest(): void;
  disableAlwaysUseSendBeacon(): void;
  setRequestMethod(method: string): void;
  setCookieNamePrefix(prefix: string): void;
  setCookiePath(path: string): void;
  setSecureCookie(secure: boolean): void;
  setCookieSameSite(value: string): void;
  setVisitorCookieTimeout(seconds: number): void;
  setReferralCookieTimeout(seconds: number): void;
  setSessionCookieTimeout(seconds: number): void;
  setCustomUrl(url: string): void;
  setReferrerUrl(url: string): void;
  setDocumentTitle(title: string): void;
  setCustomRequestProcessing(process: (request: string) => string): void;
  trackPageView(): void;
  trackEvent(category: string, action: string, name: string): void;
}

declare global {
  interface Window {
    Matomo?: { getTracker(url: string, siteId: string): Tracker };
    doNotTrack?: string;
  }
}

let initialized = false;

function validConfig(value: AnalyticsConfig): boolean {
  if (
    typeof value.enabled !== 'boolean' ||
    typeof value.consentVersion !== 'string' ||
    !/^[a-zA-Z0-9._-]{1,64}$/.test(value.consentVersion) ||
    !Number.isInteger(value.consentDays) ||
    value.consentDays < 1 ||
    value.consentDays > 365 ||
    !Number.isInteger(value.cookieVisitorSeconds) ||
    value.cookieVisitorSeconds < 1 ||
    value.cookieVisitorSeconds > value.consentDays * 86400 ||
    !Number.isInteger(value.cookieSessionSeconds) ||
    value.cookieSessionSeconds < 1 ||
    value.cookieSessionSeconds > 86400
  )
    return false;
  if (!value.enabled) return true;
  try {
    const url = new URL(value.matomoUrl);
    return (
      url.protocol === 'https:' &&
      !url.username &&
      !url.password &&
      !url.search &&
      !url.hash &&
      /^[1-9]\d*$/.test(String(value.siteId))
    );
  } catch {
    return false;
  }
}

export function initAnalytics(payload: AnalyticsPayload): void {
  if (initialized || !payload?.config || !validConfig(payload.config)) return;
  initialized = true;
  const { config } = payload;
  const page = safePage(payload.page?.id ?? 'not-found');
  const canonical = new URL(page.path, window.location.origin).href;
  const referrer = referrerOrigin(document.referrer);
  const campaign = campaignName(location.search);
  const banner = document.querySelector<HTMLElement>('[data-privacy-banner]');
  const dialog = document.querySelector<HTMLDialogElement>('[data-privacy-dialog]');
  const statuses = [...document.querySelectorAll<HTMLElement>('[data-privacy-status]')];
  const acceptButtons = [...document.querySelectorAll<HTMLButtonElement>('[data-privacy-accept]')];
  const rejectButtons = [...document.querySelectorAll<HTMLButtonElement>('[data-privacy-reject]')];
  const withdrawButtons = [
    ...document.querySelectorAll<HTMLButtonElement>('[data-privacy-withdraw]'),
  ];
  let storageFailed = false;
  let trackerFailed = false;
  let tracker: Tracker | undefined;
  let script: HTMLScriptElement | undefined;
  let loading: Promise<void> | undefined;
  let granted = false;
  let viewed = false;
  let grantEvidencePending = false;
  let listening = false;
  let generation = 0;
  let expiryTimer: ReturnType<typeof setTimeout> | undefined;
  const pendingRequests = new Set<AbortController>();

  function signal(): boolean {
    return privacySignal(navigator, window.doNotTrack);
  }

  function receipt() {
    try {
      const raw = localStorage.getItem(CONSENT_KEY);
      const state = readReceipt(raw, config.consentVersion, config.consentDays);
      if (raw && !state) localStorage.removeItem(CONSENT_KEY);
      return state;
    } catch {
      storageFailed = true;
      return null;
    }
  }

  function allowed(): boolean {
    return config.enabled && !storageFailed && !signal() && receipt()?.choice === 'accept';
  }

  function removeCookies(removeConsentMarker = false): void {
    const siteId = String(config.siteId);
    for (const cookie of document.cookie.split(';')) {
      const name = cookie.split('=')[0]?.trim() ?? '';
      if (
        (/^[1-9]\d*$/.test(siteId) &&
          /^bm_pk_[a-z0-9_]+\.[1-9]\d*\./.test(name) &&
          name.split('.')[1] === siteId) ||
        (removeConsentMarker && name === 'mtm_consent_removed')
      ) {
        document.cookie = `${name}=; Max-Age=0; Path=/; SameSite=Lax${location.protocol === 'https:' ? '; Secure' : ''}`;
      }
    }
  }

  function revoke(): void {
    generation++;
    granted = false;
    grantEvidencePending = false;
    document.removeEventListener('click', trackAction);
    listening = false;
    for (const request of pendingRequests) request.abort();
    pendingRequests.clear();
    const retired = tracker;
    tracker = undefined;
    if (retired) {
      retired.disableHeartBeatTimer();
      retired.forgetConsentGiven();
      retired.deleteCookies();
    }
    removeCookies(!!retired);
    // Retired Matomo unload hooks must not delete a later consent period's active cookies.
    retired?.setCookieNamePrefix('bm_retired_');
  }

  function updateUi(): void {
    const state = receipt();
    const blocked = signal();
    if (banner) banner.hidden = !config.enabled || blocked || !!state;
    for (const button of acceptButtons) {
      button.hidden = !config.enabled;
      button.disabled = blocked;
    }
    for (const button of rejectButtons) button.hidden = !config.enabled;
    for (const button of withdrawButtons)
      button.hidden = !config.enabled || state?.choice !== 'accept' || blocked;
    const text = !config.enabled
      ? consentCopyEn.disabled
      : blocked
        ? consentCopyEn.signal
        : storageFailed
          ? consentCopyEn.storageError
          : trackerFailed
            ? consentCopyEn.trackerError
            : state?.choice === 'accept'
              ? consentCopyEn.accepted
              : state?.choice === 'reject'
                ? consentCopyEn.rejected
                : consentCopyEn.undecided;
    for (const node of statuses) node.textContent = text;
    for (const node of document.querySelectorAll<HTMLElement>('[data-privacy-feedback]')) {
      node.hidden = !storageFailed;
      node.textContent = storageFailed ? text : '';
    }
  }

  function setExpiry(): void {
    clearTimeout(expiryTimer);
    const state = receipt();
    if (state)
      expiryTimer = setTimeout(
        reconcile,
        Math.min(Math.max(1, state.expiresAt - Date.now()), 2_147_483_647),
      );
  }

  function configureTracker(): void {
    if (!allowed() || trackerFailed || !window.Matomo) return;
    if (!tracker) {
      const endpoint = new URL(
        'matomo.php',
        config.matomoUrl.endsWith('/') ? config.matomoUrl : config.matomoUrl + '/',
      ).href;
      const candidate = window.Matomo.getTracker(endpoint, String(config.siteId));
      if (
        ![
          'requireConsent',
          'setConsentGiven',
          'forgetConsentGiven',
          'disableCampaignParameters',
          'disableQueueRequest',
          'setCustomRequestProcessing',
        ].every((key) => typeof candidate[key as keyof Tracker] === 'function')
      ) {
        trackerFailed = true;
        updateUi();
        return;
      }
      tracker = candidate;
      const trackerGeneration = generation;
      tracker.requireConsent();
      tracker.disableCampaignParameters();
      tracker.disablePerformanceTracking();
      tracker.disableHeartBeatTimer();
      tracker.disableQueueRequest();
      tracker.disableAlwaysUseSendBeacon();
      tracker.setRequestMethod('GET');
      tracker.setCookieNamePrefix('bm_pk_');
      tracker.setCookiePath('/');
      tracker.setSecureCookie(location.protocol === 'https:');
      tracker.setCookieSameSite('Lax');
      tracker.setVisitorCookieTimeout(config.cookieVisitorSeconds);
      tracker.setReferralCookieTimeout(config.cookieVisitorSeconds);
      tracker.setSessionCookieTimeout(config.cookieSessionSeconds);
      tracker.setCustomUrl(canonical);
      tracker.setReferrerUrl(referrer);
      tracker.setDocumentTitle(page.title);
      tracker.setCustomRequestProcessing((request) => {
        if (trackerGeneration !== generation || !allowed() || !granted) return '';
        const params = new URLSearchParams(
          cleanTrackingRequest(request, canonical, page.title, referrer, campaign),
        );
        params.set('consent', '1');
        const controller = new AbortController();
        pendingRequests.add(controller);
        // Matomo can delay its transport after this hook; own abortable dispatch prevents sends after withdrawal.
        void fetch(`${endpoint}?${params}`, {
          method: 'GET',
          mode: 'no-cors',
          credentials: 'omit',
          referrerPolicy: 'no-referrer',
          cache: 'no-store',
          keepalive: false,
          signal: controller.signal,
        })
          .catch(() => {})
          .finally(() => pendingRequests.delete(controller));
        return '';
      });
    }
    if (!allowed()) return;
    if (!granted) {
      tracker.setConsentGiven();
      tracker.disableBrowserFeatureDetection();
      granted = true;
    }
    if (!listening) {
      document.addEventListener('click', trackAction);
      listening = true;
    }
    if (!viewed) {
      viewed = true;
      tracker.trackPageView();
    }
    if (grantEvidencePending) {
      grantEvidencePending = false;
      tracker.trackEvent('Privacy', 'consent-granted', `${config.consentVersion}|${page.id}`);
    }
  }

  function start(): void {
    if (!allowed() || trackerFailed) return;
    if (tracker || window.Matomo) {
      configureTracker();
      return;
    }
    if (loading) return;
    const base = config.matomoUrl.endsWith('/') ? config.matomoUrl : config.matomoUrl + '/';
    loading = new Promise<void>((resolve, reject) => {
      script = document.createElement('script');
      script.src = new URL('matomo.js', base).href;
      script.async = true;
      script.referrerPolicy = 'no-referrer';
      script.dataset.matomoTracker = '';
      script.onload = () => resolve();
      script.onerror = () => reject(new Error('Analytics unavailable'));
      document.head.append(script);
    });
    loading
      .then(() => {
        if (allowed()) configureTracker();
      })
      .catch(() => {
        trackerFailed = true;
        updateUi();
      });
  }

  function reconcile(): void {
    if (allowed()) start();
    else revoke();
    updateUi();
    setExpiry();
  }

  function choose(choice: 'accept' | 'reject'): void {
    if (choice === 'accept' && (!config.enabled || signal())) return;
    storageFailed = false;
    try {
      localStorage.setItem(
        CONSENT_KEY,
        JSON.stringify(makeReceipt(choice, config.consentVersion, config.consentDays)),
      );
      if (receipt()?.choice !== choice) storageFailed = true;
    } catch {
      storageFailed = true;
    }
    if (storageFailed) {
      // A failed refusal must not leave an earlier acceptance active after navigation.
      try {
        localStorage.removeItem(CONSENT_KEY);
      } catch {
        /* Storage can forbid removal too; this document still keeps analytics off. */
      }
    }
    if (choice === 'accept' && !storageFailed) grantEvidencePending = true;
    reconcile();
    if (dialog?.open) dialog.close();
  }

  for (const button of acceptButtons) button.addEventListener('click', () => choose('accept'));
  for (const button of rejectButtons) button.addEventListener('click', () => choose('reject'));
  for (const button of withdrawButtons) button.addEventListener('click', () => choose('reject'));
  for (const control of document.querySelectorAll<HTMLElement>('[data-privacy-settings]')) {
    if (!dialog || typeof dialog.showModal !== 'function') continue;
    control.hidden = false;
    control.addEventListener('click', (event) => {
      event.preventDefault();
      updateUi();
      if (!dialog.open) dialog.showModal();
    });
  }
  for (const button of document.querySelectorAll<HTMLButtonElement>('[data-privacy-close]'))
    button.addEventListener('click', () => dialog?.close());
  function trackAction(event: MouseEvent): void {
    if (!allowed() || !granted || !tracker || !(event.target instanceof Element)) return;
    const link = event.target.closest<HTMLElement>('[data-analytics-event]');
    if (!link) return;
    const details = eventDetails(
      link.dataset.analyticsEvent ?? '',
      link.dataset.analyticsPlacement ?? '',
      link.dataset.analyticsChannel ?? '',
      page.id,
      payload.appVersion,
    );
    if (details) tracker.trackEvent(details.category, details.action, details.name);
  }
  window.addEventListener('storage', (event) => {
    if (event.key === CONSENT_KEY || event.key === null) reconcile();
  });
  window.addEventListener('pageshow', reconcile);
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) reconcile();
  });
  reconcile();
}

if (typeof document !== 'undefined') {
  const element = document.getElementById('analytics-config');
  if (element?.textContent) {
    try {
      initAnalytics(JSON.parse(element.textContent) as AnalyticsPayload);
    } catch {
      /* Invalid public configuration leaves analytics disabled. */
    }
  }
}
