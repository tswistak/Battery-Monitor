/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrowableExtensionsTest {
    @Test
    fun `hasCause matches the throwable itself and nested causes`() {
        val error = IllegalStateException("outer", IllegalArgumentException("inner"))

        assertTrue(error.hasCause<IllegalStateException>())
        assertTrue(error.hasCause<IllegalArgumentException>())
    }

    @Test
    fun `hasCause returns false when the type is absent`() {
        val error = IllegalStateException("outer", IllegalArgumentException("inner"))

        assertFalse(error.hasCause<UnsupportedOperationException>())
    }
}
