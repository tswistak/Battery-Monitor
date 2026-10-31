/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.Configuration
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.monitoring.notifications.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = BatteryMonitorApplication::class, qualifiers = "en")
class NotificationChannelLocaleLifecycleTest {
    @Test
    fun `application configuration changes refresh existing names without restarting or creating channels`() {
        val application = RuntimeEnvironment.getApplication() as BatteryMonitorApplication
        val manager = application.getSystemService(NotificationManager::class.java)
        val id = NotificationChannels.CHAN_ID_A_CHARGING_LIMIT
        val nameResource = R.string.alarm_type_charging_target_reached
        val englishName = application.getString(nameResource)
        val originalQualifiers = RuntimeEnvironment.getQualifiers()
        assertNull(manager.getNotificationChannel(NotificationChannels.CHAN_ID_MAIN))
        manager.createNotificationChannel(
            NotificationChannel(id, englishName, NotificationManager.IMPORTANCE_LOW)
        )

        try {
            RuntimeEnvironment.setQualifiers("pl")
            application.onConfigurationChanged(Configuration(application.resources.configuration))

            assertSame(RuntimeEnvironment.getApplication(), application)
            val polishName = requireNotNull(manager.getNotificationChannel(id)).name.toString()
            assertNotEquals(englishName, polishName)
            assertEquals(application.getString(nameResource), polishName)
            assertNull(manager.getNotificationChannel(NotificationChannels.CHAN_ID_MAIN))

            RuntimeEnvironment.setQualifiers("en")
            application.onConfigurationChanged(Configuration(application.resources.configuration))

            assertEquals(
                englishName, requireNotNull(manager.getNotificationChannel(id)).name.toString()
            )
            assertNull(manager.getNotificationChannel(NotificationChannels.CHAN_ID_MAIN))
        } finally {
            manager.deleteNotificationChannel(id)
            RuntimeEnvironment.setQualifiers(originalQualifiers)
            application.onConfigurationChanged(Configuration(application.resources.configuration))
        }
    }
}
