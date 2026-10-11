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

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

@SuppressLint("UseKtx")
internal class WidgetConfigurationStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    )

    fun load(widgetId: Int, family: WidgetFamily): WidgetConfiguration {
        if (widgetId <= 0) return WidgetConfiguration.defaults(family)
        return runCatching { preferences.getString(key(widgetId), null) }.getOrNull()
            ?.let(WidgetConfiguration::decode) ?: WidgetConfiguration.defaults(family)
    }

    fun save(widgetId: Int, configuration: WidgetConfiguration): Boolean {
        return !(widgetId <= 0 || !configuration.isValid()) && preferences.edit()
            .putString(key(widgetId), configuration.encode()).commit()
    }

    fun delete(widgetIds: IntArray): Boolean {
        val editor = preferences.edit()
        widgetIds.filter { it > 0 }.forEach { editor.remove(key(it)) }
        return editor.commit()
    }

    fun remap(oldIds: IntArray, newIds: IntArray): Boolean {
        if (oldIds.size != newIds.size || oldIds.any { it <= 0 } || newIds.any { it <= 0 } || oldIds.distinct().size != oldIds.size || newIds.distinct().size != newIds.size) return false
        val records = oldIds.map { preferences.getString(key(it), null) }
        val editor = preferences.edit()
        oldIds.forEach { editor.remove(key(it)) }
        newIds.forEachIndexed { index, newId ->
            records[index]?.let { editor.putString(key(newId), it) }
        }
        return editor.commit()
    }

    companion object {
        const val FILE_NAME = "widget_configuration"
        private const val KEY_PREFIX = "widget_config_v1_"
        private fun key(widgetId: Int): String = "$KEY_PREFIX$widgetId"
    }
}
