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

internal object VitalSignsContentMigration : SettingsMigration {
    override val version: Int = 2

    private val allowedValues = setOf(
        SettingsContract.VITAL_SIGN_HEALTH,
        SettingsContract.VITAL_SIGN_TEMPERATURE,
        SettingsContract.VITAL_SIGN_VOLTAGE,
        SettingsContract.VITAL_SIGN_CURRENT,
        SettingsContract.VITAL_SIGN_CHARGE,
        SettingsContract.VITAL_SIGN_STATUS_DURATION
    )

    override fun migrate(
        preferences: SharedPreferences, editor: SharedPreferences.Editor
    ) {
        val existing = if (preferences.contains(SettingsContract.KEY_VITAL_SIGNS_CONTENT)) {
            preferences.getStringSet(SettingsContract.KEY_VITAL_SIGNS_CONTENT, emptySet())
        } else {
            null
        }
        val content = migratedContent(
            existing = existing, displayCurrent = preferences.getBoolean(
                SettingsContract.KEY_DISPLAY_CURRENT_IN_NOTIFICATION, false
            ), displayStatusDuration = preferences.getBoolean(
                SettingsContract.KEY_STATUS_DURATION_IN_VITAL_SIGNS, false
            )
        )

        editor.putStringSet(SettingsContract.KEY_VITAL_SIGNS_CONTENT, content)
        editor.remove(SettingsContract.KEY_DISPLAY_CURRENT_IN_NOTIFICATION)
        editor.remove(SettingsContract.KEY_STATUS_DURATION_IN_VITAL_SIGNS)
    }

    fun restoreImportedSettings(
        editor: SharedPreferences.Editor, settings: Map<String, Any>
    ) {
        val existing =
            (settings[SettingsContract.KEY_VITAL_SIGNS_CONTENT] as? Set<*>)?.filterIsInstance<String>()
                ?.toSet()
        val content = migratedContent(
            existing = existing,
            displayCurrent = (settings[SettingsContract.KEY_DISPLAY_CURRENT_IN_NOTIFICATION]
                ?: settings[SettingsContract.LEGACY_KEY_DISPLAY_CURRENT_IN_VITAL_STATS]) as? Boolean
                ?: false,
            displayStatusDuration = settings[SettingsContract.KEY_STATUS_DURATION_IN_VITAL_SIGNS] as? Boolean
                ?: false
        )

        editor.putStringSet(SettingsContract.KEY_VITAL_SIGNS_CONTENT, content)
        editor.remove(SettingsContract.KEY_DISPLAY_CURRENT_IN_NOTIFICATION)
        editor.remove(SettingsContract.KEY_STATUS_DURATION_IN_VITAL_SIGNS)
    }

    internal fun migratedContent(
        existing: Set<String>?, displayCurrent: Boolean, displayStatusDuration: Boolean
    ): Set<String> {
        val content = (existing ?: SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT).filterTo(
            linkedSetOf()
        ) { it in allowedValues }
        if (displayCurrent) content += SettingsContract.VITAL_SIGN_CURRENT
        if (displayStatusDuration) content += SettingsContract.VITAL_SIGN_STATUS_DURATION
        return content
    }
}
