/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.history

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HistoryTimeZoneTest {
    @Test
    fun `broadcast zone overrides a stale process default for boundary event dates`() {
        val oldDefault = ZoneId.of("UTC")
        val updated = historyTimeZone("America/Los_Angeles", oldDefault)
        val event = Instant.parse("2026-03-29T00:30:00Z")

        assertEquals(ZoneId.of("America/Los_Angeles"), updated)
        assertEquals(LocalDate.of(2026, 3, 29), event.atZone(oldDefault).toLocalDate())
        assertEquals(LocalDate.of(2026, 3, 28), event.atZone(updated).toLocalDate())
    }

    @Test
    fun `missing or invalid broadcast zone uses the process default`() {
        val fallback = ZoneId.of("Europe/Warsaw")
        assertEquals(fallback, historyTimeZone(null, fallback))
        assertEquals(fallback, historyTimeZone("", fallback))
        assertEquals(fallback, historyTimeZone("invalid-zone", fallback))
    }
}
