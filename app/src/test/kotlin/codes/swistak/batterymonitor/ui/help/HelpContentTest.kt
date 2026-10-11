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
}
