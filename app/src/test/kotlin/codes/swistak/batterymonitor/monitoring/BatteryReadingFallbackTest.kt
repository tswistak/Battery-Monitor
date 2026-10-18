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

    @Test
    fun `sources follow valid Android fields and the exact resolved voltage file`() {
        val fields = mapOf("temperature" to 0, "health" to 7, "voltage" to 3700)
        val voltageSource = "/sys/class/power_supply/bms/voltage_now"
        val fallbackWithSources = fallback.copy(
            fieldSources = mapOf(
                "temperature" to "dumpsys battery / temperature",
                "health" to "dumpsys battery / health",
                "voltage" to "dumpsys battery / voltage"
            )
        )

        assertEquals(
            mapOf(
                "temperature" to "ACTION_BATTERY_CHANGED / temperature",
                "health" to "ACTION_BATTERY_CHANGED / health",
                "voltage" to voltageSource
            ), mergeBatteryFieldSources(fields, fallbackWithSources, voltageSource)
        )
    }

    @Test
    fun `invalid Android fields use provenance only for fallback values actually selected`() {
        val fields = mapOf("temperature" to 250, "health" to 1, "voltage" to 0)
        val fallbackWithSources = fallback.copy(
            fieldSources = mapOf(
                "temperature" to "dumpsys battery / temperature",
                "health" to "/sys/class/power_supply/battery/health",
                "voltage" to "dumpsys battery / voltage"
            )
        )

        assertEquals(
            mapOf(
                "temperature" to "ACTION_BATTERY_CHANGED / temperature",
                "health" to "/sys/class/power_supply/battery/health",
                "voltage" to "dumpsys battery / voltage"
            ), mergeBatteryFieldSources(fields, fallbackWithSources, null)
        )
        assertEquals(
            mapOf("voltage" to "ACTION_BATTERY_CHANGED / voltage"), mergeBatteryFieldSources(
                mapOf("voltage" to 3800), fallback, "ACTION_BATTERY_CHANGED / voltage"
            )
        )
    }

    @Test
    fun `missing defaults and values without known provenance do not claim a source`() {
        assertTrue(mergeBatteryFieldSources(emptyMap(), null, null).isEmpty())
        assertTrue(
            mergeBatteryFieldSources(
                mapOf("temperature" to Int.MIN_VALUE, "health" to 1, "voltage" to 0),
                null,
                "ACTION_BATTERY_CHANGED / voltage"
            ).isEmpty()
        )
        assertTrue(mergeBatteryFieldSources(mapOf("voltage" to 3800), null, null).isEmpty())
        assertTrue(
            mergeBatteryFieldSources(
                emptyMap(),
                PrivilegedBatteryReading(fieldSources = mapOf("temperature" to "dumpsys battery / temperature")),
                null
            ).isEmpty()
        )
    }

    @Test
    fun `valid direct readings use their acquisition times including zero temperature`() {
        val fields = mapOf("temperature" to 0, "health" to 7, "voltage" to 3700)
        val directTimes = mapOf(
            "temperature" to BatteryMeasurementTime(1_000, 100),
            "health" to BatteryMeasurementTime(1_100, 200),
            "voltage" to BatteryMeasurementTime(1_200, 300)
        )
        val fallbackWithTimes = fallback.copy(
            fieldMeasurementTimes = fields.keys.associateWith { BatteryMeasurementTime(900, 90) })

        val merged = mergeBatteryFields(fields, fallbackWithTimes)

        assertEquals(0, merged["temperature"])
        assertEquals(7, merged["health"])
        assertEquals(3700, merged["voltage"])
        assertEquals(
            directTimes, mergeBatteryFieldMeasurementTimes(fields, fallbackWithTimes, directTimes)
        )
    }

    @Test
    fun `invalid direct fields use acquisition times of the fallback values actually selected`() {
        val fields = mapOf("temperature" to 250, "health" to 1, "voltage" to 0)
        val directTime = BatteryMeasurementTime(1_000, 100)
        val fallbackHealthTime = BatteryMeasurementTime(1_100, 200)
        val fallbackVoltageTime = BatteryMeasurementTime(1_200, 300)
        val fallbackWithTimes = fallback.copy(
            fieldMeasurementTimes = mapOf(
                "temperature" to BatteryMeasurementTime(900, 90),
                "health" to fallbackHealthTime,
                "voltage" to fallbackVoltageTime
            )
        )

        val merged = mergeBatteryFields(fields, fallbackWithTimes)
        val times = mergeBatteryFieldMeasurementTimes(
            fields, fallbackWithTimes, fields.keys.associateWith { directTime })

        assertEquals(250, merged["temperature"])
        assertEquals(fallback.health, merged["health"])
        assertEquals(fallback.voltageMillivolts, merged["voltage"])
        assertEquals(
            mapOf(
                "temperature" to directTime,
                "health" to fallbackHealthTime,
                "voltage" to fallbackVoltageTime
            ), times
        )
    }

    @Test
    fun `unknown selected acquisition time cannot be borrowed from the discarded alternative`() {
        val time = BatteryMeasurementTime(1_000, 100)
        val fallbackWithTimes = fallback.copy(fieldMeasurementTimes = mapOf("voltage" to time))

        assertTrue(
            mergeBatteryFieldMeasurementTimes(
                mapOf("voltage" to 3700), fallbackWithTimes, emptyMap()
            ).isEmpty()
        )
        assertTrue(
            mergeBatteryFieldMeasurementTimes(
                mapOf("voltage" to 0), fallback, mapOf("voltage" to time)
            ).isEmpty()
        )
        assertEquals(4200, mergeBatteryFields(mapOf("voltage" to 0), fallback)["voltage"])
    }

    @Test
    fun `metadata alone cannot create a measurement while zero fallback temperature retains its time`() {
        val fields = mapOf("temperature" to Int.MIN_VALUE, "health" to 1, "voltage" to 0)
        val time = BatteryMeasurementTime(1_000, 100)
        val metadataOnly = PrivilegedBatteryReading(
            fieldSources = mapOf("temperature" to "dumpsys battery / temperature"),
            fieldMeasurementTimes = fields.keys.associateWith { time })

        assertEquals(fields, mergeBatteryFields(fields, metadataOnly))
        assertTrue(
            mergeBatteryFieldMeasurementTimes(
                fields, metadataOnly, fields.keys.associateWith { time }).isEmpty()
        )
        val zeroTemperature = metadataOnly.copy(temperatureTenthsC = 0)
        assertEquals(0, mergeBatteryFields(fields, zeroTemperature)["temperature"])
        assertEquals(
            mapOf("temperature" to time),
            mergeBatteryFieldMeasurementTimes(fields, zeroTemperature, emptyMap())
        )
    }
}
