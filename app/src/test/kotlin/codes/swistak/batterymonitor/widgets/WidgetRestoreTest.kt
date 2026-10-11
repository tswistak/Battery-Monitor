/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.widgets

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRestoreTest {
    @Test
    fun `Restore accepts unbound old IDs and only the matching provider targets`() {
        assertTrue(canRestoreWidgetIds(intArrayOf(4), intArrayOf(9), { it == 9 }, { it == 9 }))
        assertFalse(canRestoreWidgetIds(intArrayOf(4), intArrayOf(9), { false }, { it == 9 }))
    }

    @Test
    fun `Restore cannot steal configuration from another still bound instance`() {
        assertFalse(
            canRestoreWidgetIds(intArrayOf(4), intArrayOf(9), { it == 9 }, { it in setOf(4, 9) })
        )
    }

    @Test
    fun `Restore supports reused target IDs but rejects invalid and duplicate mappings`() {
        assertTrue(canRestoreWidgetIds(intArrayOf(4, 9), intArrayOf(9, 4), { true }, { true }))
        assertFalse(canRestoreWidgetIds(intArrayOf(4, 4), intArrayOf(9, 10), { true }, { false }))
        assertFalse(canRestoreWidgetIds(intArrayOf(4), intArrayOf(-1), { true }, { false }))
        assertFalse(canRestoreWidgetIds(intArrayOf(4), intArrayOf(9, 10), { true }, { false }))
    }
}
