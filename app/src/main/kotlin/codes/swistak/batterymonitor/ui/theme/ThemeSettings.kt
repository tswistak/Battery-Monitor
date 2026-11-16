/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsContract.KEY_BRIGHTNESS
import codes.swistak.batterymonitor.settings.SettingsContract.KEY_COLOR_SOURCE

internal fun colorSource(value: String?): ColorSource =
    ColorSource.entries.firstOrNull { it.name == value } ?: ColorSource.Dynamic

internal fun brightness(value: String?): Brightness =
    Brightness.entries.firstOrNull { it.name == value } ?: Brightness.System

@Composable
fun AppBatteryTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) {
        context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
    }
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in setOf(KEY_COLOR_SOURCE, KEY_BRIGHTNESS)) version++
        }
        settings.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val source = remember(version) { colorSource(settings.getString(KEY_COLOR_SOURCE, null)) }
    val brightness = remember(version) { brightness(settings.getString(KEY_BRIGHTNESS, null)) }
    BatteryTheme(source, brightness, content)
}
