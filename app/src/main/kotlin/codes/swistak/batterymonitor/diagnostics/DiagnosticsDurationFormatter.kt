/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.diagnostics

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.util.Locale

internal object DiagnosticsDurationFormatter {
    fun format(context: Context, elapsedMilliseconds: Long): String {
        return format(context.resources.configuration.locales[0], elapsedMilliseconds)
    }

    fun format(locale: Locale, elapsedMilliseconds: Long): String {
        val totalSeconds = (elapsedMilliseconds / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds % 3600L / 60L
        val seconds = totalSeconds % 60L
        val measures = buildList {
            if (hours > 0L) add(Measure(hours, MeasureUnit.HOUR))
            if (minutes > 0L) add(Measure(minutes, MeasureUnit.MINUTE))
            if (seconds > 0L || isEmpty()) add(Measure(seconds, MeasureUnit.SECOND))
        }
        return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.NARROW)
            .formatMeasures(*measures.toTypedArray())
    }
}
