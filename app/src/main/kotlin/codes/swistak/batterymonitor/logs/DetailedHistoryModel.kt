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

import kotlin.math.roundToLong

internal data class DetailedHistoryEvent(
    val key: HistoryKey, val last: Long, val count: Long, val code: Int
)

internal data class DetailedHistoryBounds(val count: Long, val min: Double?, val max: Double?)

internal data class DetailedHistoryChartModel(
    val range: HistoryRangeState,
    val viewport: HistoryRangeState,
    val metric: HistoryMetric,
    val count: Long,
    val points: List<HistoryPoint>,
    val events: List<DetailedHistoryEvent>,
    val min: Double?,
    val max: Double?,
    val raw: Boolean
)

internal fun detailedHistoryCoordinateRange(
    range: HistoryRangeState, viewport: HistoryRangeState
): HistoryRangeState {
    require(viewport.start >= range.start && viewport.end <= range.end)
    val span = viewport.end - viewport.start
    val padding = if (span > Long.MAX_VALUE / 2) Long.MAX_VALUE else span * 2
    val before = minOf(padding, viewport.start - range.start)
    val after = minOf(padding, range.end - viewport.end)
    // A nearby origin keeps float chart coordinates precise when inspecting decades of history.
    return HistoryRangeState(viewport.start - before, viewport.end + after)
}

internal class DetailedHistoryTimeAxis(private val range: HistoryRangeState) {
    fun x(time: Long): Float = ((time - range.start).toDouble() / 1000.0).toFloat()

    // Selected observations keep their original HistoryKey; this inverse is only for the viewport.
    fun time(x: Float): Long {
        if (!x.isFinite()) return range.start
        val offset = (x.toDouble() * 1000.0).coerceIn(0.0, (range.end - range.start).toDouble())
        return range.start + offset.roundToLong().coerceAtMost(range.end - range.start)
    }
}

internal fun detailedHistoryBounds(
    metric: HistoryMetric, records: Sequence<HistoryRecord>
): DetailedHistoryBounds = detailedHistoryBounds(listOf(metric), records).getValue(metric)

internal fun detailedHistoryBounds(
    metrics: List<HistoryMetric>, records: Sequence<HistoryRecord>
): Map<HistoryMetric, DetailedHistoryBounds> {
    require(metrics.size in 1..2 && metrics.distinct().size == metrics.size)
    val minima = arrayOfNulls<Double>(metrics.size)
    val maxima = arrayOfNulls<Double>(metrics.size)
    var count = 0L
    for ((_, record) in records) {
        count++
        metrics.forEachIndexed { index, metric ->
            historyObservationValue(metric, record)?.let { value ->
                minima[index] = minima[index]?.let { minOf(it, value) } ?: value
                maxima[index] = maxima[index]?.let { maxOf(it, value) } ?: value
            }
        }
    }
    return metrics.withIndex().associate { (index, metric) ->
        metric to DetailedHistoryBounds(count, minima[index], maxima[index])
    }
}

internal fun detailedHistoryChart(
    range: HistoryRangeState,
    viewport: HistoryRangeState,
    metric: HistoryMetric,
    pixelWidth: Int,
    records: Sequence<HistoryRecord>
): DetailedHistoryChartModel = detailedHistoryCharts(
    range, viewport, listOf(metric), pixelWidth, records
).getValue(metric)

internal fun detailedHistoryCharts(
    range: HistoryRangeState,
    viewport: HistoryRangeState,
    metrics: List<HistoryMetric>,
    pixelWidth: Int,
    records: Sequence<HistoryRecord>
): Map<HistoryMetric, DetailedHistoryChartModel> {
    require(viewport.start >= range.start && viewport.end <= range.end)
    require(metrics.size in 1..2 && metrics.distinct().size == metrics.size)
    val reducers = metrics.associateWith { DetailedHistoryReducer(range, viewport, it, pixelWidth) }
    for (entry in records) {
        if (range.contains(entry.record.time)) reducers.values.forEach { it.add(entry) }
    }
    return reducers.mapValues { (_, reducer) -> reducer.model() }
}

