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

import codes.swistak.batterymonitor.ui.current.CurrentMeasurement
import codes.swistak.batterymonitor.ui.navigation.SectionOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetActionsTest {
    @Test
    fun `status widgets open the current state parent without a measurement or history override`() {
        assertEquals(
            WidgetActions.destination(WidgetConfiguration.defaults(WidgetFamily.STATUS)),
            WidgetDestination(SectionOwner.CURRENT)
        )
    }

    @Test
    fun `each measurement widget tap uses a stable measurement route in current state`() {
        val expected = mapOf(
            WidgetMetric.TEMPERATURE to "temperature",
            WidgetMetric.VOLTAGE to "voltage",
            WidgetMetric.CURRENT to "current",
            WidgetMetric.REMAINING_CHARGE to "remaining-charge"
        )
        expected.forEach { (metric, route) ->
            val destination = WidgetActions.destination(
                WidgetConfiguration.defaults(WidgetFamily.MEASUREMENTS).copy(tapMetric = metric)
            )
            assertEquals(SectionOwner.CURRENT, destination.section)
            assertEquals(route, destination.measurement?.route)
            assertEquals(destination.measurement, CurrentMeasurement.fromRoute(route))
            assertNull(destination.historyHours)
        }
    }

    @Test
    fun `trend widgets open history with their configured rolling range`() {
        listOf(24, 72, 168).forEach { hours ->
            assertEquals(
                WidgetDestination(SectionOwner.HISTORY, historyHours = hours),
                WidgetActions.destination(
                    WidgetConfiguration.defaults(WidgetFamily.TREND).copy(trendHours = hours)
                )
            )
        }
    }

    @Test
    fun `invalid widget ranges fall back to twenty four hours instead of querying an arbitrary period`() {
        listOf(Int.MIN_VALUE, -1, 0, 1, 73, Int.MAX_VALUE).forEach { hours ->
            assertEquals(
                24, WidgetActions.destination(
                    WidgetConfiguration.defaults(WidgetFamily.TREND).copy(trendHours = hours)
                ).historyHours
            )
        }
    }

    @Test
    fun `measurement deep links reject labels old positions and unknown route names`() {
        listOf(null, "", "0", "2", "Temperature", "remaining_charge", "widgets").forEach {
            assertNull(CurrentMeasurement.fromRoute(it))
        }
    }
}
