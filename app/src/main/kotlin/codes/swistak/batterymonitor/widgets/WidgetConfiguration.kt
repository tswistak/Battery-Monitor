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

import codes.swistak.batterymonitor.ui.theme.Brightness
import codes.swistak.batterymonitor.ui.theme.ColorSource

internal enum class WidgetFamily(val providerName: String) {
    STATUS("codes.swistak.batterymonitor.widgets.StatusWidgetProvider"), MEASUREMENTS("codes.swistak.batterymonitor.widgets.MeasurementsWidgetProvider"), TREND(
        "codes.swistak.batterymonitor.widgets.TrendWidgetProvider"
    )
}

internal enum class WidgetMetric { TEMPERATURE, VOLTAGE, CURRENT, REMAINING_CHARGE }
internal enum class WidgetVariant { ADAPTIVE, COMPACT }
internal enum class WidgetTapAction { CURRENT_STATE, HISTORY, MEASUREMENT }
internal enum class WidgetStatusExtra { NONE, TEMPERATURE, REMAINING_CHARGE }

internal data class WidgetConfiguration(
    val brightness: Brightness? = null,
    val colorSource: ColorSource? = null,
    val temperatureUnit: String? = null,
    val backgroundOpacityPercent: Int = 100,
    val metrics: List<WidgetMetric> = WidgetMetric.entries.toList(),
    val statusExtra: WidgetStatusExtra = WidgetStatusExtra.TEMPERATURE,
    val variant: WidgetVariant = WidgetVariant.ADAPTIVE,
    val tapAction: WidgetTapAction = WidgetTapAction.CURRENT_STATE,
    val tapMetric: WidgetMetric = WidgetMetric.CURRENT,
    val trendHours: Int = 24
) {
    fun isValid(): Boolean = backgroundOpacityPercent in 0..100 && temperatureUnit in listOf(
        null, TEMPERATURE_CELSIUS, TEMPERATURE_FAHRENHEIT
    ) && metrics.size in 2..4 && metrics.distinct().size == metrics.size && trendHours in listOf(
        24, 72, 168
    )

    internal fun encode(): String = listOf(
        SCHEMA_VERSION.toString(),
        brightness?.name.orEmpty(),
        colorSource?.name.orEmpty(),
        temperatureUnit.orEmpty(),
        backgroundOpacityPercent.toString(),
        metrics.joinToString(",") { it.name },
        statusExtra.name,
        variant.name,
        tapAction.name,
        tapMetric.name,
        trendHours.toString()
    ).joinToString("|")

    companion object {
        const val TEMPERATURE_CELSIUS = "celsius"
        const val TEMPERATURE_FAHRENHEIT = "fahrenheit"
        private const val SCHEMA_VERSION = 1

        fun defaults(family: WidgetFamily): WidgetConfiguration = WidgetConfiguration(
            tapAction = when (family) {
                WidgetFamily.STATUS -> WidgetTapAction.CURRENT_STATE
                WidgetFamily.MEASUREMENTS -> WidgetTapAction.MEASUREMENT
                WidgetFamily.TREND -> WidgetTapAction.HISTORY
            }
        )

        internal fun decode(value: String): WidgetConfiguration? = runCatching {
            val parts = value.split('|')
            require(parts.size == 11 && parts[0].toInt() == SCHEMA_VERSION)
            WidgetConfiguration(
                brightness = parts[1].takeIf(String::isNotEmpty)?.let(Brightness::valueOf),
                colorSource = parts[2].takeIf(String::isNotEmpty)?.let(ColorSource::valueOf),
                temperatureUnit = parts[3].takeIf(String::isNotEmpty),
                backgroundOpacityPercent = parts[4].toInt(),
                metrics = parts[5].split(',').map(WidgetMetric::valueOf),
                statusExtra = WidgetStatusExtra.valueOf(parts[6]),
                variant = WidgetVariant.valueOf(parts[7]),
                tapAction = WidgetTapAction.valueOf(parts[8]),
                tapMetric = WidgetMetric.valueOf(parts[9]),
                trendHours = parts[10].toInt()
            ).takeIf(WidgetConfiguration::isValid)
        }.getOrNull()
    }
}
