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

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatterTest {
    @Test
    fun `day and hour display rounds minutes without changing stored duration`() {
        assertEquals(1 to 3, DurationFormatter.roundedDaysAndHours(1, 3, 15))
        assertEquals(1 to 4, DurationFormatter.roundedDaysAndHours(1, 3, 45))
        assertEquals(2 to 0, DurationFormatter.roundedDaysAndHours(1, 23, 45))
    }
}
