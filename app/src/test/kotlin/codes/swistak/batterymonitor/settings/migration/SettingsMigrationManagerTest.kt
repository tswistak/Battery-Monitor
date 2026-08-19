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
package codes.swistak.batterymonitor.settings.migration

import android.content.SharedPreferences
import codes.swistak.batterymonitor.settings.SettingsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class SettingsMigrationManagerTest {
    @Test
    fun `registered settings migrations are committed only once`() {
        val preferences = FakeSharedPreferences(
            mapOf(
                SettingsContract.LEGACY_KEY_ENABLE_CURRENT to true,
                SettingsContract.LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER to "1000",
                SettingsContract.LEGACY_KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW to true,
                SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING to true
            )
        )

        assertTrue(SettingsMigrationManager.migrate(preferences.instance))
        assertEquals(4, preferences.commitCount)
        assertEquals(true, preferences.values[SettingsContract.KEY_ENABLE_BATTERY_CURRENT])
        assertEquals("1000", preferences.values[SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER])
        assertEquals(true, preferences.values[SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT])
        assertEquals(false, preferences.values[SettingsContract.KEY_USE_PRIVILEGED_BATTERY_CURRENT])
        assertEquals("2", preferences.values[SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL])
        assertFalse(
            preferences.values.containsKey(
                SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING
            )
        )
        assertFalse(preferences.values.containsKey(SettingsContract.LEGACY_KEY_ENABLE_CURRENT))
        assertFalse(
            preferences.values.containsKey(
                SettingsContract.LEGACY_KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW
            )
        )

        assertTrue(SettingsMigrationManager.migrate(preferences.instance))
        assertEquals(4, preferences.commitCount)
    }

    @Test
    fun `existing current multiplier cancels pending automatic detection`() {
        val preferences = FakeSharedPreferences(
            mapOf(
                SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER to "-10",
                SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING to true
            )
        )

        assertTrue(SettingsMigrationManager.migrate(preferences.instance))
        assertEquals("-10", preferences.values[SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER])
        assertFalse(
            preferences.values.containsKey(
                SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING
            )
        )
    }

    @Test
    fun `pending migrations execute in version order`() {
        val preferences = FakeSharedPreferences()
        val executedVersions = mutableListOf<Int>()
        val migrations = listOf(
            recordingMigration(2, executedVersions), recordingMigration(1, executedVersions)
        )

        assertTrue(SettingsMigrationManager.migrate(preferences.instance, migrations))
        assertEquals(listOf(1, 2), executedVersions)
        assertEquals(2, preferences.commitCount)

        assertTrue(SettingsMigrationManager.migrate(preferences.instance, migrations))
        assertEquals(listOf(1, 2), executedVersions)
        assertEquals(2, preferences.commitCount)
    }

    @Test
    fun `vital signs migration preserves old notification choices`() {
        val preferences = FakeSharedPreferences(
            mapOf(
                "_applied_settings_migration_version" to 1,
                SettingsContract.KEY_DISPLAY_CURRENT_IN_NOTIFICATION to true,
                SettingsContract.KEY_STATUS_DURATION_IN_VITAL_SIGNS to true,
                SettingsContract.KEY_TOP_LINE to "remaining",
                SettingsContract.KEY_BOTTOM_LINE to "since"
            )
        )

        assertTrue(SettingsMigrationManager.migrate(preferences.instance))
        assertEquals(
            setOf(
                SettingsContract.VITAL_SIGN_HEALTH,
                SettingsContract.VITAL_SIGN_TEMPERATURE,
                SettingsContract.VITAL_SIGN_VOLTAGE,
                SettingsContract.VITAL_SIGN_CURRENT,
                SettingsContract.VITAL_SIGN_STATUS_DURATION
            ), preferences.values[SettingsContract.KEY_VITAL_SIGNS_CONTENT]
        )
        assertEquals("remaining", preferences.values[SettingsContract.KEY_TOP_LINE])
        assertEquals("since", preferences.values[SettingsContract.KEY_BOTTOM_LINE])
        assertFalse(
            preferences.values.containsKey(SettingsContract.KEY_DISPLAY_CURRENT_IN_NOTIFICATION)
        )
        assertFalse(
            preferences.values.containsKey(SettingsContract.KEY_STATUS_DURATION_IN_VITAL_SIGNS)
        )
    }

    @Test
    fun `vital signs migration defaults to health temperature and voltage`() {
        assertEquals(
            SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT,
            VitalSignsContentMigration.migratedContent(
                existing = null, displayCurrent = false, displayStatusDuration = false
            )
        )
    }

    @Test
    fun `chip content migration maps percentage`() {
        assertEquals(
            setOf(SettingsContract.CHIP_CONTENT_PERCENTAGE),
            ChipContentMigration.migratedContent("percentage")
        )
    }

    @Test
    fun `chip content migration maps temperature`() {
        assertEquals(
            setOf(SettingsContract.CHIP_CONTENT_TEMPERATURE),
            ChipContentMigration.migratedContent("temperature")
        )
    }

    @Test
    fun `chip content migration maps switching to percentage and temperature`() {
        assertEquals(
            setOf(
                SettingsContract.CHIP_CONTENT_PERCENTAGE, SettingsContract.CHIP_CONTENT_TEMPERATURE
            ), ChipContentMigration.migratedContent("switching")
        )
    }

    @Test
    fun `chip content migration persists the mapped values and default order`() {
        val preferences = FakeSharedPreferences(
            mapOf(
                "_applied_settings_migration_version" to 2,
                SettingsContract.KEY_CHIP_CONTENT to "switching"
            )
        )

        assertTrue(SettingsMigrationManager.migrate(preferences.instance))
        assertEquals(
            setOf(
                SettingsContract.CHIP_CONTENT_PERCENTAGE, SettingsContract.CHIP_CONTENT_TEMPERATURE
            ), preferences.values[SettingsContract.KEY_CHIP_CONTENT]
        )
        assertEquals(
            SettingsContract.ALL_CHIP_CONTENT.joinToString(","),
            preferences.values[SettingsContract.KEY_CHIP_CONTENT_ORDER]
        )
    }

    @Test
    fun `temperature unit migration preserves Fahrenheit choice`() {
        val preferences = FakeSharedPreferences(
            mapOf(
                "_applied_settings_migration_version" to 3,
                SettingsContract.LEGACY_KEY_CONVERT_F to true
            )
        )

        assertTrue(SettingsMigrationManager.migrate(preferences.instance))
        assertEquals(
            "fahrenheit", preferences.values[SettingsContract.KEY_TEMPERATURE_UNIT]
        )
        assertFalse(preferences.values.containsKey(SettingsContract.LEGACY_KEY_CONVERT_F))
    }

    private fun recordingMigration(
        migrationVersion: Int, executedVersions: MutableList<Int>
    ): SettingsMigration = object : SettingsMigration {
        override val version: Int = migrationVersion

        override fun migrate(
            preferences: SharedPreferences, editor: SharedPreferences.Editor
        ) {
            executedVersions += version
        }
    }

    private class FakeSharedPreferences(
        initialValues: Map<String, Any> = emptyMap()
    ) : InvocationHandler {
        val values = initialValues.toMutableMap()
        var commitCount = 0

        val instance: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java), this
        ) as SharedPreferences

        override fun invoke(proxy: Any, method: Method, arguments: Array<out Any?>?): Any? {
            val args = arguments.orEmpty()
            return when (method.name) {
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args[0] as String)
                "getBoolean" -> values[args[0] as String] as? Boolean ?: args[1]
                "getInt" -> values[args[0] as String] as? Int ?: args[1]
                "getString" -> values[args[0] as String] as? String ?: args[1]
                "getStringSet" -> values[args[0] as String] as? Set<*> ?: args[1]
                "edit" -> createEditor()
                "toString" -> "FakeSharedPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                else -> error("Unsupported SharedPreferences method: ${method.name}")
            }
        }

        private fun createEditor(): SharedPreferences.Editor {
            val updates = mutableMapOf<String, Any?>()
            val handler = InvocationHandler { proxy, method, arguments ->
                val args = arguments.orEmpty()
                when (method.name) {
                    "putBoolean", "putInt", "putString", "putStringSet" -> {
                        updates[args[0] as String] = args[1]
                        proxy
                    }

                    "remove" -> {
                        updates[args[0] as String] = Removed
                        proxy
                    }

                    "commit" -> {
                        commitCount++
                        applyUpdates(updates)
                        true
                    }

                    "apply" -> {
                        applyUpdates(updates)
                        null
                    }

                    "toString" -> "FakeSharedPreferences.Editor"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args[0]
                    else -> error("Unsupported SharedPreferences.Editor method: ${method.name}")
                }
            }
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
                handler
            ) as SharedPreferences.Editor
        }

        private fun applyUpdates(updates: Map<String, Any?>) {
            updates.forEach { (key, value) ->
                if (value === Removed) values.remove(key) else values[key] = requireNotNull(value)
            }
        }

        private data object Removed
    }
}
