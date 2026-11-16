/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.history

import codes.swistak.batterymonitor.ui.components.historyAxisFormatter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale


class HistoryTimeZoneTest {
    @Test
    fun `axis labels adapt from local time through dates and months to years`() {
        val instant = Instant.parse("2026-10-13T18:00:00Z")
        val zone = ZoneId.of("Europe/Warsaw")
        val day = 24 * 3_600_000L
        for (locale in listOf(Locale.US, Locale.forLanguageTag("pl-PL"))) {
            fun label(span: Long) = historyAxisFormatter(span, locale, zone).format(instant)
            assertEquals(
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
                    .withZone(zone).format(instant), label(day)
            )
            assertEquals(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
                    .withZone(zone).format(instant), label(7 * day)
            )
            assertEquals(
                DateTimeFormatter.ofPattern("LLL uuuu", locale).withZone(zone).format(instant),
                label(180 * day)
            )
            assertEquals("2026", label(800 * day))
            assertEquals("2026", label(Long.MAX_VALUE))
        }
    }

    @Test
    fun `short axis labels follow daylight saving transitions in the active zone`() {
        val zone = ZoneId.of("Europe/Warsaw")
        val formatter = historyAxisFormatter(3_600_000L, Locale.forLanguageTag("pl-PL"), zone)
        assertEquals("01:30:00", formatter.format(Instant.parse("2026-03-29T00:30:00Z")))
        assertEquals("03:30:00", formatter.format(Instant.parse("2026-03-29T01:30:00Z")))
    }

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
