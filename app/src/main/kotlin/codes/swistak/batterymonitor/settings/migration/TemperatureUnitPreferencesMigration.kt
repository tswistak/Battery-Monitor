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
import codes.swistak.batterymonitor.settings.TemperatureUnit

internal object TemperatureUnitPreferencesMigration : SettingsMigration {
    override val version: Int = 4

    override fun migrate(
        preferences: SharedPreferences, editor: SharedPreferences.Editor
    ) {
        if (!preferences.contains(SettingsContract.KEY_TEMPERATURE_UNIT) && preferences.contains(
                SettingsContract.LEGACY_KEY_CONVERT_F
            )
        ) {
            editor.putString(
                SettingsContract.KEY_TEMPERATURE_UNIT,
                if (preferences.getBoolean(SettingsContract.LEGACY_KEY_CONVERT_F, false)) {
                    TemperatureUnit.FAHRENHEIT.preferenceValue
                } else {
                    TemperatureUnit.CELSIUS.preferenceValue
                }
            )
        }
        editor.remove(SettingsContract.LEGACY_KEY_CONVERT_F)
    }

    fun restoreImportedSettings(
        editor: SharedPreferences.Editor, settings: Map<String, Any>
    ) {
        val importedUnit = settings[SettingsContract.KEY_TEMPERATURE_UNIT] as? String
        val unit = if (importedUnit != null) {
            TemperatureUnit.fromPreference(importedUnit)
        } else if (settings[SettingsContract.LEGACY_KEY_CONVERT_F] == true) {
            TemperatureUnit.FAHRENHEIT
        } else if (settings[SettingsContract.LEGACY_KEY_CONVERT_F] == false) {
            TemperatureUnit.CELSIUS
        } else {
            null
        }
        if (unit != null) {
            editor.putString(SettingsContract.KEY_TEMPERATURE_UNIT, unit.preferenceValue)
        } else {
            editor.remove(SettingsContract.KEY_TEMPERATURE_UNIT)
        }
        editor.remove(SettingsContract.LEGACY_KEY_CONVERT_F)
    }
}
