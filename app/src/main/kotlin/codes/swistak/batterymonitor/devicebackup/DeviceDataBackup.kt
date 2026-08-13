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
package codes.swistak.batterymonitor.devicebackup

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.monitoring.Predictor
import codes.swistak.batterymonitor.settings.SettingsContract
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

internal enum class DeviceDataType {
    LOGS, PREDICTOR_DATA
}

internal enum class LogImportMode {
    REPLACE, ADD
}

internal object DeviceDataBackup {
    const val SCHEMA_VERSION: Int = Version1DeviceDataImporter.VERSION

    private const val KEY_VERSION = "version"
    private const val KEY_LOGS = "logs"
    private const val KEY_PREDICTOR = "predictor"

    @Throws(JSONException::class)
    fun getSchemaVersion(jsonString: String): Int = JSONObject(jsonString).optInt(KEY_VERSION, 0)

    @Throws(JSONException::class, IllegalArgumentException::class)
    fun getAvailableData(jsonString: String): Set<DeviceDataType> {
        val root = parseRoot(jsonString)
        return buildSet {
            if (root.has(KEY_LOGS) && !root.isNull(KEY_LOGS)) {
                root.getJSONArray(KEY_LOGS)
                add(DeviceDataType.LOGS)
            }
            if (root.has(KEY_PREDICTOR) && !root.isNull(KEY_PREDICTOR)) {
                root.getJSONObject(KEY_PREDICTOR)
                add(DeviceDataType.PREDICTOR_DATA)
            }
        }
    }

    @Throws(JSONException::class)
    fun exportToJson(context: Context, selectedData: Set<DeviceDataType>): JSONObject {
        require(selectedData.isNotEmpty()) { "No device-specific data selected" }
        val root = JSONObject().put(KEY_VERSION, SCHEMA_VERSION)

        if (DeviceDataType.LOGS in selectedData) {
            val database = LogDatabase(context)
            try {
                root.put(KEY_LOGS, logsToJson(database.getAllLogRecords()))
            } finally {
                database.close()
            }
            addLastLogExportTime(context, root)
        }

        if (DeviceDataType.PREDICTOR_DATA in selectedData) {
            val preferences =
                context.getSharedPreferences(Predictor.STORE_NAME, Context.MODE_PRIVATE)
            val predictor = JSONObject()
            for ((backupKey, preferenceKey) in Version1DeviceDataImporter.predictorPreferenceKeysByBackupKey) {
                if (preferences.contains(preferenceKey)) {
                    predictor.put(backupKey, preferences.getFloat(preferenceKey, -1f).toDouble())
                }
            }
            root.put(KEY_PREDICTOR, predictor)
        }

        return root
    }

