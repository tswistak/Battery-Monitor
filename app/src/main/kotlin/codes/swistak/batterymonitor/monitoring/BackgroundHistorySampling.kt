/*
    Copyright (c) 2026 Tomasz Świstak
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import codes.swistak.batterymonitor.logs.HISTORY_SAMPLE_INTERVAL_MS
import codes.swistak.batterymonitor.settings.SettingsContract

internal class BackgroundHistorySampling(
    context: Context,
    private val handler: Handler,
    private val requestedMode: () -> String?,
    private val lastSampleElapsed: () -> Long?,
    private val sample: (PowerManager.WakeLock?) -> Unit
) {
    private val packageName = context.packageName
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    private data class ScheduledSample(
        val elapsed: Long, val listener: AlarmManager.OnAlarmListener
    )

    private var scheduledSample: ScheduledSample? = null
    private var continuousWakeLock: PowerManager.WakeLock? = null
    private var sampleWakeLock: PowerManager.WakeLock? = null

    private fun takeScheduledSample() {
        val now = SystemClock.elapsedRealtime()
        scheduledSample = null
        val mode = availableMode()
        reconcile(mode)
        if (mode != SettingsContract.HISTORY_SAMPLING_MODE_EXACT_ALARM || lastSampleElapsed()?.let { now - it in 0..<HISTORY_SAMPLE_INTERVAL_MS } == true) return

        sampleWakeLock?.release()
        val lock = newWakeLock("BatteryMonitor:HistorySample") { acquire(SAMPLE_WAKE_TIMEOUT_MS) }
        sampleWakeLock = lock
        try {
            sample(lock)
        } catch (error: RuntimeException) {
            lock?.release()
            Log.w(TAG, "Unable to take a scheduled history sample", error)
        }
    }

    fun reconcile() = reconcile(availableMode())

    fun stop() = reconcile(null)

    private fun availableMode(): String? {
        val mode = requestedMode()
        if (mode != SettingsContract.HISTORY_SAMPLING_MODE_EXACT_ALARM && mode != SettingsContract.HISTORY_SAMPLING_MODE_KEEP_CPU_AWAKE) return null
        if (power?.isIgnoringBatteryOptimizations(packageName) != true) return null
        if (mode == SettingsContract.HISTORY_SAMPLING_MODE_EXACT_ALARM && (alarms == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms())) return null
        return mode
    }

    private fun reconcile(mode: String?) {
        if (mode == SettingsContract.HISTORY_SAMPLING_MODE_KEEP_CPU_AWAKE) {
            if (continuousWakeLock == null) continuousWakeLock = acquireContinuousWakeLock()
        } else {
            continuousWakeLock?.release()
            continuousWakeLock = null
        }

        if (mode == SettingsContract.HISTORY_SAMPLING_MODE_EXACT_ALARM) {
            scheduleExactSample()
        } else {
            cancelExactSample()
            sampleWakeLock?.release()
            sampleWakeLock = null
        }
    }

    @SuppressLint("ScheduleExactAlarm") // availableMode checks the app's current exemption and access.
    private fun scheduleExactSample() {
        val manager = alarms ?: return
        val now = SystemClock.elapsedRealtime()
        val deadline =
            lastSampleElapsed()?.let { it + HISTORY_SAMPLE_INTERVAL_MS }?.takeIf { it > now }
                ?: scheduledSample?.elapsed ?: (now + HISTORY_SAMPLE_INTERVAL_MS)
        if (scheduledSample?.elapsed == deadline) return
        val listener = object : AlarmManager.OnAlarmListener {
            override fun onAlarm() {
                if (scheduledSample?.listener === this) takeScheduledSample()
            }
        }
        try {
            cancelExactSample()
            manager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, TAG, listener, handler)
            scheduledSample = ScheduledSample(deadline, listener)
        } catch (error: SecurityException) {
            stop()
            Log.w(TAG, "Exact history alarm unavailable; using the regular timer", error)
        }
    }

    private fun cancelExactSample() {
        val pending = scheduledSample ?: return
        scheduledSample = null
        alarms?.cancel(pending.listener)
    }

    // This mode explicitly opts out of CPU suspend for the foreground monitoring session.
    @SuppressLint("WakelockTimeout")
    private fun acquireContinuousWakeLock() = newWakeLock("BatteryMonitor:ContinuousHistory") {
        acquire()
    }

    private inline fun newWakeLock(
        name: String, acquireLock: PowerManager.WakeLock.() -> Unit
    ): PowerManager.WakeLock? = try {
        power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, name)?.apply {
            setReferenceCounted(false)
            acquireLock()
        }
    } catch (error: SecurityException) {
        Log.w(TAG, "Unable to keep the CPU awake for battery history", error)
        null
    }

    companion object {
        const val ACTION_POWER_ALLOWLIST_CHANGED = "android.os.action.POWER_SAVE_WHITELIST_CHANGED"
        private const val TAG = "BackgroundHistorySampling"
        private const val SAMPLE_WAKE_TIMEOUT_MS = 10_000L
    }
}
