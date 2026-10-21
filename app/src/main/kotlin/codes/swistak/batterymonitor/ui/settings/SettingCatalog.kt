/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.settings

import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.settings.SettingsContract
import java.text.Normalizer
import java.util.Locale

internal enum class SettingsCategory(
    val route: String, val title: Int, val summary: Int, val icon: Int, val legacyTitle: Int
) {
    General(
        "general",
        R.string.other_settings,
        R.string.settings_general_summary,
        R.drawable.ui_settings,
        R.string.other_settings
    ),
    Current(
        "current",
        R.string.tab_current_info,
        R.string.current_state_settings_summary,
        R.drawable.ui_battery,
        R.string.tab_current_info
    ),
    Time(
        "time",
        R.string.time_estimates_settings,
        R.string.time_estimates_settings_summary,
        R.drawable.ui_clock,
        R.string.time_estimates_settings
    ),
    Notification(
        "notification",
        R.string.settings_notification,
        R.string.notification_settings_summary,
        R.drawable.ui_bell,
        R.string.notification_settings
    ),
    Advanced(
        "advanced",
        R.string.advanced_settings,
        R.string.advanced_settings_summary,
        R.drawable.ui_shield,
        R.string.advanced_settings
    ),
    Backup(
        "backup",
        R.string.pref_backup_restore,
        R.string.backup_restore_settings_summary,
        R.drawable.ui_storage,
        R.string.pref_backup_restore
    );

    companion object {
        fun fromRoute(route: String?) = when (route) {
            "appearance" -> General
            "history", "data" -> Advanced
            else -> entries.firstOrNull { it.route == route }
        }

        fun fromLegacy(key: String?) = when (key) {
            SettingsContract.KEY_OTHER_SETTINGS -> General
            SettingsContract.KEY_CURRENT_STATE_SETTINGS -> Current
            SettingsContract.KEY_TIME_ESTIMATES_SETTINGS -> Time
            SettingsContract.KEY_NOTIFICATION_SETTINGS -> Notification
            SettingsContract.KEY_ADVANCED_SETTINGS -> Advanced
            SettingsContract.KEY_BACKUP_RESTORE_SETTINGS -> Backup
            else -> null
        }
    }
}

internal data class SettingDescriptor(
    val key: String,
    val category: SettingsCategory,
    val title: String,
    val summary: String,
    val group: String,
    val aliases: List<String>,
    val preference: Preference? = null,
    val diagnosticDetail: String? = null
) {
    fun matches(query: String): Boolean = matchesSettingQuery(
        query, listOf(key, title, summary, group) + aliases
    )
}

internal fun matchesSettingQuery(query: String, fields: List<String>): Boolean {
    fun normalize(value: String) =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT).replace("ł", "l").replace("ß", "ss")

    val text = normalize(fields.joinToString(" "))
    return normalize(query).split(Regex("\\s+")).filter(String::isNotEmpty).all { it in text }
}

internal object SettingCatalog {
    private data class DiagnosticEntry(val key: String, val title: Int, val summary: Int? = null)

