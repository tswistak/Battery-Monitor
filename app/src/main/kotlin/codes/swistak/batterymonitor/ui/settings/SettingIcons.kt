/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.settings.SettingsContract

private data class SettingIcon(
    val key: String, @param:StringRes val title: Int, @param:DrawableRes val drawable: Int
)

private val settingIcons = listOf(
    SettingIcon(
        SettingsContract.KEY_COLOR_SOURCE, R.string.settings_color_palette, R.drawable.ui_palette
    ),
    SettingIcon(
        SettingsContract.KEY_BRIGHTNESS, R.string.settings_brightness, R.drawable.ui_brightness
    ),
    SettingIcon(
        SettingsContract.KEY_CHANGE_APP_LANGUAGE,
        R.string.pref_change_app_language,
        R.drawable.ui_language
    ),
    SettingIcon(
        SettingsContract.KEY_TEMPERATURE_UNIT, R.string.pref_temperature_unit, R.drawable.ui_temp
    ),
    SettingIcon(
        SettingsContract.KEY_LONG_DURATION_FORMAT,
        R.string.pref_long_duration_format,
        R.drawable.ui_clock
    ),
    SettingIcon(
        SettingsContract.KEY_AUTOSTART, R.string.pref_autostart_at_boot, R.drawable.ui_refresh
    ),
    SettingIcon(
        SettingsContract.KEY_SHOW_REMAINING_CHARGE,
        R.string.pref_show_remaining_charge,
        R.drawable.ui_battery
    ),
    SettingIcon(
        SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER,
        R.string.pref_battery_current_multiplier,
        R.drawable.ui_current
    ),
    SettingIcon(
        SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT,
        R.string.pref_prefer_average_battery_current,
        R.drawable.ui_current
    ),
    SettingIcon(
        SettingsContract.KEY_PREDICTION_TYPE, R.string.pref_prediction_type, R.drawable.ui_history
    ),
    SettingIcon(
        SettingsContract.KEY_CHARGING_TARGET_MODE,
        R.string.pref_charging_target_mode,
        R.drawable.ui_target
    ),
    SettingIcon(
        SettingsContract.KEY_CUSTOM_CHARGING_TARGET,
        R.string.pref_custom_charging_target,
        R.drawable.ui_target
    ),
    SettingIcon(
        SettingsContract.KEY_DISCHARGING_TARGET,
        R.string.pref_discharging_target,
        R.drawable.ui_target
    ),
    SettingIcon(
        SettingsContract.KEY_ENABLE_NOTIFS_B, R.string.pref_cat_channel_settings, R.drawable.ui_bell
    ),
    SettingIcon(
        SettingsContract.KEY_DISMISS_LOW_BATTERY_ON_RECOVERY,
        R.string.pref_dismiss_low_battery_on_recovery,
        R.drawable.ui_bell
    ),
    SettingIcon(SettingsContract.KEY_TOP_LINE, R.string.pref_top_line_content, R.drawable.ui_text),
    SettingIcon(
        SettingsContract.KEY_BOTTOM_LINE, R.string.pref_bottom_line_content, R.drawable.ui_text
    ),
    SettingIcon(
        SettingsContract.KEY_TIME_REMAINING_VERBOSITY,
        R.string.pref_time_remaining_verbosity,
        R.drawable.ui_clock
    ),
    SettingIcon(
        SettingsContract.KEY_VITAL_SIGNS_CONTENT,
        R.string.pref_vital_signs_content,
        R.drawable.ui_heart
    ),
    SettingIcon(
        SettingsContract.KEY_EXPANDED_NOTIFICATION_DETAILS,
        R.string.pref_expanded_notification,
        R.drawable.ui_text
    ),
    SettingIcon(
        SettingsContract.KEY_ICON_CONTENT, R.string.pref_icon_content, R.drawable.ui_battery
    ),
    SettingIcon(
        SettingsContract.KEY_SHOW_ICON_UNIT, R.string.pref_show_icon_unit, R.drawable.ui_text
    ),
    SettingIcon(
        SettingsContract.KEY_INDICATE_CHARGING, R.string.pref_indicate_charging, R.drawable.ui_bolt
    ),
    SettingIcon(
        SettingsContract.KEY_LIVE_UPDATE_SYSTEM_SETTINGS,
        R.string.live_updates_notif_chan_name,
        R.drawable.ui_bell
    ),
    SettingIcon(
        SettingsContract.KEY_LIVE_UPDATE_DISPLAY,
        R.string.pref_live_update_display,
        R.drawable.ui_bell
    ),
    SettingIcon(
        SettingsContract.KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION,
        R.string.pref_live_update_keep_main_notification,
        R.drawable.ui_bell
    ),
    SettingIcon(
        SettingsContract.KEY_EXPANDED_LIVE_UPDATE_DETAILS,
        R.string.pref_expanded_live_update,
        R.drawable.ui_text
    ),
    SettingIcon(
        SettingsContract.KEY_CHIP_CONTENT, R.string.pref_chip_content, R.drawable.ui_battery
    ),
    SettingIcon(
        SettingsContract.KEY_CHIP_SWITCHING_INTERVAL,
        R.string.pref_chip_switching_interval,
        R.drawable.ui_clock
    ),
    SettingIcon(
        SettingsContract.KEY_CHIP_INDICATE_CHARGING,
        R.string.pref_chip_indicate_charging,
        R.drawable.ui_bolt
    ),
    SettingIcon(
        SettingsContract.KEY_USE_PRIVILEGED_ACCESS,
        R.string.pref_use_privileged_access,
        R.drawable.ui_shield
    ),
    SettingIcon(
        SettingsContract.KEY_ENABLE_LOGGING, R.string.pref_enable_logging, R.drawable.ui_storage
    ),
    SettingIcon(
        SettingsContract.KEY_MAX_LOG_AGE, R.string.pref_max_log_age, R.drawable.ui_calendar
    ),
    SettingIcon(
        SettingsContract.KEY_AUTO_LOG_EXPORT,
        R.string.pref_set_auto_log_export,
        R.drawable.ui_export
    ),
    SettingIcon(
        SettingsContract.KEY_AUTO_LOG_EXPORT_FREQUENCY,
        R.string.pref_auto_log_export_frequency,
        R.drawable.ui_clock
    ),
    SettingIcon(
        SettingsContract.KEY_AUTO_LOG_EXPORT_MODE,
        R.string.pref_auto_log_export_mode,
        R.drawable.ui_storage
    ),
    SettingIcon(
        SettingsContract.KEY_AUTO_LOG_EXPORT_FORMAT,
        R.string.pref_auto_log_export_format,
        R.drawable.ui_code
    ),
    SettingIcon(
        SettingsContract.KEY_AUTO_LOG_EXPORT_DIRECTORY,
        R.string.pref_auto_log_export_directory,
        R.drawable.ui_folder
    ),
    SettingIcon(
        SettingsContract.KEY_EXPORT_GENERAL_BACKUP,
        R.string.pref_export_general_backup,
        R.drawable.ui_export
    ),
    SettingIcon(
        SettingsContract.KEY_IMPORT_GENERAL_BACKUP,
        R.string.pref_import_general_backup,
        R.drawable.ui_import
    ),
    SettingIcon(
        SettingsContract.KEY_EXPORT_SETTINGS, R.string.pref_export_settings, R.drawable.ui_export
    ),
    SettingIcon(
        SettingsContract.KEY_IMPORT_SETTINGS, R.string.pref_import_settings, R.drawable.ui_import
    ),
    SettingIcon(
        SettingsContract.KEY_EXPORT_ALARMS, R.string.pref_export_alarms, R.drawable.ui_export
    ),
    SettingIcon(
        SettingsContract.KEY_IMPORT_ALARMS, R.string.pref_import_alarms, R.drawable.ui_import
    ),
    SettingIcon(
        SettingsContract.KEY_EXPORT_DEVICE_DATA,
        R.string.pref_export_device_data,
        R.drawable.ui_export
    ),
    SettingIcon(
        SettingsContract.KEY_IMPORT_DEVICE_DATA,
        R.string.pref_import_device_data,
        R.drawable.ui_import
    ),
    SettingIcon(
        SettingsContract.KEY_IMPORT_LOGS_CSV, R.string.pref_import_logs_csv, R.drawable.ui_import
    ),
    SettingIcon(
        SettingsContract.KEY_RESET_DEFAULT_SETTINGS,
        R.string.settings_reset_title,
        R.drawable.ui_refresh
    ),
    SettingIcon("background_monitoring", R.string.diagnostics_monitoring, R.drawable.ui_current),
    SettingIcon(
        SettingsContract.KEY_DEBUG_LOGGING, R.string.diagnostics_debug_logs, R.drawable.ui_code
    ),
    SettingIcon(
        "diagnostics_battery_optimization",
        R.string.diagnostics_battery_optimization,
        R.drawable.ui_battery
    ),
    SettingIcon(
        "diagnostics_vendor_settings", R.string.diagnostics_vendor_settings, R.drawable.ui_external
    ),
    SettingIcon(
        "diagnostics_dont_kill_my_app",
        R.string.diagnostics_dont_kill_my_app,
        R.drawable.ui_external
    ),
    SettingIcon(
        "diagnostics_notifications", R.string.diagnostics_notifications, R.drawable.ui_bell
    ),
    SettingIcon("diagnostics_service", R.string.diagnostics_service, R.drawable.ui_current),
    SettingIcon("diagnostics_database", R.string.diagnostics_database, R.drawable.ui_storage),
    SettingIcon("diagnostics_root", R.string.diagnostics_root, R.drawable.ui_shield),
    SettingIcon("diagnostics_shizuku", R.string.diagnostics_shizuku, R.drawable.ui_shield),
    SettingIcon("diagnostics_export", R.string.diagnostics_export, R.drawable.ui_export),
    SettingIcon("diagnostics_clear", R.string.diagnostics_clear, R.drawable.ui_delete),
    SettingIcon("monitor_start", R.string.diag_start_monitor, R.drawable.ui_bolt),
    SettingIcon("monitor_stop", R.string.diag_stop_monitor, R.drawable.ui_stop),
    SettingIcon(
        "charging_diagnostics_capture", R.string.charging_diagnostics_capture, R.drawable.ui_plug
    ),
    SettingIcon(
        "charging_diagnostics_report", R.string.charging_diagnostics_report, R.drawable.ui_export
    ),
    SettingIcon(
        "charging_diagnostics_clear", R.string.charging_diagnostics_clear, R.drawable.ui_delete
    )
)

