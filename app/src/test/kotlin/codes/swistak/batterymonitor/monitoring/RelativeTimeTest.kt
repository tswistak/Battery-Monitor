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
package codes.swistak.batterymonitor.monitoring

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {
    @Test
    fun `duration over one day keeps exact hours and minutes`() {
        val duration = BatteryInfo.RelativeTime()

        duration.update(
            to = (27 * 60 + 45) * 60 * 1000L, from = 0L
        )

        assertEquals(1, duration.days)
        assertEquals(3, duration.hours)
        assertEquals(45, duration.minutes)
    }
}
