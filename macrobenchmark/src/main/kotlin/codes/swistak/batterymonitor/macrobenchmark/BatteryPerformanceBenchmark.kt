/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>.
*/
package codes.swistak.batterymonitor.macrobenchmark

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.KeyguardManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMacrobenchmarkApi::class)
class BatteryPerformanceBenchmark {
    private val benchmark by lazy { MacrobenchmarkRule() }
    private val legacyAtrace: Boolean
        get() = InstrumentationRegistry.getArguments()
            .getString("measurementBackend") == "legacyAtrace"

    @get:Rule
    val measurementRule = TestRule { base, description ->
        if (legacyAtrace) base else benchmark.apply(base, description)
    }

    @get:Rule
    val testName = TestName()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var resources: Resources
    private var anchor = 0L
    private var iterations = 5
    private var privileged = false
    private var monitoring = true
    private var accessibilityActions = false
    private var activityLauncher: ((Intent) -> Unit)? = null
    private val legacy by lazy { LegacyAtraceBenchmark(instrumentation, TARGET) }

    @Before
    fun configure() {
        val arguments = InstrumentationRegistry.getArguments()
        require(
            (arguments.getString("measurementBackend")
                ?: "macrobenchmark") in setOf("macrobenchmark", "legacyAtrace")
        )
        val interaction = arguments.getString("interactionMethod")
            ?: if (Build.VERSION.SDK_INT == 29) "accessibility" else "gestures"
        require(interaction in setOf("accessibility", "gestures"))
        accessibilityActions = interaction == "accessibility"
        val power = context.getSystemService(PowerManager::class.java)
        if (!power.isInteractive) {
            check(!accessibilityActions) { "Wake the profiling device manually; raw wake input is unavailable" }
            device.wakeUp()
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked && !keyguard.isDeviceSecure) {
            device.executeShellCommand("wm dismiss-keyguard")
            val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT
            while (keyguard.isKeyguardLocked && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
        }
        check(!keyguard.isKeyguardLocked) { "Unlock the profiling device before running benchmarks" }
        anchor = requireNotNull(arguments.getString("anchorMillis")?.toLongOrNull()) {
            "Pass the same saved -e anchorMillis epoch to baseline and optimized runs"
        }
        require(anchor in MIN_ANCHOR_MILLIS..System.currentTimeMillis())
        iterations = arguments.getString("iterations")?.toIntOrNull() ?: 5
        require(iterations in 1..50)
        privileged = (arguments.getString("privileged") ?: "false").toBooleanStrict()
        monitoring = (arguments.getString("monitoring") ?: "true").toBooleanStrict()
        resources = context.packageManager.getResourcesForApplication(TARGET)
        val application = context.packageManager.getApplicationInfo(TARGET, 0)
        assertEquals(TARGET, application.processName)
        assertFalse(application.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertEquals(
            setOf(TARGET), context.packageManager.getPackagesForUid(application.uid)?.toSet()
        )
        val shizukuPhase = arguments.getString("shizukuPhase")
        if (Build.VERSION.SDK_INT >= 33 && shizukuPhase == null) {
            instrumentation.uiAutomation.grantRuntimePermission(
                TARGET, Manifest.permission.POST_NOTIFICATIONS
            )
        }
        val battery = context.registerReceiver(
            null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        report("conditions", Bundle().apply {
            putString("model", Build.MODEL)
            putInt("api", Build.VERSION.SDK_INT)
            putString("locale", resources.configuration.locales.toLanguageTags())
            putFloat("fontScale", resources.configuration.fontScale)
            putLong("anchorMillis", anchor)
            putInt("iterations", iterations)
            putBoolean("privileged", privileged)
            putBoolean("monitoring", monitoring)
            putBoolean("notificationGrantSkippedForShizukuPhase", shizukuPhase != null)
            putBoolean(
                "notificationPermissionGranted", context.packageManager.checkPermission(
                    Manifest.permission.POST_NOTIFICATIONS, TARGET
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            )
            putString("interactionMethod", interaction)
            putString("measurementBackend", if (legacyAtrace) "legacyAtrace" else "macrobenchmark")
            putBoolean("compositionTracingSupported", !legacyAtrace && Build.VERSION.SDK_INT >= 30)
            putString(
                "fullTracingRequested",
                arguments.getString("androidx.benchmark.fullTracing.enable") ?: "false"
            )
            putInt("plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1)
            putInt(
                "temperatureTenthsC",
                battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            )
            putString("compilationMode", "Ignore; record actual ART state separately")
            putFloat(
                "animatorScale", Settings.Global.getFloat(
                    context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
                )
            )
            putFloat(
                "transitionScale", Settings.Global.getFloat(
                    context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f
                )
            )
            putFloat(
                "windowScale", Settings.Global.getFloat(
                    context.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f
                )
            )
        })
    }

    @Test
    fun privilegedAcquisitionCost() {
        assumeTrue("Root acquisition profiling requires explicit privileged=true", privileged)
        resetFixture()
        val result = fixture("MEASURE_PRIVILEGED")
        val reportJson = requireNotNull(result.getString("reportJson"))
        val details = JSONObject(reportJson)
        val measurements = details.getJSONArray("measurements")
        val complete =
            result.getBoolean("rootAvailable") && result.getInt("completedRepetitions") == 5 && !details.optBoolean(
                "timedOut"
            ) && measurements.length() == 5 && (0 until measurements.length()).all { index ->
                val measurement = measurements.getJSONObject(index)
                measurement.getBoolean("rootAvailable") && measurement.isNull("failure") && measurement.optJSONObject(
                    "snapshot"
                )?.optBoolean("hasStats") == true
            }
        val directory = File(
            requireNotNull(
                InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ) {
                "Set additionalTestOutputDir to retain the privileged acquisition report"
            })
        check(directory.isDirectory || directory.mkdirs())
        val output = File(directory, "privileged-acquisition.json")
        check(!output.exists()) { "Preserve the previous probe report and use a fresh output directory" }
        output.writeText(reportJson)
        report("privileged_acquisition", Bundle(result).apply {
            remove("reportJson")
            putString("benchmarkOutputPath", output.absolutePath)
            putBoolean("realRootMeasurementsComplete", complete)
            putBoolean("syntheticSelfCheckPassed", result.getBoolean("selfCheckPassed"))
        })
        assertTrue(
            "Synthetic unavailable-source assertions failed; see the separate self-check report",
            result.getBoolean("selfCheckPassed")
        )
        assertTrue(
            "Root acquisition measurements are incomplete or unavailable; retained report describes the failure",
            complete
        )
        assertEquals(
            "Five root acquisitions must share one session",
            1L,
            details.getLong("rootSessionOpenCount")
        )
    }

    @Test
    fun shizukuStatus() {
        assumeTrue(
            "Shizuku probes require an explicit phase",
            InstrumentationRegistry.getArguments().getString("shizukuPhase") != null
        )
        retainShizukuResult("SHIZUKU_STATUS", "status")
    }

    @Test
    fun shizukuMonitoringAcquisition() {
        assumeTrue(
            "Shizuku probes require an explicit phase",
            InstrumentationRegistry.getArguments().getString("shizukuPhase") != null
        )
        check(privileged) { "Shizuku acquisition requires explicit privileged=true" }
        val result = retainShizukuResult("MEASURE_SHIZUKU", "measurement")
        assertTrue(
            "Actual Shizuku monitoring/reader checks are incomplete; see retained report",
            result.getBoolean("complete")
        )
    }

    private fun retainShizukuResult(action: String, defaultPhase: String): Bundle {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("shizukuPhase") ?: defaultPhase
        require(phase.matches(Regex("[A-Za-z0-9_-]{1,48}"))) { "Invalid Shizuku profiling phase" }
        // Preserve the running app process, settings and fixture between externally controlled stages.
        val result = fixture(action, phase)
        val directory = File(requireNotNull(arguments.getString("additionalTestOutputDir")))
        check(directory.isDirectory || directory.mkdirs())
        val output = File(directory, "shizuku-$phase-$action.json")
        check(!output.exists()) { "Preserve previous Shizuku reports and use a fresh output directory" }
        output.writeText(requireNotNull(result.getString("reportJson")))
        report("shizuku_probe", Bundle(result).apply {
            remove("reportJson")
            putString("phase", phase)
            putString("action", action)
            putString("benchmarkOutputPath", output.absolutePath)
        })
        return result
    }

    @Test
    fun smokeAccessibility() {
        assumeTrue(
            "Accessibility smoke applies to accessibility interaction mode", accessibilityActions
        )
        resetFixture()
        context.startActivity(sectionIntent("current"))
        settleLaunch()
        clickDescription("nav_open_menu")
        awaitDescription("nav_close_menu")
        goBack()
        assertTrue(device.wait(Until.gone(By.pkg(TARGET).desc(text("nav_close_menu"))), UI_TIMEOUT))
        report(
            "accessibility_smoke", Bundle().apply { putBoolean("drawerClickAndGlobalBack", true) })
    }

    @Test
    fun startupCold() = startup(StartupMode.COLD)

    @Test
    fun startupWarm() = startup(StartupMode.WARM)

    @Test
    fun startupReturning() = startup(StartupMode.HOT)

    private fun startup(mode: StartupMode) {
        if (legacyAtrace) {
            withLauncher(legacy::launch) {
                legacy.measure(testName.methodName, iterations, mode.name, setup = {
                    resetFixture()
                    if (mode != StartupMode.COLD) launch()
                    goHome()
                }, action = { launch(waitForMonitoring = false) })
            }
        } else {
            benchmark.measureRepeated(
                packageName = TARGET,
                metrics = listOf(StartupTimingMetric()),
                compilationMode = CompilationMode.Ignore(),
                iterations = iterations,
                startupMode = mode,
                setupBlock = {
                    val scope = this
                    withLauncher({ scope.startActivityAndWait(it) }) {
                        resetFixture()
                        if (mode != StartupMode.COLD) launch()
                        goHome()
                    }
                }) {
                val scope = this
                withLauncher({ scope.startActivityAndWait(it) }) { launch() }
            }
        }
    }

    @Test
    fun drawerFirstAndRepeatedNavigation() = frames {
        repeat(2) {
            listOf(
                "history", "diagnostics", "settings", "alarms", "help", "current"
            ).forEach { route ->
                selectSection(route)
            }
        }
        clickDescription("nav_open_menu")
        awaitDescription("nav_close_menu")
        goBack()
        check(device.wait(Until.gone(By.pkg(TARGET).desc(text("nav_close_menu"))), UI_TIMEOUT)) {
            "Back did not close the navigation drawer"
        }
        awaitDescription("nav_open_menu")
    }

    @Test
    fun rapidNavigationAndExternalIntents() = frames {
        listOf("history", "diagnostics", "settings", "alarms", "current").forEach { route ->
            context.startActivity(sectionIntent(route))
        }
        awaitDescription("nav_open_menu")
        startActivityAndWait(sectionIntent("current").putExtra(EXTRA_DETAIL, "measurement:current"))
        awaitText("diag_action_refresh")
        goBack()
    }

    @Test
    fun currentIdleUpdates() = frames {
        // A fixed observation window includes several configured two-second acquisitions.
        SystemClock.sleep(IDLE_WINDOW_MILLIS)
    }

    @Test
    fun currentScrollAndCurrentDetails() = frames {
        scroll(Direction.DOWN)
        scroll(Direction.UP)
        startActivityAndWait(sectionIntent("current").putExtra(EXTRA_DETAIL, "measurement:current"))
        awaitText("diag_action_refresh")
        reportVisibleText("current_sources")
        clickText("diag_action_refresh")
        goBack()
        awaitDescription("nav_open_menu")
    }

    @Test
    fun currentPowerDetails() = frames {
        startActivityAndWait(sectionIntent("current").putExtra(EXTRA_DETAIL, "measurement:power"))
        awaitText("battery_power_explanation")
        reportVisibleText("power_sources")
        goBack()
    }

    @Test
    fun diagnosticsTabsRefreshSearchAndScroll() = frames(section = "diagnostics") {
        clickText("nav_battery_group")
        clickText("diag_section_charging")
        clickText("diag_raw")
        device.wait(
            Until.findObject(
                By.pkg(TARGET).desc(text("diag_action_refresh")).enabled(true)
            ), LOAD_TIMEOUT
        ) ?: error("Diagnostics collection did not settle")
        val search = device.wait(
            Until.findObject(By.pkg(TARGET).clazz("android.widget.EditText")), UI_TIMEOUT
        ) ?: error("Raw diagnostics search unavailable")
        listOf("c", "cu", "cur", "current").forEach { query -> setText(search, query) }
        setText(search, "")
        dismissKeyboard()
        scroll(Direction.DOWN)
        scroll(Direction.UP)
        clickDescription("diag_action_refresh")
        device.wait(
            Until.findObject(
                By.pkg(TARGET).desc(text("diag_action_refresh")).enabled(true)
            ), LOAD_TIMEOUT
        ) ?: error("Diagnostics refresh timed out")
        clickText("diag_overview")
    }

    @Test
    fun historyRangesTabsAndSameLevelInsertion() = frames(section = "history") {
        chooseHistoryRange("history_last_7d")
        chooseHistoryRange("history_all_history")
        chooseHistoryRange("history_last_24h")
        clickText("history_events")
        scroll(Direction.DOWN)
        val appended = fixture("APPEND")
        assertEquals(1, appended.getInt("appended"))
        scroll(Direction.UP)
        clickText("history_charts")
        fixture("APPEND")
        selectSection("current")
        selectSection("history")
        goHome()
        fixture("APPEND")
        startActivityAndWait(sectionIntent("history"))
        awaitText("history_charts")
    }

    @Test
    fun fullscreenChartPanZoomAndSettings() {
        assumeFalse(
            "Raw chart panning requires input injection; accessibility controls are benchmarked separately",
            accessibilityActions
        )
        frames(section = "history") { fullscreenChartInteractions(pan = true) }
    }

    @Test
    fun fullscreenChartControlsAndSettings() = frames(section = "history") {
        fullscreenChartInteractions(pan = false)
    }

    private fun fullscreenChartInteractions(pan: Boolean) {
        clickDescription("history_open_fullscreen")
        chartAction("history_chart_zoom_in")
        chartAction("history_chart_zoom_out")
        if (pan) {
            val width = device.displayWidth
            val height = device.displayHeight
            device.swipe(width * 3 / 4, height / 2, width / 4, height / 2, 20)
            device.swipe(width / 4, height / 2, width * 3 / 4, height / 2, 20)
        }
        chartAction("history_chart_settings")
        clickText("history_chart_points")
        chartAction("history_chart_settings")
        clickText("history_chart_connected")
        chartAction("history_chart_reset")
        clickDescription("nav_up")
        awaitText("history_charts")
    }

    @Test
    fun settingsAlarmsAndUnsavedDraft() = frames(section = "settings") {
        clickText("other_settings")
        awaitText("settings_color_palette")
        clickDescription("nav_up")
        clickText("settings_notification")
        awaitText("pref_cat_channel_settings")
        scroll(Direction.DOWN)
        scroll(Direction.UP)
        clickDescription("nav_up")
        selectSection("alarms")
        val add = device.wait(
            Until.findObject(
                By.pkg(TARGET).text(text("alarms_add_threshold")).enabled(true)
            ), LOAD_TIMEOUT
        ) ?: error("Alarm rules did not become editable")
        click(add)
        val input = device.wait(
            Until.findObject(By.pkg(TARGET).clazz("android.widget.EditText")), UI_TIMEOUT
        ) ?: error("Alarm draft missing")
        setText(input, "47")
        dismissKeyboard()
        clickText("cancel")
        awaitText("alarms_discard_confirmation")
        clickText("cancel")
        val restoredInput = device.wait(
            Until.findObject(By.pkg(TARGET).clazz("android.widget.EditText").enabled(true)),
            UI_TIMEOUT
        ) ?: error("Alarm draft did not return after canceling discard")
        assertEquals("47", restoredInput.text)
        clickText("cancel")
        clickText("alarms_discard")
        selectSection("current")
        goHome()
        startActivityAndWait(sectionIntent("current"))
        awaitDescription("nav_open_menu")
    }

    @Test
    fun widgetConfigurationAndReturn() {
        val host = AppWidgetHost(context, WIDGET_HOST_ID)
        val widgetId = host.allocateAppWidgetId()
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
            val bound = try {
                AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(
                    widgetId, ComponentName(
                        TARGET, "codes.swistak.batterymonitor.widgets.StatusWidgetProvider"
                    )
                )
            } finally {
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
            assertTrue("Cannot bind isolated test widget", bound)
            frames {
                startActivityAndWait(
                    Intent().setComponent(ComponentName(TARGET, WIDGET_CONFIGURATION))
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                )
                awaitText("widget_configure_title")
                awaitText("widget_preview_note")
                if (device.hasObject(By.pkg(TARGET).scrollable(true))) {
                    scroll(Direction.DOWN)
                    scroll(Direction.UP)
                } else {
                    // A large viewport can display the complete configuration without overflow.
                    awaitText("widget_tap_action")
                    check(
                        device.wait(
                            Until.hasObject(
                                By.pkg(TARGET).text(text("charging_diagnostics_save")).enabled(true)
                            ), UI_TIMEOUT
                        )
                    ) { "Widget configuration save action did not become ready" }
                    awaitDescription("cancel")
                }
                clickDescription("cancel")
                startActivityAndWait(sectionIntent("current"))
                awaitDescription("nav_open_menu")
            }
        } finally {
            host.deleteAppWidgetId(widgetId)
        }
    }

    private fun frames(section: String = "current", interaction: () -> Unit) {
        if (legacyAtrace) {
            withLauncher(legacy::launch) {
                legacy.measure(testName.methodName, iterations, setup = {
                    resetFixture()
                    launch(section)
                }, action = interaction)
            }
        } else {
            benchmark.measureRepeated(
                packageName = TARGET,
                metrics = listOf(FrameTimingMetric()),
                compilationMode = CompilationMode.Ignore(),
                iterations = iterations,
                setupBlock = {
                    val scope = this
                    withLauncher({ scope.startActivityAndWait(it) }) {
                        resetFixture()
                        launch(section)
                    }
                },
                measureBlock = {
                    val scope = this
                    withLauncher({ scope.startActivityAndWait(it) }, interaction)
                })
        }
    }

    private fun withLauncher(launcher: (Intent) -> Unit, action: () -> Unit) {
        check(activityLauncher == null)
        activityLauncher = launcher
        try {
            action()
        } finally {
            activityLauncher = null
        }
    }

    private fun startActivityAndWait(intent: Intent) = checkNotNull(activityLauncher)(intent)

    private fun launch(section: String = "current", waitForMonitoring: Boolean = true) {
        startActivityAndWait(sectionIntent(section))
        settleLaunch(waitForMonitoring)
    }

    private fun settleLaunch(waitForMonitoring: Boolean = true) {
        if (device.wait(
                Until.hasObject(By.pkg(TARGET).text(text("battery_optimized"))), PROMPT_TIMEOUT
            )
        ) {
            clickText("cancel")
            report("startup_battery_prompt", Bundle().apply { putBoolean("dismissed", true) })
        }
        awaitDescription("nav_open_menu")
        if (monitoring && waitForMonitoring) {
            val deadline = SystemClock.elapsedRealtime() + LOAD_TIMEOUT
            var servicePid = ""
            while (servicePid.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
                servicePid = device.executeShellCommand("pidof $TARGET:BIS").trim()
                if (servicePid.isEmpty()) SystemClock.sleep(100)
            }
            assertTrue("Monitoring process did not start", servicePid.isNotEmpty())
            report("monitoring_process", Bundle().apply { putString("pid", servicePid) })
        }
    }

    private fun resetFixture() {
        device.executeShellCommand("am force-stop $TARGET")
        val seeded = fixture("SEED")
        assertEquals(anchor, seeded.getLong("anchorMillis"))
        assertEquals(FIXTURE_ROWS, seeded.getInt("rows"))
        report("fixture", seeded)
        device.executeShellCommand("am force-stop $TARGET")
        if (Build.VERSION.SDK_INT >= 29) {
            report("thermal", Bundle().apply {
                putInt(
                    "status",
                    context.getSystemService(PowerManager::class.java).currentThermalStatus
                )
            })
        }
    }

    private fun fixture(command: String, phase: String? = null): Bundle {
        val completion = CountDownLatch(1)
        val result = AtomicReference<Bundle>()
        val request =
            Intent("$TARGET.$command").setComponent(ComponentName(TARGET, FIXTURE_RECEIVER))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).putExtra("anchorMillis", anchor)
                .putExtra("privileged", privileged).apply {
                    if (phase != null) {
                        putExtra("phase", phase)
                        val arguments = InstrumentationRegistry.getArguments()
                        putExtra(
                            "enable",
                            arguments.getString("shizukuEnable")?.toBooleanStrict() ?: false
                        )
                        putExtra(
                            "readerChecks",
                            arguments.getString("shizukuReaderChecks")?.toBooleanStrict() ?: false
                        )
                    }
                }.putExtra("monitoring", monitoring)
        context.sendOrderedBroadcast(request, null, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                result.set(requireNotNull(getResultExtras(true)).apply {
                    putInt(
                        "resultCode", resultCode
                    )
                })
                completion.countDown()
            }
        }, null, Activity.RESULT_CANCELED, null, null)
        check(
            completion.await(
                LOAD_TIMEOUT, TimeUnit.MILLISECONDS
            )
        ) { "Fixture $command timed out" }
        return checkNotNull(result.get()).also {
            check(it.getInt("resultCode") == Activity.RESULT_OK) {
                "Fixture $command failed: ${
                    it.getString(
                        "error"
                    )
                }"
            }
        }
    }

    private fun sectionIntent(route: String) =
        Intent().setComponent(ComponentName(TARGET, MAIN_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_SECTION, route)

    private fun selectSection(route: String) {
        clickDescription("nav_open_menu")
        clickText("nav_$route", last = true)
        check(device.wait(Until.gone(By.pkg(TARGET).desc(text("nav_close_menu"))), UI_TIMEOUT)) {
            "Navigation drawer did not close"
        }
        awaitDescription("nav_open_menu")
    }

    private fun chooseHistoryRange(label: String) {
        clickText("history_change_range")
        clickText(label, last = true)
        awaitText("history_change_range")
    }

    private fun chartAction(label: String) {
        val actionDescription = text(label)
        val overflowDescription = text("history_chart_more")
        // Wait through fullscreen Activity creation before choosing its responsive toolbar variant.
        // Runtime action and overflow descriptions are distinct, although both are unknown to the IDE.
        @Suppress("RegExpDuplicateAlternationBranch") val descriptions = Pattern.compile(
            "${Pattern.quote(actionDescription)}|${Pattern.quote(overflowDescription)}"
        )
        val control = device.wait(
            Until.findObject(By.pkg(TARGET).desc(descriptions).enabled(true)), LOAD_TIMEOUT
        ) ?: error("Fullscreen chart action did not become ready: $label")
        val fromOverflow = control.contentDescription == overflowDescription
        click(control)
        if (fromOverflow) {
            val menuAction = device.wait(
                Until.findObject(By.pkg(TARGET).text(actionDescription).enabled(true)), LOAD_TIMEOUT
            ) ?: error("Fullscreen chart menu action did not become ready: $label")
            click(menuAction)
        }
    }

    private fun scroll(direction: Direction) {
        val selector = By.pkg(TARGET).scrollable(true)
        check(device.wait(Until.hasObject(selector), UI_TIMEOUT)) {
            "Visible scroll container did not become ready"
        }
        val list = device.findObjects(selector).lastOrNull()
            ?: error("Visible scroll container unavailable")
        if (accessibilityActions) {
            val action =
                if (direction == Direction.DOWN) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            accessibilityAction(list, action)
        } else {
            list.setGestureMargin(device.displayWidth / 6)
            list.fling(direction)
        }
        device.waitForIdle(IDLE_TIMEOUT)
    }

    private fun clickText(name: String, last: Boolean = false) {
        awaitText(name)
        val matches = device.findObjects(By.pkg(TARGET).text(text(name)))
        click(if (last) matches.last() else matches.first())
    }

    private fun awaitText(name: String): UiObject2 =
        device.wait(Until.findObject(By.pkg(TARGET).text(text(name))), UI_TIMEOUT)
            ?: error("Missing target text resource: $name")

    private fun clickDescription(name: String) = click(awaitDescription(name))

    private fun click(target: UiObject2) {
        if (accessibilityActions) accessibilityAction(target, AccessibilityNodeInfo.ACTION_CLICK)
        else target.click()
    }

    private fun setText(target: UiObject2, value: String) {
        if (accessibilityActions) accessibilityAction(
            target, AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })
        else target.text = value
    }

    private fun goBack() {
        if (accessibilityActions) check(
            instrumentation.uiAutomation.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_BACK
            )
        ) {
            "Accessibility global Back unavailable"
        }
        else device.pressBack()
    }

