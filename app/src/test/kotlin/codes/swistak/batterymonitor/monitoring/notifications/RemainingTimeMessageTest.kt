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

import android.app.Application
import android.content.res.Resources
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.settings.LongDurationFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RemainingTimeMessageTest {
    private data class ResourceCall(
        val id: Int, val quantity: Int? = null, val arguments: List<Any?> = emptyList()
    )

    @Suppress("DEPRECATION")
    private class RecordingResources(base: Resources) :
        Resources(base.assets, base.displayMetrics, base.configuration) {
        val calls = mutableListOf<ResourceCall>()
        val templateOutput = "synthetic-template-output"

        override fun getQuantityString(id: Int, quantity: Int): String {
            calls += ResourceCall(id, quantity)
            return "[$id:%s]"
        }

        override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String {
            calls += ResourceCall(id, quantity, formatArgs.toList())
            return templateOutput
        }

        override fun getString(id: Int, vararg formatArgs: Any?): String {
            calls += ResourceCall(id, arguments = formatArgs.toList())
            return templateOutput
        }

        override fun getString(id: Int): String = error("Unexpected unformatted resource: $id")
    }

    private val resources = RecordingResources(RuntimeEnvironment.getApplication().resources)

    private fun message(
        days: Int = 0,
        hours: Int = 0,
        minutes: Int = 0,
        untilCharged: Boolean = false,
        longDurationFormat: LongDurationFormat = LongDurationFormat.DAYS_AND_HOURS,
        verbosity: String = "normal",
        expanded: Boolean = false
    ) {
        resources.calls.clear()
        assertEquals(
            resources.templateOutput, RemainingTimeMessage.format(
                resources,
                days,
                hours,
                minutes,
                untilCharged,
                longDurationFormat,
                verbosity,
                expanded
            )
        )
    }

    private fun assertSelection(
        template: Int, quantity: Int? = null, vararg units: Pair<Int, Int>
    ) {
        val duration = units.joinToString("") { (id, count) -> "[$id:$count]" }
        assertEquals(units.map { (id, count) -> ResourceCall(id, count) } + ResourceCall(
            template, quantity, listOf(duration)
        ), resources.calls)
    }

    @Test
    fun `minute-only messages select minute grammar with the displayed count including zero`() {
        for (minutes in listOf(0, 1, 2, 5, 12, 21, 22)) {
            message(minutes = minutes)
            assertSelection(
                R.plurals.notification_until_drained_minutes,
                minutes,
                R.plurals.n_minutes_long to minutes
            )
        }
    }

    @Test
    fun `whole days select day grammar without using total minutes or zero trailing hours`() {
        for (days in listOf(1, 2, 4, 5, 12, 21, 22)) {
            message(days = days)
            assertSelection(
                R.plurals.notification_until_drained_days,
                days,
                R.plurals.n_days to days,
                R.plurals.n_hours to 0
            )
        }
        message(days = 1, hours = 23, minutes = 30)
        assertSelection(
            R.plurals.notification_until_drained_days,
            2,
            R.plurals.n_days to 2,
            R.plurals.n_hours to 0
        )
        message(days = 1, hours = 23, minutes = 29)
        assertSelection(
            R.string.notification_until_drained_mixed,
            null,
            R.plurals.n_days to 1,
            R.plurals.n_hours to 23
        )
    }

    @Test
    fun `hour messages select displayed hour grammar and the requested verbosity units`() {
        for ((verbosity, hourUnit, minuteUnit) in listOf(
            Triple("normal", R.plurals.n_hours_long, R.plurals.n_minutes_medium),
            Triple("condensed", R.plurals.n_hours_medium, R.plurals.n_minutes_medium),
            Triple("verbose", R.plurals.n_hours_long, R.plurals.n_minutes_long)
        )) {
            for (hours in listOf(1, 2, 5, 12, 21, 22)) {
                message(hours = hours, verbosity = verbosity)
                assertSelection(
                    R.plurals.notification_until_drained_hours,
                    hours,
                    hourUnit to hours,
                    minuteUnit to 0
                )
            }
            for ((expanded, template) in listOf(
                false to R.string.notification_until_drained_mixed,
                true to R.string.notification_until_drained_mixed_expanded
            )) {
                message(hours = 1, minutes = 30, verbosity = verbosity, expanded = expanded)
                assertSelection(template, null, hourUnit to 1, minuteUnit to 30)
            }
        }
    }

    @Test
    fun `mixed day and hour output selects the ordinary or expanded template`() {
        for ((expanded, template) in listOf(
            false to R.string.notification_until_drained_mixed,
            true to R.string.notification_until_drained_mixed_expanded
        )) {
            for (verbosity in listOf("normal", "condensed", "verbose")) {
                message(days = 1, hours = 2, verbosity = verbosity, expanded = expanded)
                assertSelection(template, null, R.plurals.n_days to 1, R.plurals.n_hours to 2)
            }
        }
    }

    @Test
    fun `all hours mode retains minutes without rounding into days`() {
        message(
            days = 1, hours = 23, minutes = 30, longDurationFormat = LongDurationFormat.HOURS_ONLY
        )
        assertSelection(
            R.string.notification_until_drained_mixed,
            null,
            R.plurals.n_hours_long to 47,
            R.plurals.n_minutes_medium to 30
        )
        message(days = 2, verbosity = "verbose", longDurationFormat = LongDurationFormat.HOURS_ONLY)
        assertSelection(
            R.plurals.notification_until_drained_hours,
            48,
            R.plurals.n_hours_long to 48,
            R.plurals.n_minutes_long to 0
        )
    }

    @Test
    fun `charging selects its complete template ahead of single and mixed discharge templates`() {
        for (expanded in listOf(false, true)) {
            message(minutes = 1, untilCharged = true, expanded = expanded)
            assertSelection(
                R.string.notification_until_charged, null, R.plurals.n_minutes_long to 1
            )
            message(hours = 1, minutes = 30, untilCharged = true, expanded = expanded)
            assertSelection(
                R.string.notification_until_charged,
                null,
                R.plurals.n_hours_long to 1,
                R.plurals.n_minutes_medium to 30
            )
            message(days = 1, hours = 23, minutes = 30, untilCharged = true, expanded = expanded)
            assertSelection(
                R.string.notification_until_charged,
                null,
                R.plurals.n_days to 2,
                R.plurals.n_hours to 0
            )
        }
    }
}
