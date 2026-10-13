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

    suspend fun detailedChart(
        range: HistoryRangeState,
        metric: HistoryMetric,
        viewport: HistoryRangeState = range,
        pixelWidth: Int
    ): DetailedHistoryChartModel = detailedCharts(
        range, listOf(metric), viewport, pixelWidth
    ).getValue(metric)

    suspend fun detailedCharts(
        range: HistoryRangeState,
        metrics: List<HistoryMetric>,
        viewport: HistoryRangeState = range,
        pixelWidth: Int
    ): Map<HistoryMetric, DetailedHistoryChartModel> = withContext(Dispatchers.IO) {
        require(viewport.start >= range.start && viewport.end <= range.end)
        val coroutine = currentCoroutineContext()
        database {
            val before = if (viewport.start > range.start) readHistory(
                historyQuery(
                    HistoryRangeState(range.start, viewport.start), ascending = false, limit = 1
                )
            ) { it.firstOrNull() } else null
            coroutine.ensureActive()
            val after = if (viewport.end < range.end) readHistory(
                historyQuery(HistoryRangeState(viewport.end, range.end), limit = 1)
            ) { it.firstOrNull() } else null
            coroutine.ensureActive()
            readHistory(historyQuery(viewport)) { visible ->
                detailedHistoryCharts(range, viewport, metrics, pixelWidth, sequence {
                    before?.let { yield(it) }
                    yieldAll(visible)
                    after?.let { yield(it) }
                }.onEach { coroutine.ensureActive() })
            }
        }
    }

    suspend fun detailedBounds(
        range: HistoryRangeState, metric: HistoryMetric
    ): DetailedHistoryBounds = detailedBounds(range, listOf(metric)).getValue(metric)

    suspend fun detailedBounds(
        range: HistoryRangeState, metrics: List<HistoryMetric>
    ): Map<HistoryMetric, DetailedHistoryBounds> = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        database {
            readHistory(historyQuery(range)) {
                detailedHistoryBounds(metrics, it.onEach { coroutine.ensureActive() })
            }
        }
    }

    suspend fun historyRange(): HistoryRangeState? = withContext(Dispatchers.IO) {
        database { retainedHistoryRange() }
    }

    suspend fun detailedSelection(key: HistoryKey): HistoryRecord? = withContext(Dispatchers.IO) {
        database {
            readHistory(
                HistoryQuery(
                    "SELECT _id, status, charge, time, temperature, voltage, current, remaining_charge_uah " + "FROM logs WHERE _id = ? AND time = ?",
                    arrayOf(key.id.toString(), key.time.toString())
                )
            ) { it.firstOrNull() }
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
