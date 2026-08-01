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
import androidx.core.content.edit
import codes.swistak.batterymonitor.settings.SettingsContract

class BackgroundServiceWatchdog : BroadcastReceiver() {
    companion object {
        private const val LOG_TAG = "BackgroundWatchdog"
        private const val REQUEST_CODE = 2137
        private const val WATCHDOG_INTERVAL_MS = 20L * 60L * 1000L

        const val KEY_LAST_HEARTBEAT_ELAPSED_TIME: String = "background_last_heartbeat_elapsed_time"

        fun recordHeartbeat(context: Context) {
            context.getSharedPreferences(
                SettingsContract.SP_SERVICE_FILE,
                Context.MODE_PRIVATE
            )
                .edit {
                    putLong(KEY_LAST_HEARTBEAT_ELAPSED_TIME, SystemClock.elapsedRealtime())
                }
        }

        fun schedule(context: Context) {
            if (!isServiceDesired(context)) {
                cancel(context)
                return
            }

            val alarmManager =
                context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager? ?: return

            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
                pendingIntent(context)!!
            )
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager?
            alarmManager?.cancel(pendingIntent(context)!!)
        }

        fun isServiceDesired(context: Context): Boolean {
            val settings = context.getSharedPreferences(
                SettingsContract.SETTINGS_FILE,
                Context.MODE_PRIVATE
            )
            if ("always" == settings.getString(
                    SettingsContract.KEY_AUTOSTART,
                    "auto"
                )
            ) return true

            val mainPreferences = context.getSharedPreferences(
                SettingsContract.SP_MAIN_FILE,
                Context.MODE_PRIVATE
            )
            if (mainPreferences.getBoolean(
                    SettingsContract.KEY_MIGRATED_SERVICE_DESIRED,
                    false
                )
            ) {
                return mainPreferences.getBoolean(
                    BatteryInfoService.KEY_SERVICE_DESIRED,
                    false
                )
            }

            return context.getSharedPreferences(
                SettingsContract.SP_SERVICE_FILE,
                Context.MODE_PRIVATE
            )
                .getBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, false)
        }

        private fun pendingIntent(context: Context?): PendingIntent? {
            val intent = Intent(context, BackgroundServiceWatchdog::class.java)
            return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (!isServiceDesired(context)) {
            cancel(context)
            return
        }

        schedule(context)
        val servicePreferences = context.getSharedPreferences(
            SettingsContract.SP_SERVICE_FILE,
            Context.MODE_PRIVATE
        )
        val lastHeartbeat = servicePreferences.getLong(KEY_LAST_HEARTBEAT_ELAPSED_TIME, 0L)
        val heartbeatAge = SystemClock.elapsedRealtime() - lastHeartbeat

        if (lastHeartbeat == 0L || heartbeatAge >= WATCHDOG_INTERVAL_MS) {
            Log.w(
                LOG_TAG,
                "Battery monitoring heartbeat is stale; requesting a foreground-service restart"
            )
            BatteryInfoService.startForegroundServiceSafely(context)
        }
    }
}
