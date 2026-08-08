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
import codes.swistak.batterymonitor.settings.ChipContentOrder
import codes.swistak.batterymonitor.settings.SettingsContract

internal object ChipContentMigration : SettingsMigration {
    override val version: Int = 3

    override fun migrate(
        preferences: SharedPreferences, editor: SharedPreferences.Editor
    ) {
        val storedValue = preferences.all[SettingsContract.KEY_CHIP_CONTENT]
        val content = when (storedValue) {
            is Set<*> -> storedValue.filterIsInstance<String>().filterTo(linkedSetOf()) {
                it in SettingsContract.ALL_CHIP_CONTENT
            }.ifEmpty { SettingsContract.DEFAULT_CHIP_CONTENT }

            is String -> migratedContent(storedValue)
            else -> SettingsContract.DEFAULT_CHIP_CONTENT
        }
        editor.putStringSet(SettingsContract.KEY_CHIP_CONTENT, content)
        editor.putString(
            SettingsContract.KEY_CHIP_CONTENT_ORDER, ChipContentOrder.serialize(
                preferences.getString(SettingsContract.KEY_CHIP_CONTENT_ORDER, null)?.split(",")
                    ?: SettingsContract.ALL_CHIP_CONTENT
            )
        )
    }

    fun restoreImportedSettings(
        editor: SharedPreferences.Editor, settings: Map<String, Any>
    ) {
        editor.putStringSet(
            SettingsContract.KEY_CHIP_CONTENT,
            migratedContent(settings[SettingsContract.KEY_CHIP_CONTENT] as? String)
        )
        editor.putString(
            SettingsContract.KEY_CHIP_CONTENT_ORDER,
            ChipContentOrder.serialize(SettingsContract.ALL_CHIP_CONTENT)
        )
    }

    internal fun migratedContent(legacyValue: String?): Set<String> = when (legacyValue) {
        SettingsContract.CHIP_CONTENT_TEMPERATURE -> setOf(
            SettingsContract.CHIP_CONTENT_TEMPERATURE
        )

        "switching" -> setOf(
            SettingsContract.CHIP_CONTENT_PERCENTAGE, SettingsContract.CHIP_CONTENT_TEMPERATURE
        )

        else -> setOf(SettingsContract.CHIP_CONTENT_PERCENTAGE)
    }
}
