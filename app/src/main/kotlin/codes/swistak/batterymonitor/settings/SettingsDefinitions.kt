/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.settings

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import codes.swistak.batterymonitor.R

internal sealed interface SettingsTextDefault {
    data class Literal(val value: String) : SettingsTextDefault
    data class Resource(@param:StringRes val resource: Int) : SettingsTextDefault

    fun resolve(text: (Int) -> String): String = when (this) {
        is Literal -> value
        is Resource -> text(resource)
    }
}

internal sealed interface SettingsControl {
    data object Action : SettingsControl
    data class Toggle(val defaultValue: Boolean) : SettingsControl
    data class Choice(
        @param:ArrayRes val entries: Int,
        @param:ArrayRes val entryValues: Int,
        val defaultValue: SettingsTextDefault,
        @param:StringRes val dialogTitle: Int? = null
    ) : SettingsControl

    data class Number(
        val defaultValue: Int,
        val min: Int,
        val max: Int,
    ) : SettingsControl

    fun defaultValue(text: (Int) -> String): Any? = when (this) {
        Action -> null
        is Toggle -> defaultValue
        is Choice -> defaultValue.resolve(text)
        is Number -> defaultValue
    }
}

internal data class SettingsPreferenceDefinition(
    val key: String,
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int? = null,
    val control: SettingsControl = SettingsControl.Action
)

internal data class SettingsGroup(
    @param:StringRes val title: Int, val preferences: List<SettingsPreferenceDefinition>
)

