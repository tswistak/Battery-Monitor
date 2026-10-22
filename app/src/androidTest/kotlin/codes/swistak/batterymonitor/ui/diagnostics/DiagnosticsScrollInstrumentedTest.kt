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
package codes.swistak.batterymonitor.ui.diagnostics

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.monitoring.Predictor
import codes.swistak.batterymonitor.monitoring.PredictorStoredState
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringSnapshot
import codes.swistak.batterymonitor.ui.history.FullscreenChartActivity
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsScrollInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: FullscreenChartActivity

    @Test
    fun staged_raw_groups_keep_entry_at_top_but_preserve_a_readers_position() {
        val context = instrumentation.targetContext
        val host = instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        instrumentation.startActivitySync(
            FullscreenChartActivity.intent(
                context, HistoryMetric.LEVEL, HistoryRangeState(0, 1), "custom"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        var tab by mutableIntStateOf(0)
        var state by mutableStateOf(DiagnosticsState(rawLoading = true))
        val monitor = MonitoringSnapshot.fromBundle(BatteryInfo().toBundle()).copy(
            predictorData = PredictorStoredState(mapOf(Predictor.KEY_AVERAGE[0] to 864000f), 2)
        )
        try {
            await {
                val hostActivity = host.lastActivity as? FullscreenChartActivity
                hostActivity != null && !hostActivity.isDestroyed && hostActivity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED && (hostActivity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE || android.os.Build.VERSION.SDK_INT >= 36 && hostActivity.resources.configuration.smallestScreenWidthDp >= 600 || hostActivity.isInMultiWindowMode)
            }
            activity = host.lastActivity as FullscreenChartActivity
            main {
                activity.setContent {
                    AppBatteryTheme {
                        // Keep the other tab scrollable even in a tall tablet window.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(400.dp)
                        ) {
                            DiagnosticsScreen(
                                state,
                                {},
                                {},
                                {},
                                {},
                                { tab = it },
                                monitor = monitor,
                                tab = tab
                            )
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            main { tab = 3 }
            await { indexOf("raw:${R.string.device_data_predictor}:Battery Monitor:header") >= 0 }
            assertTop()
            main { state = state.copy(basic = snapshot("Android", "basic")) }
            await { indexOf("raw:${R.string.advanced_section_service}:Android:basic_0") >= 0 }
            assertTop()
            main { state = state.copy(appRaw = snapshot("App UID", "app")) }
            await { indexOf("raw:${R.string.advanced_section_service}:App UID:app_0") >= 0 }
            assertTop()
            main { state = state.copy(advanced = snapshot("root", "root"), rawLoading = false) }
            await { indexOf("raw:${R.string.advanced_section_service}:Root:root_0") >= 0 }
            assertTop()

            main { assertTrue(list().config[SemanticsActions.ScrollToIndex].action!!.invoke(30)) }
            await { scroll() >= 30f }
            val before = scroll()
            main { state = state.copy(basic = snapshot("Android", "basic", "updated")) }
            instrumentation.waitForIdleSync()
            await { texts().any { it.contains("updated") } }
            assertEquals(before, scroll(), 0.01f)
            main { tab = 1 }
            await { indexOf("1:0:${R.string.advanced_section_counters}") >= 0 }
            main { list().config[SemanticsActions.ScrollToIndex].action!!.invoke(2) }
            await { scroll() > 0f }
            main { tab = 3 }
            await { indexOf("raw:${R.string.advanced_section_service}:Android:basic_0") >= 0 }
            assertTop()
            main { tab = 0 }
            instrumentation.waitForIdleSync()
            main { tab = 3 }
            assertTop()
        } finally {
            main { host.lastActivity?.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(host)
        }
    }

    private fun snapshot(access: String, prefix: String, value: String = "original") =
        AdvancedBatterySnapshot().apply {
            accessMethod = access
            serviceLabels.addAll((0..49).map { "${prefix}_$it" })
            serviceValues.addAll(List(50) { value })
            sysfsLabels.add("$prefix/sysfs")
            sysfsValues.add(value)
            metadataLabels.add("$prefix/metadata")
            metadataValues.add(value)
        }

    private fun assertTop() {
        instrumentation.waitForIdleSync()
        await { scroll() == 0f }
        assertEquals(0f, scroll(), 0f)
    }

    private fun list() = nodes().first { it.config.contains(SemanticsActions.ScrollToIndex) }
    private fun indexOf(key: String): Int {
        var index = -1
        main {
            index =
                nodes().firstOrNull { it.config.contains(SemanticsProperties.IndexForKey) }?.config?.get(
                    SemanticsProperties.IndexForKey
                )?.invoke(key) ?: -1
        }
        return index
    }

    private fun scroll(): Float {
        var value = -1f
        main {
            value =
                nodes().firstOrNull { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }?.config?.get(
                    SemanticsProperties.VerticalScrollAxisRange
                )?.value?.invoke() ?: -1f
        }
        return value
    }

    private fun texts(): List<String> {
        var values = emptyList<String>()
        main {
            values = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .map { it.text }
        }
        return values
    }

    private fun nodes(): List<SemanticsNode> {
        fun children(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::children)

        fun owners(view: View): List<SemanticsOwner> {
            val owner = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }
                ?.invoke(view) as? SemanticsOwner
            if (owner != null) return listOf(owner)
            return if (view is ViewGroup) (0 until view.childCount).flatMap {
                owners(
                    view.getChildAt(
                        it
                    )
                )
            } else emptyList()
        }
        return owners(activity.window.decorView).flatMap { children(it.rootSemanticsNode) }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(ready: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!ready() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("Diagnostics did not reach the expected staged scroll position", ready())
    }
}
