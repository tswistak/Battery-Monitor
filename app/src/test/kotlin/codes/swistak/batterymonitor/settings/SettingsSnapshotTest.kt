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
package codes.swistak.batterymonitor.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSnapshotTest {
    @Test
    fun `snapshot retains every shared preferences value type`() {
        val values = linkedMapOf<String, Any>(
            "boolean" to true,
            "float" to 1.5f,
            "int" to 2,
            "long" to 3L,
            "string" to "value",
            "strings" to linkedSetOf("first", "second")
        )

        assertEquals(values, SettingsSnapshot.normalizedValues(values))
    }

    @Test
    fun `snapshot rejects unsupported values and invalid string sets`() {
        val values = mapOf<String, Any>(
            "list" to listOf("value"), "mixed_set" to setOf("value", 1)
        )

        assertEquals(emptyMap<String, Any>(), SettingsSnapshot.normalizedValues(values))
    }
}
