# CHANGELOG

## 1.0.0

### New

- Added live updates (Android 16+) with a dedicated settings screen.
- Added showing temperature on a notification icon.
- Added support for Android's per-app language chooser.

### Improved

- Rebranded the app from BatteryBot Pro to Battery Monitor.
- Added automated translations for missing strings.
- Possibility to set custom values for battery alarms (charge levels, temperature).

### Fixed

- Fixed Android 16 compatibility issues (UI, crash during DND)
- Fixed notification reliability on older Android versions.
- Fixed random timeout crashes.
- Fixed hardcoded strings to use string resources for better localization.

### Development

- Updated target SDK to 37 and upgraded SDK/Gradle toolchain.
- Added release GitHub Action.
