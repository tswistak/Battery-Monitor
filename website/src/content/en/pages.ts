export const homeCopy = {
  title: ['Your battery,', 'with context.'],
  productLine: 'Open-source battery monitoring for Android',
  introduction:
    'Understand your Android battery with clear readings, a useful history, and alarms you choose.',
  appPreviewLabel: 'Inside Battery Monitor',
  current: {
    title: ["See what's", 'happening now.'],
    description: 'Charge, temperature and time remaining. A clearer picture in one place.',
    imageAlt: 'Battery Monitor current battery readings',
  },
  history: {
    title: ['Look back.', 'See the pattern.'],
    description:
      'Look back through recorded readings. Follow charging events and explore the period that matters.',
    legacyDescription:
      'Look back through stored battery records and understand charging over time.',
    imageAlt: 'Battery Monitor history chart',
    legacyImageAlt: 'Battery Monitor stored battery records',
  },
  alarms: {
    title: ['Your thresholds.', 'Your reminders.'],
    description: 'Choose the battery levels you want to hear about.',
    imageAlt: 'Battery Monitor battery alarm thresholds',
  },
  privacy: {
    title: ['Local data.', 'Open code.'],
    android: 'Android',
    minimumVersionSuffix: '.0 or later.',
    deviceReadings: 'Available readings depend on your device.',
    description:
      'The Battery Monitor Android app processes and stores battery readings on your device, without uploading them to external servers. You decide when to export or share files.',
    sourceLink: 'Explore the source',
    licenseLink: 'Read the license',
    privacyLink: 'App & website privacy',
  },
  faqTitle: 'Frequently asked questions',
  moreQuestions: 'More questions',
};

export const aboutCopy = {
  introduction:
    'A clearer view of your Android battery, with open code you can inspect and improve.',
  historyTitle: 'Built on an open-source history',
  history:
    'Battery Monitor is maintained by Tomasz Świstak. It continues the Battery Indicator Pro / BatteryBot Pro project originally developed by Darshan Computing, LLC.',
  contribution: 'Contributions, useful bug reports and translations help the project improve.',
  contributionLink: 'Read the contribution guide',
  contributionAlternative: 'or',
  supportLink: 'support continued development',
  licensesTitle: 'Licenses and notices',
  licenses:
    'Original website code and original website text use GPL-3.0-or-later. App-derived text, inherited artwork, provider badges, fonts and dependencies retain their applicable notices.',
  sourceLink: 'Source code',
  gplLink: 'GPL-3.0 license',
  applicationLicenseLink: 'Application license',
  assetNoticesLink: 'Website asset notices',
  fontLicenseLink: 'Roboto OFL-1.1',
  artworkLicenseLink: 'CC BY-SA 3.0',
  artwork:
    'Inherited artwork attribution: The Android application Battery Indicator Pro by Darshan Computing, LLC. Applicable inherited and modified artwork remains under CC BY-SA 3.0; see',
  artworkNoticesLink: 'artwork notices',
  providerNotices:
    "F-Droid's badge is CC BY-SA 3.0. GitHub's Octicons use MIT. Liberapay artwork uses CC0. Google Play, Suppi and provider branding retain their respective terms. Local badge copies do not imply affiliation or store availability.",
};

export const downloadCopy = {
  introduction: 'Open-source battery monitoring for Android',
  minimumVersionSuffix: '.0 and later.',
  currentVersion: 'Current version',
  published: 'Published',
  apkLink: 'Get the APK',
  signatureLink: 'Detached signature',
  releaseLink: 'GitHub release notes & assets',
  checksum: 'APK SHA-256',
  channelsTitle: 'Choose your distribution channel',
  channels: "F-Droid's available version may differ from GitHub. Google Play is coming soon.",
  setupTitle: 'Before you start',
  updates:
    'Keep using your existing distribution channel when updating. Packages from different channels may use different signing keys.',
  firstRun:
    'Open the app after installation and review notification permissions if you want battery notifications and alarms. Device background restrictions can affect updates.',
  monitoring: 'Ordinary monitoring needs neither root nor Shizuku. See',
  helpLink: 'Help & FAQ',
  helpContext: 'for setup and troubleshooting.',
};

