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
import codes.swistak.batterymonitor.devicebackup.CsvLogImporter
import codes.swistak.batterymonitor.monitoring.batteryvoltage.BatteryVoltageValidator
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal enum class LogExportFormat(val preferenceValue: String, val extension: String) {
    CSV("csv", "csv"), JSON("json", "json");

    companion object {
        fun fromPreference(value: String?): LogExportFormat =
            entries.firstOrNull { it.preferenceValue == value } ?: CSV
    }
}

internal object LogExport {
    internal fun hasRawCurrentCsvHeader(columns: List<String>): Boolean =
        columns.size == 8 && columns.last().contains("µA")

    private fun currentColumn(context: Context): String = context.getString(
        R.string.log_csv_column_with_unit,
        context.getString(R.string.pref_cat_battery_current_main),
        BidiFormatter.getInstance(context.resources.configuration.locales[0]).unicodeWrap("µA")
    )

    internal fun upgradeLegacyCsv(reader: Reader, writer: Writer, currentColumn: String) {
        val rows = CsvLogImporter.readCsvRows(reader).iterator()
        require(rows.hasNext()) { "Missing CSV header" }
        val header = rows.next()
        require(header.size == 7) { "Unsupported CSV columns" }
        fun write(row: List<String>) {
            writer.write(row.joinToString(",", transform = ::csvField))
            writer.write("\r\n")
        }
        write(header + currentColumn)
        for (row in rows) {
            require(row.size >= 7) { "Incomplete CSV row" }
            write(row + "")
        }
        writer.flush()
    }

    internal fun needsCsvSeparator(input: InputStream): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var last = -1
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            if (count > 0) last = buffer[count - 1].toInt() and 0xff
        }
        return last != -1 && last != '\r'.code && last != '\n'.code
    }

    fun appendCsv(context: Context, uri: Uri, records: Sequence<LogRecord>) {
        val resolver = context.contentResolver
        val header =
            (resolver.openInputStream(uri) ?: error("Could not read append target")).bufferedReader(
                StandardCharsets.UTF_8
            ).use { CsvLogImporter.readCsvRows(it).firstOrNull().orEmpty() }
        if (header.isEmpty() || hasRawCurrentCsvHeader(header)) {
            val needsSeparator = header.isNotEmpty() && (resolver.openInputStream(uri)
                ?: error("Could not read append target")).use(::needsCsvSeparator)
            resolver.openOutputStream(uri, if (header.isEmpty()) "wt" else "wa")?.use { output ->
                if (needsSeparator) output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
                writeCsv(context, output, records, includeHeader = header.isEmpty())
            } ?: error("Could not open append target")
            return
        }
        require(header.size == 7) { "Unsupported CSV columns" }

        val original = File.createTempFile("csv-recovery-", ".csv", context.filesDir)
        val upgraded = File.createTempFile("csv-upgrade-", ".csv", context.cacheDir)
        var retainRecovery = false
        try {
            resolver.openInputStream(uri)
                ?.use { input -> original.outputStream().use { output -> input.copyTo(output) } }
                ?: error("Could not read append target")
            upgraded.outputStream().use { output ->
                original.bufferedReader(StandardCharsets.UTF_8).use { input ->
                    val writer = OutputStreamWriter(output, StandardCharsets.UTF_8).buffered()
                    upgradeLegacyCsv(input, writer, currentColumn(context))
                }
                writeCsv(context, output, records, includeHeader = false)
            }
            try {
                resolver.openOutputStream(uri, "wt")?.use { output ->
                    upgraded.inputStream().use { it.copyTo(output) }
                } ?: error("Could not replace append target")
            } catch (failure: Exception) {
                try {
                    resolver.openOutputStream(uri, "wt")?.use { output ->
                        original.inputStream().use { it.copyTo(output) }
                    } ?: error("Could not restore append target")
                } catch (recoveryFailure: Exception) {
                    retainRecovery = true
                    failure.addSuppressed(recoveryFailure)
                    Log.e("LogExport", "Original CSV retained at $original", recoveryFailure)
                }
                throw failure
            }
        } finally {
            upgraded.delete()
            if (!retainRecovery) original.delete()
        }
    }

    fun loadRecords(
        context: Context, afterExclusive: Long? = null, throughInclusive: Long? = null
    ): List<LogRecord> {
        val database = LogDatabase(context.applicationContext)
        return try {
            database.getLogRecordsInRange(afterExclusive, throughInclusive)
        } finally {
            database.close()
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
                    resources.getString(R.string.voltage),
                    currentColumn(context)
                ).joinToString(",", transform = ::csvField)
            )
            writer.write("\r\n")
        }

        val date = Date()
        for (record in records) {
            date.time = record.time
            val temperature = record.temperature ?: 0
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
                (record.charge ?: 0).toString(),
                (temperature / 10.0).toString(),
                ((temperature * 9 / 5.0).roundToInt() / 10.0 + 32.0).toString(),
                csvVoltageField(record.voltage),
                record.currentMicroAmps?.toString() ?: ""
            )
            writer.write(values.joinToString(",", transform = ::csvField))
            writer.write("\r\n")
        }
        writer.flush()
    }

    private fun statusLabel(
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
        val labels = if (statusAge == LogDatabase.STATUS_OLD) oldStatuses else statuses
        val label = labels.getOrElse(status) { unknownLabel }
        return if (plugged in 1 until pluggedValues.size) "$label ${pluggedValues[plugged]}" else label
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
