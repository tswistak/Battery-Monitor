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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

internal object AlarmBackup {
    const val SCHEMA_VERSION: Int = Version1AlarmImporter.VERSION

    @Throws(JSONException::class)
    fun getSchemaVersion(jsonString: String): Int = JSONObject(jsonString).optInt("version", 0)

    @Throws(JSONException::class)
    fun exportToJson(database: AlarmDatabase): JSONObject {
        val alarms = JSONArray()
        for (record in database.getAllAlarmRecords()) {
            alarms.put(
                JSONObject().put(Version1AlarmImporter.KEY_ENABLED, record.enabled)
                    .put(Version1AlarmImporter.KEY_TYPE, record.type)
                    .put(Version1AlarmImporter.KEY_THRESHOLD, record.threshold)
            )
        }
        return JSONObject().put("version", SCHEMA_VERSION).put("alarms", alarms)
    }

    @Throws(JSONException::class, IllegalArgumentException::class)
    fun importFromJson(database: AlarmDatabase, jsonString: String): Int {
        val root = JSONObject(jsonString)
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
        check(database.replaceAllAlarms(restored)) { "Could not restore alarms" }
        return restored.size
    }

    @Throws(IOException::class)
    fun writeToUri(context: Context, uri: Uri, json: JSONObject) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "w") ?: return
        pfd.use {
            FileOutputStream(it.fileDescriptor).use { output ->
                output.write(json.toString().toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    @Throws(IOException::class)
    fun readFromUri(context: Context, uri: Uri): String? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        pfd.use {
            BufferedReader(
                InputStreamReader(FileInputStream(it.fileDescriptor), StandardCharsets.UTF_8)
            ).use { reader ->
                return reader.readText()
            }
        }
    }
}
