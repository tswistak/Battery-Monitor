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
package codes.swistak.batterymonitor.alarms.backup

import android.content.Context
import android.net.Uri
import codes.swistak.batterymonitor.alarms.AlarmDatabase
import codes.swistak.batterymonitor.alarms.AlarmRecord
import codes.swistak.batterymonitor.devicebackup.BackupImportInputStream
import codes.swistak.batterymonitor.devicebackup.readBackupJson
import codes.swistak.batterymonitor.devicebackup.writeBackupJson
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

internal data class AlarmBackupImportPreview(val version: Int, val alarms: List<AlarmRecord>)

internal object AlarmBackup {
    const val SCHEMA_VERSION: Int = Version2AlarmImporter.VERSION

    const val MAX_IMPORT_BYTES = 2 * 1024 * 1024

    @Throws(JSONException::class)
    fun exportToJson(database: AlarmDatabase): JSONObject {
        val alarms = JSONArray()
        for ((enabled, type, threshold) in database.getAllAlarmRecords()) {
            alarms.put(
                JSONObject().put(Version1AlarmImporter.KEY_ENABLED, enabled)
                    .put(Version1AlarmImporter.KEY_TYPE, type)
                    .put(Version1AlarmImporter.KEY_THRESHOLD, threshold)
            )
        }
        return JSONObject().put("version", SCHEMA_VERSION).put("alarms", alarms)
    }

    @Throws(JSONException::class, IllegalArgumentException::class)
    fun importFromJson(database: AlarmDatabase, jsonString: String): Int {
        require(jsonString.length <= MAX_IMPORT_BYTES) { "Alarm backup is too large" }
        val input = BackupImportInputStream(jsonString.byteInputStream(), MAX_IMPORT_BYTES)
        return restore(database, validatedRecords(readBackupJson(input)))
    }

    fun restore(database: AlarmDatabase, restored: List<AlarmRecord>): Int {
        check(database.replaceAllAlarms(restored)) { "Could not restore alarms" }
        return restored.size
    }

    fun validatedRecords(root: JSONObject): List<AlarmRecord> {
        val importer = alarmImporterForVersion(root.optInt("version", 0))
        val alarms = root.getJSONArray("alarms")
        val restored = buildList {
            for (index in 0 until alarms.length()) {
                val alarm = alarms.optJSONObject(index) ?: continue
                val values = buildMap<String, Any?> {
                    for (key in importer.schema.keys) {
                        if (alarm.has(key) && !alarm.isNull(key)) put(key, alarm.get(key))
                    }
                }
                importer.restore(values)?.let(::add)
            }
        }
        require(alarms.length() == 0 || restored.isNotEmpty()) {
            "The backup contains no supported alarms"
        }
        return restored
    }

    @Throws(IOException::class)
    fun writeToUri(context: Context, uri: Uri, json: JSONObject) =
        writeBackupJson(context, uri, json, MAX_IMPORT_BYTES)

    @Throws(IOException::class)
    fun readFromUri(context: Context, uri: Uri): AlarmBackupImportPreview {
        val root = readBackupJson(context, uri, MAX_IMPORT_BYTES)
        return AlarmBackupImportPreview(root.optInt("version", 0), validatedRecords(root))
    }
}
