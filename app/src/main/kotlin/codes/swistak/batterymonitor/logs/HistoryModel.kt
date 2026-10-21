/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.logs

import codes.swistak.batterymonitor.monitoring.BatteryCurrent
import codes.swistak.batterymonitor.monitoring.batteryvoltage.BatteryVoltageValidator
import java.time.LocalDate
import java.time.ZoneId

internal data class HistoryRangeState(val start: Long, val end: Long) {
    init {
        require(start in 0..<end)
    }

    val exportAfter: Long get() = start - 1
    val exportThrough: Long get() = if (end == Long.MAX_VALUE) end else end - 1
    val endOperator: String get() = if (end == Long.MAX_VALUE) "<=" else "<"

    fun contains(time: Long): Boolean = time >= start && (time < end || end == Long.MAX_VALUE)

    companion object {
        fun lastHours(hours: Int, now: Long = System.currentTimeMillis()) =
            HistoryRangeState((now + 1 - hours * 3_600_000L).coerceAtLeast(0), now + 1)

        fun days(first: LocalDate, last: LocalDate, zone: ZoneId): HistoryRangeState {
            require(!last.isBefore(first))
            return HistoryRangeState(
                first.atStartOfDay(zone).toInstant().toEpochMilli(),
                last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            )
        }
    }
}


internal data class HistoryKey(val time: Long, val id: Long)
internal data class HistoryChanges(val revision: Long, val affectsRange: Boolean)

internal data class HistoryRecord(val id: Long, val record: LogRecord) {
    val key get() = HistoryKey(record.time, id)
}

internal val historyFilterKeys = listOf(
    "plugged_in",
    "unplugged",
    "charging",
    "discharging",
    "not_charging",
    "fully_charged",
    "boot_completed",
    "unknown",
    "battery_measurement"
)

internal fun historyFilterKey(code: Int): String? {
    val decoded = LogDatabase.decodeStatus(code)
    if (decoded[2] == LogDatabase.STATUS_SAMPLE) return "battery_measurement"
    val status = decoded[0]
    return when {
        status == -1 -> "boot_completed"
        status == 5 -> "fully_charged"
        status == 4 -> "not_charging"
        status == 1 || status == 3 || status > 5 -> "unknown"
        status == 0 -> when (decoded[2]) {
            0 -> "unplugged"
            1 -> "discharging"
            else -> null
        }

        status == 2 -> when (decoded[2]) {
            0 -> "plugged_in"
            1 -> "charging"
            else -> null
        }

        else -> null
    }
}


internal fun historyExclusiveEnd(time: Long): Long = if (time == Long.MAX_VALUE) time else time + 1

internal fun historyEventCode(status: Int): Int? = when (historyFilterKey(status)) {
    "plugged_in" -> HistoryEvent.CONNECTED
    "unplugged" -> HistoryEvent.DISCONNECTED
    "boot_completed" -> HistoryEvent.REBOOT
    else -> null
}

internal fun historyObservationValue(metric: HistoryMetric, record: LogRecord): Double? {
    if (record.status == LogDatabase.STATUS_BOOT_COMPLETED) return null
    val voltage = record.voltage?.takeIf(BatteryVoltageValidator::isValidBroadcastMillivolts)
    return metric.value(record.copy(voltage = voltage))?.takeIf(Double::isFinite)
}

internal enum class HistoryMetric {
    LEVEL, TEMPERATURE, VOLTAGE, CURRENT, POWER, REMAINING_CHARGE;

    fun value(record: LogRecord): Double? = when (this) {
        LEVEL -> record.charge?.takeIf { it in 0..100 }?.toDouble()
        TEMPERATURE -> record.temperature?.div(10.0)
        VOLTAGE -> record.voltage?.div(1000.0)
        CURRENT -> record.currentMicroAmps?.div(1000.0)
        POWER -> BatteryCurrent.powerWatts(record.voltage, record.currentMicroAmps?.div(1000.0))
        REMAINING_CHARGE -> record.remainingChargeMicroampHours?.div(1000.0)
    }
}

internal data class HistoryPoint(val key: HistoryKey, val value: Double?, val segment: Long = 0)
internal data class HistoryEvent(val first: Long, val last: Long, val count: Long, val code: Int) {
    companion object {
        const val CONNECTED = 2
        const val DISCONNECTED = 0
        const val REBOOT = LogDatabase.STATUS_BOOT_COMPLETED

        private const val CONNECTED_INDEX = 0
        private const val DISCONNECTED_INDEX = 1
        private const val REBOOT_INDEX = 2
        const val TYPE_COUNT = 3

        fun indexFor(code: Int?): Int? = when (code) {
            CONNECTED -> CONNECTED_INDEX
            DISCONNECTED -> DISCONNECTED_INDEX
            REBOOT -> REBOOT_INDEX
            else -> null
        }
    }
}

internal data class HistorySeries(
    val points: List<HistoryPoint>, val min: Double?, val max: Double?
)

internal data class HistoryChartModel(
    val range: HistoryRangeState,
    val count: Long,
    val series: Map<HistoryMetric, HistorySeries>,
    val events: List<HistoryEvent>
)

