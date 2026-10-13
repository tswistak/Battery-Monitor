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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DetailedHistoryModelTest {
    private val observation = LogRecord(200, 50, 1_000, 300, 4000, -100_000, 2_500_000)

    private fun rows(count: Int) = (0..<count).asSequence().map { index ->
        HistoryRecord(index + 1L, observation.copy(time = 1_000 + index * 120_000L))
    }

    @Test
    fun narrow_views_keep_every_observation_and_duplicate_timestamp_identity() {
        val range = HistoryRangeState(1_000, 901_000)
        val records = rows(8).toMutableList()
        records.add(1, HistoryRecord(99, records.first().record))
        val model =
            detailedHistoryChart(range, range, HistoryMetric.CURRENT, 800, records.asSequence())
        assertTrue(model.raw)
        assertEquals(records.map { it.key }, model.points.map { it.key })
        assertEquals(9L, model.count)
        assertEquals(-100.0, model.min!!, 0.0)
        assertEquals(-100.0, model.max!!, 0.0)
        assertTrue(model.events.isEmpty())
    }

    @Test
    fun zoom_queries_reveal_real_observations_omitted_from_the_wide_view() {
        val range = HistoryRangeState(0, 100_000_000)
        val wide = detailedHistoryChart(range, range, HistoryMetric.LEVEL, 12, rows(600))
        val viewport = HistoryRangeState(30_001_000, 30_901_000)
        val actual = rows(600).filter { it.record.time in viewport.start..<viewport.end }.toList()
        val narrow =
            detailedHistoryChart(range, viewport, HistoryMetric.LEVEL, 12, actual.asSequence())
        assertFalse(wide.raw)
        assertTrue(narrow.raw)
        assertEquals(actual.map { it.key }, narrow.points.map { it.key })
        assertTrue(narrow.points.any { narrowPoint -> wide.points.none { it.key == narrowPoint.key } })
    }

    @Test
    fun downsampling_keeps_spikes_troughs_boundaries_and_transition_neighbors() {
        val range = HistoryRangeState(0, 100_000_000)
        val records = rows(600).mapIndexed { index, entry ->
            entry.copy(
                record = entry.record.copy(
                    currentMicroAmps = when (index) {
                        100 -> 2_000_000
                        101 -> -3_000_000
                        else -> -100_000
                    }, status = when (index) {
                        300 -> 12
                        400 -> 0
                        else -> 200
                    }
                )
            )
        }.toList()
        val model =
            detailedHistoryChart(range, range, HistoryMetric.CURRENT, 12, records.asSequence())
        val ids = model.points.map { it.key.id }
        assertTrue(ids.containsAll(listOf(1L, 101L, 102L, 300L, 301L, 400L, 401L, 600L)))
        assertEquals(-3000.0, model.min!!, 0.0)
        assertEquals(2000.0, model.max!!, 0.0)
        assertEquals(listOf(2, 0), model.events.map { it.code })
        assertEquals(ids.distinct(), ids)
        assertEquals(
            model.points.sortedWith(compareBy({ it.key.time }, { it.key.id })), model.points
        )
    }

    @Test
    fun nulls_and_boots_break_segments_even_when_the_gap_is_downsampled() {
        val range = HistoryRangeState(0, 100_000_000)
        val records = rows(600).mapIndexed { index, entry ->
            entry.copy(
                record = when (index) {
                    100 -> entry.record.copy(voltage = null)
                    300 -> entry.record.copy(status = -1)
                    else -> entry.record
                }
            )
        }.toList()
        val model =
            detailedHistoryChart(range, range, HistoryMetric.VOLTAGE, 3, records.asSequence())
        val valid = model.points.filter { it.value != null }
        assertNotEquals(valid.first().segment, valid.last().segment)
        assertTrue(model.points.any { it.value == null })
        assertEquals(-1, model.events.single().code)
        assertTrue(valid.zipWithNext().any { (first, second) -> first.segment != second.segment })
    }

    @Test
    fun irregular_periodic_observations_remain_connected_without_fabricating_samples() {
        val range = HistoryRangeState(0, 2_000_000)
        val records = listOf(
            HistoryRecord(1, observation),
            HistoryRecord(2, observation.copy(time = 121_000)),
            HistoryRecord(3, observation.copy(time = 1_000_000))
        )
        val model =
            detailedHistoryChart(range, range, HistoryMetric.LEVEL, 800, records.asSequence())
        assertEquals(3, model.points.size)
        assertEquals(model.points[0].segment, model.points[1].segment)
        assertEquals(model.points[1].segment, model.points[2].segment)
        assertTrue(model.events.isEmpty())
        val legacy = records.map { it.copy(record = it.record.copy(status = 100)) }
        val oldModel =
            detailedHistoryChart(range, range, HistoryMetric.LEVEL, 800, legacy.asSequence())
        assertEquals(1, oldModel.points.map { it.segment }.distinct().size)
        assertTrue(oldModel.events.isEmpty())
    }

    @Test
    fun legacy_sparse_readings_and_mixed_periodic_events_match_compact_continuity() {
        val range = HistoryRangeState(0, 10_000_000)
        val legacy = listOf(
            HistoryRecord(1, observation.copy(time = 1, status = 100)),
            HistoryRecord(2, observation.copy(time = 1_000_000, status = 100))
        )
        val mixed = listOf(200, 100, 200, 100, 100, 22).mapIndexed { index, status ->
            HistoryRecord(
                index + 1L, observation.copy(time = 1 + index * 1_000_000L, status = status)
            )
        }
        for (records in listOf(
            legacy, legacy + HistoryRecord(
                3, observation.copy(time = 9_000_000, status = 200)
            ), mixed
        )) {
            val compact = historyChart(range, records.asSequence())
            for (metric in HistoryMetric.entries) {
                val chart = detailedHistoryChart(range, range, metric, 800, records.asSequence())
                assertEquals(records.map { it.key }, chart.points.map { it.key })
                assertEquals(1, chart.points.map { it.segment }.distinct().size)
                assertEquals(
                    compact.series.getValue(metric).points.map { it.segment },
                    chart.points.map { it.segment })
                assertEquals(compact.events.map { it.code }, chart.events.map { it.code })
            }
        }
    }

    @Test
    fun irregular_real_device_samples_match_compact_segments_before_and_after_reduction() {
        val range = HistoryRangeState(0, 1_000_000_000)
        val records = (0..<800).map { index ->
            HistoryRecord(
                index + 1L, observation.copy(
                    time = 1_000 + index * 1_000_000L,
                    status = if (index == 400) -1 else if (index % 3 == 0) 100 else 200,
                    currentMicroAmps = if (index == 200) null else -100_000
                )
            )
        }
        val compact = historyChart(range, records.asSequence())
        for (width in listOf(3, 800)) for (metric in HistoryMetric.entries) {
            val chart = detailedHistoryChart(range, range, metric, width, records.asSequence())
            assertEquals(width == 800, chart.raw)
            assertEquals(800L, chart.count)
            assertEquals(listOf(-1), chart.events.map { it.code })
            assertEquals(1L, chart.events.sumOf { it.count })
            val expected = compact.series.getValue(metric).points.associateBy { it.key }
            for ((key, value, segment) in chart.points) expected[key]?.let { other ->
                assertEquals(other.segment, segment)
                assertEquals(other.value, value)
            }
            val validSegments =
                chart.points.filter { it.value != null }.map { it.segment }.distinct()
            assertEquals(
                if (metric in listOf(HistoryMetric.CURRENT, HistoryMetric.POWER)) 3 else 2,
                validSegments.size
            )
        }
        val missingCurrent = detailedHistoryCharts(
            range,
            range,
            listOf(HistoryMetric.LEVEL, HistoryMetric.CURRENT),
            800,
            records.asSequence().constrainOnce()
        )
        assertEquals(
            missingCurrent.getValue(HistoryMetric.LEVEL).count,
            missingCurrent.getValue(HistoryMetric.CURRENT).count
        )
        assertEquals(0L, missingCurrent.getValue(HistoryMetric.LEVEL).points[200].segment)
        assertEquals(1L, missingCurrent.getValue(HistoryMetric.CURRENT).points[200].segment)
    }

    @Test
    fun two_metrics_consume_one_stream_and_keep_independent_actual_units_and_extrema() {
        val range = HistoryRangeState(0, 1_000)
        val metrics = listOf(HistoryMetric.LEVEL, HistoryMetric.CURRENT)
        val records = listOf(
            HistoryRecord(1, observation.copy(time = 1, charge = 20, currentMicroAmps = 100_000)),
            HistoryRecord(2, observation.copy(time = 2, charge = 60, currentMicroAmps = null)),
            HistoryRecord(3, observation.copy(time = 3, charge = 80, currentMicroAmps = -500_000))
        )
        var chartReads = 0
        val charts = detailedHistoryCharts(
            range, range, metrics, 800, records.asSequence().onEach { chartReads++ }.constrainOnce()
        )
        var boundsReads = 0
        val bounds = detailedHistoryBounds(
            metrics, records.asSequence().onEach { boundsReads++ }.constrainOnce()
        )
        assertEquals(3, chartReads)
        assertEquals(3, boundsReads)
        assertEquals(metrics, charts.keys.toList())
        assertEquals(
            listOf(20.0, 60.0, 80.0), charts.getValue(HistoryMetric.LEVEL).points.map { it.value })
        assertEquals(
            listOf(100.0, null, -500.0),
            charts.getValue(HistoryMetric.CURRENT).points.map { it.value })
        assertEquals(20.0, bounds.getValue(HistoryMetric.LEVEL).min!!, 0.0)
        assertEquals(-500.0, bounds.getValue(HistoryMetric.CURRENT).min!!, 0.0)
        assertEquals(100.0, bounds.getValue(HistoryMetric.CURRENT).max!!, 0.0)
        assertEquals(
            1, charts.getValue(HistoryMetric.LEVEL).points.map { it.segment }.distinct().size
        )
        assertEquals(
            2, charts.getValue(HistoryMetric.CURRENT).points.map { it.segment }.distinct().size
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun detailed_pipeline_rejects_a_third_metric() {
        val range = HistoryRangeState(0, 1_000)
        detailedHistoryCharts(range, range, HistoryMetric.entries.take(3), 800, emptySequence())
    }

    @Test
    fun saturated_range_retains_the_last_representable_timestamp_without_overflow() {
        val range = HistoryRangeState(Long.MAX_VALUE - 1, Long.MAX_VALUE)
        val rows = sequenceOf(HistoryRecord(1, observation.copy(time = Long.MAX_VALUE)))
        val chart = detailedHistoryChart(range, range, HistoryMetric.LEVEL, 800, rows)
        assertEquals(1L, chart.count)
        assertEquals(Long.MAX_VALUE, chart.points.single().key.time)
        assertEquals(Long.MAX_VALUE, range.exportThrough)
        assertTrue(historyQuery(range).sql.contains("time <= ?"))
    }

    @Test
    fun viewport_statistics_exclude_guard_neighbors_and_end_is_exclusive() {
        val range = HistoryRangeState(0, 1_000)
        val viewport = HistoryRangeState(100, 200)
        val records = listOf(
            HistoryRecord(1, observation.copy(charge = 0, time = 99)),
            HistoryRecord(2, observation.copy(charge = 20, time = 100)),
            HistoryRecord(3, observation.copy(charge = 30, time = 199)),
            HistoryRecord(4, observation.copy(charge = 100, time = 200)),
            HistoryRecord(5, observation.copy(charge = 100, time = 1_000))
        )
        val model =
            detailedHistoryChart(range, viewport, HistoryMetric.LEVEL, 800, records.asSequence())
        assertEquals(2L, model.count)
        assertEquals(20.0, model.min!!, 0.0)
        assertEquals(30.0, model.max!!, 0.0)
        assertEquals(listOf(1L, 2L, 3L, 4L), model.points.map { it.key.id })
    }

    @Test
    fun bounds_and_chart_share_metric_units_validation_and_unavailable_values() {
        val range = HistoryRangeState(0, 1_000)
        val record = HistoryRecord(1, observation.copy(time = 1))
        val expected = listOf(50.0, 30.0, 4.0, -100.0, -0.4, 2500.0)
        for ((index, metric) in HistoryMetric.entries.withIndex()) {
            val bounds = detailedHistoryBounds(metric, sequenceOf(record))
            val chart = detailedHistoryChart(range, range, metric, 800, sequenceOf(record))
            assertEquals(expected[index], bounds.min!!, 0.0001)
            assertEquals(bounds.min, chart.min)
        }
        for (metric in listOf(HistoryMetric.VOLTAGE, HistoryMetric.POWER)) {
            val invalid = record.copy(record = record.record.copy(voltage = -1))
            assertNull(detailedHistoryBounds(metric, sequenceOf(invalid)).min)
            assertNull(
                detailedHistoryChart(
                    range, range, metric, 800, sequenceOf(invalid)
                ).points.single().value
            )
        }
        val boot = record.copy(record = record.record.copy(status = -1))
        assertNull(detailedHistoryBounds(HistoryMetric.LEVEL, sequenceOf(boot)).min)
    }

    @Test
    fun rendering_memory_is_bounded_by_pixels_even_with_many_events_and_gaps() {
        val range = HistoryRangeState(0, 200_000_000_000)
        val records = rows(1_000_001).mapIndexed { index, entry ->
            entry.copy(
                record = entry.record.copy(
                    status = when (index % 3) {
                        0 -> -1
                        1 -> 12
                        else -> 0
                    }
                )
            )
        }
        val model = detailedHistoryChart(range, range, HistoryMetric.LEVEL, 300, records)
        assertFalse(model.raw)
        assertEquals(1_000_001L, model.count)
        assertTrue(model.points.size <= 100 * 17)
        assertTrue(model.events.size <= 100 * 3)
        assertEquals(1_000_001L, model.events.sumOf { it.count })
    }

    @Test
    fun nearby_coordinate_window_preserves_deep_zoom_after_thirty_years() {
        val range = HistoryRangeState(0, 30L * 365 * 86_400_000)
        val viewport = HistoryRangeState(range.end - 3_600_000, range.end - 3_540_000)
        val coordinates = detailedHistoryCoordinateRange(range, viewport)
        assertEquals(
            HistoryRangeState(viewport.start - 120_000, viewport.end + 120_000), coordinates
        )
        val axis = DetailedHistoryTimeAxis(coordinates)
        val firstSample = viewport.start - 60_000
        val nextSample = firstSample + 120_000
        assertNotEquals(axis.x(firstSample), axis.x(nextSample))
        assertNotEquals(axis.x(viewport.start), axis.x(viewport.end))
        assertEquals(viewport.start, axis.time(axis.x(viewport.start)))
        assertEquals(viewport.end, axis.time(axis.x(viewport.end)))
        assertEquals(firstSample + 1, axis.time(axis.x(firstSample + 1)))
        assertEquals(range, detailedHistoryCoordinateRange(range, range))
    }

    @Test
    fun coordinate_window_padding_clamps_at_both_edges_without_overflow() {
        val range = HistoryRangeState(0, Long.MAX_VALUE)
        val first = HistoryRangeState(10, 70)
        assertEquals(HistoryRangeState(0, 190), detailedHistoryCoordinateRange(range, first))
        val last = HistoryRangeState(Long.MAX_VALUE - 60, Long.MAX_VALUE)
        val coordinates = detailedHistoryCoordinateRange(range, last)
        assertEquals(HistoryRangeState(Long.MAX_VALUE - 180, Long.MAX_VALUE), coordinates)
        val axis = DetailedHistoryTimeAxis(coordinates)
        assertEquals(Long.MAX_VALUE, axis.time(axis.x(Long.MAX_VALUE)))
        assertEquals(range, detailedHistoryCoordinateRange(range, range))
        assertEquals(
            range, detailedHistoryCoordinateRange(
                range, HistoryRangeState(1, Long.MAX_VALUE - 1)
            )
        )
        assertEquals(Long.MAX_VALUE, historyExclusiveEnd(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, historyExclusiveEnd(Long.MAX_VALUE - 1))
        assertEquals(1L, historyExclusiveEnd(0))
    }

    @Test
    fun relative_seconds_keep_deep_zoom_precision_and_keys_keep_exact_timestamps() {
        val start = 1_800_000_000_123L
        val range = HistoryRangeState(start, start + 28 * 86_400_000L)
        val axis = DetailedHistoryTimeAxis(range)
        val time = start + 15 * 60_000L + 1
        assertTrue(abs(axis.time(axis.x(time)) - time) <= 1)
        assertNotEquals(axis.x(time), axis.x(time + 1))
        val last = range.end - 123
        assertTrue(abs(axis.time(axis.x(last)) - last) <= 125)
        assertEquals(range.start, axis.time(-1f))
        assertEquals(range.end, axis.time(Float.MAX_VALUE))
        assertEquals(range.start, axis.time(Float.NaN))
        val key = HistoryKey(last, 99)
        val duplicate = HistoryKey(last, 100)
        assertEquals(axis.x(key.time), axis.x(duplicate.time), 0f)
        assertNotEquals(key, duplicate)
        assertEquals(last, key.time)
    }
}
