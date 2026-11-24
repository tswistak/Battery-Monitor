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
import codes.swistak.batterymonitor.alarms.AlarmSoundSettings
import codes.swistak.batterymonitor.alarms.AlarmThresholdKey
import codes.swistak.batterymonitor.alarms.backup.Version3AlarmImporter.KEY_CUSTOM_SETTINGS
import codes.swistak.batterymonitor.alarms.backup.Version3AlarmImporter.KEY_PLAYBACK
import codes.swistak.batterymonitor.alarms.backup.Version3AlarmImporter.KEY_SHARED_SETTINGS
import codes.swistak.batterymonitor.alarms.backup.Version3AlarmImporter.KEY_VOLUME_CATEGORY
import codes.swistak.batterymonitor.alarms.projectAlarms
import codes.swistak.batterymonitor.devicebackup.BackupImportInputStream
import codes.swistak.batterymonitor.devicebackup.readBackupJson
import codes.swistak.batterymonitor.devicebackup.writeBackupJson
import codes.swistak.batterymonitor.monitoring.notifications.NotificationChannels
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

internal data class AlarmBackupImportPreview(
    val version: Int,
    val alarms: List<AlarmRecord>,
    val shared: Map<String, AlarmSoundSettings>,
    val custom: Map<AlarmThresholdKey, AlarmSoundSettings>
)

internal object AlarmBackup {
    const val SCHEMA_VERSION: Int = Version3AlarmImporter.VERSION

    const val MAX_IMPORT_BYTES = 2 * 1024 * 1024

    @Throws(JSONException::class)
    fun exportToJson(database: AlarmDatabase): JSONObject {
        val snapshot = database.getSnapshot()
        val alarms = JSONArray()
        for ((_, enabled, type, threshold) in snapshot.rules) {
            if (type.isEmpty()) continue
            alarms.put(
                JSONObject().put(Version1AlarmImporter.KEY_ENABLED, enabled)
                    .put(Version1AlarmImporter.KEY_TYPE, type)
                    .put(Version1AlarmImporter.KEY_THRESHOLD, threshold)
            )
        }
        val shared = JSONObject()
        for ((type, configuration) in snapshot.shared) {
            shared.put(type, soundSettingsToJson(configuration.sound))
        }
        val custom = JSONArray()
        for ((_, alarms1) in projectAlarms(snapshot.rules).types) {
            for (alarm in alarms1) {
                val key = alarm.thresholdKey ?: continue
                val configuration = snapshot.custom[alarm.id] ?: continue
                custom.put(
                    soundSettingsToJson(configuration.sound).put(
                        Version1AlarmImporter.KEY_TYPE, key.type
                    ).put(Version1AlarmImporter.KEY_THRESHOLD, key.threshold)
                )
            }
        }
        return JSONObject().put("version", SCHEMA_VERSION).put("alarms", alarms)
            .put(KEY_SHARED_SETTINGS, shared).put(KEY_CUSTOM_SETTINGS, custom)
    }

    @Throws(JSONException::class, IllegalArgumentException::class)
    fun importFromJson(
        context: Context,
        database: AlarmDatabase,
        jsonString: String,
        allowChannelChanges: Boolean = false
    ): Int {
        require(jsonString.length <= MAX_IMPORT_BYTES) { "Alarm backup is too large" }
        val input = BackupImportInputStream(jsonString.byteInputStream(), MAX_IMPORT_BYTES)
        return restore(context, database, parseImport(readBackupJson(input)), allowChannelChanges)
    }

    fun restore(
        context: Context,
        database: AlarmDatabase,
        restored: AlarmBackupImportPreview,
        allowChannelChanges: Boolean = false
    ): Int {
        check(
            NotificationChannels.restoreAlarms(
                context,
                database,
                restored.alarms,
                restored.shared,
                restored.custom,
                allowChannelChanges
            )
        ) { "Could not restore alarms" }
        return restored.alarms.size
    }

    fun mayReplaceChannels(context: Context, restored: AlarmBackupImportPreview): Boolean {
        val database = AlarmDatabase(context)
        try {
            return NotificationChannels.alarmRestoreMayReplaceChannels(
                context, database, restored.shared, restored.custom
            )
        } finally {
            database.close()
        }
    }

    fun parseImport(root: JSONObject): AlarmBackupImportPreview {
        val version = root.optInt("version", 0)
        return alarmImporterForVersion(version).restoreBackup(root, version)
    }

    private fun soundSettingsToJson(settings: AlarmSoundSettings) =
        JSONObject().put(KEY_PLAYBACK, settings.playback.name)
            .put(KEY_VOLUME_CATEGORY, settings.volumeCategory.name)

    @Throws(IOException::class)
    fun writeToUri(context: Context, uri: Uri, json: JSONObject) =
        writeBackupJson(context, uri, json, MAX_IMPORT_BYTES)

    @Throws(IOException::class)
    fun readFromUri(context: Context, uri: Uri): AlarmBackupImportPreview =
        parseImport(readBackupJson(context, uri, MAX_IMPORT_BYTES))
}
