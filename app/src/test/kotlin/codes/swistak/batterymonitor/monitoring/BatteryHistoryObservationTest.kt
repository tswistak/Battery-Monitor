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

import codes.swistak.batterymonitor.logs.LogDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryHistoryObservationTest {
    @Test
    fun unavailable_fields_never_become_UI_defaults_or_previous_readings() {
        val reading = batteryHistoryObservation(emptyMap(), 123, null, null, 50)
        assertNull(reading.charge)
        assertNull(reading.temperature)
        assertNull(reading.voltage)
        assertNull(reading.currentMicroAmps)
        assertNull(reading.remainingChargeMicroampHours)
        assertArrayEquals(intArrayOf(1, 3, 0), LogDatabase.decodeStatus(reading.status))
        val invalid = batteryHistoryObservation(
            mapOf(
                "level" to -1, "scale" to 0, "voltage" to 0, "temperature" to Int.MIN_VALUE
            ), 123, Long.MIN_VALUE, -1, 50
        )
        assertEquals(reading, invalid)
    }

    @Test
    fun resolved_standard_and_privileged_fields_preserve_actual_readings_and_valid_zeros() {
        val fields = mergeBatteryFields(
            mapOf("level" to 50, "scale" to 100, "plugged" to 0),
            PrivilegedBatteryReading(status = 3, temperatureTenthsC = 0, voltageMillivolts = 4000)
        )
        val observation = batteryHistoryObservation(fields, 123, 0, 0, 50)
        assertEquals(50, observation.charge)
        assertEquals(0, observation.temperature)
        assertEquals(4000, observation.voltage)
        assertEquals(0L, observation.currentMicroAmps)
        assertEquals(0L, observation.remainingChargeMicroampHours)
        assertEquals(123L, observation.time)
        assertEquals(0, LogDatabase.decodeStatus(observation.status)[0])
        val lost =
            batteryHistoryObservation(mergeBatteryFields(emptyMap(), null), 124, null, null, 50)
        assertNull(lost.voltage)
        assertNull(lost.temperature)
        assertNull(lost.charge)
    }
}
