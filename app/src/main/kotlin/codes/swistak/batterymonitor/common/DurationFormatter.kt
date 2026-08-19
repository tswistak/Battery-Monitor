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
package codes.swistak.batterymonitor.common

import android.content.res.Resources
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.settings.LongDurationFormat

internal object DurationFormatter {
    internal fun roundedDaysAndHours(
        days: Int, hours: Int, minutes: Int
    ): Pair<Int, Int> {
        val roundedTotalHours = days * 24 + hours + if (minutes >= 30) 1 else 0
        return roundedTotalHours / 24 to roundedTotalHours % 24
    }

    fun formatRoundedDaysAndHours(
        resources: Resources, days: Int, hours: Int, minutes: Int
    ): String {
        val (roundedDays, roundedHours) = roundedDaysAndHours(days, hours, minutes)
        return String.format(
            resources.getQuantityString(R.plurals.n_days, roundedDays), roundedDays
        ) + String.format(
            resources.getQuantityString(R.plurals.n_hours, roundedHours), roundedHours
        )
    }

    fun formatShort(
        resources: Resources, totalMinutes: Int, longDurationFormat: LongDurationFormat
    ): String {
        val safeMinutes = totalMinutes.coerceAtLeast(0)
        val totalHours = safeMinutes / 60
        val minutes = safeMinutes % 60

        if (longDurationFormat == LongDurationFormat.DAYS_AND_HOURS && totalHours >= 24) {
            val days = totalHours / 24
            val hours = totalHours % 24
            return buildList {
                add(String.format(resources.getString(R.string.unit_days), days))
                if (hours > 0) {
                    add(String.format(resources.getString(R.string.unit_hours), hours))
                }
                if (minutes > 0) {
                    add(String.format(resources.getString(R.string.unit_minutes), minutes))
                }
            }.joinToString(" ")
        }

        return String.format(
            resources.getQuantityString(R.plurals.n_hours_short, totalHours), totalHours
        ) + String.format(
            resources.getQuantityString(R.plurals.n_minutes_short, minutes), minutes
        )
    }

    fun formatRoundedHours(
        resources: Resources, totalHours: Int, longDurationFormat: LongDurationFormat
    ): String {
        if (longDurationFormat != LongDurationFormat.DAYS_AND_HOURS || totalHours < 24) {
            return String.format(
                resources.getQuantityString(R.plurals.for_n_hours, totalHours), totalHours
            )
        }

        val duration = formatShort(
            resources, totalHours * 60, LongDurationFormat.DAYS_AND_HOURS
        )
        return resources.getString(R.string.duration_for, duration)
    }
}
