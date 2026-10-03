/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.logs

import codes.swistak.batterymonitor.ui.components.historyChartScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

class HistoryModelTest {
    private val range = HistoryRangeState(100, 1000)
    private fun entry(
        id: Long,
        time: Long,
        charge: Int? = 50,
        temperature: Int? = 300,
        voltage: Int? = 4000,
        status: Int = 100
    ) = HistoryRecord(id, LogRecord(status, charge, time, temperature, voltage))

    @Test
    fun `empty history has no values and one observation retains its timestamp and units`() {
        val empty = historyChart(range, emptySequence())
        assertEquals(0, empty.count)
        assertNull(empty.series.getValue(HistoryMetric.LEVEL).min)
        val single = historyChart(range, sequenceOf(entry(1, 500)))
        assertEquals(
            listOf(HistoryPoint(HistoryKey(500, 1), 50.0)),
            single.series.getValue(HistoryMetric.LEVEL).points
        )
        assertEquals(30.0, single.series.getValue(HistoryMetric.TEMPERATURE).min)
        assertEquals(4.0, single.series.getValue(HistoryMetric.VOLTAGE).min)
    }

    @Test
    fun `analytical range includes start and excludes end while legacy export has the same records`() {
        val records = sequenceOf(entry(1, 99), entry(2, 100), entry(3, 999), entry(4, 1000))
        val result = historyChart(range, records)
        assertEquals(
            listOf(2L, 3L), result.series.getValue(HistoryMetric.LEVEL).points.map { it.key.id })
        assertEquals(99, range.exportAfter)
        assertEquals(999, range.exportThrough)
        for (time in 98L..1001L) assertEquals(
            (time >= range.start && time < range.end),
            (time > range.exportAfter && time <= range.exportThrough)
        )
    }

    @Test
    fun `calendar days respect spring and autumn DST while seven days are exactly 168 hours`() {
        val zone = ZoneId.of("Europe/Warsaw")
        val spring = LocalDate.of(2026, 3, 29)
        val autumn = LocalDate.of(2026, 10, 25)
        assertEquals(
            23 * 3_600_000L, HistoryRangeState.days(spring, spring, zone).let { it.end - it.start })
        assertEquals(
            25 * 3_600_000L, HistoryRangeState.days(autumn, autumn, zone).let { it.end - it.start })
        val rolling = HistoryRangeState.lastHours(168, 2_000_000_000L)
        assertEquals(168 * 3_600_000L, rolling.end - rolling.start)
    }

    @Test
    fun `nulls and reboot stay missing rather than zero and sparse records remain observations`() {
        val result = historyChart(
            range, sequenceOf(
                entry(1, 100),
                entry(2, 500, null, null, null, -1),
                entry(3, 800, voltage = null),
                entry(4, 999, temperature = 0)
            )
        )
        assertTrue(result.series.getValue(HistoryMetric.LEVEL).points.any { it.value == null && it.key.id == 2L })
        assertEquals(50.0, result.series.getValue(HistoryMetric.LEVEL).min)
        assertEquals(4.0, result.series.getValue(HistoryMetric.VOLTAGE).min)
        assertEquals(0.0, result.series.getValue(HistoryMetric.TEMPERATURE).min)
        assertEquals(-1, result.events.single().code)
        assertEquals(500, result.events.single().first)
        val levels = result.series.getValue(HistoryMetric.LEVEL).points.filter { it.value != null }
        assertNotEquals(levels.first().segment, levels.last().segment)
    }

    @Test
    fun `line segments never bridge repeated missing readings within one reduction bucket`() {
        val result = historyChart(
            range, sequenceOf(
                entry(1, 100),
                entry(2, 101, voltage = null),
                entry(3, 102),
                entry(4, 103, voltage = null),
                entry(5, 104)
            )
        )
        val voltage =
            result.series.getValue(HistoryMetric.VOLTAGE).points.filter { it.value != null }
        assertNotEquals(voltage.first().segment, voltage.last().segment)
        val level = result.series.getValue(HistoryMetric.LEVEL).points.filter { it.value != null }
        assertEquals(level.first().segment, level.last().segment)
    }