    @Throws(JSONException::class, IllegalArgumentException::class, IllegalStateException::class)
    @SuppressLint("UseKtx")
    fun importFromJson(
        context: Context,
        jsonString: String,
        selectedData: Set<DeviceDataType>,
        logImportMode: LogImportMode
    ) {
        require(selectedData.isNotEmpty()) { "No device-specific data selected" }
        val root = parseRoot(jsonString)

        val logs = if (DeviceDataType.LOGS in selectedData) {
            parseLogs(root.getJSONArray(KEY_LOGS))
        } else {
            null
        }
        val lastLogExportTime =
            if (DeviceDataType.LOGS in selectedData && root.has(Version1DeviceDataImporter.KEY_LAST_LOG_EXPORT_TIME)) {
                parseLong(root, Version1DeviceDataImporter.KEY_LAST_LOG_EXPORT_TIME)
            } else {
                null
            }
        val predictor = if (DeviceDataType.PREDICTOR_DATA in selectedData) {
            parsePredictor(root.getJSONObject(KEY_PREDICTOR))
        } else {
            null
        }

        logs?.let {
            val database = LogDatabase(context)
            try {
                when (logImportMode) {
                    LogImportMode.REPLACE -> database.replaceAllLogs(it)
                    LogImportMode.ADD -> database.addLogs(it)
                }
            } finally {
                database.close()
            }
        }
        lastLogExportTime?.let {
            check(
                context.getSharedPreferences(
                    SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
                ).edit().putLong(SettingsContract.KEY_LAST_LOG_EXPORT_TIME, it).commit()
            ) { "Could not restore the last log export time" }
        }
        predictor?.let {
            val preferences =
                context.getSharedPreferences(Predictor.STORE_NAME, Context.MODE_PRIVATE)
            val editor = preferences.edit().clear()
            for ((key, value) in it) editor.putFloat(key, value)
            check(editor.commit()) { "Could not restore predictor data" }
        }
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

    fun exportLogsToJson(context: Context, records: List<LogRecord>): JSONObject =
        JSONObject().put(KEY_VERSION, SCHEMA_VERSION).put(KEY_LOGS, logsToJson(records)).also {
            addLastLogExportTime(context, it)
        }

    fun readLogsFromJson(jsonString: String): List<LogRecord> =
        parseLogs(parseRoot(jsonString).getJSONArray(KEY_LOGS))

    private fun addLastLogExportTime(context: Context, root: JSONObject) {
        val preferences = context.getSharedPreferences(
            SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
        )
        if (preferences.contains(SettingsContract.KEY_LAST_LOG_EXPORT_TIME)) {
            root.put(
                Version1DeviceDataImporter.KEY_LAST_LOG_EXPORT_TIME,
                preferences.getLong(SettingsContract.KEY_LAST_LOG_EXPORT_TIME, 0L)
            )
        }
    }

    private fun parseLong(root: JSONObject, key: String): Long {
        val value = root.get(key)
        require(value is Number) { "Invalid value for '$key'" }
        val longValue = value.toLong()
        require(value.toDouble().isFinite() && value.toDouble() == longValue.toDouble()) {
            "Invalid value for '$key'"
        }
        return longValue
    }

    private fun parseRoot(jsonString: String): JSONObject {
        val root = JSONObject(jsonString)
        val version = root.optInt(KEY_VERSION, 0)
        require(version >= Version1DeviceDataImporter.VERSION) {
            "Unsupported device-specific data backup version"
        }
        return root
    }

    private fun logsToJson(records: List<LogRecord>): JSONArray = JSONArray().apply {
        for (record in records) {
            put(
                JSONObject().put(Version1DeviceDataImporter.KEY_LOG_STATUS, record.status)
                    .putNullable(Version1DeviceDataImporter.KEY_LOG_CHARGE, record.charge)
                    .put(Version1DeviceDataImporter.KEY_LOG_TIME, record.time).putNullable(
                        Version1DeviceDataImporter.KEY_LOG_TEMPERATURE, record.temperature
                    ).putNullable(Version1DeviceDataImporter.KEY_LOG_VOLTAGE, record.voltage)
            )
        }
    }

    private fun parseLogs(logs: JSONArray): List<LogRecord> = buildList {
        for (index in 0 until logs.length()) {
            val log = logs.getJSONObject(index)
            val values = buildMap<String, Any?> {
                for (key in listOf(
                    Version1DeviceDataImporter.KEY_LOG_STATUS,
                    Version1DeviceDataImporter.KEY_LOG_CHARGE,
                    Version1DeviceDataImporter.KEY_LOG_TIME,
                    Version1DeviceDataImporter.KEY_LOG_TEMPERATURE,
                    Version1DeviceDataImporter.KEY_LOG_VOLTAGE
                )) {
                    if (log.has(key)) put(key, if (log.isNull(key)) null else log.get(key))
                }
            }
            add(Version1DeviceDataImporter.restoreLog(values))
        }
    }

    private fun parsePredictor(predictor: JSONObject): Map<String, Float> {
        val values = buildMap<String, Any?> {
            for (key in Version1DeviceDataImporter.predictorPreferenceKeysByBackupKey.keys) {
                if (predictor.has(key) && !predictor.isNull(key)) put(key, predictor.get(key))
            }
        }
        return Version1DeviceDataImporter.restorePredictor(values)
    }

    private fun JSONObject.putNullable(key: String, value: Int?): JSONObject =
        put(key, value ?: JSONObject.NULL)
}
