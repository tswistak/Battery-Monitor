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
package codes.swistak.batterymonitor.ui.history

import codes.swistak.batterymonitor.logs.DetailedHistoryBounds
import codes.swistak.batterymonitor.logs.DetailedHistoryChartModel
import codes.swistak.batterymonitor.logs.HistoryKey
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryPoint
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.ui.components.historyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FullscreenChartStateTest {
    @Test
    fun `coordinates rebase only when zoom precision or window edges require it`() {
        val late = 1_800_000_000_000L
        val range = HistoryRangeState(0, Long.MAX_VALUE)
        val coordinates = HistoryRangeState(late - 120_000, late + 180_000)
        assertFalse(
            fullscreenCoordinatesNeedRebase(
                coordinates, range, HistoryRangeState(late, late + 60_000)
            )
        )
        assertFalse(
            fullscreenCoordinatesNeedRebase(
                coordinates, range, HistoryRangeState(late + 10_000, late + 70_000)
            )
        )
        assertTrue(
            fullscreenCoordinatesNeedRebase(
                coordinates, range, HistoryRangeState(late, late + 5_000)
            )
        )
        assertTrue(
            fullscreenCoordinatesNeedRebase(
                coordinates, range, HistoryRangeState(late + 140_000, late + 200_000)
            )
        )
        assertTrue(
            fullscreenCoordinatesNeedRebase(
                coordinates, range, HistoryRangeState(late + 110_000, late + 170_000)
            )
        )
        assertTrue(
            fullscreenCoordinatesNeedRebase(
                coordinates,
                HistoryRangeState(late, late + 60_000),
                HistoryRangeState(late, late + 60_000)
            )
        )
    }

    @Test
    fun `adjacent observation viewports preserve span and stay inside selected range`() {
        val range = HistoryRangeState(100, 1000)
        val viewport = HistoryRangeState(400, 600)
        assertEquals(HistoryRangeState(200, 400), fullscreenViewportAround(range, viewport, 300))
        assertEquals(HistoryRangeState(600, 800), fullscreenViewportAround(range, viewport, 700))
        assertEquals(HistoryRangeState(100, 300), fullscreenViewportAround(range, viewport, 0))
        assertEquals(HistoryRangeState(800, 1000), fullscreenViewportAround(range, viewport, 1100))
        assertEquals(range, fullscreenViewportAround(range, range, 900))
        assertEquals(
            HistoryRangeState(501, 502),
            fullscreenViewportAround(range, HistoryRangeState(500, 501), 501)
        )
    }

    @Test
    fun `viewport recentering does not overflow near long maximum`() {
        val range = HistoryRangeState(0, Long.MAX_VALUE)
        val viewport = HistoryRangeState(Long.MAX_VALUE - 1000, Long.MAX_VALUE - 500)
        assertEquals(
            HistoryRangeState(Long.MAX_VALUE - 500, Long.MAX_VALUE),
            fullscreenViewportAround(range, viewport, Long.MAX_VALUE)
        )
        assertEquals(range, fullscreenViewportAround(range, range, Long.MAX_VALUE))
    }

    @Test
    fun `request keeps every metric and exact inclusive exclusive range`() {
        val range = HistoryRangeState(1_800_000_000_123, 1_800_003_600_124)
        for (metric in HistoryMetric.entries) {
            assertEquals(
                FullscreenChartRequest(metric, range),
                fullscreenChartRequest(metric.name, range.start, range.end)
            )
        }
        assertEquals(
            HistoryRangeState(0, Long.MAX_VALUE),
            fullscreenChartRequest("LEVEL", 0, Long.MAX_VALUE)!!.range
        )
    }

    @Test
    fun `request rejects missing unknown metrics and invalid ranges`() {
        for (metric in listOf(null, "", "level", "UNKNOWN")) {
            assertNull(fullscreenChartRequest(metric, 0, 100))
        }
        for ((start, end) in listOf(-1L to 100L, 100L to 100L, 101L to 100L, 0L to -1L)) {
            assertNull(fullscreenChartRequest("LEVEL", start, end))
        }
    }

    @Test
    fun `viewport stays inside selected range with a nonempty interval`() {
        val range = HistoryRangeState(100, 200)
        assertEquals(range, boundedChartViewport(range, HistoryRangeState(0, 300)))
        assertEquals(
            HistoryRangeState(110, 190), boundedChartViewport(range, HistoryRangeState(110, 190))
        )
        assertEquals(
            HistoryRangeState(100, 101), boundedChartViewport(range, HistoryRangeState(0, 50))
        )
        assertEquals(
            HistoryRangeState(199, 200), boundedChartViewport(range, HistoryRangeState(300, 400))
        )
        assertEquals(
            HistoryRangeState(Long.MAX_VALUE - 1, Long.MAX_VALUE), boundedChartViewport(
                HistoryRangeState(0, Long.MAX_VALUE),
                HistoryRangeState(Long.MAX_VALUE - 1, Long.MAX_VALUE)
            )
        )
    }

    @Test
    fun `automatic scale uses local extrema and fixed scale uses full period`() {
        val automatic = fullscreenYBounds(
            HistoryMetric.TEMPERATURE, ChartScaleMode.AUTOMATIC, 34.2, 35.1, 20.0, 45.0, false, 1
        )
        val fixed = fullscreenYBounds(
            HistoryMetric.TEMPERATURE, ChartScaleMode.FIXED, 34.2, 35.1, 20.0, 45.0, false, 1
        )
        assertTrue(automatic.minimum <= 34.2 && automatic.maximum >= 35.1)
        assertTrue(fixed.minimum <= 20.0 && fixed.maximum >= 45.0)
        assertTrue(automatic.maximum - automatic.minimum < fixed.maximum - fixed.minimum)
        for (mode in listOf(ChartScaleMode.FIXED, ChartScaleMode.MANUAL)) {
            val level = fullscreenYBounds(
                HistoryMetric.LEVEL, mode, 50.0, 51.0, 20.0, 80.0, false, 1
            )
            assertEquals(0.0, level.minimum, 0.0)
            assertEquals(100.0, level.maximum, 0.0)
        }
    }

    @Test
    fun `unit preferences convert values and reorder reversed current bounds`() {
        assertEquals(95.0, fullscreenDisplayValue(35.0, HistoryMetric.TEMPERATURE, true, 1), 0.0001)
        assertEquals(35.0, fullscreenDisplayValue(35.0, HistoryMetric.TEMPERATURE, false, -1), 0.0)
        assertEquals(100.0, fullscreenDisplayValue(-100.0, HistoryMetric.CURRENT, false, -1), 0.0)
        assertEquals(0.4, fullscreenDisplayValue(-0.4, HistoryMetric.POWER, false, -1), 0.0)
        for (metric in listOf(
            HistoryMetric.LEVEL, HistoryMetric.VOLTAGE, HistoryMetric.REMAINING_CHARGE
        )) {
            assertEquals(4.0, fullscreenDisplayValue(4.0, metric, true, -1), 0.0)
        }
        val current = fullscreenYBounds(
            HistoryMetric.CURRENT, ChartScaleMode.AUTOMATIC, -200.0, 100.0, -999.0, 999.0, false, -1
        )
        assertTrue(current.minimum <= -100.0 && current.maximum >= 200.0)
        val fahrenheit = fullscreenYBounds(
            HistoryMetric.TEMPERATURE, ChartScaleMode.AUTOMATIC, 34.2, 35.1, 20.0, 45.0, true, 1
        )
        assertTrue(fahrenheit.minimum <= 93.56 && fahrenheit.maximum >= 95.18)
    }

    @Test
    fun `metric selection keeps one primary and at most one distinct comparison`() {
        val range = HistoryRangeState(100, 1000)
        val initial = FullscreenChartUiState(range, "all", HistoryMetric.LEVEL)
        assertEquals(listOf(HistoryMetric.LEVEL), initial.metrics)
        assertEquals(initial, initial.withComparison(HistoryMetric.LEVEL))
        val compared = initial.withComparison(HistoryMetric.CURRENT)
        assertEquals(listOf(HistoryMetric.LEVEL, HistoryMetric.CURRENT), compared.metrics)
        val swapped = compared.withMetric(HistoryMetric.CURRENT)
        assertEquals(listOf(HistoryMetric.CURRENT, HistoryMetric.LEVEL), swapped.metrics)
        val replaced = swapped.withComparison(HistoryMetric.POWER)
        assertEquals(listOf(HistoryMetric.CURRENT, HistoryMetric.POWER), replaced.metrics)
        assertEquals(replaced, replaced.withComparison(HistoryMetric.CURRENT))
        assertEquals(listOf(HistoryMetric.CURRENT), replaced.withComparison(null).metrics)
        for (primary in HistoryMetric.entries) {
            for (comparison in HistoryMetric.entries) {
                val state = compared.withMetric(primary).withComparison(comparison)
                assertEquals(primary, state.metric)
                assertTrue(state.metrics.size in 1..2)
                assertEquals(state.metrics.size, state.metrics.distinct().size)
            }
        }
    }

    @Test
    fun `metric changes preserve range viewport selection and loaded data`() {
        val range = HistoryRangeState(100, 1000)
        val chart = DetailedHistoryChartModel(
            range, range, HistoryMetric.LEVEL, 2, emptyList(), emptyList(), 20.0, 80.0, true
        )
        val initial = FullscreenChartUiState(
            range = range,
            rangeLabel = "all",
            metric = HistoryMetric.LEVEL,
            comparison = HistoryMetric.CURRENT,
            viewport = HistoryRangeState(400, 600),
            scaleMode = ChartScaleMode.MANUAL,
            selectedKey = HistoryKey(500, 7),
            charts = mapOf(HistoryMetric.LEVEL to chart),
            boundsByMetric = mapOf(HistoryMetric.LEVEL to DetailedHistoryBounds(2, 20.0, 80.0)),
            valueScale = ChartValueScale.LOGARITHMIC
        )
        val states = listOf(
            initial.withMetric(HistoryMetric.CURRENT),
            initial.withComparison(null)
        )
        for ((range1, rangeLabel, _, _, viewport, scaleMode, selectedKey, charts, boundsByMetric) in states) {
            assertEquals(initial.range, range1)
            assertEquals("all", rangeLabel)
            assertEquals(initial.viewport, viewport)
            assertEquals(initial.selectedKey, selectedKey)
            assertEquals(ChartScaleMode.MANUAL, scaleMode)
            assertSame(initial.charts, charts)
            assertSame(initial.boundsByMetric, boundsByMetric)
        }
    }

    @Test
    fun `constant and empty series have finite nondegenerate axes for every metric`() {
        for (metric in HistoryMetric.entries) {
            for (mode in ChartScaleMode.entries) {
                for (value in listOf(null, 0.0, 50.0)) {
                    val scale =
                        fullscreenYBounds(metric, mode, value, value, value, value, false, 1)
                    assertTrue(scale.minimum.isFinite())
                    assertTrue(scale.maximum.isFinite())
                    assertTrue(scale.maximum > scale.minimum)
                    assertTrue(scale.ticks.size >= 2)
                    assertTrue(scale.ticks.all { it.isFinite() && it in scale.minimum..scale.maximum })
                }
            }
        }
    }

    @Test
    fun `selected measurement formatting uses existing units locale and preferences`() {
        val polish = Locale.forLanguageTag("pl-PL")
        val values = listOf(57.0, 34.2, 4.215, -12.5, -0.4, 2500.125)
        val expected = listOf("57%", "93,6 °F", "4,22 V", "12,5 mA", "+0,40 W", "2 500,125 mAh")
        for ((index, metric) in HistoryMetric.entries.withIndex()) {
            val formatted =
                historyValue(values[index], metric, true, polish, -1).replace('\u00a0', ' ')
                    .replace('\u202f', ' ')
            assertEquals(expected[index], formatted)
            assertEquals("—", historyValue(null, metric, false, Locale.US))
        }
        assertEquals("34.2 °C", historyValue(34.2, HistoryMetric.TEMPERATURE, false, Locale.US))
        assertEquals("-12.5 mA", historyValue(-12.5, HistoryMetric.CURRENT, false, Locale.US))
    }

    @Test
    fun `both axes scale independently in actual units`() {
        for (mode in ChartScaleMode.entries) {
            val level = fullscreenYBounds(
                HistoryMetric.LEVEL, mode, 55.0, 56.0, 20.0, 80.0, false, 1
            )
            val current = fullscreenYBounds(
                HistoryMetric.CURRENT, mode, -1200.0, -1100.0, -3000.0, -100.0, false, -1
            )
            assertTrue(level.minimum <= 55.0 && level.maximum >= 56.0)
            assertTrue(current.minimum <= 1100.0 && current.maximum >= 1200.0)
            assertTrue(current.maximum > level.maximum)
            if (mode != ChartScaleMode.AUTOMATIC) {
                assertEquals(0.0, level.minimum, 0.0)
                assertEquals(100.0, level.maximum, 0.0)
                assertTrue(current.minimum <= 100.0 && current.maximum >= 3000.0)
            }
        }
    }

    @Test
    fun `logarithmic coordinates and inverse labels preserve real units and preferences`() {
        val values = listOf(57.0, -5.0, 4.215, -12.5, -0.4, 2500.125)
        for ((index, metric) in HistoryMetric.entries.withIndex()) {
            val value = values[index]
            for (scale in ChartValueScale.entries) {
                val coordinate = fullscreenChartValue(value, metric, true, -1, scale)
                assertTrue(coordinate.isFinite())
                val restored = fullscreenRawValue(coordinate, metric, true, -1, scale)
                assertEquals(value, restored, 0.000000001)
                assertEquals(
                    historyValue(value, metric, true, Locale.US, -1),
                    historyValue(restored, metric, true, Locale.US, -1)
                )
            }
        }
        assertEquals(
            2.0, fullscreenChartValue(
                100.0, HistoryMetric.REMAINING_CHARGE, false, 1, ChartValueScale.LOGARITHMIC
            ), 0.0
        )
        assertEquals(
            100.0, fullscreenRawValue(
                2.0, HistoryMetric.REMAINING_CHARGE, false, 1, ChartValueScale.LOGARITHMIC
            ), 0.0
        )
    }

    @Test
    fun `logarithmic availability checks every metric in the complete selected range`() {
        val metrics = listOf(HistoryMetric.LEVEL, HistoryMetric.CURRENT)
        val positive = DetailedHistoryBounds(3, 10.0, 100.0)
        assertTrue(fullscreenLogAvailable(metrics, metrics.associateWith { positive }, false, 1))
        for (minimum in listOf(0.0, -1.0, Double.NaN, Double.NEGATIVE_INFINITY)) {
            val invalid =
                metrics.associateWith { positive } + (HistoryMetric.CURRENT to DetailedHistoryBounds(
                    3, minimum, 100.0
                ))
            assertFalse(fullscreenLogAvailable(metrics, invalid, false, 1))
        }
        assertFalse(
            fullscreenLogAvailable(
                metrics, mapOf(HistoryMetric.LEVEL to positive), false, 1
            )
        )
        val empty = DetailedHistoryBounds(3, null, null)
        assertTrue(
            fullscreenLogAvailable(
                metrics,
                mapOf(HistoryMetric.LEVEL to positive, HistoryMetric.CURRENT to empty),
                false,
                1
            )
        )
        assertFalse(fullscreenLogAvailable(metrics, metrics.associateWith { empty }, false, 1))
        assertFalse(fullscreenLogAvailable(emptyList(), emptyMap(), false, 1))
    }

    @Test
    fun `logarithmic availability applies fahrenheit and current sign before validation`() {
        val temperature = listOf(HistoryMetric.TEMPERATURE)
        val cold = mapOf(HistoryMetric.TEMPERATURE to DetailedHistoryBounds(2, -5.0, 5.0))
        assertFalse(fullscreenLogAvailable(temperature, cold, false, 1))
        assertTrue(fullscreenLogAvailable(temperature, cold, true, 1))
        val freezing = mapOf(HistoryMetric.TEMPERATURE to DetailedHistoryBounds(2, -20.0, 5.0))
        assertFalse(fullscreenLogAvailable(temperature, freezing, true, 1))
        for (metric in listOf(HistoryMetric.CURRENT, HistoryMetric.POWER)) {
            val negative = mapOf(metric to DetailedHistoryBounds(2, -100.0, -1.0))
            assertFalse(fullscreenLogAvailable(listOf(metric), negative, false, 1))
            assertTrue(fullscreenLogAvailable(listOf(metric), negative, false, -1))
            val mixed = mapOf(metric to DetailedHistoryBounds(2, -100.0, 1.0))
            assertFalse(fullscreenLogAvailable(listOf(metric), mixed, false, -1))
        }
    }

    @Test
    fun `fresh positive bounds do not admit stale nonpositive chart data during refresh`() {
        val range = HistoryRangeState(100, 1000)
        val metric = HistoryMetric.LEVEL
        val bounds = mapOf(metric to DetailedHistoryBounds(2, 10.0, 80.0))
        val positive = DetailedHistoryChartModel(
            range,
            range,
            metric,
            2,
            listOf(HistoryPoint(HistoryKey(100, 1), 10.0), HistoryPoint(HistoryKey(200, 2), 80.0)),
            emptyList(),
            10.0,
            80.0,
            true
        )
        assertTrue(
            fullscreenLogAvailable(
                listOf(metric), bounds, false, 1, mapOf(metric to positive)
            )
        )
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val stalePoint =
                positive.copy(points = positive.points + HistoryPoint(HistoryKey(300, 3), invalid))
            assertFalse(
                fullscreenLogAvailable(
                    listOf(metric), bounds, false, 1, mapOf(metric to stalePoint)
                )
            )
            val staleExtrema = positive.copy(min = invalid)
            assertFalse(
                fullscreenLogAvailable(
                    listOf(metric), bounds, false, 1, mapOf(metric to staleExtrema)
                )
            )
        }
        val emptyPrimary = bounds + (metric to DetailedHistoryBounds(
            0, null, null
        )) + (HistoryMetric.CURRENT to DetailedHistoryBounds(1, 100.0, 100.0))
        assertFalse(
            fullscreenLogAvailable(
                listOf(metric, HistoryMetric.CURRENT),
                emptyPrimary,
                false,
                1,
                mapOf(metric to positive.copy(min = 0.0))
            )
        )
    }

    @Test
    fun `logarithmic axis bounds use local or complete extrema for both axes`() {
        for (metric in listOf(HistoryMetric.CURRENT, HistoryMetric.POWER)) {
            val automatic = fullscreenYBounds(
                metric,
                ChartScaleMode.AUTOMATIC,
                -20.0,
                -10.0,
                -1000.0,
                -1.0,
                false,
                -1,
                ChartValueScale.LOGARITHMIC
            )
            assertTrue(automatic.minimum <= 1.0 && automatic.maximum >= kotlin.math.log10(20.0))
            for (mode in listOf(ChartScaleMode.FIXED, ChartScaleMode.MANUAL)) {
                val full = fullscreenYBounds(
                    metric,
                    mode,
                    -20.0,
                    -10.0,
                    -1000.0,
                    -1.0,
                    false,
                    -1,
                    ChartValueScale.LOGARITHMIC
                )
                assertTrue(full.minimum <= 0.0 && full.maximum >= 3.0)
                assertTrue(full.maximum - full.minimum > automatic.maximum - automatic.minimum)
            }
        }
    }

    @Test
    fun `positive fixed level logarithmic axis uses real lower bound and hundred percent upper bound`() {
        for (mode in listOf(ChartScaleMode.FIXED, ChartScaleMode.MANUAL)) {
            val scale = fullscreenYBounds(
                HistoryMetric.LEVEL,
                mode,
                55.0,
                56.0,
                20.0,
                80.0,
                false,
                1,
                ChartValueScale.LOGARITHMIC
            )
            val lower = fullscreenRawValue(
                scale.minimum, HistoryMetric.LEVEL, false, 1, ChartValueScale.LOGARITHMIC
            )
            val upper = fullscreenRawValue(
                scale.maximum, HistoryMetric.LEVEL, false, 1, ChartValueScale.LOGARITHMIC
            )
            assertTrue(lower > 0.0 && lower <= 20.0)
            assertEquals(100.0, upper, 0.000001)
        }
    }

    @Test
    fun `flat and empty logarithmic axes and inverse float labels remain finite`() {
        for (metric in HistoryMetric.entries) {
            for (mode in ChartScaleMode.entries) {
                for (value in listOf(null, 1.0, 50.0)) {
                    val scale = fullscreenYBounds(
                        metric,
                        mode,
                        value,
                        value,
                        value,
                        value,
                        false,
                        1,
                        ChartValueScale.LOGARITHMIC
                    )
                    assertTrue(scale.minimum.isFinite() && scale.maximum.isFinite())
                    assertTrue(scale.maximum > scale.minimum)
                    for (tick in scale.ticks) {
                        assertTrue(tick.isFinite() && tick in scale.minimum..scale.maximum)
                        assertTrue(
                            fullscreenRawValue(
                                tick.toFloat().toDouble(),
                                metric,
                                false,
                                1,
                                ChartValueScale.LOGARITHMIC
                            ).isFinite()
                        )
                    }
                }
            }
        }
        for (value in listOf(Double.MIN_VALUE, Double.MAX_VALUE)) {
            val coordinate = fullscreenChartValue(
                value, HistoryMetric.CURRENT, false, 1, ChartValueScale.LOGARITHMIC
            )
            assertTrue(coordinate.toFloat().isFinite())
            val inverse = fullscreenRawValue(
                coordinate.toFloat().toDouble(),
                HistoryMetric.CURRENT,
                false,
                1,
                ChartValueScale.LOGARITHMIC
            )
            assertTrue(inverse.isFinite() && inverse > 0.0)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nonpositive observations are rejected instead of silently transformed`() {
        fullscreenChartValue(0.0, HistoryMetric.LEVEL, false, 1, ChartValueScale.LOGARITHMIC)
    }
}
