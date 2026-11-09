export type FAQCapability =
  'alarms' | 'history' | 'widgets' | 'liveUpdates' | 'backups' | 'privileged';

export interface FAQItem {
  id: string;
  question: string;
  html: string;
  capability?: FAQCapability;
}

export const homeFaq: readonly FAQItem[] = [
  {
    id: 'faq-root',
    question: 'Does Battery Monitor require root?',
    html: '<p>No. Ordinary battery monitoring works without root. The <a href="/help/battery-readings/">battery readings guide</a> explains device-dependent measurements and any optional access supported by your app version.</p>',
  },
  {
    id: 'faq-privacy',
    question: 'Does it collect or upload my battery data?',
    html: '<p>The Android app processes and stores monitoring data on your device, without uploading it to external servers. Files you choose to export or share may contain device information. <a href="/privacy/#android-app">Read about app privacy</a>.</p>',
  },
  {
    id: 'faq-charging-stop',
    question: 'Can it automatically stop charging at 80%?',
    html: '<p>No. Battery Monitor does not start or stop charging. An estimate target or alarm threshold is not a hardware charging cutoff. <a href="/help/charging-targets/">Understand estimates and charging targets</a>.</p>',
  },
  {
    id: 'faq-unavailable',
    question: 'Why are some battery readings unavailable?',
    html: '<p>Android and manufacturers expose different battery information. Some measurements are unavailable on particular devices. <a href="/help/battery-readings/">Understand available readings</a>.</p>',
  },
];

export const helpFaq: readonly FAQItem[] = [
  ...homeFaq,
  {
    id: 'faq-estimates',
    question: 'Why do charging or discharging estimates seem inaccurate?',
    html: '<p>Estimates are approximate. Available observations, your usage and charging conditions affect the result; learned averages can become more useful with continued use. In <strong>Settings → Time estimates → Estimate Type</strong>, try another supported method and read its explanation. No method can guarantee a fixed completion time.</p><p><a href="/help/charging-targets/">Read about prediction methods and targets</a>.</p>',
  },
  {
    id: 'faq-clear-logs',
    question: 'Does clearing history affect time estimates?',
    capability: 'history',
    html: '<p>No. Prediction data is separate from history logs, so clearing history does not reset learned estimates. <a href="/help/history-and-backups/">Understand history and backups</a>.</p>',
  },
  {
    id: 'faq-live-updates',
    question: 'Why is the status-bar chip missing or not working?',
    capability: 'liveUpdates',
    html: '<p>Live Updates require Android 16 or later, a compatible device and the relevant system notification settings. Check that notifications and Live Updates are allowed for Battery Monitor. Switching Live Updates off and on again may help when the system has not applied a change.</p><p>Android 16 alone does not guarantee support: manufacturers can limit Live Updates or which apps may use them. The app cannot override those restrictions. <a href="/help/notifications/">Check notification settings and limitations</a>.</p>',
  },
  {
    id: 'faq-status-bar-icon',
    question: 'Why does the status-bar icon not show percentage or temperature?',
    html: '<p>On Android 16 or later, some system variants restrict custom notification icons. The percentage or temperature icon settings may remain available without producing the expected result on that device. Their presence does not guarantee that Android will display a custom icon.</p><p>Notification presentation depends on Android and your device. <a href="/help/notifications/">Read about notifications and status-bar behavior</a>.</p>',
  },
  {
    id: 'faq-current-multiplier',
    question: 'Why does battery current look too small, too large or reversed?',
    html: '<p>Some devices report current in unexpected units or with the opposite sign. Open <strong>Settings → Current state → Multiplier</strong> to correct the displayed reading, or try <strong>Detect automatically</strong> when it is available. If the value is about 1,000 times too small or too large, the unit scale may be wrong.</p><p>As rough device references, active charging is often around +500 to +5,000 mA and ordinary discharge around −100 to −3,000 mA. These are not required operating ranges: values near zero can be normal when the battery is nearly full or the device is idle, and automatic detection may be unavailable for the current reading. <a href="/help/battery-readings/">Understand current measurements and correction</a>.</p>',
  },
  {
    id: 'faq-google-play',
    question: 'Will Battery Monitor be available on Google Play?',
    html: '<p>Google Play support is coming soon. For now, use the official F-Droid listing or GitHub releases. <a href="/download/">See the available distribution channels</a>.</p>',
  },
];

export function getFaq(
  capabilities?: Partial<Record<FAQCapability, boolean>>,
  scope: 'help' | 'home' = 'help',
): readonly FAQItem[] {
  const items = scope === 'home' ? homeFaq : helpFaq;
  return items.filter(
    (item) =>
      !item.capability || capabilities === undefined || capabilities[item.capability] === true,
  );
}
