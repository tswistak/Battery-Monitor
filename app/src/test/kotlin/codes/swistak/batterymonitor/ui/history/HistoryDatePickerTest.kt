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
package codes.swistak.batterymonitor.ui.history

import codes.swistak.batterymonitor.logs.HistoryRangeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

class HistoryDatePickerTest {
    @Test
    fun `picker timestamps are UTC dates regardless of device timezone`() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf(
                "UTC", "America/Los_Angeles", "Europe/Warsaw", "Pacific/Kiritimati"
            )) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for (date in listOf("1969-12-31", "2026-03-29", "2026-10-25", "2028-02-29")) {
                    val selected = LocalDate.parse(date)
                    val millis = historyDatePickerMillis(selected)
                    assertEquals(selected, historyDatePickerDate(millis))
                    assertEquals(
                        selected.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), millis
                    )
                }
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `western timezones do not shift the selected day backwards`() {
        val date = LocalDate.of(2026, 3, 8)
        val millis = historyDatePickerMillis(date)
        val zone = ZoneId.of("America/Los_Angeles")
        assertEquals(date.minusDays(1), Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())
        assertEquals(date, historyDatePickerDate(millis))
    }

    @Test
    fun `selected calendar days remain inclusive through local DST transitions`() {
        for ((zoneName, spring, autumn) in listOf(
            Triple("Europe/Warsaw", "2026-03-29", "2026-10-25"),
            Triple("America/Los_Angeles", "2026-03-08", "2026-11-01")
        )) {
            val zone = ZoneId.of(zoneName)
            for ((text, hours) in listOf(spring to 23, autumn to 25)) {
                val date = historyDatePickerDate(historyDatePickerMillis(LocalDate.parse(text)))
                val range =
                    historyDraftRange("custom", date.toString(), date.toString(), "", "", zone)!!
                val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val nextDay = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                assertEquals(start, range.start)
                assertEquals(nextDay, range.end)
                assertEquals(hours * 3_600_000L, range.end - range.start)
                assertTrue(range.contains(start))
                assertTrue(range.contains(nextDay - 1))
                assertFalse(range.contains(nextDay))
            }
        }
    }

    @Test
    fun `month and year ranges keep all calendar days and leap days`() {
        val zone = ZoneId.of("Europe/Warsaw")
        assertEquals(
            HistoryRangeState.days(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29), zone),
            historyDraftRange("month", "", "", "2", "2028", zone)
        )
        assertEquals(
            HistoryRangeState.days(LocalDate.of(2028, 1, 1), LocalDate.of(2028, 12, 31), zone),
            historyDraftRange("year", "", "", "", "2028", zone)
        )
    }

    @Test
    fun `invalid drafts do not produce ranges`() {
        val zone = ZoneOffset.UTC
        assertNull(historyDraftRange("custom", "2026-04-02", "2026-04-01", "", "", zone))
        assertNull(historyDraftRange("custom", "invalid", "2026-04-01", "", "", zone))
        assertNull(historyDraftRange("month", "", "", "13", "2026", zone))
        assertNull(historyDraftRange("month", "", "", "0", "2026", zone))
        for (year in listOf("", "bad", "1969", "10000")) {
            assertNull(historyDraftRange("year", "", "", "", year, zone))
            assertNull(historyDraftRange("month", "", "", "1", year, zone))
        }
    }

    @Test
    fun `epoch boundary dates keep existing nonnegative history validation`() {
        val west = ZoneId.of("America/Los_Angeles")
        assertEquals(
            LocalDate.of(1969, 12, 31),
            historyDatePickerDate(historyDatePickerMillis(Instant.EPOCH.atZone(west).toLocalDate()))
        )
        assertNull(historyDraftRange("custom", "1969-12-31", "1970-01-01", "", "", west))
        assertNotNull(historyDraftRange("custom", "1970-01-01", "1970-01-01", "", "", west))
    }
}
