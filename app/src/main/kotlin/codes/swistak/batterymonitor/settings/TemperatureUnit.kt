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
package codes.swistak.batterymonitor.settings

import android.content.SharedPreferences

internal enum class TemperatureUnit(
    val preferenceValue: String, val convertToFahrenheit: Boolean
) {
    CELSIUS("celsius", false), FAHRENHEIT("fahrenheit", true);

    companion object {
        fun fromPreference(value: String?): TemperatureUnit =
            entries.firstOrNull { it.preferenceValue == value } ?: CELSIUS
    }
}

internal fun SharedPreferences.temperatureUnit(defaultValue: String): TemperatureUnit =
    TemperatureUnit.fromPreference(getString(SettingsContract.KEY_TEMPERATURE_UNIT, defaultValue))
