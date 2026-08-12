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

import org.junit.Assert.assertEquals
import org.junit.Test

class GeneralBackupSchemaTest {
    @Test
    fun `version one declares the three independently versioned json files`() {
        assertEquals(1, GeneralBackup.SCHEMA_VERSION)
        assertEquals(
            listOf("settings.json", "alarms.json", "device-specific.json"),
            Version1GeneralBackupSchema.expectedFiles
        )
        assertEquals("metadata.json", GeneralBackup.METADATA_FILE)
    }
}
