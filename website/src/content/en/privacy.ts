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
  const entries = Object.entries(values)
    .filter(([, value]) => value != null && value !== '')
    .map(([label, value]) => `<div><dt>${escape(label)}</dt><dd>${escape(value)}</dd></div>`)
    .join('');
  return entries ? `<dl class="privacy-facts">${entries}</dl>` : '';
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
      html: `<p>Loading a page sends the information needed to deliver it to the website infrastructure. This can include your IP address, request time, requested URL, browser information and a referring address supplied by your browser. This information is used to deliver the website and protect it against abuse, separately from optional analytics. Avoid including sensitive information in page URLs.</p>${facts({ 'Website host and content delivery': p.hosting.provider, 'Where information is processed': p.hosting.processingLocations, 'Provider arrangements': p.hosting.processorAgreement, 'Other providers involved': p.hosting.subprocessorInformation })}${facts({ 'Legal basis': p.hosting.deliveryLegalBasis, 'Why this processing is needed': p.hosting.deliveryInterests })}${facts({ 'Information kept in server logs': p.hosting.accessLogData, 'How long server logs are kept': days(p.hosting.accessLogRetentionDays), 'Who can access the logs': p.hosting.accessLogAccessControls, 'How long backups are kept': days(p.hosting.backupRetentionDays) })}${facts({ 'International transfers and safeguards': p.hosting.transferSafeguards })}`,
    },
    {
      id: 'preferences',
      title: 'Your analytics choice',
      html: `<p>${a.enabled ? 'Optional website analytics is available only after you choose “Accept analytics”.' : 'Optional website analytics is disabled. No Matomo tracker is loaded.'} You can use the website and follow download links without accepting analytics. Closing or ignoring a notice, scrolling, or continuing to browse is not consent.</p><p>When analytics is available, “Accept analytics” and “Reject analytics” are offered together. Use <a href="/privacy/#preferences" data-privacy-settings>Privacy settings</a> to change your choice or withdraw consent. Withdrawal stops new analytics requests and removes this site’s analytics cookies where the browser permits it. A request already sent cannot be recalled. Withdrawal does not automatically erase records already held on the analytics server; contact ${contact} to ask about deletion.</p><p>Your choice is valid for ${escape(a.consentDays)} days. If the analytics purposes change, the site asks again. Missing, invalid, expired or outdated choices do not allow analytics. An enabled Global Privacy Control or Do Not Track browser signal is also treated as a refusal, even after an earlier acceptance. Applying GPC to all optional analytics is this website’s voluntary policy.</p><p>Analytics is not loaded when JavaScript is unavailable. All page content, help and download links still work. The privacy controls require JavaScript to change a stored choice.</p>`,
    },
    {
      id: 'analytics',
      title: 'Optional website analytics',
      html: a.enabled
        ? `<p>With your consent, this website uses the dedicated Matomo service at ${escape(new URL(a.matomoUrl).origin)} to understand visits, useful content and interest in download channels. The legal basis for this optional processing is your consent (GDPR Article 6(1)(a)).</p><p>Analytics includes recognised page paths, navigation and visit information, the origin of a referring website, standard browser, operating-system and device-category information, approximate location, first-party visitor identifiers with limited lifetimes, and specific download, source or support-link events.</p><p>Only three approved campaign names are accepted from <code>utm_campaign</code>: <code>app-release</code>, <code>website-launch</code> and <code>project-update</code>. All other query values, URL fragments, unknown page paths and user-provided event text are excluded. Campaign source, medium and keyword values are not collected.</p>${facts({ 'Matomo hosting provider': p.analytics.hostingProvider, 'Where information is processed': p.analytics.processingLocations, 'Provider arrangements': p.analytics.processorAgreement, 'Other providers involved': p.analytics.subprocessorInformation, 'International transfers and safeguards': p.analytics.transferSafeguards })}${p.analytics.ipMaskingBytes == null ? '' : `<p>Matomo removes ${escape(p.analytics.ipMaskingBytes)} bytes from IP addresses and uses the masked address to estimate location. This reduces precision, but visitor identifiers and visit records can still be personal data: this analytics is pseudonymous, not completely anonymous.</p>`}<p>We do not enable user IDs, visitor linking across websites, additional fingerprinting, heatmaps, session recording, form-content tracking, advertising or remarketing. The site does not send app battery data or replay actions taken before consent. Refusing analytics is not reported as an event.</p>${a.consentDimensionId == null ? '' : '<p>When analytics starts after a new acceptance, the first page view includes the version of the consent notice. Accepting is not counted as a separate interaction, and this information is not a complete history of your consent choices.</p>'}<p>A download-link click measures interest in that link, not an installation or application use. GitHub’s aggregate asset counts are a separate measurement.</p>`
        : '<p>This website does not collect optional analytics, load Matomo, or create analytics identifiers or cookies. The site does not perform hidden replacement tracking when analytics is refused or unavailable.</p>',
    },
    {
      id: 'storage',
      title: 'Browser storage and cookies',
      html: `<p>A small first-party localStorage entry remembers your analytics choice, including refusal. It contains only the choice, the notice version, the time of your decision and its expiry. It is not a visitor analytics identifier and is not shared with the owner’s other websites.</p><p>This necessary preference storage lets the site apply the privacy choice you requested; it is separate from optional analytics and does not require you to accept analytics. Where the preference involves personal data, the legal basis is meeting data-protection obligations concerning consent and withdrawal (GDPR Articles 6(1)(c) and 7).</p><p>The choice remains valid for ${escape(a.consentDays)} days. The browser may keep the entry while the site is closed, but expired or outdated choices cannot enable analytics and are removed when the site next checks them. This local record is used to apply your preference; it is not a complete server-side consent log.</p>${a.enabled ? `<div class="table-scroll"><table><caption>Browser storage used on this website</caption><thead><tr><th scope="col">Name</th><th scope="col">Purpose</th><th scope="col">Lifetime</th></tr></thead><tbody><tr><th scope="row"><code>bm.analytics-consent</code> (localStorage)</th><td>Remember your acceptance or refusal, notice version and decision times.</td><td>${escape(a.consentDays)} days of decision validity</td></tr><tr><th scope="row"><code>bm_pk_id.${escape(a.siteId)}.&lt;site hash&gt;</code></th><td>Recognise a returning browser, after consent.</td><td>Up to ${escape(a.cookieVisitorSeconds / 86400)} days</td></tr><tr><th scope="row"><code>bm_pk_ses.${escape(a.siteId)}.&lt;site hash&gt;</code></th><td>Group activity into a visit, after consent.</td><td>Up to ${escape(a.cookieSessionSeconds / 60)} minutes</td></tr><tr><th scope="row"><code>bm_pk_ref.${escape(a.siteId)}.&lt;site hash&gt;</code>, when present</th><td>Remember approved campaign or referring-site information, after consent.</td><td>Up to ${escape(a.cookieVisitorSeconds / 86400)} days</td></tr></tbody></table></div><p>Analytics cookies belong to this website’s hostname and root path, rather than the parent <code>.swistak.codes</code> domain. They are set only after consent. The site does not use a persistent Matomo consent-grant cookie. Browser settings can block or remove cookies; this site can remove only cookies accessible within its own scope.</p>` : '<p>With analytics disabled, this website sets no Matomo analytics cookies. A privacy preference saved on an earlier visit may remain; it does not enable tracking.</p>'}<p>Clearing browser storage removes the remembered choice. If storage is blocked or fails, analytics stays off.</p>`,
    },
    {
      id: 'retention',
      title: 'How long information is kept',
      html: `${facts({ 'Support correspondence': p.contact.retentionCriteria })}${a.enabled ? `${facts({ 'Individual analytics records': days(p.analytics.rawRetentionDays), 'Aggregated analytics reports': days(p.analytics.aggregatedRetentionDays), 'Information in analytics server logs': p.analytics.proxyLogData, 'Analytics server logs': days(p.analytics.proxyLogRetentionDays), 'Analytics backups': days(p.analytics.backupRetentionDays), 'Who can access analytics information': p.analytics.accessControls })}<p>Removing analytics cookies does not remove records already stored on the server or in backups. Withdrawing consent stops new tracking; you can separately request deletion of records that can be linked to you. Backups expire according to the period above.</p>` : '<p>No optional analytics records are collected.</p>'}`,
    },
    {
      id: 'contact-and-external-services',
      title: 'Contact and external services',
      html: `<p>If you contact ${contact}, the information you choose to send, your contact details and the correspondence are used to respond and handle the request.</p>${facts({ 'Contact-service provider and arrangements': p.contact.providerInformation, 'Legal basis': p.contact.legalBasis, 'International transfers and safeguards': p.contact.transferSafeguards })}<p>GitHub Issues and Discussions are public third-party services. Do not post secrets or unnecessary device identifiers. Download and donation links lead to independent services; following them allows those services to process your request under their own policies. Provider badges and fonts are served locally rather than loaded as tracking embeds.</p><p>See <a href="https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement">GitHub’s privacy statement</a>, <a href="https://f-droid.org/en/2024/03/08/privacy-design-of-fdroid.org-webservers.html">F-Droid’s website privacy explanation</a>, <a href="https://liberapay.com/about/privacy">Liberapay’s privacy policy</a> and <a href="https://suppi.pl/polityka_prywatnosci">Suppi’s privacy information</a>. This website does not proxy downloads or modify APKs to measure installations.</p>`,
    },
    {
      id: 'rights',
      title: 'Your rights and requests',
      html: `<p>Your rights depend on the processing and the law that applies to you. Under the GDPR, these can include access, correction, erasure, restriction, an objection to processing based on legitimate interests, and data portability. You may withdraw consent at any time without affecting earlier lawful processing.</p><p>Email ${contact} with the right you want to exercise and enough context to find the records: for example, the email address used for correspondence, an approximate visit date and the pages concerned. We may ask for proportionate information to verify that records concern you. Do not send passwords or unnecessary identity documents. We do not collect extra identifiers just to identify otherwise unidentifiable visits, and cannot promise to locate or delete analytics records that cannot be linked to you.</p><p>For GDPR requests, we normally respond within one month of receiving the request. Where the law permits an extension because of the complexity or number of requests, this can be extended by up to two further months; we will explain the extension and its reasons within the first month.</p><p>You may complain to a relevant supervisory authority, including where you live or work in the EEA. In Poland, this is the <a href="https://uodo.gov.pl/en">President of the Personal Data Protection Office (UODO)</a>. UK visitors may contact the <a href="https://ico.org.uk/make-a-complaint/">Information Commissioner’s Office (ICO)</a>. Other jurisdictions may provide different rights and complaint routes. This site does not sell personal information, use cross-context advertising, or make automated decisions with legal or similarly significant effects.</p>`,
    },
    {
      id: 'changes',
      title: 'Changes to this policy',
      html: `${facts({ 'Policy version': p.policyVersion, 'Effective date': p.effectiveDate })}<p>Material changes to the analytics purpose or collection require a new consent-notice version; cosmetic website changes do not extend your decision indefinitely.</p>`,
    },
  ];
}
