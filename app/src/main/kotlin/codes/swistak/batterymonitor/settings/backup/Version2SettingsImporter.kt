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
package codes.swistak.batterymonitor.settings.backup

import android.content.SharedPreferences
import codes.swistak.batterymonitor.SettingsKeys

internal object Version2SettingsImporter : SettingsImporter {
    const val VERSION = 2

    override val schema: Map<String, Class<*>> = buildMap {
        putAll(Version1SettingsImporter.schema)
        remove(SettingsKeys.LEGACY_KEY_ENABLE_CURRENT)
        remove(SettingsKeys.LEGACY_KEY_PREFER_FILE_SYSTEM)
        remove(SettingsKeys.LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER)
        remove(SettingsKeys.LEGACY_KEY_DISPLAY_CURRENT_IN_VITAL_STATS)
        remove(SettingsKeys.LEGACY_KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS)
        remove(SettingsKeys.LEGACY_KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW)
        remove(SettingsKeys.LEGACY_KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW)
        remove(SettingsKeys.LEGACY_KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW)
        put(SettingsKeys.KEY_ENABLE_BATTERY_CURRENT, Boolean::class.java)
        put(SettingsKeys.KEY_USE_PRIVILEGED_BATTERY_CURRENT, Boolean::class.java)
        put(SettingsKeys.KEY_BATTERY_CURRENT_MULTIPLIER, String::class.java)
        put(SettingsKeys.KEY_BATTERY_CURRENT_REFRESH_INTERVAL, String::class.java)
        put(SettingsKeys.KEY_DISPLAY_CURRENT_IN_NOTIFICATION, Boolean::class.java)
        put(SettingsKeys.KEY_PREFER_AVERAGE_BATTERY_CURRENT, Boolean::class.java)
    }

    override fun restore(
        editor: SharedPreferences.Editor, settings: Map<String, Any>
    ) {
        for ((key, value) in settings) editor.putSetting(key, value)
    }
}
