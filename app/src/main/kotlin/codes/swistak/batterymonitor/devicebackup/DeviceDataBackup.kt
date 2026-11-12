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
import android.os.Bundle
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import android.util.MalformedJsonException
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogExport
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.monitoring.Predictor
import codes.swistak.batterymonitor.monitoring.PredictorStoredState
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsSnapshot
import org.json.JSONTokener
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.nio.charset.StandardCharsets

internal enum class DeviceDataType {
    LOGS, PREDICTOR_DATA
}

internal enum class LogImportMode {
    REPLACE, ADD
}

internal object DeviceDataBackup {
    const val SCHEMA_VERSION: Int = Version2DeviceDataImporter.VERSION

    private const val KEY_VERSION = "version"
    private const val KEY_LOGS = "logs"
    private const val KEY_PREDICTOR = "predictor"

    fun exportToUri(
        context: Context,
        uri: Uri,
        selectedData: Set<DeviceDataType>,
        predictorState: PredictorStoredState? = null
    ) {
        writeStagedBackupExport(context, { uri }) {
            writeBackup(context, BackupExportOutputStream(it), selectedData, predictorState)
        }
    }

    fun writeBackup(
        context: Context,
        output: OutputStream,
        selectedData: Set<DeviceDataType>,
        predictorState: PredictorStoredState? = null
    ) {
        require(selectedData.isNotEmpty()) { "No device-specific data selected" }
        val writer = backupWriter(output)
        if (DeviceDataType.LOGS in selectedData) {
            val database = LogDatabase(context)
            try {
                database.readLogRecords { writer.writeLogs(context, it) }
            } finally {
                database.close()
            }
        }

        if (DeviceDataType.PREDICTOR_DATA in selectedData) {
            val state = PredictorBackupReader.state(predictorState) {
                PredictorBackupReader.read(context.dataDir)
            }
            val version = state.version ?: Version1DeviceDataImporter.VERSION
            require(version in 1..Predictor.STATE_VERSION) { "Unsupported predictor state version" }
            writer.name(KEY_PREDICTOR).beginObject()
            for ((backupKey, preferenceKey) in Version1DeviceDataImporter.predictorPreferenceKeysByBackupKey) {
                state.averages[preferenceKey]?.let { writer.name(backupKey).value(it.toDouble()) }
            }
            writer.name(Version2DeviceDataImporter.KEY_PREDICTOR_STATE_VERSION).value(
                version.toLong()
            )
            writer.endObject()
        }
        writer.endObject().flush()
    }

    fun writeHistory(context: Context, output: OutputStream, records: Sequence<LogRecord>) {
        backupWriter(BackupExportOutputStream(output)).apply {
            writeLogs(context, records)
            endObject().flush()
        }
    }

    private fun backupWriter(output: OutputStream): JsonWriter =
        JsonWriter(OutputStreamWriter(output, StandardCharsets.UTF_8)).beginObject()
            .name(KEY_VERSION).value(SCHEMA_VERSION.toLong())

    private fun JsonWriter.writeLogs(context: Context, records: Sequence<LogRecord>) {
        name(KEY_LOGS).beginArray()
        for (record in records) writeLogRecord(record)
        endArray()
        val preferences = context.getSharedPreferences(
            SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
        )
        if (preferences.contains(SettingsContract.KEY_LAST_LOG_EXPORT_TIME)) {
            name(Version1DeviceDataImporter.KEY_LAST_LOG_EXPORT_TIME).value(
                preferences.getLong(SettingsContract.KEY_LAST_LOG_EXPORT_TIME, 0L)
            )
        }
    }

    private fun JsonWriter.writeLogRecord(record: LogRecord) {
        val (status, charge, time, temperature, voltage, current, remainingCharge) = record
        require(remainingCharge == null || remainingCharge >= 0) {
            "Invalid remaining charge in a history record"
        }
        beginObject()
        name(Version1DeviceDataImporter.KEY_LOG_STATUS).value(status.toLong())
        name(Version1DeviceDataImporter.KEY_LOG_CHARGE).value(charge as Number?)
        name(Version1DeviceDataImporter.KEY_LOG_TIME).value(time)
        name(Version1DeviceDataImporter.KEY_LOG_TEMPERATURE).value(temperature as Number?)
        name(Version1DeviceDataImporter.KEY_LOG_VOLTAGE).value(voltage as Number?)
        name(Version2DeviceDataImporter.KEY_LOG_CURRENT).value(current as Number?)
        name(Version2DeviceDataImporter.KEY_LOG_REMAINING_CHARGE).value(remainingCharge as Number?)
        endObject()
    }

    fun preparedLogsFile(source: File): File = File(source.parentFile, "${source.name}.logs")