    private val diagnosticEntries = listOf(
        DiagnosticEntry(
            "background_monitoring", R.string.diagnostics_monitoring, R.string.diagnostics_summary
        ),
        DiagnosticEntry(
            SettingsContract.KEY_DEBUG_LOGGING,
            R.string.diagnostics_debug_logs,
            R.string.diagnostics_debug_logs_warning
        ),
        DiagnosticEntry(
            "diagnostics_battery_optimization", R.string.diagnostics_battery_optimization
        ),
        DiagnosticEntry(
            "diagnostics_vendor_settings",
            R.string.diagnostics_vendor_settings,
            R.string.diagnostics_vendor_settings_summary
        ),
        DiagnosticEntry(
            "diagnostics_dont_kill_my_app",
            R.string.diagnostics_dont_kill_my_app,
            R.string.diagnostics_dont_kill_my_app_summary
        ),
        DiagnosticEntry("diagnostics_notifications", R.string.diagnostics_notifications),
        DiagnosticEntry("diagnostics_service", R.string.diagnostics_service),
        DiagnosticEntry("diagnostics_database", R.string.diagnostics_database),
        DiagnosticEntry("diagnostics_root", R.string.diagnostics_root),
        DiagnosticEntry("diagnostics_shizuku", R.string.diagnostics_shizuku),
        DiagnosticEntry(
            "diagnostics_export", R.string.diagnostics_export, R.string.diagnostics_export_summary
        ),
        DiagnosticEntry(
            "diagnostics_clear", R.string.diagnostics_clear, R.string.diagnostics_clear_summary
        ),
        DiagnosticEntry("monitor_start", R.string.diag_start_monitor),
        DiagnosticEntry("monitor_stop", R.string.diag_stop_monitor),
        DiagnosticEntry(
            "charging_diagnostics_capture",
            R.string.charging_diagnostics_capture,
            R.string.charging_diagnostics_capture_summary
        ),
        DiagnosticEntry(
            "charging_diagnostics_report",
            R.string.charging_diagnostics_report,
            R.string.charging_diagnostics_report_summary
        ),
        DiagnosticEntry(
            "charging_diagnostics_clear",
            R.string.charging_diagnostics_clear,
            R.string.charging_diagnostics_clear_summary
        )
    )
    val diagnosticRoutes: Map<String, String> = diagnosticEntries.associate {
        it.key to if (it.key.startsWith("charging_diagnostics_")) "charging-tools" else "monitor"
    }
    private val groups = linkedMapOf(
        SettingsCategory.General to listOf(
            SettingsContract.KEY_CHANGE_APP_LANGUAGE,
            SettingsContract.KEY_TEMPERATURE_UNIT,
            SettingsContract.KEY_LONG_DURATION_FORMAT,
            SettingsContract.KEY_AUTOSTART,
            SettingsContract.KEY_COLOR_SOURCE,
            SettingsContract.KEY_BRIGHTNESS
        ), SettingsCategory.Current to listOf(
            SettingsContract.KEY_SHOW_REMAINING_CHARGE,
            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER,
            SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT
        ), SettingsCategory.Time to listOf(
            SettingsContract.KEY_PREDICTION_TYPE,
            SettingsContract.KEY_CHARGING_TARGET_MODE,
            SettingsContract.KEY_CUSTOM_CHARGING_TARGET,
            SettingsContract.KEY_DISCHARGING_TARGET
        ), SettingsCategory.Notification to listOf(
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
            SettingsContract.KEY_CHIP_INDICATE_CHARGING
        ), SettingsCategory.Advanced to listOf(
            SettingsContract.KEY_USE_PRIVILEGED_ACCESS,
            SettingsContract.KEY_ENABLE_LOGGING,
            SettingsContract.KEY_MAX_LOG_AGE,
            SettingsContract.KEY_AUTO_LOG_EXPORT
        ), SettingsCategory.Backup to listOf(
            SettingsContract.KEY_EXPORT_GENERAL_BACKUP,
            SettingsContract.KEY_IMPORT_GENERAL_BACKUP,
            SettingsContract.KEY_EXPORT_SETTINGS,
            SettingsContract.KEY_IMPORT_SETTINGS,
            SettingsContract.KEY_EXPORT_ALARMS,
            SettingsContract.KEY_IMPORT_ALARMS,
            SettingsContract.KEY_EXPORT_DEVICE_DATA,
            SettingsContract.KEY_IMPORT_DEVICE_DATA,
            SettingsContract.KEY_IMPORT_LOGS_CSV,
            SettingsContract.KEY_RESET_DEFAULT_SETTINGS
        )
    )
    val routes: Map<String, SettingsCategory> = groups.flatMap { (category, keys) ->
        keys.map { it to category }
    }.toMap() + diagnosticRoutes.keys.associateWith { SettingsCategory.Advanced }

