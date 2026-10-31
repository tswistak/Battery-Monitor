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
package codes.swistak.batterymonitor.monitoring.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.LocaleList
import android.os.Parcel
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.monitoring.MonitoringDesiredState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    application = Application::class,
    qualifiers = "en",
    shadows = [NotificationChannelsTest.CountingNotificationManager::class]
)
class NotificationChannelsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val shadow get() = Shadow.extract<CountingNotificationManager>(manager)
    private val expectedNames = linkedMapOf(
        "main_004" to R.string.main_notif_chan_name,
        "live_update_001" to R.string.live_updates_notif_chan_name,
        "fully_charged" to R.string.alarm_type_fully_charged,
        "charging_limit_met" to R.string.alarm_type_charging_target_reached,
        "discharging_limit_met" to R.string.alarm_type_discharging_target_reached,
        "charge_drops" to R.string.alarm_type_charge_drops,
        "charge_rises" to R.string.alarm_type_charge_rises,
        "temp_drops" to R.string.alarm_type_temperature_drops,
        "temp_rises" to R.string.alarm_type_temperature_rises,
        "health_failure" to R.string.alarm_type_health_failure
    )

    @After
    fun removeTestChannels() {
        expectedNames.keys.forEach(manager::deleteNotificationChannel)
        manager.deleteNotificationChannel("unowned")
        manager.deleteNotificationChannelGroup("alarms")
    }

    @Test
    @Config(sdk = [26], qualifiers = "pl")
    fun `full registration creates every original channel with its localized name and defaults`() {
        NotificationChannels.ensureChannelsCreated(context)

        assertEquals(expectedNames.keys, manager.notificationChannels.map { it.id }.toSet())
        val group = manager.notificationChannelGroups.single { it.id == "alarms" }
        assertEquals(context.getString(R.string.channel_group_name_alarms), group.name.toString())
        for ((id, resource) in expectedNames) {
            val channel = requireNotNull(manager.getNotificationChannel(id))
            assertEquals(context.getString(resource), channel.name.toString())
            assertEquals(Notification.VISIBILITY_PUBLIC, channel.lockscreenVisibility)
            if (id == "main_004" || id == "live_update_001") {
                assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
                assertNull(channel.sound)
                assertNull(channel.audioAttributes)
                assertFalse(channel.shouldShowLights())
                assertFalse(channel.shouldVibrate())
                assertFalse(channel.canShowBadge())
                assertNull(channel.group)
            } else {
                assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
                assertEquals(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), channel.sound
                )
                assertEquals(
                    AudioAttributes.USAGE_NOTIFICATION_EVENT, channel.audioAttributes.usage
                )
                assertTrue(channel.shouldShowLights())
                assertEquals(-0xcc4a1b, channel.lightColor)
                assertTrue(channel.shouldVibrate())
                assertEquals(
                    listOf(0L, 500L, 500L, 500L, 500L, 1000L, 1000L, 1000L, 1000L),
                    channel.vibrationPattern.toList()
                )
                assertTrue(channel.canShowBadge())
                assertEquals("alarms", channel.group)
            }
        }

        val registrations = shadow.channelUpdates to shadow.groupUpdates
        NotificationChannels.refreshExistingChannelNames(context)
        NotificationChannels.ensureChannelsCreated(context)
        assertEquals(registrations, shadow.channelUpdates to shadow.groupUpdates)
    }

    @Test
    fun `early foreground registration creates only the minimal main channel and keeps its settings`() {
        NotificationChannels.ensureForegroundChannelCreated(context)

        assertEquals(setOf("main_004"), manager.notificationChannels.map { it.id }.toSet())
        assertNull(manager.notificationChannelGroups.firstOrNull { it.id == "alarms" })
        val main = requireNotNull(manager.getNotificationChannel("main_004"))
        assertEquals(context.getString(R.string.main_notif_chan_name), main.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, main.importance)
        assertNull(main.sound)
        assertFalse(main.shouldShowLights())
        assertFalse(main.shouldVibrate())
        assertFalse(main.canShowBadge())
        assertEquals(
            NotificationChannel(
                "baseline", "Baseline", NotificationManager.IMPORTANCE_LOW
            ).lockscreenVisibility, main.lockscreenVisibility
        )
        val before = copyChannel(main)

        NotificationChannels.ensureChannelsCreated(context)

        assertEquals(expectedNames.keys, manager.notificationChannels.map { it.id }.toSet())
        assertEquals(before, manager.getNotificationChannel("main_004"))
    }

    @Test
    fun `refresh follows the current locale while preserving all existing channel settings`() {
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(
                "alarms", "Old alarms"
            ).apply {
                description = "Preserved group description"
            })
        for (id in expectedNames.keys) {
            val silentChannel = id == "main_004" || id == "live_update_001"
            manager.createNotificationChannel(
                NotificationChannel(
                    id,
                    "Old name: $id",
                    if (silentChannel) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Preserved description: $id"
                    setSound(
                        Uri.parse("content://test/custom-tone"),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                    )
                    enableLights(silentChannel)
                    lightColor = 0xff556677.toInt()
                    vibrationPattern = longArrayOf(0, 137, 251)
                    enableVibration(silentChannel)
                    setShowBadge(silentChannel)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    if (!silentChannel) group = "alarms"
                })
        }
        val expected = expectedNames.mapValues { (id, _) ->
            copyChannel(requireNotNull(manager.getNotificationChannel(id)))
        }
        shadow.channelUpdates = 0
        shadow.groupUpdates = 0
        assertFalse(MonitoringDesiredState.read(context))

        NotificationChannels.refreshExistingChannelNames(context)

        assertFalse(MonitoringDesiredState.read(context))
        assertLocalizedNamesAndPreservedSettings(context, expected)
        assertEquals(expectedNames.size, shadow.channelUpdates)
        assertEquals(1, shadow.groupUpdates)
        NotificationChannels.refreshExistingChannelNames(context)
        NotificationChannels.ensureChannelsCreated(context)
        assertEquals(expectedNames.size, shadow.channelUpdates)
        assertEquals(1, shadow.groupUpdates)

        val polish =
            context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags("pl"))
            })
        assertNotEquals(
            context.getString(R.string.main_notif_chan_name),
            polish.getString(R.string.main_notif_chan_name)
        )

        NotificationChannels.refreshExistingChannelNames(polish)

        assertLocalizedNamesAndPreservedSettings(polish, expected)
        val localizedShadow = Shadow.extract<CountingNotificationManager>(
            polish.getSystemService(NotificationManager::class.java)
        )
        val localizedRegistrations = localizedShadow.channelUpdates to localizedShadow.groupUpdates
        NotificationChannels.refreshExistingChannelNames(polish)
        NotificationChannels.ensureChannelsCreated(polish)
        assertEquals(
            localizedRegistrations, localizedShadow.channelUpdates to localizedShadow.groupUpdates
        )
    }

    @Test
    fun `name refresh never creates missing application channels or changes an unowned channel`() {
        manager.createNotificationChannel(
            NotificationChannel("unowned", "Leave this name", NotificationManager.IMPORTANCE_MIN)
        )
        val before = copyChannel(requireNotNull(manager.getNotificationChannel("unowned")))
        val registrations = shadow.channelUpdates to shadow.groupUpdates

        NotificationChannels.refreshExistingChannelNames(context)

        expectedNames.keys.forEach { assertNull(manager.getNotificationChannel(it)) }
        assertNull(manager.notificationChannelGroups.firstOrNull { it.id == "alarms" })
        assertEquals(before, manager.getNotificationChannel("unowned"))
        assertEquals(registrations, shadow.channelUpdates to shadow.groupUpdates)
    }

    private fun assertLocalizedNamesAndPreservedSettings(
        localized: Context, expected: Map<String, NotificationChannel>
    ) {
        for ((id, resource) in expectedNames) {
            val original = expected.getValue(id)
            original.name = localized.getString(resource)
            assertEquals("Settings changed for $id", original, manager.getNotificationChannel(id))
        }
        val group = manager.notificationChannelGroups.single { it.id == "alarms" }
        assertEquals(localized.getString(R.string.channel_group_name_alarms), group.name.toString())
        assertEquals("Preserved group description", group.description)
    }

    private fun copyChannel(channel: NotificationChannel): NotificationChannel {
        val parcel = Parcel.obtain()
        return try {
            channel.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            NotificationChannel.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    @Implements(NotificationManager::class)
    class CountingNotificationManager : ShadowNotificationManager() {
        var channelUpdates = 0
        var groupUpdates = 0

        @Implementation
        public override fun createNotificationChannel(channel: NotificationChannel) {
            channelUpdates++
            super.createNotificationChannel(channel)
        }

        @Implementation
        public override fun createNotificationChannelGroup(group: NotificationChannelGroup) {
            groupUpdates++
            super.createNotificationChannelGroup(group)
        }
    }
}