    fun readFromUri(context: Context, uri: Uri, logsFile: File): PreparedDeviceDataBackup {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open backup source")
        return input.use { read(it, logsFile) }
    }

    fun read(input: InputStream, logsFile: File): PreparedDeviceDataBackup = try {
        DeviceBackupReader.read(
            InputStreamReader(BackupImportInputStream(input), StandardCharsets.UTF_8.newDecoder()),
            logsFile
        )
    } catch (failure: Exception) {
        if (failure is MalformedJsonException || failure is EOFException || failure is CharacterCodingException || failure is IllegalStateException) throw IllegalArgumentException(
            "Invalid device-specific backup", failure
        )
        throw failure
    }

    fun validate(backup: PreparedDeviceDataBackup, selectedData: Set<DeviceDataType>) {
        require(selectedData.isNotEmpty() && backup.available.containsAll(selectedData)) {
            "Selected device data is not available in the backup"
        }
        if (DeviceDataType.LOGS in selectedData) {
            backup.logs?.getOrThrow()?.validate()
            backup.watermark?.getOrThrow()
        }
        if (DeviceDataType.PREDICTOR_DATA in selectedData) backup.predictor?.getOrThrow()
    }

    fun predictorSnapshot(context: Context): Bundle = SettingsSnapshot.capture(
        context.getSharedPreferences(Predictor.STORE_NAME, Context.MODE_PRIVATE)
    )

    // KTX edit discards the commit results required before marking restored data complete.
    @SuppressLint("UseKtx")
    fun restore(
        context: Context,
        backup: PreparedDeviceDataBackup,
        selectedData: Set<DeviceDataType>,
        logImportMode: LogImportMode
    ) {
        validate(backup, selectedData)
        val logs = if (DeviceDataType.LOGS in selectedData) backup.logs?.getOrThrow() else null
        val lastLogExportTime = if (logs != null) backup.watermark?.getOrThrow() else null
        val predictor = if (DeviceDataType.PREDICTOR_DATA in selectedData) {
            backup.predictor?.getOrThrow()
        } else null

        restoreData(restoreLogs = logs?.let { records ->
            {
                val database = LogDatabase(context)
                try {
                    records.read { rows ->
                        when (logImportMode) {
                            LogImportMode.REPLACE -> database.replaceAllLogs(rows)
                            LogImportMode.ADD -> database.addLogs(rows)
                        }
                    }
                } finally {
                    database.close()
                }
                lastLogExportTime?.let { timestamp ->
                    LogExport.withWatermarkLock(context) {
                        check(
                            context.getSharedPreferences(
                                SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
                            ).edit().putLong(SettingsContract.KEY_LAST_LOG_EXPORT_TIME, timestamp)
                                .remove(SettingsContract.KEY_LAST_LOG_EXPORT_ID).commit()
                        ) { "Could not restore the last log export time" }
                    }
                }
            }
        }, restorePredictor = predictor?.let { state ->
            {
                val preferences =
                    context.getSharedPreferences(Predictor.STORE_NAME, Context.MODE_PRIVATE)
                val editor = preferences.edit().clear()
                for ((key, value) in state.averages) editor.putFloat(key, value)
                editor.putInt(Predictor.KEY_STATE_VERSION, requireNotNull(state.version))
                check(editor.commit()) { "Could not restore predictor data" }
            }
        })
    }


    internal fun restoreData(restoreLogs: (() -> Unit)?, restorePredictor: (() -> Unit)?) {
        val completed = linkedSetOf<GeneralBackupDataType>()
        var attempted = emptySet<GeneralBackupDataType>()
        try {
            restoreLogs?.let {
                attempted = setOf(GeneralBackupDataType.LOGS)
                it()
                completed += attempted
            }
            restorePredictor?.let {
                attempted = setOf(GeneralBackupDataType.PREDICTOR_DATA)
                it()
                completed += attempted
            }
        } catch (error: Exception) {
            throw GeneralBackupRestoreException(completed.toSet(), attempted, error)
        }
    }

