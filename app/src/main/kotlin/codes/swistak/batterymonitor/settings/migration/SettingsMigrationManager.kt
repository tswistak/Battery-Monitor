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
import android.util.Log

internal object SettingsMigrationManager {
    private const val LOG_TAG = "BatteryMonitor - SettingsMigration"
    private const val APPLIED_VERSION_KEY = "_applied_settings_migration_version"

    private val registeredMigrations: List<SettingsMigration> = listOf(
        BatteryCurrentPreferencesMigration,
        VitalSignsContentMigration,
        ChipContentMigration,
        TemperatureUnitPreferencesMigration,
        GlobalPrivilegedAccessMigration
    )

    @Synchronized
    fun migrate(
        preferences: SharedPreferences, migrations: List<SettingsMigration> = registeredMigrations
    ): Boolean {
        val orderedMigrations = migrations.sortedBy(SettingsMigration::version)
        require(orderedMigrations.all { it.version > 0 }) {
            "Settings migration versions must be positive"
        }
        require(
            orderedMigrations.map(SettingsMigration::version)
                .distinct().size == orderedMigrations.size
        ) {
            "Settings migration versions must be unique"
        }

        var appliedVersion = preferences.getInt(APPLIED_VERSION_KEY, 0)
        for (migration in orderedMigrations) {
            if (migration.version <= appliedVersion) continue

            val editor = preferences.edit()
            migration.migrate(preferences, editor)
            editor.putInt(APPLIED_VERSION_KEY, migration.version)
            if (!editor.commit()) {
                Log.e(LOG_TAG, "Failed to persist settings migration ${migration.version}")
                return false
            }
            appliedVersion = migration.version
        }
        return true
    }
}