private val iconsByKey = settingIcons.associate { it.key to it.drawable }
private val iconsByTitle = settingIcons.associate { it.title to it.drawable } + mapOf(
    R.string.settings_appearance to R.drawable.ui_palette,
    R.string.pref_cat_language to R.drawable.ui_language,
    R.string.pref_cat_autostart to R.drawable.ui_refresh,
    R.string.pref_cat_battery_current_main to R.drawable.ui_current,
    R.string.remaining_charge to R.drawable.ui_battery,
    R.string.pref_cat_predictor to R.drawable.ui_history,
    R.string.pref_cat_target_levels to R.drawable.ui_target,
    R.string.pref_cat_notification_content to R.drawable.ui_bell,
    R.string.status_bar_icon_settings to R.drawable.ui_battery,
    R.string.status_bar_chip_settings to R.drawable.ui_battery,
    R.string.pref_cat_privileged_access to R.drawable.ui_shield,
    R.string.pref_cat_logging to R.drawable.ui_storage,
    R.string.pref_general_backup to R.drawable.ui_storage,
    R.string.settings_activity_subtitle to R.drawable.ui_settings,
    R.string.pref_backup_device_data to R.drawable.ui_storage,
    R.string.pref_auto_log_export to R.drawable.ui_export,
    R.string.pref_disable_auto_log_export to R.drawable.ui_stop,
    R.string.alarm_settings to R.drawable.ui_bell,
    R.string.alarms_overview to R.drawable.ui_bell,
    R.string.channel_group_name_alarms to R.drawable.ui_bell,
    R.string.pref_alarm_enabled to R.drawable.ui_bell,
    R.string.pref_cat_alarm_conditions to R.drawable.ui_target,
    R.string.pref_alarm_type to R.drawable.ui_bell,
    R.string.pref_alarm_threshold to R.drawable.ui_target,
    R.string.settings_overview to R.drawable.ui_settings,
    R.string.other_settings to R.drawable.ui_settings,
    R.string.time_estimates_settings to R.drawable.ui_clock,
    R.string.tab_current_info to R.drawable.ui_battery,
    R.string.notification_settings to R.drawable.ui_bell,
    R.string.advanced_settings to R.drawable.ui_shield,
    R.string.pref_backup_restore to R.drawable.ui_storage,
    R.string.diagnostics to R.drawable.ui_current,
    R.string.nav_diagnostics to R.drawable.ui_current,
    R.string.diagnostics_help_title to R.drawable.ui_current,
    R.string.charging_diagnostics_title to R.drawable.ui_plug,
    R.string.advanced_section_counters to R.drawable.ui_current,
    R.string.advanced_section_capacity to R.drawable.ui_battery,
    R.string.advanced_section_charging to R.drawable.ui_plug,
    R.string.advanced_section_service to R.drawable.ui_battery,
    R.string.advanced_section_sysfs to R.drawable.ui_code,
    R.string.advanced_section_metadata to R.drawable.ui_info,
    R.string.device_data_predictor to R.drawable.ui_history,
    R.string.advanced_help_troubleshooting_title to R.drawable.ui_warning,
    R.string.help_cat_version to R.drawable.ui_info,
    R.string.help_cat_open_source to R.drawable.ui_code,
    R.string.help_cat_acknowledgments to R.drawable.ui_people,
    R.string.help_cat_frequently_asked_questions to R.drawable.ui_help,
    R.string.help_cat_contact to R.drawable.ui_contact,
    R.string.help_cat_privacy to R.drawable.ui_shield,
    R.string.help_cat_no_warranty to R.drawable.ui_warning
)

@DrawableRes
internal fun settingIcon(key: String): Int = iconsByKey[key] ?: R.drawable.ui_settings

@DrawableRes
internal fun helpHeadingIcon(@StringRes title: Int): Int? = iconsByTitle[title]