    internal fun prepareLogsAppend(
        original: File, output: OutputStream, records: Sequence<LogRecord>, maxBytes: Int
    ): Boolean {
        require(original.isFile && original.length() <= maxBytes) { "Invalid staged JSON history" }
        val empty = original.length() == 0L
        val appended = records.iterator()
        val writeReplacement = empty || appended.hasNext()
        val destination = if (writeReplacement) BackupExportOutputStream(output, maxBytes)
        else object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
        val writer =
            JsonWriter(OutputStreamWriter(destination, StandardCharsets.UTF_8.newEncoder()))
        writer.beginObject()
        if (empty) {
            writer.name(KEY_VERSION).value(SCHEMA_VERSION.toLong())
            writer.name(KEY_LOGS).beginArray()
            while (appended.hasNext()) writer.writeLogRecord(appended.next())
            writer.endArray()
        } else {
            val version = original.appendReader().use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == KEY_VERSION) {
                        require(reader.peek() == JsonToken.NUMBER) { "Invalid JSON history version" }
                        val value = reader.nextString().toIntOrNull()
                        require(value != null && value in Version1DeviceDataImporter.VERSION..SCHEMA_VERSION) {
                            "Unsupported JSON history version"
                        }
                        return@use value
                    }
                    reader.skipValue()
                }
                throw IllegalArgumentException("Missing JSON history version")
            }

            original.appendReader().use { reader ->
                val fields = reader.appendObjectFields { key ->
                    writer.name(key)
                    when (key) {
                        KEY_VERSION -> {
                            reader.skipValue()
                            writer.value(SCHEMA_VERSION.toLong())
                        }

                        KEY_LOGS -> {
                            reader.beginArray()
                            writer.beginArray()
                            while (reader.hasNext()) {
                                writer.beginObject()
                                val log = mutableMapOf<String, Any?>()
                                reader.appendObjectFields { field ->
                                    writer.name(field)
                                    val value = reader.copyAppendValue(writer)
                                    if (field in DeviceBackupReader.logKeys) log[field] = value
                                }
                                Version2DeviceDataImporter.restoreLog(log)
                                writer.endObject()
                            }
                            reader.endArray()
                            while (appended.hasNext()) writer.writeLogRecord(appended.next())
                            writer.endArray()
                        }

                        KEY_PREDICTOR -> {
                            if (reader.peek() == JsonToken.NULL) reader.copyAppendValue(writer)
                            else {
                                writer.beginObject()
                                val fields = reader.appendObjectFields { field ->
                                    writer.name(field)
                                    reader.copyAppendValue(writer)
                                }
                                val predictorVersion =
                                    Version2DeviceDataImporter.KEY_PREDICTOR_STATE_VERSION
                                if (version < SCHEMA_VERSION && predictorVersion !in fields) {
                                    writer.name(predictorVersion).value(1L)
                                }
                                writer.endObject()
                            }
                        }

                        Version1DeviceDataImporter.KEY_LAST_LOG_EXPORT_TIME -> {
                            val value = reader.copyAppendValue(writer)
                            require(
                                value is Number && value.toDouble()
                                    .isFinite() && value.toDouble() == value.toLong().toDouble()
                            ) { "Invalid last log export time" }
                        }

                        else -> reader.copyAppendValue(writer)
                    }
                }
                require(KEY_LOGS in fields) { "Missing JSON history records" }
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected content after JSON history" }
            }
        }
        writer.endObject().flush()
        return writeReplacement
    }

    private fun File.appendReader() = JsonReader(
        BoundedJsonTokens(InputStreamReader(inputStream(), StandardCharsets.UTF_8.newDecoder()))
    )

    private inline fun JsonReader.appendObjectFields(copy: (String) -> Unit): Set<String> {
        beginObject()
        val fields = mutableSetOf<String>()
        var nameChars = 0
        while (hasNext()) {
            val name = nextName()
            nameChars += name.length
            require(fields.size < 4096 && nameChars <= 64 * 1024) { "JSON history object has too many fields" }
            require(fields.add(name)) { "Duplicate JSON history field" }
            copy(name)
        }
        endObject()
        return fields
    }

    private fun JsonReader.copyAppendValue(writer: JsonWriter): Any? = when (peek()) {
        JsonToken.BEGIN_OBJECT -> {
            writer.beginObject()
            appendObjectFields { field ->
                writer.name(field)
                copyAppendValue(writer)
            }
            writer.endObject()
            Unit
        }

        JsonToken.BEGIN_ARRAY -> {
            beginArray()
            writer.beginArray()
            while (hasNext()) copyAppendValue(writer)
            endArray()
            writer.endArray()
            Unit
        }

        JsonToken.NUMBER -> {
            val literal = nextString()
            val number = JSONTokener(literal).nextValue()
            require(number is Number && BigDecimal(literal).compareTo(BigDecimal(number.toString())) == 0) {
                "JSON history contains a number that cannot be preserved"
            }
            number.also { writer.value(it) }
        }

        JsonToken.STRING -> nextString().also { writer.value(it) }
        JsonToken.BOOLEAN -> nextBoolean().also { writer.value(it) }
        JsonToken.NULL -> {
            nextNull()
            writer.nullValue()
            null
        }

        else -> error("Invalid JSON history value")
    }

}
