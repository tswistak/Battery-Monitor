/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.settings

import android.content.res.Resources
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsControl
import codes.swistak.batterymonitor.settings.SettingsDefinitions
import java.text.Normalizer
import java.util.Locale

internal enum class SettingsCategory(
    val route: String, val title: Int, val summary: Int, val icon: Int, val legacyKey: String
) {
    General(
        "general",
        R.string.other_settings,
        R.string.settings_general_summary,
        R.drawable.ui_settings,
        SettingsContract.KEY_OTHER_SETTINGS
    ),
    Current(
        "current",
        R.string.tab_current_info,
        R.string.current_state_settings_summary,
        R.drawable.ui_battery,
        SettingsContract.KEY_CURRENT_STATE_SETTINGS
    ),
    Time(
        "time",
        R.string.time_estimates_settings,
        R.string.time_estimates_settings_summary,
        R.drawable.ui_clock,
        SettingsContract.KEY_TIME_ESTIMATES_SETTINGS
    ),
    Notification(
        "notification",
        R.string.notification_settings,
        R.string.notification_settings_summary,
        R.drawable.ui_bell,
        SettingsContract.KEY_NOTIFICATION_SETTINGS
    ),
    Advanced(
        "advanced",
        R.string.advanced_settings,
        R.string.advanced_settings_summary,
        R.drawable.ui_shield,
        SettingsContract.KEY_ADVANCED_SETTINGS
    ),
    Backup(
        "backup",
        R.string.pref_backup_restore,
        R.string.backup_restore_settings_summary,
        R.drawable.ui_storage,
        SettingsContract.KEY_BACKUP_RESTORE_SETTINGS
    );

    companion object {
        fun fromRoute(route: String?) = entries.firstOrNull { it.route == route }

        fun fromLegacy(key: String?) = entries.firstOrNull { it.legacyKey == key }
    }
}

