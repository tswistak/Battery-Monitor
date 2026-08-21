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

internal object GlobalPrivilegedAccessMigration : SettingsMigration {
    override val version: Int = 5

    override fun migrate(
        preferences: SharedPreferences, editor: SharedPreferences.Editor
    ) {
        if (!preferences.contains(SettingsContract.KEY_USE_PRIVILEGED_ACCESS) && preferences.contains(
                SettingsContract.LEGACY_KEY_USE_PRIVILEGED_BATTERY_CURRENT
            )
        ) {
            editor.putBoolean(
                SettingsContract.KEY_USE_PRIVILEGED_ACCESS, preferences.getBoolean(
                    SettingsContract.LEGACY_KEY_USE_PRIVILEGED_BATTERY_CURRENT, false
                )
            )
        }
        editor.remove(SettingsContract.LEGACY_KEY_USE_PRIVILEGED_BATTERY_CURRENT)
    }
}
