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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.net.Uri
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.alarms.refreshTargetAlarmChannelNames
import codes.swistak.batterymonitor.ui.alarms.alarmTypeLabel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "en")
class AlarmTargetPresentationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val channelIds = listOf("charging_limit_met", "discharging_limit_met")

    @After
    fun removeTestChannels() {
        channelIds.forEach(manager::deleteNotificationChannel)
    }

    @Test
    fun `existing target channels get new names without changing their identities or settings`() {
        val tone = Uri.parse("content://test/legacy-tone")
        val vibration = longArrayOf(0, 137, 251)
        for ((id, oldName) in listOf(
            "charging_limit_met" to "Charging limit met",
            "discharging_limit_met" to "Discharging limit met"
        )) {
            manager.createNotificationChannel(
                NotificationChannel(id, oldName, NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(tone, AudioAttributes.Builder().build())
                    vibrationPattern = vibration
                })
        }

        assertFalse(MonitoringDesiredState.read(context))
        refreshTargetAlarmChannelNames(context)
        assertFalse(MonitoringDesiredState.read(context))

        for ((id, name) in listOf(
            "charging_limit_met" to "Charging target reached",
            "discharging_limit_met" to "Discharging target reached"
        )) {
            val channel = requireNotNull(manager.getNotificationChannel(id))
            assertEquals(id, channel.id)
            assertEquals(name, channel.name.toString())
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertEquals(tone, channel.sound)
            assertTrue(channel.shouldVibrate())
            assertArrayEquals(vibration, channel.vibrationPattern)
        }
    }

    @Test
    fun `refresh leaves missing target channels absent`() {
        channelIds.forEach { assertNull(manager.getNotificationChannel(it)) }

        refreshTargetAlarmChannelNames(context)

        channelIds.forEach { assertNull(manager.getNotificationChannel(it)) }
    }

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