    private val chipKeys = setOf(
        SettingsContract.KEY_LIVE_UPDATE_SYSTEM_SETTINGS,
        SettingsContract.KEY_LIVE_UPDATE_DISPLAY,
        SettingsContract.KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION,
        SettingsContract.KEY_EXPANDED_LIVE_UPDATE_DETAILS,
        SettingsContract.KEY_CHIP_CONTENT,
        SettingsContract.KEY_CHIP_SWITCHING_INTERVAL,
        SettingsContract.KEY_CHIP_INDICATE_CHARGING
    )

    internal fun isVisible(key: String, sdkInt: Int, chargingTargetMode: String?): Boolean =
        when (key) {
            SettingsContract.KEY_CUSTOM_CHARGING_TARGET -> chargingTargetMode == SettingsContract.CHARGING_TARGET_MODE_CUSTOM

            in chipKeys -> sdkInt >= 36
            else -> true
        }

    internal fun aliasesFor(key: String): List<String> =
        if (key == SettingsContract.KEY_LIVE_UPDATE_SYSTEM_SETTINGS) listOf("diagnostics_live_updates") else emptyList()


    fun entries(screen: PreferenceGroup, text: (Int) -> String): List<SettingDescriptor> {
        val result = mutableListOf(
            SettingDescriptor(
                SettingsContract.KEY_COLOR_SOURCE,
                SettingsCategory.General,
                text(R.string.settings_color_palette),
                text(R.string.settings_color_palette_summary),
                text(R.string.settings_appearance),
                listOf(text(R.string.settings_appearance), text(SettingsCategory.General.title))
            ), SettingDescriptor(
                SettingsContract.KEY_BRIGHTNESS,
                SettingsCategory.General,
                text(R.string.settings_brightness),
                text(R.string.settings_brightness_summary),
                text(R.string.settings_appearance),
                listOf(text(R.string.settings_appearance), text(SettingsCategory.General.title))
            )
        )

        fun visit(group: PreferenceGroup, heading: String) {
            for (index in 0 until group.preferenceCount) {
                val preference = group.getPreference(index)
                if (preference is PreferenceGroup) visit(
                    preference, preference.title?.toString() ?: heading
                )
                else {
                    val key = preference.key ?: continue
                    val category = routes[key] ?: continue
                    result += SettingDescriptor(
                        key, category, preference.title?.toString().orEmpty().ifBlank {
                            if (key == SettingsContract.KEY_ENABLE_NOTIFS_B) text(R.string.pref_cat_channel_settings) else key
                        }, preference.summary?.toString().orEmpty(), if (key in setOf(
                                SettingsContract.KEY_ENABLE_LOGGING,
                                SettingsContract.KEY_MAX_LOG_AGE,
                                SettingsContract.KEY_AUTO_LOG_EXPORT
                            )
                        ) text(R.string.settings_history_export) else heading, listOf(
                            text(category.title), text(category.legacyTitle)
                        ) + (when (category) {
                            SettingsCategory.General -> listOf(text(R.string.settings_legacy_general))
                            SettingsCategory.Advanced -> listOf(
                                text(R.string.settings_history_export),
                                text(R.string.settings_data_permissions)
                            )

                            else -> emptyList()
                        }) + aliasesFor(key), preference
                    )
                }
            }
        }
        visit(screen, "")
        result += diagnosticEntries(text)
        return result
    }

    internal fun diagnosticEntries(text: (Int) -> String): List<SettingDescriptor> =
        diagnosticEntries.map {
            val route = diagnosticRoutes.getValue(it.key)
            SettingDescriptor(
                it.key,
                SettingsCategory.Advanced,
                text(it.title),
                it.summary?.let(text).orEmpty(),
                text(if (route == "charging-tools") R.string.charging_diagnostics_title else R.string.diagnostics_monitoring),
                listOf(
                    text(R.string.diagnostics),
                    text(R.string.advanced_settings),
                    text(R.string.settings_data_permissions)
                ),
                diagnosticDetail = route
            )
        }
}
