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
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryReadingFallbackTest {
    private val fallback = PrivilegedBatteryReading(
        percent = 80,
        status = 2,
        health = 2,
        plugged = 2,
        temperatureTenthsC = 310,
        voltageMillivolts = 4200
    )

    @Test
    fun `valid Android readings take precedence including empty battery zero temperature and unplugged`() {
        val android = mapOf(
            "level" to 0,
            "scale" to 200,
            "status" to 3,
            "health" to 7,
            "plugged" to 0,
            "temperature" to 0,
            "voltage" to 3700
        )

        assertTrue(missingBatteryFields(android).isEmpty())
        assertEquals(android, mergeBatteryFields(android, fallback))
    }

    @Test
    fun `missing Android fields receive the same normalized readings used by monitoring`() {
        assertEquals(
            mapOf(
                "level" to 80,
                "scale" to 100,
                "status" to 2,
                "health" to 2,
                "plugged" to 2,
                "temperature" to 310,
                "voltage" to 4200
            ), mergeBatteryFields(emptyMap(), fallback)
        )
    }

    @Test
    fun `unsupported enums invalid voltage and zero scale can use privileged readings`() {
        val android = mapOf(
            "level" to 50,
            "scale" to 0,
            "status" to 1,
            "health" to 1,
            "plugged" to 3,
            "temperature" to Int.MIN_VALUE,
            "voltage" to 0
        )

        assertEquals(
            mergeBatteryFields(emptyMap(), fallback), mergeBatteryFields(android, fallback)
        )
    }

    @Test
    fun `partial fallback preserves Android values and leaves unavailable fields missing`() {
        val android = mapOf("level" to 50, "scale" to 200, "temperature" to 250)

        assertEquals(
            android + ("voltage" to 3800),
            mergeBatteryFields(android, PrivilegedBatteryReading(voltageMillivolts = 3800))
        )
        assertEquals(android, mergeBatteryFields(android, null))
    }
}
