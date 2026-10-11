/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import codes.swistak.batterymonitor.monitoring.BackgroundServiceWatchdog
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsSnapshot

class WidgetSettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val snapshot = intent.getBundleExtra(EXTRA_SETTINGS)
        if (snapshot != null && !SettingsSnapshot.apply(
                context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE),
                snapshot
            )
        ) return
        if (!BackgroundServiceWatchdog.isServiceDesired(context)) LegacyWidgetRenderer.renderStopped(
            context
        )
        val pending = goAsync()
        WidgetUpdateDispatcher.refresh(context) { pending.finish() }
    }

    companion object {
        private const val EXTRA_SETTINGS = "widget_settings"

        internal fun refresh(context: Context) {
            context.sendBroadcast(Intent(context, WidgetSettingsReceiver::class.java))
        }

        internal fun synchronize(context: Context, settings: SharedPreferences) {
            context.sendBroadcast(
                Intent(context, WidgetSettingsReceiver::class.java).putExtra(
                    EXTRA_SETTINGS, SettingsSnapshot.capture(settings)
                )
            )
        }
    }
}