export const supportCopy = {
  title: 'Help shape what comes next.',
  description:
    'If Battery Monitor is useful to you, your support helps fund the time and care behind its development.',
  thanks: 'Every contribution is appreciated.',
  githubLink: 'Sponsor on GitHub',
  liberapayAlt: 'Donate using Liberapay',
  suppiAlt: 'Support on Suppi — Przybij piątkę',
  suppiAvailability: 'Poland only',
};

export const featureCopy = {
  title: 'More than just battery percentage',
  mediaLabel: 'Additional app features',
  notifications: {
    title: 'Notifications & Live Updates',
    legacyTitle: 'Battery notifications',
    description:
      'Keep battery information visible without opening the app. Live Updates work on compatible Android 16+ devices; notification behavior can vary by manufacturer.',
    legacyDescription:
      'Keep battery information visible in your notification shade. Available content and delivery depend on Android and your device.',
    imageAlt: 'Expanded Battery Monitor notification',
    videoLabel: 'Open and close a Battery Monitor notification to see the battery readings',
    transcript:
      'In the Android notification shade, expand the Battery Monitor notification. It shows the charging estimate, charging duration, health, temperature, voltage, current and remaining charge. Collapse the notification to return to its compact view.',
    caption: 'Battery information in your notification shade.',
  },
  widgets: {
    title: 'Home-screen widgets',
    description:
      'Choose configurable widgets for battery status, measurements or trends. Put the information you use most on your home screen.',
    legacyDescription:
      'Put battery information on your home screen with the widgets available in this release.',
    imageAlt: 'Battery Monitor home-screen widgets',
    videoLabel: 'Swipe between home screens with Battery Monitor widgets',
    transcript:
      'Swipe between home screens to see Battery Monitor status, measurement and history widgets. The status widget is widened to show the charge percentage and battery graphic. The measurement widget keeps its default size. The history chart fills the home-screen grid width while keeping its default height. The sequence moves through all three screens, then returns through the second screen to the first.',
    caption: 'Battery information, at a glance.',
  },
  backups: {
    title: 'Exports & backups',
    description:
      'Export history as CSV or JSON, manually or automatically. Keep backups of settings, alarms, history and learned prediction data.',
    legacyDescription:
      'Keep copies of your settings and alarms. Export the battery records supported by this release.',
    imageAlt: 'Export battery history as CSV or JSON',
    videoLabel: 'Browse battery history, export a CSV and open the data in a separate viewer',
    transcript:
      'In History, switch to Events and scroll through the battery readings. Open the menu, choose Export logs, keep CSV selected and export the selected range. Save the file, then open it in a separate CSV viewer. The table shows the recorded charge percentage, temperature, voltage, current and remaining charge.',
    caption: 'Export a CSV, then open it in a separate viewer.',
    exportFormats: 'CSV · JSON',
    legacyExports: 'History exports',
    history: 'Battery history',
    settings: 'Settings & alarms',
    settingsDescription: 'Back up what you configured.',
    fallbackCaption: 'Your data, ready to take with you.',
  },
  advanced: {
    title: 'Optional advanced readings',
    description:
      'Root or Shizuku can provide additional data on compatible devices. Ordinary battery monitoring works without either.',
    imageAlt:
      'Side-by-side unfiltered Raw diagnostics, with 24 device values without root on the left and 51 with root on the right',
    caption: 'More device data with root. Availability depends on your device.',
    withoutRoot: 'Without root',
    withRoot: 'With root',
    standardCount: '24 raw device values',
    rootCount: '51 raw device values',
    viewLarger: 'View comparison larger',
    videoLabel:
      'Scroll all raw values side by side, without root on the left and with root on the right',
    transcript:
      'The unfiltered Raw diagnostics view is shown side by side on the same device: without root on the left, with root on the right. Both scroll through the complete lists and return to the top. The ordinary view has 24 raw device values. Root provides 51, including additional battery-service values and 21 kernel power-supply entries. The 14 prediction fields shown on both sides are shared app data and are not included in those device counts. The available readings depend on the device.',
    root: 'Root',
    alternative: 'or',
    shizuku: 'Shizuku',
    readings: 'Supported device readings',
    accessDescription: 'Optional access. Ordinary monitoring works without it.',
    fallbackCaption: 'Extra readings depend on the device.',
  },
  fallbackCaption: 'Availability depends on Android and the device.',
};