private class DetailedHistoryReducer(
    private val range: HistoryRangeState,
    private val viewport: HistoryRangeState,
    private val metric: HistoryMetric,
    pixelWidth: Int
) {
    private val width = pixelWidth.coerceIn(1, 4096)
    private val bucketCount = (width / 3).coerceAtLeast(1)
    private val rawLimit = maxOf(256, width * 2)

    class Bucket {
        var first: HistoryPoint? = null
        var last: HistoryPoint? = null
        var minimum: HistoryPoint? = null
        var maximum: HistoryPoint? = null
        var missing: HistoryPoint? = null
        val eventFirst = arrayOfNulls<HistoryPoint>(HistoryEvent.TYPE_COUNT)
        val eventLast = arrayOfNulls<HistoryPoint>(HistoryEvent.TYPE_COUNT)
        val beforeEventFirst = arrayOfNulls<HistoryPoint>(HistoryEvent.TYPE_COUNT)
        val beforeEventLast = arrayOfNulls<HistoryPoint>(HistoryEvent.TYPE_COUNT)

        fun add(point: HistoryPoint, event: Int?, previous: HistoryPoint?) {
            first = first ?: point
            last = point
            if (event != null) {
                if (eventFirst[event] == null) {
                    eventFirst[event] = point
                    beforeEventFirst[event] = previous
                }
                eventLast[event] = point
                beforeEventLast[event] = previous
            }
            val value = point.value
            if (value == null) {
                missing = missing ?: point
            } else {
                if (minimum == null || value < minimum!!.value!!) minimum = point
                if (maximum == null || value > maximum!!.value!!) maximum = point
            }
        }

        fun points() = listOfNotNull(
            first, minimum, maximum, last, missing
        ) + eventFirst.filterNotNull() + eventLast.filterNotNull() + beforeEventFirst.filterNotNull() + beforeEventLast.filterNotNull()
    }

    private val buckets = Array(bucketCount) { Bucket() }
    private val events = mutableMapOf<Pair<Int, Int>, DetailedHistoryEvent>()
    private val observations = ArrayList<HistoryPoint>(rawLimit + 2)
    private var raw = true
    private var count = 0L
    private var minimum: Double? = null
    private var maximum: Double? = null
    private var segment = 0L
    private var previous: HistoryPoint? = null
    fun add(entry: HistoryRecord) {
        val record = entry.record
        val visible = viewport.contains(record.time)
        val value = historyObservationValue(metric, record)
        if (value == null) segment++
        val point = HistoryPoint(entry.key, value, segment)
        val eventCode = historyEventCode(record.status)
        val eventIndex = HistoryEvent.indexFor(eventCode)
        val bucket =
            (((record.time - viewport.start).toDouble() / (viewport.end - viewport.start)) * bucketCount).toInt()
                .coerceIn(0, bucketCount - 1)
        buckets[bucket].add(point, eventIndex, previous)
        if (raw) {
            observations += point
            if (observations.size > rawLimit + 2) {
                observations.clear()
                raw = false
            }
        }
        if (visible) {
            count++
            if (value != null) {
                minimum = minimum?.let { minOf(it, value) } ?: value
                maximum = maximum?.let { maxOf(it, value) } ?: value
            }
            if (eventCode != null) {
                val key = bucket to eventCode
                val old = events[key]
                events[key] = DetailedHistoryEvent(
                    old?.key ?: entry.key, record.time, (old?.count ?: 0) + 1, eventCode
                )
            }
        }
        previous = point
    }

    fun model(): DetailedHistoryChartModel {
        val points =
            if (raw) observations else buckets.flatMap { it.points() }.distinctBy { it.key }
                .sortedWith(compareBy({ it.key.time }, { it.key.id }))
        return DetailedHistoryChartModel(
            range, viewport, metric, count, points, events.values.sortedWith(
                compareBy({ it.key.time }, { it.key.id })
            ), minimum, maximum, raw
        )
    }
}
