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
package codes.swistak.batterymonitor

internal object SettingsKeys {
    const val KEY_ENABLE_LOGGING: String = "enable_logging"
    const val KEY_MAX_LOG_AGE: String = "max_log_age"
    const val KEY_ICON_CONTENT: String = "icon_content"
    const val KEY_SHOW_ICON_UNIT: String = "show_icon_unit"
    const val KEY_CONVERT_F: String = "convert_to_fahrenheit"
    const val KEY_NOTIFY_STATUS_DURATION: String = "notify_status_duration"
    const val KEY_AUTOSTART: String = "autostart"
    const val KEY_PREDICTION_TYPE: String = "prediction_type"
    const val KEY_STATUS_DUR_EST: String = "status_dur_est"
    const val KEY_INDICATE_CHARGING: String = "indicate_charging"
    const val KEY_CHIP_CONTENT: String = "chip_content"
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
    const val KEY_ENABLE_BATTERY_CURRENT: String = "enable_battery_current"
    const val KEY_USE_PRIVILEGED_BATTERY_CURRENT: String = "use_privileged_battery_current"
    const val KEY_BATTERY_CURRENT_MULTIPLIER: String = "battery_current_multiplier"
    const val KEY_BATTERY_CURRENT_REFRESH_INTERVAL: String = "battery_current_refresh_interval"
    const val KEY_DISPLAY_CURRENT_IN_NOTIFICATION: String = "display_current_in_notification"
    const val KEY_PREFER_AVERAGE_BATTERY_CURRENT: String = "prefer_average_battery_current"

    const val KEY_UI_COLOR: String = "ui_color"
    const val KEY_ENABLE_ADVANCED_STATS: String = "enable_advanced_stats"

    const val LEGACY_KEY_ENABLE_CURRENT = "enable_current_hack"
    const val LEGACY_KEY_PREFER_FILE_SYSTEM = "current_hack_prefer_fs"
    const val LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER = "current_hack_multiplier"
    const val LEGACY_KEY_CURRENT_SOURCE = "battery_current_source"
    const val LEGACY_KEY_DISPLAY_CURRENT_IN_VITAL_STATS = "display_current_in_vital_stats"
    const val LEGACY_KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS = "prefer_current_avg_in_vital_stats"
    const val LEGACY_KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW = "display_current_in_main_window"
    const val LEGACY_KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW = "prefer_current_avg_in_main_window"
    const val LEGACY_KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW = "auto_refresh_current_in_main_window"
}
