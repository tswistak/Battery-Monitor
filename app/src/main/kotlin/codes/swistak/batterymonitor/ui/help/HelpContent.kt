/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.help

import androidx.annotation.StringRes
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.ui.settings.SettingCatalog

internal data class HelpParagraph(
    @param:StringRes val textResource: Int, val subheading: Boolean = false
)

internal data class HelpSection(
    @param:StringRes val titleResource: Int?, val paragraphs: List<HelpParagraph>
)

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
                )
            ),
            HelpSection(
                R.string.help_cat_open_source, listOf(
                    HelpParagraph(R.string.help_open_source),
                )
            ),
            HelpSection(
                R.string.help_cat_acknowledgments, listOf(
                    HelpParagraph(R.string.help_acknowledgments),
                )
            ),
            HelpSection(
                R.string.help_cat_frequently_asked_questions, listOf(
                    HelpParagraph(R.string.help_frequently_asked_questions),
                )
            ),
            HelpSection(
                R.string.help_cat_contact, listOf(
                    HelpParagraph(R.string.help_contact),
                )
            ),
            HelpSection(
                R.string.help_cat_privacy, listOf(
                    HelpParagraph(R.string.help_privacy),
                )
            ),
            HelpSection(
                R.string.help_cat_no_warranty, listOf(
                    HelpParagraph(R.string.help_no_warranty),
                )
            ),
        )

        SettingsContract.KEY_NOTIFICATION_SETTINGS -> listOfNotNull(
            HelpSection(
                R.string.pref_cat_channel_settings, listOf(
                    HelpParagraph(R.string.notification_channels_help),
                )
            ),
            HelpSection(
                R.string.channel_group_name_alarms, listOf(
                    HelpParagraph(R.string.pref_dismiss_low_battery_on_recovery, subheading = true),
                    HelpParagraph(R.string.pref_dismiss_low_battery_on_recovery_summary),
                )
            ),
            HelpSection(
                R.string.pref_cat_notification_content, listOf(
                    HelpParagraph(R.string.pref_top_line_content, subheading = true),
                    HelpParagraph(R.string.pref_top_line_content_help),
                    HelpParagraph(R.string.pref_bottom_line_content, subheading = true),
                    HelpParagraph(R.string.pref_bottom_line_content_help),
                    HelpParagraph(R.string.pref_time_remaining_verbosity, subheading = true),
                    HelpParagraph(R.string.pref_time_remaining_verbosity_help),
                    HelpParagraph(R.string.pref_vital_signs_content, subheading = true),
                    HelpParagraph(R.string.pref_vital_signs_content_summary),
                    HelpParagraph(R.string.pref_expanded_notification, subheading = true),
                    HelpParagraph(R.string.pref_expanded_notification_help),
                )
            ),
            HelpSection(
                R.string.status_bar_icon_settings, listOf(
                    HelpParagraph(R.string.status_bar_icon_settings_help),
                    HelpParagraph(R.string.pref_icon_content, subheading = true),
                    HelpParagraph(R.string.pref_icon_content_help),
                    HelpParagraph(R.string.pref_show_icon_unit, subheading = true),
                    HelpParagraph(R.string.pref_show_icon_unit_help),
                    HelpParagraph(R.string.pref_indicate_charging, subheading = true),
                    HelpParagraph(R.string.pref_indicate_charging_help),
                )
            ),
            if (chipVisible) HelpSection(
                R.string.status_bar_chip_settings, listOf(
                    HelpParagraph(R.string.status_bar_chip_settings_help),
                    HelpParagraph(R.string.live_updates_notif_chan_name, subheading = true),
                    HelpParagraph(R.string.settings_live_update_summary),
                    HelpParagraph(R.string.pref_live_update_display, subheading = true),
                    HelpParagraph(R.string.pref_live_update_display_help),
                    HelpParagraph(
                        R.string.pref_live_update_keep_main_notification, subheading = true
                    ),
                    HelpParagraph(R.string.pref_live_update_keep_main_notification_help),
                    HelpParagraph(R.string.pref_expanded_live_update, subheading = true),
                    HelpParagraph(R.string.pref_expanded_live_update_help),
                    HelpParagraph(R.string.pref_chip_content, subheading = true),
                    HelpParagraph(R.string.pref_chip_content_help),
                    HelpParagraph(R.string.pref_chip_switching_interval, subheading = true),
                    HelpParagraph(R.string.pref_chip_switching_interval_help),
                    HelpParagraph(R.string.pref_chip_indicate_charging, subheading = true),
                    HelpParagraph(R.string.pref_chip_indicate_charging_help),
                )
            ) else null,
        )

        SettingsContract.KEY_CURRENT_STATE_SETTINGS -> listOf(
            HelpSection(
                R.string.remaining_charge, listOf(
                    HelpParagraph(R.string.pref_show_remaining_charge, subheading = true),
                    HelpParagraph(R.string.pref_show_remaining_charge_summary),
                )
            ),
            HelpSection(
                R.string.pref_cat_battery_current_main, listOf(
                    HelpParagraph(R.string.pref_battery_current_multiplier, subheading = true),
                    HelpParagraph(R.string.pref_battery_current_multiplier_help),
                    HelpParagraph(R.string.pref_prefer_average_battery_current, subheading = true),
                    HelpParagraph(R.string.pref_prefer_current_avg_help),
                )
            ),
        )

        SettingsContract.KEY_OTHER_SETTINGS -> listOf(
            HelpSection(
                R.string.settings_appearance, listOf(
                    HelpParagraph(R.string.settings_color_palette, subheading = true),
                    HelpParagraph(R.string.settings_color_palette_summary),
                    HelpParagraph(R.string.settings_brightness, subheading = true),
                    HelpParagraph(R.string.settings_brightness_summary),
                )
            ),
            HelpSection(
                R.string.pref_cat_language, listOf(
                    HelpParagraph(R.string.pref_change_app_language, subheading = true),
                    HelpParagraph(R.string.pref_change_app_language_help),
                    HelpParagraph(R.string.pref_temperature_unit, subheading = true),
                    HelpParagraph(R.string.pref_temperature_unit_help),
                    HelpParagraph(R.string.pref_long_duration_format, subheading = true),
                    HelpParagraph(R.string.pref_long_duration_format_help),
                )
            ),
            HelpSection(
                R.string.pref_cat_autostart, listOf(
                    HelpParagraph(R.string.pref_autostart_at_boot, subheading = true),
                    HelpParagraph(R.string.pref_autostart_at_boot_help),
                )
            ),
        )

        SettingsContract.KEY_TIME_ESTIMATES_SETTINGS -> listOf(
            HelpSection(
                R.string.pref_cat_predictor, listOf(
                    HelpParagraph(R.string.pref_prediction_type, subheading = true),
                    HelpParagraph(R.string.pref_prediction_type_help),
                )
            ),
            HelpSection(
                R.string.pref_cat_target_levels, listOfNotNull(
                    HelpParagraph(R.string.pref_charging_target_mode, subheading = true),
                    HelpParagraph(R.string.pref_charging_target_mode_help),
                    HelpParagraph(R.string.pref_charging_target_mode_support_help),
                    if (customTargetVisible) HelpParagraph(
                        R.string.pref_custom_charging_target, subheading = true
                    ) else null,
                    if (customTargetVisible) HelpParagraph(R.string.pref_custom_charging_target_help) else null,
                    HelpParagraph(R.string.pref_discharging_target, subheading = true),
                    HelpParagraph(R.string.pref_discharging_target_help),
                )
            ),
        )

        SettingsContract.KEY_ADVANCED_SETTINGS -> listOf(
            HelpSection(
                R.string.pref_cat_privileged_access, listOf(
                    HelpParagraph(R.string.pref_use_privileged_access, subheading = true),
                    HelpParagraph(R.string.pref_use_privileged_access_help),
                )
            ),
            HelpSection(
                R.string.pref_cat_logging, listOf(
                    HelpParagraph(R.string.pref_enable_logging, subheading = true),
                    HelpParagraph(R.string.pref_enable_logging_help),
                    HelpParagraph(R.string.pref_max_log_age, subheading = true),
                    HelpParagraph(R.string.pref_max_log_age_help),
                    HelpParagraph(R.string.pref_auto_log_export, subheading = true),
                    HelpParagraph(R.string.auto_log_export_help),
                    HelpParagraph(R.string.auto_log_export_format_help),
                )
            ),
            HelpSection(
                R.string.diagnostics_monitoring, listOf(
                    HelpParagraph(R.string.diagnostics_help_overview),
                )
            ),
        )

        SettingsContract.KEY_BACKUP_RESTORE_SETTINGS -> listOf(
            HelpSection(
                R.string.pref_general_backup, listOf(
                    HelpParagraph(R.string.general_backup_help),
                )
            ),
            HelpSection(
                R.string.settings_activity_subtitle, listOf(
                    HelpParagraph(R.string.settings_backup_help),
                    HelpParagraph(R.string.settings_reset_title, subheading = true),
                    HelpParagraph(R.string.settings_reset_help),
                )
            ),
            HelpSection(
                R.string.alarm_settings, listOf(
                    HelpParagraph(R.string.alarms_backup_help),
                )
            ),
            HelpSection(
                R.string.pref_backup_device_data, listOf(
                    HelpParagraph(R.string.device_specific_backup_help),
                    HelpParagraph(R.string.pref_import_logs_csv, subheading = true),
                    HelpParagraph(R.string.csv_logs_import_help),
                )
            ),
        )

        SettingsContract.KEY_DIAGNOSTICS_SETTINGS -> listOf(
            HelpSection(
                R.string.diagnostics_help_title, listOf(
                    HelpParagraph(R.string.diagnostics_help_overview),
                    HelpParagraph(R.string.diagnostics_export, subheading = true),
                    HelpParagraph(R.string.diagnostics_help_report),
                    HelpParagraph(R.string.charging_diagnostics_title, subheading = true),
                    HelpParagraph(R.string.charging_diagnostics_help_steps),
                    HelpParagraph(R.string.charging_diagnostics_help_contact),
                )
            ),
        )

        SettingsContract.KEY_ADVANCED_INFO_HELP -> listOf(
            HelpSection(
                R.string.nav_diagnostics, listOf(
                    HelpParagraph(R.string.advanced_help_overview),
                    HelpParagraph(R.string.device_data_predictor, subheading = true),
                    HelpParagraph(R.string.diagnostics_predictor_help),
                    HelpParagraph(R.string.advanced_section_counters, subheading = true),
                    HelpParagraph(R.string.advanced_help_counters),
                    HelpParagraph(R.string.advanced_section_capacity, subheading = true),
                    HelpParagraph(R.string.advanced_help_capacity),
                    HelpParagraph(R.string.advanced_section_charging, subheading = true),
                    HelpParagraph(R.string.advanced_help_charging),
                    HelpParagraph(R.string.advanced_section_service, subheading = true),
                    HelpParagraph(R.string.advanced_help_service),
                    HelpParagraph(R.string.advanced_section_sysfs, subheading = true),
                    HelpParagraph(R.string.advanced_help_sysfs),
                    HelpParagraph(R.string.advanced_section_metadata, subheading = true),
                    HelpParagraph(R.string.advanced_help_metadata),
                )
            ),
            HelpSection(
                R.string.advanced_help_troubleshooting_title, listOf(
                    HelpParagraph(R.string.advanced_help_troubleshooting),
                )
            ),
        )

        SettingsContract.KEY_ALARMS_SETTINGS -> listOf(
            HelpSection(
                R.string.alarm_settings, listOf(
                    HelpParagraph(R.string.alarm_settings_help),
                    HelpParagraph(R.string.alarms_overview, subheading = true),
                    HelpParagraph(R.string.alarms_overview_help),
                )
            ),
        )

        SettingsContract.KEY_ALARM_EDIT_SETTINGS -> listOf(
            HelpSection(
                null, listOf(
                    HelpParagraph(R.string.alarms_overview_help),
                )
            ),
            HelpSection(
                R.string.pref_cat_alarm_conditions, listOf(
                    HelpParagraph(R.string.pref_cat_alarm_conditions_help),
                    HelpParagraph(R.string.pref_alarm_threshold, subheading = true),
                    HelpParagraph(R.string.pref_alarm_threshold_help),
                    HelpParagraph(R.string.alarms_channel_help),
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
                )
            ),
            HelpSection(
                R.string.tab_current_info, listOf(
                    HelpParagraph(R.string.current_state_settings_help),
                )
            ),
            HelpSection(
                R.string.time_estimates_settings, listOf(
                    HelpParagraph(R.string.time_estimates_settings_help),
                )
            ),
            HelpSection(
                R.string.notification_settings, listOf(
                    HelpParagraph(R.string.notification_settings_help),
                    HelpParagraph(R.string.status_bar_icon_settings_help),
                    HelpParagraph(R.string.status_bar_chip_settings_help),
                )
            ),
            HelpSection(
                R.string.advanced_settings, listOf(
                    HelpParagraph(R.string.advanced_settings_help),
                )
            ),
            HelpSection(
                R.string.pref_backup_restore, listOf(
                    HelpParagraph(R.string.backup_restore_help_overview),
                )
            ),
            HelpSection(
                R.string.diagnostics, listOf(
                    HelpParagraph(R.string.advanced_help_overview),
                )
            ),
        )

    }
}
