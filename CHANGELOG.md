# CHANGELOG

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
