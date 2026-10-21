/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.settings

import android.content.Context
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
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
        val increment: Int = 1,
        val adjustable: Boolean = true,
        val showValue: Boolean = true
    ) : SettingsControl
}

internal data class SettingsPreferenceDefinition(
    val key: String,
    @param:StringRes val title: Int? = null,
    @param:StringRes val summary: Int? = null,
    val singleLineTitle: Boolean? = null,
    val selectable: Boolean? = null,
    val control: SettingsControl = SettingsControl.Action
)

internal data class SettingsGroup(
    @param:StringRes val title: Int,
    val key: String? = null,
    val preferences: List<SettingsPreferenceDefinition>
)

internal object SettingsDefinitions {
    val categories: Map<String, List<SettingsGroup>> = linkedMapOf(
        SettingsContract.KEY_OTHER_SETTINGS to listOf(
            SettingsGroup(
                title = R.string.pref_cat_language,
                key = SettingsContract.KEY_CHANGE_APP_LANGUAGE_HOLDER,
                preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_CHANGE_APP_LANGUAGE,
                        title = R.string.pref_change_app_language,
                        singleLineTitle = false,
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_TEMPERATURE_UNIT,
                        title = R.string.pref_temperature_unit,
                        singleLineTitle = false,
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
                        singleLineTitle = false,
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
                        singleLineTitle = false,
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
                        singleLineTitle = false,
                        control = SettingsControl.Toggle(true),
                    ),
                )
            ),
            SettingsGroup(
                title = R.string.pref_cat_battery_current_main,
                key = SettingsContract.KEY_CATEGORY_BATTERY_CURRENT_MAIN,
                preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER,
                        title = R.string.pref_battery_current_multiplier,
                        singleLineTitle = false,
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
                        singleLineTitle = false,
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
                        singleLineTitle = false,
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
                        singleLineTitle = false,
                        control = SettingsControl.Choice(
                            entries = R.array.charging_target_mode_entries,
                            entryValues = R.array.charging_target_mode_values,
                            defaultValue = SettingsTextDefault.Literal(SettingsContract.CHARGING_TARGET_MODE_AUTOMATIC),
                        ),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_CUSTOM_CHARGING_TARGET,
                        title = R.string.pref_custom_charging_target,
                        singleLineTitle = false,
                        control = SettingsControl.Number(
                            defaultValue = SettingsContract.DEFAULT_CUSTOM_CHARGING_TARGET,
                            min = 1,
                            max = 100
                        ),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_DISCHARGING_TARGET,
                        title = R.string.pref_discharging_target,
                        singleLineTitle = false,
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
                        selectable = false,
                    ),
                )
            ),
            SettingsGroup(
                title = R.string.channel_group_name_alarms, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_DISMISS_LOW_BATTERY_ON_RECOVERY,
                        title = R.string.pref_dismiss_low_battery_on_recovery,
                        summary = R.string.pref_dismiss_low_battery_on_recovery_summary,
                        singleLineTitle = false,
                        control = SettingsControl.Toggle(false),
                    ),
                )
            ),
            SettingsGroup(
                title = R.string.pref_cat_notification_content, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_TOP_LINE,
                        title = R.string.pref_top_line_content,
                        singleLineTitle = false,
                        control = SettingsControl.Choice(
                            entries = R.array.notification_line_content_entries,
                            entryValues = R.array.notification_line_content_values,
                            defaultValue = SettingsTextDefault.Resource(R.string.default_top_line_content),
                        ),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_BOTTOM_LINE,
                        title = R.string.pref_bottom_line_content,
                        singleLineTitle = false,
                        control = SettingsControl.Choice(
                            entries = R.array.notification_line_content_entries,
                            entryValues = R.array.notification_line_content_values,
                            defaultValue = SettingsTextDefault.Resource(R.string.default_bottom_line_content),
                        ),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_TIME_REMAINING_VERBOSITY,
                        title = R.string.pref_time_remaining_verbosity,
                        singleLineTitle = false,
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
                        singleLineTitle = false,
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_EXPANDED_NOTIFICATION_DETAILS,
                        title = R.string.pref_expanded_notification,
                        summary = R.string.pref_expanded_notification_summary,
                        singleLineTitle = false,
                        control = SettingsControl.Toggle(true),
                    ),
                )
            ),
            SettingsGroup(
                title = R.string.status_bar_icon_settings, preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_ICON_CONTENT,
                        title = R.string.pref_icon_content,
                        singleLineTitle = false,
                        control = SettingsControl.Choice(
                            entries = R.array.icon_content_entries,
                            entryValues = R.array.icon_content_values,
                            defaultValue = SettingsTextDefault.Resource(R.string.default_icon_content),
                        ),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_SHOW_ICON_UNIT,
                        title = R.string.pref_show_icon_unit,
                        singleLineTitle = false,
                        control = SettingsControl.Toggle(true),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_INDICATE_CHARGING,
                        title = R.string.pref_indicate_charging,
                        summary = R.string.pref_indicate_charging_summary,
                        singleLineTitle = false,
                        control = SettingsControl.Toggle(true),
                    ),
                )
            ),
            SettingsGroup(
                title = R.string.status_bar_chip_settings,
                key = SettingsContract.KEY_CATEGORY_STATUS_BAR_CHIP,
                preferences = listOf(
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_LIVE_UPDATE_SYSTEM_SETTINGS,
                        title = R.string.live_updates_notif_chan_name,
                        summary = R.string.settings_live_update_summary,
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_LIVE_UPDATE_DISPLAY,
                        title = R.string.pref_live_update_display,
                        summary = R.string.pref_live_update_display_summary,
                        singleLineTitle = false,
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
                        singleLineTitle = false,
                        control = SettingsControl.Toggle(false),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_EXPANDED_LIVE_UPDATE_DETAILS,
                        title = R.string.pref_expanded_live_update,
                        summary = R.string.pref_expanded_live_update_summary,
                        singleLineTitle = false,
                        control = SettingsControl.Toggle(false),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_CHIP_CONTENT,
                        title = R.string.pref_chip_content,
                        summary = R.string.pref_chip_content_summary,
                        singleLineTitle = false,
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_CHIP_SWITCHING_INTERVAL,
                        title = R.string.pref_chip_switching_interval,
                        summary = R.string.pref_chip_switching_interval_summary,
                        singleLineTitle = false,
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
                        singleLineTitle = false,
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
                        singleLineTitle = false,
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
                        singleLineTitle = false,
                        control = SettingsControl.Toggle(true),
                    ),
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_MAX_LOG_AGE,
                        title = R.string.pref_max_log_age,
                        singleLineTitle = false,
                        control = SettingsControl.Choice(
                            entries = R.array.max_log_age_entries,
                            entryValues = R.array.max_log_age_values,
                            defaultValue = SettingsTextDefault.Resource(R.string.default_max_log_age),
                            dialogTitle = R.string.pref_max_log_age_summary,
                        ),
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
                    SettingsPreferenceDefinition(
                        key = SettingsContract.KEY_AUTO_LOG_EXPORT,
                        title = R.string.pref_set_auto_log_export,
                        summary = R.string.pref_auto_log_export_not_set_summary,
                    ),
                )
            ),
        ),
    )

    val preferences: List<SettingsPreferenceDefinition>
        get() = categories.values.flatten().flatMap { it.preferences }

    fun defaultValues(text: (Int) -> String): Map<String, Any> = buildMap {
        for ((key, _, _, _, _, control) in preferences) {
            defaultValue(control, text)?.let { put(key, it) }
        }
    }

    fun createScreen(context: Context, manager: PreferenceManager): PreferenceScreen {
        val screen = manager.createPreferenceScreen(context)
        for ((groupTitle, groupKey, definitions) in categories.values.flatten()) {
            val category = PreferenceCategory(context).apply {
                groupKey?.let { key = it }
                setTitle(groupTitle)
            }
            screen.addPreference(category)
            for ((key, title, summary, singleLineTitle, selectable, control) in definitions) {
                val preference = when (control) {
                    SettingsControl.Action -> Preference(context)
                    is SettingsControl.Toggle -> CheckBoxPreference(context)
                    is SettingsControl.Choice -> ListPreference(context).apply {
                        setEntries(control.entries)
                        setEntryValues(control.entryValues)
                        (control.dialogTitle ?: title)?.let { setDialogTitle(it) }
                    }

                    is SettingsControl.Number -> SeekBarPreference(context).apply {
                        min = control.min
                        max = control.max
                        seekBarIncrement = control.increment
                        isAdjustable = control.adjustable
                        showSeekBarValue = control.showValue
                    }
                }
                preference.key = key
                title?.let { preference.setTitle(it) }
                summary?.let { preference.setSummary(it) }
                singleLineTitle?.let { preference.isSingleLineTitle = it }
                selectable?.let { preference.isSelectable = it }
                defaultValue(control, context::getString)?.let { preference.setDefaultValue(it) }
                category.addPreference(preference)
            }
        }
        return screen
    }

    private fun defaultValue(control: SettingsControl, text: (Int) -> String): Any? =
        when (control) {
            SettingsControl.Action -> null
            is SettingsControl.Toggle -> control.defaultValue
            is SettingsControl.Choice -> control.defaultValue.resolve(text)
            is SettingsControl.Number -> control.defaultValue
        }
}
