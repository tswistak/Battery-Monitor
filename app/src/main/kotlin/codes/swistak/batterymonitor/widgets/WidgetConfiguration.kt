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
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.ui.theme.Brightness
import codes.swistak.batterymonitor.ui.theme.ColorSource
import java.util.Collections

internal enum class WidgetFamily(val providerName: String) {
    STATUS("codes.swistak.batterymonitor.widgets.StatusWidgetProvider"), MEASUREMENTS("codes.swistak.batterymonitor.widgets.MeasurementsWidgetProvider"), TREND(
        "codes.swistak.batterymonitor.widgets.TrendWidgetProvider"
    )
}

internal enum class WidgetMetric { TEMPERATURE, VOLTAGE, CURRENT, REMAINING_CHARGE }
internal enum class WidgetVariant { ADAPTIVE, COMPACT }
internal enum class WidgetTapAction { CURRENT_STATE, HISTORY, MEASUREMENT }
internal enum class WidgetStatusExtra { NONE, TEMPERATURE, REMAINING_CHARGE }

internal fun trendRangeLabel(hours: Int): Int = when (hours) {
    72 -> R.string.history_last_3d
    168 -> R.string.history_last_7d
    else -> R.string.history_last_24h
}

internal fun trendMetricLabel(metric: HistoryMetric): Int = when (metric) {
    HistoryMetric.LEVEL -> R.string.history_battery_level
    HistoryMetric.TEMPERATURE -> R.string.current_temperature
    HistoryMetric.VOLTAGE -> R.string.current_voltage
    HistoryMetric.CURRENT -> R.string.pref_cat_battery_current_main
    HistoryMetric.POWER -> R.string.battery_power
    HistoryMetric.REMAINING_CHARGE -> R.string.remaining_charge
}

internal data class WidgetConfiguration(
    val brightness: Brightness? = null,
    val colorSource: ColorSource? = null,
    val temperatureUnit: String? = null,
    val backgroundOpacityPercent: Int = 100,
    val metricOrder: List<WidgetMetric> = WidgetMetric.entries,
    val selectedMetrics: Set<WidgetMetric> = WidgetMetric.entries.toSet(),
    val statusExtra: WidgetStatusExtra = WidgetStatusExtra.TEMPERATURE,
    val variant: WidgetVariant = WidgetVariant.ADAPTIVE,
    val tapAction: WidgetTapAction = WidgetTapAction.CURRENT_STATE,
    val tapMetric: WidgetMetric = WidgetMetric.CURRENT,
    val trendHours: Int = 24,
    val showYAxisLabels: Boolean = true,
    val trendMetric: HistoryMetric = HistoryMetric.LEVEL
) {
    val metrics: List<WidgetMetric> get() = metricOrder.filter(selectedMetrics::contains)

    fun canToggleMetric(metric: WidgetMetric): Boolean =
        if (metric in selectedMetrics) selectedMetrics.size > 2 else selectedMetrics.size < 4

    fun toggleMetric(metric: WidgetMetric): WidgetConfiguration = when {
        !canToggleMetric(metric) -> this
        metric in selectedMetrics -> copy(selectedMetrics = selectedMetrics - metric)
        else -> copy(selectedMetrics = selectedMetrics + metric)
    }

    fun moveMetric(metric: WidgetMetric, offset: Int): WidgetConfiguration {
        val from = metricOrder.indexOf(metric)
        val to = from + offset
        if (from !in metricOrder.indices || to !in metricOrder.indices) return this
        return copy(
            metricOrder = metricOrder.toMutableList().apply { Collections.swap(this, from, to) })
    }

    fun isValid(): Boolean = backgroundOpacityPercent in 0..100 && temperatureUnit in listOf(
        null, TEMPERATURE_CELSIUS, TEMPERATURE_FAHRENHEIT
    ) && selectedMetrics.size in 2..4 && metricOrder.size == WidgetMetric.entries.size && metricOrder.toSet()
        .containsAll(WidgetMetric.entries) && trendHours in listOf(
        24, 72, 168
    )

    internal fun encode(): String = listOf(
        SCHEMA_VERSION.toString(),
        brightness?.name.orEmpty(),
        colorSource?.name.orEmpty(),
        temperatureUnit.orEmpty(),
        backgroundOpacityPercent.toString(),
        WidgetMetric.entries.filter(selectedMetrics::contains).joinToString(",") { it.name },
        statusExtra.name,
        variant.name,
        tapAction.name,
        tapMetric.name,
        trendHours.toString(),
        showYAxisLabels.toString(),
        metricOrder.joinToString(",") { it.name },
        trendMetric.name
    ).joinToString("|")

    companion object {
        const val TEMPERATURE_CELSIUS = "celsius"
        const val TEMPERATURE_FAHRENHEIT = "fahrenheit"
        private const val SCHEMA_VERSION = 4

        fun defaults(family: WidgetFamily): WidgetConfiguration = WidgetConfiguration(
            tapAction = when (family) {
                WidgetFamily.STATUS -> WidgetTapAction.CURRENT_STATE
                WidgetFamily.MEASUREMENTS -> WidgetTapAction.MEASUREMENT
                WidgetFamily.TREND -> WidgetTapAction.HISTORY
            }
        )

        internal fun decode(value: String): WidgetConfiguration? = runCatching {
            val parts = value.split('|')
            val trendMetric = when (parts[0].toInt()) {
                3 -> {
                    require(parts.size == 13)
                    HistoryMetric.LEVEL
                }

                SCHEMA_VERSION -> {
                    require(parts.size == 14)
                    HistoryMetric.valueOf(parts[13])
                }

                else -> return null
            }
            val selected = parts[5].split(',').map(WidgetMetric::valueOf)
            require(selected.distinct().size == selected.size)
            val order = parts[12].split(',').map(WidgetMetric::valueOf)
            WidgetConfiguration(
                brightness = parts[1].takeIf(String::isNotEmpty)?.let(Brightness::valueOf),
                colorSource = parts[2].takeIf(String::isNotEmpty)?.let(ColorSource::valueOf),
                temperatureUnit = parts[3].takeIf(String::isNotEmpty),
                backgroundOpacityPercent = parts[4].toInt(),
                metricOrder = order,
                selectedMetrics = selected.toSet(),
                statusExtra = WidgetStatusExtra.valueOf(parts[6]),
                variant = WidgetVariant.valueOf(parts[7]),
                tapAction = WidgetTapAction.valueOf(parts[8]),
                tapMetric = WidgetMetric.valueOf(parts[9]),
                trendHours = parts[10].toInt(),
                showYAxisLabels = parts[11].toBooleanStrict(),
                trendMetric = trendMetric
            ).takeIf(WidgetConfiguration::isValid)
        }.getOrNull()
    }
}
