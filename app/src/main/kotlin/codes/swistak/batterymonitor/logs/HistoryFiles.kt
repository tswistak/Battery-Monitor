/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.logs

import android.content.Context
import android.net.Uri
import codes.swistak.batterymonitor.devicebackup.DeviceDataBackup
import codes.swistak.batterymonitor.settings.SettingsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class HistoryExportRequest(
    val after: Long?,
    val through: Long,
    val format: LogExportFormat,
    val append: Boolean = false,
    val advanceWatermark: Boolean = false
) {
    init {
        require(!append || format == LogExportFormat.CSV)
    }
}

internal inline fun completeHistoryExport(
    request: HistoryExportRequest, write: () -> Unit, saveWatermark: (Long) -> Unit
) {
    write()
    if (request.advanceWatermark) saveWatermark(request.through)
}

internal suspend fun exportHistory(context: Context, uri: Uri, request: HistoryExportRequest) =
    withContext(Dispatchers.IO) {
        val database = LogDatabase(context.applicationContext)
        try {
            completeHistoryExport(request, write = {
                val start = (request.after ?: -1L) + 1
                val records = if (start <= request.through) {
                    historyQuery(HistoryRangeState(start, request.through + 1))
                } else null

                fun write(sequence: Sequence<HistoryRecord>) {
                    if (request.append) {
                        LogExport.appendCsv(context, uri, sequence.map { it.record })
                        return
                    }
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        when (request.format) {
                            LogExportFormat.CSV -> LogExport.writeCsv(
                                context, output, sequence.map { it.record }, includeHeader = true
                            )

                            LogExportFormat.JSON -> output.write(
                                DeviceDataBackup.exportLogsToJson(
                                    context, sequence.map { it.record }.toList()
                                ).toString().toByteArray(Charsets.UTF_8)
                            )
                        }
                    } ?: error("Could not open export file")
                }
                if (records == null) write(emptySequence()) else database.readHistory(
                    records, ::write
                )
            }, saveWatermark = { through ->
                val preferences = context.getSharedPreferences(
                    SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
                )
                val old = preferences.getLong(SettingsContract.KEY_LAST_LOG_EXPORT_TIME, 0)
                check(
                    preferences.edit().putLong(
                        SettingsContract.KEY_LAST_LOG_EXPORT_TIME, maxOf(old, through)
                    ).commit()
                ) { "Could not save export watermark" }
            })
        } finally {
            database.close()
        }
    }
