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
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.settings.SettingsContract

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        if (Intent.ACTION_BOOT_COMPLETED != action && Intent.ACTION_MY_PACKAGE_REPLACED != action) return

        val settings = context.getSharedPreferences(
            SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
        )
        val spMain = context.getSharedPreferences(SettingsContract.SP_MAIN_FILE, 0)
        val spService = context.getSharedPreferences(SettingsContract.SP_SERVICE_FILE, 0)

        val startPref: String =
            settings.getString(SettingsContract.KEY_AUTOSTART, "auto")!!

        val serviceDesired: Boolean = if (!spMain.getBoolean(
                SettingsContract.KEY_MIGRATED_SERVICE_DESIRED, false
            )
        ) spService.getBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, false)
        else spMain.getBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, false)

        // Note: Regardless of anything here, Android will start the Service on boot if there are any desktop widgets
        if (startPref == "always" || (startPref == "auto" && serviceDesired)) {
            BatteryInfoService.startForegroundServiceSafely(context)
        }

        // This receiver is called on PACKAGE_REPLACED, too, but we don't want to log boot in that case
        if (Intent.ACTION_BOOT_COMPLETED == intent.action && settings.getBoolean(
                SettingsContract.KEY_ENABLE_LOGGING, true
            )
        ) LogDatabase(context).logBoot()
    }
}
