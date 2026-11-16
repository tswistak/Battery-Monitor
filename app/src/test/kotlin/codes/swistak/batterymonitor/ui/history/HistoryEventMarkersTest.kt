/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.history

import codes.swistak.batterymonitor.logs.HistoryEvent
import codes.swistak.batterymonitor.ui.components.forEachHistoryEventMarker
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToLong

class HistoryEventMarkersTest {
    @Test
    fun `a reboot owns the overlapping stroke until the timestamps separate on screen`() {
        val events = mapOf(
            HistoryEvent.CONNECTED to listOf(100L, 120L, 150L, 300L),
            HistoryEvent.DISCONNECTED to listOf(210L),
            HistoryEvent.REBOOT to listOf(100L, 155L, 205L)
        )

        fun visible(scale: Float, left: Float, right: Float) = buildList {
            forEachHistoryEventMarker(events, left, right, 1.5f, 1f, { it * scale }) { code, x ->
                add(code to (x / scale).roundToLong())
            }
        }.groupBy({ it.first }, { it.second })

        assertEquals(
            mapOf(
                HistoryEvent.REBOOT to listOf(100L, 205L), HistoryEvent.CONNECTED to listOf(300L)
            ), visible(0.05f, 0f, 30f)
        )
        assertEquals(
            mapOf(
                HistoryEvent.REBOOT to listOf(100L, 155L, 205L),
                HistoryEvent.CONNECTED to listOf(120L, 150L),
                HistoryEvent.DISCONNECTED to listOf(210L)
            ), visible(0.5f, 48f, 110f)
        )
    }
}
