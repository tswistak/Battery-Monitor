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
package codes.swistak.batterymonitor.ui.alarms

import codes.swistak.batterymonitor.alarms.AlarmDatabase
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale

internal data class AlarmDraft(
    val id: Int? = null,
    val enabled: Boolean = true,
    val type: String = "fully_charged",
    val threshold: String = "",
    val thresholdInput: String? = null
)

internal fun defaultAlarmThreshold(type: String): String = when (type) {
    "charge_drops" -> "20"
    "charge_rises" -> "90"
    "temp_drops" -> "60"
    "temp_rises" -> "460"
    else -> ""
}

internal fun validAlarmDraft(draft: AlarmDraft): Boolean {
    if (draft.type !in AlarmDatabase.SUPPORTED_TYPES) return false
    return when (draft.type) {
        "charge_drops", "charge_rises" -> draft.threshold.toIntOrNull() in 0..100
        "temp_drops", "temp_rises" -> draft.threshold.toIntOrNull() in -500..1000
        else -> draft.threshold.isEmpty()
    }
}

internal fun alarmThresholdBounds(type: String, convertFahrenheit: Boolean): IntRange =
    if (type == "temp_drops" || type == "temp_rises") {
        if (convertFahrenheit) -58..212 else -50..100
    } else 0..100

internal fun alarmThresholdValue(draft: AlarmDraft, convertFahrenheit: Boolean): Double {
    val threshold =
        draft.threshold.toIntOrNull() ?: defaultAlarmThreshold(draft.type).toIntOrNull() ?: 0
    return if (draft.type == "temp_drops" || draft.type == "temp_rises") {
        if (convertFahrenheit) threshold * 9.0 / 50.0 + 32 else threshold / 10.0
    } else threshold.toDouble()
}

internal fun alarmThresholdInput(
    draft: AlarmDraft, convertFahrenheit: Boolean, locale: Locale
): String {
    if (draft.threshold.toIntOrNull() == null) return draft.thresholdInput ?: draft.threshold
    return NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        maximumFractionDigits =
            if (draft.type == "temp_drops" || draft.type == "temp_rises") 1 else 0
    }.format(alarmThresholdValue(draft, convertFahrenheit))
}

internal fun withAlarmThresholdInput(
    draft: AlarmDraft, text: String, convertFahrenheit: Boolean, locale: Locale
): AlarmDraft {
    val temperature = draft.type == "temp_drops" || draft.type == "temp_rises"
    val raw = parseThreshold(text, temperature, convertFahrenheit, locale) ?: return draft.copy(
        threshold = "", thresholdInput = text
    )
    val threshold = if (raw == draft.threshold.toIntOrNull()) draft.threshold else raw.toString()
    val updated = draft.copy(threshold = threshold, thresholdInput = null)
    return updated.copy(
        thresholdInput = text.takeUnless {
            it == alarmThresholdInput(
                updated, convertFahrenheit, locale
            )
        })
}

private fun parseThreshold(
    input: String, temperature: Boolean, convertFahrenheit: Boolean, locale: Locale
): Int? {
    val text = input.trim()
    if (text.isEmpty()) return null
    val parser = (NumberFormat.getNumberInstance(locale) as DecimalFormat).apply {
        isGroupingUsed = false
        isParseBigDecimal = true
    }
    val separator = parser.decimalFormatSymbols.decimalSeparator
    val signs = parser.negativePrefix + parser.positivePrefix
    if (text.any { !it.isDigit() && it != separator && it !in signs }) return null
    val decimal = text.indexOf(separator)
    if (decimal >= 0 && (!temperature || text.length - decimal - 1 != 1 || !text.last()
            .isDigit())
    ) {
        return null
    }
    val position = ParsePosition(0)
    val number = parser.parse(text, position) as? BigDecimal ?: return null
    if (position.index != text.length) return null
    val bounds = if (temperature) if (convertFahrenheit) -58..212 else -50..100 else 0..100
    if (number < BigDecimal(bounds.first) || number > BigDecimal(bounds.last)) return null
    return try {
        when {
            !temperature -> number.intValueExact()
            !convertFahrenheit -> number.multiply(BigDecimal.TEN).intValueExact()
            else -> number.subtract(BigDecimal(32)).multiply(BigDecimal(50))
                .divide(BigDecimal(9), 0, RoundingMode.HALF_UP).intValueExact()
        }
    } catch (_: ArithmeticException) {
        null
    }
}
