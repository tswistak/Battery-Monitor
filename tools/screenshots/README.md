# Screenshot preparation

Use a dedicated disposable full-system phone emulator running Android 15/API 35 or Android 16/API 36, never an owner's installation. The commands below target only `codes.swistak.batterymonitor.debug`. Follow the JDK 21 / Android SDK prerequisites in [E2E.md](../../E2E.md); do not run the destructive E2E suite to prepare these images.

The screenshot build keeps the application's screens, formatting, charts, notification and widget renderers unchanged. An explicitly selected Gradle init script copies Kotlin sources into ignored `app/build/screenshot-main`, checks four exact input substitutions and includes the helper below. The substitutions supply battery fields, current and remaining charge to the existing monitoring paths, including the separate monitoring/widget process. Each acquisition reads the shared input file afresh, so live emulator properties cannot replace part of the scene. A capture-only resource uses the normal Battery Monitor application label. These mechanisms are absent from ordinary debug and release builds. The init script refuses release/profile task graphs.

## Build and seed

Run from the repository root. Set `JAVA_HOME` to a JDK 21 installation and `ANDROID_HOME` to your Android SDK. Put `$JAVA_HOME/bin`, `$ANDROID_HOME/platform-tools` and `$ANDROID_HOME/emulator` on `PATH`. The build command below overrides any checkout-specific Gradle JDK setting.

Keep the disposable signing key for repeat installations; no production signing material is needed. Create it only if it does not already exist:

```sh
mkdir -p captures/screenshots
if [ ! -e captures/screenshots/local-test.keystore ]; then
  keytool -genkeypair \
    -keystore captures/screenshots/local-test.keystore \
    -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 3650 \
    -dname CN=BatteryMonitorScreenshots -noprompt
fi
./gradlew -Pprofiling -Dorg.gradle.java.home="$JAVA_HOME" -I tools/screenshots/build.gradle :app:assembleDebug \
  --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=1g'
```

The existing `-Pprofiling` property keeps this build from loading release-signing inputs. The requested task is still `assembleDebug`, with the screenshot init script's disposable signer; do not select profile or release tasks.

Set `SERIAL` to the disposable emulator shown by `adb devices -l`. Use an explicit serial for every subsequent command. Install the generated APK, launch it normally once, complete permission/background setup and let startup work settle. Then stop both application processes and take the anchor from this emulator's clock immediately before seeding:

```sh
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" shell am start -n codes.swistak.batterymonitor.debug/codes.swistak.batterymonitor.app.BatteryInfoActivity
# Complete setup in the emulator before continuing.
adb -s "$SERIAL" shell cmd activity stop-app codes.swistak.batterymonitor.debug
adb -s "$SERIAL" shell dumpsys battery set -f ac 1
adb -s "$SERIAL" shell dumpsys battery set -f usb 0
adb -s "$SERIAL" shell dumpsys battery set -f wireless 0
adb -s "$SERIAL" shell dumpsys battery set -f status 2
adb -s "$SERIAL" shell dumpsys battery set -f level 68
adb -s "$SERIAL" shell dumpsys battery set -f temp 330
ANCHOR_MILLIS="$(adb -s "$SERIAL" shell date +%s)000"
adb -s "$SERIAL" shell am broadcast \
  -n codes.swistak.batterymonitor.debug/codes.swistak.batterymonitor.monitoring.ScreenshotFixtureReceiver \
  -a codes.swistak.batterymonitor.debug.SCREENSHOT_SEED \
  --ez disposable true --el anchorMillis "$ANCHOR_MILLIS" --es state charging
adb -s "$SERIAL" shell cmd activity stop-app codes.swistak.batterymonitor.debug
```

Require broadcast `result=-1`, which signals that preparation and its internal assertions passed. Some Android versions display only an opaque parcel size for the result bundle rather than its scenario, count and estimate fields. An error or a successful ADB exit alone is insufficient. Inspect the actual Current State estimate and retrieve the seeded history for timestamp and event-count checks:

```sh
adb -s "$SERIAL" exec-out run-as codes.swistak.batterymonitor.debug \
  cat files/screenshot-history.json > captures/screenshots/history.json
```

