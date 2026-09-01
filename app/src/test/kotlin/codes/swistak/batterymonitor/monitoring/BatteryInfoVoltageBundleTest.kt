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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryInfoVoltageBundleTest {
    @Test
    fun `valid voltage round-trips through the bundle field`() {
        val accessor = FakeBundleFieldAccessor()

        writeVoltageField(accessor, 3874)

        assertEquals(3874, readVoltageField(accessor))
        assertEquals(3874, accessor.fields[BatteryInfo.FIELD_VOLTAGE])
    }

    @Test
    fun `unavailable voltage is omitted and restores as null`() {
        val accessor = FakeBundleFieldAccessor()

        writeVoltageField(accessor, null)

        assertFalse(accessor.containsKey(BatteryInfo.FIELD_VOLTAGE))
        assertNull(readVoltageField(accessor))
    }

    @Test
    fun `missing voltage field restores as null`() {
        assertNull(readVoltageField(FakeBundleFieldAccessor()))
    }

    private class FakeBundleFieldAccessor : BundleFieldAccessor {
        val fields = mutableMapOf<String, Int>()

        override fun containsKey(key: String): Boolean = fields.containsKey(key)

        override fun getInt(key: String): Int = fields[key] ?: 0

        override fun putInt(key: String, value: Int) {
            fields[key] = value
        }
    }
}