internal object SettingsDefinitions {
    val categories: Map<String, List<SettingsGroup>> = linkedMapOf(
        SettingsContract.KEY_OTHER_SETTINGS to listOf(
            SettingsGroup(
                title = R.string.settings_appearance, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_COLOR_SOURCE,
                        title = R.string.settings_color_palette,
                        summary = R.string.settings_color_palette_summary,
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_BRIGHTNESS,
                        title = R.string.settings_brightness,
                        summary = R.string.settings_brightness_summary,
                    ),
                )
            ),
            SettingsGroup(
                title = R.string.pref_cat_language, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_CHANGE_APP_LANGUAGE,
                        title = R.string.pref_change_app_language,
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_TEMPERATURE_UNIT,
                        title = R.string.pref_temperature_unit,
                        control = SettingsControl.Choice(
                            entries = R.array.temperature_unit_entries,
                            entryValues = R.array.temperature_unit_values,
                            defaultValue = SettingsTextDefault.Resource(R.string.default_temperature_unit),
                        ),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_LONG_DURATION_FORMAT,
                        title = R.string.pref_long_duration_format,
                        summary = R.string.pref_long_duration_format_help,
                        control = SettingsControl.Choice(
                            entries = R.array.long_duration_format_entries,
                            entryValues = R.array.long_duration_format_values,
                            defaultValue = SettingsTextDefault.Literal("days_and_hours"),
                        ),
                    ),
                )
            ),
            SettingsGroup(
                title = R.string.pref_cat_autostart, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_AUTOSTART,
                        title = R.string.pref_autostart_at_boot,
                        control = SettingsControl.Choice(
                            entries = R.array.boot_pref_entries,
                            entryValues = R.array.boot_pref_values,
                            defaultValue = SettingsTextDefault.Literal("auto"),
                            dialogTitle = R.string.pref_autostart_at_boot_summary,
                        ),
                    ),
                )
            ),
        ),
        SettingsContract.KEY_CURRENT_STATE_SETTINGS to listOf(
            SettingsGroup(
                title = R.string.remaining_charge, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_SHOW_REMAINING_CHARGE,
                        title = R.string.pref_show_remaining_charge,
                        summary = R.string.pref_show_remaining_charge_summary,
                        control = SettingsControl.Toggle(true),
                    ),
                )
            ),
            SettingsGroup(
                title = R.string.pref_cat_battery_current_main, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER,
                        title = R.string.pref_battery_current_multiplier,
                        control = SettingsControl.Choice(
                            entries = R.array.battery_current_multiplier_entries,
                            entryValues = R.array.battery_current_multiplier_values,
                            defaultValue = SettingsTextDefault.Literal("1"),
                        ),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT,
                        title = R.string.pref_prefer_average_battery_current,
                        summary = R.string.pref_prefer_average_battery_current_summary,
                        control = SettingsControl.Toggle(false),
                    ),
                )
            ),
        ),
        SettingsContract.KEY_TIME_ESTIMATES_SETTINGS to listOf(
            SettingsGroup(
                title = R.string.pref_cat_predictor, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_PREDICTION_TYPE,
                        title = R.string.pref_prediction_type,
                        control = SettingsControl.Choice(
                            entries = R.array.prediction_type_entries,
                            entryValues = R.array.prediction_type_values,
                            defaultValue = SettingsTextDefault.Resource(R.string.default_prediction_type),
                        ),
                    ),
                )
            ),
            SettingsGroup(
                title = R.string.pref_cat_target_levels, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_CHARGING_TARGET_MODE,
                        title = R.string.pref_charging_target_mode,
                        control = SettingsControl.Choice(
                            entries = R.array.charging_target_mode_entries,
                            entryValues = R.array.charging_target_mode_values,
                            defaultValue = SettingsTextDefault.Literal(SettingsContract.CHARGING_TARGET_MODE_AUTOMATIC),
                        ),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_CUSTOM_CHARGING_TARGET,
                        title = R.string.pref_custom_charging_target,
                        control = SettingsControl.Number(
                            defaultValue = SettingsContract.DEFAULT_CUSTOM_CHARGING_TARGET,
                            min = 1,
                            max = 100
                        ),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_DISCHARGING_TARGET,
                        title = R.string.pref_discharging_target,
                        control = SettingsControl.Number(
                            defaultValue = SettingsContract.DEFAULT_DISCHARGING_TARGET,
                            min = 0,
                            max = 99
                        ),
                    ),
                )
            ),
        ),
        SettingsContract.KEY_NOTIFICATION_SETTINGS to listOf(
            SettingsGroup(
                title = R.string.pref_cat_channel_settings, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_ENABLE_NOTIFS_B,
                        title = R.string.pref_manage_main_channel,
                        summary = R.string.pref_manage_main_channel_summary,
                    ),
                )
            ),
            SettingsGroup(
                title = R.string.pref_cat_notification_content, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_TOP_LINE,
                        title = R.string.pref_top_line_content,
                        control = SettingsControl.Choice(
                            entries = R.array.notification_line_content_entries,
                            entryValues = R.array.notification_line_content_values,
                            defaultValue = SettingsTextDefault.Resource(R.string.default_top_line_content),
                        ),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_BOTTOM_LINE,
                        title = R.string.pref_bottom_line_content,
                        control = SettingsControl.Choice(
                            entries = R.array.notification_line_content_entries,
                            entryValues = R.array.notification_line_content_values,
                            defaultValue = SettingsTextDefault.Resource(R.string.default_bottom_line_content),
                        ),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_TIME_REMAINING_VERBOSITY,
                        title = R.string.pref_time_remaining_verbosity,
                        control = SettingsControl.Choice(
                            entries = R.array.time_remaining_verbosity_entries,
                            entryValues = R.array.time_remaining_verbosity_values,
                            defaultValue = SettingsTextDefault.Resource(R.string.default_time_remaining_verbosity),
                        ),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_VITAL_SIGNS_CONTENT,
                        title = R.string.pref_vital_signs_content,
                        summary = R.string.pref_vital_signs_content_summary,
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_EXPANDED_NOTIFICATION_DETAILS,
                        title = R.string.pref_expanded_notification,
                        summary = R.string.pref_expanded_notification_summary,
                        control = SettingsControl.Toggle(true),
                    ),
                )
            ),
            SettingsGroup(
                title = R.string.status_bar_icon_settings, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_ICON_CONTENT,
                        title = R.string.pref_icon_content,
                        control = SettingsControl.Choice(
                            entries = R.array.icon_content_entries,
                            entryValues = R.array.icon_content_values,
                            defaultValue = SettingsTextDefault.Resource(R.string.default_icon_content),
                        ),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_SHOW_ICON_UNIT,
                        title = R.string.pref_show_icon_unit,
                        summary = R.string.pref_show_icon_unit_summary,
                        control = SettingsControl.Toggle(true),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_INDICATE_CHARGING,
                        title = R.string.pref_indicate_charging,
                        summary = R.string.pref_indicate_charging_summary,
                        control = SettingsControl.Toggle(true),
                    ),
                )
            ),
            SettingsGroup(
                title = R.string.status_bar_chip_settings, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_LIVE_UPDATE_SYSTEM_SETTINGS,
                        title = R.string.live_updates_notif_chan_name,
                        summary = R.string.settings_live_update_summary,
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_LIVE_UPDATE_DISPLAY,
                        title = R.string.pref_live_update_display,
                        summary = R.string.pref_live_update_display_summary,
                        control = SettingsControl.Choice(
                            entries = R.array.live_update_display_entries,
                            entryValues = R.array.live_update_display_values,
                            defaultValue = SettingsTextDefault.Resource(R.string.default_live_update_display_mode),
                        ),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION,
                        title = R.string.pref_live_update_keep_main_notification,
                        summary = R.string.pref_live_update_keep_main_notification_summary,
                        control = SettingsControl.Toggle(false),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_EXPANDED_LIVE_UPDATE_DETAILS,
                        title = R.string.pref_expanded_live_update,
                        summary = R.string.pref_expanded_live_update_summary,
                        control = SettingsControl.Toggle(false),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_CHIP_CONTENT,
                        title = R.string.pref_chip_content,
                        summary = R.string.pref_chip_content_summary,
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_CHIP_SWITCHING_INTERVAL,
                        title = R.string.pref_chip_switching_interval,
                        summary = R.string.pref_chip_switching_interval_summary,
                        control = SettingsControl.Choice(
                            entries = R.array.chip_switching_interval_entries,
                            entryValues = R.array.chip_switching_interval_values,
                            defaultValue = SettingsTextDefault.Literal("5"),
                        ),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_CHIP_INDICATE_CHARGING,
                        title = R.string.pref_chip_indicate_charging,
                        summary = R.string.pref_chip_indicate_charging_summary,
                        control = SettingsControl.Toggle(true),
                    ),
                )
            ),
        ),
        SettingsContract.KEY_ADVANCED_SETTINGS to listOf(
            SettingsGroup(
                title = R.string.pref_cat_privileged_access, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_USE_PRIVILEGED_ACCESS,
                        title = R.string.pref_use_privileged_access,
                        summary = R.string.pref_use_privileged_access_summary,
                        control = SettingsControl.Toggle(false),
                    ),
                )
            ),
            SettingsGroup(
                title = R.string.pref_cat_logging, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_ENABLE_LOGGING,
                        title = R.string.pref_enable_logging,
                        summary = R.string.pref_enable_logging_summary,
                        control = SettingsControl.Toggle(true),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_MAX_LOG_AGE,
                        title = R.string.pref_max_log_age,
                        control = SettingsControl.Choice(
                            entries = R.array.max_log_age_entries,
                            entryValues = R.array.max_log_age_values,
                            defaultValue = SettingsTextDefault.Resource(R.string.default_max_log_age),
                            dialogTitle = R.string.pref_max_log_age,
                        ),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_AUTO_LOG_EXPORT,
                        title = R.string.pref_auto_log_export_title,
                        summary = R.string.pref_auto_log_export_setup_summary,
                    ),
                )
            ),
        ),
        SettingsContract.KEY_BACKUP_RESTORE_SETTINGS to listOf(
            SettingsGroup(
                title = R.string.pref_general_backup, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_EXPORT_GENERAL_BACKUP,
                        title = R.string.pref_export_general_backup,
                        summary = R.string.pref_export_general_backup_summary,
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_IMPORT_GENERAL_BACKUP,
                        title = R.string.pref_import_general_backup,
                        summary = R.string.pref_import_general_backup_summary,
                    ),
                )
            ),
            SettingsGroup(
                title = R.string.settings_activity_subtitle, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_EXPORT_SETTINGS,
                        title = R.string.pref_export_settings,
                        summary = R.string.pref_export_settings_summary,
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_IMPORT_SETTINGS,
                        title = R.string.pref_import_settings,
                        summary = R.string.pref_import_settings_summary,
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_RESET_DEFAULT_SETTINGS,
                        title = R.string.settings_reset_title,
                        summary = R.string.settings_reset_summary,
                    ),
                )
            ),
            SettingsGroup(
                title = R.string.alarm_settings, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_EXPORT_ALARMS,
                        title = R.string.pref_export_alarms,
                        summary = R.string.pref_export_alarms_summary,
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_IMPORT_ALARMS,
                        title = R.string.pref_import_alarms,
                        summary = R.string.pref_import_alarms_summary,
                    ),
                )
            ),
            SettingsGroup(
                title = R.string.pref_backup_device_data, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_EXPORT_DEVICE_DATA,
                        title = R.string.pref_export_device_data,
                        summary = R.string.pref_export_device_data_summary,
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_IMPORT_DEVICE_DATA,
                        title = R.string.pref_import_device_data,
                        summary = R.string.pref_import_device_data_summary,
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_IMPORT_LOGS_CSV,
                        title = R.string.pref_import_logs_csv,
                        summary = R.string.pref_import_logs_csv_summary,
                    ),
                )
            ),
        ),
    )

    val preferences: List<SettingsPreferenceDefinition> =
        categories.values.flatten().flatMap { it.preferences }

    val byKey = preferences.associateBy { it.key }

    fun defaultValues(text: (Int) -> String): Map<String, Any> = buildMap {
        for ((key, _, _, control) in preferences) {
            control.defaultValue(text)?.let { put(key, it) }
        }
        put(
            SettingsContract.KEY_DISMISS_LOW_BATTERY_ON_RECOVERY,
            SettingsContract.DEFAULT_DISMISS_LOW_BATTERY_ON_RECOVERY
        )
    }
}
