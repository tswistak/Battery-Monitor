/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.devicebackup

import android.util.JsonReader
import android.util.JsonToken
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.monitoring.PredictorStoredState
import org.json.JSONTokener
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FilterReader
import java.io.Reader

internal data class PreparedDeviceDataBackup(
    val version: Int,
    val logs: Result<PreparedBackupLogs>?,
    val watermark: Result<Long>?,
    val predictor: Result<PredictorStoredState>?
) {
    val available: Set<DeviceDataType> = buildSet {
        if (logs != null) add(DeviceDataType.LOGS)
        if (predictor != null) add(DeviceDataType.PREDICTOR_DATA)
    }
}

internal class PreparedBackupLogs(
    private val file: File, private val count: Int, private val expectedSize: Long
) {
    fun validate() {
        check(file.isFile && file.length() == expectedSize) { "Prepared history is incomplete" }
    }

    fun <T> read(block: (Sequence<LogRecord>) -> T): T =
        DataInputStream(file.inputStream().buffered()).use { input ->
            block(sequence {
                repeat(count) {
                    val nulls = input.readUnsignedByte()
                    yield(
                        LogRecord(
                            status = input.readInt(),
                            charge = if (nulls and 1 == 0) input.readInt() else null,
                            time = input.readLong(),
                            temperature = if (nulls and 2 == 0) input.readInt() else null,
                            voltage = if (nulls and 4 == 0) input.readInt() else null,
                            currentMicroAmps = if (nulls and 8 == 0) input.readLong() else null,
                            remainingChargeMicroampHours = if (nulls and 16 == 0) input.readLong() else null
                        )
                    )
                }
                check(input.read() == -1) { "Unexpected data in prepared history" }
            })
        }
}

internal object DeviceBackupReader {
    private const val KEY_LOGS = "logs"
    private const val KEY_PREDICTOR = "predictor"
    private const val KEY_VERSION = "version"
    val logKeys = setOf(
        Version1DeviceDataImporter.KEY_LOG_STATUS,
        Version1DeviceDataImporter.KEY_LOG_CHARGE,
        Version1DeviceDataImporter.KEY_LOG_TIME,
        Version1DeviceDataImporter.KEY_LOG_TEMPERATURE,
        Version1DeviceDataImporter.KEY_LOG_VOLTAGE,
        Version2DeviceDataImporter.KEY_LOG_CURRENT,
        Version2DeviceDataImporter.KEY_LOG_REMAINING_CHARGE
    )
    private val predictorKeys =
        Version1DeviceDataImporter.predictorPreferenceKeysByBackupKey.keys + Version2DeviceDataImporter.KEY_PREDICTOR_STATE_VERSION

