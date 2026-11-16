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

import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringAvailability
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringSnapshot
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class CurrentStateBundleTest {
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
}
