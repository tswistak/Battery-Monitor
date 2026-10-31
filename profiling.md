# Profiling execution guide

Run these commands from the repository root. The `profile` application build and `macrobenchmark` module provide an isolated target and repeatable scenarios. Device selection uses an explicit ADB serial; these commands do not depend on a manufacturer or model. Ordinary tests are described in [README](README.md#tests), and Appium tests in the [E2E guide](E2E.md).

## Requirements

Use JDK 21, the Android SDK platform-tools, API 37 compilation platform and Build Tools 37.0.0. Use a physical Android 10/API 29 or newer device for the standard Macrobenchmark workflow below. Root is not required. The application supports API 26 onward, but benchmark and tracing capabilities have their own Android-version requirements. Macrobenchmark rejects emulators for performance measurement; use ordinary instrumented/E2E tests for emulator coverage. See the [Android Macrobenchmark guide](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview).

Authorize USB debugging, keep the screen awake and unlocked, and use English (United States) UI labels. Keep font/display settings, animations, battery/USB power, thermal conditions and compilation state consistent between comparable runs. Tracing and UI automation must be available on the selected device; an Android version alone does not guarantee every optional metric or transport.

**Benchmark scenarios replace history, reset settings and delete alarms in the isolated profiling installation. Use disposable test data only.** The target is `codes.swistak.batterymonitor.profile`; the harness is `codes.swistak.batterymonitor.macrobenchmark`. Ordinary runs grant the target's notification permission on Android 13 onward, and the widget scenario temporarily adopts widget-binding permission. Root and Shizuku authorization remain manual prerequisites for their optional tests.

## Build and install

Set `ANDROID_HOME` to your SDK and choose the target shown by ADB:

```sh
ADB="$ANDROID_HOME/platform-tools/adb"
"$ADB" devices -l
SERIAL="replace-with-your-test-device-serial"
```

Create the disposable profiling key only if it is missing. Preserve an existing key so APK updates retain the same signer. The public test-key password is `android`; production signing material is unnecessary.

```sh
mkdir -p captures/profiling
if [ ! -e captures/profiling/local-test.keystore ]; then
  keytool -genkeypair \
    -keystore captures/profiling/local-test.keystore \
    -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 3650 \
    -dname CN=BatteryMonitorLocalProfiling -noprompt
fi
./gradlew -Pprofiling :app:assembleProfile :macrobenchmark:assembleProfile
APP_APK="app/build/outputs/apk/profile/app-profile.apk"
BENCHMARK_APK="macrobenchmark/build/outputs/apk/profile/macrobenchmark-profile.apk"
"$ADB" -s "$SERIAL" install -r "$APP_APK"
"$ADB" -s "$SERIAL" install -r "$BENCHMARK_APK"
```

`-Pprofiling` enables the build targets and local signing. Use both APKs from this build: the target must be non-debuggable, have an isolated UID/process and share the profiling signature permission with the harness. If the device refuses ADB installation, install these same APKs through an allowed device UI. Do not bypass its security restrictions or uninstall/clear a personal application to resolve an installation failure.

## Run a benchmark

Choose a new run name for every attempt. Create fresh local and device output directories; if directory creation fails, stop and choose an unused name. Save the fixture anchor once, using the device clock:

```sh
RUN_ID="startup-cold-01"
LOCAL_OUTPUT="captures/profiling/$RUN_ID"
REMOTE_ROOT="/sdcard/Android/media/codes.swistak.batterymonitor.macrobenchmark/performance"
REMOTE_OUTPUT="$REMOTE_ROOT/$RUN_ID"
mkdir "$LOCAL_OUTPUT"
"$ADB" -s "$SERIAL" shell mkdir -p "$REMOTE_ROOT"
"$ADB" -s "$SERIAL" shell mkdir "$REMOTE_OUTPUT"
ANCHOR_MILLIS="$("$ADB" -s "$SERIAL" shell date +%s)000"
printf '%s\n' "$ANCHOR_MILLIS" > "$LOCAL_OUTPUT/anchor-millis.txt"
BENCHMARK_CLASS="codes.swistak.batterymonitor.macrobenchmark.BatteryPerformanceBenchmark"
RUNNER="codes.swistak.batterymonitor.macrobenchmark/androidx.test.runner.AndroidJUnitRunner"
```

Use only letters, digits, underscores and hyphens in `RUN_ID`. Reuse the saved anchor for comparisons; do not regenerate it between the two APKs.

Start with one cold-start iteration:

```sh
"$ADB" -s "$SERIAL" shell am instrument -w -r \
  -e class "$BENCHMARK_CLASS#startupCold" \
  -e anchorMillis "$ANCHOR_MILLIS" \
  -e iterations 1 \
  -e privileged false \
  -e monitoring true \
  -e interactionMethod gestures \
  -e measurementBackend macrobenchmark \
  -e androidx.benchmark.fullTracing.enable false \
  -e additionalTestOutputDir "$REMOTE_OUTPUT" \
  "$RUNNER" > "$LOCAL_OUTPUT/instrumentation.txt" 2>&1
```

Collect output even if instrumentation fails:

```sh
"$ADB" -s "$SERIAL" pull "$REMOTE_OUTPUT" "$LOCAL_OUTPUT/benchmark"
cat "$LOCAL_OUTPUT/instrumentation.txt"
```

Inspect the final JUnit result and any failures or assumption skips; ADB success and diagnostic status packets alone do not prove that measurements completed. The [Android benchmark output guide](https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci) describes generated JSON and trace files. The direct ADB commands above do not install again, collect additional device dumps or impose a host timeout automatically.

For repeated measurements, use a fresh output directory and change `iterations` to at least `5` (the supported range is 1–50). The saved anchor must be an integer epoch in milliseconds from 2020 onward and no later than the device's current time. Check that it still gives equivalent membership in rolling history ranges.

Select another existing `@Test` method from [BatteryPerformanceBenchmark.kt](macrobenchmark/src/main/kotlin/codes/swistak/batterymonitor/macrobenchmark/BatteryPerformanceBenchmark.kt), such as `startupWarm`, `rapidNavigationAndExternalIntents`, `historyRangesTabsAndSameLevelInsertion` or `settingsAlarmsAndUnsavedDraft`. Use `-e class "$BENCHMARK_CLASS"` without a method suffix for the full class. Optional root/Shizuku methods may be skipped unless explicitly configured.

If raw input injection is unavailable, use `-e interactionMethod accessibility` and wake/unlock the device manually. This uses accessibility actions and excludes raw fullscreen panning; compare runs using the same interaction method. Keep `privileged` and `monitoring` modes identical between comparable runs. `CompilationMode.Ignore` preserves existing ART compilation state rather than standardizing it. Full composition tracing can be enabled separately on Android 11/API 30 onward when supported; keep the tracing setting consistent.

### Interpreting `currentIdleUpdates`

This scenario retains its existing name and fixed Current State observation window. The standard backend collects only `FrameTimingMetric`: it describes frames rendered while this screen is idle. It does not measure battery acquisition cost, background monitoring overhead, CPU efficiency, power or wakeups. The legacy ATrace backend retains its separate measurements and limitations.

The `current_idle_scope` status record reports `visibleUpdatesObserved=unavailable`: this harness has no reliable observer of battery-driven visible changes. A configured acquisition interval does not establish that a displayed value changed. Do not interpret absent frame samples as zero cost or as good performance; retain missing metrics as unavailable and use the separate acquisition/profiling probes for those questions.

### Production journal checks and the manual historical SQL experiment

`HistorySynchronizationVerification#journalContract` runs `ProfileJournalVerification.verifyContract`, which uses current production `LogDatabase` methods. It remains the maintained journal correctness check. Select individual verification methods with `finalVerification=true`, an owned `additionalTestOutputDir`, and a distinct `phase`; these optional profile checks are separate from ordinary functional test execution.

`HistorySynchronizationVerification#journalWriteCost` is an optional, manual historical SQL experiment. It compares hand-written schema 6 and schema 7 SQL paths and does not validate current production performance. Its report is labeled `historical-journal-sql-experiment`; the target report adds `historicalSqlExperimentExecuted` and `productionPerformanceValidated=false`. `journalMode=correctness` remains the default and does not run this experiment.

With the isolated profile APKs already installed and the variables above set, use fresh output directories and select one method explicitly:

```sh
rtk proxy "$ADB" -s "$SERIAL" shell am instrument -w -r \
  -e class codes.swistak.batterymonitor.macrobenchmark.HistorySynchronizationVerification#journalContract \
  -e finalVerification true -e phase journal-contract-01 \
  -e additionalTestOutputDir "$REMOTE_OUTPUT" "$RUNNER"
```

For a deliberate historical experiment, select `#journalWriteCost` instead and add `-e journalPattern single -e journalInitialRows 0`. Existing patterns are `single`, `periodic128`, `bulkAdd5000`, and `bulkReplace5000`; initial rows are `0` or `128`. Keep those synthetic results separate from production correctness and performance reports. Neither experiment completion nor matching synthetic rows proves production performance.

## Optional root, Shizuku and legacy tracing

These options depend on available capabilities, independently of device brand. Use the same `am instrument` command with the appropriate method/arguments and a fresh output directory for each phase.

- **Root acquisition:** select `#privilegedAcquisitionCost`, set `privileged true`, `measurementBackend legacyAtrace` and full tracing `false`. Authorize root manually when prompted. This selected probe resets the isolated fixture and performs acquisition checks without starting an ATrace capture.
- **Shizuku status:** install/start Shizuku separately and configure the isolated application's access. Select `#shizukuStatus`, set `measurementBackend legacyAtrace`, full tracing `false`, and `-e shizukuPhase status-01`. The phase label must contain 1–48 ASCII letters, digits, underscores or hyphens. Status delivery is not proof of successful readings.
- **Shizuku acquisition:** select `#shizukuMonitoringAcquisition`, set `privileged true`, the same backend/tracing arguments, and a new `shizukuPhase`. Enable the isolated application's privileged preference and approve its Shizuku permission manually beforehand. Optional `-e shizukuEnable true` enables in-memory access only when that preference is already enabled; `-e shizukuReaderChecks true` adds separate reader cache/disable/recovery checks. Inspect the actual access method and measurement fields to establish Shizuku usage. These phases preserve live fixture/process state and start no trace.
- **Legacy timed UI/startup capture:** use `measurementBackend legacyAtrace` with full tracing `false` only on Android 12/API 31 onward with compatible authorized root, mounted writable `/sys/kernel/debug/tracing`, idle tracing and no active Perfetto session. The backend requires a root implementation supporting `su -mm -c`; it does not mount filesystems or configure permissions. It produces separate ATrace/gfxinfo measurements, which must not be pooled with Macrobenchmark metrics. These tracing prerequisites apply to timed captures, not the trace-free probes above.

For recovery checks, manually stop/restart the optional service or revoke/restore only the isolated application's permission between separate selected-method runs. Do not reinstall the APKs between live connection phases. Retain failed phases and restore the intended service/permission state afterward. Neither root nor Shizuku is required for standard benchmarks or ordinary application operation.

### Root session verification

Keep Magisk's notifications for Battery Monitor enabled throughout verification. The root acquisition report (`privileged-acquisition-v3`) separates `rootExecutorInvocationCount` from `rootSessionOpenCount`: the former counts commands; the latter counts actual `su` process starts, including denied acquisition and recovery, within the measured Android process. A healthy shell should serve all five acquisitions without further session opens. A session already open before the probe can give a total of zero. The UI and monitoring service use separate processes and may each need their own first session.

On the rooted device:

1. Enable privileged access and allow root. Run `#privilegedAcquisitionCost` with the arguments above; retain its JSON and confirm all five snapshots report root access, real measurement values and fresh acquisition timestamps.
2. Keep Battery Monitor visible for several minutes. Confirm measurements update, the UI responds and ordinary polling produces no recurring Magisk grant toasts.
3. Repeat with another app in the foreground and the screen on, then turn the screen off and on. Confirm monitoring continues without repeatedly reopening sessions.
4. Open diagnostics, run the explicit root test and exercise charging-target reads. Confirm these root-backed operations share the healthy session rather than causing a new grant toast for each command.
5. Disable privileged access. Confirm automatic privileged work stops. Exercise the explicit diagnostics root test while automatic access is disabled, then re-enable privileged access and confirm fresh readings resume.
6. Using only the disposable test installation, test denial followed by an explicit retry/re-enable and controlled root-session/process death. Confirm denied access does not cause repeated automatic requests, and legitimate recovery restores readings. Restore the intended permission afterward.

Initial acquisition and recovery may produce a grant toast. Record which steps were actually performed; a passing acquisition probe alone does not establish several minutes of background monitoring or visual toast behavior.

#### Focused root transport tests

These instrumented tests use the debug installation (`codes.swistak.batterymonitor.debug`), separately from the `.profile` acquisition benchmark. Build and install its target/test APKs with the project wrapper and SDK tools:

```sh
rtk proxy ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
rtk proxy "$ADB" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
rtk proxy "$ADB" -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
ROOT_TEST_CLASS="codes.swistak.batterymonitor.common.RootShellSessionInstrumentedTest"
ROOT_TEST_RUNNER="codes.swistak.batterymonitor.debug.test/androidx.test.runner.AndroidJUnitRunner"
```

Keep Magisk notifications enabled and grant root to the debug target. Select both normal tests explicitly:

```sh
rtk proxy "$ADB" -s "$SERIAL" shell am instrument -w -r \
  -e class "$ROOT_TEST_CLASS#rawCommandsReuseRootAndPreserveBytesStatusAndIsolation,$ROOT_TEST_CLASS#timeoutDisableAndInterruptionStopActualChildrenAndAllowRecovery" \
  -e rootSessionTests true \
  "$ROOT_TEST_RUNNER"
```

The first test checks real root-session reuse, raw UTF-8 bytes, exit status, command isolation and the output cap. The second checks that timeout, disable and interruption stop the actual child command, reject stale output and allow recovery. Confirm the final result reports two executed tests without assumption skips.

For the separate denial test, first change only the disposable debug target's policy to **Deny** in Magisk. The `rootDeniedTests` flag selects the denial scenario; it does not change Magisk policy. Run:

```sh
rtk proxy "$ADB" -s "$SERIAL" shell am instrument -w -r \
  -e class "$ROOT_TEST_CLASS#deniedMagiskPolicyStopsAutomaticRetriesAndPermitsOneExplicitRetry" \
  -e rootDeniedTests true \
  "$ROOT_TEST_RUNNER"
```

Confirm one executed test, then restore the debug target's previous Magisk policy. The test requires a real denial and verifies one initial automatic acquisition plus one deliberate retry, without repeated automatic authorization attempts. Without the matching `rootSessionTests=true` or `rootDeniedTests=true` flag, these device tests skip root work during ordinary instrumented suites.

## Host checks and analysis

The optional host helpers use Python 3.10 or newer and the standard library. They invoke `rtk proxy` internally, so `rtk` must be on `PATH`. Run parser/safety checks without a device:

```sh
python3 -B -m unittest discover -s tools/profiling -p 'test_*.py'
```

Set `PROFILING_TRACE_PROCESSOR` to an existing native Perfetto `trace_processor_shell` binary to include the otherwise-skipped synthetic parser integration check. The binary must support `query TRACE SQL`; helpers never download it.

Use the actual generated JSON filename under the pulled `benchmark/` directory:

```sh
BENCHMARK_JSON="captures/profiling/startup-cold-01/benchmark/replace-with-generated-result.json"
python3 -B tools/profiling/analyze.py \
  --benchmark "$BENCHMARK_JSON" \
  --output captures/profiling/startup-cold-01/analysis.json
```

To analyze captured traces, add `--trace` with an actual pulled trace path and `--trace-processor` with your existing native binary. Both input options can be repeated. For legacy capture, use `--legacy-results` with the generated `legacy-<method>.json` and provide `--trace-processor`. Create parent directories first and use new output filenames; analysis refuses to overwrite a report. Traces can also be opened in Android Studio or a locally used Perfetto viewer.

Aggregate existing per-scenario legacy analysis files, using paths where you saved them:

```sh
python3 -B tools/profiling/summarize.py \
  --before captures/profiling/before/analysis/*.analysis.json \
  --after captures/profiling/after/analysis/*.analysis.json \
  --output captures/profiling/comparison.json
```

`--after` is optional for a baseline-only summary. Inspect metric availability, sample counts, trace health and collection failures before comparing results. Unsupported or missing metrics are unavailable, not zero. Keep APKs/hashes, effective arguments, instrumentation logs and failed attempts alongside each measurement set, and compare matching scenarios, devices, backends and conditions.

All generated APKs, traces, dumps, private test data and reports belong under ignored `captures/` or build output directories. They can contain private information; do not commit or publish them. This guide contains reusable execution instructions only.
