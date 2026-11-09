import type { SiteConfig } from '../../lib/site-config';

export interface PrivacySection {
  id: string;
  title: string;
  html: string;
}

const entities: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
};

function escape(value: unknown): string {
  return String(value).replace(/[&<>"']/g, (character) => entities[character] ?? character);
}

function facts(values: Record<string, string | number | null | undefined>): string {
  const text = Object.entries(values)
    .filter(([, value]) => value != null && value !== '')
    .map(([label, value]) => `${escape(label)}: ${escape(value)}.`)
    .join(' ');
  return text ? `<p>${text}</p>` : '';
}

function days(value: number | null): string | null {
  return value == null ? null : value === 0 ? 'not retained' : `up to ${value} days`;
}

export function getPrivacySections(config: SiteConfig): PrivacySection[] {
  const { privacy: p, analytics: a } = config;
  const contact = `<a href="mailto:${encodeURIComponent(p.controller.email)}">${escape(p.controller.email)}</a>`;
  return [
    {
      id: 'controller',
      title: 'Who is responsible',
      html: `<p>${escape(p.controller.name)} is responsible for this website. For privacy questions or requests, contact ${contact}.</p>${facts({ 'Policy version': p.policyVersion, 'Effective date': p.effectiveDate })}`,
    },
    {
      id: 'android-app',
      title: 'The Android application',
      html: '<p>Battery Monitor processes and stores battery readings on your device. The app does not upload monitoring data to an external server and this website does not receive your app readings, alarms or diagnostic data automatically.</p><p>You choose whether to export a file, make a backup or send a report. Exported or shared files may contain device information, including identifiers made available by the device. Review the file before sharing it and include only what is necessary. Following a website or support link from the app opens a separate service whose processing is described here or in that service’s policy.</p>',
    },
    {
      id: 'website-delivery',
      title: 'Delivering and protecting the website',
      html: `<p>Loading a page sends the network address and request information needed to deliver it to the website infrastructure. This can include an IP address, request time, requested URL, browser information and a referring address supplied by your browser. These delivery and security operations are separate from optional analytics. Do not put sensitive information into page URLs.</p>${facts({ 'Hosting/CDN provider': p.hosting.provider, 'Processing locations': p.hosting.processingLocations, 'Processor arrangements': p.hosting.processorAgreement, 'Subprocessor information': p.hosting.subprocessorInformation })}${facts({ 'Delivery/security legal basis': p.hosting.deliveryLegalBasis, 'Relevant interests and necessity assessment': p.hosting.deliveryInterests })}${facts({ 'Access-log information': p.hosting.accessLogData, 'Access-log retention': days(p.hosting.accessLogRetentionDays), 'Access controls': p.hosting.accessLogAccessControls, 'Delivery/security backup retention': days(p.hosting.backupRetentionDays) })}${facts({ 'International processing and safeguards': p.hosting.transferSafeguards })}<p>The website does not claim that all processing stays in the EU; the actual infrastructure and processor arrangements determine where data is processed.</p>`,
    },
    {
      id: 'preferences',
      title: 'Your analytics choice',
      html: `<p>${a.enabled ? 'Optional website analytics is available only after you choose “Accept analytics”.' : 'Optional website analytics is disabled. No Matomo tracker is loaded.'} You can use the website and follow download links without accepting analytics. Closing or ignoring a notice, scrolling, or continuing to browse is not consent.</p><p>When analytics is available, “Accept analytics” and “Reject analytics” are offered together. Use <a href="/privacy/#preferences" data-privacy-settings>Privacy settings</a> to change your choice or withdraw consent. Withdrawal prevents further analytics activity on this site and removes this site’s analytics cookies where the browser permits it. It does not erase records already held on the analytics server; contact ${contact} if you want to request deletion.</p><p>A choice is valid for ${escape(a.consentDays)} days. A new purpose version requires a new choice. Missing, malformed, expired or incompatible choices do not allow analytics. The site also treats an enabled Global Privacy Control or Do Not Track signal as a refusal of optional analytics, even when a previous local choice was acceptance. This voluntary analytics policy is broader than GPC’s sale/sharing opt-out meaning.</p><p>Analytics is not loaded when JavaScript is unavailable. All page content, help and download links still work. The privacy controls require JavaScript to change a stored choice.</p>`,
    },
    {
      id: 'analytics',
      title: 'Optional website analytics',
      html: a.enabled
        ? `<p>With your consent, this website uses the dedicated Matomo service at ${escape(new URL(a.matomoUrl).origin)} to understand visits, useful content and distribution-channel interest. Consent is the basis for this optional processing.</p><p>Analytics can include known page paths, navigation and visit information, referring sites reduced to their origin, standard browser/operating-system/device-category information, approximate geography under the configured IP-masking settings, bounded first-party visitor identifiers and specific download, source or support-link events. Raw URL queries, fragments, arbitrary unknown paths and user-provided event text are excluded. Automatic campaign capture is disabled. Only the approved <code>utm_campaign</code> values <code>app-release</code>, <code>website-launch</code> and <code>project-update</code> may be sent as a controlled campaign name; other campaign, source, medium, keyword and query values are discarded.</p>${facts({ 'Matomo hosting provider': p.analytics.hostingProvider, 'Processing locations': p.analytics.processingLocations, 'Processor arrangements': p.analytics.processorAgreement, Subprocessors: p.analytics.subprocessorInformation, 'Transfer safeguards': p.analytics.transferSafeguards })}${p.analytics.ipMaskingBytes == null ? '' : `<p>IP masking removes ${escape(p.analytics.ipMaskingBytes)} bytes before the configured location processing. Masked IP is used for geolocation. Pseudonymous analytics is not described as completely anonymous.</p>`}<p>We do not enable user IDs, cross-domain visitor linking, additional fingerprinting, heatmaps, session recording, form-content tracking, advertising or remarketing. The site does not send app battery data. No pre-consent actions are queued for later replay. A newly granted choice sends the notice version with a consent-granted event; refusal is not reported as an event.</p><p>A distribution-link click measures interest in that link, not an installation or application use. GitHub’s aggregate asset counts are a separate measurement.</p>`
        : '<p>This website does not collect optional analytics, load Matomo, or create analytics identifiers or cookies. The site does not perform hidden replacement tracking when analytics is refused or unavailable.</p>',
    },
    {
      id: 'storage',
      title: 'Browser storage and cookies',
      html: `<p>The website does not use a cross-site identifier for consent. If the analytics controls are used, the local preference key <code>bm.analytics-consent</code> stores only a choice, notice version, decision time and expiry. It is confined to this website origin. The decision is valid for ${escape(a.consentDays)} days; localStorage has no automatic expiry while the site is closed. Invalid, expired or old-purpose values are removed at the next site check and cannot enable analytics after expiry.</p>${a.enabled ? `<div class="table-scroll"><table><caption>Storage used by the configured analytics integration</caption><thead><tr><th scope="col">Name</th><th scope="col">Purpose</th><th scope="col">Maximum configured lifetime</th></tr></thead><tbody><tr><th scope="row"><code>bm.analytics-consent</code> (localStorage)</th><td>Remember your accept/reject choice and its notice version.</td><td>${escape(a.consentDays)} days of decision validity</td></tr><tr><th scope="row"><code>bm_pk_id.${escape(a.siteId)}.&lt;site hash&gt;</code></th><td>Pseudonymous returning-visitor information, after consent.</td><td>${escape(a.cookieVisitorSeconds / 86400)} days</td></tr><tr><th scope="row"><code>bm_pk_ses.${escape(a.siteId)}.&lt;site hash&gt;</code></th><td>Group activity into a visit, after consent.</td><td>${escape(a.cookieSessionSeconds / 60)} minutes</td></tr><tr><th scope="row"><code>bm_pk_ref.${escape(a.siteId)}.&lt;site hash&gt;</code>, when present</th><td>Sanitized referring-site attribution, after consent.</td><td>${escape(a.cookieVisitorSeconds / 86400)} days</td></tr></tbody></table></div><p>The cookie prefix is specific to this website; cookie scope is the current hostname and root path, not the parent <code>.swistak.codes</code> domain. Analytics cookies are not set before consent. This site grants tracker consent in memory and does not use a persistent Matomo consent-grant cookie. The tracker service must not issue additional third-party or HttpOnly tracking cookies. JavaScript can remove only technically accessible cookies in this site’s scope, not HttpOnly cookies or cookies belonging to other hosts.</p>` : '<p>With analytics disabled, this website sets no Matomo analytics cookies. An old privacy preference may remain from an earlier visit; it is not an analytics identifier and does not enable tracking.</p>'}<p>Clearing browser storage removes the remembered choice. If storage is blocked or fails, analytics fails closed rather than treating the failure as consent. Browser controls may also block or remove cookies independently.</p>`,
    },
    {
      id: 'retention',
      title: 'How long information is kept',
      html: `${facts({ 'Support correspondence retention': p.contact.retentionCriteria })}${a.enabled ? `${facts({ 'Raw analytics retention': days(p.analytics.rawRetentionDays), 'Aggregated-report retention': days(p.analytics.aggregatedRetentionDays), 'Analytics proxy/access-log categories': p.analytics.proxyLogData, 'Analytics proxy/access-log retention': days(p.analytics.proxyLogRetentionDays), 'Analytics backup retention': days(p.analytics.backupRetentionDays), 'Access controls': p.analytics.accessControls })}<p>Deleting analytics cookies stops the browser identifier from being used for future activity; it does not delete historical server records or immediately remove data from retained backups. Server deletion and backup expiry follow the configured procedures.</p>` : '<p>No optional analytics records are collected.</p>'}`,
    },
    {
      id: 'contact-and-external-services',
      title: 'Contact and external services',
      html: `<p>If you contact ${contact}, the information you choose to send, your contact details and the correspondence are used to respond and handle the request.</p>${facts({ 'Contact-service provider and processing arrangements': p.contact.providerInformation, 'Legal basis': p.contact.legalBasis, 'Transfer safeguards': p.contact.transferSafeguards })}<p>GitHub Issues and Discussions are public third-party services. Do not post secrets or unnecessary device identifiers. Download and donation links lead to independent services; following them allows those services to process your request under their own policies. Provider badges and fonts are served locally rather than loaded as tracking embeds.</p><p>See <a href="https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement">GitHub’s privacy statement</a>, <a href="https://f-droid.org/en/2024/03/08/privacy-design-of-fdroid.org-webservers.html">F-Droid’s website privacy explanation</a>, <a href="https://liberapay.com/about/privacy">Liberapay’s privacy policy</a> and <a href="https://suppi.pl/polityka_prywatnosci">Suppi’s privacy information</a>. This website does not proxy downloads or modify APKs to measure installations.</p>`,
    },
    {
      id: 'rights',
      title: 'Your rights and requests',
      html: `<p>Depending on the processing and applicable law, you may ask to access, correct, erase or restrict your personal data, object to processing based on legitimate interests, and receive portable data where that right applies. Where processing relies on consent, you can withdraw it at any time without affecting earlier lawful processing.</p><p>Send requests to ${contact}. Explain the relevant website activity or correspondence without sending unnecessary data. We may need proportionate information to identify the relevant records or confirm that they concern you; we do not collect extra identifiers merely to make otherwise unidentifiable activity identifiable.</p><p>You may complain to a relevant supervisory authority. In Poland, this is the <a href="https://uodo.gov.pl/en">President of the Personal Data Protection Office (UODO)</a>. You may also have rights under other applicable laws. This site does not sell personal information, use cross-context advertising, or make automated decisions with legal or similarly significant effects.</p>`,
    },
    {
      id: 'changes',
      title: 'Changes to this policy',
      html: `${facts({ 'Policy version': p.policyVersion, 'Effective date': p.effectiveDate })}<p>Material changes to the analytics purpose or collection require a new consent-notice version; cosmetic website changes do not extend your decision indefinitely.</p>`,
    },
  ];
}
