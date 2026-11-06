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
package codes.swistak.batterymonitor.monitoring.notifications

import android.content.res.Resources
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.DurationFormatter
import codes.swistak.batterymonitor.settings.LongDurationFormat

internal object RemainingTimeMessage {
    fun format(
        resources: Resources,
        days: Int,
        hours: Int,
        minutes: Int,
        untilCharged: Boolean,
        longDurationFormat: LongDurationFormat,
        verbosity: String,
        expanded: Boolean = false
    ): String {
        val displayHours = days * 24 + hours
        val duration: String
        val plural: Int
        val quantity: Int
        val mixed: Boolean
        if (days > 0 && longDurationFormat == LongDurationFormat.DAYS_AND_HOURS) {
            val (roundedDays, roundedHours) = DurationFormatter.roundedDaysAndHours(
                days, hours, minutes
            )
            duration = DurationFormatter.formatRoundedDaysAndHours(resources, days, hours, minutes)
            plural = R.plurals.notification_until_drained_days
            quantity = roundedDays
            mixed = roundedHours > 0
        } else if (displayHours > 0) {
            val hoursPlural = if (verbosity == "condensed") {
                R.plurals.n_hours_medium
            } else {
                R.plurals.n_hours_long
            }
            val minutesPlural = if (verbosity == "verbose") {
                R.plurals.n_minutes_long
            } else {
                R.plurals.n_minutes_medium
            }
            duration = String.format(
                resources.getQuantityString(hoursPlural, displayHours), displayHours
            ) + String.format(resources.getQuantityString(minutesPlural, minutes), minutes)
            plural = R.plurals.notification_until_drained_hours
            quantity = displayHours
            mixed = minutes > 0
        } else {
            duration = String.format(
                resources.getQuantityString(R.plurals.n_minutes_long, minutes), minutes
            )
            plural = R.plurals.notification_until_drained_minutes
            quantity = minutes
            mixed = false
        }

        return when {
            untilCharged -> resources.getString(R.string.notification_until_charged, duration)
            mixed -> resources.getString(
                if (expanded) R.string.notification_until_drained_mixed_expanded
                else R.string.notification_until_drained_mixed, duration
            )

            else -> resources.getQuantityString(plural, quantity, duration)
        }
    }
}