The receiver accepts only the `.debug` package on an emulator, requires `disposable=true`, and rejects an anchor more than five minutes old. Both process stops are necessary: ordinary shared preferences are cached separately in the UI and monitoring processes. The internal assertions check both replayed and freshly restored predictor estimates even when the result bundle's fields are hidden.

Use `cmd activity stop-app` for these stops: it reproduces Android's Task Manager stop and removes the whole app from memory, while scheduled jobs remain scheduled. This command is available from API 33. See [Android's Task Manager stop guidance](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping).

Avoid `am force-stop` in this workflow. On Android 15+, relaunching an app from the force-stopped state delivers `BOOT_COMPLETED`; the normal app then logs a reboot and a subsequent charging observation. See [Android's stopped-state changes](https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state). Check the resulting event counts against the fixture after relaunch; do not suppress production boot handling to change a screenshot.

Seeding replaces this disposable installation's history, alarms, settings and predictor data. It imports schema-2 history JSON through the production `DeviceDataBackup` reader. Roughly seven days of two-minute observations use seven distinct daily profiles: overnight, late-morning and evening charging, partial charges, two short top-ups and two days without charging. Charging tapers, overnight idle is gradual, and heavier use varies through the day. Exact plug transitions use the production `STATUS_NEW` encoding; level changes use `STATUS_OLD`, and same-level periodic observations use `STATUS_SAMPLE`. The range boundary is not a fictitious connect/disconnect event.

The imported history includes one synthetic reboot five hours before the fixture's anchor so the Trend widget can show its reboot marker. The predictor replay still uses only the battery observations.

The separate predictor is trained by replaying these observations through the actual `PredictorCore`. Its learned averages and current state version are persisted; a fresh production `Predictor` must then calculate a plausible estimate. Recent session observations are memory-only and are not claimed to survive a restart. The target is manually configured to 80%; the emulator does not detect an OEM charging limit.

The default charging scene ends at 68%, +1,650,000 µA, 4,000 mV, 330 tenths °C and 3,060,000 µAh, corresponding to a 4,500 mAh scenario capacity. The UI derives 6.6 W of battery-side power. To prepare the alternate discharging scene, repeat the stopped-app seed sequence with `--es state discharging`; it ends at 68%, −140,000 µA, 4,000 mV, 290 tenths °C and 3,060,000 µAh. Its final hour falls gradually from about 71% to 68%, consistent with a light-use estimate of roughly 22 hours. Do not combine readings from the two scenes.

The battery commands above align the real SystemUI battery indicator with the charging scene. For the alternate scene, use the following overrides before seeding, keeping level 68 and USB/wireless power off:

```sh
adb -s "$SERIAL" shell dumpsys battery set -f ac 0
adb -s "$SERIAL" shell dumpsys battery set -f status 3
adb -s "$SERIAL" shell dumpsys battery set -f temp 290
```

Current, voltage and remaining charge come from the development input seam; do not try to set them through emulator battery commands. Inspect the resulting system indicator and application readings, rather than relying only on command exit codes.

Every seed checks timestamp ordering/range, observation gaps, gradual temperature, value bounds, final values, actual transition counts and both replayed and restarted predictor estimates. Generated evidence is stored privately in `files/screenshot-history.json` and `files/screenshot-input.json` inside this disposable package. The phone fixture uses no owner's history or hardware identifiers.

## Capture and cleanup

Use English (United States), Celsius, default font scale, ordinary phone density and consistent timezone. Match SystemUI's simulated battery level/status to the selected scene; the helper does not change SystemUI. Launch normally, complete permission/background setup, and verify the actual Current State, latest history, notification and widgets before capturing. Use the actual launcher widget workflow and normal application settings to change themes or notification content.

Set the disposable emulator's actual display with `adb -s "$SERIAL" shell wm size 1080x2400`. Rotate it for landscape image 6. Use font scale 1.0 and density 420; image 4 uses density 360. Restore overrides with `adb -s "$SERIAL" shell wm size reset` and `adb -s "$SERIAL" shell wm density reset` during cleanup.

For image 4, use Pixel Launcher on an Android 16 emulator at 1080 × 2400, effective density 360 (`adb -s "$SERIAL" shell wm density 360`), and font scale 1.0. On a four-column workspace, place Status and Measurements side by side at 2 × 2 cells each and Trend below them at 4 × 2, set to **Last 24 hours**. Leave the unused rows empty. Use a blank launcher page; stage and resize the cards on another page if the launcher needs room for their initial sizes. Dismiss the resize handles and confirm the battery illustration, all four readings, and the chart axes and event markers are visible before capturing the native PNG.

If SystemUI demo mode is used to hide unrelated notification icons, set its clock from this emulator's actual time immediately before each capture and keep its battery level/status equal to the real battery overrides. Reseed before final captures if lengthy launcher preparation has aged the fixture; this uses the same production import/predictor checks and does not alter widget configuration.

```sh
adb -s "$SERIAL" exec-out screencap -p > "$OUTPUT"
```

Inspect each native PNG for readable controls, coherent values/time, correct theme/locale, actual event counts and absence of dialogs, helper UI or unrelated notifications. A successful build is not visual verification. Store the final English images in `fastlane/metadata/android/en-US/images/phoneScreenshots/`; the README uses images 1–4 directly.

When finished, reset battery simulation and exit SystemUI demo mode if they were used, restore any changed rotation/display/locale/animation settings, remove this task's temporary files/installations, and stop only the emulator started for this task:

```sh
adb -s "$SERIAL" shell dumpsys battery reset
adb -s "$SERIAL" shell am broadcast -a com.android.systemui.demo --es command exit
adb -s "$SERIAL" uninstall codes.swistak.batterymonitor.debug
adb -s "$SERIAL" emu kill
```

Keep final images and this guide. APKs, emulator disks, disposable keys and device dumps belong in ignored build/capture output, outside tracked metadata. A later ordinary build without `-I tools/screenshots/build.gradle` compiles the unmodified production inputs and includes no screenshot receiver.

## Asset inventory

Use English (United States), Europe/Warsaw time, Celsius, and font scale 1.0 for the phone images. Use density 420 except for image 4, which uses 360. Select the Battery Blue palette and the appearance listed below through the app's real settings. For image 5, expand the ordinary persistent notification with Live Update set to “Never”; an API 36 emulator provides the required SystemUI layout. Capture native PNGs at the listed display sizes.

| File | Subject | Size | Appearance |
| --- | --- | --- | --- |
| [1.png](../../fastlane/metadata/android/en-US/images/phoneScreenshots/1.png) | Current battery readings and charging estimate to the manual 80% target | 1080 × 2400 | Light |
| [2.png](../../fastlane/metadata/android/en-US/images/phoneScreenshots/2.png) | Battery-level history over 24 hours | 1080 × 2400 | Light |
| [3.png](../../fastlane/metadata/android/en-US/images/phoneScreenshots/3.png) | Grouped battery alarms with enabled and disabled thresholds | 1080 × 2400 | Light |
| [4.png](../../fastlane/metadata/android/en-US/images/phoneScreenshots/4.png) | Status and Measurements (2 × 2 each), with 24-hour Trend (4 × 2) below | 1080 × 2400 | Light |
| [5.png](../../fastlane/metadata/android/en-US/images/phoneScreenshots/5.png) | Expanded ordinary persistent battery notification | 1080 × 2400 | SystemUI |
| [6.png](../../fastlane/metadata/android/en-US/images/phoneScreenshots/6.png) | Landscape fullscreen comparison of battery level and temperature | 2400 × 1080 | Dark |
| [7.png](../../fastlane/metadata/android/en-US/images/phoneScreenshots/7.png) | History Events with Plugged in, Unplugged and Measurements selected: 3 of 9 event types | 1080 × 2400 | Light |
| [8.png](../../fastlane/metadata/android/en-US/images/phoneScreenshots/8.png) | Current battery readings and manually configured 80% charging target | 1080 × 2400 | Dark |

The [tablet History image](../../fastlane/metadata/android/en-US/images/tenInchScreenshots/1.png) shows the last 24 hours at 1920 × 1200 in landscape, with light appearance, dynamic colors, English application text and Polish SystemUI. Do not seed, wipe or modify the owner's tablet to prepare screenshots.
