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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
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
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringSnapshot
import codes.swistak.batterymonitor.monitoring.presentation.PredictionSnapshot
import codes.swistak.batterymonitor.settings.LongDurationFormat
import codes.swistak.batterymonitor.ui.history.FullscreenChartActivity
import codes.swistak.batterymonitor.ui.navigation.SectionOwner
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentStateLayoutInstrumentedTest {
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
        direction: LayoutDirection = LayoutDirection.Ltr
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
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .onGloballyPositioned {
                                        renderedLayoutGeneration = generation
                                    }) {
                                CurrentStateScreen(
                                    model = model,
                                    preferences = preferences,
                                    currentReading = CurrentReading(
                                        220.0, 2000, "current-layout-fixture.current"
                                    ),
                                    onToggleTarget = {},
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
