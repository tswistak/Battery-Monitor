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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemperatureUnitTest {
    @Test
    fun `known preference values select their unit`() {
        assertEquals(TemperatureUnit.CELSIUS, TemperatureUnit.fromPreference("celsius"))
        assertEquals(TemperatureUnit.FAHRENHEIT, TemperatureUnit.fromPreference("fahrenheit"))
        assertFalse(TemperatureUnit.CELSIUS.convertToFahrenheit)
        assertTrue(TemperatureUnit.FAHRENHEIT.convertToFahrenheit)
    }

    @Test
    fun `missing and unknown values fall back to Celsius`() {
        assertEquals(TemperatureUnit.CELSIUS, TemperatureUnit.fromPreference(null))
        assertEquals(TemperatureUnit.CELSIUS, TemperatureUnit.fromPreference("unsupported"))
    }
}
