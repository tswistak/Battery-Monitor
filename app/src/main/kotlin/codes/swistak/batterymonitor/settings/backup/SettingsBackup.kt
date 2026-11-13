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

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import codes.swistak.batterymonitor.devicebackup.readBackupJson
import codes.swistak.batterymonitor.devicebackup.writeBackupJson
import codes.swistak.batterymonitor.settings.ChipContentOrder
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.VitalSignsOrder
import codes.swistak.batterymonitor.settings.migration.ChipContentMigration
import codes.swistak.batterymonitor.settings.migration.TemperatureUnitPreferencesMigration
import codes.swistak.batterymonitor.settings.migration.VitalSignsContentMigration
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

internal object SettingsBackup {
    const val SCHEMA_VERSION: Int = Version4SettingsImporter.VERSION

    const val MAX_IMPORT_BYTES = 1024 * 1024

    fun parsedSettings(root: JSONObject): Pair<Int, Map<String, Any>> {
        val version = root.optInt("version", 0)
        val importer = settingsImporterForVersion(version)
        val settings = root.getJSONObject("settings")
        val values = buildMap<String, Any> {
            val keys = settings.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key in importer.schema) put(key, settings.get(key))
            }
        }
        SettingsBackupValidation.validate(values, importer.schema)
        if (version >= Version3SettingsImporter.VERSION) {
            SettingsBackupCodec.vitalSignsOrderFromBackup(values)
            SettingsBackupCodec.chipContentOrderFromBackup(values)
        }
        return version to values
    }

    @Throws(JSONException::class)
    fun exportToJson(prefs: SharedPreferences): JSONObject {
        val settings = JSONObject()
        for ((key, value) in prefs.all) {
            if (Version4SettingsImporter.schema.containsKey(key)) {
                settings.put(key, value)
            }
        }

        val vitalSignsContent = prefs.getStringSet(
            SettingsContract.KEY_VITAL_SIGNS_CONTENT, SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT
        ) ?: SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT
        for ((backupKey, contentValue) in SettingsBackupCodec.vitalSignsContentByBackupKey) {
            settings.put(backupKey, contentValue in vitalSignsContent)
        }
        val vitalSignsOrder = VitalSignsOrder.parse(
            prefs.getString(SettingsContract.KEY_VITAL_SIGNS_ORDER, null)
        )
        for ((backupKey, contentValue) in SettingsBackupCodec.vitalSignsOrderByBackupKey) {
            settings.put(backupKey, vitalSignsOrder.indexOf(contentValue))
        }

        val chipContent = prefs.getStringSet(
            SettingsContract.KEY_CHIP_CONTENT, SettingsContract.DEFAULT_CHIP_CONTENT
        ) ?: SettingsContract.DEFAULT_CHIP_CONTENT
        for ((backupKey, contentValue) in SettingsBackupCodec.chipContentByBackupKey) {
            settings.put(backupKey, contentValue in chipContent)
        }
        val chipContentOrder = ChipContentOrder.parse(
            prefs.getString(SettingsContract.KEY_CHIP_CONTENT_ORDER, null)
        )
        for ((backupKey, contentValue) in SettingsBackupCodec.chipContentOrderByBackupKey) {
            settings.put(backupKey, chipContentOrder.indexOf(contentValue))
        }

        val root = JSONObject()
        root.put("version", SCHEMA_VERSION)
        root.put("settings", settings)
        return root
    }

    fun restore(editor: SharedPreferences.Editor, parsed: Pair<Int, Map<String, Any>>) {
        val (version, validatedSettings) = parsed
        settingsImporterForVersion(version).restore(editor, validatedSettings)
        TemperatureUnitPreferencesMigration.restoreImportedSettings(editor, validatedSettings)
        if (version < Version3SettingsImporter.VERSION) {
            VitalSignsContentMigration.restoreImportedSettings(editor, validatedSettings)
            ChipContentMigration.restoreImportedSettings(editor, validatedSettings)
        }
    }

    @Throws(IOException::class)
    fun writeToUri(context: Context, uri: Uri, json: JSONObject) =
        writeBackupJson(context, uri, json, MAX_IMPORT_BYTES)

    @Throws(IOException::class)
    fun readFromUri(context: Context, uri: Uri): Pair<Int, Map<String, Any>> =
        parsedSettings(readBackupJson(context, uri, MAX_IMPORT_BYTES))
}
