package codes.swistak.batterymonitor.monitoring.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import codes.swistak.batterymonitor.alarms.AlarmConfiguration
import codes.swistak.batterymonitor.alarms.AlarmPlayback
import codes.swistak.batterymonitor.alarms.AlarmRule
import codes.swistak.batterymonitor.alarms.AlarmSnapshot
import codes.swistak.batterymonitor.alarms.AlarmSoundSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowPendingIntent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
class AlarmNotificationsTest {
    @Test
    fun `repeating alarms respect application notification permission and disabled channels`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        val shadow = Shadow.extract<ShadowNotificationManager>(manager)
        val type = "charge_drops"
        val rule = AlarmRule(1, true, type, "20")
        val settings = AlarmSoundSettings(AlarmPlayback.REPEAT)
        manager.createNotificationChannel(
            NotificationChannel("allowed", "Allowed", NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannel(
            NotificationChannel("disabled", "Disabled", NotificationManager.IMPORTANCE_NONE)
        )
        fun post(channel: String) {
            val snapshot = AlarmSnapshot(
                rules = listOf(rule),
                custom = mapOf(rule.id to AlarmConfiguration(settings, channel))
            )
            AlarmNotifications.post(context, snapshot, type, "Low battery", null, rule.id, 20)
        }

        shadow.setNotificationsEnabled(false)
        post("allowed")
        assertEquals(0, shadow.size())
        shadow.setNotificationsEnabled(true)
        post("disabled")
        assertEquals(0, shadow.size())
        post("allowed")
        assertEquals(1, shadow.size())
    }

    @Test
    @Config(shadows = [DroppingNotificationManager::class])
    fun `a dropped replacement does not invalidate the visible stop action`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        val shadow = Shadow.extract<DroppingNotificationManager>(manager)
        val type = "charge_drops"
        val rule = AlarmRule(2, true, type, "15")
        val channel = "repeat"
        manager.createNotificationChannel(
            NotificationChannel(channel, "Test", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val snapshot = AlarmSnapshot(
            rules = listOf(rule), custom = mapOf(
                rule.id to AlarmConfiguration(AlarmSoundSettings(AlarmPlayback.REPEAT), channel)
            )
        )
        AlarmNotifications.post(context, snapshot, type, "Visible event", null, rule.id, 15)
        val stop = manager.activeNotifications.single().notification.actions.single().actionIntent
        shadow.dropUpdates = true
        AlarmNotifications.post(context, snapshot, type, "Dropped event", null, rule.id, 15)
        AlarmNotifications.stop(context, Shadow.extract<ShadowPendingIntent>(stop).savedIntent)
        assertEquals(0, shadow.size())
    }

    @Implements(NotificationManager::class)
    class DroppingNotificationManager : ShadowNotificationManager() {
        var dropUpdates = false

        @Implementation
        public override fun notify(tag: String?, id: Int, notification: Notification) {
            if (!dropUpdates) super.notify(tag, id, notification)
        }
    }
}
