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
package codes.swistak.batterymonitor.logs

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.text.BidiFormatter
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.devicebackup.BackupExportOutputStream
import codes.swistak.batterymonitor.devicebackup.BackupImportInputStream
import codes.swistak.batterymonitor.devicebackup.CsvLogImporter
import codes.swistak.batterymonitor.devicebackup.GeneralBackup
import codes.swistak.batterymonitor.monitoring.batteryvoltage.BatteryVoltageValidator
import codes.swistak.batterymonitor.settings.SettingsContract
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.io.Reader
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

internal enum class LogExportFormat(val preferenceValue: String, val extension: String) {
    CSV("csv", "csv"), JSON("json", "json");

    companion object {
        fun fromPreference(value: String?): LogExportFormat =
            entries.firstOrNull { it.preferenceValue == value } ?: CSV
    }
}

internal object LogExport {
    private val exportLock = ReentrantLock()
    private val watermarkLock = ReentrantLock()

    internal fun <T> withDocumentLock(context: Context, operation: () -> T): T =
        withFileLock(context, "log-export.lock", exportLock, operation)

    internal fun <T> withWatermarkLock(context: Context, operation: () -> T): T =
        withFileLock(context, "log-export-watermarks.lock", watermarkLock, operation)

    private fun <T> withFileLock(
        context: Context, name: String, lock: ReentrantLock, operation: () -> T
    ): T = lock.withLock {
        if (lock.holdCount > 1) return@withLock operation()
        RandomAccessFile(File(context.filesDir, name), "rw").use { file ->
            file.channel.use { channel -> channel.lock().use { operation() } }
        }
    }

    fun appendJson(context: Context, uri: Uri, records: Sequence<LogRecord>) =
        withDocumentLock(context) {
            recoverCsv(context, uri, csvRecoveryCopy(context, uri))
            JsonLogAppend.append(context, uri, records)
        }

    internal fun hasMeasurementCsvHeader(columns: List<String>): Boolean =
        columns.size == 10 && columns[7].contains("µA") && columns[8].contains("µAh") && columns[9].contains(
            "W"
        )

    private fun measurementColumns(context: Context): List<String> = listOf(
        R.string.pref_cat_battery_current_main to "µA",
        R.string.remaining_charge to "µAh",
        R.string.battery_power to "W"
    ).map { (label, unit) ->
        context.getString(
            R.string.log_csv_column_with_unit,
            context.getString(label),
            BidiFormatter.getInstance(context.resources.configuration.locales[0]).unicodeWrap(unit)
        )
    }

    private fun currentMultiplier(context: Context): Int =
        context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
            .getString(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "1")?.toIntOrNull() ?: 1

    internal fun upgradeLegacyCsv(
        reader: Reader, writer: Writer, measurementColumns: List<String>
    ) {
        val rows = CsvLogImporter.readCsvRows(reader).iterator()
        require(rows.hasNext()) { "Missing CSV header" }
        val header = rows.next()
        require(header.size == 7) { "Unsupported CSV columns" }
        require(measurementColumns.size == 3)
        fun write(row: List<String>) {
            writer.write(row.joinToString(",", transform = ::csvField))
            writer.write("\r\n")
        }
        write(header + measurementColumns)
        for (row in rows) {
            require(row.size >= header.size) { "Incomplete CSV row" }
            write(row + listOf("", "", ""))
        }
        writer.flush()
    }

    internal data class CsvTail(val size: Int, val needsSeparator: Boolean)

    internal fun readCsvTail(input: InputStream): CsvTail {
        val bounded = BackupImportInputStream(input)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0
        var last = -1
        while (true) {
            val count = bounded.read(buffer)
            if (count == -1) break
            if (count > 0) {
                size += count
                last = buffer[count - 1].toInt() and 0xff
            }
        }
        return CsvTail(size, last != -1 && last != '\r'.code && last != '\n'.code)
    }

