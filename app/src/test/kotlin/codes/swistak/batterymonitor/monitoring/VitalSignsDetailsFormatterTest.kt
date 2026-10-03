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

import org.junit.Assert.assertEquals
import org.junit.Test

class VitalSignsDetailsFormatterTest {
    private val entries = listOf(
        "Health" to "Healthy",
        "Temperature" to "25.0°C",
        "Voltage" to "4.1V"
    )

    @Test
    fun `collapsed line joins values without labels`() {
        assertEquals(
            "Healthy / 25.0°C / 4.1V",
            VitalSignsDetailsFormatter.collapsedLine(entries)
        )
    }

    @Test
    fun `detailed text shows one labeled value per line in order`() {
        assertEquals(
            "Health: Healthy\nTemperature: 25.0°C\nVoltage: 4.1V",
            VitalSignsDetailsFormatter.detailedText(entries, "%1\$s: %2\$s")
        )
    }

    @Test
    fun `empty entries produce empty texts`() {
        assertEquals("", VitalSignsDetailsFormatter.collapsedLine(emptyList()))
        assertEquals("", VitalSignsDetailsFormatter.detailedText(emptyList(), "%1\$s: %2\$s"))
    }
}
