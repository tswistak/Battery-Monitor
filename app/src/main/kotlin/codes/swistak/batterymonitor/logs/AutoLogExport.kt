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

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import codes.swistak.batterymonitor.devicebackup.DeviceDataBackup
import codes.swistak.batterymonitor.settings.SettingsContract
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal enum class AutoLogExportFrequency(
    val preferenceValue: String, val intervalHours: Int?
) {
    OFF("off", null), ONE_DAY("24", 24), TWO_DAYS("48", 48), FOUR_DAYS("96", 96), ONE_WEEK(
        "168", 168
    ),
    TWO_WEEKS("336", 336), THREE_WEEKS("504", 504), FOUR_WEEKS("672", 672);

    companion object {
        fun fromPreference(value: String?): AutoLogExportFrequency =
            entries.firstOrNull { it.preferenceValue == value } ?: OFF

        fun enabledForRetention(maxLogAgeHours: Int): List<AutoLogExportFrequency> =
            entries.filter { frequency ->
                frequency.intervalHours?.let { maxLogAgeHours !in 0..<it } == true
            }

        fun cappedForRetention(
            frequency: AutoLogExportFrequency, maxLogAgeHours: Int
        ): AutoLogExportFrequency {
            val enabled = enabledForRetention(maxLogAgeHours)
            return frequency.takeIf { it in enabled } ?: enabled.lastOrNull() ?: ONE_DAY
        }
    }
}

internal enum class AutoLogExportMode(val preferenceValue: String) {
    NEW_FILE("new"), APPEND("append");

    companion object {
        fun fromPreference(value: String?): AutoLogExportMode =
            entries.firstOrNull { it.preferenceValue == value } ?: NEW_FILE
    }
}

internal enum class AutoLogExportSetupAction {
    START_INITIAL_EXPORT, RESCHEDULE
}

internal fun autoLogExportSetupAction(wasConfigured: Boolean): AutoLogExportSetupAction =
    if (wasConfigured) AutoLogExportSetupAction.RESCHEDULE
    else AutoLogExportSetupAction.START_INITIAL_EXPORT

internal fun shouldCreateNewAutoLogExportFile(
    hasRecords: Boolean, createFileWhenEmpty: Boolean
): Boolean = hasRecords || createFileWhenEmpty

internal fun shouldRunAutoLogExport(
    loggingEnabled: Boolean, frequency: AutoLogExportFrequency, directoryConfigured: Boolean
): Boolean = loggingEnabled && frequency != AutoLogExportFrequency.OFF && directoryConfigured

internal object AutoLogExportScheduler {
    private const val REQUEST_CODE = 3084

    fun ensureScheduled(context: Context) = AutoLogExportProvider.schedule(context, "ensure")
    fun reschedule(context: Context) = AutoLogExportProvider.schedule(context, "reschedule")
    fun scheduleAfterRun(context: Context) = AutoLogExportProvider.schedule(context, "after")
    fun cancel(context: Context) = AutoLogExportProvider.schedule(context, "cancel")

    internal fun ensureScheduledInMainProcess(context: Context) {
        val preferences = preferences(context)
        val frequency = AutoLogExportFrequency.fromPreference(
            preferences.getString(SettingsContract.KEY_AUTO_LOG_EXPORT_FREQUENCY, null)
        )
        if (!shouldRunAutoLogExport(
                preferences.getBoolean(SettingsContract.KEY_ENABLE_LOGGING, true),
                frequency,
                preferences.contains(SettingsContract.KEY_AUTO_LOG_EXPORT_DIRECTORY)
            )
        ) {
            cancelInMainProcess(context)
            return
        }

        val now = System.currentTimeMillis()
        val storedNext = preferences.getLong(SettingsContract.KEY_NEXT_AUTO_LOG_EXPORT_TIME, 0L)
        val next = if (storedNext > now) storedNext else nextOccurrence(now, frequency)
        preferences.edit { putLong(SettingsContract.KEY_NEXT_AUTO_LOG_EXPORT_TIME, next) }
        scheduleAlarm(context, next)
    }

    internal fun rescheduleInMainProcess(context: Context) {
        preferences(context).edit { remove(SettingsContract.KEY_NEXT_AUTO_LOG_EXPORT_TIME) }
        ensureScheduledInMainProcess(context)
    }

    fun startInitialExport(context: Context) {
        cancel(context)
        context.applicationContext.sendBroadcast(
            Intent(context.applicationContext, AutoLogExportReceiver::class.java).putExtra(
                AutoLogExportReceiver.EXTRA_CREATE_FILE_WHEN_EMPTY, true
            )
        )
    }

