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
import codes.swistak.batterymonitor.settings.ChipContentOrder
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.VitalSignsOrder
import codes.swistak.batterymonitor.settings.migration.ChipContentMigration
import codes.swistak.batterymonitor.settings.migration.TemperatureUnitPreferencesMigration
import codes.swistak.batterymonitor.settings.migration.VitalSignsContentMigration
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

internal object SettingsBackup {
    const val SCHEMA_VERSION: Int = Version4SettingsImporter.VERSION

    private fun validateSettings(
        settings: JSONObject, schema: Map<String, Class<*>>
    ): Map<String, Any> = buildMap {
        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val expectedType = schema[key] ?: continue
            val value = settings.get(key)

            when (expectedType) {
                Boolean::class.java -> require(value is Boolean) {
                    "Invalid type for '$key': expected boolean"
                }

                String::class.java -> require(value is String) {
                    "Invalid type for '$key': expected string"
                }

                Int::class.java -> require(value is Int) {
                    "Invalid type for '$key': expected integer"
                }

                else -> error("Unsupported settings type for '$key'")
            }
            put(key, value)
        }
    }

    @Throws(JSONException::class)
    fun getSchemaVersion(jsonString: String): Int {
        return JSONObject(jsonString).optInt("version", 0)
    }

    @Throws(JSONException::class)
    fun exportToJson(prefs: SharedPreferences): JSONObject {
        val settings = JSONObject()
        for (entry in prefs.all.entries) {
            if (Version4SettingsImporter.schema.containsKey(entry.key)) {
                settings.put(entry.key, entry.value)
            }
        }

        val vitalSignsContent = prefs.getStringSet(
            SettingsContract.KEY_VITAL_SIGNS_CONTENT, SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT
        ) ?: SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT
        for ((backupKey, contentValue) in Version3SettingsImporter.vitalSignsContentByBackupKey) {
            settings.put(backupKey, contentValue in vitalSignsContent)
        }
        val vitalSignsOrder = VitalSignsOrder.parse(
            prefs.getString(SettingsContract.KEY_VITAL_SIGNS_ORDER, null)
        )
        for ((backupKey, contentValue) in Version3SettingsImporter.vitalSignsOrderByBackupKey) {
            settings.put(backupKey, vitalSignsOrder.indexOf(contentValue))
        }

        val chipContent = prefs.getStringSet(
            SettingsContract.KEY_CHIP_CONTENT, SettingsContract.DEFAULT_CHIP_CONTENT
        ) ?: SettingsContract.DEFAULT_CHIP_CONTENT
        for ((backupKey, contentValue) in Version3SettingsImporter.chipContentByBackupKey) {
            settings.put(backupKey, contentValue in chipContent)
        }
        val chipContentOrder = ChipContentOrder.parse(
            prefs.getString(SettingsContract.KEY_CHIP_CONTENT_ORDER, null)
        )
        for ((backupKey, contentValue) in Version3SettingsImporter.chipContentOrderByBackupKey) {
            settings.put(backupKey, chipContentOrder.indexOf(contentValue))
        }

        val root = JSONObject()
        root.put("version", SCHEMA_VERSION)
        root.put("settings", settings)
        return root
    }

    @Throws(JSONException::class, IllegalArgumentException::class)
    fun importFromJson(editor: SharedPreferences.Editor, jsonString: String) {
        val root = JSONObject(jsonString)
        val version = root.optInt("version", 0)
        val importer = settingsImporterForVersion(version)
        val settings = root.optJSONObject("settings") ?: return
        val validatedSettings = validateSettings(settings, importer.schema)
        importer.restore(editor, validatedSettings)
        TemperatureUnitPreferencesMigration.restoreImportedSettings(editor, validatedSettings)
        if (version < Version3SettingsImporter.VERSION) {
            VitalSignsContentMigration.restoreImportedSettings(editor, validatedSettings)
            ChipContentMigration.restoreImportedSettings(editor, validatedSettings)
        }
    }

    @Throws(IOException::class)
    fun writeToUri(context: Context, uri: Uri, json: JSONObject) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "w") ?: return
        pfd.use { pfd ->
            val fos = FileOutputStream(pfd.fileDescriptor)
            fos.write(json.toString().toByteArray(StandardCharsets.UTF_8))
            fos.close()
        }
    }

    @Throws(IOException::class)
    fun readFromUri(context: Context, uri: Uri): String? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        pfd.use { pfd ->
            val reader = BufferedReader(
                InputStreamReader(FileInputStream(pfd.fileDescriptor), StandardCharsets.UTF_8)
            )
            val sb = StringBuilder()
            var line: String?
            while ((reader.readLine().also { line = it }) != null) sb.append(line).append('\n')
            reader.close()
            return sb.toString()
        }
    }
}
