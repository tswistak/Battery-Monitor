/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.settings

import android.content.SharedPreferences
import codes.swistak.batterymonitor.settings.backup.Version1SettingsImporter
import codes.swistak.batterymonitor.settings.backup.Version2SettingsImporter
import codes.swistak.batterymonitor.settings.backup.Version3SettingsImporter
import codes.swistak.batterymonitor.settings.backup.Version4SettingsImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class SettingsResetTest {
    @Test
    fun `reset removes settings from every backup version and runtime configuration without touching operational state`() {
        val backupKeys = listOf(
            Version1SettingsImporter.schema,
            Version2SettingsImporter.schema,
            Version3SettingsImporter.schema,
            Version4SettingsImporter.schema
        ).flatMap { it.keys }.toSet()
        val runtimeKeys = setOf(
            "vital_signs_content",
            "vital_signs_order",
            "chip_content",
            "chip_content_order",
            "debug_logging",
            "battery_current_multiplier_detection_pending",
            "auto_log_export_frequency",
            "auto_log_export_mode",
            "auto_log_export_format",
            "auto_log_export_directory",
            "last_auto_log_export_time",
            "next_auto_log_export_time",
            "last_log_export_time",
            "battery_current_source",
            "enable_advanced_stats"
        )
        val protected = mapOf<String, Any?>(
            "_applied_settings_migration_version" to 5,
            "service_desired_migrated_to_sp_main" to true,
            "serviceDesired" to true,
            "first_run" to false,
            "onboarding_completed" to true,
            "background_last_heartbeat_elapsed_time" to 1234L,
            "background_last_successful_log_check_elapsed_time" to 1200L,
            "log_filters_migrated_to_sp_main" to true,
            "unknown_future_setting" to setOf("keep", "unchanged")
        )
        val stored =
            (protected + (backupKeys + runtimeKeys).associateWith { "custom value" }).toMutableMap()
        val removed = mutableSetOf<String>()

        SettingsReset.reset(removingEditor(stored, removed))

        assertEquals(protected, stored)
        assertTrue(removed.containsAll(backupKeys + runtimeKeys))
        assertTrue(removed.containsAll(setOf("ui_color_source", "ui_brightness")))
    }

    @Test
    fun `reset preserves a stopped monitor and unknown values when no user configuration is stored`() {
        val protected = mapOf<String, Any?>(
            "serviceDesired" to false,
            "first_run" to false,
            "_applied_settings_migration_version" to 5,
            "unknown_string" to "original",
            "unknown_number" to 42,
            "unknown_flag" to true
        )
        val stored = protected.toMutableMap()

        SettingsReset.reset(removingEditor(stored, mutableSetOf()))

        assertEquals(protected, stored)
    }

    private fun removingEditor(
        stored: MutableMap<String, Any?>, removed: MutableSet<String>
    ): SharedPreferences.Editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)
    ) { proxy, method, arguments ->
        check(method.name == "remove") { "Reset must only queue removals, called ${method.name}" }
        val key = arguments!![0] as String
        removed += key
        stored.remove(key)
        proxy
    } as SharedPreferences.Editor
}
