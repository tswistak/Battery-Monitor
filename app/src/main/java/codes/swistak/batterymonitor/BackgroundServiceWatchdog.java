/*
    Copyright (c) 2026 Tomasz Świstak
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

package codes.swistak.batterymonitor;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

public class BackgroundServiceWatchdog extends BroadcastReceiver {
    private static final String LOG_TAG = "BackgroundWatchdog";
    private static final int REQUEST_CODE = 2137;
    private static final long WATCHDOG_INTERVAL_MS = 20L * 60L * 1000L;

    static final String KEY_LAST_HEARTBEAT_ELAPSED_TIME = "background_last_heartbeat_elapsed_time";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!isServiceDesired(context)) {
            cancel(context);
            return;
        }

        schedule(context);
        SharedPreferences servicePreferences = context.getSharedPreferences(SettingsFragment.SP_SERVICE_FILE,
                Context.MODE_MULTI_PROCESS);
        long lastHeartbeat = servicePreferences.getLong(KEY_LAST_HEARTBEAT_ELAPSED_TIME, 0L);
        long heartbeatAge = SystemClock.elapsedRealtime() - lastHeartbeat;

        if (lastHeartbeat == 0L || heartbeatAge >= WATCHDOG_INTERVAL_MS) {
            Log.w(LOG_TAG, "Battery monitoring heartbeat is stale; requesting a foreground-service restart");
            BatteryInfoService.startForegroundServiceSafely(context);
        }
    }

    static void recordHeartbeat(Context context) {
        context.getSharedPreferences(SettingsFragment.SP_SERVICE_FILE, Context.MODE_MULTI_PROCESS)
                .edit()
                .putLong(KEY_LAST_HEARTBEAT_ELAPSED_TIME, SystemClock.elapsedRealtime())
                .apply();
    }

    static void schedule(Context context) {
        if (!isServiceDesired(context)) {
            cancel(context);
            return;
        }

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
                pendingIntent(context));
    }

    static void cancel(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) alarmManager.cancel(pendingIntent(context));
    }

    static boolean isServiceDesired(Context context) {
        SharedPreferences settings = context.getSharedPreferences(SettingsFragment.SETTINGS_FILE,
                Context.MODE_MULTI_PROCESS);
        if ("always".equals(settings.getString(SettingsFragment.KEY_AUTOSTART, "auto")))
            return true;

        SharedPreferences mainPreferences = context.getSharedPreferences(SettingsFragment.SP_MAIN_FILE,
                Context.MODE_MULTI_PROCESS);
        if (mainPreferences.getBoolean(SettingsFragment.KEY_MIGRATED_SERVICE_DESIRED, false)) {
            return mainPreferences.getBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, false);
        }

        return context.getSharedPreferences(SettingsFragment.SP_SERVICE_FILE, Context.MODE_MULTI_PROCESS)
                .getBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, false);
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, BackgroundServiceWatchdog.class);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
