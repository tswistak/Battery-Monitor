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

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.monitoring.Predictor
import codes.swistak.batterymonitor.monitoring.PredictorStoredState
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringSnapshot
import codes.swistak.batterymonitor.testsupport.ComposeTestActivity
import codes.swistak.batterymonitor.testsupport.awaitCondition
import codes.swistak.batterymonitor.testsupport.composeNodes
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import codes.swistak.batterymonitor.testsupport.runOnMain as main

class DiagnosticsScrollInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: ComposeTestActivity

    @Test
    fun staged_raw_groups_keep_entry_at_top_but_preserve_a_readers_position() {
        val context = instrumentation.targetContext
        val scenario = ActivityScenario.launch(ComposeTestActivity::class.java)
        var tab by mutableIntStateOf(0)
        var visible by mutableStateOf(true)
        var stopped by mutableStateOf(false)
        var savedPredictor by mutableStateOf(SavedPredictorDiagnostics())
        var state by mutableStateOf(DiagnosticsState(rawLoading = true))
        val monitor = MonitoringSnapshot.fromBundle(BatteryInfo().toBundle()).copy(
            predictorData = PredictorStoredState(mapOf(Predictor.KEY_AVERAGE[0] to 864000f), 2)
        )
        scenario.use { scenario ->
            scenario.onActivity { activity = it }
            main {
                activity.setContent {
                    AppBatteryTheme {
                        val savedScreens = rememberSaveableStateHolder()
                        if (visible) savedScreens.SaveableStateProvider("diagnostics") {
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
                                    tab = tab,
                                    savedPredictor = savedPredictor,
                                    monitoringStopped = stopped
                                )
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            main { tab = 3 }
            await { indexOf("raw:${R.string.device_data_predictor}:header") >= 0 }
            assertTop()
            main { state = state.copy(basic = snapshot("Android", "basic")) }
            await { indexOf("raw:${R.string.diag_section_service}:basic_0") >= 0 }
            assertTop()
            main { state = state.copy(appRaw = snapshot("App UID", "app")) }
            await { indexOf("raw:${R.string.diag_section_service}:app_0") >= 0 }
            assertTop()
            main { state = state.copy(advanced = snapshot("root", "root"), rawLoading = false) }
            await { indexOf("raw:${R.string.diag_section_service}:root_0") >= 0 }
            assertTop()
            val metadataHeader = indexOf("raw:${R.string.diag_section_metadata}:header")
            val metadataCurrent =
                indexOf("raw:${R.string.diag_section_metadata}:BATTERY_PROPERTY_CURRENT_NOW")
            assertEquals(metadataHeader + 1, metadataCurrent)
            for ((offset, prefix) in listOf("basic", "app", "root").withIndex()) {
                assertEquals(
                    metadataCurrent + offset + 1,
                    indexOf("raw:${R.string.diag_section_metadata}:$prefix/metadata")
                )
            }

            main { assertTrue(list().config[SemanticsActions.ScrollToIndex].action!!.invoke(30)) }
            await { scroll() >= 30f }
            val before = scroll()
            main { state = state.copy(basic = snapshot("Android", "basic", "updated")) }
            instrumentation.waitForIdleSync()
            await { texts().any { it.contains("updated") } }
            assertEquals(before, scroll(), 0.01f)
            main { visible = false }
            await { !hasList() }
            main { visible = true }
            await { indexOf("raw:${R.string.diag_section_service}:basic_0") >= 0 }
            assertEquals(before, scroll(), 0.01f)
            await { indexOf("raw:${R.string.device_data_predictor}:header") >= 0 }
            main {
                assertTrue(list().config[SemanticsActions.ScrollBy].action!!.invoke(0f, 100_000f))
            }
            await { atBottom() && scroll() > before }
            val bottomBefore = scroll()
            assertTrue(bottomBefore > before)
            main { visible = false }
            await { !hasList() }
            main { visible = true }
            await { indexOf("raw:${R.string.device_data_predictor}:header") >= 0 }
            assertEquals(bottomBefore, scroll(), 0.01f)
            main {
                visible = false
                stopped = true
                savedPredictor = SavedPredictorDiagnostics(loading = true)
            }
            await { !hasList() }
            main { visible = true }
            await { texts().contains(context.getString(R.string.diag_raw)) }
            main {
                assertFalse(nodes().any { it.config.contains(SemanticsActions.ScrollToIndex) })
            }
            main {
                savedPredictor = SavedPredictorDiagnostics(
                    data = monitor.predictorData, checkedAtMillis = 2_000
                )
            }
            await { indexOf("raw:${R.string.device_data_predictor}:header") >= 0 }
            assertEquals(bottomBefore, scroll(), 0.01f)
            main { savedPredictor = savedPredictor.copy(loading = true) }
            instrumentation.waitForIdleSync()
            assertEquals(bottomBefore, scroll(), 0.01f)
            main { savedPredictor = savedPredictor.copy(loading = false) }
            main { tab = 1 }
            await { indexOf("1:0:${R.string.diag_section_counters}") >= 0 }
            main { list().config[SemanticsActions.ScrollToIndex].action!!.invoke(2) }
            await { scroll() > 0f }
            main { tab = 3 }
            await { indexOf("raw:${R.string.diag_section_service}:basic_0") >= 0 }
            assertTop()
            main { tab = 0 }
            instrumentation.waitForIdleSync()
            main { tab = 3 }
            assertTop()
        }
    }

    private fun snapshot(access: String, prefix: String, value: String = "original") =
        AdvancedBatterySnapshot().apply {
            accessMethod = access
            serviceLabels.addAll((0..49).map { "${prefix}_$it" })
            serviceValues.addAll(List(50) { value })
            sysfsLabels.add("$prefix/sysfs")
            sysfsValues.add(value)
            metadataLabels.add("BATTERY_PROPERTY_CURRENT_NOW")
            metadataValues.add(value)
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

    private fun hasList(): Boolean {
        var present = false
        main { present = nodes().any { it.config.contains(SemanticsActions.ScrollToIndex) } }
        return present
    }

    private fun atBottom(): Boolean {
        var bottom = false
        main {
            val range = list().config[SemanticsProperties.VerticalScrollAxisRange]
            bottom = range.value() >= range.maxValue()
        }
        return bottom
    }

    private fun texts(): List<String> {
        var values = emptyList<String>()
        main {
            values = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .map { it.text }
        }
        return values
    }

    private fun nodes(): List<SemanticsNode> = composeNodes(activity.window.decorView)

    private fun await(ready: () -> Boolean) =
        awaitCondition({ "Diagnostics did not reach the expected staged scroll position" }, ready)
}
