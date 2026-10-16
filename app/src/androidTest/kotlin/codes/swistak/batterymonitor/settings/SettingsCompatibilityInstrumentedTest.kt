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

import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.settings.backup.SettingsBackup
import codes.swistak.batterymonitor.settings.backup.Version4SettingsImporter
import codes.swistak.batterymonitor.settings.migration.BatteryCurrentPreferencesMigration
import codes.swistak.batterymonitor.settings.migration.ChipContentMigration
import codes.swistak.batterymonitor.settings.migration.GlobalPrivilegedAccessMigration
import codes.swistak.batterymonitor.settings.migration.SettingsMigration
import codes.swistak.batterymonitor.settings.migration.SettingsMigrationManager
import codes.swistak.batterymonitor.settings.migration.TemperatureUnitPreferencesMigration
import codes.swistak.batterymonitor.settings.migration.VitalSignsContentMigration
import codes.swistak.batterymonitor.ui.theme.Brightness
import codes.swistak.batterymonitor.ui.theme.ColorSource
import codes.swistak.batterymonitor.ui.theme.brightness
import codes.swistak.batterymonitor.ui.theme.colorSource
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class SettingsCompatibilityInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceNames = mutableListOf<String>()
    private lateinit var source: SharedPreferences
    private lateinit var restored: SharedPreferences

    @Before
    fun setUp() {
        source = preferences()
        restored = preferences()
    }

    @After
    fun tearDown() {
        for (name in preferenceNames) context.deleteSharedPreferences(name)
    }

    @Test
    fun failed_android_commit_keeps_migrations_pending_and_retry_preserves_choices() {
        for (failedCommit in listOf(1, 2)) {
            assertTrue(source.edit().clear().apply {
                putString(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "-1000")
                putString(SettingsContract.LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER, "10")
                putBoolean(SettingsContract.KEY_DISPLAY_CURRENT_IN_NOTIFICATION, true)
                putBoolean(SettingsContract.KEY_STATUS_DURATION_IN_VITAL_SIGNS, true)
                putString(SettingsContract.KEY_CHIP_CONTENT, "switching")
                putBoolean(SettingsContract.LEGACY_KEY_CONVERT_F, true)
                putString(SettingsContract.KEY_AUTOSTART, "never")
            }.commit())
            val executed = mutableListOf<Int>()
            val migrations = listOf(
                BatteryCurrentPreferencesMigration,
                VitalSignsContentMigration,
                ChipContentMigration,
                TemperatureUnitPreferencesMigration,
                GlobalPrivilegedAccessMigration
            ).map { migration ->
                object : SettingsMigration {
                    override val version = migration.version
                    override fun migrate(
                        preferences: SharedPreferences, editor: SharedPreferences.Editor
                    ) {
                        executed += version
                        migration.migrate(preferences, editor)
                    }
                }
            }
            var commits = 0
            val failingPreferences = object : SharedPreferences by source {
                override fun edit(): SharedPreferences.Editor {
                    val delegate = source.edit()
                    return object : SharedPreferences.Editor by delegate {
                        override fun commit(): Boolean {
                            val persisted = delegate.commit()
                            commits++
                            // Android updates memory before reporting disk failure.
                            return persisted && commits != failedCommit
                        }
                    }
                }
            }

            assertFalse(SettingsMigrationManager.migrate(failingPreferences, migrations))
            assertEquals((1..failedCommit).toList(), executed)
            assertEquals(failedCommit - 1, source.getInt(MIGRATION_VERSION_KEY, 0))
            assertEquals("switching", source.getString(SettingsContract.KEY_CHIP_CONTENT, null))
            assertTrue(source.contains(SettingsContract.LEGACY_KEY_CONVERT_F))

            assertTrue(SettingsMigrationManager.migrate(failingPreferences, migrations))
            assertEquals(
                (1..failedCommit).toList() + (failedCommit..5).toList(), executed
            )
            assertEquals(5, source.getInt(MIGRATION_VERSION_KEY, 0))
            assertEquals(
                "-1000", source.getString(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, null)
            )
            assertEquals("never", source.getString(SettingsContract.KEY_AUTOSTART, null))
            assertEquals(TemperatureUnit.FAHRENHEIT, source.temperatureUnit("celsius"))
            assertEquals(
                SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT + setOf(
                    SettingsContract.VITAL_SIGN_CURRENT, SettingsContract.VITAL_SIGN_STATUS_DURATION
                ), source.getStringSet(SettingsContract.KEY_VITAL_SIGNS_CONTENT, null)
            )
            assertEquals(
                setOf(
                    SettingsContract.CHIP_CONTENT_PERCENTAGE,
                    SettingsContract.CHIP_CONTENT_TEMPERATURE
                ), source.getStringSet(SettingsContract.KEY_CHIP_CONTENT, null)
            )
            val completed = source.all.toMap()
            val completedCommits = commits
            assertTrue(SettingsMigrationManager.migrate(failingPreferences, migrations))
            assertEquals(completed, source.all)
            assertEquals(completedCommits, commits)
        }
    }

    @Test
    fun stored_preferences_export_and_restore_all_portable_effective_settings() {
        val portable = mapOf(
            SettingsContract.KEY_TEMPERATURE_UNIT to "fahrenheit",
            SettingsContract.KEY_LONG_DURATION_FORMAT to "hours_only",
            SettingsContract.KEY_AUTOSTART to "always",
            SettingsContract.KEY_SHOW_REMAINING_CHARGE to false,
            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER to "-1000",
            SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT to true,
            SettingsContract.KEY_PREDICTION_TYPE to "5",
            SettingsContract.KEY_CHARGING_TARGET_MODE to "custom",
            SettingsContract.KEY_CUSTOM_CHARGING_TARGET to 87,
            SettingsContract.KEY_DISCHARGING_TARGET to 13,
            SettingsContract.KEY_DISMISS_LOW_BATTERY_ON_RECOVERY to true,
            SettingsContract.KEY_TOP_LINE to "since",
            SettingsContract.KEY_BOTTOM_LINE to "remaining",
            SettingsContract.KEY_TIME_REMAINING_VERBOSITY to "verbose",
            SettingsContract.KEY_EXPANDED_NOTIFICATION_DETAILS to false,
            SettingsContract.KEY_ICON_CONTENT to "temperature",
            SettingsContract.KEY_SHOW_ICON_UNIT to false,
            SettingsContract.KEY_INDICATE_CHARGING to false,
            SettingsContract.KEY_LIVE_UPDATE_DISPLAY to "always",
            SettingsContract.KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION to true,
            SettingsContract.KEY_EXPANDED_LIVE_UPDATE_DETAILS to true,
            SettingsContract.KEY_CHIP_SWITCHING_INTERVAL to "10",
            SettingsContract.KEY_CHIP_INDICATE_CHARGING to false,
            SettingsContract.KEY_USE_PRIVILEGED_ACCESS to true,
            SettingsContract.KEY_ENABLE_LOGGING to false,
            SettingsContract.KEY_MAX_LOG_AGE to "-1",
            SettingsContract.KEY_COLOR_SOURCE to "BatteryBlue",
            SettingsContract.KEY_BRIGHTNESS to "TrueBlack",
            SettingsContract.KEY_VITAL_SIGNS_CONTENT to setOf(
                "charge", "current", "status_duration"
            ),
            SettingsContract.KEY_VITAL_SIGNS_ORDER to SettingsContract.ALL_VITAL_SIGNS_CONTENT.reversed()
                .joinToString(","),
            SettingsContract.KEY_CHIP_CONTENT to setOf("charge", "current"),
            SettingsContract.KEY_CHIP_CONTENT_ORDER to SettingsContract.ALL_CHIP_CONTENT.reversed()
                .joinToString(",")
        )
        val excluded = mapOf<String, Any>(
            MIGRATION_VERSION_KEY to 5,
            SettingsContract.KEY_FIRST_RUN to false,
            SettingsContract.KEY_MIGRATED_SERVICE_DESIRED to true,
            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING to true,
            SettingsContract.KEY_DEBUG_LOGGING to true,
            SettingsContract.KEY_AUTO_LOG_EXPORT_DIRECTORY to "content://old-device/tree/123",
            SettingsContract.KEY_AUTO_LOG_EXPORT_FREQUENCY to "daily",
            SettingsContract.KEY_AUTO_LOG_EXPORT_MODE to "append",
            SettingsContract.KEY_AUTO_LOG_EXPORT_FORMAT to "csv",
            SettingsContract.KEY_LAST_LOG_EXPORT_TIME to 123L,
            SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME to 456L,
            SettingsContract.KEY_NEXT_AUTO_LOG_EXPORT_TIME to 789L
        )
        write(source, portable + excluded)
        val exported = SettingsBackup.exportToJson(source).toString()
        val json = JSONObject(exported).getJSONObject("settings")
        assertEquals(SettingsBackup.SCHEMA_VERSION, SettingsBackup.getSchemaVersion(exported))
        for (key in excluded.keys) {
            assertFalse(
                "Technical, transient or SAF-bound setting must stay local: $key", json.has(key)
            )
            assertFalse(Version4SettingsImporter.schema.containsKey(key))
        }
        assertEquals("fahrenheit", json.getString(SettingsContract.KEY_TEMPERATURE_UNIT))
        assertEquals(87, json.getInt(SettingsContract.KEY_CUSTOM_CHARGING_TARGET))
        assertTrue(json.getBoolean("chip_content_charge"))
        assertFalse(json.getBoolean("chip_content_percentage"))
        assertEquals(0, json.getInt("vital_signs_status_duration_order"))

        write(restored, excluded)
        SettingsBackup.validateJson(exported)
        assertTrue(restored.edit().also { SettingsBackup.importFromJson(it, exported) }.commit())
        for ((key, value) in portable) assertEquals("Restored $key", value, restored.all[key])
        for ((key, value) in excluded - SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING) {
            assertEquals("Preserved local $key", value, restored.all[key])
        }
        assertFalse(restored.contains(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING))
        assertEquals(TemperatureUnit.FAHRENHEIT, restored.temperatureUnit("celsius"))
        assertEquals(
            LongDurationFormat.HOURS_ONLY, LongDurationFormat.fromPreference(
                restored.getString(
                    SettingsContract.KEY_LONG_DURATION_FORMAT, null
                )
            )
        )
        assertEquals(
            ColorSource.BatteryBlue,
            colorSource(restored.getString(SettingsContract.KEY_COLOR_SOURCE, null))
        )
        assertEquals(
            Brightness.TrueBlack,
            brightness(restored.getString(SettingsContract.KEY_BRIGHTNESS, null))
        )
        assertEquals(
            SettingsContract.ALL_VITAL_SIGNS_CONTENT.reversed(),
            VitalSignsOrder.parse(restored.getString(SettingsContract.KEY_VITAL_SIGNS_ORDER, null))
        )
        assertEquals(
            SettingsContract.ALL_CHIP_CONTENT.reversed(), ChipContentOrder.parse(
                restored.getString(
                    SettingsContract.KEY_CHIP_CONTENT_ORDER, null
                )
            )
        )
        for ((key, _, _, _, _, control) in SettingsDefinitions.preferences) {
            val expected = portable[key] ?: continue
            val effective = when (control) {
                is SettingsControl.Toggle -> restored.getBoolean(
                    key, control.defaultValue
                )

                is SettingsControl.Number -> restored.getInt(key, control.defaultValue)
                is SettingsControl.Choice -> restored.getString(
                    key, control.defaultValue.resolve(context::getString)
                )

                SettingsControl.Action -> restored.getStringSet(key, emptySet())
            }
            assertEquals("Effective $key", expected, effective)
        }
    }

    @Test
    fun historical_json_schemas_restore_through_the_real_parser_and_current_readers() {
        val backups = listOf(
            """{"version":1,"settings":{"convert_to_fahrenheit":true,"current_hack_multiplier":"-1000","prefer_current_avg_in_main_window":true,"chip_content":"switching","autostart":"never"}}""",
            """{"version":2,"settings":{"convert_to_fahrenheit":true,"battery_current_multiplier":"-1000","prefer_average_battery_current":true,"chip_content":"switching","autostart":"never"}}""",
            """{"version":3,"settings":{"convert_to_fahrenheit":true,"battery_current_multiplier":"-1000","prefer_average_battery_current":true,"chip_content_percentage":true,"chip_content_temperature":true,"autostart":"never"}}"""
        )
        for (backup in backups) {
            assertTrue(restored.edit().clear().commit())
            assertTrue(restored.edit().also { SettingsBackup.importFromJson(it, backup) }.commit())
            assertEquals(TemperatureUnit.FAHRENHEIT, restored.temperatureUnit("celsius"))
            assertEquals(
                "-1000", restored.getString(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, null)
            )
            assertTrue(
                restored.getBoolean(
                    SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT, false
                )
            )
            assertEquals("never", restored.getString(SettingsContract.KEY_AUTOSTART, null))
            assertEquals(
                setOf(
                    SettingsContract.CHIP_CONTENT_PERCENTAGE,
                    SettingsContract.CHIP_CONTENT_TEMPERATURE
                ), restored.getStringSet(SettingsContract.KEY_CHIP_CONTENT, null)
            )
            assertFalse(restored.contains(SettingsContract.LEGACY_KEY_CONVERT_F))
        }
    }

    private fun preferences(): SharedPreferences {
        val name = "settings-compatibility-${UUID.randomUUID()}"
        preferenceNames += name
        return context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    private fun write(preferences: SharedPreferences, values: Map<String, Any>) {
        val editor = preferences.edit()
        for ((key, value) in values) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported test preference $key")
            }
        }
        assertTrue(editor.commit())
    }

    private companion object {
        const val MIGRATION_VERSION_KEY = "_applied_settings_migration_version"
    }
}
