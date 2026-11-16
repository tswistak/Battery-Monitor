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

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MonitoringStartupPolicyTest {
    @Test
    fun `package replacement resumes active monitoring and preserves an explicit stop for every autostart mode`() {
        for (autostart in listOf("always", "auto", "never")) {
            for (desired in listOf(false, true)) {
                assertEquals(
                    "Package replacement with autostart=$autostart and desired=$desired",
                    desired,
                    shouldStartMonitoringAfterSystemEvent(
                        Intent.ACTION_MY_PACKAGE_REPLACED, autostart, desired
                    )
                )
            }
        }
    }

    @Test
    fun `boot applies always auto and never preferences without confusing them with an upgrade`() {
        for (desired in listOf(false, true)) {
            for (autostart in listOf("always", "auto", "never")) {
                assertEquals(
                    "Boot with autostart=$autostart and desired=$desired",
                    autostart == "always" || autostart == "auto" && desired,
                    shouldStartMonitoringAfterSystemEvent(
                        Intent.ACTION_BOOT_COMPLETED, autostart, desired
                    )
                )
            }
        }
    }

    @Test
    fun `unrelated actions and unknown reboot preferences do not start monitoring`() {
        for (action in listOf(null, "unrelated.action")) {
            assertFalse(shouldStartMonitoringAfterSystemEvent(action, "always", true))
        }
        for (autostart in listOf(null, "unknown")) {
            assertFalse(
                shouldStartMonitoringAfterSystemEvent(Intent.ACTION_BOOT_COMPLETED, autostart, true)
            )
        }
    }
}
