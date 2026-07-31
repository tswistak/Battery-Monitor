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

internal interface SettingsImporter {
    val schema: Map<String, Class<*>>

    fun restore(
        editor: SharedPreferences.Editor, settings: Map<String, Any>
    )
}

internal fun settingsImporterForVersion(version: Int): SettingsImporter {
    require(version >= Version1SettingsImporter.VERSION) {
        "Unsupported settings schema version: $version"
    }
    return if (version == Version1SettingsImporter.VERSION) {
        Version1SettingsImporter
    } else {
        Version2SettingsImporter
    }
}

internal fun SharedPreferences.Editor.putSetting(key: String, value: Any) {
    when (value) {
        is Boolean -> putBoolean(key, value)
        is String -> putString(key, value)
        else -> error("Unsupported settings value for '$key'")
    }
}
