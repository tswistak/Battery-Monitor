/*
    Copyright (c) 2009-2020 Darshan Computing, LLC
    Modified in 2026 by Tomasz Świstak <tomasz@swistak.codes> for the Battery Monitor fork.
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.monitoring


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import codes.swistak.batterymonitor.logs.AutoLogExportScheduler
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.widgets.LegacyWidgetRenderer

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        if (Intent.ACTION_BOOT_COMPLETED != action && Intent.ACTION_MY_PACKAGE_REPLACED != action) return

        val settings = context.getSharedPreferences(
            SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
        )
        val startPref: String = settings.getString(SettingsContract.KEY_AUTOSTART, "auto")!!
        val serviceDesired = BackgroundServiceWatchdog.isServiceDesired(context)
        val shouldStart = if (startPref == "always") {
            MonitoringDesiredState.write(context, desired = true, mirrorPreferences = false)
        } else startPref == "auto" && serviceDesired
        BackgroundServiceWatchdog.schedule(context)
        if (shouldStart) {
            BatteryInfoService.startForegroundServiceSafely(context)
        }
        if (!BackgroundServiceWatchdog.isServiceDesired(context)) {
            LegacyWidgetRenderer.renderStopped(context)
        }

        if (Intent.ACTION_BOOT_COMPLETED == intent.action && settings.getBoolean(
                SettingsContract.KEY_ENABLE_LOGGING, true
            )
        ) LogDatabase(context).logBoot()

        val pending = goAsync()
        codes.swistak.batterymonitor.widgets.WidgetUpdateDispatcher.refresh(context) { pending.finish() }
        AutoLogExportScheduler.ensureScheduled(context)
    }
}
