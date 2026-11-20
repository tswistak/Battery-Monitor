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

function paragraphs(...values: (string | null)[]): string {
  return values
    .filter(Boolean)
    .map((value) => `<p>${escape(value)}</p>`)
    .join('');
}

function facts(values: Record<string, string | null>): string {
  const entries = Object.entries(values)
    .filter(([, value]) => value)
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
  const visitorDays = escape(a.cookieVisitorSeconds / 86_400);
  const sessionMinutes = escape(a.cookieSessionSeconds / 60);
  const reportRetention =
    p.analytics.aggregatedRetentionDays === 732
      ? 'Approximately 24 months'
      : days(p.analytics.aggregatedRetentionDays);

  return [
    {
      id: 'controller',
      title: 'Who is responsible',
      html: `<p>${escape(p.controller.name)} operates the Battery Monitor Android app and website and is the controller for the personal data we collect. For privacy questions and requests, email ${contact}.</p>`,
    },
    {
      id: 'android-app',
      title: 'The Android application',
      html: `<p>Battery Monitor uses battery readings on your device to show status, record history, make estimates and trigger alarms. Your history, learned estimates, settings and alarms stay on the device. Diagnostics can include device details and identifiers such as the battery serial number. Root and Shizuku access are optional. The app does not automatically upload monitoring or diagnostic data to us.</p>
        <p>Android protects the app’s private storage. The app opts out of Android’s automatic cloud backup and device-to-device transfer. You can export history manually or automatically to a folder you choose, back up history, settings, alarms and learned estimates, or share diagnostics. The app does not encrypt these files. Your chosen storage provider or recipients may receive them. See <a href="#contact-and-external-services">Contact and external services</a> if you send them to us.</p>
        <p>While logging is enabled, history retention is configurable and defaults to 28 days. Pending exports can delay deletion. You can clear history in the app. Learned estimate data is stored separately. Clearing app storage in Android Settings or uninstalling removes app-managed data. Delete exported and shared copies separately, where you still control them.</p>`,
    },
    {
      id: 'website-delivery',
      title: 'Delivering and protecting the website',
      html: `<p>We use ${escape(p.hosting.provider)} to host and deliver this website. Your browser sends technical request data when you load a page. This data is needed to serve the page.</p>
        ${paragraphs(
          p.hosting.accessLogData,
          p.hosting.deliveryLegalBasis,
          p.hosting.processingLocations,
          p.hosting.transferSafeguards,
        )}
        <p>See <a href="https://bunny.net/gdpr/">Bunny’s data protection information</a> and <a href="https://bunny.net/gdpr/sub-processors/">its list of service providers</a>.</p>`,
    },
    {
      id: 'preferences',
      title: 'Your analytics choice',
      html: a.enabled
        ? `<p>Analytics starts only if you choose “Accept analytics”. You can refuse and still use the whole site, including downloads. We also respect Global Privacy Control and Do Not Track signals.</p>
          <p>Use Privacy settings to change your choice at any time. Withdrawing consent stops future collection and removes this site’s analytics cookies where the browser allows it. Existing server records remain until they expire or we delete them on request. To request deletion, email ${contact}.</p>`
        : '<p>The whole site, including downloads, is available without analytics.</p>',
    },
    {
      id: 'analytics',
      title: 'Optional website analytics',
      html: a.enabled
        ? `<p>With your consent (GDPR Article 6(1)(a)), we use Matomo to understand which pages and download options people use. It measures visits, referral sources, browser and device information, approximate location and selected link clicks, including download and support links. First-party analytics cookies help recognise a returning browser.</p>
          ${
            p.analytics.ipMaskingBytes != null && p.analytics.maskedIpGeolocation
              ? '<p>Matomo uses masked IP addresses to estimate location. Masking reduces the risk of identification, but visit records can still be personal data.</p>'
              : ''
          }
          ${paragraphs(
            p.analytics.hostingProvider,
            p.analytics.processingLocations,
            p.analytics.transferSafeguards,
          )}
          <p>See <a href="https://www.ovhcloud.com/en/personal-data-protection/legal-privacy-security/">OVHcloud’s data protection information</a>.</p>`
        : '<p>Optional website analytics is disabled. This website does not load Matomo or set analytics cookies.</p>',
    },
    {
      id: 'storage',
      title: 'Browser storage and cookies',
      html: `<p>When analytics is available, we save your choice in your browser to apply it on later visits. Where it involves personal data, this helps us meet our obligations around consent and withdrawal (GDPR Articles 6(1)(c) and 7).</p>
        <div class="table-scroll"><table>
          <caption>Browser storage used on this website</caption>
          <thead><tr><th scope="col">Category</th><th scope="col">Purpose</th><th scope="col">Duration</th></tr></thead>
          <tbody>
            <tr><th scope="row">Analytics preference</th><td>Remember your choice.</td><td>${escape(a.consentDays)} days</td></tr>
            ${
              a.enabled
                ? `<tr><th scope="row">Returning-visitor cookie</th><td>Recognise a returning browser.</td><td>Up to ${visitorDays} days</td></tr>
                <tr><th scope="row">Session cookie</th><td>Group activity into one visit.</td><td>Up to ${sessionMinutes} minutes</td></tr>
                <tr><th scope="row">Referrer cookie, when used</th><td>Remember where the visit came from.</td><td>Up to ${visitorDays} days</td></tr>`
                : ''
            }
          </tbody>
        </table></div>
        ${
          a.enabled
            ? '<p>Analytics cookies are first-party and require consent. Clearing browser storage removes the saved choice.</p>'
            : '<p>A choice saved on an earlier visit may remain in your browser until you clear it.</p>'
        }`,
    },
    {
      id: 'retention',
      title: 'How long information is kept',
      html: `${facts({
        'CDN request logs': days(p.hosting.accessLogRetentionDays),
        'Bunny operational backups':
          p.hosting.backupRetentionPolicy ?? days(p.hosting.backupRetentionDays),
        'Support correspondence': p.contact.retentionCriteria,
        ...(a.enabled && {
          'Individual analytics records': days(p.analytics.rawRetentionDays),
          'Aggregated analytics reports': reportRetention,
          'Analytics technical logs': days(p.analytics.proxyLogRetentionDays),
          'Analytics backups': days(p.analytics.backupRetentionDays),
        }),
      })}${
        a.enabled
          ? paragraphs(
              p.analytics.proxyLogData,
              'These logs serve our legitimate interest in securing and maintaining the analytics service (GDPR Article 6(1)(f)).',
            )
          : ''
      }`,
    },
    {
      id: 'contact-and-external-services',
      title: 'Contact and external services',
      html: `<p>Contacting us is optional. We use the email address, message and any attachments you send to answer and handle your request. We need enough information to respond.</p>
        ${paragraphs(p.contact.providerInformation, p.contact.legalBasis, p.contact.transferSafeguards)}
        <p>See the <a href="https://cloud.google.com/terms/data-processing-addendum">Google Cloud Data Processing Addendum</a> and <a href="https://workspace.google.com/terms/subprocessors/">Google Workspace’s list of subprocessors</a>.</p>
        <p>When you follow external links, those services handle your data under their own policies: <a href="https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement">GitHub</a>, <a href="https://f-droid.org/en/2024/03/08/privacy-design-of-fdroid.org-webservers.html">F-Droid</a>, <a href="https://policies.google.com/privacy">Google Play</a>, <a href="https://liberapay.com/about/privacy">Liberapay</a> and <a href="https://suppi.pl/polityka_prywatnosci">Suppi</a>. GitHub Issues and Discussions are public. Google handles any Google Play purchase. Battery Monitor does not collect payment details.</p>`,
    },
    {
      id: 'rights',
      title: 'Your rights and requests',
      html: `<p>Under the GDPR you can ask to access, correct or erase your personal data, restrict its use, or receive a portable copy where applicable. <strong>You can object to processing based on legitimate interests.</strong> You can withdraw consent at any time. Processing before withdrawal remains lawful where consent was valid.</p>
        <p>Email ${contact} to exercise a right or ask for a copy of the applicable international transfer safeguards. You can also complain to your data protection authority, including the <a href="https://uodo.gov.pl/en">Polish Personal Data Protection Office (UODO)</a> or the <a href="https://ico.org.uk/make-a-complaint/">UK Information Commissioner’s Office (ICO)</a>, as applicable.</p>
        <p>The app and website make no automated decisions with legal or similarly significant effects.</p>`,
    },
    {
      id: 'changes',
      title: 'Changes to this policy',
      html: `<p>Version ${escape(p.policyVersion)}.${
        p.effectiveDate
          ? ` Effective <time datetime="${escape(p.effectiveDate)}">${escape(p.effectiveDate)}</time>.`
          : ''
      } We ask for consent again if we make material changes to how we use analytics.</p>`,
    },
  ];
}
