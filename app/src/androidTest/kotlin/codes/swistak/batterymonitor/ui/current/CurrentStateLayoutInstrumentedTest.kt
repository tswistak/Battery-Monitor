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
package codes.swistak.batterymonitor.ui.current

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.view.inspector.WindowInspectorCompat
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.logs.HistoryChartModel
import codes.swistak.batterymonitor.logs.HistoryKey
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryPoint
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.HistorySeries
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringAvailability
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringSnapshot
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringUiState
import codes.swistak.batterymonitor.monitoring.presentation.PredictionSnapshot
import codes.swistak.batterymonitor.settings.LongDurationFormat
import codes.swistak.batterymonitor.ui.components.MeasurementChart
import codes.swistak.batterymonitor.ui.history.FullscreenChartActivity
import codes.swistak.batterymonitor.ui.navigation.SectionOwner
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class CurrentStateLayoutInstrumentedTest {
    @Test
    fun monitoring_bundle_keeps_the_effective_target_when_the_full_range_ETA_is_cleared() {
        val info = BatteryInfo().apply {
            percent = 88
            status = BatteryInfo.STATUS_NOT_CHARGING
            plugged = BatteryInfo.PLUGGED_USB
            effectivePredictionTargetPercent = 80
            prediction.markTargetReached(80)
            fullRangePrediction.clear()
        }
        val bundle = info.toBundle()
        val snapshot = MonitoringSnapshot.fromBundle(bundle)
        assertEquals(80, snapshot.effectivePredictionTargetPercent)
        assertEquals(0, snapshot.fullRangePrediction.targetPercent)
        assertEquals(BatteryInfo.Prediction.NONE, snapshot.fullRangePrediction.direction)
        assertTrue(
            currentStateModel(
                MonitoringUiState(
                    MonitoringAvailability.LIVE, snapshot
                )
            ).hasAlternative
        )
        bundle.remove("effective_prediction_target")
        assertEquals(80, MonitoringSnapshot.fromBundle(bundle).effectivePredictionTargetPercent)
    }

    @Test
    fun overview_percentage_points_keep_their_physical_height_as_samples_arrive() {
        val host = instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        instrumentation.startActivitySync(
            FullscreenChartActivity.intent(
                context, HistoryMetric.LEVEL, HistoryRangeState(0, 1), "custom"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        var values by mutableStateOf<List<Double>>(emptyList())
        var origin = Offset.Zero
        var plotWidth = 0
        var color = 0
        try {
            await {
                val hostActivity = host.lastActivity as? FullscreenChartActivity
                hostActivity != null && !hostActivity.isDestroyed && hostActivity.lifecycle.currentState == Lifecycle.State.RESUMED && (hostActivity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || android.os.Build.VERSION.SDK_INT >= 36 && hostActivity.resources.configuration.smallestScreenWidthDp >= 600 || hostActivity.isInMultiWindowMode)
            }
            activity = host.lastActivity as FullscreenChartActivity
            for (width in listOf(390, 1000)) {
                main {
                    density = minOf(
                        activity.window.decorView.width.toFloat() / width,
                        activity.window.decorView.height.toFloat() / 240
                    )
                    activity.setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                            AppBatteryTheme {
                                color = MaterialTheme.colorScheme.primary.toArgb()
                                val series = HistorySeries(values.mapIndexed { index, value ->
                                    HistoryPoint(
                                        HistoryKey(
                                            1_800_000L + index * 60_000, index.toLong()
                                        ), value
                                    )
                                }, values.minOrNull(), values.maxOrNull())
                                val chart = HistoryChartModel(
                                    HistoryRangeState(0, 3_600_000),
                                    values.size.toLong(),
                                    mapOf(HistoryMetric.LEVEL to series),
                                    emptyList()
                                )
                                Box(Modifier.size(width.dp, 200.dp)) {
                                    MeasurementChart(
                                        chart,
                                        HistoryMetric.LEVEL,
                                        false,
                                        null,
                                        {},
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .onGloballyPositioned {
                                                origin = it.positionInWindow()
                                                plotWidth = it.size.width
                                            },
                                        compact = true,
                                        compactChartHeight = 100.dp,
                                        fixedPercentageScale = true
                                    )
                                }
                            }
                        }
                    }
                }
                val cases = listOf(
                    emptyList(),
                    listOf(88.0),
                    listOf(88.0, 88.0),
                    listOf(89.0, 88.0),
                    listOf(0.0),
                    listOf(100.0),
                    listOf(1.0, 0.0),
                    listOf(99.0, 100.0)
                )
                for (samples in cases) {
                    main { values = samples }
                    instrumentation.waitForIdleSync()
                    if (samples.isEmpty()) {
                        await { hasText(context.getString(R.string.current_unavailable)) }
                    } else {
                        await { plotWidth > 0 && !hasText(context.getString(R.string.current_unavailable)) }
                        // Inspect the selected marker in the real Canvas, including single and flat series.
                        await {
                            var matches = false
                            main {
                                val decor = activity.window.decorView
                                val bitmap = Bitmap.createBitmap(
                                    decor.width, decor.height, Bitmap.Config.ARGB_8888
                                )
                                decor.draw(Canvas(bitmap))
                                val inset = 8 * density
                                val time = 1_800_000L + (samples.size - 1) * 60_000
                                val x =
                                    origin.x + inset + time / 3_600_000f * (plotWidth - 2 * inset)
                                val y = origin.y + (100 - 8) * density - samples.last()
                                    .toFloat() / 100 * (100 - 16) * density
                                matches = bitmap.getPixel(x.roundToInt(), y.roundToInt()) == color
                                bitmap.recycle()
                            }
                            matches
                        }
                    }
                }
            }
        } finally {
            main { host.lastActivity?.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(host)
        }
    }

    @Test
    fun paused_target_card_switches_without_a_zero_target_duplicate_or_invented_ETA() {
        val host = instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        instrumentation.startActivitySync(
            FullscreenChartActivity.intent(
                context, HistoryMetric.LEVEL, HistoryRangeState(0, 1), "custom"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val reached = PredictionSnapshot(BatteryInfo.Prediction.NONE, 80, true, 0, 0, 0)
        val cleared = PredictionSnapshot(BatteryInfo.Prediction.NONE, 0, false, 2, 12, 30)
        var reading by mutableStateOf(
            MonitoringSnapshot(
                88,
                BatteryInfo.STATUS_NOT_CHARGING,
                BatteryInfo.HEALTH_GOOD,
                BatteryInfo.PLUGGED_USB,
                270,
                4120,
                null,
                BatteryInfo.STATUS_CHARGING,
                -1,
                0,
                reached,
                cleared,
                2000,
                effectivePredictionTargetPercent = 80
            )
        )
        var availability by mutableStateOf(MonitoringAvailability.LIVE)
        var available by mutableStateOf(true)
        try {
            await {
                val hostActivity = host.lastActivity as? FullscreenChartActivity
                hostActivity != null && !hostActivity.isDestroyed && hostActivity.lifecycle.currentState == Lifecycle.State.RESUMED && (hostActivity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || android.os.Build.VERSION.SDK_INT >= 36 && hostActivity.resources.configuration.smallestScreenWidthDp >= 600 || hostActivity.isInMultiWindowMode)
            }
            activity = host.lastActivity as FullscreenChartActivity
            main { DisplayStrings.setResources(activity.resources) }
            for (width in listOf(390, 1000)) {
                render(width, 850, monitorOverride = {
                    if (available) MonitoringUiState(availability, reading) else MonitoringUiState()
                })
                if (width == 390) scrollTo("prediction")
                val reachedText = context.getString(R.string.current_target_reached, 80)
                await { hasText(reachedText) }
                assertFalse(
                    hasText(
                        "$reachedText ${
                            context.getString(
                                R.string.current_prediction_target, 80
                            )
                        }"
                    )
                )
                click(context.getString(R.string.current_show_to_target, 100))
                val paused100 = "${context.getString(R.string.current_paused)} ${
                    context.getString(
                        R.string.current_prediction_target, 100
                    )
                }"
                await { hasText(paused100) }
                assertFalse(hasText(reachedText))
                assertFalse(hasText(context.getString(R.string.current_show_to_target, 0)))
                main { availability = MonitoringAvailability.STALE }
                await { hasText("— ${context.getString(R.string.current_prediction_target, 100)}") }
                main { available = false }
                await { !hasText(context.getString(R.string.current_show_to_target, 80)) }
                main {
                    available = true
                    availability = MonitoringAvailability.LIVE
                }
                await { hasText(paused100) }
                main { reading = reading.copy(observedAtMillis = reading.observedAtMillis + 1000) }
                await { hasText(paused100) }
                main {
                    reading = reading.copy(
                        status = BatteryInfo.STATUS_CHARGING,
                        fullRangePrediction = PredictionSnapshot(
                            BatteryInfo.Prediction.UNTIL_CHARGED, 100, false, 0, 0, 40
                        )
                    )
                }
                await { hasText(context.getString(R.string.current_show_to_target, 80)) }
                assertFalse(hasText(paused100))
                click(context.getString(R.string.current_show_to_target, 80))
                await { hasText(reachedText) }
                main {
                    reading = reading.copy(
                        status = BatteryInfo.STATUS_FULLY_CHARGED,
                        levelPercent = 100,
                        fullRangePrediction = cleared
                    )
                }
                await {
                    hasText(
                        "${context.getString(R.string.status_fully_charged)} ${
                            context.getString(
                                R.string.current_prediction_target, 80
                            )
                        }"
                    )
                }
                main {
                    reading =
                        reading.copy(status = BatteryInfo.STATUS_NOT_CHARGING, levelPercent = 88)
                }
            }
        } finally {
            main { host.lastActivity?.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(host)
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var activity: FullscreenChartActivity
    private var density = 1f
    private var selectedSection: SectionOwner? = null
    private var batteryUsageRequests = 0
    private var requestedLayoutGeneration = 0
    private var renderedLayoutGeneration = 0

    @Test
    fun narrow_sections_are_lazy_and_grouped_actions_survive_wide_large_font_and_rtl() {
        val monitor =
            instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        instrumentation.startActivitySync(
            FullscreenChartActivity.intent(
                context, HistoryMetric.LEVEL, HistoryRangeState(0, 1), "custom"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            await {
                val host = monitor.lastActivity as? FullscreenChartActivity
                host != null && !host.isDestroyed && (host.resources.configuration.smallestScreenWidthDp >= 600 || host.isInMultiWindowMode || host.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) && host.lifecycle.currentState == Lifecycle.State.RESUMED
            }
            instrumentation.waitForIdleSync()
            activity = monitor.lastActivity as FullscreenChartActivity
            main { DisplayStrings.setResources(activity.resources) }
            render(390, 280)
            await { hasHero() }
            assertFalse(hasText(context.getString(R.string.history_level_24h)))
            assertFalse(hasText(context.getString(R.string.current_system_usage)))

            scrollTo("measurement_grid")
            val labels = listOf(
                R.string.current_temperature,
                R.string.current_voltage,
                R.string.pref_cat_battery_current_main,
                R.string.battery_power,
                R.string.current_android_health
            ).map { context.getString(it) }
            await { hasText(labels.first()) }
            main {
                labels.forEach {
                    assertTrue(clickable(it).config.contains(SemanticsActions.OnClick))
                }
            }
            click(labels.first())
            val detailSource = context.getString(
                R.string.current_detail_source, "current-layout-fixture.temperature"
            )
            await { hasText(detailSource) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { !hasText(detailSource) }

            scrollTo("trend_history")
            await { hasText(context.getString(R.string.history_level_24h)) }
            click(context.getString(R.string.nav_history))
            main { assertEquals(SectionOwner.HISTORY, selectedSection) }
            scrollTo("system_usage")
            await { hasText(context.getString(R.string.current_system_usage)) }
            click(context.getString(R.string.current_system_usage))
            main { assertEquals(1, batteryUsageRequests) }

            render(390, 280, fontScale = 2f, direction = LayoutDirection.Rtl)
            scrollTo("measurement_grid")
            await { hasText(labels.first()) }
            main {
                assertTrue(
                    "Large fonts retain a single column of metric actions",
                    clickable(labels[1]).boundsInRoot.top > clickable(labels[0]).boundsInRoot.top
                )
                labels.forEach {
                    assertTrue(clickable(it).config.contains(SemanticsActions.OnClick))
                }
            }

            for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                render(1000, 700, direction = direction)
                await { hasText(labels.first()) && hasHero() }
                main {
                    val hero = nodes().first {
                        it.config.getOrNull(SemanticsProperties.ContentDescription)
                            ?.any { description -> description.startsWith("72%") } == true
                    }.boundsInRoot
                    val temperature = clickable(labels[0]).boundsInRoot
                    val voltage = clickable(labels[1]).boundsInRoot
                    val gridLeft = minOf(temperature.left, voltage.left)
                    val gridRight = maxOf(temperature.right, voltage.right)
                    assertEquals(
                        "Independent columns start together", hero.top, temperature.top, 1f
                    )
                    assertEquals(
                        "Overview keeps its 1.02 weight",
                        1.02f,
                        hero.width / (gridRight - gridLeft),
                        0.02f
                    )
                    val gap = if (direction == LayoutDirection.Ltr) gridLeft - hero.right
                    else hero.left - gridRight
                    assertEquals("Wide columns retain their gap", 24f * density, gap, 2f)
                    assertTrue(list().config[SemanticsProperties.IndexForKey]("expanded_content") >= 0)
                }
            }
        } finally {
            main { monitor.lastActivity?.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun render(
        width: Int,
        height: Int,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        monitorOverride: () -> MonitoringUiState? = { null }
    ) {
        val generation = ++requestedLayoutGeneration
        val prediction = PredictionSnapshot(
            BatteryInfo.Prediction.UNTIL_DRAINED, 20, false, 0, 2, 20
        )
        val snapshot = MonitoringSnapshot(
            levelPercent = 72,
            status = BatteryInfo.STATUS_DISCHARGING,
            health = BatteryInfo.HEALTH_GOOD,
            plugged = BatteryInfo.PLUGGED_UNPLUGGED,
            temperatureTenthsCelsius = 302,
            voltageMillivolts = 3880,
            remainingChargeMicroampHours = 1840000L,
            lastStatus = BatteryInfo.STATUS_DISCHARGING,
            lastPercent = -1,
            lastStatusTimeMillis = 0,
            configuredPrediction = prediction,
            fullRangePrediction = prediction,
            observedAtMillis = 2000,
            fieldSources = mapOf("temperature" to "current-layout-fixture.temperature")
        )
        val model = CurrentStateModel(
            snapshot,
            CurrentCondition.DISCHARGING,
            prediction,
            hasAlternative = false,
            showingFullRange = false,
            notificationsDisabled = false
        )
        val preferences = CurrentPreferences(
            showRemainingCharge = true,
            preferAverageCurrent = false,
            currentRefreshMillis = 2000,
            currentMultiplier = 1,
            fahrenheit = false,
            longDurationFormat = LongDurationFormat.DAYS_AND_HOURS,
            predictionMethod = "-3",
            loggingEnabled = true
        )
        val trend = HistoryChartModel(
            HistoryRangeState(0, 3600000), 2, mapOf(
                HistoryMetric.LEVEL to HistorySeries(
                    listOf(
                        HistoryPoint(HistoryKey(0, 1), 72.0),
                        HistoryPoint(HistoryKey(1800000, 2), 71.0)
                    ), 71.0, 72.0
                )
            ), emptyList()
        )
        main {
            density = minOf(
                activity.window.decorView.width.toFloat() / width,
                activity.window.decorView.height.toFloat() / height
            )
            activity.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(density, fontScale),
                    LocalLayoutDirection provides direction
                ) {
                    AppBatteryTheme {
                        key(width, height, fontScale, direction) {
                            var full by rememberSaveable { mutableStateOf(false) }
                            val presentation = monitorOverride()?.let {
                                currentStateModel(it, full)
                            } ?: model
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .onGloballyPositioned {
                                        renderedLayoutGeneration = generation
                                    }) {
                                CurrentStateScreen(
                                    model = presentation,
                                    preferences = preferences,
                                    currentReading = CurrentReading(
                                        220.0, 2000, "current-layout-fixture.current"
                                    ),
                                    onToggleTarget = { full = !full },
                                    onSection = { selectedSection = it },
                                    onBatteryUsage = { batteryUsageRequests++ },
                                    trend = trend,
                                    modifier = Modifier.size(width.dp, height.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        instrumentation.waitForIdleSync()
        // A replacement composition is applied on a frame after the Android queue can become idle.
        await {
            var rendered = false
            main { rendered = renderedLayoutGeneration == generation }
            rendered
        }
    }

    private fun scrollTo(key: String) {
        main {
            val list = list()
            val index = list.config[SemanticsProperties.IndexForKey](key)
            assertTrue("Missing lazy section: $key", index >= 0)
            assertTrue(list.config[SemanticsActions.ScrollToIndex].action?.invoke(index) == true)
        }
        instrumentation.waitForIdleSync()
    }

    private fun list() = nodes().single { it.config.contains(SemanticsActions.ScrollToIndex) }

    private fun clickable(text: String) = nodes().first {
        it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.Text)
            ?.any { value -> value.text == text } == true
    }

    private fun click(text: String) {
        main { assertTrue(clickable(text).config[SemanticsActions.OnClick].action?.invoke() == true) }
        instrumentation.waitForIdleSync()
    }

    private fun hasText(text: String): Boolean {
        var found = false
        main {
            found = nodes().any {
                it.config.getOrNull(SemanticsProperties.Text)
                    ?.any { value -> value.text == text } == true
            }
        }
        return found
    }

    private fun hasHero(): Boolean {
        var found = false
        main {
            found = nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.any { description -> description.startsWith("72%") } == true
            }
        }
        return found
    }

    private fun nodes(): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)

        fun owners(view: View): List<SemanticsOwner> {
            val owner = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }
                ?.invoke(view) as? SemanticsOwner
            if (owner != null) return listOf(owner)
            if (view !is ViewGroup) return emptyList()
            return (0 until view.childCount).flatMap { owners(view.getChildAt(it)) }
        }
        return WindowInspectorCompat.getGlobalWindowViews().flatMap(::owners)
            .flatMap { descendants(it.rootSemanticsNode) }
    }

    private fun main(action: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action()
        else {
            var failure: Throwable? = null
            instrumentation.runOnMainSync {
                try {
                    action()
                } catch (error: Throwable) {
                    failure = error
                }
            }
            failure?.let { throw it }
        }
    }

    private fun await(ready: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!ready() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("Current state did not reach the expected layout", ready())
    }
}
