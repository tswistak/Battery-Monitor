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
package codes.swistak.batterymonitor.alarms.backup

import codes.swistak.batterymonitor.alarms.AlarmDatabase
import codes.swistak.batterymonitor.alarms.AlarmRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmBackupSchemaTest {
    @Test
    fun `alarm backups use the latest schema version`() {
        assertEquals(2, AlarmBackup.SCHEMA_VERSION)
        assertEquals(
            setOf("enabled", "type", "threshold"), Version1AlarmImporter.schema.keys
        )
        assertFalse(Version1AlarmImporter.schema.containsKey("_id"))
        assertFalse(Version1AlarmImporter.schema.containsKey("ringtone"))
    }

    @Test
    fun `version one restores all supported alarm settings`() {
        assertEquals(
            AlarmRecord(false, "temp_rises", "375"), Version1AlarmImporter.restore(
                mapOf("enabled" to false, "type" to "temp_rises", "threshold" to "375")
            )
        )
    }

    @Test
    fun `missing and invalid values use safe defaults`() {
        assertEquals(
            AlarmRecord(true, "charge_drops", "20"), Version1AlarmImporter.restore(
                mapOf("type" to "charge_drops", "threshold" to "invalid")
            )
        )
        assertEquals(
            AlarmRecord(true, "fully_charged", ""), Version1AlarmImporter.restore(
                mapOf("type" to "fully_charged", "threshold" to "50")
            )
        )
    }

    @Test
    fun `unknown alarm types are skipped`() {
        assertNull(Version1AlarmImporter.restore(mapOf("type" to "future_alarm")))
    }

    @Test
    fun `version two contains the same portable alarm fields`() {
        assertEquals(
            setOf("enabled", "type", "threshold"), Version2AlarmImporter.schema.keys
        )
        assertFalse(Version2AlarmImporter.schema.containsKey("_id"))
        assertFalse(Version2AlarmImporter.schema.containsKey("ringtone"))
    }

    @Test
    fun `charging limit met is imported as a threshold-less alarm`() {
        assertEquals(
            AlarmRecord(true, "charging_limit_met", ""), Version2AlarmImporter.restore(
                mapOf("enabled" to true, "type" to "charging_limit_met", "threshold" to "80")
            )
        )
    }

    @Test
    fun `discharging limit met is imported as a threshold-less alarm`() {
        assertEquals(
            AlarmRecord(false, "discharging_limit_met", ""), Version2AlarmImporter.restore(
                mapOf("enabled" to false, "type" to "discharging_limit_met", "threshold" to "20")
            )
        )
    }

    @Test
    fun `target alarm records round trip with an empty threshold`() {
        for (type in listOf("charging_limit_met", "discharging_limit_met")) {
            val record = Version2AlarmImporter.restore(
                mapOf("enabled" to true, "type" to type, "threshold" to "50")
            )!!
            assertEquals("", record.threshold)
            assertEquals(
                record, Version2AlarmImporter.restore(
                    mapOf(
                        "enabled" to record.enabled,
                        "type" to record.type,
                        "threshold" to record.threshold
                    )
                )
            )
        }
    }

    @Test
    fun `version two keeps validating generic threshold alarms`() {
        assertEquals(
            AlarmRecord(true, "charge_rises", "90"), Version2AlarmImporter.restore(
                mapOf("enabled" to true, "type" to "charge_rises", "threshold" to "90")
            )
        )
        assertEquals(
            AlarmRecord(true, "charge_rises", "90"), Version2AlarmImporter.restore(
                mapOf("enabled" to true, "type" to "charge_rises", "threshold" to "not_a_number")
            )
        )
    }

    @Test
    fun `target limit alarm types are in the supported types`() {
        assertTrue("charging_limit_met" in AlarmDatabase.SUPPORTED_TYPES)
        assertTrue("discharging_limit_met" in AlarmDatabase.SUPPORTED_TYPES)
    }

    @Test
    fun `each version resolves its own importer and future versions use the latest`() {
        assertSame(Version1AlarmImporter, alarmImporterForVersion(1))
        assertSame(Version2AlarmImporter, alarmImporterForVersion(2))
        assertSame(Version2AlarmImporter, alarmImporterForVersion(3))
        assertThrows(IllegalArgumentException::class.java) {
            alarmImporterForVersion(0)
        }
    }
}