    @Test
    fun `chart axes match the prototype ranges and use display units for Fahrenheit`() {
        val level =
            historyChartScale(HistorySeries(emptyList(), 42.0, 80.0), HistoryMetric.LEVEL, false)
        assertEquals(listOf(100.0, 80.0, 60.0, 40.0), level.ticks)
        val temperature = historyChartScale(
            HistorySeries(emptyList(), 29.8, 36.4), HistoryMetric.TEMPERATURE, false
        )
        assertEquals(listOf(40.0, 35.0, 30.0, 25.0), temperature.ticks)
        val voltage =
            historyChartScale(HistorySeries(emptyList(), 3.88, 4.20), HistoryMetric.VOLTAGE, false)
        assertEquals(3.8, voltage.minimum, 0.0001)
        assertEquals(4.4, voltage.maximum, 0.0001)
        val fahrenheit = historyChartScale(
            HistorySeries(emptyList(), 29.8, 36.4), HistoryMetric.TEMPERATURE, true
        )
        assertTrue(fahrenheit.minimum <= 29.8 * 1.8 + 32)
        assertTrue(fahrenheit.maximum >= 36.4 * 1.8 + 32)
        for (metric in HistoryMetric.entries) {
            val constant =
                historyChartScale(HistorySeries(emptyList(), 100.0, 100.0), metric, false)
            assertTrue(constant.maximum > constant.minimum)
        }
    }

    @Test
    fun `duplicate timestamps retain stable IDs for selection and keyset pagination`() {
        val result = historyChart(range, sequenceOf(entry(9, 500, 30), entry(10, 500, 90)))
        assertEquals(
            listOf(9L, 10L), result.series.getValue(HistoryMetric.LEVEL).points.map { it.key.id })
        val ascending =
            historyQuery(range, ascending = true, anchor = HistoryKey(500, 9), limit = 129)
        assertTrue(ascending.sql.contains("time > ? OR (time = ? AND _id > ?)"))
        assertEquals(listOf("100", "1000", "500", "500", "9"), ascending.args.toList())
        assertTrue(
            historyQuery(
                range, ascending = false, anchor = HistoryKey(500, 10), limit = 129
            ).sql.contains("_id < ?")
        )
    }

    @Test
    fun `each legacy status filter keeps its original meaning`() {
        assertEquals("plugged_in", historyFilterKey(22))
        assertEquals("charging", historyFilterKey(122))
        assertEquals("unplugged", historyFilterKey(0))
        assertEquals("discharging", historyFilterKey(100))
        assertEquals("not_charging", historyFilterKey(24))
        assertEquals("fully_charged", historyFilterKey(125))
        assertEquals("unknown", historyFilterKey(3))
        assertEquals("boot_completed", historyFilterKey(-1))
        assertTrue(historyQuery(range, emptySet()).sql.contains("AND (0)"))
        assertFalse(historyQuery(range).sql.contains("status ="))
    }

    @Test
    fun `a million observations over a year keep bounded series extrema and dense event boundaries`() {
        val year = HistoryRangeState.days(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), ZoneId.of("UTC")
        )
        val result = historyChart(year, sequence {
            repeat(1_000_000) { index ->
                yield(
                    entry(
                        index.toLong(),
                        year.start + index * 30_000L,
                        index % 101,
                        temperature = if (index % 13 == 0) null else index % 1000,
                        voltage = 3000 + index % 2000,
                        status = when (index % 1000) {
                            0 -> 22; 1 -> 0; 2 -> -1; else -> 100
                        }
                    )
                )
            }
        })
        assertEquals(1_000_000, result.count)
        result.series.values.forEach { assertTrue(it.points.size <= 500) }
        assertTrue(result.events.size <= 300)
        assertEquals(0.0, result.series.getValue(HistoryMetric.LEVEL).min)
        assertEquals(100.0, result.series.getValue(HistoryMetric.LEVEL).max)
        assertEquals(1000, result.events.filter { it.code == 2 }.sumOf { it.count })
        val dense =
            historyChart(range, sequenceOf(entry(1, 100, status = 22), entry(2, 101, status = 22)))
        assertEquals(HistoryEvent(100, 101, 2, 2), dense.events.single())
    }

    @Test
    fun `a failed or cancelled write cannot advance the export watermark`() {
        var watermark = 10L
        val request = HistoryExportRequest(10, 20, LogExportFormat.CSV, advanceWatermark = true)
        for (failure in listOf(
            IOException("write or close failed"),
            java.util.concurrent.CancellationException("cancelled")
        )) {
            try {
                completeHistoryExport(request, { throw failure }, { watermark = it }); fail()
            } catch (exception: Exception) {
                assertSame(failure, exception)
            }
            assertEquals(10, watermark)
        }
        completeHistoryExport(request, {}, { watermark = it })
        assertEquals(20, watermark)
        completeHistoryExport(
            request.copy(through = 100, advanceWatermark = false),
            {},
            { watermark = it })
        assertEquals(20, watermark)
    }
}
