/*
    Copyright (c) 2026 Tomasz Świstak
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import codes.swistak.batterymonitor.settings.SettingsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BackgroundServiceWatchdog : BroadcastReceiver() {
    companion object {
        private const val LOG_TAG = "BackgroundWatchdog"
        private const val REQUEST_CODE = 2137
        private const val WATCHDOG_INTERVAL_MS = 20L * 60L * 1000L

        const val KEY_LAST_HEARTBEAT_ELAPSED_TIME: String = "background_last_heartbeat_elapsed_time"
        const val KEY_LAST_SUCCESSFUL_LOG_CHECK_ELAPSED_TIME: String =
            "background_last_successful_log_check_elapsed_time"

        private fun isHeartbeatStale(lastHeartbeat: Long, elapsedRealtime: Long): Boolean {
            return lastHeartbeat !in 1L..elapsedRealtime || elapsedRealtime - lastHeartbeat >= WATCHDOG_INTERVAL_MS
        }

        private fun staleReason(context: Context, checkDatabase: Boolean): String? {
            val healthState = MonitoringHealthStore.read(context)
            val elapsedRealtime = SystemClock.elapsedRealtime()
            val lastServiceHeartbeat = healthState.serviceHeartbeatElapsedTime
            if (isHeartbeatStale(lastServiceHeartbeat, elapsedRealtime)) {
                return "service heartbeat is stale (last=$lastServiceHeartbeat, now=$elapsedRealtime)"
            }

            if (!checkDatabase) return null
            val settings = context.getSharedPreferences(
                SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
            )
            if (!settings.getBoolean(SettingsContract.KEY_ENABLE_LOGGING, true)) return null

            val lastSuccessfulLogCheck = healthState.databaseHeartbeatElapsedTime
            return if (isHeartbeatStale(lastSuccessfulLogCheck, elapsedRealtime)) {
                "logging database heartbeat is stale (last=$lastSuccessfulLogCheck, now=$elapsedRealtime)"
            } else null
        }

        fun selfHealIfNeeded(context: Context, watchdogFired: Boolean = false): Boolean {
            if (!isServiceDesired(context)) {
                cancel(context)
                return false
            }

            arm(context, replaceDeliveredAlarm = watchdogFired)
            val reason = staleReason(context, checkDatabase = watchdogFired) ?: return false
            Log.w(LOG_TAG, "Battery monitoring is unhealthy: $reason; requesting restart")
            val result = BatteryInfoService.startForegroundServiceSafely(context)
            Log.i(LOG_TAG, "Battery monitoring self-heal result: $result")
            return true
        }

        fun schedule(context: Context) {
            if (isServiceDesired(context)) arm(context, replaceDeliveredAlarm = false)
            else cancel(context)
        }

        private fun arm(context: Context, replaceDeliveredAlarm: Boolean) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            if (!replaceDeliveredAlarm && pendingIntent(
                    context, PendingIntent.FLAG_NO_CREATE
                ) != null
            ) return
            val alarm = pendingIntent(context, 0) ?: return

            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
                    alarm
                )
            } catch (error: RuntimeException) {
                alarm.cancel()
                Log.w(LOG_TAG, "Unable to schedule monitoring health check", error)
            }
        }

        fun cancel(context: Context) {
            val alarm = pendingIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return
            context.getSystemService(AlarmManager::class.java)?.cancel(alarm)
            alarm.cancel()
        }

        fun isServiceDesired(context: Context): Boolean = MonitoringDesiredState.read(context)

        private fun pendingIntent(context: Context, flags: Int): PendingIntent? {
            val intent = Intent(context, BackgroundServiceWatchdog::class.java)
            return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent, flags or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                selfHealIfNeeded(context, watchdogFired = true)
            } finally {
                pending.finish()
            }
        }
    }
}
