/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.logs

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class LogsRepository(context: Context) {
    private val appContext = context.applicationContext

    suspend fun chart(range: HistoryRangeState): HistoryChartModel = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        database {
            readHistory(historyQuery(range)) {
                historyChart(
                    range, it.onEach { coroutine.ensureActive() })
            }
        }
    }

    suspend fun page(
        range: HistoryRangeState,
        filters: Set<String>?,
        ascending: Boolean,
        anchor: HistoryKey?,
        backwards: Boolean = false,
        includeAnchor: Boolean = false,
        revealedId: Long? = null
    ): List<HistoryRecord> = withContext(Dispatchers.IO) {
        database {
            readHistory(
                historyQuery(
                    range, filters, ascending != backwards, anchor, 129, includeAnchor, revealedId
                )
            ) { it.toList() }
        }
    }

    suspend fun hasRecordsBefore(
        range: HistoryRangeState,
        filters: Set<String>,
        ascending: Boolean,
        key: HistoryKey,
        revealedId: Long?
    ): Boolean = withContext(Dispatchers.IO) {
        database {
            readHistory(historyQuery(range, filters, !ascending, key, 1, revealedId = revealedId)) {
                it.any()
            }
        }
    }

    suspend fun duration(record: HistoryRecord): Long? = withContext(Dispatchers.IO) {
        database { historyDuration(record) }
    }

    suspend fun delete(range: HistoryRangeState?) = withContext(Dispatchers.IO) {
        check(
            AutoLogExporter.exportBeforeManualLogClearing(
                appContext
            )
        ) {
            "Automatic export failed; history was retained"
        }
        database { deleteHistory(range) }
    }

    private inline fun <T> database(block: LogDatabase.() -> T): T = LogDatabase(appContext).run {
        try {
            block()
        } finally {
            close()
        }
    }

    suspend fun read(
        afterExclusive: Long? = null, throughInclusive: Long? = null
    ): List<LogRecord> = withContext(Dispatchers.IO) {
        LogDatabase(appContext).run {
            try {
                getLogRecordsInRange(afterExclusive, throughInclusive)
            } finally {
                close()
            }
        }
    }
}
