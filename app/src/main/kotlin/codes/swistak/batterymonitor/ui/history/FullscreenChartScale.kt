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
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.HistorySeries
import codes.swistak.batterymonitor.ui.components.HistoryChartScale
import codes.swistak.batterymonitor.ui.components.historyChartScale
import kotlin.math.log10
import kotlin.math.pow

internal fun fullscreenDisplayValue(
    value: Double, metric: HistoryMetric, fahrenheit: Boolean, currentMultiplier: Int
): Double = when (metric) {
    HistoryMetric.TEMPERATURE if fahrenheit -> value * 1.8 + 32
    HistoryMetric.CURRENT, HistoryMetric.POWER -> value * currentMultiplier
    else -> value
}

internal fun fullscreenChartValue(
    value: Double,
    metric: HistoryMetric,
    fahrenheit: Boolean,
    currentMultiplier: Int,
    valueScale: ChartValueScale
): Double {
    val displayed = fullscreenDisplayValue(value, metric, fahrenheit, currentMultiplier)
    require(displayed.isFinite())
    if (valueScale == ChartValueScale.LINEAR) return displayed
    require(displayed > 0.0)
    return log10(displayed)
}

internal fun fullscreenRawValue(
    value: Double,
    metric: HistoryMetric,
    fahrenheit: Boolean,
    currentMultiplier: Int,
    valueScale: ChartValueScale
): Double {
    require(value.isFinite())
    val displayed = if (valueScale == ChartValueScale.LOGARITHMIC) {
        10.0.pow(value).coerceIn(Double.MIN_VALUE, Double.MAX_VALUE)
    } else value
    return when (metric) {
        HistoryMetric.TEMPERATURE if fahrenheit -> (displayed - 32.0) / 1.8
        HistoryMetric.CURRENT, HistoryMetric.POWER -> {
            require(currentMultiplier != 0)
            displayed / currentMultiplier
        }

        else -> displayed
    }
}

internal fun fullscreenLogAvailable(
    metrics: List<HistoryMetric>,
    boundsByMetric: Map<HistoryMetric, DetailedHistoryBounds>,
    fahrenheit: Boolean,
    currentMultiplier: Int,
    charts: Map<HistoryMetric, DetailedHistoryChartModel> = emptyMap()
): Boolean {
    var hasValues = false
    for (metric in metrics) {
        val bounds = boundsByMetric[metric] ?: return false
        charts[metric]?.let { chart ->
            val values =
                sequenceOf(chart.min, chart.max).filterNotNull() + chart.points.asSequence()
                    .mapNotNull { it.value }
            for (value in values) {
                val displayed = fullscreenDisplayValue(value, metric, fahrenheit, currentMultiplier)
                if (!displayed.isFinite() || displayed <= 0.0) return false
            }
        }
        if (bounds.min == null && bounds.max == null) continue
        val minimum = bounds.min ?: return false
        val maximum = bounds.max ?: return false
        val first = fullscreenDisplayValue(minimum, metric, fahrenheit, currentMultiplier)
        val last = fullscreenDisplayValue(maximum, metric, fahrenheit, currentMultiplier)
        if (!first.isFinite() || !last.isFinite() || minOf(first, last) <= 0.0) return false
        hasValues = true
    }
    return hasValues
}

internal fun fullscreenViewportAround(
    range: HistoryRangeState, viewport: HistoryRangeState, time: Long
): HistoryRangeState {
    val span = (viewport.end - viewport.start).coerceAtMost(range.end - range.start)
    val center = time.coerceIn(range.start, range.end - 1)
    val start = (center - span / 2).coerceIn(range.start, range.end - span)
    return HistoryRangeState(start, start + span)
}

internal fun fullscreenYBounds(
    metric: HistoryMetric,
    scaleMode: ChartScaleMode,
    visibleMinimum: Double?,
    visibleMaximum: Double?,
    fullMinimum: Double?,
    fullMaximum: Double?,
    fahrenheit: Boolean,
    currentMultiplier: Int,
    valueScale: ChartValueScale = ChartValueScale.LINEAR
): HistoryChartScale {
    val automatic = scaleMode == ChartScaleMode.AUTOMATIC
    val minimum = if (automatic) visibleMinimum else fullMinimum
    val maximum = if (automatic) visibleMaximum else fullMaximum
    if (valueScale == ChartValueScale.LOGARITHMIC) {
        if (minimum == null && maximum == null) {
            return HistoryChartScale(-1.0, 1.0, listOf(-1.0, 0.0, 1.0))
        }
        require(minimum != null && maximum != null)
        val first = fullscreenChartValue(minimum, metric, fahrenheit, currentMultiplier, valueScale)
        val last = fullscreenChartValue(maximum, metric, fahrenheit, currentMultiplier, valueScale)
        val lowerValue = minOf(first, last)
        val upperValue =
            if (metric == HistoryMetric.LEVEL && !automatic) 2.0 else maxOf(first, last)
        val padding = maxOf((upperValue - lowerValue) * 0.05, 0.05)
        val lower = maxOf(lowerValue - padding, log10(Double.MIN_VALUE))
        val upperLimit = if (metric == HistoryMetric.LEVEL) 2.0 else log10(Double.MAX_VALUE)
        val upper = minOf(upperValue + padding, upperLimit)
        return HistoryChartScale(lower, upper, (0..4).map { lower + (upper - lower) * it / 4 })
    }
    if (metric == HistoryMetric.LEVEL && scaleMode != ChartScaleMode.AUTOMATIC) {
        return HistoryChartScale(0.0, 100.0, listOf(0.0, 25.0, 50.0, 75.0, 100.0))
    }
    return historyChartScale(
        HistorySeries(emptyList(), minimum, maximum), metric, fahrenheit, currentMultiplier
    )
}
