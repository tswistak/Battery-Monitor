/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeSettingsTest {
    @Test
    fun `every saved color source and brightness retains its chosen value`() {
        ColorSource.entries.forEach { assertEquals(it, colorSource(it.name)) }
        Brightness.entries.forEach { assertEquals(it, brightness(it.name)) }
    }

    @Test
    fun `missing and unknown appearance values fall back to dynamic system appearance`() {
        for (value in listOf(null, "", "unknown", "dynamic", " dark ")) {
            assertEquals(value, ColorSource.Dynamic, colorSource(value))
            assertEquals(value, Brightness.System, brightness(value))
        }
    }

    @Test
    fun `brightness and color source remain independent choices`() {
        assertEquals(ColorSource.BatteryBlue, colorSource("BatteryBlue"))
        assertEquals(Brightness.System, brightness(null))
        assertEquals(ColorSource.Dynamic, colorSource(null))
        assertEquals(Brightness.TrueBlack, brightness("TrueBlack"))
    }
}
