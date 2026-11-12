package codes.swistak.batterymonitor.monitoring

import android.app.AlarmManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import codes.swistak.batterymonitor.settings.SettingsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MonitoringRecoveryTest {
    private val context = RecordingContext(RuntimeEnvironment.getApplication())
    private val preferences =
        context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
    private val alarms = shadowOf(checkNotNull(context.getSystemService(AlarmManager::class.java)))

    @Test
    fun fresh_install_keeps_the_initial_monitoring_default() {
        setBoot(9)
        assertFalse(MonitoringDesiredState.read(context))
        assertTrue(MonitoringDesiredState.initialize(context))
        assertTrue(MonitoringDesiredState.read(context))
    }

    @Test
    fun boot_policy_applies_before_the_boot_receiver_and_survives_later_setting_edits() {
        var boot = 10
        for (autostart in listOf("always", "auto", "never")) {
            for (active in listOf(false, true)) {
                val expected = autostart == "always" || autostart == "auto" && active
                preferences.edit().putString(SettingsContract.KEY_AUTOSTART, autostart).apply()
                setBoot(boot++)
                assertTrue(MonitoringDesiredState.write(context, active))

                setBoot(boot++)
                assertEquals(expected, BackgroundServiceWatchdog.isServiceDesired(context))
                assertEquals(expected, BackgroundServiceWatchdog.selfHealIfNeeded(context))
                assertEquals(expected, alarms.peekNextScheduledAlarm() != null)
                assertEquals(
                    expected,
                    MonitoringDesiredState.afterSystemEvent(context, Intent.ACTION_BOOT_COMPLETED)
                )

                preferences.edit().putString(SettingsContract.KEY_AUTOSTART, "always").apply()
                assertEquals(expected, BackgroundServiceWatchdog.isServiceDesired(context))
                BackgroundServiceWatchdog.cancel(context)
            }
        }
    }

    @Test
    fun manual_start_after_never_reboot_allows_recovery_until_explicit_stop() {
        preferences.edit().putString(SettingsContract.KEY_AUTOSTART, "never").apply()
        setBoot(20)
        assertTrue(MonitoringDesiredState.write(context, true))
        setBoot(21)
        assertFalse(BackgroundServiceWatchdog.selfHealIfNeeded(context))
        assertEquals(0, context.startCount)

        assertTrue(MonitoringDesiredState.write(context, true))
        assertTrue(MonitoringDesiredState.afterSystemEvent(context, Intent.ACTION_BOOT_COMPLETED))
        assertTrue(BackgroundServiceWatchdog.selfHealIfNeeded(context))
        assertEquals(1, context.startCount)

        assertTrue(MonitoringDesiredState.write(context, false))
        assertFalse(BackgroundServiceWatchdog.selfHealIfNeeded(context, watchdogFired = true))
        assertNull(alarms.peekNextScheduledAlarm())
        assertEquals(1, context.startCount)
    }

    @Test
    fun upgrading_a_released_active_session_keeps_it_running_until_reboot() {
        preferences.edit().putString(SettingsContract.KEY_AUTOSTART, "never").apply()
        val released =
            context.getSharedPreferences(SettingsContract.SP_SERVICE_FILE, Context.MODE_PRIVATE)
        released.edit().putBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, true).apply()
        setBoot(30)
        assertFalse(BackgroundServiceWatchdog.isServiceDesired(context))
        assertTrue(MonitoringDesiredState.initialize(context))
        assertFalse(BackgroundServiceWatchdog.isServiceDesired(context))
        assertTrue(
            MonitoringDesiredState.afterSystemEvent(
                context, Intent.ACTION_MY_PACKAGE_REPLACED
            )
        )
        assertTrue(BackgroundServiceWatchdog.isServiceDesired(context))
        setBoot(31)
        assertFalse(BackgroundServiceWatchdog.isServiceDesired(context))
    }

    @Test
    fun database_failure_remains_observable_while_service_heartbeats_continue() {
        setBoot(40)
        assertTrue(MonitoringDesiredState.write(context, true))
        recordHealth(databaseHealthy = true)
        assertFalse(BackgroundServiceWatchdog.selfHealIfNeeded(context, watchdogFired = true))
        val deadline = checkNotNull(alarms.peekNextScheduledAlarm()).triggerAtMs

        repeat(2) {
            ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
            recordHealth(databaseHealthy = false)
            assertFalse(BackgroundServiceWatchdog.selfHealIfNeeded(context))
            assertEquals(deadline, alarms.peekNextScheduledAlarm()?.triggerAtMs)
        }
        assertTrue(BackgroundServiceWatchdog.selfHealIfNeeded(context, watchdogFired = true))
        assertEquals(1, context.startCount)

        recordHealth(databaseHealthy = true)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        recordHealth(databaseHealthy = false)
        assertFalse(BackgroundServiceWatchdog.selfHealIfNeeded(context, watchdogFired = true))

        setBoot(41)
        assertEquals(MonitoringHealthState(0L, 0L), MonitoringHealthStore.read(context))
        assertTrue(BackgroundServiceWatchdog.selfHealIfNeeded(context))
        assertEquals(2, context.startCount)
    }

    private fun recordHealth(databaseHealthy: Boolean) {
        val elapsed = SystemClock.elapsedRealtime()
        MonitoringHealthStore.record(context, elapsed, if (databaseHealthy) elapsed else 0L)
    }

    private fun setBoot(boot: Int) {
        assertTrue(
            Settings.Global.putInt(
                context.contentResolver, Settings.Global.BOOT_COUNT, boot
            )
        )
    }

    private class RecordingContext(application: Application) : ContextWrapper(application) {
        var startCount = 0

        override fun startForegroundService(service: Intent): ComponentName {
            startCount++
            return checkNotNull(service.component)
        }
    }
}
