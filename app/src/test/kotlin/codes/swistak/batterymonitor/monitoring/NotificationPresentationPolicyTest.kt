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
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationPresentationPolicyTest {
    @Test
    fun `unsupported or system disabled Live Updates always use the ordinary notification path`() {
        for (supported in listOf(false, true)) {
            for (enabled in listOf(false, true)) {
                if (supported && enabled) continue
                for (mode in listOf("always", "charging", "never")) {
                    for (charging in listOf(false, true)) {
                        assertFalse(
                            "supported=$supported, system=$enabled, mode=$mode, charging=$charging",
                            shouldRequestLiveUpdate(supported, enabled, mode, charging)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `available Live Updates respect always charging and never modes`() {
        for (charging in listOf(false, true)) {
            for (mode in listOf("always", "charging", "never", "invalid")) {
                assertEquals(
                    mode == "always" || mode == "charging" && charging, shouldRequestLiveUpdate(
                        supported = true,
                        enabledInSystem = true,
                        mode = mode,
                        chargingOrFull = charging
                    )
                )
            }
        }
    }
}
