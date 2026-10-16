# E2E execution guide

Run these commands from the repository root. E2E execution is separate from the ordinary instrumented suite.

## Requirements and emulator setup

Use JDK 21, Node.js 22.19+ from the Node 22 release line or Node 24+, npm 10 or newer, `unzip`, and the Android SDK command-line tools. Set `ANDROID_HOME` and put `platform-tools`, `emulator` and the command-line tools' `bin` directory on `PATH`. The project requires the API 37 compilation platform; the commands below use an API 35 Google APIs emulator. Accept SDK licenses with `sdkmanager --licenses`.

For an Intel/AMD Linux or Intel macOS host:

```sh
sdkmanager "platform-tools" "emulator" "platforms;android-37.0" "build-tools;37.0.0" "system-images;android-35;google_apis;x86_64"
avdmanager create avd --name battery-monitor-tests --package "system-images;android-35;google_apis;x86_64" --device pixel_2
emulator -avd battery-monitor-tests -wipe-data -no-snapshot -no-boot-anim -noaudio
```

Keep the emulator running and use a second terminal for tests. On Apple Silicon, use an available `arm64-v8a` Google APIs image instead of `x86_64`. Linux requires working KVM acceleration and access to `/dev/kvm`. Avoid a Play Store image. Use English (United States) for stable application and DocumentsUI labels. Select one emulator with `ANDROID_SERIAL` if other devices are connected.

**E2E wipes application data and changes emulator battery state. Use a dedicated test emulator, never your personal installation.** Physical devices are rejected. The suite also sets the emulator timezone to Europe/Warsaw for historical CSV timestamps and locks portrait rotation.

## Run E2E

```sh
npm ci --prefix e2e
E2E_ALLOW_WIPE=true npm run e2e --prefix e2e
```

`npm ci` installs the locked project dependencies, including Appium 3, WebdriverIO and the Android UiAutomator2 driver. WebdriverIO manages the local Appium server through its Appium service; no global Appium installation or separately started server is needed.

To run one scenario, use WebdriverIO's standard spec selection:

```sh
E2E_ALLOW_WIPE=true npm run e2e --prefix e2e -- --spec ./specs/released-upgrade-running.e2e.ts
```

Add `--logLevel debug` for detailed runner output. Each scenario is a separate TypeScript file under `e2e/specs/`, with its steps and expected results in the test itself.

The E2E command prepares the pinned APK inputs and disposable signing, then runs Gradle against the current checkout before WebdriverIO starts the tests. Each run builds the latest application and helper APKs automatically. Generated APKs remain under `app/build/`, `e2e/helper/build/` and `e2e/build/`. `E2E_ALLOW_WIPE=true` explicitly acknowledges the destructive test setup.

The test build retains the released application ID, `codes.swistak.batterymonitor`, rather than the normal `.debug` suffix. It uses the disposable signing identity and increases the configured version code by one for an ordered upgrade. No production signing secret is required. Re-signing changes the historical APK's signing envelope; the harness verifies that its actual non-signature payload remains unchanged, then checks both APKs' IDs, signatures, version ordering and legacy manifest/provider contracts.

To supply a local original APK:

```sh
HISTORICAL_APK=/absolute/path/battery-monitor-1.7.3.apk E2E_ALLOW_WIPE=true npm run e2e --prefix e2e
```

Its SHA-256 and size must match `e2e/fixtures.json`. Use `npm run prepare:apks --prefix e2e` to diagnose download/signing/build preparation separately. SDK Build Tools must include `aapt2` and `apksigner`.

## Fixture inputs

The five immutable backup fixtures under `e2e/fixtures/historical-1.7.3/` were captured from the actual 1.7.3 exporters. Their provenance manifest pins the source APK and each file's SHA-256. Changed or missing bytes fail the normal suite. Never regenerate historical fixtures with the current exporter.

Set `NEW_UI_BASELINE_APK` and `NEW_UI_BASELINE_SHA256` to supply a genuine earlier new-UI APK with the production package ID and a lower version code. Preparation re-signs a separate copy with the test key. If this optional input is omitted, its test is reported as **NOT EXECUTED**; set `E2E_REQUIRE_NEW_UI_BASELINE=true` to require it. Two builds of the same source revision are not valid cross-version inputs. Keep APK inputs outside source control.

## Reports and troubleshooting

Reports are collected under `e2e/build/reports/`: WebdriverIO JUnit XML in `junit/`, JSON reports in `json/`, Appium server logs in `appium/`, and failure screenshots, UI XML, logcat and package information in `diagnostics/`. Inspect the failing assertion and its accompanying diagnostics. APK preparation records metadata in `e2e/build/apks.json`, build output in `e2e/build/reports/preparation-build.txt` and build duration in the console output. CI uploads the metadata with the reports.

Missing prerequisites, failed assertions and empty test runs return a nonzero exit code. Type-check the TypeScript code and test preparation logic without an emulator:

```sh
npm run typecheck --prefix e2e
npm run test:preparation --prefix e2e
```

For signature/version failures, inspect APK metadata first. The default E2E command prepares signing and builds with `-Pe2e` automatically; an ordinary debug APK has a different package ID. Never work around a failed upgrade by clearing data or uninstalling between versions, or by supplying production signing secrets.

If a low-memory Gradle build repeatedly stalls in garbage collection, use `--max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=1g'`. The E2E builder and Android CI APK build steps use these limits.

JVM reports live under `app/build/reports/tests/` and `app/build/test-results/`; native AndroidJUnitRunner reports live under `app/build/reports/androidTests/` and `app/build/outputs/androidTest-results/`. See [README test commands](README.md#tests) for those suites.
