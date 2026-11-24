package codes.swistak.batterymonitor.monitoring.notifications

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.alarms.AlarmConfiguration
import codes.swistak.batterymonitor.alarms.AlarmPlayback
import codes.swistak.batterymonitor.alarms.AlarmRule
import codes.swistak.batterymonitor.alarms.AlarmSnapshot
import codes.swistak.batterymonitor.alarms.AlarmSoundSettings
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import codes.swistak.batterymonitor.monitoring.MonitoringDesiredState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AlarmNotificationsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val shared = "test-shared-${UUID.randomUUID()}"
    private val dedicated = "test-custom-${UUID.randomUUID()}"
    private val type = "charge_drops"
    private val desiredBefore = MonitoringDesiredState.read(context)
    private val inheritedId = Int.MAX_VALUE - 1
    private val customId = Int.MAX_VALUE
    private val snapshot = AlarmSnapshot(
        rules = listOf(
            AlarmRule(inheritedId, true, type, "20"), AlarmRule(customId, true, type, "15")
        ), shared = mapOf(type to AlarmConfiguration(channelId = shared)), custom = mapOf(
            customId to AlarmConfiguration(
                AlarmSoundSettings(AlarmPlayback.REPEAT), dedicated
            )
        )
    )

    @Before
    @Suppress("DEPRECATION")
    fun createTestChannels() {
        assertTrue(MonitoringDesiredState.write(context, desired = false))
        val service = ComponentName(context, BatteryInfoService::class.java)
        context.stopService(Intent().setComponent(service))
        val services = context.getSystemService(ActivityManager::class.java)
        await("The real monitor must stop before posting synthetic alarms") {
            services.getRunningServices(Int.MAX_VALUE).none { it.service == service }
        }
        if (Build.VERSION.SDK_INT >= 33 && !manager.areNotificationsEnabled()) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS
            )
        }
        assertTrue(manager.areNotificationsEnabled())
        for (channel in listOf(shared, dedicated)) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channel, channel, NotificationManager.IMPORTANCE_DEFAULT
                ).apply { setSound(null, null) })
        }
    }

    @After
    fun removeTestChannels() {
        for (channel in listOf(shared, dedicated)) manager.deleteNotificationChannel(channel)
        assertTrue(MonitoringDesiredState.write(context, desiredBefore))
    }

    @Test
    fun repeatAndOnceCanCoexistAndStopTargetsOnlyTheSelectedEvent() {
        postBoth()
        val once = requireNotNull(active(shared))
        val first = requireNotNull(active(dedicated))
        assertFalse(once.notification.flags and Notification.FLAG_INSISTENT != 0)
        assertTrue(once.notification.actions.isNullOrEmpty())
        assertTrue(first.notification.flags and Notification.FLAG_INSISTENT != 0)
        val oldStop = first.notification.actions.single().actionIntent

        manager.cancel(first.tag, first.id)
        await("The first event should clear before the threshold is crossed again") {
            active(dedicated) == null
        }
        AlarmNotifications.post(context, snapshot, type, "New event", null, customId, 15)
        await("The next event should be posted") { active(dedicated) != null }
        val latest = requireNotNull(active(dedicated))
        assertEquals(first.key, latest.key)
        assertTrue(oldStop != latest.notification.actions.single().actionIntent)
        sendStop(oldStop)
        assertEquals(latest.key, active(dedicated)?.key)
        sendStop(latest.notification.actions.single().actionIntent)
        await("Stop should dismiss only the selected threshold") { active(dedicated) == null }
        assertEquals(once.key, active(shared)?.key)
    }

    @Test
    fun configurationChangesCancelOnlyNotificationsForAffectedThresholds() {
        postBoth()
        val disabled = snapshot.copy(
            rules = listOf(
                snapshot.rules[0].copy(enabled = false), snapshot.rules[1]
            )
        )
        AlarmNotifications.reconcileConfiguration(context, disabled)
        await("Disabling the shared threshold should remove its notification") { active(shared) == null }
        assertTrue(active(dedicated) != null)

        AlarmNotifications.reconcileConfiguration(
            context, disabled.copy(rules = listOf(disabled.rules[0]))
        )
        await("Deleting the custom threshold should remove its notification") { active(dedicated) == null }

        postBoth()
        val edited = snapshot.copy(
            rules = listOf(
                snapshot.rules[0], snapshot.rules[1].copy(threshold = "17")
            )
        )
        AlarmNotifications.reconcileConfiguration(context, edited)
        await("Editing the custom threshold should remove the old event") { active(dedicated) == null }
        assertTrue(active(shared) != null)
    }

    private fun postBoth() {
        AlarmNotifications.post(context, snapshot, type, "Shared threshold", null, inheritedId, 20)
        AlarmNotifications.post(context, snapshot, type, "Custom threshold", null, customId, 15)
        await("Both configured channels should have active notifications") {
            active(shared) != null && active(dedicated) != null
        }
    }

    private fun active(channelId: String): StatusBarNotification? =
        manager.activeNotifications.singleOrNull {
            it.notification.channelId == channelId && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
        }

    private fun sendStop(action: PendingIntent) {
        val finished = CountDownLatch(1)
        action.send(0, { _, _, _, _, _ -> finished.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("Stop action did not finish", finished.await(5, TimeUnit.SECONDS))
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        assertTrue(message, condition())
    }
}
