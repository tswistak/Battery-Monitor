/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogCollectorTest {
    @Test
    fun `collector starts only when a non service process is identified`() {
        assertTrue(DebugLogCollector.shouldStartInProcess("codes.swistak.batterymonitor"))
        assertFalse(DebugLogCollector.shouldStartInProcess("codes.swistak.batterymonitor.BIS"))
        assertFalse(DebugLogCollector.shouldStartInProcess(null))
        assertFalse(DebugLogCollector.shouldStartInProcess(""))
    }
}