    fun appendCsv(context: Context, uri: Uri, records: Sequence<LogRecord>) =
        withDocumentLock(context) {
            val resolver = context.contentResolver
            val original = csvRecoveryCopy(context, uri)
            recoverCsv(context, uri, original)
            val header = csvInput(context, uri).use { input ->
                BackupImportInputStream(input).bufferedReader(StandardCharsets.UTF_8).use {
                    CsvLogImporter.readCsvRows(it).firstOrNull().orEmpty()
                }
            }
            val prepared = File(context.cacheDir, "csv-append.prepared")
            try {
                if (header.isNotEmpty() && !hasMeasurementCsvHeader(header)) {
                    migrateCsv(context, uri, records, original, prepared)
                } else {
                    val rows = records.iterator()
                    if (header.isNotEmpty() && !rows.hasNext()) return@withDocumentLock
                    val tail = if (header.isEmpty()) CsvTail(0, false)
                    else csvInput(context, uri).use(::readCsvTail)
                    prepared.outputStream().use { file ->
                        val output = BackupExportOutputStream(
                            file, GeneralBackup.MAX_ENTRY_BYTES - tail.size
                        )
                        if (tail.needsSeparator) output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
                        writeCsv(
                            context, output, rows.asSequence(), includeHeader = header.isEmpty()
                        )
                    }
                    resolver.openOutputStream(uri, if (header.isEmpty()) "wt" else "wa")
                        ?.use { output ->
                            prepared.inputStream().use { it.copyTo(output) }
                        } ?: error("Could not open append target")
                }
            } finally {
                prepared.delete()
            }
        }

    internal fun <T> withReadableCsv(context: Context, uri: Uri, read: () -> T): T =
        withDocumentLock(context) {
            val original = csvRecoveryCopy(context, uri)
            if (original.exists()) {
                throw csvRecoveryException(
                    context,
                    original,
                    IllegalStateException("CSV history replacement is incomplete")
                )
            }
            read()
        }

    private fun csvRecoveryCopy(context: Context, uri: Uri): File =
        File(context.filesDir, "export-recovery/${exportRecoveryKey(uri)}.csv-original")

    private fun csvInput(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: error("Could not read append target")

    private fun migrateCsv(
        context: Context, uri: Uri, records: Sequence<LogRecord>, original: File, prepared: File
    ) {
        val directory = requireNotNull(original.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Could not create export recovery directory" }
        val reading = File(directory, "csv.read")
        try {
            csvInput(context, uri).use { input ->
                FileOutputStream(reading).use { output ->
                    BackupImportInputStream(input).copyTo(output)
                    output.fd.sync()
                }
            }
            prepared.outputStream().use { file ->
                val output = BackupExportOutputStream(file)
                InputStreamReader(
                    reading.inputStream(), StandardCharsets.UTF_8.newDecoder()
                ).buffered().use { input ->
                    val writer = OutputStreamWriter(output, StandardCharsets.UTF_8).buffered()
                    upgradeLegacyCsv(input, writer, measurementColumns(context))
                }
                writeCsv(context, output, records, includeHeader = false)
            }
            // Only the renamed copy marks a migration that may have modified the SAF document.
            check(reading.renameTo(original)) { "Could not preserve CSV history" }
            try {
                replaceCsv(context, uri, prepared)
            } catch (failure: Exception) {
                try {
                    recoverCsv(context, uri, original)
                } catch (recoveryFailure: LogExportRecoveryException) {
                    recoveryFailure.addSuppressed(failure)
                    throw recoveryFailure
                }
                throw failure
            }
            if (!original.delete()) csvRecoveryFailure(
                context, original, IllegalStateException("Could not finish CSV history replacement")
            )
        } finally {
            reading.delete()
        }
    }

    private fun recoverCsv(context: Context, uri: Uri, original: File) {
        if (!original.exists()) return
        try {
            if (!csvMatches(context, uri, original)) replaceCsv(context, uri, original)
            check(original.delete()) { "Could not finish CSV history recovery" }
        } catch (failure: Exception) {
            csvRecoveryFailure(context, original, failure)
        }
    }

    private fun csvRecoveryFailure(context: Context, original: File, failure: Exception): Nothing {
        Log.e("LogExport", "Original CSV retained in ${original.name}", failure)
        throw csvRecoveryException(context, original, failure)
    }

    private fun csvRecoveryException(context: Context, original: File, failure: Exception) =
        LogExportRecoveryException(
            context.getString(R.string.log_export_recovery_required, original.name), failure
        )

    private fun replaceCsv(context: Context, uri: Uri, source: File) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            source.inputStream().use { it.copyTo(output) }
        } ?: error("Could not replace CSV history")
        check(csvMatches(context, uri, source)) { "CSV history did not match the prepared file" }
    }

    private fun csvMatches(context: Context, uri: Uri, source: File): Boolean {
        DataInputStream(csvInput(context, uri)).use { actual ->
            source.inputStream().use { expected ->
                val expectedBytes = ByteArray(DEFAULT_BUFFER_SIZE)
                val actualBytes = ByteArray(DEFAULT_BUFFER_SIZE)
                var count = expected.read(expectedBytes)
                while (count != -1) {
                    try {
                        actual.readFully(actualBytes, 0, count)
                    } catch (_: EOFException) {
                        return false
                    }
                    for (index in 0 until count) {
                        if (actualBytes[index] != expectedBytes[index]) return false
                    }
                    count = expected.read(expectedBytes)
                }
                return actual.read() == -1
            }
        }
    }

