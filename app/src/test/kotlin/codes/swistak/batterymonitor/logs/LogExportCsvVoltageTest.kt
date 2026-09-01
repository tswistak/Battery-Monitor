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
package codes.swistak.batterymonitor.logs

import org.junit.Assert.assertEquals
import org.junit.Test

class LogExportCsvVoltageTest {
    @Test
    fun `valid millivolt voltage is exported as volts`() {
        assertEquals("3.874", LogExport.csvVoltageField(3874))
        assertEquals("7.6", LogExport.csvVoltageField(7600))
    }

    @Test
    fun `unavailable voltage is exported as an empty field`() {
        assertEquals("", LogExport.csvVoltageField(null))
    }

    @Test
    fun `implausible legacy voltage is exported as an empty field`() {
        assertEquals("", LogExport.csvVoltageField(3))
        assertEquals("", LogExport.csvVoltageField(4))
    }
}
