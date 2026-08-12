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
package codes.swistak.batterymonitor.devicebackup

import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.monitoring.Predictor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Version1DeviceDataImporterTest {
    @Test
    fun `device-specific data backup starts at schema version one`() {
        assertEquals(1, DeviceDataBackup.SCHEMA_VERSION)
        assertEquals(
            setOf(
                "averageDischarge",
                "averageRechargeAc",
                "averageRechargeWireless",
                "averageRechargeUsb"
            ), Version1DeviceDataImporter.predictorPreferenceKeysByBackupKey.keys
        )
    }

    @Test
    fun `version one restores a complete log entry`() {
        assertEquals(
            LogRecord(
                status = 123,
                charge = 74,
                time = 1_754_000_000_000L,
                temperature = 315,
                voltage = 4_125
            ), Version1DeviceDataImporter.restoreLog(
                mapOf(
                    "status" to 123,
                    "charge" to 74,
                    "time" to 1_754_000_000_000L,
                    "temperature" to 315,
                    "voltage" to 4_125
                )
            )
        )
    }

    @Test
    fun `version one preserves nullable boot log values`() {
        assertEquals(
            LogRecord(-1, null, 1_754_000_000_000L, null, null),
            Version1DeviceDataImporter.restoreLog(
                mapOf(
                    "status" to -1,
                    "charge" to null,
                    "time" to 1_754_000_000_000L,
                    "temperature" to null,
                    "voltage" to null
                )
            )
        )
    }

    @Test
    fun `fractional and missing required log values are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Version1DeviceDataImporter.restoreLog(
                mapOf("status" to 1.5, "time" to 1_754_000_000_000L)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Version1DeviceDataImporter.restoreLog(mapOf("status" to 1))
        }
    }

    @Test
    fun `predictor fields map to all internal averages`() {
        assertEquals(
            Predictor.KEY_AVERAGE.zip(listOf(10f, 20f, 30f, 40f)).toMap(),
            Version1DeviceDataImporter.restorePredictor(
                mapOf(
                    "averageDischarge" to 10.0,
                    "averageRechargeAc" to 20.0,
                    "averageRechargeWireless" to 30.0,
                    "averageRechargeUsb" to 40.0
                )
            )
        )
    }

    @Test
    fun `invalid predictor values are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Version1DeviceDataImporter.restorePredictor(
                mapOf("averageDischarge" to Double.NaN)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Version1DeviceDataImporter.restorePredictor(
                mapOf("averageDischarge" to "not a number")
            )
        }
    }
}
