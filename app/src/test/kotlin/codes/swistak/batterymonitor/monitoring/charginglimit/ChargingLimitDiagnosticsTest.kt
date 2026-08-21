/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring.charginglimit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChargingLimitDiagnosticsTest {
    @Test
    fun `numeric and known mode values remain useful`() {
        assertEquals("80", ChargingLimitDiagnostics.sanitizeValue("80"))
        assertEquals("adaptive", ChargingLimitDiagnostics.sanitizeValue("adaptive"))
    }

    @Test
    fun `unexpected strings are stable but never exported`() {
        val privateValue = "tomasz@example.com /Users/tomasz/private"
        val first = ChargingLimitDiagnostics.sanitizeValue(privateValue)
        val second = ChargingLimitDiagnostics.sanitizeValue(privateValue)

        assertEquals(first, second)
        assertEquals("<redacted>", first)
        assertFalse(first.contains("tomasz"))
        assertFalse(first.contains("example.com"))
        assertFalse(first.contains("/Users"))
    }
}
