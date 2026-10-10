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

import codes.swistak.batterymonitor.settings.SettingsContract

internal object SettingsBackupValidation {
    private val choices = mapOf(
        SettingsContract.KEY_AUTOSTART to setOf("auto", "always", "never"),
        SettingsContract.KEY_ICON_CONTENT to setOf("percentage", "temperature"),
        SettingsContract.KEY_CHIP_CONTENT to (SettingsContract.ALL_CHIP_CONTENT + "switching").toSet(),
        SettingsContract.KEY_LIVE_UPDATE_DISPLAY to setOf("always", "charging", "never"),
        SettingsContract.KEY_TOP_LINE to setOf("remaining", "since", "vitals"),
        SettingsContract.KEY_BOTTOM_LINE to setOf("remaining", "since", "vitals"),
        SettingsContract.KEY_TIME_REMAINING_VERBOSITY to setOf("condensed", "normal", "verbose"),
        SettingsContract.KEY_TEMPERATURE_UNIT to setOf("celsius", "fahrenheit"),
        SettingsContract.KEY_LONG_DURATION_FORMAT to setOf("days_and_hours", "hours_only"),
        SettingsContract.KEY_CHARGING_TARGET_MODE to setOf("automatic", "custom"),
        SettingsContract.KEY_COLOR_SOURCE to setOf("Dynamic", "BatteryBlue"),
        SettingsContract.KEY_BRIGHTNESS to setOf("System", "Light", "Dark", "TrueBlack")
    )

    fun validate(values: Map<String, Any>, schema: Map<String, Class<*>>) {
        for ((key, value) in values) {
            val expected = schema[key] ?: continue
            val typeMatches = when (expected) {
                Boolean::class.java -> value is Boolean
                String::class.java -> value is String
                Int::class.java -> value is Int
                else -> false
            }
            require(typeMatches) { "Invalid settings type for '$key'" }
            choices[key]?.let { options ->
                require(value is String && value in options) { "Invalid settings value for '$key'" }
            }
            val valid = when (key) {
                SettingsContract.KEY_CUSTOM_CHARGING_TARGET -> value as Int in 1..100
                SettingsContract.KEY_DISCHARGING_TARGET -> value as Int in 0..99
                SettingsContract.KEY_RED_THRESH, SettingsContract.KEY_AMBER_THRESH, SettingsContract.KEY_GREEN_THRESH -> (value as String).toIntOrNull()
                    ?.let { it in 0..100 } == true

                SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL, SettingsContract.KEY_CHIP_SWITCHING_INTERVAL -> (value as String).toIntOrNull()
                    ?.let { it in 1..3600 } == true

                SettingsContract.KEY_MAX_LOG_AGE -> (value as String).toIntOrNull()
                    ?.let { it == -1 || it > 0 } == true

                SettingsContract.KEY_PREDICTION_TYPE -> (value as String).toIntOrNull()
                    ?.let { it in -3..-1 || it > 0 } == true

                SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, SettingsContract.LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER -> (value as String).toIntOrNull()
                    ?.let { it != 0 } == true

                else -> true
            }
            require(valid) { "Invalid settings value for '$key'" }
        }
    }
}
