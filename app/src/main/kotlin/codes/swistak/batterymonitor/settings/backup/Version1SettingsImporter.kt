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
package codes.swistak.batterymonitor.settings.backup

import android.content.SharedPreferences
import codes.swistak.batterymonitor.settings.SettingsContract


internal object Version1SettingsImporter : SettingsImporter {
    const val VERSION = 1

    override val schema: Map<String, Class<*>> = buildMap {
        put(SettingsContract.KEY_ENABLE_LOGGING, Boolean::class.java)
        put(SettingsContract.KEY_MAX_LOG_AGE, String::class.java)
        put(SettingsContract.KEY_ICON_CONTENT, String::class.java)
        put(SettingsContract.KEY_SHOW_ICON_UNIT, Boolean::class.java)
        put(SettingsContract.KEY_CONVERT_F, Boolean::class.java)
        put(SettingsContract.KEY_NOTIFY_STATUS_DURATION, Boolean::class.java)
        put(SettingsContract.KEY_AUTOSTART, String::class.java)
        put(SettingsContract.KEY_PREDICTION_TYPE, String::class.java)
        put(SettingsContract.KEY_STATUS_DUR_EST, String::class.java)
        put(SettingsContract.KEY_INDICATE_CHARGING, Boolean::class.java)
        put(SettingsContract.KEY_CHIP_CONTENT, String::class.java)
        put(SettingsContract.KEY_CHIP_SWITCHING_INTERVAL, String::class.java)
        put(SettingsContract.KEY_CHIP_INDICATE_CHARGING, Boolean::class.java)
        put(SettingsContract.KEY_LIVE_UPDATE_DISPLAY, String::class.java)
        put(SettingsContract.KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION, Boolean::class.java)
        put(SettingsContract.KEY_RED, Boolean::class.java)
        put(SettingsContract.KEY_RED_THRESH, String::class.java)
        put(SettingsContract.KEY_AMBER, Boolean::class.java)
        put(SettingsContract.KEY_AMBER_THRESH, String::class.java)
        put(SettingsContract.KEY_GREEN, Boolean::class.java)
        put(SettingsContract.KEY_GREEN_THRESH, String::class.java)
        put(SettingsContract.KEY_TOP_LINE, String::class.java)
        put(SettingsContract.KEY_BOTTOM_LINE, String::class.java)
        put(SettingsContract.KEY_TIME_REMAINING_VERBOSITY, String::class.java)
        put(SettingsContract.KEY_STATUS_DURATION_IN_VITAL_SIGNS, Boolean::class.java)
        put(SettingsContract.LEGACY_KEY_ENABLE_CURRENT, Boolean::class.java)
        put(SettingsContract.LEGACY_KEY_PREFER_FILE_SYSTEM, Boolean::class.java)
        put(SettingsContract.LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER, String::class.java)
        put(SettingsContract.LEGACY_KEY_DISPLAY_CURRENT_IN_VITAL_STATS, Boolean::class.java)
        put(
            SettingsContract.LEGACY_KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS, Boolean::class.java
        )
        put(SettingsContract.LEGACY_KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW, Boolean::class.java)
        put(
            SettingsContract.LEGACY_KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW, Boolean::class.java
        )
        put(
            SettingsContract.LEGACY_KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW, Boolean::class.java
        )
        put(SettingsContract.KEY_UI_COLOR, String::class.java)
        put(SettingsContract.KEY_ENABLE_ADVANCED_STATS, Boolean::class.java)
    }

    override fun restore(
        editor: SharedPreferences.Editor, settings: Map<String, Any>
    ) {
        var displayCurrentInNotification: Boolean? = null
        var preferAverageCurrent: Boolean? = null

        for ((key, value) in settings) {
            when (key) {
                SettingsContract.LEGACY_KEY_ENABLE_CURRENT -> editor.putBoolean(
                    SettingsContract.KEY_ENABLE_BATTERY_CURRENT, value as Boolean
                )

                SettingsContract.LEGACY_KEY_PREFER_FILE_SYSTEM -> Unit

                SettingsContract.LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER -> {
                    editor.putString(
                        SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, value as String
                    )
                    editor.remove(
                        SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING
                    )
                }

                SettingsContract.LEGACY_KEY_DISPLAY_CURRENT_IN_VITAL_STATS -> displayCurrentInNotification =
                    value as Boolean

                SettingsContract.LEGACY_KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS, SettingsContract.LEGACY_KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW -> preferAverageCurrent =
                    (preferAverageCurrent ?: false) || value as Boolean

                SettingsContract.LEGACY_KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW, SettingsContract.LEGACY_KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW -> Unit

                else -> editor.putSetting(key, value)
            }
        }

        editor.putBoolean(SettingsContract.KEY_USE_PRIVILEGED_BATTERY_CURRENT, false)
        editor.putString(SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL, "2")
        displayCurrentInNotification?.let {
            editor.putBoolean(SettingsContract.KEY_DISPLAY_CURRENT_IN_NOTIFICATION, it)
        }
        preferAverageCurrent?.let {
            editor.putBoolean(SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT, it)
        }

        editor.remove(SettingsContract.LEGACY_KEY_ENABLE_CURRENT)
        editor.remove(SettingsContract.LEGACY_KEY_PREFER_FILE_SYSTEM)
        editor.remove(SettingsContract.LEGACY_KEY_BATTERY_CURRENT_MULTIPLIER)
        editor.remove(SettingsContract.LEGACY_KEY_DISPLAY_CURRENT_IN_VITAL_STATS)
        editor.remove(SettingsContract.LEGACY_KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS)
        editor.remove(SettingsContract.LEGACY_KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW)
        editor.remove(SettingsContract.LEGACY_KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW)
        editor.remove(SettingsContract.LEGACY_KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW)
    }
}