internal data class SettingDescriptor(
    val key: String,
    val category: SettingsCategory,
    val title: String,
    val summary: String,
    val group: String,
    val aliases: List<String>,
    val control: SettingsControl = SettingsControl.Action,
    val value: Any? = null,
    val unavailable: String? = null,
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

internal data class NotificationAvailability(
    val appEnabled: Boolean,
    val mainEnabled: Boolean,
    val liveEnabled: Boolean,
)

internal object SettingCatalog {
    private data class DiagnosticEntry(val key: String, val title: Int, val summary: Int? = null)

    private val diagnosticEntries = listOf(
        DiagnosticEntry(
            "background_monitoring", R.string.diag_monitor_operation, R.string.diagnostics_summary
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
    val routes: Map<String, SettingsCategory> = SettingsCategory.entries.flatMap { category ->
        SettingsDefinitions.categories.getValue(category.legacyKey)
            .flatMap { group -> group.preferences.map { it.key to category } }
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


    private val sharedNotificationKeys = setOf(
        SettingsContract.KEY_TOP_LINE,
        SettingsContract.KEY_BOTTOM_LINE,
        SettingsContract.KEY_TIME_REMAINING_VERBOSITY,
        SettingsContract.KEY_VITAL_SIGNS_CONTENT,
    )

    fun notificationDisabledReason(
        key: String, sdkInt: Int, availability: NotificationAvailability
    ): Int? {
        if (routes[key] != SettingsCategory.Notification || key == SettingsContract.KEY_ENABLE_NOTIFS_B || key == SettingsContract.KEY_LIVE_UPDATE_SYSTEM_SETTINGS) return null
        if (!availability.appEnabled) return R.string.app_notifs_disabled_summary
        val liveEnabled = sdkInt >= 36 && availability.liveEnabled
        return when (key) {
            in chipKeys -> if (liveEnabled) null else R.string.settings_live_update_summary
            in sharedNotificationKeys -> if (availability.mainEnabled || liveEnabled) null
            else if (sdkInt >= 36) R.string.settings_dependency_disabled
            else R.string.settings_notification_disabled

            else -> if (availability.mainEnabled) null else R.string.settings_notification_disabled
        }
    }

    fun entries(
        resources: Resources,
        values: Map<String, *> = emptyMap<String, Any>(),
        sdkInt: Int,
        notification: NotificationAvailability,
        chargingTargetSummary: String? = null,
        autoExportSummary: String? = null,
    ): List<SettingDescriptor> = buildList {
        val text: (Int) -> String = resources::getString
        val logging = values[SettingsContract.KEY_ENABLE_LOGGING] as? Boolean ?: true
        val chargingTargetMode = values[SettingsContract.KEY_CHARGING_TARGET_MODE] as? String
        val chipMetrics = values[SettingsContract.KEY_CHIP_CONTENT] as? Set<*>
            ?: SettingsContract.DEFAULT_CHIP_CONTENT
        val liveDisplay = values[SettingsContract.KEY_LIVE_UPDATE_DISPLAY] as? String
            ?: text(R.string.default_live_update_display_mode)
        for (category in SettingsCategory.entries) {
            val aliases = listOf(text(category.title)) + when (category) {
                SettingsCategory.General -> listOf(text(R.string.settings_legacy_general))
                SettingsCategory.Advanced -> listOf(
                    text(R.string.pref_cat_logging), text(R.string.settings_data_permissions)
                )

                else -> emptyList()
            }
            for ((title1, preferences) in SettingsDefinitions.categories.getValue(category.legacyKey)) {
                for ((key, title, summaryResource, control) in preferences) {
                    if (!isVisible(key, sdkInt, chargingTargetMode)) continue
                    val value = values[key] ?: control.defaultValue(text)
                    val summary = when (key) {
                        SettingsContract.KEY_ENABLE_NOTIFS_B -> text(
                            when {
                                !notification.appEnabled -> R.string.app_notifs_disabled_summary
                                !notification.mainEnabled -> R.string.main_notifs_disabled_summary
                                else -> R.string.pref_manage_main_channel_summary
                            }
                        )

                        SettingsContract.KEY_CHANGE_APP_LANGUAGE -> text(R.string.currently_set_to) + Locale.getDefault().displayLanguage

                        SettingsContract.KEY_AUTO_LOG_EXPORT -> autoExportSummary
                        SettingsContract.KEY_CHARGING_TARGET_MODE -> chargingTargetSummary
                        SettingsContract.KEY_CHIP_SWITCHING_INTERVAL -> {
                            val seconds = (value as? String)?.toIntOrNull()?.coerceIn(1, 3600) ?: 5
                            val choice = control as SettingsControl.Choice
                            val index = resources.getStringArray(choice.entryValues)
                                .indexOf(seconds.toString())
                            val label = resources.getTextArray(choice.entries).getOrNull(index)
                                ?: MeasureFormat.getInstance(
                                    resources.configuration.locales[0],
                                    MeasureFormat.FormatWidth.WIDE
                                ).formatMeasures(Measure(seconds, MeasureUnit.SECOND))
                            text(R.string.currently_set_to) + label
                        }

                        SettingsContract.KEY_MAX_LOG_AGE -> if (logging) null else text(R.string.currently_disabled)

                        else -> null
                    } ?: when (control) {
                        is SettingsControl.Choice -> {
                            val index = resources.getStringArray(control.entryValues)
                                .indexOf(value as? String)
                            resources.getTextArray(control.entries).getOrNull(index)?.let {
                                text(R.string.currently_set_to) + it
                            }
                        }

                        is SettingsControl.Number -> resources.getString(
                            R.string.pref_target_level_summary,
                            ((value as? Int) ?: control.defaultValue).coerceIn(
                                control.min, control.max
                            )
                        )

                        else -> null
                    } ?: summaryResource?.let(text).orEmpty()
                    val unavailable =
                        notificationDisabledReason(key, sdkInt, notification) ?: when (key) {
                            SettingsContract.KEY_CHANGE_APP_LANGUAGE -> R.string.settings_app_language_unavailable.takeIf { sdkInt < 33 }

                            SettingsContract.KEY_MAX_LOG_AGE, SettingsContract.KEY_AUTO_LOG_EXPORT -> R.string.settings_dependency_disabled.takeUnless { logging }

                            SettingsContract.KEY_CHIP_SWITCHING_INTERVAL -> R.string.settings_dependency_disabled.takeIf { chipMetrics.size < 2 || liveDisplay == "never" }

                            else -> null
                        }
                    add(
                        SettingDescriptor(
                            key,
                            category,
                            text(title),
                            summary,
                            text(title1),
                            aliases + aliasesFor(key),
                            control,
                            value,
                            unavailable?.let(text)
                        )
                    )
                }
            }
        }
        addAll(diagnosticEntries(text))
    }

    internal fun diagnosticEntries(text: (Int) -> String): List<SettingDescriptor> =
        diagnosticEntries.map {
            val route = diagnosticRoutes.getValue(it.key)
            SettingDescriptor(
                it.key,
                SettingsCategory.Advanced,
                text(it.title),
                it.summary?.let(text).orEmpty(),
                text(if (route == "charging-tools") R.string.charging_diagnostics_title else R.string.diag_monitor_operation),
                listOf(
                    text(R.string.diagnostics),
                    text(R.string.advanced_settings),
                    text(R.string.settings_data_permissions)
                ),
                diagnosticDetail = route
            )
        }
}
