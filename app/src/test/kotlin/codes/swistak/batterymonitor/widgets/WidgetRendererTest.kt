/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.widgets

import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.ui.components.historyAxisFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class WidgetRendererTest {
    @Test
    fun `status widget labels exact charge and discharge targets as reached and only lower discharge levels as below`() {
        val reached = WidgetSnapshot(
            20, BatteryInfo.STATUS_UNPLUGGED, 320, 4000, -500000, 3000000, 0, 20, true, 2000000L
        )
        assertEquals(R.string.current_target_reached, WidgetRenderer.targetReachedLabel(reached))
        assertEquals(
            R.string.current_target_reached,
            WidgetRenderer.targetReachedLabel(reached.copy(status = BatteryInfo.STATUS_DISCHARGING))
        )
        assertEquals(
            R.string.current_below_target,
            WidgetRenderer.targetReachedLabel(reached.copy(levelPercent = 19))
        )
        assertEquals(
            R.string.current_target_reached, WidgetRenderer.targetReachedLabel(
                reached.copy(
                    levelPercent = 80, status = BatteryInfo.STATUS_CHARGING, predictionTarget = 80
                )
            )
        )
    }

    @Test
    fun `compact is selected for constrained allocations or an explicit preference`() {
        assertTrue(WidgetRenderer.usesCompactLayout(WidgetVariant.ADAPTIVE, 100, 260))
        assertFalse(WidgetRenderer.usesCompactLayout(WidgetVariant.ADAPTIVE, 180, 200))
        assertFalse(WidgetRenderer.usesCompactLayout(WidgetVariant.ADAPTIVE, 230, 140))
        assertTrue(WidgetRenderer.usesCompactLayout(WidgetVariant.ADAPTIVE, 230, 130))
        assertTrue(WidgetRenderer.usesCompactLayout(WidgetVariant.COMPACT, 230, 200))
    }

    @Test
    fun `measurement rows reflow for wide short and narrow tall content`() {
        assertEquals(4, WidgetRenderer.measurementColumns(360, 84, 4))
        assertEquals(2, WidgetRenderer.measurementColumns(176, 104, 4))
        assertEquals(2, WidgetRenderer.measurementColumns(360, 200, 4))
        assertEquals(1, WidgetRenderer.measurementColumns(146, 220, 4))
    }

    @Test
    fun `stopped stale missing and future readings never show a cached forecast as current`() {
        val now = 2_000_000L
        val fresh = WidgetSnapshot(70, 2, 320, 4000, 500000, 3000000, 20, 80, false, now)
        assertTrue(WidgetRenderer.canShowPrediction(fresh, now))
        assertFalse(WidgetRenderer.canShowPrediction(fresh.copy(monitoringEnabled = false), now))
        assertFalse(
            WidgetRenderer.canShowPrediction(
                fresh.copy(observedAtMillis = now - 900_001), now
            )
        )
        assertFalse(WidgetRenderer.canShowPrediction(fresh.copy(observedAtMillis = now + 1), now))
        assertFalse(WidgetRenderer.canShowPrediction(fresh.copy(observedAtMillis = 0), now))
        assertFalse(WidgetRenderer.canShowPrediction(null, now))
    }

    @Test
    fun `Y axis retains scale endpoints when crowded and only the midpoint for compact widgets`() {
        assertEquals(listOf(45.0, 37.5, 30.0), WidgetTrendChart.axisValues(30.0, 45.0, false))
        assertEquals(listOf(37.5), WidgetTrendChart.axisValues(30.0, 45.0, true))
        assertEquals(listOf(100.0, 70.0, 40.0), WidgetTrendChart.axisValues(40.0, 100.0, false))
        assertEquals(
            listOf(100.0, 40.0),
            WidgetTrendChart.axisValues(40.0, 100.0, false, includeMiddle = false)
        )
        assertEquals(
            listOf(70.0), WidgetTrendChart.axisValues(40.0, 100.0, true, includeMiddle = false)
        )
    }

    @Test
    fun `trend lines stop at missing readings and known segment boundaries`() {
        val points = listOf(
            WidgetTrendPoint(10, 60.0, 1),
            WidgetTrendPoint(20, 58.0, 1),
            WidgetTrendPoint(30, null, 1),
            WidgetTrendPoint(40, 56.0, 1),
            WidgetTrendPoint(50, 57.0, 2),
            WidgetTrendPoint(60, 58.0, 2)
        )
        assertEquals(
            listOf(listOf(10L, 20L), listOf(40L), listOf(50L, 60L)),
            WidgetTrendChart.runs(points, 0, 100).map { run -> run.map { it.time } })
    }

    @Test
    fun `trend ignores out of range values and never connects observations in reverse time`() {
        val points = listOf(
            WidgetTrendPoint(-1, 60.0, 1),
            WidgetTrendPoint(10, 60.0, 1),
            WidgetTrendPoint(20, Double.NaN, 1),
            WidgetTrendPoint(30, 101.0, 1),
            WidgetTrendPoint(50, 59.0, 1),
            WidgetTrendPoint(40, 58.0, 1),
            WidgetTrendPoint(101, 58.0, 1)
        )
        assertEquals(
            listOf(listOf(10L), listOf(50L), listOf(40L)),
            WidgetTrendChart.runs(points, 0, 100).map { run -> run.map { it.time } })
        assertTrue(WidgetTrendChart.runs(emptyList(), 0, 100).isEmpty())
    }

    @Test
    fun `time ticks keep plot endpoints and add intermediate times only when their labels fit`() {
        val start = Instant.parse("2026-11-05T16:34:00Z").toEpochMilli()
        val left = 27f
        for (hours in listOf(24, 72, 168)) {
            val end = start + hours * 3_600_000L
            val formatter = historyAxisFormatter(
                end - start, Locale.forLanguageTag("pl-PL"), ZoneId.of("Europe/Warsaw")
            )

            fun ticks(width: Float) = WidgetTrendChart.timeTicks(
                start,
                end,
                left,
                left + width,
                6f,
                { formatter.format(Instant.ofEpochMilli(it)) },
                { 36f })
            assertEquals(listOf(start, end - 1), ticks(100f).map { it.time })
            val wide = ticks(250f)
            assertEquals(4, wide.size)
            assertEquals(left, wide.first().left, 0.01f)
            assertEquals(left + 250f, wide.last().left + wide.last().width, 0.01f)
            for (tick in wide) {
                val expected = left + (tick.time - start).toDouble() / (end - start) * 250f
                assertEquals(expected.toFloat(), tick.x, 0.01f)
            }
            assertTrue(wide.zipWithNext().all { (a, b) -> a.left + a.width + 6f <= b.left })
            if (hours > 24) assertTrue(wide.first().label != wide.last().label)
        }
    }
}
