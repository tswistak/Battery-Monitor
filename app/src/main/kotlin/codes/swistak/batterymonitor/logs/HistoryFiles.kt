/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.logs

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import codes.swistak.batterymonitor.devicebackup.BackupExportOutputStream
import codes.swistak.batterymonitor.devicebackup.DeviceDataBackup
import codes.swistak.batterymonitor.devicebackup.writeStagedBackupExport
import codes.swistak.batterymonitor.settings.SettingsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class HistoryExportRequest(
    val after: Long?,
    val through: Long,
    val format: LogExportFormat,
    val append: Boolean = false,
    val advanceWatermark: Boolean = false
)

internal inline fun completeHistoryExport(
    request: HistoryExportRequest, write: () -> HistoryKey?, saveWatermark: (HistoryKey) -> Unit
) {
    val lastWritten = write()
    if (request.advanceWatermark && lastWritten != null) saveWatermark(lastWritten)
}

internal fun SharedPreferences.exportWatermarkId(
    after: Long?, timeKey: String, idKey: String
): Long? =
    if (after != null && contains(timeKey) && getLong(timeKey, 0) == after && contains(idKey)) {
        getLong(idKey, 0)
    } else null

@SuppressLint("UseKtx")
internal fun SharedPreferences.saveExportWatermark(
    timeKey: String, idKey: String, written: HistoryKey
) {
    val previous = getLong(timeKey, Long.MIN_VALUE)
    if (written.time < previous || written.time == previous && written.id <= getLong(
            idKey, Long.MIN_VALUE
        )
    ) return
    check(edit().putLong(timeKey, written.time).putLong(idKey, written.id).commit()) {
        "Could not save export watermark"
    }
}

internal suspend fun exportHistory(context: Context, uri: Uri, request: HistoryExportRequest) =
    withContext(Dispatchers.IO) {
        LogExport.withDocumentLock(context) {
            val preferences = context.getSharedPreferences(
                SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
            )
            val timeKey = SettingsContract.KEY_LAST_LOG_EXPORT_TIME
            val idKey = SettingsContract.KEY_LAST_LOG_EXPORT_ID
            val afterId = if (request.advanceWatermark) preferences.exportWatermarkId(
                request.after, timeKey, idKey
            ) else null
            val database = LogDatabase(context.applicationContext)
            try {
                completeHistoryExport(request, write = {
                    var lastWritten: HistoryKey? = null
                    database.readExportRecords(request.after, request.through, afterId) { rows ->
                        val records = rows.map {
                            lastWritten = it.key
                            it.record
                        }
                        if (request.append) {
                            when (request.format) {
                                LogExportFormat.CSV -> LogExport.appendCsv(context, uri, records)
                                LogExportFormat.JSON -> LogExport.appendJson(context, uri, records)
                            }
                        } else {
                            writeStagedBackupExport(context, { uri }) { output ->
                                when (request.format) {
                                    LogExportFormat.CSV -> LogExport.writeCsv(
                                        context,
                                        BackupExportOutputStream(output),
                                        records,
                                        includeHeader = true
                                    )

                                    LogExportFormat.JSON -> DeviceDataBackup.writeHistory(
                                        context, output, records
                                    )
                                }
                            }
                        }
                    }
                    lastWritten
                }, saveWatermark = { lastWritten ->
                    LogExport.withWatermarkLock(context) {
                        preferences.saveExportWatermark(timeKey, idKey, lastWritten)
                    }
                })
            } finally {
                database.close()
            }
        }
    }
