/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.HistoryChartModel
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistorySeries
import codes.swistak.batterymonitor.monitoring.BatteryCurrent
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

internal fun historyValue(
    value: Double?,
    metric: HistoryMetric,
    fahrenheit: Boolean,
    locale: Locale,
    currentMultiplier: Int = 1
): String {
    if (value == null) return "—"
    return when (metric) {
        HistoryMetric.LEVEL -> String.format(locale, "%.0f%%", value)
        HistoryMetric.TEMPERATURE -> String.format(
            locale,
            "%.1f %s",
            if (fahrenheit) value * 1.8 + 32 else value,
            if (fahrenheit) "°F" else "°C"
        )

        HistoryMetric.VOLTAGE -> String.format(locale, "%.2f V", value)
        HistoryMetric.CURRENT -> BatteryCurrent.formatMilliAmps(
            value * currentMultiplier, locale
        ) + " mA"

        HistoryMetric.POWER -> (if (value * currentMultiplier > 0) "+" else "") + String.format(
            locale, "%.2f W", if (value == 0.0) 0.0 else value * currentMultiplier
        )

        HistoryMetric.REMAINING_CHARGE -> NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 3
        }.format(value) + " mAh"
    }
}


private fun historyUnit(metric: HistoryMetric, fahrenheit: Boolean): String = when (metric) {
    HistoryMetric.LEVEL -> "%"
    HistoryMetric.TEMPERATURE -> if (fahrenheit) "°F" else "°C"
    HistoryMetric.VOLTAGE -> "V"
    HistoryMetric.CURRENT -> "mA"
    HistoryMetric.POWER -> "W"
    HistoryMetric.REMAINING_CHARGE -> "mAh"
}

internal fun historyRangeValue(
    series: HistorySeries,
    metric: HistoryMetric,
    fahrenheit: Boolean,
    locale: Locale,
    currentMultiplier: Int = 1
): String {
    if (series.min == null || series.max == null) return "—"
    val reversed =
        (metric == HistoryMetric.CURRENT || metric == HistoryMetric.POWER) && currentMultiplier < 0
    val maximum = historyValue(
        if (reversed) series.min else series.max, metric, fahrenheit, locale, currentMultiplier
    )
    if (series.min == series.max) return maximum
    return historyValue(
        if (reversed) series.max else series.min, metric, fahrenheit, locale, currentMultiplier
    ).removeSuffix(historyUnit(metric, fahrenheit)).trimEnd() + "–" + maximum
}

internal data class HistoryChartScale(
    val minimum: Double, val maximum: Double, val ticks: List<Double>
)

private fun chartValue(
    value: Double, metric: HistoryMetric, fahrenheit: Boolean, currentMultiplier: Int
) = when (metric) {
    HistoryMetric.TEMPERATURE if fahrenheit -> value * 1.8 + 32
    HistoryMetric.CURRENT, HistoryMetric.POWER -> value * currentMultiplier
    else -> value
}

internal fun historyChartScale(
    series: HistorySeries, metric: HistoryMetric, fahrenheit: Boolean, currentMultiplier: Int = 1
): HistoryChartScale {
    val first = chartValue(series.min ?: 0.0, metric, fahrenheit, currentMultiplier)
    val last = chartValue(series.max ?: series.min ?: 0.0, metric, fahrenheit, currentMultiplier)
    val minimum = minOf(first, last)
    val maximum = maxOf(first, last)
    val fallback = when (metric) {
        HistoryMetric.LEVEL -> 20.0; HistoryMetric.TEMPERATURE -> 5.0; HistoryMetric.VOLTAGE -> 0.2
        HistoryMetric.CURRENT, HistoryMetric.REMAINING_CHARGE -> 100.0
        HistoryMetric.POWER -> 0.5
    }
    val rawStep = maxOf((maximum - minimum) / 3, fallback / 4)
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).first { it * magnitude >= rawStep } * magnitude
    var lower = floor(minimum / step) * step
    var upper = maxOf(ceil(maximum / step) * step, lower + 3 * step)
    if (metric == HistoryMetric.LEVEL) {
        upper = upper.coerceAtMost(100.0)
        lower = minOf(lower, upper - 3 * step).coerceAtLeast(0.0)
    }
    val intervals = ((upper - lower) / step).roundToInt().coerceAtLeast(1)
    return HistoryChartScale(
        lower, upper, (0..intervals).map { lower + (upper - lower) * it / intervals }.reversed()
    )
}

