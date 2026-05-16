<p align="center">
  <img src="docs/assets/app-icon.png" alt="Battery Monitor app icon" width="128" height="128" />
</p>

<h1 align="center">Battery Monitor</h1>

Battery Monitor is a classic, no-nonsense Android battery app focused on clarity, reliability, and control.

See battery status at a glance, get meaningful notifications, and track battery behavior over time.

## Highlights

- Live battery percentage and status in notifications
- Detailed current battery info (charge, temperature, voltage, health, plug state)
- Configurable battery alarms (for charge levels, temperature, and more)
- Battery history/logging with CSV export
- Home screen widgets
- Privacy-friendly by design (no personal data collection)

## Features

### Main Notification
- Persistent battery status in the notification area
- Flexible display options for percentage/time/status content
- Designed for quick readability

### Battery Alarms
- Alerts for full charge
- Alerts when charge drops below or rises above selected levels
- Optional temperature and health-related alerts

### History and Logs
- Built-in battery event logging
- Filter and review battery events
- Export logs to CSV for analysis

### Widgets
- Circle and full-size widget variants
- Keep battery info visible without opening the app

## Privacy

Battery Monitor does not collect, store, or transmit personal data.

## Project Background

This project is a fork of [Battery Indicator Pro / BatteryBot Pro](https://github.com/darshan-/Battery-Indicator-Pro), with continued development and rebranding.

Main goal of the fork is to maintain and enhance the app to ensure it works well on modern Android versions.

Huge thanks to the original authors and contributors.

## Build

Build with Android Studio or from CLI:

```bash
./gradlew assembleDebug
```

## License

GNU GPL v3.0-or-later.  
See [LICENSE](LICENSE).