internal fun historyChart(
    range: HistoryRangeState, records: Sequence<HistoryRecord>, selectedKey: HistoryKey? = null
): HistoryChartModel {
    class Bucket {
        var selected: HistoryPoint? = null

        var first: HistoryPoint? = null
        var last: HistoryPoint? = null
        var min: HistoryPoint? = null
        var max: HistoryPoint? = null
        var missing: HistoryPoint? = null
        val eventFirst = arrayOfNulls<HistoryPoint>(HistoryEvent.TYPE_COUNT)
        val eventLast = arrayOfNulls<HistoryPoint>(HistoryEvent.TYPE_COUNT)

        fun add(point: HistoryPoint, event: Int?) {
            if (event != null) {
                eventFirst[event] = eventFirst[event] ?: point
                eventLast[event] = point
            }
            if (point.value == null) {
                missing = missing ?: point
                return
            }
            first = first ?: point
            last = point
            if (min == null || point.value < min!!.value!!) min = point
            if (max == null || point.value > max!!.value!!) max = point
        }

        fun points() = (listOfNotNull(
            first, min, max, last, missing, selected
        ) + eventFirst.filterNotNull() + eventLast.filterNotNull()).distinctBy { it.key }
    }

    val buckets = HistoryMetric.entries.associateWith { Array(42) { Bucket() } }
    val events = mutableMapOf<Pair<Int, Int>, HistoryEvent>()
    val segments = LongArray(HistoryMetric.entries.size)
    var count = 0L
    for (entry in records) {
        val record = entry.record
        if (!range.contains(record.time)) continue
        count++
        val bucket =
            (((record.time - range.start).toDouble() / (range.end - range.start)) * 42).toInt()
                .coerceIn(0, 41)
        val eventCode = historyEventCode(record.status)
        val event = HistoryEvent.indexFor(eventCode)
        for (metric in HistoryMetric.entries) {
            val value = historyObservationValue(metric, record)
            if (value == null) segments[metric.ordinal]++
            val point = HistoryPoint(entry.key, value, segments[metric.ordinal])
            buckets.getValue(metric)[bucket].apply {
                add(point, event)
                if (entry.key == selectedKey) selected = point
            }
        }
        if (eventCode != null) {
            val key = bucket to eventCode
            val old = events[key]
            events[key] = HistoryEvent(
                old?.first ?: record.time, record.time, (old?.count ?: 0) + 1, eventCode
            )
        }
    }
    return HistoryChartModel(range, count, buckets.mapValues { (_, values) ->
        val points =
            values.flatMap { it.points() }.sortedWith(compareBy({ it.key.time }, { it.key.id }))
        HistorySeries(
            points,
            points.mapNotNull { it.value }.minOrNull(),
            points.mapNotNull { it.value }.maxOrNull()
        )
    }, events.values.sortedBy { it.first })
}


internal data class HistoryQuery(val sql: String, val args: Array<String>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HistoryQuery

        if (sql != other.sql) return false
        if (!args.contentEquals(other.args)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sql.hashCode()
        result = 31 * result + args.contentHashCode()
        return result
    }
}

internal fun historyQuery(
    range: HistoryRangeState,
    filters: Set<String>? = null,
    ascending: Boolean = true,
    anchor: HistoryKey? = null,
    limit: Int? = null,
    includeAnchor: Boolean = false,
    revealedId: Long? = null
): HistoryQuery {
    val clauses = listOf(
        "(status >= 0 AND status % 10 = 2 AND status < 100)",
        "(status >= 0 AND status % 10 = 0 AND status < 100)",
        "(status >= 100 AND status < 200 AND status % 10 = 2)",
        "(status >= 100 AND status < 200 AND status % 10 = 0)",
        "(status >= 0 AND status < 200 AND status % 10 = 4)",
        "(status >= 0 AND status < 200 AND status % 10 = 5)",
        "status = -1",
        "(status >= 0 AND status < 200 AND (status % 10 IN (1,3,6,7,8,9)))",
        "(status >= 200 AND status < 300)"
    )
    val selected =
        historyFilterKeys.indices.filter { filters?.contains(historyFilterKeys[it]) == true }
            .map { clauses[it] }
    val args = mutableListOf(range.start.toString(), range.end.toString())
    val where = mutableListOf("time >= ?", "time ${range.endOperator} ?")
    if (filters != null) {
        val selection = selected.joinToString(" OR ").ifEmpty { "0" }
        where += if (revealedId == null) "($selection)" else "($selection OR _id = ?)"
        revealedId?.let { args += it.toString() }
    }
    if (anchor != null) {
        val op = if (ascending) ">" else "<"
        val idOp = if (includeAnchor) "$op=" else op
        where += "(time $op ? OR (time = ? AND _id $idOp ?))"
        args += listOf(anchor.time.toString(), anchor.time.toString(), anchor.id.toString())
    }
    val order = if (ascending) "ASC" else "DESC"
    require(limit == null || limit in 1..129)
    return HistoryQuery(
        "SELECT _id, status, charge, time, temperature, voltage, current, remaining_charge_uah FROM logs WHERE ${
            where.joinToString(
                " AND "
            )
        } ORDER BY time $order, _id $order" + (limit?.let { " LIMIT $it" } ?: ""),
        args.toTypedArray())
}