    fun read(source: Reader, logsFile: File): PreparedDeviceDataBackup {
        try {
            return JsonReader(BoundedJsonTokens(source)).use { reader ->
                var version = 0
                var logs: Result<PreparedBackupLogs>? = null
                var watermark: Result<Long>? = null
                var predictor: Map<String, Any?>? = null
                val seen = mutableSetOf<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    when (name) {
                        KEY_VERSION, KEY_LOGS, KEY_PREDICTOR, Version1DeviceDataImporter.KEY_LAST_LOG_EXPORT_TIME -> require(
                            seen.add(name)
                        ) { "Duplicate device backup field" }
                    }
                    when (name) {
                        KEY_VERSION -> version = reader.readScalar() as? Int ?: 0
                        KEY_LOGS -> if (reader.peek() == JsonToken.NULL) reader.nextNull()
                        else logs = readLogs(reader, logsFile)

                        KEY_PREDICTOR -> if (reader.peek() == JsonToken.NULL) reader.nextNull()
                        else predictor = reader.readFields(predictorKeys)

                        Version1DeviceDataImporter.KEY_LAST_LOG_EXPORT_TIME -> {
                            val value = reader.readScalar()
                            watermark = runCatching {
                                require(value is Number) { "Invalid last log export time" }
                                val timestamp = value.toLong()
                                require(
                                    value.toDouble()
                                        .isFinite() && value.toDouble() == timestamp.toDouble()
                                ) {
                                    "Invalid last log export time"
                                }
                                timestamp
                            }
                        }

                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected content after device backup" }
                require(version >= Version1DeviceDataImporter.VERSION) { "Unsupported device-specific data backup version" }
                val predictorResult = predictor?.let { fields ->
                    runCatching {
                        Version2DeviceDataImporter.restorePredictor(
                            Version1DeviceDataImporter.restorePredictor(fields.filterValues { it != null }),
                            version,
                            fields[Version2DeviceDataImporter.KEY_PREDICTOR_STATE_VERSION]
                        )
                    }
                }
                PreparedDeviceDataBackup(version, logs, watermark, predictorResult)
            }
        } catch (failure: Exception) {
            logsFile.delete()
            throw failure
        }
    }

    private fun readLogs(reader: JsonReader, file: File): Result<PreparedBackupLogs> {
        reader.beginArray()
        var count = 0
        var invalid: IllegalArgumentException? = null
        DataOutputStream(file.outputStream().buffered()).use { output ->
            while (reader.hasNext()) {
                if (invalid != null) {
                    reader.skipValue()
                    continue
                }
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    reader.skipValue()
                    invalid = IllegalArgumentException("Invalid history record")
                    continue
                }
                val values = reader.readFields(logKeys)
                val record = try {
                    Version2DeviceDataImporter.restoreLog(values)
                } catch (failure: IllegalArgumentException) {
                    invalid = failure
                    continue
                }
                output.writeRecord(record)
                count++
            }
        }
        reader.endArray()
        invalid?.let {
            file.delete()
            return Result.failure(it)
        }
        return Result.success(PreparedBackupLogs(file, count, file.length()))
    }

    private fun JsonReader.readFields(keys: Set<String>): Map<String, Any?> = buildMap {
        beginObject()
        while (hasNext()) {
            val name = nextName()
            if (name in keys) {
                require(!containsKey(name)) { "Duplicate device backup field" }
                put(name, readScalar())
            } else skipValue()
        }
        endObject()
    }

    private fun JsonReader.readScalar(): Any? = when (peek()) {
        JsonToken.NUMBER -> JSONTokener(nextString()).nextValue()
        JsonToken.NULL -> nextNull().let { null }
        else -> {
            skipValue()
        }
    }

    private fun DataOutputStream.writeRecord(record: LogRecord) {
        var nulls = 0
        if (record.charge == null) nulls = nulls or 1
        if (record.temperature == null) nulls = nulls or 2
        if (record.voltage == null) nulls = nulls or 4
        if (record.currentMicroAmps == null) nulls = nulls or 8
        if (record.remainingChargeMicroampHours == null) nulls = nulls or 16
        writeByte(nulls)
        writeInt(record.status)
        record.charge?.let(::writeInt)
        writeLong(record.time)
        record.temperature?.let(::writeInt)
        record.voltage?.let(::writeInt)
        record.currentMicroAmps?.let(::writeLong)
        record.remainingChargeMicroampHours?.let(::writeLong)
    }
}

internal class BoundedJsonTokens(source: Reader) : FilterReader(source) {
    private var depth = 0
    private var quoted = false
    private var escaped = false
    private var tokenLength = 0

    override fun read(): Int = super.read().also { if (it != -1) scan(it.toChar()) }

    override fun read(buffer: CharArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { count ->
            for (index in offset until offset + count) scan(buffer[index])
        }

    private fun scan(char: Char) {
        if (quoted) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> {
                    quoted = false
                    tokenLength = 0
                    return
                }
            }
        } else {
            when (char) {
                '"' -> quoted = true
                '{', '[' -> require(++depth <= 64) { "Backup JSON is nested too deeply" }
                '}', ']' -> require(--depth >= 0) { "Unbalanced backup JSON" }
                ' ', '\t', '\n', '\r', ':', ',' -> Unit
                else -> {
                    require(++tokenLength <= 64 * 1024) { "Backup JSON field is too large" }
                    return
                }
            }
            tokenLength = 0
            return
        }
        require(++tokenLength <= 64 * 1024) { "Backup JSON field is too large" }
    }
}
