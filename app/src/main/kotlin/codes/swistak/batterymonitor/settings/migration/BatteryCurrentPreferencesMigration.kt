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
import codes.swistak.batterymonitor.SettingsKeys

internal object BatteryCurrentPreferencesMigration : SettingsMigration {
    override val version: Int = 1

    override fun migrate(
        preferences: SharedPreferences, editor: SharedPreferences.Editor
    ) {
        val needsEnabled =
            !preferences.contains(SettingsKeys.KEY_ENABLE_BATTERY_CURRENT) && preferences.contains(
                SettingsKeys.LEGACY_KEY_ENABLE_CURRENT
            )
        val needsMultiplier =
            !preferences.contains(SettingsKeys.KEY_BATTERY_CURRENT_MULTIPLIER) && preferences.contains(
                SettingsKeys.LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER
            )
        val needsPrivileged = !preferences.contains(SettingsKeys.KEY_USE_PRIVILEGED_BATTERY_CURRENT)
        val needsNotification =
            !preferences.contains(SettingsKeys.KEY_DISPLAY_CURRENT_IN_NOTIFICATION) && preferences.contains(
                SettingsKeys.LEGACY_KEY_DISPLAY_CURRENT_IN_VITAL_STATS
            )
        val needsAverage = !preferences.contains(SettingsKeys.KEY_PREFER_AVERAGE_BATTERY_CURRENT)
        val needsRefreshInterval =
            !preferences.contains(SettingsKeys.KEY_BATTERY_CURRENT_REFRESH_INTERVAL)

        editor.apply {
            if (needsEnabled) {
                putBoolean(
                    SettingsKeys.KEY_ENABLE_BATTERY_CURRENT,
                    preferences.getBoolean(SettingsKeys.LEGACY_KEY_ENABLE_CURRENT, false)
                )
            }
            if (needsPrivileged) {
                putBoolean(
                    SettingsKeys.KEY_USE_PRIVILEGED_BATTERY_CURRENT, preferences.getString(
                        SettingsKeys.LEGACY_KEY_CURRENT_SOURCE, null
                    ) == "privileged"
                )
            }
            if (needsMultiplier) {
                putString(
                    SettingsKeys.KEY_BATTERY_CURRENT_MULTIPLIER, preferences.getString(
                        SettingsKeys.LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER, "1"
                    )
                )
            }
            if (needsNotification) {
                putBoolean(
                    SettingsKeys.KEY_DISPLAY_CURRENT_IN_NOTIFICATION, preferences.getBoolean(
                        SettingsKeys.LEGACY_KEY_DISPLAY_CURRENT_IN_VITAL_STATS, false
                    )
                )
            }
            if (needsAverage) {
                putBoolean(
                    SettingsKeys.KEY_PREFER_AVERAGE_BATTERY_CURRENT, preferences.getBoolean(
                        SettingsKeys.LEGACY_KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS, false
                    ) || preferences.getBoolean(
                        SettingsKeys.LEGACY_KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW, false
                    )
                )
            }
            if (needsRefreshInterval) {
                putString(SettingsKeys.KEY_BATTERY_CURRENT_REFRESH_INTERVAL, "2")
            }

            remove(SettingsKeys.LEGACY_KEY_ENABLE_CURRENT)
            remove(SettingsKeys.LEGACY_KEY_PREFER_FILE_SYSTEM)
            remove(SettingsKeys.LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER)
            remove(SettingsKeys.LEGACY_KEY_CURRENT_SOURCE)
            remove(SettingsKeys.LEGACY_KEY_DISPLAY_CURRENT_IN_VITAL_STATS)
            remove(SettingsKeys.LEGACY_KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS)
            remove(SettingsKeys.LEGACY_KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW)
            remove(SettingsKeys.LEGACY_KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW)
            remove(SettingsKeys.LEGACY_KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW)
        }
    }
}
