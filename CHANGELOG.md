# CHANGELOG

## Unreleased

### New

- Added system (Android 13+) language chooser in settings (#56, thanks @Aga-C).
- Added importing and exporting app settings (#51, thanks @Hirdaya-Shrestha).

### Fixed

- Using localized unit strings instead of hardcoded English ones (#63, thanks @Hirdaya-Shrestha).
- Rewritten live updates to use NotificationCompat, to ensure it works well with API 36.
- Turned off force refresh of battery data in OS in Advanced tab, which could cause issues with some battery drivers.

## 1.2.0

### New

- Added the "Advanced" tab showing battery data accessible by root/Shizuku (#27).

### Improved

- Changed font in icons to narrower, so digits are bigger now (#18). 

### Fixed

- The default file name for export now contains the current app name (thanks @Aga-C).
- Long names in setting are now wrapped to the new line (#40, thanks @Aga-C).

### Development

- Removed unused black icon variant.

## 1.1.0

### New

- Added an option to keep logs until they are manually removed (#25, thanks @lrcstars).
- Notification bar icons redesign with new settings (#18).

### Improved

- Chinese translations (thanks @lrcstars).
- Dutch translations (thanks @ltguillaume).
- CSV now exports time with seconds (#19, thanks @lrcstars).

### Fixed

- Fixed the untranslated app name on the launcher (#31, thanks @Aga-C).

### Development

- Rebuilt an icon generation script for new icon types.
- Removed legacy image assets.
- Reordered all localized strings to match order in English (#23).

## 1.0.4

### Improved

- Changed "Good Health" to "Healthy" in notification (#11, thanks @ltguillaume)

### Fixed

- Separated live updates channel from the main notification channel (#10)

## 1.0.3

### Fixed

- Disabled Android dependency metadata in release.

## 1.0.2

### Fixed

- Added missing metadata required for F-Droid release.

## 1.0.1

### Fixed

- Replaced old icon in live updates with the current one.

## 1.0.0

### New

- Added live updates (Android 16+) with a dedicated settings screen.
- Added showing temperature on a notification icon.
- Added support for Android's per-app language chooser.

### Improved

- Rebranded the app from BatteryBot Pro to Battery Monitor.
- Added automated translations for missing strings.
- Human-made Polish translations (thanks @Aga-C).
- Possibility to set custom values for battery alarms (charge levels, temperature).
- Removed unnecessary text from notifications.

### Fixed

- Fixed Android 16 compatibility issues (UI, crash during DND).
- Fixed notification reliability on older Android versions.
- Fixed random timeout crashes.
- Fixed hardcoded strings to use string resources for better localization.

### Development

- Updated target SDK to 37 and upgraded SDK/Gradle toolchain.
- Added release GitHub Action.
- Cleaned up unused resources.