@Composable
internal fun MeasurementChart(
    model: HistoryChartModel,
    metric: HistoryMetric,
    fahrenheit: Boolean,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    currentMultiplier: Int = 1
) {
    val locale = LocalConfiguration.current.locales[0]
    val series = model.series.getValue(metric)
    val points = remember(series) { series.points.filter { it.value != null } }
    if (points.isEmpty()) {
        Text(
            stringResource(R.string.current_unavailable),
            modifier,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val selected = points.firstOrNull { it.key.id == selectedId } ?: points.lastOrNull()
    val scale = remember(series, metric, fahrenheit, compact, currentMultiplier) {
        if (compact) {
            val first = chartValue(series.min ?: 0.0, metric, fahrenheit, currentMultiplier)
            val last = chartValue(series.max ?: 1.0, metric, fahrenheit, currentMultiplier)
            HistoryChartScale(
                minOf(first, last), maxOf(first, last, minOf(first, last) + 0.1), emptyList()
            )
        } else historyChartScale(series, metric, fahrenheit, currentMultiplier)
    }
    val formatter = remember(locale, compact) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
            .withZone(ZoneId.systemDefault())
    }

    fun time(value: Long) = formatter.format(Instant.ofEpochMilli(value))
    val axisFormatter = remember(locale, model.range, compact) {
        (if (model.range.end - model.range.start <= 48 * 3_600_000L || compact) DateTimeFormatter.ofLocalizedTime(
            FormatStyle.SHORT
        ) else DateTimeFormatter.ofLocalizedDate(
            FormatStyle.SHORT
        )).withLocale(locale).withZone(ZoneId.systemDefault())
    }
    val numberFormat = remember(locale) {
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
    }
    val description = stringResource(
        R.string.history_chart_description,
        time(model.range.start),
        time(model.range.end - 1),
        historyValue(
            if ((metric == HistoryMetric.CURRENT || metric == HistoryMetric.POWER) && currentMultiplier < 0) series.max else series.min,
            metric,
            fahrenheit,
            locale,
            currentMultiplier
        ),
        historyValue(
            if ((metric == HistoryMetric.CURRENT || metric == HistoryMetric.POWER) && currentMultiplier < 0) series.min else series.max,
            metric,
            fahrenheit,
            locale,
            currentMultiplier
        )
    )
    val previous = stringResource(R.string.history_previous_point)
    val next = stringResource(R.string.history_next_point)
    fun move(delta: Int): Boolean {
        if (points.isEmpty()) return false
        val index = points.indexOf(selected).coerceAtLeast(0)
        onSelect(points[(index + delta).coerceIn(0, points.lastIndex)].key.id)
        return true
    }

    val line = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val eventColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!compact) {
            val formatted =
                historyValue(selected?.value, metric, fahrenheit, locale, currentMultiplier)
            val unit = historyUnit(metric, fahrenheit)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    formatted.removeSuffix(unit).trim(),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.alignByBaseline()
                )
                if (selected != null) Text(
                    unit,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.alignByBaseline()
                )
            }
            selected?.let {
                Text(
                    stringResource(
                        if (it == points.lastOrNull()) R.string.history_last_reading else R.string.history_selected_reading,
                        axisFormatter.format(Instant.ofEpochMilli(it.key.time))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = if (compact) 0.dp else 8.dp)
        ) {
            if (!compact) Column(
                Modifier
                    .widthIn(min = 28.dp)
                    .height(160.dp)
                    .padding(end = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                scale.ticks.forEach { tick ->
                    Text(
                        numberFormat.format(tick),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Canvas(Modifier
                    .fillMaxWidth()
                    .height(if (compact) 56.dp else 160.dp)
                    .semantics {
                        contentDescription = description
                        stateDescription = selected?.let {
                            "${time(it.key.time)}, ${
                                historyValue(
                                    it.value, metric, fahrenheit, locale, currentMultiplier
                                )
                            }"
                        } ?: "—"
                        if (!compact) customActions = listOf(
                            CustomAccessibilityAction(previous) { move(-1) },
                            CustomAccessibilityAction(next) { move(1) })
                    }
                    .onKeyEvent {
                        if (it.type != KeyEventType.KeyDown || compact) false
                        else when (it.key) {
                            Key.DirectionLeft -> move(-1); Key.DirectionRight -> move(1); else -> false
                        }
                    }
                    .focusable(!compact)
                    .pointerInput(points, model.range, onSelect) {
                        if (!compact) detectTapGestures { offset ->
                            val fraction =
                                ((offset.x - 8.dp.toPx()) / (size.width - 16.dp.toPx())).coerceIn(
                                    0f, 1f
                                )
                            val timestamp =
                                model.range.start + fraction.toDouble() * (model.range.end - model.range.start)
                            points.minByOrNull { abs(it.key.time - timestamp) }
                                ?.let { onSelect(it.key.id) }
                        }
                    }) {
                    val inset = 8.dp.toPx()
                    fun x(time: Long) =
                        inset + ((time - model.range.start).toDouble() / (model.range.end - model.range.start) * (size.width - 2 * inset)).toFloat()

                    fun y(value: Double) =
                        size.height - inset - ((value - scale.minimum) / (scale.maximum - scale.minimum) * (size.height - 2 * inset)).toFloat()

                    val dashed = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx()))
                    for (tick in if (compact) listOf(scale.minimum) else scale.ticks) {
                        drawLine(
                            outline,
                            Offset(inset, y(tick)),
                            Offset(size.width - inset, y(tick)),
                            1.dp.toPx(),
                            pathEffect = dashed
                        )
                    }
                    if (!compact) model.events.forEach { event ->
                        listOf(event.first, event.last).distinct().forEach { time ->
                            drawLine(
                                eventColor,
                                Offset(x(time), inset),
                                Offset(x(time), size.height - inset),
                                1.dp.toPx(),
                                pathEffect = when (event.code) {
                                    2 -> PathEffect.dashPathEffect(
                                        floatArrayOf(
                                            3.dp.toPx(), 4.dp.toPx()
                                        )
                                    )

                                    0 -> PathEffect.dashPathEffect(
                                        floatArrayOf(
                                            7.dp.toPx(), 4.dp.toPx()
                                        )
                                    )

                                    else -> PathEffect.dashPathEffect(
                                        floatArrayOf(
                                            1.dp.toPx(), 4.dp.toPx()
                                        )
                                    )
                                }
                            )
                        }
                    }
                    points.zipWithNext().forEach { (first, last) ->
                        if (first.segment == last.segment) drawLine(
                            line, Offset(
                                x(first.key.time),
                                y(chartValue(first.value!!, metric, fahrenheit, currentMultiplier))
                            ), Offset(
                                x(last.key.time),
                                y(chartValue(last.value!!, metric, fahrenheit, currentMultiplier))
                            ), if (compact) 2.5.dp.toPx() else 3.dp.toPx(), cap = StrokeCap.Round
                        )
                    }
                    points.forEachIndexed { index, point ->
                        val isolated =
                            points.getOrNull(index - 1)?.segment != point.segment && points.getOrNull(
                                index + 1
                            )?.segment != point.segment
                        if (point == selected || isolated) drawCircle(
                            line, if (point == selected) 4.dp.toPx() else 2.5.dp.toPx(), Offset(
                                x(point.key.time),
                                y(chartValue(point.value!!, metric, fahrenheit, currentMultiplier))
                            )
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    for (index in 0..(if (compact) 1 else 3)) {
                        val steps = if (compact) 1 else 3
                        val timestamp =
                            model.range.start + (model.range.end - model.range.start - 1) * index / steps
                        Text(
                            axisFormatter.format(Instant.ofEpochMilli(timestamp)),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = when (index) {
                                0 -> androidx.compose.ui.text.style.TextAlign.Start; steps -> androidx.compose.ui.text.style.TextAlign.End; else -> androidx.compose.ui.text.style.TextAlign.Center
                            }
                        )
                    }
                }
            }
        }
        if (!compact) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                painterResource(R.drawable.ui_info),
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.history_observations),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
