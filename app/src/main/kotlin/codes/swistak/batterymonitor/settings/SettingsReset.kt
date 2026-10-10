/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.settings

import android.content.SharedPreferences
import codes.swistak.batterymonitor.settings.backup.Version1SettingsImporter
import codes.swistak.batterymonitor.settings.backup.Version4SettingsImporter

internal object SettingsReset {
    fun reset(editor: SharedPreferences.Editor) {
        val keys =
            Version1SettingsImporter.schema.keys + Version4SettingsImporter.schema.keys + setOf(
                SettingsContract.KEY_VITAL_SIGNS_CONTENT,
                SettingsContract.KEY_VITAL_SIGNS_ORDER,
                SettingsContract.KEY_CHIP_CONTENT,
                SettingsContract.KEY_CHIP_CONTENT_ORDER,
                SettingsContract.KEY_DEBUG_LOGGING,
                SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING,
                SettingsContract.KEY_AUTO_LOG_EXPORT_FREQUENCY,
                SettingsContract.KEY_AUTO_LOG_EXPORT_MODE,
                SettingsContract.KEY_AUTO_LOG_EXPORT_FORMAT,
                SettingsContract.KEY_AUTO_LOG_EXPORT_DIRECTORY,
                SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME,
                SettingsContract.KEY_NEXT_AUTO_LOG_EXPORT_TIME,
                SettingsContract.KEY_LAST_LOG_EXPORT_TIME,
                SettingsContract.KEY_DISPLAY_CURRENT_IN_NOTIFICATION,
                SettingsContract.LEGACY_KEY_USE_PRIVILEGED_BATTERY_CURRENT,
                SettingsContract.LEGACY_KEY_CURRENT_SOURCE,
                "enable_advanced_stats"
            )
        keys.forEach { editor.remove(it) }
    }
}