    private fun goHome() {
        if (accessibilityActions) check(
            instrumentation.uiAutomation.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_HOME
            )
        ) {
            "Accessibility global Home unavailable"
        }
        else device.pressHome()
    }

    private fun dismissKeyboard() {
        val windows = instrumentation.uiAutomation.windows
        try {
            if (windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) goBack()
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    // Accessibility nodes still require recycling on Android 12L and older.
    @Suppress("DEPRECATION")
    private fun accessibilityAction(target: UiObject2, action: Int, arguments: Bundle? = null) {
        val bounds = target.visibleBounds
        val label = target.text
        val description = target.contentDescription
        val className = target.className
        val scrolling =
            action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val root =
            checkNotNull(instrumentation.uiAutomation.rootInActiveWindow) { "Accessibility window unavailable" }
        val matched = try {
            findAccessibilityNode(root) { node ->
                val candidateBounds = Rect()
                node.getBoundsInScreen(candidateBounds)
                node.packageName?.toString() == TARGET && node.isVisibleToUser && node.className?.toString() == className && node.text?.toString()
                    .orEmpty() == label.orEmpty() && node.contentDescription?.toString()
                    .orEmpty() == description.orEmpty() && (!scrolling || node.isScrollable) && candidateBounds.contains(
                    bounds.centerX(), bounds.centerY()
                )
            }
        } finally {
            root.recycle()
        }
        var node =
            checkNotNull(matched) { "Accessibility target disappeared: $label / $description" }
        try {
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                while (!node.isClickable) {
                    val parent = node.parent ?: break
                    node.recycle()
                    node = parent
                }
            }
            check(
                node.performAction(
                    action, arguments
                )
            ) { "Accessibility action $action unavailable: $label / $description" }
        } finally {
            node.recycle()
        }
    }

    // The copy constructor requires API 30; pooled nodes also need recycling before API 33.
    @Suppress("DEPRECATION")
    private fun findAccessibilityNode(
        node: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (matches(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                findAccessibilityNode(child, matches)?.let { return it }
            } finally {
                child.recycle()
            }
        }
        return null
    }

    private fun awaitDescription(name: String): UiObject2 =
        device.wait(Until.findObject(By.pkg(TARGET).desc(text(name)).enabled(true)), UI_TIMEOUT)
            ?: error("Missing target description resource: $name")

    private fun text(name: String): String {
        val id = resources.getIdentifier(name, "string", TARGET).takeIf { it != 0 }
            ?: resources.getIdentifier(name, "string", "codes.swistak.batterymonitor")
        check(id != 0) { "Unknown target string resource: $name" }
        return resources.getString(id)
    }

    private fun reportVisibleText(name: String) = report(name, Bundle().apply {
        putStringArrayList(
            "text",
            ArrayList(
                device.findObjects(By.pkg(TARGET).textStartsWith("")).mapNotNull { it.text }
                    .filter { it.isNotBlank() }.distinct()
            )
        )
    })

    private fun report(name: String, data: Bundle) {
        data.putString("profilingRecord", name)
        instrumentation.sendStatus(0, data)
    }

    companion object {
        private const val TARGET = "codes.swistak.batterymonitor.profile"
        private const val MAIN_ACTIVITY = "codes.swistak.batterymonitor.app.BatteryInfoActivity"
        private const val WIDGET_CONFIGURATION =
            "codes.swistak.batterymonitor.ui.widgets.WidgetConfigurationActivity"
        private const val FIXTURE_RECEIVER =
            "codes.swistak.batterymonitor.profiling.ProfileFixtureReceiver"
        private const val EXTRA_SECTION = "codes.swistak.batterymonitor.EXTRA_SECTION"
        private const val EXTRA_DETAIL = "codes.swistak.batterymonitor.EXTRA_DETAIL"
        private const val MIN_ANCHOR_MILLIS = 1_577_836_800_000L
        private const val FIXTURE_ROWS = 21_570
        private const val WIDGET_HOST_ID = 7631
        private const val UI_TIMEOUT = 10_000L
        private const val LOAD_TIMEOUT = 60_000L
        private const val PROMPT_TIMEOUT = 500L
        private const val IDLE_TIMEOUT = 500L
        private const val IDLE_WINDOW_MILLIS = 10_000L
    }
}
