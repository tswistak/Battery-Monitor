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
package codes.swistak.batterymonitor.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetCompletionTest {
    @Test
    fun `a failed broadcast completion does not prevent later widget completions or the next batch`() {
        val completed = mutableListOf<Int>()
        val errors = mutableListOf<Exception>()
        val failure = IllegalStateException("Broadcast already finished")
        WidgetUpdateDispatcher.finishCallbacks(
            listOf({ completed += 1 }, { throw failure }, { completed += 3 }), errors::add
        )
        WidgetUpdateDispatcher.finishCallbacks(listOf { completed += 4 }, errors::add)

        assertEquals(listOf(1, 3, 4), completed)
        assertEquals(listOf(failure), errors)
    }
}
