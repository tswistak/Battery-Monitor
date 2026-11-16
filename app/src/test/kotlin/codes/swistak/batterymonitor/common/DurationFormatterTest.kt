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

import codes.swistak.batterymonitor.R
import org.junit.Assert.assertEquals
import org.junit.Test

import java.util.Locale

class DurationFormatterTest {

    @Test
    fun `compact diagnostic ages keep unit pairs separate and truncate partial seconds`() {
        val units = mapOf(
            R.string.unit_hours to "%dh",
            R.string.unit_minutes to "%dm",
            R.string.unit_seconds to "%ds"
        )
        val cases = listOf(
            Long.MIN_VALUE to "0s",
            -1L to "0s",
            0L to "0s",
            999L to "0s",
            1_000L to "1s",
            32_999L to "32s",
            59_999L to "59s",
            60_000L to "1m",
            92_000L to "1m 32s",
            3_600_000L to "1h",
            3_601_000L to "1h 1s",
            3_723_000L to "1h 2m 3s",
            86_461_000L to "24h 1m 1s"
        )
        cases.forEach { (milliseconds, expected) ->
            assertEquals(
                "$milliseconds milliseconds",
                expected,
                DurationFormatter.formatCompact(Locale.ENGLISH, milliseconds, units::getValue)
            )
        }
        assertEquals(
            "١h ٢m ٣s", DurationFormatter.formatCompact(
                Locale.forLanguageTag("ar"), 3_723_000L, units::getValue
            )
        )
    }

    // JUnit expects the expected Pair first and the actual roundedDaysAndHours result second.
    @Suppress("KotlinMisorderedAssertEqualsArguments")
    @Test
    fun `day and hour display rounds minutes without changing stored duration`() {
        assertEquals(1 to 3, DurationFormatter.roundedDaysAndHours(1, 3, 15))
        assertEquals(1 to 4, DurationFormatter.roundedDaysAndHours(1, 3, 45))
        assertEquals(2 to 0, DurationFormatter.roundedDaysAndHours(1, 23, 45))
    }
}
