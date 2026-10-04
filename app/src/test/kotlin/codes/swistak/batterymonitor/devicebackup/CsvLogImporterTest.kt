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
import codes.swistak.batterymonitor.logs.LogExport
import codes.swistak.batterymonitor.logs.LogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvLogImporterTest {
    @Test
    fun `raw current headers support translated order and bidi controls`() {
        val prefix = "Date,Time,Status,Charge,Temperature,Temperature F,Voltage,"
        for (header in listOf(
            "Battery current (µA)",
            "µA (تيار البطارية)",
            "تيار البطارية (\u200f\u202aµA\u202c\u200f)"
        )) {
            assertTrue(
                LogExport.hasRawCurrentCsvHeader(
                    CsvLogImporter.parseCsv(prefix + header).single()
                )
            )
        }
        assertFalse(
            LogExport.hasRawCurrentCsvHeader(
                CsvLogImporter.parseCsv(prefix + "Current (mA)").single()
            )
        )
        assertFalse(
            LogExport.hasRawCurrentCsvHeader(
                CsvLogImporter.parseCsv(prefix.dropLast(1)).single()
            )
        )
        for ((text, expected) in listOf(
            "" to false, "a,b\r\n" to false, "a,b\n" to false, "a,b" to true
        )) {
            assertEquals(expected, LogExport.needsCsvSeparator(text.byteInputStream()))
        }
    }

    @Test
    fun `legacy CSV upgrade preserves quoted multiline and unquoted localized fields with empty current`() {
        val csv =
            "Date,Time,Status,Charge,Temperature,Temperature F,Voltage\r\n" + "date,time,\"Charging,\r\nUSB \"\"slow\"\"\",50,30,86,4.0\r\n" + "date,time,Charging, AC,75,31.5,88.7,4.125"
        val writer = java.io.StringWriter()
        val column = "µA (تيار البطارية)"
        LogExport.upgradeLegacyCsv(csv.reader().buffered(), writer, column)
        val original = CsvLogImporter.parseCsv(csv)
        val upgraded = CsvLogImporter.parseCsv(writer.toString())
        assertEquals(original.first() + column, upgraded.first())
        assertEquals(original.drop(1).map { it + "" }, upgraded.drop(1))
        assertEquals(
            CsvLogImporter.parseRecords(csv, { 122 }, { _, _ -> 1L }),
            CsvLogImporter.parseRecords(writer.toString(), { 122 }, { _, _ -> 1L })
        )
        for (invalid in listOf(
            "a,b,c,d,e,f\n1,2,3,4,5,6", "a,b,c,d,e,f,g\n1,2,3", "a,b,c,d,e,f,g\n\"unterminated"
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                LogExport.upgradeLegacyCsv(
                    invalid.reader().buffered(), java.io.StringWriter(), column
                )
            }
        }
    }

    @Test
    fun `old and new CSV rows preserve missing signed fractional and zero current`() {
        val csv =
            "Date,Time,Status,Charge,Temperature,Temperature F,Voltage,Current (µA)\n" + "date,time,Discharging,50,30,86,4.0\n" + "date,time,Discharging,50,30,86,4.0,-240125\n" + "date,time,Charging,50,30,86,4.0,1200250\n" + "date,time,Charging,50,30,86,4.0,0\n" + "date,time,Charging,50,30,86,4.0,\n" + "date,time,Boot Completed,0,0,32,0,\n"
        val records = CsvLogImporter.parseRecords(csv, {
            when (it) {
                "Discharging" -> 100; "Charging" -> 122; "Boot Completed" -> -1; else -> null
            }
        }, { _, _ -> 1L })
        assertEquals(
            listOf(null, -240125L, 1200250L, 0L, null, null), records.map { it.currentMicroAmps })
        for (invalid in listOf(
            "NaN", "Infinity", "-Infinity", "invalid", "1.5", "9223372036854775808"
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                CsvLogImporter.parseRecords(csv.replace("-240125", invalid), {
                    when (it) {
                        "Discharging" -> 100; "Charging" -> 122; "Boot Completed" -> -1; else -> null
                    }
                }, { _, _ -> 1L })
            }
        }
    }

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
