/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.help

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.ui.settings.SettingCatalog

internal data class HelpParagraph(
    @param:StringRes val textResource: Int,
    val subheading: Boolean = false,
    val controlKey: String? = null
)

internal data class HelpSection(
    @param:StringRes val titleResource: Int?,
    val paragraphs: List<HelpParagraph>,
    val controlKey: String? = null,
    @param:DrawableRes val iconResource: Int? = null
)

internal const val MONITORING_HELP_TOPIC = "monitoring_help"
internal const val CHARGING_TOOLS_HELP_TOPIC = "charging_tools_help"

internal fun diagnosticDetailHelpTopic(detail: String?): String =
    when (detail?.substringBefore(':')) {
        null -> SettingsContract.KEY_ADVANCED_INFO_HELP
        "charging-tools" -> CHARGING_TOOLS_HELP_TOPIC
        else -> MONITORING_HELP_TOPIC
    }

internal fun helpSections(
    topic: String?, sdkInt: Int, chargingTargetMode: String?
): List<HelpSection> {
    val customTargetVisible = SettingCatalog.isVisible(
        SettingsContract.KEY_CUSTOM_CHARGING_TARGET, sdkInt, chargingTargetMode
    )
    val chipVisible = SettingCatalog.isVisible(
        SettingsContract.KEY_CHIP_CONTENT, sdkInt, chargingTargetMode
    )
    return when (topic) {
        null -> listOf(
            HelpSection(
                R.string.help_cat_version, listOf(
                    HelpParagraph(R.string.nav_help_version),
                ), iconResource = R.drawable.ui_info
            ),
            HelpSection(
                R.string.help_cat_open_source, listOf(
                    HelpParagraph(R.string.help_open_source),
                ), iconResource = R.drawable.ui_code
            ),
            HelpSection(
                R.string.help_cat_acknowledgments, listOf(
                    HelpParagraph(R.string.help_acknowledgments),
                ), iconResource = R.drawable.ui_people
            ),
            HelpSection(
                R.string.help_cat_frequently_asked_questions, listOf(
                    HelpParagraph(R.string.help_frequently_asked_questions),
                ), iconResource = R.drawable.ui_help
            ),
            HelpSection(
                R.string.help_cat_contact, listOf(
                    HelpParagraph(R.string.help_contact),
                ), iconResource = R.drawable.ui_contact
            ),
            HelpSection(
                R.string.help_cat_privacy, listOf(
                    HelpParagraph(R.string.help_privacy),
                ), iconResource = R.drawable.ui_shield
            ),
            HelpSection(
                R.string.help_cat_no_warranty, listOf(
                    HelpParagraph(R.string.help_no_warranty),
                ), iconResource = R.drawable.ui_warning
            ),
        )

        SettingsContract.KEY_NOTIFICATION_SETTINGS -> listOfNotNull(
            HelpSection(
                R.string.pref_cat_channel_settings, listOf(
                    HelpParagraph(
                        R.string.pref_manage_main_channel,
                        subheading = true,
                        controlKey = SettingsContract.KEY_ENABLE_NOTIFS_B
                    ),
                    HelpParagraph(R.string.notification_channels_help),
                )
            ),
            HelpSection(
                R.string.pref_cat_notification_content, listOf(
                    HelpParagraph(
                        R.string.pref_top_line_content,
                        subheading = true,
                        controlKey = SettingsContract.KEY_TOP_LINE
                    ),
                    HelpParagraph(R.string.pref_top_line_content_help),
                    HelpParagraph(
                        R.string.pref_bottom_line_content,
                        subheading = true,
                        controlKey = SettingsContract.KEY_BOTTOM_LINE
                    ),
                    HelpParagraph(R.string.pref_bottom_line_content_help),
                    HelpParagraph(
                        R.string.pref_time_remaining_verbosity,
                        subheading = true,
                        controlKey = SettingsContract.KEY_TIME_REMAINING_VERBOSITY
                    ),
                    HelpParagraph(R.string.pref_time_remaining_verbosity_help),
                    HelpParagraph(
                        R.string.pref_vital_signs_content,
                        subheading = true,
                        controlKey = SettingsContract.KEY_VITAL_SIGNS_CONTENT
                    ),
                    HelpParagraph(R.string.pref_vital_signs_content_help),
                    HelpParagraph(
                        R.string.pref_expanded_notification,
                        subheading = true,
                        controlKey = SettingsContract.KEY_EXPANDED_NOTIFICATION_DETAILS
                    ),
                    HelpParagraph(R.string.pref_expanded_notification_help),
                )
            ),
            HelpSection(
                R.string.status_bar_icon_settings, listOf(
                    HelpParagraph(R.string.status_bar_icon_availability_help),
                    HelpParagraph(
                        R.string.pref_icon_content,
                        subheading = true,
                        controlKey = SettingsContract.KEY_ICON_CONTENT
                    ),
                    HelpParagraph(R.string.pref_icon_content_help),
                    HelpParagraph(
                        R.string.pref_show_icon_unit,
                        subheading = true,
                        controlKey = SettingsContract.KEY_SHOW_ICON_UNIT
                    ),
                    HelpParagraph(R.string.pref_show_icon_unit_help),
                    HelpParagraph(
                        R.string.pref_indicate_charging,
                        subheading = true,
                        controlKey = SettingsContract.KEY_INDICATE_CHARGING
                    ),
                    HelpParagraph(R.string.pref_indicate_charging_help),
                )
            ),
            if (chipVisible) HelpSection(
                R.string.status_bar_chip_settings, listOf(
                    HelpParagraph(R.string.status_bar_chip_availability_help),
                    HelpParagraph(
                        R.string.live_updates_notif_chan_name,
                        subheading = true,
                        controlKey = SettingsContract.KEY_LIVE_UPDATE_SYSTEM_SETTINGS
                    ),
                    HelpParagraph(R.string.settings_live_update_help),
                    HelpParagraph(
                        R.string.pref_live_update_display,
                        subheading = true,
                        controlKey = SettingsContract.KEY_LIVE_UPDATE_DISPLAY
                    ),
                    HelpParagraph(R.string.pref_live_update_display_help),
                    HelpParagraph(
                        R.string.pref_live_update_keep_main_notification,
                        subheading = true,
                        controlKey = SettingsContract.KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION
                    ),
                    HelpParagraph(R.string.pref_live_update_keep_main_notification_help),
                    HelpParagraph(
                        R.string.pref_expanded_live_update,
                        subheading = true,
                        controlKey = SettingsContract.KEY_EXPANDED_LIVE_UPDATE_DETAILS
                    ),
                    HelpParagraph(R.string.pref_expanded_live_update_help),
                    HelpParagraph(
                        R.string.pref_chip_content,
                        subheading = true,
                        controlKey = SettingsContract.KEY_CHIP_CONTENT
                    ),
                    HelpParagraph(R.string.pref_chip_content_help),
                    HelpParagraph(
                        R.string.pref_chip_switching_interval,
                        subheading = true,
                        controlKey = SettingsContract.KEY_CHIP_SWITCHING_INTERVAL
                    ),
                    HelpParagraph(R.string.pref_chip_switching_interval_help),
                    HelpParagraph(
                        R.string.pref_chip_indicate_charging,
                        subheading = true,
                        controlKey = SettingsContract.KEY_CHIP_INDICATE_CHARGING
                    ),
                    HelpParagraph(R.string.pref_chip_indicate_charging_help),
                )
            ) else null,
        )

        SettingsContract.KEY_CURRENT_STATE_SETTINGS -> listOf(
            HelpSection(
                R.string.remaining_charge, listOf(
                    HelpParagraph(
                        R.string.pref_show_remaining_charge,
                        subheading = true,
                        controlKey = SettingsContract.KEY_SHOW_REMAINING_CHARGE
                    ),
                    HelpParagraph(R.string.pref_show_remaining_charge_help),
                )
            ),
            HelpSection(
                R.string.pref_cat_battery_current_main, listOf(
                    HelpParagraph(
                        R.string.pref_battery_current_multiplier,
                        subheading = true,
                        controlKey = SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER
                    ),
                    HelpParagraph(R.string.pref_battery_current_multiplier_help),
                    HelpParagraph(
                        R.string.pref_prefer_average_battery_current,
                        subheading = true,
                        controlKey = SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT
                    ),
                    HelpParagraph(R.string.pref_prefer_current_avg_help),
                )
            ),
        )

        SettingsContract.KEY_OTHER_SETTINGS -> listOf(
            HelpSection(
                R.string.settings_appearance, listOfNotNull(
                    HelpParagraph(
                        R.string.settings_color_palette,
                        subheading = true,
                        controlKey = SettingsContract.KEY_COLOR_SOURCE
                    ),
                    HelpParagraph(R.string.settings_color_palette_help),
                    if (sdkInt < 31) HelpParagraph(R.string.settings_color_palette_unavailable) else null,
                    HelpParagraph(
                        R.string.settings_brightness,
                        subheading = true,
                        controlKey = SettingsContract.KEY_BRIGHTNESS
                    ),
                    HelpParagraph(R.string.settings_brightness_help),
                )
            ),
            HelpSection(
                R.string.pref_cat_language, listOf(
                    HelpParagraph(
                        R.string.pref_change_app_language,
                        subheading = true,
                        controlKey = SettingsContract.KEY_CHANGE_APP_LANGUAGE
                    ),
                    HelpParagraph(
                        if (sdkInt >= 33) R.string.pref_change_app_language_help
                        else R.string.settings_app_language_unavailable_help
                    ),
                    HelpParagraph(
                        R.string.pref_temperature_unit,
                        subheading = true,
                        controlKey = SettingsContract.KEY_TEMPERATURE_UNIT
                    ),
                    HelpParagraph(R.string.pref_temperature_unit_help),
                    HelpParagraph(
                        R.string.pref_long_duration_format,
                        subheading = true,
                        controlKey = SettingsContract.KEY_LONG_DURATION_FORMAT
                    ),
                    HelpParagraph(R.string.pref_long_duration_format_help),
                )
            ),
            HelpSection(
                R.string.pref_cat_autostart, listOf(
                    HelpParagraph(
                        R.string.pref_autostart_at_boot,
                        subheading = true,
                        controlKey = SettingsContract.KEY_AUTOSTART
                    ),
                    HelpParagraph(R.string.pref_autostart_at_boot_help),
                )
            ),
        )

        SettingsContract.KEY_TIME_ESTIMATES_SETTINGS -> listOf(
            HelpSection(
                R.string.pref_cat_predictor, listOf(
                    HelpParagraph(
                        R.string.pref_prediction_type,
                        subheading = true,
                        controlKey = SettingsContract.KEY_PREDICTION_TYPE
                    ),
                    HelpParagraph(R.string.pref_prediction_type_help),
                )
            ),
            HelpSection(
                R.string.pref_cat_target_levels, listOfNotNull(
                    HelpParagraph(
                        R.string.pref_charging_target_mode,
                        subheading = true,
                        controlKey = SettingsContract.KEY_CHARGING_TARGET_MODE
                    ),
                    HelpParagraph(R.string.pref_charging_target_mode_help),
                    if (customTargetVisible) HelpParagraph(
                        R.string.pref_custom_charging_target,
                        subheading = true,
                        controlKey = SettingsContract.KEY_CUSTOM_CHARGING_TARGET
                    ) else null,
                    if (customTargetVisible) HelpParagraph(R.string.pref_custom_charging_target_help) else null,
                    HelpParagraph(
                        R.string.pref_discharging_target,
                        subheading = true,
                        controlKey = SettingsContract.KEY_DISCHARGING_TARGET
                    ),
                    HelpParagraph(R.string.pref_discharging_target_help),
                )
            ),
        )

        SettingsContract.KEY_ADVANCED_SETTINGS -> listOf(
            HelpSection(
                R.string.pref_cat_privileged_access, listOf(
                    HelpParagraph(
                        R.string.pref_use_privileged_access,
                        subheading = true,
                        controlKey = SettingsContract.KEY_USE_PRIVILEGED_ACCESS
                    ),
                    HelpParagraph(R.string.pref_use_privileged_access_help),
                )
            ),
            HelpSection(
                R.string.pref_cat_logging, listOf(
                    HelpParagraph(
                        R.string.pref_enable_logging,
                        subheading = true,
                        controlKey = SettingsContract.KEY_ENABLE_LOGGING
                    ),
                    HelpParagraph(R.string.pref_enable_logging_help),
                    HelpParagraph(
                        R.string.pref_max_log_age,
                        subheading = true,
                        controlKey = SettingsContract.KEY_MAX_LOG_AGE
                    ),
                    HelpParagraph(R.string.pref_max_log_age_help),
                    HelpParagraph(
                        R.string.pref_auto_log_export_title,
                        subheading = true,
                        controlKey = SettingsContract.KEY_AUTO_LOG_EXPORT
                    ),
                    HelpParagraph(R.string.auto_log_export_help),
                    HelpParagraph(R.string.auto_log_export_format_help),
                    HelpParagraph(R.string.auto_log_export_options_help),
                )
            ),
            HelpSection(
                R.string.diag_monitor_operation, listOf(
                    HelpParagraph(R.string.diagnostics_help_overview),
                ), controlKey = "background_monitoring"
            ),
            HelpSection(
                R.string.charging_diagnostics_title, listOf(
                    HelpParagraph(R.string.charging_diagnostics_help_overview),
                ), controlKey = "charging_diagnostics_capture"
            ),
        )

        SettingsContract.KEY_BACKUP_RESTORE_SETTINGS -> listOf(
            HelpSection(
                R.string.pref_general_backup, listOf(
                    HelpParagraph(
                        R.string.pref_export_general_backup,
                        subheading = true,
                        controlKey = SettingsContract.KEY_EXPORT_GENERAL_BACKUP
                    ),
                    HelpParagraph(R.string.pref_export_general_backup_help),
                    HelpParagraph(
                        R.string.pref_import_general_backup,
                        subheading = true,
                        controlKey = SettingsContract.KEY_IMPORT_GENERAL_BACKUP
                    ),
                    HelpParagraph(R.string.pref_import_general_backup_help),
                )
            ),
            HelpSection(
                R.string.settings_activity_subtitle, listOf(
                    HelpParagraph(
                        R.string.pref_export_settings,
                        subheading = true,
                        controlKey = SettingsContract.KEY_EXPORT_SETTINGS
                    ),
                    HelpParagraph(R.string.pref_export_settings_help),
                    HelpParagraph(
                        R.string.pref_import_settings,
                        subheading = true,
                        controlKey = SettingsContract.KEY_IMPORT_SETTINGS
                    ),
                    HelpParagraph(R.string.pref_import_settings_help),
                    HelpParagraph(
                        R.string.settings_reset_title,
                        subheading = true,
                        controlKey = SettingsContract.KEY_RESET_DEFAULT_SETTINGS
                    ),
                    HelpParagraph(R.string.settings_reset_help),
                )
            ),
            HelpSection(
                R.string.alarm_settings, listOf(
                    HelpParagraph(
                        R.string.pref_export_alarms,
                        subheading = true,
                        controlKey = SettingsContract.KEY_EXPORT_ALARMS
                    ),
                    HelpParagraph(R.string.pref_export_alarms_help),
                    HelpParagraph(
                        R.string.pref_import_alarms,
                        subheading = true,
                        controlKey = SettingsContract.KEY_IMPORT_ALARMS
                    ),
                    HelpParagraph(R.string.pref_import_alarms_help),
                )
            ),
            HelpSection(
                R.string.pref_backup_device_data, listOf(
                    HelpParagraph(
                        R.string.pref_export_device_data,
                        subheading = true,
                        controlKey = SettingsContract.KEY_EXPORT_DEVICE_DATA
                    ),
                    HelpParagraph(R.string.pref_export_device_data_help),
                    HelpParagraph(
                        R.string.pref_import_device_data,
                        subheading = true,
                        controlKey = SettingsContract.KEY_IMPORT_DEVICE_DATA
                    ),
                    HelpParagraph(R.string.pref_import_device_data_help),
                    HelpParagraph(
                        R.string.pref_import_logs_csv,
                        subheading = true,
                        controlKey = SettingsContract.KEY_IMPORT_LOGS_CSV
                    ),
                    HelpParagraph(R.string.csv_logs_import_help),
                )
            ),
        )

        SettingsContract.KEY_DIAGNOSTICS_SETTINGS, MONITORING_HELP_TOPIC -> listOf(
            HelpSection(
                R.string.diagnostics_permissions, listOfNotNull(
                    HelpParagraph(
                        R.string.diagnostics_notifications,
                        subheading = true,
                        controlKey = "diagnostics_notifications"
                    ),
                    HelpParagraph(R.string.notification_channels_help),
                    if (chipVisible) HelpParagraph(
                        R.string.live_updates_notif_chan_name,
                        subheading = true,
                        controlKey = "diagnostics_live_updates"
                    ) else null,
                    if (chipVisible) HelpParagraph(R.string.settings_live_update_help) else null,
                    HelpParagraph(
                        R.string.diagnostics_root,
                        subheading = true,
                        controlKey = "diagnostics_root"
                    ),
                    HelpParagraph(R.string.diagnostics_root_help),
                    HelpParagraph(
                        R.string.diagnostics_shizuku,
                        subheading = true,
                        controlKey = "diagnostics_shizuku"
                    ),
                    HelpParagraph(R.string.diagnostics_shizuku_help),
                    HelpParagraph(R.string.monitoring_privileged_access_help),
                )
            ),
            HelpSection(
                R.string.diagnostics_monitoring, listOf(
                    HelpParagraph(
                        R.string.diagnostics_service,
                        subheading = true,
                        controlKey = "diagnostics_service"
                    ),
                    HelpParagraph(R.string.diagnostics_service_help),
                    HelpParagraph(
                        R.string.diagnostics_database,
                        subheading = true,
                        controlKey = "diagnostics_database"
                    ),
                    HelpParagraph(R.string.diagnostics_database_help),
                    HelpParagraph(
                        R.string.diagnostics_battery_optimization,
                        subheading = true,
                        controlKey = "diagnostics_battery_optimization"
                    ),
                    HelpParagraph(R.string.diagnostics_battery_optimization_help),
                    HelpParagraph(R.string.battery_optimized_message),
                    HelpParagraph(
                        R.string.diagnostics_vendor_settings,
                        subheading = true,
                        controlKey = "diagnostics_vendor_settings"
                    ),
                    HelpParagraph(R.string.diagnostics_vendor_settings_help),
                    HelpParagraph(R.string.diagnostics_vendor_availability_help),
                    HelpParagraph(
                        R.string.diagnostics_dont_kill_my_app,
                        subheading = true,
                        controlKey = "diagnostics_dont_kill_my_app"
                    ),
                    HelpParagraph(R.string.diagnostics_dont_kill_my_app_help),
                    HelpParagraph(
                        R.string.diag_start_monitor, subheading = true, controlKey = "monitor_start"
                    ),
                    HelpParagraph(R.string.diagnostics_start_help),
                    HelpParagraph(
                        R.string.diag_stop_monitor, subheading = true, controlKey = "monitor_stop"
                    ),
                    HelpParagraph(R.string.diag_stop_monitor_body),
                )

            ),
            HelpSection(
                R.string.diagnostics_device_and_logs, listOf(
                    HelpParagraph(
                        R.string.diagnostics_debug_logs,
                        subheading = true,
                        controlKey = SettingsContract.KEY_DEBUG_LOGGING
                    ),
                    HelpParagraph(R.string.diagnostics_debug_logs_help),
                    HelpParagraph(
                        R.string.diagnostics_export,
                        subheading = true,
                        controlKey = "diagnostics_export"
                    ),
                    HelpParagraph(R.string.diagnostics_help_report),
                    HelpParagraph(R.string.diag_include_debug, subheading = true),
                    HelpParagraph(R.string.diag_report_privacy),
                    HelpParagraph(
                        R.string.diagnostics_clear,
                        subheading = true,
                        controlKey = "diagnostics_clear"
                    ),
                    HelpParagraph(R.string.diagnostics_clear_help),
                )
            ),
        )

        CHARGING_TOOLS_HELP_TOPIC -> listOf(
            HelpSection(
                R.string.charging_diagnostics_title, listOf(
                    HelpParagraph(R.string.charging_diagnostics_help_overview),
                    HelpParagraph(R.string.charging_diagnostics_privileged_hint),
                    HelpParagraph(
                        R.string.charging_diagnostics_capture,
                        subheading = true,
                        controlKey = "charging_diagnostics_capture"
                    ),
                    HelpParagraph(R.string.charging_diagnostics_help_steps),
                    HelpParagraph(
                        R.string.charging_diagnostics_choose_state, subheading = true
                    ),
                    HelpParagraph(R.string.charging_diagnostics_condition_help),
                    HelpParagraph(
                        R.string.charging_diagnostics_report,
                        subheading = true,
                        controlKey = "charging_diagnostics_report"
                    ),
                    HelpParagraph(R.string.charging_diagnostics_report_help),
                    HelpParagraph(R.string.diag_include_apps, subheading = true),
                    HelpParagraph(R.string.diag_report_privacy),
                    HelpParagraph(R.string.charging_diagnostics_help_contact),
                    HelpParagraph(
                        R.string.charging_diagnostics_clear,
                        subheading = true,
                        controlKey = "charging_diagnostics_clear"
                    ),
                    HelpParagraph(R.string.charging_diagnostics_clear_help),
                )
            ),
        )

        SettingsContract.KEY_ADVANCED_INFO_HELP -> listOf(
            HelpSection(
                R.string.nav_diagnostics, listOf(
                    HelpParagraph(R.string.diag_help_overview),
                    HelpParagraph(R.string.diag_source_help),
                )
            ),
            HelpSection(
                R.string.diag_overview, listOfNotNull(
                    HelpParagraph(R.string.diagnostics_service, subheading = true),
                    HelpParagraph(R.string.diagnostics_service_help),
                    HelpParagraph(R.string.diag_heartbeat, subheading = true),
                    HelpParagraph(R.string.diagnostics_heartbeat_help),
                    HelpParagraph(R.string.diagnostics_database, subheading = true),
                    HelpParagraph(R.string.diagnostics_database_help),
                    HelpParagraph(R.string.diagnostics_notifications, subheading = true),
                    HelpParagraph(R.string.notification_channels_help),
                    if (chipVisible) HelpParagraph(
                        R.string.live_updates_notif_chan_name, subheading = true
                    ) else null,
                    if (chipVisible) HelpParagraph(R.string.settings_live_update_help) else null,
                    HelpParagraph(R.string.diagnostics_battery_optimization, subheading = true),
                    HelpParagraph(R.string.diagnostics_battery_optimization_help),
                    HelpParagraph(R.string.device_data_predictor, subheading = true),
                    HelpParagraph(R.string.diagnostics_predictor_help),
                )
            ),
            HelpSection(
                R.string.nav_battery_group, listOf(
                    HelpParagraph(R.string.diag_section_counters, subheading = true),
                    HelpParagraph(R.string.diag_help_counters),
                    HelpParagraph(R.string.diag_section_capacity, subheading = true),
                    HelpParagraph(R.string.diag_help_capacity),
                )
            ),
            HelpSection(
                R.string.diag_section_charging, listOf(
                    HelpParagraph(R.string.diag_help_charging),
                    HelpParagraph(R.string.current_prediction_explanation),
                    HelpParagraph(
                        R.string.charging_diagnostics_title,
                        subheading = true,
                        controlKey = "charging_diagnostics_capture"
                    ),
                    HelpParagraph(R.string.charging_diagnostics_help_overview),
                )
            ),
            HelpSection(
                R.string.diag_raw, listOf(
                    HelpParagraph(R.string.diag_section_service, subheading = true),
                    HelpParagraph(R.string.diag_help_service),
                    HelpParagraph(R.string.diag_section_sysfs, subheading = true),
                    HelpParagraph(R.string.diag_help_sysfs),
                    HelpParagraph(R.string.diag_section_metadata, subheading = true),
                    HelpParagraph(R.string.diag_help_metadata),
                )
            ),
            HelpSection(
                R.string.diag_additional_sources, listOf(
                    HelpParagraph(
                        R.string.pref_cat_privileged_access,
                        subheading = true,
                        controlKey = SettingsContract.KEY_USE_PRIVILEGED_ACCESS
                    ),
                    HelpParagraph(R.string.pref_use_privileged_access_help),
                    HelpParagraph(
                        R.string.diag_monitor_operation,
                        subheading = true,
                        controlKey = "background_monitoring"
                    ),
                    HelpParagraph(R.string.diagnostics_help_overview),
                )
            ),
            HelpSection(
                R.string.diag_help_troubleshooting_title, listOf(
                    HelpParagraph(R.string.diag_help_troubleshooting),
                )
            ),
            HelpSection(
                R.string.diagnostics_export, listOf(
                    HelpParagraph(R.string.diag_include_raw, subheading = true),
                    HelpParagraph(R.string.diag_report_privacy),
                ), controlKey = "diagnostics_export"
            ),
        )

        SettingsContract.KEY_ALARMS_SETTINGS -> listOf(
            HelpSection(
                R.string.alarm_settings, listOf(
                    HelpParagraph(R.string.alarm_settings_help),
                    HelpParagraph(R.string.alarms_overview, subheading = true),
                    HelpParagraph(R.string.alarms_overview_help),
                    HelpParagraph(R.string.notification_channels_help),
                    HelpParagraph(
                        R.string.alarms_add_threshold,
                        subheading = true,
                        controlKey = "alarms_add_threshold"
                    ),
                    HelpParagraph(R.string.pref_alarm_threshold_help),
                    HelpParagraph(
                        R.string.alarms_edit_threshold,
                        subheading = true,
                        controlKey = "alarms_edit_threshold"
                    ),
                    HelpParagraph(R.string.alarms_threshold_edit_help),
                )
            ),
            HelpSection(
                R.string.alarms_options_title, listOf(
                    HelpParagraph(R.string.pref_dismiss_low_battery_on_recovery, subheading = true),
                    HelpParagraph(R.string.pref_dismiss_low_battery_on_recovery_help),
                )
            ),
            HelpSection(
                null, listOf(
                    HelpParagraph(R.string.alarms_invalid_records_help),
                )
            ),
        )

        SettingsContract.KEY_ALARM_EDIT_SETTINGS -> listOf(
            HelpSection(
                R.string.alarms_overview, listOf(
                    HelpParagraph(R.string.alarms_overview_help),
                )
            ),
            HelpSection(
                R.string.pref_cat_alarm_conditions, listOf(
                    HelpParagraph(R.string.pref_cat_alarm_conditions_help),
                    HelpParagraph(
                        R.string.alarms_add_threshold,
                        subheading = true,
                        controlKey = "alarms_add_threshold"
                    ),
                    HelpParagraph(R.string.pref_alarm_threshold_help),
                    HelpParagraph(
                        R.string.alarms_edit_threshold,
                        subheading = true,
                        controlKey = "alarms_edit_threshold"
                    ),
                    HelpParagraph(R.string.alarms_threshold_edit_help),
                    HelpParagraph(R.string.notification_channels_help),
                )
            ),
        )

        else -> listOf(
            HelpSection(
                R.string.settings_overview, listOf(
                    HelpParagraph(R.string.settings_overview_help),
                )
            ),
            HelpSection(
                R.string.other_settings, listOf(
                    HelpParagraph(R.string.other_settings_help),
                ), controlKey = SettingsContract.KEY_OTHER_SETTINGS
            ),
            HelpSection(
                R.string.tab_current_info, listOf(
                    HelpParagraph(R.string.current_state_settings_help),
                ), controlKey = SettingsContract.KEY_CURRENT_STATE_SETTINGS
            ),
            HelpSection(
                R.string.time_estimates_settings, listOf(
                    HelpParagraph(R.string.time_estimates_settings_help),
                ), controlKey = SettingsContract.KEY_TIME_ESTIMATES_SETTINGS
            ),
            HelpSection(
                R.string.notification_settings, listOf(
                    HelpParagraph(R.string.notification_settings_help),
                    HelpParagraph(R.string.status_bar_icon_settings_help),
                    HelpParagraph(R.string.status_bar_chip_settings_help),
                ), controlKey = SettingsContract.KEY_NOTIFICATION_SETTINGS
            ),
            HelpSection(
                R.string.advanced_settings, listOf(
                    HelpParagraph(R.string.advanced_settings_help),
                ), controlKey = SettingsContract.KEY_ADVANCED_SETTINGS
            ),
            HelpSection(
                R.string.pref_backup_restore, listOf(
                    HelpParagraph(R.string.backup_restore_help_overview),
                ), controlKey = SettingsContract.KEY_BACKUP_RESTORE_SETTINGS
            ),
        )

    }
}
