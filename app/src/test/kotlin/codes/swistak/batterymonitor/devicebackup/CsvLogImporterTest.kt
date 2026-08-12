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

import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CsvLogImporterTest {
    @Test
    fun `translated column names are ignored and columns are read by position`() {
        val statusCode = LogDatabase.encodeStatus(2, 1, LogDatabase.STATUS_NEW)
        val records = CsvLogImporter.parseRecords(
            csv = "Data,Czas,Stan,Poziom,Temperatura C,Temperatura F,Napięcie\r\n" + "10.08.2026,12:34:56,Ładowanie AC,75,31.5,88.7,4.125\r\n",
            statusCodeFor = { label -> if (label == "Ładowanie AC") statusCode else null },
            timestampFor = { date, time ->
                if (date == "10.08.2026" && time == "12:34:56") 123_456L else null
            })

        assertEquals(
            listOf(LogRecord(statusCode, 75, 123_456L, 315, 4_125)), records
        )
    }

    @Test
    fun `commas in status labels from the existing unquoted exporter are supported`() {
        val records = CsvLogImporter.parseRecords(
            csv = "Date,Time,Status,Charge,Temperature,Temperature F,Voltage\n" + "8/10/26,1:00:00 PM,Charging, AC,50,20.0,68.0,4.0\n",
            statusCodeFor = { if (it == "Charging, AC") 12 else null },
            timestampFor = { _, _ -> 1L })

        assertEquals(listOf(LogRecord(12, 50, 1L, 200, 4_000)), records)
    }

    @Test
    fun `boot rows restore nullable database values`() {
        val records = CsvLogImporter.parseRecords(
            csv = "Date,Time,Status,Charge,Temperature,Temperature F,Voltage\n" + "8/10/26,1:00:00 PM,Boot Completed,0,0.0,32.0,0.0\n",
            statusCodeFor = { LogDatabase.STATUS_BOOT_COMPLETED },
            timestampFor = { _, _ -> 1L })

        assertEquals(
            listOf(LogRecord(LogDatabase.STATUS_BOOT_COMPLETED, null, 1L, null, null)), records
        )
    }

    @Test
    fun `unknown statuses and invalid rows reject the complete import`() {
        assertThrows(IllegalArgumentException::class.java) {
            CsvLogImporter.parseRecords(
                csv = "Date,Time,Status,Charge,Temperature,Temperature F,Voltage\n" + "8/10/26,1:00:00 PM,Future Status,50,20.0,68.0,4.0\n",
                statusCodeFor = { null },
                timestampFor = { _, _ -> 1L })
        }
        assertThrows(IllegalArgumentException::class.java) {
            CsvLogImporter.parseRecords(
                csv = "Date,Time,Status,Charge,Temperature,Temperature F,Voltage\n" + "8/10/26,1:00:00 PM,Charging,not-a-number,20.0,68.0,4.0\n",
                statusCodeFor = { 2 },
                timestampFor = { _, _ -> 1L })
        }
    }
}
