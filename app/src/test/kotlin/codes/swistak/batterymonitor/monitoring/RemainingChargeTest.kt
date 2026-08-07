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

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemainingChargeTest {
    @Test
    fun `reader returns charge counter reported by Android`() {
        var requestedProperty = 0
        val reader = RemainingChargeReader({ property ->
            requestedProperty = property
            2_847_300
        })

        assertEquals(2_847_300L, reader.readMicroAmpHours())
        assertEquals(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, requestedProperty)
    }

    @Test
    fun `reader preserves a reported zero`() {
        val reader = RemainingChargeReader({ 0 })

        assertEquals(0L, reader.readMicroAmpHours())
    }

    @Test
    fun `reader rejects unsupported and negative values`() {
        assertNull(
            RemainingChargeReader({ Int.MIN_VALUE }).readMicroAmpHours()
        )
        assertNull(
            RemainingChargeReader({ -1 }).readMicroAmpHours()
        )
    }

    @Test
    fun `reader handles runtime failures`() {
        var failures = 0
        val reader = RemainingChargeReader(
            getIntProperty = { throw IllegalStateException("failure") },
            onReadFailure = { failures++ })

        assertNull(reader.readMicroAmpHours())
        assertEquals(1, failures)
    }

}
