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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitoringDesiredStateTest {
    @Test
    fun `the persisted authority preserves both a running and an explicitly stopped monitor`() {
        for (desired in listOf(false, true)) {
            assertEquals(
                desired, MonitoringDesiredState.decode(MonitoringDesiredState.encode(desired))
            )
        }
    }

    @Test
    fun `invalid or unsupported persisted authority is ignored so legacy state can be used`() {
        val valid = MonitoringDesiredState.encode(true)
        for (length in valid.indices) {
            assertNull(MonitoringDesiredState.decode(valid.copyOf(length)))
        }
        assertNull(MonitoringDesiredState.decode(valid + byteArrayOf(0)))
        assertNull(MonitoringDesiredState.decode(valid.copyOf().also { it[0] = 0 }))
        assertNull(MonitoringDesiredState.decode(valid.copyOf().also { it[7] = 2 }))
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
