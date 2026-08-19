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

internal object SettingsContract {
    const val SETTINGS_FILE: String = "codes.swistak.batterymonitor_preferences"
    const val SP_SERVICE_FILE: String = "sp_store"
    const val SP_MAIN_FILE: String = "sp_store_main"

    const val EXTRA_SCREEN: String = "codes.swistak.batterymonitor.PrefScreen"

    const val KEY_NOTIFICATION_SETTINGS: String = "notification_settings"
    const val KEY_STATUS_BAR_ICON_SETTINGS: String = "status_bar_icon_settings"
    const val KEY_STATUS_BAR_CHIP_SETTINGS: String = "status_bar_chip_settings"
    const val KEY_CURRENT_STATE_SETTINGS: String = "current_state_settings"
    const val KEY_ALARMS_SETTINGS: String = "alarms_settings"
    const val KEY_ALARM_EDIT_SETTINGS: String = "alarm_edit_settings"
    const val KEY_ADVANCED_INFO_HELP: String = "advanced_info_help"
    const val KEY_OTHER_SETTINGS: String = "other_settings"
    const val KEY_UNITS_FORMATTING_SETTINGS: String = "units_formatting_settings"
    const val KEY_ADVANCED_SETTINGS: String = "advanced_settings"

    const val KEY_BACKUP_RESTORE_SETTINGS: String = "backup_restore_settings"
    const val KEY_DIAGNOSTICS_SETTINGS: String = "diagnostics_settings"
    const val KEY_CHANGE_APP_LANGUAGE_HOLDER: String = "change_app_language_holder"
    const val KEY_CHANGE_APP_LANGUAGE: String = "change_app_language"
    const val KEY_PLUGIN_SETTINGS: String = "plugin_settings"
    const val KEY_CAT_STATUS_BAR_CHIP: String = "category_status_bar_chip"
    const val KEY_FIRST_RUN: String = "first_run"
    const val KEY_MIGRATED_SERVICE_DESIRED: String = "service_desired_migrated_to_sp_main"
    const val KEY_ENABLE_NOTIFS_B: String = "enable_notifications_button"
    const val KEY_ENABLE_NOTIFS_SUMMARY: String = "enable_notifications_summary"
    const val KEY_EXPORT_SETTINGS: String = "export_settings_backup"
    const val KEY_IMPORT_SETTINGS: String = "import_settings_backup"
    const val KEY_EXPORT_ALARMS: String = "export_alarms_backup"
    const val KEY_IMPORT_ALARMS: String = "import_alarms_backup"
    const val KEY_EXPORT_DEVICE_DATA: String = "export_device_data_backup"
    const val KEY_IMPORT_DEVICE_DATA: String = "import_device_data_backup"
    const val KEY_IMPORT_LOGS_CSV: String = "import_logs_csv_backup"
    const val KEY_EXPORT_GENERAL_BACKUP: String = "export_general_backup"
    const val KEY_IMPORT_GENERAL_BACKUP: String = "import_general_backup"

    const val KEY_AUTO_LOG_EXPORT: String = "auto_log_export"
    const val KEY_LAST_LOG_EXPORT_TIME: String = "last_log_export_time"
    const val KEY_AUTO_LOG_EXPORT_FREQUENCY: String = "auto_log_export_frequency"
    const val KEY_AUTO_LOG_EXPORT_MODE: String = "auto_log_export_mode"
    const val KEY_AUTO_LOG_EXPORT_FORMAT: String = "auto_log_export_format"
    const val KEY_AUTO_LOG_EXPORT_DIRECTORY: String = "auto_log_export_directory"
    const val KEY_LAST_AUTO_LOG_EXPORT_TIME: String = "last_auto_log_export_time"
    const val KEY_NEXT_AUTO_LOG_EXPORT_TIME: String = "next_auto_log_export_time"

    const val KEY_ENABLE_LOGGING: String = "enable_logging"
    const val KEY_DEBUG_LOGGING: String = "debug_logging"

    const val KEY_MAX_LOG_AGE: String = "max_log_age"
    const val KEY_ICON_CONTENT: String = "icon_content"
    const val KEY_SHOW_ICON_UNIT: String = "show_icon_unit"
    const val KEY_TEMPERATURE_UNIT: String = "temperature_unit"

    const val KEY_LONG_DURATION_FORMAT: String = "long_duration_format"

