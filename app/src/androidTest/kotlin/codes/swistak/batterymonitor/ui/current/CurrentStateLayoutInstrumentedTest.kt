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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
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
import codes.swistak.batterymonitor.testsupport.ComposeTestActivity
import codes.swistak.batterymonitor.testsupport.awaitCondition
import codes.swistak.batterymonitor.testsupport.composeWindowNodes
import codes.swistak.batterymonitor.ui.components.MeasurementChart
import codes.swistak.batterymonitor.ui.navigation.SectionOwner
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import codes.swistak.batterymonitor.testsupport.runOnMain as main

class CurrentStateLayoutInstrumentedTest {

    @Test
    fun overview_percentage_points_keep_their_physical_height_as_samples_arrive() {
        val scenario = ActivityScenario.launch(ComposeTestActivity::class.java)
        var values by mutableStateOf<List<Double>>(emptyList())
        var origin = Offset.Zero
        var plotWidth = 0
        var color = 0
        scenario.use { scenario ->
            scenario.onActivity { activity = it }
            val width = 390
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
                listOf(100.0)
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
                            val x = origin.x + inset + time / 3_600_000f * (plotWidth - 2 * inset)
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
    }

    @Test
    fun paused_target_card_switches_without_a_zero_target_duplicate_or_invented_ETA() {
        val scenario = ActivityScenario.launch(ComposeTestActivity::class.java)
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
        scenario.use { scenario ->
            scenario.onActivity { activity = it }
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
                await {
                    !hasText(paused100) && hasText(
                        context.getString(
                            R.string.current_show_to_target, 80
                        )
                    )
                }
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
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var activity: ComposeTestActivity

    private var density = 1f
    private var selectedSection: SectionOwner? = null
    private var batteryUsageRequests = 0
    private var requestedLayoutGeneration = 0
    private var renderedLayoutGeneration = 0

    @Test
    fun current_actions_remain_usable_on_small_wide_large_font_and_rtl_layouts() {
        val scenario = ActivityScenario.launch(ComposeTestActivity::class.java)
        scenario.use { scenario ->
            scenario.onActivity { activity = it }
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
            assertTrue(hasText(context.getString(R.string.current_detail_unit, "°C")))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { !hasText(detailSource) }

            click(labels.last())
            val healthExplanation = context.getString(R.string.current_health_explanation)
            await { hasText(healthExplanation) }
            assertTrue(hasText(context.getString(R.string.health_good)))
            main {
                val unitPrefix = context.getString(R.string.current_detail_unit, "")
                assertFalse(nodes().any { node ->
                    node.config.getOrNull(SemanticsProperties.Text)
                        ?.any { it.text.startsWith(unitPrefix) } == true
                })
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { !hasText(healthExplanation) }

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
                labels.forEach {
                    assertTrue(clickable(it).config.contains(SemanticsActions.OnClick))
                }
            }

            click(labels.first())
            await { hasText(detailSource) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { !hasText(detailSource) }

            for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                render(1000, 700, direction = direction)
                await { hasText(labels.first()) && hasHero() }
                main {
                    labels.forEach {
                        assertTrue(clickable(it).config.contains(SemanticsActions.OnClick))
                    }
                }
                click(labels.first())
                await { hasText(detailSource) }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await { !hasText(detailSource) }
            }
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

    private fun nodes(): List<SemanticsNode> = composeWindowNodes()

    private fun await(ready: () -> Boolean) =
        awaitCondition({ "Current state did not reach the expected layout" }, ready)
}