    fun fileName(format: LogExportFormat, timestamp: Long = System.currentTimeMillis()): String {
        val formattedTime = SimpleDateFormat(
            "yyyy-MM-dd-HHmmss-SSS", Locale.getDefault()
        ).format(Date(timestamp))
        return "${fileNamePrefix()}-$formattedTime.${format.extension}"
    }

    fun appendFileName(format: LogExportFormat): String = "${fileNamePrefix()}.${format.extension}"

    fun writeCsv(
        context: Context, output: OutputStream, records: List<LogRecord>, includeHeader: Boolean
    ) = writeCsv(context, output, records.asSequence(), includeHeader)

    fun writeCsv(
        context: Context, output: OutputStream, records: Sequence<LogRecord>, includeHeader: Boolean
    ) {
        val resources = context.resources
        val dateFormat = android.text.format.DateFormat.getDateFormat(context)
        val statuses = resources.getStringArray(R.array.log_statuses)
        val oldStatuses = resources.getStringArray(R.array.log_statuses_old)
        val pluggedValues = resources.getStringArray(R.array.pluggeds)
        val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))

        if (includeHeader) {
            writer.write(
                listOf(
                    resources.getString(R.string.date),
                    resources.getString(R.string.time),
                    resources.getString(R.string.status),
                    resources.getString(R.string.charge),
                    resources.getString(R.string.temperature),
                    resources.getString(R.string.temperature_f),
                    resources.getString(R.string.voltage)
                ).plus(measurementColumns(context)).joinToString(",", transform = ::csvField)
            )
            writer.write("\r\n")
        }

        val date = Date()
        val multiplier = currentMultiplier(context)
        for (record in records) {
            date.time = record.time
            val temperature = record.temperature
            val values = listOf(
                dateFormat.format(date),
                DisplayStrings.formatTime(context, date, includeSeconds = true),
                statusLabel(
                    record.status,
                    statuses,
                    oldStatuses,
                    pluggedValues,
                    resources.getString(R.string.status_boot_completed),
                    resources.getString(R.string.status_unknown)
                ),
                record.charge?.toString() ?: "",
                temperature?.let { (it / 10.0).toString() } ?: "",
                temperature?.let { ((it * 9 / 5.0).roundToInt() / 10.0 + 32.0).toString() } ?: "",
                csvVoltageField(record.voltage),
                record.currentMicroAmps?.toString() ?: "",
                record.remainingChargeMicroampHours?.toString() ?: "",
                csvPowerField(record, multiplier))
            writer.write(values.joinToString(",", transform = ::csvField))
            writer.write("\r\n")
        }
        writer.flush()
    }

    internal fun csvPowerField(record: LogRecord, currentMultiplier: Int): String =
        HistoryMetric.POWER.value(record)?.let {
            (if (it == 0.0) 0.0 else it * currentMultiplier).toString()
        } ?: ""

    internal fun statusLabel(
        statusCode: Int,
        statuses: Array<String>,
        oldStatuses: Array<String>,
        pluggedValues: Array<String>,
        bootLabel: String,
        unknownLabel: String
    ): String {
        if (statusCode == LogDatabase.STATUS_BOOT_COMPLETED) return bootLabel
        val decoded = LogDatabase.decodeStatus(statusCode)
        val status = decoded[0]
        val plugged = decoded[1]
        val statusAge = decoded[2]
        val labels = if (statusAge != LogDatabase.STATUS_NEW) oldStatuses else statuses
        val label = labels.getOrElse(status) { unknownLabel }
        val text =
            if (plugged in 1 until pluggedValues.size) "$label ${pluggedValues[plugged]}" else label
        return if (statusAge == LogDatabase.STATUS_SAMPLE) "[sample=$statusCode] $text" else text
    }


    private fun csvField(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\r' || it == '\n' }) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    internal fun csvVoltageField(millivolts: Int?): String {
        return millivolts?.takeIf(BatteryVoltageValidator::isValidBroadcastMillivolts)
            ?.let { (it / 1000.0).toString() } ?: ""
    }

    private fun fileNamePrefix(): String {
        val device = sanitizeFileNamePart("${Build.MANUFACTURER}-${Build.MODEL}")
        return "Battery_Monitor-Logs-$device"
    }

    private fun sanitizeFileNamePart(value: String): String =
        value.trim().replace("[^\\p{L}\\p{N}._-]+".toRegex(), "-").replace("-+".toRegex(), "-")
}
