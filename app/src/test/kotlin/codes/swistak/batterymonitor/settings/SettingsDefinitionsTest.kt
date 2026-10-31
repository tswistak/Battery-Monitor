/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.settings

import codes.swistak.batterymonitor.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDefinitionsTest {
    private val resourceDefaults = mapOf(
        R.string.default_bottom_line_content to "vitals",
        R.string.default_icon_content to "percentage",
        R.string.default_live_update_display_mode to "charging",
        R.string.default_max_log_age to "672",
        R.string.default_prediction_type to "-3",
        R.string.default_temperature_unit to "celsius",
        R.string.default_time_remaining_verbosity to "normal",
        R.string.default_top_line_content to "remaining",
    )

    @Test
    fun `setting keys are nonempty and unique across categories and groups`() {
        val categories = SettingsDefinitions.categories
        val groups = categories.values.flatten()
        val preferenceKeys = SettingsDefinitions.preferences.map { it.key }
        val keys = categories.keys.toList() + groups.mapNotNull { it.key } + preferenceKeys
        assertTrue(keys.all { it.isNotBlank() })
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(categories.values.all { it.isNotEmpty() })
        assertTrue(groups.all { it.preferences.isNotEmpty() })
    }

    @Test
    fun `defaults retain every stored key value and type`() {
        val expected: Map<String, Any> = mapOf(
            SettingsContract.KEY_TEMPERATURE_UNIT to "celsius",
            SettingsContract.KEY_LONG_DURATION_FORMAT to "days_and_hours",
            SettingsContract.KEY_AUTOSTART to "auto",
            SettingsContract.KEY_SHOW_REMAINING_CHARGE to true,
            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER to "1",
            SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT to false,
            SettingsContract.KEY_PREDICTION_TYPE to "-3",
            SettingsContract.KEY_CHARGING_TARGET_MODE to "automatic",
            SettingsContract.KEY_CUSTOM_CHARGING_TARGET to 80,
            SettingsContract.KEY_DISCHARGING_TARGET to 0,
            SettingsContract.KEY_DISMISS_LOW_BATTERY_ON_RECOVERY to false,
            SettingsContract.KEY_TOP_LINE to "remaining",
            SettingsContract.KEY_BOTTOM_LINE to "vitals",
            SettingsContract.KEY_TIME_REMAINING_VERBOSITY to "normal",
            SettingsContract.KEY_EXPANDED_NOTIFICATION_DETAILS to true,
            SettingsContract.KEY_ICON_CONTENT to "percentage",
            SettingsContract.KEY_SHOW_ICON_UNIT to true,
            SettingsContract.KEY_INDICATE_CHARGING to true,
            SettingsContract.KEY_LIVE_UPDATE_DISPLAY to "charging",
            SettingsContract.KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION to false,
            SettingsContract.KEY_EXPANDED_LIVE_UPDATE_DETAILS to false,
            SettingsContract.KEY_CHIP_SWITCHING_INTERVAL to "5",
            SettingsContract.KEY_CHIP_INDICATE_CHARGING to true,
            SettingsContract.KEY_USE_PRIVILEGED_ACCESS to false,
            SettingsContract.KEY_ENABLE_LOGGING to true,
            SettingsContract.KEY_MAX_LOG_AGE to "672",
        )
        assertEquals(expected, SettingsDefinitions.defaultValues(resourceDefaults::getValue))
    }

    @Test
    fun `localized defaults resolve when requested instead of being cached`() {
        val original = SettingsDefinitions.defaultValues(resourceDefaults::getValue)
        val usDefaults = SettingsDefinitions.defaultValues { resource ->
            if (resource == R.string.default_temperature_unit) "fahrenheit" else resourceDefaults.getValue(
                resource
            )
        }
        assertEquals("celsius", original[SettingsContract.KEY_TEMPERATURE_UNIT])
        assertEquals("fahrenheit", usDefaults[SettingsContract.KEY_TEMPERATURE_UNIT])
        assertEquals(
            original - SettingsContract.KEY_TEMPERATURE_UNIT,
            usDefaults - SettingsContract.KEY_TEMPERATURE_UNIT
        )
    }

    @Test
    fun `measurement and target choices retain their persisted value resources`() {
        val choices = SettingsDefinitions.preferences.associate { it.key to it.control }
        for ((key, values) in mapOf(
            SettingsContract.KEY_TEMPERATURE_UNIT to R.array.temperature_unit_values,
            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER to R.array.battery_current_multiplier_values,
            SettingsContract.KEY_CHARGING_TARGET_MODE to R.array.charging_target_mode_values
        )) {
            val choice = choices.getValue(key) as SettingsControl.Choice
            assertEquals(key, values, choice.entryValues)
        }
    }

    @Test
    fun `integer controls retain typed defaults and bounds`() {
        val numbers = SettingsDefinitions.preferences.mapNotNull { definition ->
            (definition.control as? SettingsControl.Number)?.let { definition.key to it }
        }.toMap()
        assertEquals(
            mapOf(
                SettingsContract.KEY_CUSTOM_CHARGING_TARGET to SettingsControl.Number(80, 1, 100),
                SettingsContract.KEY_DISCHARGING_TARGET to SettingsControl.Number(0, 0, 99)
            ), numbers
        )
        for ((defaultValue, min, max, increment, adjustable, showValue) in numbers.values) {
            assertTrue(defaultValue in min..max)
            assertEquals(1, increment)
            assertTrue(adjustable)
            assertTrue(showValue)
        }
    }
}
