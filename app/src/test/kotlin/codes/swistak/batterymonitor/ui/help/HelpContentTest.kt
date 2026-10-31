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
import codes.swistak.batterymonitor.settings.SettingsDefinitions
import codes.swistak.batterymonitor.ui.settings.SettingCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpContentTest {
    @Test
    fun `settings categories resolve to contextual help instead of the fallback overview`() {
        val overview = helpSections("unknown-topic", 37, null)
        for (topic in SettingsDefinitions.categories.keys) {
            val sections = helpSections(topic, 37, SettingsContract.CHARGING_TARGET_MODE_CUSTOM)
            assertTrue(topic, sections.isNotEmpty())
            assertTrue(topic, sections != overview)
            assertTrue(topic, sections.all { it.paragraphs.isNotEmpty() })
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
    fun `advanced help introduces the monitoring and charging detail destinations`() {
        val paragraphs = helpSections(
            SettingsContract.KEY_ADVANCED_SETTINGS,
            37,
            SettingsContract.CHARGING_TARGET_MODE_AUTOMATIC
        ).flatMap { it.paragraphs }.map { it.textResource }
        assertTrue(R.string.diagnostics_help_overview in paragraphs)
        assertTrue(R.string.charging_diagnostics_help_overview in paragraphs)
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
    fun `monitoring and charging topics resolve their own guidance and report privacy help`() {
        val monitoring = helpSections(MONITORING_HELP_TOPIC, 37, null).flatMap { it.paragraphs }
            .map { it.textResource }.toSet()
        val charging = helpSections(CHARGING_TOOLS_HELP_TOPIC, 37, null).flatMap { it.paragraphs }
            .map { it.textResource }.toSet()
        assertTrue(R.string.monitoring_help_overview in monitoring)
        assertTrue(R.string.monitoring_help_overview !in charging)
        assertTrue(R.string.charging_diagnostics_help_steps in charging)
        assertTrue(R.string.charging_diagnostics_help_steps !in monitoring)
        assertTrue(R.string.diag_report_privacy in monitoring)
        assertTrue(R.string.diag_report_privacy in charging)
    }

    @Test
    fun `disabled visible settings explain Android requirements and notification dependencies`() {
        for (sdk in listOf(26, 30, 31, 32, 33, 37)) {
            val general = helpSections(
                SettingsContract.KEY_OTHER_SETTINGS, sdk, null
            ).flatMap { it.paragraphs }.map { it.textResource }.toSet()
            assertEquals(sdk < 31, R.string.settings_color_palette_unavailable in general)
            assertEquals(sdk < 33, R.string.settings_app_language_unavailable in general)
            assertEquals(sdk >= 33, R.string.pref_change_app_language_help in general)
            val monitor = helpSections(MONITORING_HELP_TOPIC, sdk, null).flatMap { it.paragraphs }
                .map { it.textResource }.toSet()
            assertEquals(sdk >= 36, R.string.settings_live_update_summary in monitor)
        }
        val notification = helpSections(
            SettingsContract.KEY_NOTIFICATION_SETTINGS, 37, null
        ).flatMap { it.paragraphs }.map { it.textResource }.toSet()
        assertTrue(R.string.settings_notification_disabled in notification)
    }

    @Test
    fun `diagnostics explains reading sources and both alarm destinations explain thresholds`() {
        val diagnostics = helpSections(SettingsContract.KEY_ADVANCED_INFO_HELP, 37, null)
        assertTrue(diagnostics.flatMap { it.paragraphs }
            .any { it.textResource == R.string.diag_source_help })
        for (topic in listOf(
            SettingsContract.KEY_ALARMS_SETTINGS, SettingsContract.KEY_ALARM_EDIT_SETTINGS
        )) {
            assertTrue(
                topic,
                helpSections(topic, 37, null).flatMap { it.paragraphs }
                    .any { it.textResource == R.string.pref_alarm_threshold_help })
        }
    }


}
