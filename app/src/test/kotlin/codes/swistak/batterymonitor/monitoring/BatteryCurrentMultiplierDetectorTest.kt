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
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryCurrentMultiplierDetectorTest {
    @Test
    fun `keeps standard units and sign`() {
        assertEquals(
            1, BatteryCurrentMultiplierDetector.detect(
                750.0, BatteryInfo.STATUS_CHARGING, 50
            )
        )
        assertEquals(
            1, BatteryCurrentMultiplierDetector.detect(
                -250.0, BatteryInfo.STATUS_DISCHARGING, 50
            )
        )
    }

    @Test
    fun `corrects a reversed sign`() {
        assertEquals(
            -1, BatteryCurrentMultiplierDetector.detect(
                -750.0, BatteryInfo.STATUS_CHARGING, 50
            )
        )
        assertEquals(
            -1, BatteryCurrentMultiplierDetector.detect(
                250.0, BatteryInfo.STATUS_UNPLUGGED, 50
            )
        )
    }

    @Test
    fun `selects the smallest magnitude correction reaching a typical value`() {
        assertEquals(
            10, BatteryCurrentMultiplierDetector.detect(
                75.0, BatteryInfo.STATUS_CHARGING, 50
            )
        )
        assertEquals(
            -100, BatteryCurrentMultiplierDetector.detect(
                -7.5, BatteryInfo.STATUS_CHARGING, 50
            )
        )
        assertEquals(
            1000, BatteryCurrentMultiplierDetector.detect(
                -0.25, BatteryInfo.STATUS_DISCHARGING, 50
            )
        )
    }

    @Test
    fun `does not guess while charging current may be tapering`() {
        assertNull(
            BatteryCurrentMultiplierDetector.detect(
                75.0, BatteryInfo.STATUS_CHARGING, 90
            )
        )
    }

    @Test
    fun `does not guess without a directional battery state or usable reading`() {
        assertNull(
            BatteryCurrentMultiplierDetector.detect(
                750.0, BatteryInfo.STATUS_FULLY_CHARGED, 100
            )
        )
        assertNull(
            BatteryCurrentMultiplierDetector.detect(
                0.0, BatteryInfo.STATUS_DISCHARGING, 50
            )
        )
        assertNull(
            BatteryCurrentMultiplierDetector.detect(
                Double.NaN, BatteryInfo.STATUS_CHARGING, 50
            )
        )
    }
}
