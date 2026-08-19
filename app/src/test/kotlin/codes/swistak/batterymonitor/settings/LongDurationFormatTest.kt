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
package codes.swistak.batterymonitor.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LongDurationFormatTest {
    @Test
    fun `known values are parsed`() {
        assertEquals(
            LongDurationFormat.DAYS_AND_HOURS, LongDurationFormat.fromPreference("days_and_hours")
        )
        assertEquals(
            LongDurationFormat.HOURS_ONLY, LongDurationFormat.fromPreference("hours_only")
        )
    }

    @Test
    fun `missing and unknown values use days and hours format`() {
        assertEquals(LongDurationFormat.DAYS_AND_HOURS, LongDurationFormat.fromPreference(null))
        assertEquals(
            LongDurationFormat.DAYS_AND_HOURS, LongDurationFormat.fromPreference("unsupported")
        )
        assertEquals(
            LongDurationFormat.DAYS_AND_HOURS, LongDurationFormat.fromPreference("automatic")
        )
    }
}