    internal fun scheduleAfterRunInMainProcess(context: Context) {
        val preferences = preferences(context)
        val frequency = AutoLogExportFrequency.fromPreference(
            preferences.getString(SettingsContract.KEY_AUTO_LOG_EXPORT_FREQUENCY, null)
        )
        if (!shouldRunAutoLogExport(
                preferences.getBoolean(SettingsContract.KEY_ENABLE_LOGGING, true),
                frequency,
                preferences.contains(SettingsContract.KEY_AUTO_LOG_EXPORT_DIRECTORY)
            )
        ) {
            cancelInMainProcess(context)
            return
        }
        val next = nextOccurrence(System.currentTimeMillis(), frequency)
        preferences.edit { putLong(SettingsContract.KEY_NEXT_AUTO_LOG_EXPORT_TIME, next) }
        scheduleAlarm(context, next)
    }

    internal fun cancelInMainProcess(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarmManager?.cancel(pendingIntent(context))
        preferences(context).edit { remove(SettingsContract.KEY_NEXT_AUTO_LOG_EXPORT_TIME) }
    }

    internal fun nextOccurrence(now: Long, frequency: AutoLogExportFrequency): Long {
        val intervalHours = frequency.intervalHours ?: return now
        return now + TimeUnit.HOURS.toMillis(intervalHours.toLong())
    }

    private fun scheduleAlarm(context: Context, time: Long) {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, time, pendingIntent(context)
        )
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, AutoLogExportReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun preferences(context: Context) = context.getSharedPreferences(
        SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
    )
}

internal class AutoLogExportReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_CREATE_FILE_WHEN_EMPTY =
            "codes.swistak.batterymonitor.extra.CREATE_AUTO_EXPORT_FILE_WHEN_EMPTY"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val result = goAsync()
        val createFileWhenEmpty = intent?.getBooleanExtra(
            EXTRA_CREATE_FILE_WHEN_EMPTY, false
        ) == true
        Thread {
            try {
                AutoLogExporter.export(context.applicationContext, createFileWhenEmpty)
            } catch (exception: Exception) {
                Log.e("AutoLogExport", "Automatic log export failed", exception)
                AutoLogExporter.showRecoveryFailure(context, exception)
            } finally {
                AutoLogExportScheduler.scheduleAfterRun(context.applicationContext)
                result.finish()
            }
        }.start()
    }
}

internal object AutoLogExporter {
    private const val LOG_TAG = "AutoLogExport"

    fun isEnabled(context: Context): Boolean {
        val preferences = context.getSharedPreferences(
            SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
        )
        return shouldRunAutoLogExport(
            preferences.getBoolean(SettingsContract.KEY_ENABLE_LOGGING, true),
            AutoLogExportFrequency.fromPreference(
                preferences.getString(SettingsContract.KEY_AUTO_LOG_EXPORT_FREQUENCY, null)
            ),
            preferences.contains(SettingsContract.KEY_AUTO_LOG_EXPORT_DIRECTORY)
        )
    }

    fun exportBeforeManualLogClearing(context: Context): Boolean =
        exportBeforeLogDeletion(context, rescheduleAfterExport = false)

    fun exportBeforeAutomaticLogPruning(context: Context): Boolean =
        exportBeforeLogDeletion(context, rescheduleAfterExport = true)

