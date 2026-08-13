/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.app


import android.app.Application
import codes.swistak.batterymonitor.logs.AutoLogExportScheduler
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.migration.SettingsMigrationManager

class BatteryMonitorApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        SettingsMigrationManager.migrate(
            getSharedPreferences(SettingsContract.SETTINGS_FILE, MODE_PRIVATE)
        )
        AutoLogExportScheduler.ensureScheduled(this)
    }
}
