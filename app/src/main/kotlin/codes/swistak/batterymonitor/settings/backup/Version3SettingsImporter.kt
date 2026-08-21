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

internal object Version3SettingsImporter : SettingsImporter {
    const val VERSION = 3

    override val schema: Map<String, Class<*>> = buildMap {
        putAll(Version2SettingsImporter.schema)
        remove(SettingsContract.KEY_DISPLAY_CURRENT_IN_NOTIFICATION)
        remove(SettingsContract.KEY_STATUS_DURATION_IN_VITAL_SIGNS)
        remove(SettingsContract.KEY_CHIP_CONTENT)
        put(SettingsContract.KEY_SHOW_REMAINING_CHARGE, Boolean::class.java)
        for (key in SettingsBackupCodec.vitalSignsContentByBackupKey.keys) {
            put(key, Boolean::class.java)
        }
        for (key in SettingsBackupCodec.vitalSignsOrderByBackupKey.keys) {
            put(key, Int::class.java)
        }
        for (key in SettingsBackupCodec.chipContentByBackupKey.keys) {
            put(key, Boolean::class.java)
        }
        for (key in SettingsBackupCodec.chipContentOrderByBackupKey.keys) {
            put(key, Int::class.java)
        }
    }

    override fun restore(
        editor: SharedPreferences.Editor, settings: Map<String, Any>
    ) = SettingsBackupCodec.restore(editor, settings)
}
