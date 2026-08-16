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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AutoLogExportSchedulerTest {
    @Test
    fun `frequency preference values have safe defaults`() {
        assertEquals(
            AutoLogExportFrequency.OFF, AutoLogExportFrequency.fromPreference("unexpected")
        )
        assertEquals(AutoLogExportFrequency.OFF, AutoLogExportFrequency.fromPreference("daily"))
        assertEquals(
            AutoLogExportFrequency.ONE_WEEK, AutoLogExportFrequency.fromPreference("168")
        )
        assertEquals(AutoLogExportMode.NEW_FILE, AutoLogExportMode.fromPreference(null))
        assertEquals(AutoLogExportMode.APPEND, AutoLogExportMode.fromPreference("append"))
        assertEquals(LogExportFormat.CSV, LogExportFormat.fromPreference(null))
        assertEquals(LogExportFormat.JSON, LogExportFormat.fromPreference("json"))
    }

    @Test
    fun `scheduling uses the same hour intervals as log retention`() {
        val start = 1_700_000_000_000L
        assertEquals(
            start + TimeUnit.HOURS.toMillis(96), AutoLogExportScheduler.nextOccurrence(
                start, AutoLogExportFrequency.FOUR_DAYS
            )
        )
    }

    @Test
    fun `frequencies longer than log retention are disabled and capped`() {
        assertEquals(
            listOf(
                AutoLogExportFrequency.ONE_DAY,
                AutoLogExportFrequency.TWO_DAYS,
                AutoLogExportFrequency.FOUR_DAYS,
                AutoLogExportFrequency.ONE_WEEK
            ), AutoLogExportFrequency.enabledForRetention(168)
        )
        assertEquals(
            AutoLogExportFrequency.ONE_WEEK, AutoLogExportFrequency.cappedForRetention(
                AutoLogExportFrequency.FOUR_WEEKS, 168
            )
        )
        assertEquals(7, AutoLogExportFrequency.enabledForRetention(-1).size)
    }

    @Test
    fun `configured auto export runs only when logging is enabled`() {
        assertFalse(
            shouldRunAutoLogExport(
                loggingEnabled = false,
                frequency = AutoLogExportFrequency.ONE_DAY,
                directoryConfigured = true
            )
        )
        assertTrue(
            shouldRunAutoLogExport(
                loggingEnabled = true,
                frequency = AutoLogExportFrequency.ONE_DAY,
                directoryConfigured = true
            )
        )
    }

    @Test
    fun `new configuration exports immediately and creates a file even without logs`() {
        assertEquals(
            AutoLogExportSetupAction.START_INITIAL_EXPORT,
            autoLogExportSetupAction(wasConfigured = false)
        )
        assertTrue(
            shouldCreateNewAutoLogExportFile(
                hasRecords = false, createFileWhenEmpty = true
            )
        )
    }

    @Test
    fun `editing configuration reschedules and scheduled empty export creates no file`() {
        assertEquals(
            AutoLogExportSetupAction.RESCHEDULE, autoLogExportSetupAction(wasConfigured = true)
        )
        assertFalse(
            shouldCreateNewAutoLogExportFile(
                hasRecords = false, createFileWhenEmpty = false
            )
        )
    }
}
