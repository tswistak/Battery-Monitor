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
package codes.swistak.batterymonitor.monitoring

import android.app.Application
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.ui.alarms.alarmTypeLabel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "en")
class AlarmTargetPresentationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `legacy target types resolve to target labels and percentage notification text`() {
        assertEquals(
            "Charging target reached", context.getString(alarmTypeLabel("charging_limit_met"))
        )
        assertEquals(
            "Discharging target reached", context.getString(alarmTypeLabel("discharging_limit_met"))
        )
        assertEquals(
            "Charging target reached: 83%",
            context.getString(R.string.alarm_charging_target_reached, 83)
        )
        assertEquals(
            "Discharging target reached: 17%",
            context.getString(R.string.alarm_discharging_target_reached, 17)
        )
    }
}
