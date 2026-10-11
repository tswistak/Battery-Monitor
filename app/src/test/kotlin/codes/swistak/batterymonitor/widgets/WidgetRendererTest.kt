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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRendererTest {
    @Test
    fun `a short host allocation selects compact content without changing widget configuration`() {
        assertTrue(WidgetRenderer.usesCompactLayout(WidgetVariant.ADAPTIVE, 100))
        assertFalse(WidgetRenderer.usesCompactLayout(WidgetVariant.ADAPTIVE, 230))
        assertTrue(WidgetRenderer.usesCompactLayout(WidgetVariant.ADAPTIVE, 230, 140))
        assertTrue(WidgetRenderer.usesCompactLayout(WidgetVariant.COMPACT, 230))
    }

    @Test
    fun `four measurements fit a single row only when the host provides enough width`() {
        assertEquals(4, WidgetRenderer.measurementColumns(400, 110, true, 4))
        assertEquals(2, WidgetRenderer.measurementColumns(200, 110, true, 4))
        assertEquals(2, WidgetRenderer.measurementColumns(400, 230, false, 4))
    }

    @Test
    fun `short narrow measurements retain the first two configured metrics while larger allocations show all`() {
        val metrics = listOf(
            WidgetMetric.REMAINING_CHARGE,
            WidgetMetric.CURRENT,
            WidgetMetric.VOLTAGE,
            WidgetMetric.TEMPERATURE
        )
        val narrow = WidgetRenderer.visibleMetrics(metrics, 180, 100)
        val wide = WidgetRenderer.visibleMetrics(metrics, 400, 100)
        val tall = WidgetRenderer.visibleMetrics(metrics, 180, 230)
        assertEquals(narrow, metrics.take(2))
        assertEquals(metrics, wide)
        assertEquals(metrics, tall)
        assertEquals(2, WidgetRenderer.measurementColumns(180, 100, true, narrow.size))
        assertEquals(4, WidgetRenderer.measurementColumns(400, 100, true, wide.size))
        assertEquals(2, WidgetRenderer.measurementColumns(180, 230, false, tall.size))
        assertEquals(metrics.take(3), WidgetRenderer.visibleMetrics(metrics.take(3), 300, 100))
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
            WidgetRenderer.chartRuns(points, 0, 100).map { run -> run.map { it.time } })
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
            WidgetRenderer.chartRuns(points, 0, 100).map { run -> run.map { it.time } })
        assertTrue(WidgetRenderer.chartRuns(emptyList(), 0, 100).isEmpty())
    }
}