    private fun exportBeforeLogDeletion(
        context: Context, rescheduleAfterExport: Boolean
    ): Boolean {
        return try {
            export(context)
            if (rescheduleAfterExport) AutoLogExportScheduler.reschedule(context)
            true
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "Skipping log deletion because automatic export failed", exception)
            showRecoveryFailure(context, exception)
            false
        }
    }

    internal fun showRecoveryFailure(context: Context, exception: Exception) {
        if (exception is LogExportRecoveryException) Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, exception.message, Toast.LENGTH_LONG).show()
        }
    }

    fun export(context: Context, createFileWhenEmpty: Boolean = false) =
        AutoLogExportProvider.export(context, createFileWhenEmpty)

    @SuppressLint("UseKtx")
    internal fun exportInMainProcess(context: Context, createFileWhenEmpty: Boolean = false) =
        LogExport.withDocumentLock(context) {
            if (!isEnabled(context)) return@withDocumentLock
            val preferences = context.getSharedPreferences(
                SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
            )
            val treeUri = preferences.getString(
                SettingsContract.KEY_AUTO_LOG_EXPORT_DIRECTORY, null
            )?.let(Uri::parse) ?: return@withDocumentLock
            val format = LogExportFormat.fromPreference(
                preferences.getString(SettingsContract.KEY_AUTO_LOG_EXPORT_FORMAT, null)
            )
            val mode = AutoLogExportMode.fromPreference(
                preferences.getString(SettingsContract.KEY_AUTO_LOG_EXPORT_MODE, null)
            )
            val exportThrough = System.currentTimeMillis()
            val lastAutomaticExport = preferences.takeIf {
                it.contains(SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME)
            }?.getLong(SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME, 0L)

            when (mode) {
                AutoLogExportMode.NEW_FILE -> {
                    val records = LogExport.loadRecords(
                        context,
                        afterExclusive = lastAutomaticExport,
                        throughInclusive = exportThrough
                    )
                    if (shouldCreateNewAutoLogExportFile(
                            records.isNotEmpty(), createFileWhenEmpty
                        )
                    ) {
                        val uri = createDocument(
                            context,
                            treeUri,
                            mimeType(format),
                            LogExport.fileName(format, exportThrough)
                        )
                        writeNewFile(context, uri, format, records)
                    }
                }

                AutoLogExportMode.APPEND -> {
                    val fileName = LogExport.appendFileName(format)
                    val existingUri = findDocument(context, treeUri, fileName)
                    val after = lastAutomaticExport.takeIf { existingUri != null }
                    if (format == LogExportFormat.JSON) {
                        val uri = existingUri ?: createDocument(
                            context, treeUri, mimeType(format), fileName
                        )
                        val database = LogDatabase(context.applicationContext)
                        try {
                            if (after == null || after < exportThrough) {
                                val start = (after ?: -1L) + 1
                                database.readHistory(
                                    historyQuery(
                                        HistoryRangeState(
                                            start.coerceAtLeast(0), exportThrough + 1
                                        )
                                    )
                                ) { rows ->
                                    LogExport.appendJson(
                                        context, uri, rows.map { it.record })
                                }
                            } else LogExport.appendJson(context, uri, emptySequence())
                        } finally {
                            database.close()
                        }
                    } else {
                        val records = LogExport.loadRecords(
                            context, afterExclusive = after, throughInclusive = exportThrough
                        )
                        if (records.isNotEmpty() || existingUri == null) {
                            val uri = existingUri ?: createDocument(
                                context, treeUri, mimeType(format), fileName
                            )
                            appendToFile(context, uri, format, records)
                        }
                    }
                }
            }
            LogExport.withWatermarkLock(context) {
                check(preferences.edit().apply {
                    putLong(SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME, exportThrough)
                }.commit()) { "Could not save automatic export watermark" }
            }
        }

    private fun writeNewFile(
        context: Context, uri: Uri, format: LogExportFormat, records: List<LogRecord>
    ) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            write(context, output, format, records)
        } ?: error("Could not open automatic export file")
    }

    private fun appendToFile(
        context: Context, uri: Uri, format: LogExportFormat, records: List<LogRecord>
    ) {
        when (format) {
            LogExportFormat.CSV -> LogExport.appendCsv(context, uri, records.asSequence())
            LogExportFormat.JSON -> LogExport.appendJson(context, uri, records.asSequence())
        }
    }

    private fun write(
        context: Context, output: OutputStream, format: LogExportFormat, records: List<LogRecord>
    ) {
        when (format) {
            LogExportFormat.CSV -> LogExport.writeCsv(
                context, output, records, includeHeader = true
            )

            LogExportFormat.JSON -> output.write(
                DeviceDataBackup.exportLogsToJson(context, records).toString()
                    .toByteArray(StandardCharsets.UTF_8)
            )
        }
    }

    private fun findDocument(context: Context, treeUri: Uri, displayName: String): Uri? {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        resolver.query(
            childrenUri, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ), null, null, null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            )
            val nameColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == displayName) {
                    return DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, cursor.getString(idColumn)
                    )
                }
            }
        }
        return null
    }

    private fun createDocument(
        context: Context, treeUri: Uri, mimeType: String, displayName: String
    ): Uri {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        return requireNotNull(
            DocumentsContract.createDocument(
                context.contentResolver, parentUri, mimeType, displayName
            )
        ) { "Could not create automatic export file" }
    }

    private fun mimeType(format: LogExportFormat): String = when (format) {
        LogExportFormat.CSV -> "text/csv"
        LogExportFormat.JSON -> "application/json"
    }
}
