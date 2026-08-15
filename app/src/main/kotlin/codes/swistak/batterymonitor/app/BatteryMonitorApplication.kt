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


import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import codes.swistak.batterymonitor.diagnostics.DebugLogCollector
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
        if (DebugLogCollector.shouldStartInProcess(currentProcessName())) {
            DebugLogCollector.sync(
                this, getSharedPreferences(SettingsContract.SETTINGS_FILE, MODE_PRIVATE).getBoolean(
                    SettingsContract.KEY_DEBUG_LOGGING, false
                )
            )
        }
    }

    private fun currentProcessName(): String? {
        val frameworkProcessName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { getProcessName() }.getOrNull()
        } else {
            null
        }
        if (!frameworkProcessName.isNullOrEmpty()) return frameworkProcessName

        val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        return activityManager?.runningAppProcesses?.firstOrNull {
            it.pid == Process.myPid()
        }?.processName
    }
}
