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
package codes.swistak.batterymonitor.settings.migration

import android.content.SharedPreferences
import codes.swistak.batterymonitor.settings.SettingsContract


internal object BatteryCurrentPreferencesMigration : SettingsMigration {
    override val version: Int = 1

    override fun migrate(
        preferences: SharedPreferences, editor: SharedPreferences.Editor
    ) {
        val needsEnabled =
            !preferences.contains(SettingsContract.KEY_ENABLE_BATTERY_CURRENT) && preferences.contains(
                SettingsContract.LEGACY_KEY_ENABLE_CURRENT
            )
        val needsMultiplier =
            !preferences.contains(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER) && preferences.contains(
                SettingsContract.LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER
            )
        val needsPrivileged = !preferences.contains(SettingsContract.KEY_USE_PRIVILEGED_BATTERY_CURRENT)
        val needsNotification =
            !preferences.contains(SettingsContract.KEY_DISPLAY_CURRENT_IN_NOTIFICATION) && preferences.contains(
                SettingsContract.LEGACY_KEY_DISPLAY_CURRENT_IN_VITAL_STATS
            )
        val needsAverage = !preferences.contains(SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT)
        val needsRefreshInterval =
            !preferences.contains(SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL)

        editor.apply {
            if (needsEnabled) {
                putBoolean(
                    SettingsContract.KEY_ENABLE_BATTERY_CURRENT,
                    preferences.getBoolean(SettingsContract.LEGACY_KEY_ENABLE_CURRENT, false)
                )
            }
            if (needsPrivileged) {
                putBoolean(
                    SettingsContract.KEY_USE_PRIVILEGED_BATTERY_CURRENT, preferences.getString(
                        SettingsContract.LEGACY_KEY_CURRENT_SOURCE, null
                    ) == "privileged"
                )
            }
            if (needsMultiplier) {
                putString(
                    SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, preferences.getString(
                        SettingsContract.LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER, "1"
                    )
                )
            }
            if (needsNotification) {
                putBoolean(
                    SettingsContract.KEY_DISPLAY_CURRENT_IN_NOTIFICATION, preferences.getBoolean(
                        SettingsContract.LEGACY_KEY_DISPLAY_CURRENT_IN_VITAL_STATS, false
                    )
                )
            }
            if (needsAverage) {
                putBoolean(
                    SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT, preferences.getBoolean(
                        SettingsContract.LEGACY_KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS, false
                    ) || preferences.getBoolean(
                        SettingsContract.LEGACY_KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW, false
                    )
                )
            }
            if (needsRefreshInterval) {
                putString(SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL, "2")
            }

            remove(SettingsContract.LEGACY_KEY_ENABLE_CURRENT)
            remove(SettingsContract.LEGACY_KEY_PREFER_FILE_SYSTEM)
            remove(SettingsContract.LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER)
            remove(SettingsContract.LEGACY_KEY_CURRENT_SOURCE)
            remove(SettingsContract.LEGACY_KEY_DISPLAY_CURRENT_IN_VITAL_STATS)
            remove(SettingsContract.LEGACY_KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS)
            remove(SettingsContract.LEGACY_KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW)
            remove(SettingsContract.LEGACY_KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW)
            remove(SettingsContract.LEGACY_KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW)
        }
    }
}
