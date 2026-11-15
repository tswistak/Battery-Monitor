/*
    Copyright (c) 2026 Tomasz Świstak
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring

import android.app.AlarmManager
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import codes.swistak.batterymonitor.logs.HISTORY_SAMPLE_INTERVAL_MS
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
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BackgroundHistorySamplingTest {
    private val context = RuntimeEnvironment.getApplication()
    private val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val power = shadowOf(context.getSystemService(PowerManager::class.java))
    private val main = Looper.getMainLooper()
    private var requestedMode: String? = null
    private var lastSample: Long? = null
    private val sampled = mutableListOf<PowerManager.WakeLock?>()
    private val sampling = BackgroundHistorySampling(
        context,
        Handler(main),
        { requestedMode },
        { lastSample },
        sampled::add
    )

    @Test
    fun exact_wakeup_tracks_committed_samples_and_retries_if_a_sample_does_not_commit() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        power.setIgnoringBatteryOptimizations(context.packageName, true)
        requestedMode = SettingsContract.HISTORY_SAMPLING_MODE_EXACT_ALARM

        sampling.reconcile()
        val original = checkNotNull(alarms.peekNextScheduledAlarm())
        @Suppress("DEPRECATION") assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, original.type)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, original.windowLengthMs)
        advance(30_000)
        sampling.reconcile()
        assertEquals(original.triggerAtMs, alarms.peekNextScheduledAlarm()?.triggerAtMs)

        val committed = SystemClock.elapsedRealtime()
        lastSample = committed
        sampling.reconcile()
        val aligned = checkNotNull(alarms.peekNextScheduledAlarm())
        assertEquals(committed + HISTORY_SAMPLE_INTERVAL_MS, aligned.triggerAtMs)
        advance(90_000)
        @Suppress("DEPRECATION") checkNotNull(original.onAlarmListener).onAlarm()
        assertTrue(sampled.isEmpty())

        advance(30_000)
        alarms.fireAlarm(aligned)
        shadowOf(main).idle()
        val lock = checkNotNull(sampled.single())
        assertTrue(lock.isHeld)
        assertEquals(
            SystemClock.elapsedRealtime() + HISTORY_SAMPLE_INTERVAL_MS,
            alarms.peekNextScheduledAlarm()?.triggerAtMs
        )

        sampling.stop()
        assertFalse(lock.isHeld)
        assertNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun advanced_modes_require_their_access_and_release_the_cpu_on_power_or_session_changes() {
        requestedMode = SettingsContract.HISTORY_SAMPLING_MODE_EXACT_ALARM
        power.setIgnoringBatteryOptimizations(context.packageName, true)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        sampling.reconcile()
        assertNull(alarms.peekNextScheduledAlarm())

        requestedMode = SettingsContract.HISTORY_SAMPLING_MODE_KEEP_CPU_AWAKE
        sampling.reconcile()
        val first = checkNotNull(ShadowPowerManager.getLatestWakeLock())
        assertTrue(first.isHeld)
        assertNull(alarms.peekNextScheduledAlarm())

        power.setIgnoringBatteryOptimizations(context.packageName, false)
        sampling.reconcile()
        assertFalse(first.isHeld)
        power.setIgnoringBatteryOptimizations(context.packageName, true)
        sampling.reconcile()
        val second = checkNotNull(ShadowPowerManager.getLatestWakeLock())
        assertTrue(second.isHeld)

        requestedMode = null
        sampling.reconcile()
        assertFalse(second.isHeld)
    }

    private fun advance(millis: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(millis))
}
