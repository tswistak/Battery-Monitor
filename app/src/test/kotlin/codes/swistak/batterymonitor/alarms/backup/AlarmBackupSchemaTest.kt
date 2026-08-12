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

import codes.swistak.batterymonitor.alarms.AlarmRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AlarmBackupSchemaTest {
    @Test
    fun `version one contains only portable alarm fields`() {
        assertEquals(1, AlarmBackup.SCHEMA_VERSION)
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
    fun `future versions use the latest known importer`() {
        assertSame(Version1AlarmImporter, alarmImporterForVersion(2))
        assertThrows(IllegalArgumentException::class.java) {
            alarmImporterForVersion(0)
        }
    }
}
