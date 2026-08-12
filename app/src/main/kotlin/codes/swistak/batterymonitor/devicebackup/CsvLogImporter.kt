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
import android.content.res.Configuration
import android.net.Uri
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogRecord
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt
import android.text.format.DateFormat as AndroidDateFormat

internal object CsvLogImporter {
    private const val COLUMN_COUNT = 7

    private val supportedLocaleTags = listOf(
        "en",
        "en-US",
        "ar",
        "cs",
        "de",
        "el",
        "es",
        "es-US",
        "fa",
        "fr",
        "hi",
        "hu",
        "it",
        "he",
        "ja",
        "lt",
        "nl",
        "pl",
        "pt",
        "pt-BR",
        "ro",
        "ru",
        "sk",
        "sl",
        "sr",
        "tr",
        "zh-Hans",
        "zh-Hant"
    )

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun importFromCsv(context: Context, csv: String, logImportMode: LogImportMode): Int {
        val statusCodes = localizedStatusCodes(context)
        val timestampFormats = timestampFormats(context)
        var selectedTimestampFormat: DateFormat? = null
        val records = parseRecords(
            csv = csv,
            statusCodeFor = { statusCodes[it.trim()] },
            timestampFor = { date, time ->
                val value = "$date $time"
                selectedTimestampFormat?.parseFully(value)
                    ?: timestampFormats.firstNotNullOfOrNull { format ->
                        format.parseFully(value)?.also { selectedTimestampFormat = format }
                    }
            })

        val database = LogDatabase(context)
        try {
            when (logImportMode) {
                LogImportMode.REPLACE -> database.replaceAllLogs(records)
                LogImportMode.ADD -> database.addLogs(records)
            }
        } finally {
            database.close()
        }
        return records.size
    }

    internal fun parseRecords(
        csv: String, statusCodeFor: (String) -> Int?, timestampFor: (String, String) -> Long?
    ): List<LogRecord> {
        val rows = parseCsv(csv).filterNot { row -> row.all(String::isBlank) }
        require(rows.size > 1) { "The CSV file contains no log entries" }

        return rows.drop(1).mapIndexed { index, row ->
            require(row.size >= COLUMN_COUNT) { "Invalid CSV row ${index + 2}" }
            val trailingColumnStart = row.size - 4
            val statusLabel = row.subList(2, trailingColumnStart).joinToString(",")
            val statusCode = statusCodeFor(statusLabel)
                ?: throw IllegalArgumentException("Unknown status in CSV row ${index + 2}")
            val timestamp = timestampFor(row[0].trim(), row[1].trim())
                ?: throw IllegalArgumentException("Invalid timestamp in CSV row ${index + 2}")
            val isBoot = statusCode == LogDatabase.STATUS_BOOT_COMPLETED

            LogRecord(
                status = statusCode,
                charge = if (isBoot) null else row[trailingColumnStart].trim().toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid charge in CSV row ${index + 2}"),
                time = timestamp,
                temperature = if (isBoot) null else parseScaledDecimal(
                    row[trailingColumnStart + 1], 10.0, "temperature", index + 2
                ),
                voltage = if (isBoot) null else parseScaledDecimal(
                    row[trailingColumnStart + 3], 1000.0, "voltage", index + 2
                )
            )
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

    @SuppressLint("AppBundleLocaleChanges")
    private fun localizedStatusCodes(context: Context): Map<String, Int> = buildMap {
        for (localeTag in supportedLocaleTags) {
            val locale = Locale.forLanguageTag(localeTag)
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(locale)
            }
            val resources = context.createConfigurationContext(configuration).resources
            val newStatuses = resources.getStringArray(R.array.log_statuses)
            val oldStatuses = resources.getStringArray(R.array.log_statuses_old)
            val pluggedValues = resources.getStringArray(R.array.pluggeds)

            for (status in newStatuses.indices) {
                for (plugged in pluggedValues.indices) {
                    val suffix = if (plugged == 0) "" else " ${pluggedValues[plugged]}"
                    put(
                        newStatuses[status] + suffix,
                        LogDatabase.encodeStatus(status, plugged, LogDatabase.STATUS_NEW)
                    )
                }
            }
            for (status in oldStatuses.indices) {
                if (oldStatuses[status] == newStatuses[status]) continue
                for (plugged in pluggedValues.indices) {
                    val suffix = if (plugged == 0) "" else " ${pluggedValues[plugged]}"
                    put(
                        oldStatuses[status] + suffix,
                        LogDatabase.encodeStatus(status, plugged, LogDatabase.STATUS_OLD)
                    )
                }
            }
            put(
                resources.getString(R.string.status_boot_completed),
                LogDatabase.STATUS_BOOT_COMPLETED
            )
        }
    }

    @SuppressLint("AppBundleLocaleChanges")
    private fun timestampFormats(context: Context): List<DateFormat> {
        val formats = mutableListOf<DateFormat>()
        val seenPatterns = mutableSetOf<String>()
        for (localeTag in supportedLocaleTags) {
            val locale = Locale.forLanguageTag(localeTag)
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(locale)
            }
            val localeContext = context.createConfigurationContext(configuration)
            val datePatterns = buildSet {
                (AndroidDateFormat.getDateFormat(localeContext) as? SimpleDateFormat)?.toPattern()
                    ?.let(::add)
                (DateFormat.getDateInstance(
                    DateFormat.SHORT, locale
                ) as? SimpleDateFormat)?.toPattern()?.let(::add)
                add("yyyy-MM-dd")
            }
            val timePatterns = setOf(
                AndroidDateFormat.getBestDateTimePattern(locale, "Hms"),
                AndroidDateFormat.getBestDateTimePattern(locale, "hms")
            )
            for (datePattern in datePatterns) {
                for (timePattern in timePatterns) {
                    val combinedPattern = "$datePattern $timePattern"
                    val identity = "$localeTag:$combinedPattern"
                    if (seenPatterns.add(identity)) {
                        formats += SimpleDateFormat(combinedPattern, locale).apply {
                            isLenient = false
                        }
                    }
                }
            }
        }
        return formats
    }

    private fun DateFormat.parseFully(value: String): Long? {
        val position = ParsePosition(0)
        val parsed = parse(value, position) ?: return null
        return if (position.index == value.length) parsed.time else null
    }

    private fun parseScaledDecimal(value: String, scale: Double, name: String, row: Int): Int {
        val parsed = value.trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid $name in CSV row $row")
        require(parsed.isFinite()) { "Invalid $name in CSV row $row" }
        val scaled = parsed * scale
        require(scaled in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
            "Invalid $name in CSV row $row"
        }
        return scaled.roundToInt()
    }

    private fun parseCsv(csv: String): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < csv.length) {
            val character = csv[index]
            when {
                quoted && character == '"' && index + 1 < csv.length && csv[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }

                character == '"' -> quoted = !quoted
                !quoted && character == ',' -> {
                    row += field.toString()
                    field.clear()
                }

                !quoted && (character == '\n' || character == '\r') -> {
                    row += field.toString()
                    field.clear()
                    rows += row
                    row = mutableListOf()
                    if (character == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') {
                        index++
                    }
                }

                else -> field.append(character)
            }
            index++
        }
        require(!quoted) { "Unterminated quoted CSV field" }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows
    }
}
