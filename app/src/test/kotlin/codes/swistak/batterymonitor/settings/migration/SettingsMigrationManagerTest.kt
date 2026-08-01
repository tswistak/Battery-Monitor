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
    fun `battery current migration is committed only once`() {
        val preferences = FakeSharedPreferences(
            mapOf(
                SettingsContract.LEGACY_KEY_ENABLE_CURRENT to true,
                SettingsContract.LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER to "1000",
                SettingsContract.LEGACY_KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW to true,
                SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING to true
            )
        )

        assertTrue(SettingsMigrationManager.migrate(preferences.instance))
        assertEquals(1, preferences.commitCount)
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
        assertEquals(1, preferences.commitCount)
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
                "contains" -> values.containsKey(args[0] as String)
                "getBoolean" -> values[args[0] as String] as? Boolean ?: args[1]
                "getInt" -> values[args[0] as String] as? Int ?: args[1]
                "getString" -> values[args[0] as String] as? String ?: args[1]
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
                    "putBoolean", "putInt", "putString" -> {
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
