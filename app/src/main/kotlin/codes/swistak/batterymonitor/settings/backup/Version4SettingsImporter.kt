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
import codes.swistak.batterymonitor.settings.SettingsContract

internal object Version4SettingsImporter : SettingsImporter {
    const val VERSION = 4

    override val schema: Map<String, Class<*>> = buildMap {
        putAll(Version3SettingsImporter.schema)
        remove(SettingsContract.LEGACY_KEY_CONVERT_F)
        put(SettingsContract.KEY_TEMPERATURE_UNIT, String::class.java)
        put(SettingsContract.KEY_LONG_DURATION_FORMAT, String::class.java)
        put(SettingsContract.KEY_CHARGING_TARGET_MODE, String::class.java)
        put(SettingsContract.KEY_CUSTOM_CHARGING_TARGET, Int::class.java)
        put(SettingsContract.KEY_DISCHARGING_TARGET, Int::class.java)
        remove(SettingsContract.LEGACY_KEY_USE_PRIVILEGED_BATTERY_CURRENT)
        put(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, Boolean::class.java)
    }

    override fun restore(
        editor: SharedPreferences.Editor, settings: Map<String, Any>
    ) = SettingsBackupCodec.restore(editor, settings)
}
