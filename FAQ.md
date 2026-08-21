# Frequently Asked Questions

Here you can find answers to the most common questions asked about Battery Monitor.

## Discharge/charge estimations don't look good, how can I fix them?

Estimations get better with time — the longer you use the app, the more accurate they will be. However, if it doesn't help, you may change the way how the app estimates time: Settings → Time estimates → Estimate Type. In the app's built-in help, there are short descriptions of how each estimation type works.

## Does clearing logs affect estimations?

No, estimations aren't counted based on logs.

## Why can't I use a status bar chip? Why doesn't it work?

1. If you don't see this option, it means that you don't have at least Android 16.
2. Check if "Live Updates" are enabled for the app's notifications in device settings.
3. You can always try to turn them off and on again, just in case.
4. There's a chance that even if you have at least Android 16, your system doesn't support Live Updates or doesn't support them for all apps (e.g., some Samsung devices). Sometimes it's possible to turn them on via the device's Developer options.

## The status bar icon doesn't show percentage or temperature. Why?

Starting from Android 16, not every OS gives a possibility to change the notification icon, to stick to Material 3 Expressive design guidelines. Instead of allowing apps to change the icon, Android now promotes using Live Updates for status updates.

The settings are left, in case it changes in the future, or for devices that don't follow those guidelines. However, they're not expected to work properly on Android 16+.

## Battery current seems too small/large. What can I do?

In Settings → Current state you may find Multiplier, which you can either set by yourself or have the app to detect the proper one automatically. Generally, charging should return values around 500 to 5000 mA, while discharging -100 to -3000 mA.

## Will you release the app on Google Play?

Versions 1.x no, as they have outdated looks based on the original BatteryBot. As for the versions 2.x, maybe. We will see.