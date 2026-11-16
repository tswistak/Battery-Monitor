/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.monitoring

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitoringDesiredStateTest {
    @Test
    fun `the persisted authority preserves a running or stopped monitor and its boot`() {
        for (desired in listOf(false, true)) {
            val session = MonitoringDesiredState.Session(desired, 12)
            val savedV2 = byteArrayOf(
                0x42, 0x4d, 0x44, 0x53, 0, 0, 0, 2, if (desired) 1 else 0, 0, 0, 0, 12
            )
            assertEquals(session, MonitoringDesiredState.decode(savedV2))
            assertArrayEquals(savedV2, MonitoringDesiredState.encode(session))
        }
    }

    @Test
    fun `invalid or unsupported persisted authority is ignored so legacy state can be used`() {
        val valid = MonitoringDesiredState.encode(MonitoringDesiredState.Session(true, 12))
        for (length in valid.indices) {
            assertNull(MonitoringDesiredState.decode(valid.copyOf(length)))
        }
        assertNull(MonitoringDesiredState.decode(valid + byteArrayOf(0)))
        assertNull(MonitoringDesiredState.decode(valid.copyOf().also { it[0] = 0 }))
        assertNull(MonitoringDesiredState.decode(valid.copyOf().also { it[7] = 3 }))
        assertNull(MonitoringDesiredState.decode(valid.copyOf().also { it[8] = 2 }))
    }

    @Test
    fun `migrated main preferences take precedence over autostart and legacy service state`() {
        for (mainDesired in listOf(false, true)) {
            for (autostart in listOf("auto", "always", "never", null)) {
                for (serviceDesired in listOf(false, true)) {
                    assertEquals(
                        mainDesired, MonitoringDesiredState.legacyDesired(
                            true, mainDesired, autostart, serviceDesired
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `unmigrated preferences retain the legacy always autostart and service desired policy`() {
        for (mainDesired in listOf(false, true)) {
            for (autostart in listOf("auto", "always", "never", null)) {
                for (serviceDesired in listOf(false, true)) {
                    assertEquals(
                        autostart == "always" || serviceDesired,
                        MonitoringDesiredState.legacyDesired(
                            false, mainDesired, autostart, serviceDesired
                        )
                    )
                }
            }
        }
    }
}
