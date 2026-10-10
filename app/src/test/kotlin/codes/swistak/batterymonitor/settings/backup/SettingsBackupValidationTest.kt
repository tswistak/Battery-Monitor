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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsBackupValidationTest {
    @Test
    fun `current settings accept boundary targets and custom numeric intervals`() {
        SettingsBackupValidation.validate(
            mapOf(
                SettingsContract.KEY_CUSTOM_CHARGING_TARGET to 1,
                SettingsContract.KEY_DISCHARGING_TARGET to 99,
                SettingsContract.KEY_RED_THRESH to "0",
                SettingsContract.KEY_GREEN_THRESH to "100",
                SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL to "3600",
                SettingsContract.KEY_CHIP_SWITCHING_INTERVAL to "1",
                SettingsContract.KEY_PREDICTION_TYPE to "600000",
                SettingsContract.KEY_MAX_LOG_AGE to "-1",
                SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER to "-1000"
            ), Version4SettingsImporter.schema
        )
    }

    @Test
    fun `invalid types choices bounds and action sentinels are rejected before restore`() {
        val invalidValues = listOf(
            SettingsContract.KEY_ENABLE_LOGGING to "true",
            SettingsContract.KEY_CUSTOM_CHARGING_TARGET to 101,
            SettingsContract.KEY_DISCHARGING_TARGET to -1,
            SettingsContract.KEY_RED_THRESH to "101",
            SettingsContract.KEY_RED_THRESH to "invalid",
            SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL to "custom",
            SettingsContract.KEY_CHIP_SWITCHING_INTERVAL to "0",
            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER to "auto",
            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER to "0",
            SettingsContract.KEY_PREDICTION_TYPE to "0",
            SettingsContract.KEY_MAX_LOG_AGE to "-2",
            SettingsContract.KEY_TEMPERATURE_UNIT to "kelvin",
            "ui_brightness" to "invalid"
        )
        for ((key, value) in invalidValues) {
            assertThrows(
                "Expected rejection for $key=$value", IllegalArgumentException::class.java
            ) {
                SettingsBackupValidation.validate(
                    mapOf(key to value), Version4SettingsImporter.schema
                )
            }
        }
    }

    @Test
    fun `legacy import keeps old multiplier windows and unknown fields compatible`() {
        SettingsBackupValidation.validate(
            mapOf(
                SettingsContract.LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER to "-1000",
                SettingsContract.KEY_CHIP_CONTENT to "switching",
                SettingsContract.KEY_PREDICTION_TYPE to "120000",
                "future_setting" to 12.5
            ), Version1SettingsImporter.schema
        )
    }

    @Test
    fun `appearance choices are included in the current portable settings schema`() {
        assertEquals(String::class.java, Version4SettingsImporter.schema["ui_color_source"])
        assertEquals(String::class.java, Version4SettingsImporter.schema["ui_brightness"])
        for (brightness in listOf("System", "Light", "Dark", "TrueBlack")) {
            SettingsBackupValidation.validate(
                mapOf("ui_color_source" to "BatteryBlue", "ui_brightness" to brightness),
                Version4SettingsImporter.schema
            )
        }
    }
}
