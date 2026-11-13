/*
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

import codes.swistak.batterymonitor.settings.SettingsContract
import org.junit.Assert.assertEquals
import org.junit.Test

class VitalSignsDetailsFormatterTest {
    private val entries = listOf(
        SettingsContract.VITAL_SIGN_HEALTH to "Healthy",
        SettingsContract.VITAL_SIGN_TEMPERATURE to "25.0°C",
        SettingsContract.VITAL_SIGN_VOLTAGE to "4.1V",
        SettingsContract.VITAL_SIGN_STATUS_DURATION to "2h"
    )

    @Test
    fun `collapsed line joins values without labels`() {
        assertEquals(
            "Healthy / 25.0°C / 4.1V / 2h", VitalSignsDetailsFormatter.collapsedLine(entries)
        )
    }

    @Test
    fun `empty entries produce no collapsed text`() {
        assertEquals("", VitalSignsDetailsFormatter.collapsedLine(emptyList()))
    }
}
