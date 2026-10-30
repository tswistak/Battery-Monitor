/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.help

import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.ui.settings.SettingCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpContentTest {
    @Test
    fun `settings category help starts with its controls and omits category summaries`() {
        val categories = listOf(
            Triple(
                SettingsContract.KEY_OTHER_SETTINGS,
                R.string.other_settings_help,
                R.string.settings_appearance
            ), Triple(
                SettingsContract.KEY_CURRENT_STATE_SETTINGS,
                R.string.current_state_settings_help,
                R.string.remaining_charge
            ), Triple(
                SettingsContract.KEY_TIME_ESTIMATES_SETTINGS,
                R.string.time_estimates_settings_help,
                R.string.pref_cat_predictor
            ), Triple(
                SettingsContract.KEY_ADVANCED_SETTINGS,
                R.string.advanced_settings_help,
                R.string.pref_cat_privileged_access
            ), Triple(
                SettingsContract.KEY_BACKUP_RESTORE_SETTINGS,
                R.string.backup_restore_help_overview,
                R.string.pref_general_backup
            ), Triple(
                SettingsContract.KEY_NOTIFICATION_SETTINGS,
                R.string.notification_settings_help,
                R.string.pref_cat_channel_settings
            )
        )
        for ((topic, summary, firstGroup) in categories) {
            val sections = helpSections(topic, 37, SettingsContract.CHARGING_TARGET_MODE_CUSTOM)
            assertEquals(topic, firstGroup, sections.first().titleResource)
            assertTrue(
                topic,
                sections.flatMap { it.paragraphs }.none { it.textResource == summary })
        }
    }

    @Test
    fun `custom charging target help follows the selected charging target mode`() {
        val customTexts =
            setOf(R.string.pref_custom_charging_target, R.string.pref_custom_charging_target_help)
        for (mode in listOf(
            null,
            SettingsContract.CHARGING_TARGET_MODE_AUTOMATIC,
            "100",
            SettingsContract.CHARGING_TARGET_MODE_CUSTOM
        )) {
            val paragraphs = helpSections(
                SettingsContract.KEY_TIME_ESTIMATES_SETTINGS, 37, mode
            ).flatMap { it.paragraphs }.map { it.textResource }.toSet()
            val expected =
                if (mode == SettingsContract.CHARGING_TARGET_MODE_CUSTOM) customTexts else emptySet()
            assertEquals("Charging target mode $mode", expected, paragraphs.intersect(customTexts))
            assertTrue(paragraphs.contains(R.string.pref_discharging_target))
        }
    }

    @Test
    fun `status bar chip help is available from Android 16`() {
        for (sdkInt in listOf(35, 36)) {
            val sections = helpSections(
                SettingsContract.KEY_NOTIFICATION_SETTINGS,
                sdkInt,
                SettingsContract.CHARGING_TARGET_MODE_AUTOMATIC
            )
            assertEquals(
                sdkInt >= 36,
                sections.any { it.titleResource == R.string.status_bar_chip_settings })
            assertEquals(
                sdkInt >= 36,
                sections.flatMap { it.paragraphs }
                    .any { it.textResource == R.string.pref_chip_content })
            assertTrue(sections.any { it.titleResource == R.string.status_bar_icon_settings })
        }
    }

    @Test
    fun `general help retains the dynamic version and unknown topics open settings overview`() {
        val general = helpSections(null, 37, SettingsContract.CHARGING_TARGET_MODE_AUTOMATIC)
        val version = general.first()
        assertEquals(R.string.help_cat_version, version.titleResource)
        assertEquals(R.string.nav_help_version, version.paragraphs.single().textResource)

        val overview = helpSections("main", 37, SettingsContract.CHARGING_TARGET_MODE_AUTOMATIC)
        assertEquals(R.string.settings_overview, overview.first().titleResource)
        assertEquals(
            overview,
            helpSections("unknown-topic", 37, SettingsContract.CHARGING_TARGET_MODE_AUTOMATIC)
        )
    }

    @Test
    fun `advanced help covers both detail destinations and retained advanced controls`() {
        val sections = helpSections(
            SettingsContract.KEY_ADVANCED_SETTINGS,
            37,
            SettingsContract.CHARGING_TARGET_MODE_AUTOMATIC
        )
        val paragraphs = sections.flatMap { it.paragraphs }.map { it.textResource }.toSet()
        assertTrue(sections.any {
            it.titleResource == R.string.diagnostics_monitoring && it.paragraphs.any { paragraph -> paragraph.textResource == R.string.diagnostics_help_overview }
        })
        assertTrue(sections.any {
            it.titleResource == R.string.charging_diagnostics_title && it.paragraphs.any { paragraph -> paragraph.textResource == R.string.charging_diagnostics_help_overview }
        })
        assertTrue(
            paragraphs.containsAll(
                setOf(
                    R.string.pref_use_privileged_access_help,
                    R.string.pref_enable_logging_help,
                    R.string.pref_max_log_age_help,
                    R.string.auto_log_export_help,
                    R.string.auto_log_export_format_help
                )
            )
        )
        assertTrue(R.string.charging_diagnostics_help_steps !in paragraphs)
    }

    @Test
    fun `detail help follows the destination including every settings search highlight`() {
        assertEquals(SettingsContract.KEY_ADVANCED_INFO_HELP, diagnosticDetailHelpTopic(null))
        assertEquals(MONITORING_HELP_TOPIC, diagnosticDetailHelpTopic("monitor"))
        assertEquals(CHARGING_TOOLS_HELP_TOPIC, diagnosticDetailHelpTopic("charging-tools"))
        for ((key, route) in SettingCatalog.diagnosticRoutes) {
            val expected =
                if (route == "charging-tools") CHARGING_TOOLS_HELP_TOPIC else MONITORING_HELP_TOPIC
            assertEquals(key, expected, diagnosticDetailHelpTopic("$route:$key"))
            assertEquals(
                key, diagnosticDetailHelpTopic(route), diagnosticDetailHelpTopic("$route:$key")
            )
        }
        assertEquals(
            helpSections(MONITORING_HELP_TOPIC, 37, null),
            helpSections(SettingsContract.KEY_DIAGNOSTICS_SETTINGS, 37, null)
        )
    }

    @Test
    fun `monitoring and OEM help explain their own actions and report options`() {
        val monitoring = helpSections(MONITORING_HELP_TOPIC, 37, null).flatMap { it.paragraphs }
            .map { it.textResource }.toSet()
        val charging = helpSections(CHARGING_TOOLS_HELP_TOPIC, 37, null).flatMap { it.paragraphs }
            .map { it.textResource }.toSet()
        val monitorOnly = setOf(
            R.string.monitoring_help_overview,
            R.string.diag_stop_monitor_body,
            R.string.battery_optimized_message,
            R.string.diagnostics_vendor_settings_summary,
            R.string.diagnostics_dont_kill_my_app_summary,
            R.string.diagnostics_debug_logs_warning,
            R.string.diagnostics_help_report,
            R.string.diag_include_debug,
            R.string.diagnostics_clear_summary
        )
        val chargingOnly = setOf(
            R.string.charging_diagnostics_privileged_hint,
            R.string.charging_diagnostics_help_steps,
            R.string.charging_diagnostics_report_summary,
            R.string.diag_include_apps,
            R.string.charging_diagnostics_help_contact,
            R.string.charging_diagnostics_clear_summary
        )
        assertTrue(monitoring.containsAll(monitorOnly))
        assertTrue(charging.containsAll(chargingOnly))
        assertTrue(monitoring.intersect(chargingOnly).isEmpty())
        assertTrue(charging.intersect(monitorOnly).isEmpty())
        assertTrue(R.string.diag_report_privacy in monitoring)
        assertTrue(R.string.diag_report_privacy in charging)
    }

    @Test
    fun `disabled visible settings explain Android requirements and notification dependencies`() {
        for (sdk in listOf(26, 30, 31, 32, 33, 37)) {
            val general = helpSections(
                SettingsContract.KEY_OTHER_SETTINGS,
                sdk,
                null
            ).flatMap { it.paragraphs }.map { it.textResource }.toSet()
            assertEquals(sdk < 31, R.string.settings_color_palette_unavailable in general)
            assertEquals(sdk < 33, R.string.settings_app_language_unavailable in general)
            assertEquals(sdk >= 33, R.string.pref_change_app_language_help in general)
            val monitor = helpSections(MONITORING_HELP_TOPIC, sdk, null).flatMap { it.paragraphs }
                .map { it.textResource }.toSet()
            assertEquals(sdk >= 36, R.string.settings_live_update_summary in monitor)
        }
        val notification = helpSections(
            SettingsContract.KEY_NOTIFICATION_SETTINGS,
            37,
            null
        ).flatMap { it.paragraphs }.map { it.textResource }.toSet()
        assertTrue(R.string.settings_notification_disabled in notification)
    }

    @Test
    fun `diagnostics groups data by tabs and alarm help covers the reachable threshold editor`() {
        val diagnostics = helpSections(SettingsContract.KEY_ADVANCED_INFO_HELP, 37, null)
        assertTrue(diagnostics.first().paragraphs.any { it.textResource == R.string.diag_source_help })
        assertTrue(
            diagnostics.map { it.titleResource }.containsAll(
                setOf(
                    R.string.diag_overview,
                    R.string.nav_battery_group,
                    R.string.diag_section_charging,
                    R.string.diag_raw
                )
            )
        )
        val raw = diagnostics.single { it.titleResource == R.string.diag_raw }
        assertTrue(
            raw.paragraphs.map { it.textResource }.containsAll(
                setOf(
                    R.string.diag_help_service,
                    R.string.diag_help_sysfs,
                    R.string.diag_help_metadata
                )
            )
        )
        val report = diagnostics.single { it.titleResource == R.string.diagnostics_export }
        assertTrue(
            report.paragraphs.map { it.textResource }.containsAll(
                setOf(
                    R.string.diag_include_raw, R.string.diag_report_privacy
                )
            )
        )
        assertTrue(raw.paragraphs.none { it.textResource == R.string.diagnostics_export })
        val alarms = helpSections(SettingsContract.KEY_ALARMS_SETTINGS, 37, null)
        assertTrue(alarms.flatMap { it.paragraphs }
            .any { it.textResource == R.string.pref_alarm_threshold_help })
        val editor = helpSections(SettingsContract.KEY_ALARM_EDIT_SETTINGS, 37, null)
        assertEquals(R.string.alarms_overview, editor.first().titleResource)
        assertTrue(editor.flatMap { it.paragraphs }
            .none { it.textResource == R.string.alarms_channel_help })
    }

}