    const val KEY_NOTIFY_STATUS_DURATION: String = "notify_status_duration"
    const val KEY_AUTOSTART: String = "autostart"
    const val KEY_PREDICTION_TYPE: String = "prediction_type"
    const val KEY_STATUS_DUR_EST: String = "status_dur_est"
    const val KEY_INDICATE_CHARGING: String = "indicate_charging"
    const val KEY_CHIP_CONTENT: String = "chip_content"
    const val KEY_CHIP_CONTENT_ORDER: String = "chip_content_order"
    const val CHIP_CONTENT_PERCENTAGE: String = "percentage"
    const val CHIP_CONTENT_TEMPERATURE: String = "temperature"
    const val CHIP_CONTENT_VOLTAGE: String = "voltage"
    const val CHIP_CONTENT_CURRENT: String = "current"
    const val CHIP_CONTENT_CHARGE: String = "charge"
    val ALL_CHIP_CONTENT: List<String> = listOf(
        CHIP_CONTENT_PERCENTAGE,
        CHIP_CONTENT_TEMPERATURE,
        CHIP_CONTENT_VOLTAGE,
        CHIP_CONTENT_CURRENT,
        CHIP_CONTENT_CHARGE
    )
    val DEFAULT_CHIP_CONTENT: Set<String> = setOf(CHIP_CONTENT_PERCENTAGE)

    const val KEY_CHIP_SWITCHING_INTERVAL: String = "chip_switching_interval"
    const val KEY_CHIP_INDICATE_CHARGING: String = "chip_indicate_charging"
    const val KEY_LIVE_UPDATE_DISPLAY: String = "live_update_display"
    const val KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION: String = "live_update_keep_main_notification"
    const val KEY_RED: String = "use_red"
    const val KEY_RED_THRESH: String = "red_threshold"
    const val KEY_AMBER: String = "use_amber"
    const val KEY_AMBER_THRESH: String = "amber_threshold"
    const val KEY_GREEN: String = "use_green"
    const val KEY_GREEN_THRESH: String = "green_threshold"
    const val KEY_TOP_LINE: String = "top_line"
    const val KEY_BOTTOM_LINE: String = "bottom_line"
    const val KEY_TIME_REMAINING_VERBOSITY: String = "time_remaining_verbosity"
    const val KEY_STATUS_DURATION_IN_VITAL_SIGNS: String = "status_duration_in_vital_signs"

    const val KEY_VITAL_SIGNS_CONTENT: String = "vital_signs_content"
    const val KEY_VITAL_SIGNS_ORDER: String = "vital_signs_order"
    const val VITAL_SIGN_HEALTH: String = "health"
    const val VITAL_SIGN_TEMPERATURE: String = "temperature"
    const val VITAL_SIGN_VOLTAGE: String = "voltage"
    const val VITAL_SIGN_CURRENT: String = "current"
    const val VITAL_SIGN_CHARGE: String = "charge"
    const val VITAL_SIGN_STATUS_DURATION: String = "status_duration"
    val ALL_VITAL_SIGNS_CONTENT: List<String> = listOf(
        VITAL_SIGN_HEALTH,
        VITAL_SIGN_TEMPERATURE,
        VITAL_SIGN_VOLTAGE,
        VITAL_SIGN_CURRENT,
        VITAL_SIGN_CHARGE,
        VITAL_SIGN_STATUS_DURATION
    )
    val DEFAULT_VITAL_SIGNS_CONTENT: Set<String> = ALL_VITAL_SIGNS_CONTENT.take(3).toSet()
    const val KEY_ENABLE_BATTERY_CURRENT: String = "enable_battery_current"
    const val KEY_USE_PRIVILEGED_BATTERY_CURRENT: String = "use_privileged_battery_current"
    const val KEY_BATTERY_CURRENT_MULTIPLIER: String = "battery_current_multiplier"
    const val KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING: String =
        "battery_current_multiplier_detection_pending"

    const val KEY_BATTERY_CURRENT_REFRESH_INTERVAL: String = "battery_current_refresh_interval"
    const val KEY_DISPLAY_CURRENT_IN_NOTIFICATION: String = "display_current_in_notification"
    const val KEY_PREFER_AVERAGE_BATTERY_CURRENT: String = "prefer_average_battery_current"

    const val KEY_SHOW_REMAINING_CHARGE: String = "show_remaining_charge"

    const val KEY_UI_COLOR: String = "ui_color"
    const val KEY_ENABLE_ADVANCED_STATS: String = "enable_advanced_stats"

    const val LEGACY_KEY_ENABLE_CURRENT = "enable_current_hack"
    const val LEGACY_KEY_CONVERT_F = "convert_to_fahrenheit"

    const val LEGACY_KEY_PREFER_FILE_SYSTEM = "current_hack_prefer_fs"
    const val LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER = "current_hack_multiplier"
    const val LEGACY_KEY_CURRENT_SOURCE = "battery_current_source"
    const val LEGACY_KEY_DISPLAY_CURRENT_IN_VITAL_STATS = "display_current_in_vital_stats"
    const val LEGACY_KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS = "prefer_current_avg_in_vital_stats"
    const val LEGACY_KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW = "display_current_in_main_window"
    const val LEGACY_KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW = "prefer_current_avg_in_main_window"
    const val LEGACY_KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW = "auto_refresh_current_in_main_window"
}
