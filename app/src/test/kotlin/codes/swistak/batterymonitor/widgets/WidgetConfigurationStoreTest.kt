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
package codes.swistak.batterymonitor.widgets

import android.content.SharedPreferences
import codes.swistak.batterymonitor.ui.theme.Brightness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class WidgetConfigurationStoreTest {
    @Test
    fun `saving and deleting one instance preserve all other widget configurations`() {
        val preferences = TestPreferences()
        val store = WidgetConfigurationStore(preferences.preferences)
        val first =
            WidgetConfiguration.defaults(WidgetFamily.STATUS).copy(backgroundOpacityPercent = 35)
        val second = WidgetConfiguration.defaults(WidgetFamily.MEASUREMENTS)
            .copy(brightness = Brightness.Dark)
        assertTrue(store.save(17, first))
        assertTrue(store.save(21, second))
        assertEquals(first, store.load(17, WidgetFamily.STATUS))
        assertEquals(second, store.load(21, WidgetFamily.MEASUREMENTS))
        assertTrue(store.delete(intArrayOf(17)))
        assertEquals(
            WidgetConfiguration.defaults(WidgetFamily.STATUS), store.load(17, WidgetFamily.STATUS)
        )
        assertEquals(second, store.load(21, WidgetFamily.MEASUREMENTS))
    }

    @Test
    fun `restore moves every configuration in one commit and removes only remapped source IDs`() {
        val preferences = TestPreferences()
        val store = WidgetConfigurationStore(preferences.preferences)
        val first = WidgetConfiguration().copy(backgroundOpacityPercent = 35)
        val second = WidgetConfiguration().copy(backgroundOpacityPercent = 75)
        val unaffected = WidgetConfiguration().copy(backgroundOpacityPercent = 90)
        store.save(17, first)
        store.save(21, second)
        store.save(40, unaffected)
        val commits = preferences.commits

        assertTrue(store.remap(intArrayOf(17, 21), intArrayOf(51, 53)))

        assertEquals(commits + 1, preferences.commits)
        assertEquals(first, store.load(51, WidgetFamily.STATUS))
        assertEquals(second, store.load(53, WidgetFamily.STATUS))
        assertEquals(unaffected, store.load(40, WidgetFamily.STATUS))
        assertEquals(WidgetConfiguration(), store.load(17, WidgetFamily.STATUS))
        assertEquals(WidgetConfiguration(), store.load(21, WidgetFamily.STATUS))
    }

    @Test
    fun `restore reads all sources before writing when a launcher reuses old widget IDs`() {
        val preferences = TestPreferences()
        val store = WidgetConfigurationStore(preferences.preferences)
        val first = WidgetConfiguration().copy(backgroundOpacityPercent = 35)
        val second = WidgetConfiguration().copy(backgroundOpacityPercent = 75)
        store.save(17, first)
        store.save(21, second)

        assertTrue(store.remap(intArrayOf(17, 21), intArrayOf(21, 17)))

        assertEquals(first, store.load(21, WidgetFamily.STATUS))
        assertEquals(second, store.load(17, WidgetFamily.STATUS))
    }

    @Test
    fun `invalid saves and ambiguous restore mappings never write preferences`() {
        val preferences = TestPreferences()
        val store = WidgetConfigurationStore(preferences.preferences)
        assertFalse(store.save(0, WidgetConfiguration()))
        assertFalse(store.save(-1, WidgetConfiguration()))
        assertFalse(store.save(1, WidgetConfiguration(backgroundOpacityPercent = 101)))
        assertFalse(store.remap(intArrayOf(1), intArrayOf(2, 3)))
        assertFalse(store.remap(intArrayOf(1, 1), intArrayOf(2, 3)))
        assertFalse(store.remap(intArrayOf(1, 2), intArrayOf(3, 3)))
        assertFalse(store.remap(intArrayOf(1), intArrayOf(0)))
        assertEquals(0, preferences.commits)
        assertTrue(preferences.stored.isEmpty())
    }

    @Test
    fun `unreadable configurations use family defaults without overwriting stored data`() {
        val preferences = TestPreferences()
        preferences.stored["widget_config_v1_17"] = "future record"
        preferences.stored["widget_config_v1_21"] = 42
        val store = WidgetConfigurationStore(preferences.preferences)
        assertEquals(
            WidgetConfiguration.defaults(WidgetFamily.TREND), store.load(17, WidgetFamily.TREND)
        )
        assertEquals(
            WidgetConfiguration.defaults(WidgetFamily.MEASUREMENTS),
            store.load(21, WidgetFamily.MEASUREMENTS)
        )
        assertEquals(0, preferences.commits)
        assertEquals("future record", preferences.stored["widget_config_v1_17"])
        assertEquals(42, preferences.stored["widget_config_v1_21"])
    }

    @Test
    fun `save and restore report a failed persistence commit`() {
        val preferences = TestPreferences(commitSucceeded = false)
        val store = WidgetConfigurationStore(preferences.preferences)
        assertFalse(store.save(17, WidgetConfiguration()))
        assertFalse(store.remap(intArrayOf(17), intArrayOf(21)))
    }

    private class TestPreferences(private val commitSucceeded: Boolean = true) {
        val stored = mutableMapOf<String, Any>()
        var commits = 0
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, arguments ->
            when (method.name) {
                "getString" -> stored[arguments!![0] as String] as String? ?: arguments[1]
                "edit" -> editor()
                else -> error("Unexpected preference method ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, String?>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { proxy, method, arguments ->
                when (method.name) {
                    "putString" -> {
                        pending[arguments!![0] as String] = arguments[1] as String?; proxy
                    }

                    "remove" -> {
                        pending[arguments!![0] as String] = null; proxy
                    }

                    "commit" -> {
                        commits++
                        if (commitSucceeded) pending.forEach { (key, value) ->
                            if (value == null) stored.remove(key) else stored[key] = value
                        }
                        commitSucceeded
                    }

                    else -> error("Unexpected editor method ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
