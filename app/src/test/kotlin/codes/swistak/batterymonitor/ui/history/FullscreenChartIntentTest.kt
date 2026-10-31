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
package codes.swistak.batterymonitor.ui.history

import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class FullscreenChartIntentTest {
    @Test
    fun explicit_intent_roundtrips_metric_exact_range_and_label() {
        val exact = HistoryRangeState(1_800_000_000_123, 1_800_003_600_124)
        val intent = FullscreenChartActivity.intent(
            org.robolectric.RuntimeEnvironment.getApplication(),
            HistoryMetric.POWER,
            exact,
            "custom"
        )
        assertEquals(FullscreenChartActivity::class.java.name, intent.component!!.className)
        assertEquals(
            FullscreenChartRequest(HistoryMetric.POWER, exact), fullscreenChartRequest(
                intent.getStringExtra("chart.metric"),
                intent.getLongExtra("chart.start", -1),
                intent.getLongExtra("chart.end", -1)
            )
        )
        assertEquals("custom", intent.getStringExtra("chart.label"))
    }
}
