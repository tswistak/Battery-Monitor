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
package codes.swistak.batterymonitor.settings

import android.content.SharedPreferences
import android.os.Bundle

internal object SettingsSnapshot {
    fun capture(preferences: SharedPreferences): Bundle = Bundle().apply {
        for ((key, value) in normalizedValues(preferences.all)) {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Float -> putFloat(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is String -> putString(key, value)
                is Set<*> -> putStringArrayList(key, ArrayList(value.filterIsInstance<String>()))
            }
        }
    }

    @Suppress("DEPRECATION")
    fun apply(preferences: SharedPreferences, snapshot: Bundle): Boolean {
        val editor = preferences.edit().clear()
        for (key in snapshot.keySet()) {
            when (val value = snapshot.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is ArrayList<*> -> {
                    if (value.all { it is String }) {
                        editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }
        }
        return editor.commit()
    }

    internal fun normalizedValues(values: Map<String, *>): Map<String, Any> = buildMap {
        for ((key, value) in values) {
            when (value) {
                is Boolean, is Float, is Int, is Long, is String -> put(key, value)
                is Set<*> -> if (value.all { it is String }) {
                    put(key, value.filterIsInstance<String>().toSet())
                }
            }
        }
    }
}
