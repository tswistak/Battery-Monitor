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
    fun `settings preserve category order controls and category keys`() {
        assertEquals(
            listOf(
                SettingsContract.KEY_OTHER_SETTINGS,
                SettingsContract.KEY_CURRENT_STATE_SETTINGS,
                SettingsContract.KEY_TIME_ESTIMATES_SETTINGS,
                SettingsContract.KEY_NOTIFICATION_SETTINGS,
                SettingsContract.KEY_ADVANCED_SETTINGS,
                SettingsContract.KEY_BACKUP_RESTORE_SETTINGS
            ), SettingsDefinitions.categories.keys.toList()
        )
        assertEquals(
            listOf(4, 3, 4, 17, 3, 11), SettingsDefinitions.categories.values.map { groups ->
                groups.sumOf { it.preferences.size }
            })
        val definitions = SettingsDefinitions.preferences
        assertEquals(
            listOf(
                SettingsContract.KEY_CHANGE_APP_LANGUAGE,
                SettingsContract.KEY_TEMPERATURE_UNIT,
                SettingsContract.KEY_LONG_DURATION_FORMAT,
                SettingsContract.KEY_AUTOSTART,
                SettingsContract.KEY_SHOW_REMAINING_CHARGE,
                SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER,
                SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT,
                SettingsContract.KEY_PREDICTION_TYPE,
                SettingsContract.KEY_CHARGING_TARGET_MODE,
                SettingsContract.KEY_CUSTOM_CHARGING_TARGET,
                SettingsContract.KEY_DISCHARGING_TARGET,
                SettingsContract.KEY_ENABLE_NOTIFS_B,
                SettingsContract.KEY_DISMISS_LOW_BATTERY_ON_RECOVERY,
                SettingsContract.KEY_TOP_LINE,
                SettingsContract.KEY_BOTTOM_LINE,
                SettingsContract.KEY_TIME_REMAINING_VERBOSITY,
                SettingsContract.KEY_VITAL_SIGNS_CONTENT,
                SettingsContract.KEY_EXPANDED_NOTIFICATION_DETAILS,
                SettingsContract.KEY_ICON_CONTENT,
                SettingsContract.KEY_SHOW_ICON_UNIT,
                SettingsContract.KEY_INDICATE_CHARGING,
                SettingsContract.KEY_LIVE_UPDATE_SYSTEM_SETTINGS,
                SettingsContract.KEY_LIVE_UPDATE_DISPLAY,
                SettingsContract.KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION,
                SettingsContract.KEY_EXPANDED_LIVE_UPDATE_DETAILS,
                SettingsContract.KEY_CHIP_CONTENT,
                SettingsContract.KEY_CHIP_SWITCHING_INTERVAL,
                SettingsContract.KEY_CHIP_INDICATE_CHARGING,
                SettingsContract.KEY_USE_PRIVILEGED_ACCESS,
                SettingsContract.KEY_ENABLE_LOGGING,
                SettingsContract.KEY_MAX_LOG_AGE,
                SettingsContract.KEY_EXPORT_GENERAL_BACKUP,
                SettingsContract.KEY_IMPORT_GENERAL_BACKUP,
                SettingsContract.KEY_EXPORT_SETTINGS,
                SettingsContract.KEY_IMPORT_SETTINGS,
                SettingsContract.KEY_RESET_DEFAULT_SETTINGS,
                SettingsContract.KEY_EXPORT_ALARMS,
                SettingsContract.KEY_IMPORT_ALARMS,
                SettingsContract.KEY_EXPORT_DEVICE_DATA,
                SettingsContract.KEY_IMPORT_DEVICE_DATA,
                SettingsContract.KEY_IMPORT_LOGS_CSV,
                SettingsContract.KEY_AUTO_LOG_EXPORT
            ), definitions.map { it.key })
        assertEquals(42, definitions.map { it.key }.toSet().size)
        assertEquals(16, definitions.count { it.control == SettingsControl.Action })
        assertEquals(11, definitions.count { it.control is SettingsControl.Toggle })
        assertEquals(13, definitions.count { it.control is SettingsControl.Choice })
        assertEquals(2, definitions.count { it.control is SettingsControl.Number })
        assertEquals(29, definitions.count { it.singleLineTitle == false })
        assertEquals(
            listOf(
                SettingsContract.KEY_CHANGE_APP_LANGUAGE_HOLDER,
                SettingsContract.KEY_CATEGORY_BATTERY_CURRENT_MAIN,
                SettingsContract.KEY_CATEGORY_STATUS_BAR_CHIP
            ), SettingsDefinitions.categories.values.flatten().mapNotNull { it.key })
        assertEquals(
            false, definitions.single { it.key == SettingsContract.KEY_ENABLE_NOTIFS_B }.selectable
        )
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
    fun `choice controls preserve option resources and dialog titles`() {
        val expected = mapOf(
            SettingsContract.KEY_TEMPERATURE_UNIT to (R.array.temperature_unit_entries to R.array.temperature_unit_values),
            SettingsContract.KEY_LONG_DURATION_FORMAT to (R.array.long_duration_format_entries to R.array.long_duration_format_values),
            SettingsContract.KEY_AUTOSTART to (R.array.boot_pref_entries to R.array.boot_pref_values),
            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER to (R.array.battery_current_multiplier_entries to R.array.battery_current_multiplier_values),
            SettingsContract.KEY_PREDICTION_TYPE to (R.array.prediction_type_entries to R.array.prediction_type_values),
            SettingsContract.KEY_CHARGING_TARGET_MODE to (R.array.charging_target_mode_entries to R.array.charging_target_mode_values),
            SettingsContract.KEY_TOP_LINE to (R.array.notification_line_content_entries to R.array.notification_line_content_values),
            SettingsContract.KEY_BOTTOM_LINE to (R.array.notification_line_content_entries to R.array.notification_line_content_values),
            SettingsContract.KEY_TIME_REMAINING_VERBOSITY to (R.array.time_remaining_verbosity_entries to R.array.time_remaining_verbosity_values),
            SettingsContract.KEY_ICON_CONTENT to (R.array.icon_content_entries to R.array.icon_content_values),
            SettingsContract.KEY_LIVE_UPDATE_DISPLAY to (R.array.live_update_display_entries to R.array.live_update_display_values),
            SettingsContract.KEY_CHIP_SWITCHING_INTERVAL to (R.array.chip_switching_interval_entries to R.array.chip_switching_interval_values),
            SettingsContract.KEY_MAX_LOG_AGE to (R.array.max_log_age_entries to R.array.max_log_age_values),
        )
        val choices = SettingsDefinitions.preferences.mapNotNull { definition ->
            (definition.control as? SettingsControl.Choice)?.let { definition.key to it }
        }.toMap()
        assertEquals(
            expected, choices.mapValues { (_, control) -> control.entries to control.entryValues })
        assertEquals(
            R.string.pref_autostart_at_boot_summary,
            choices.getValue(SettingsContract.KEY_AUTOSTART).dialogTitle
        )
        assertEquals(
            R.string.pref_max_log_age_summary,
            choices.getValue(SettingsContract.KEY_MAX_LOG_AGE).dialogTitle
        )
        assertEquals(2, choices.values.count { it.dialogTitle != null })
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
        for (control in numbers.values) {
            assertTrue(control.defaultValue in control.min..control.max)
            assertEquals(1, control.increment)
            assertTrue(control.adjustable)
            assertTrue(control.showValue)
        }
    }
}
