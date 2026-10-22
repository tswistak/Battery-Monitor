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
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryHistorySamplerTest {
    private val record = LogRecord(100, 50, 1_000_000, 300, 4000, -100_000, 2_500_000)

    @Test
    fun `unchanged state samples at 120 seconds and measurement fluctuations do not write`() {
        val sampler = BatteryHistorySampler(0)
        for (elapsed in 1L..<HISTORY_SAMPLE_INTERVAL_MS step 100) {
            val reading = record.copy(
                temperature = 301,
                voltage = 4010,
                currentMicroAmps = elapsed,
                remainingChargeMicroampHours = 2_499_999
            )
            assertNull(sampler.statusAge(record, reading, elapsed))
        }
        assertEquals(LogDatabase.STATUS_SAMPLE, sampler.statusAge(record, record, 120_000))
        sampler.inserted(120_000)
        assertNull(sampler.statusAge(record, record, 120_001))
        assertEquals(LogDatabase.STATUS_SAMPLE, sampler.statusAge(record, record, 240_000))
    }

    @Test
    fun `every level status and source change is immediate and resets the interval`() {
        val sampler = BatteryHistorySampler(0)
        for ((observation, age) in listOf(
            record.copy(charge = 51) to LogDatabase.STATUS_OLD,
            record.copy(status = 12) to LogDatabase.STATUS_NEW,
            record.copy(status = 110) to LogDatabase.STATUS_NEW,
            record.copy(status = 115) to LogDatabase.STATUS_NEW
        )) {
            assertEquals(age, sampler.statusAge(record, observation, 10_000))
            sampler.inserted(10_000)
            assertNull(sampler.statusAge(observation, observation, 120_000))
            assertEquals(
                LogDatabase.STATUS_SAMPLE, sampler.statusAge(observation, observation, 130_000)
            )
        }
        assertEquals(LogDatabase.STATUS_NEW, sampler.statusAge(null, record, 10_001))
        assertEquals(
            LogDatabase.STATUS_NEW, sampler.statusAge(record.copy(status = -1), record, 10_001)
        )
    }

    @Test
    fun `failed writes do not advance sampling or lose an event on retry`() {
        val sampler = BatteryHistorySampler(0)
        repeat(3) {
            assertEquals(
                LogDatabase.STATUS_SAMPLE, sampler.statusAge(record, record, 120_000)
            )
        }
        assertEquals(0L, sampler.lastRecordedElapsed)
        val changed = record.copy(status = 12)
        repeat(3) {
            assertEquals(
                LogDatabase.STATUS_NEW, sampler.statusAge(record, changed, 120_001)
            )
        }
        sampler.inserted(120_001)
        assertNull(sampler.statusAge(changed, changed, 120_002))
    }

    @Test
    fun `skipped updates preserve the periodic deadline and successful events reset it`() {
        val sampler = BatteryHistorySampler(0)
        for (elapsed in listOf(1_000L, 60_000L, 119_999L)) {
            val changedMeasurements = record.copy(temperature = 310, currentMicroAmps = elapsed)
            assertNull(sampler.statusAge(record, changedMeasurements, elapsed))
            assertEquals(120_000L, elapsed + sampler.nextUpdateDelay(elapsed))
        }
        assertEquals(1L, sampler.nextUpdateDelay(119_999))
        val event = record.copy(charge = 49)
        assertEquals(LogDatabase.STATUS_OLD, sampler.statusAge(record, event, 119_999))
        sampler.inserted(119_999)
        assertEquals(120_000L, sampler.nextUpdateDelay(119_999))
        assertEquals(239_999L, 120_001L + sampler.nextUpdateDelay(120_001))
        assertEquals(LogDatabase.STATUS_SAMPLE, sampler.statusAge(event, event, 239_999))
        sampler.inserted(239_999)
        assertEquals(120_000L, sampler.nextUpdateDelay(239_999))
    }

    @Test
    fun `restored deadlines resume without tight retries after long gaps or failures`() {
        val restored =
            BatteryHistorySampler(BatteryHistorySampler.restoreElapsed(10_000, 7, 7, 70_000))
        assertEquals(60_000L, restored.nextUpdateDelay(70_000))
        assertEquals(1L, restored.nextUpdateDelay(129_999))
        for (elapsed in listOf(130_000L, 130_001L, 86_400_000L)) {
            assertEquals(LogDatabase.STATUS_SAMPLE, restored.statusAge(record, record, elapsed))
            // Without a successful insert, the monitoring callback still waits two minutes.
            assertEquals(HISTORY_SAMPLE_INTERVAL_MS, restored.nextUpdateDelay(elapsed))
        }
        assertEquals(HISTORY_SAMPLE_INTERVAL_MS, restored.nextUpdateDelay(1))
        assertEquals(HISTORY_SAMPLE_INTERVAL_MS, BatteryHistorySampler().nextUpdateDelay(1))
        restored.inserted(86_400_000)
        assertEquals(119_999L, restored.nextUpdateDelay(86_400_001))
    }

    @Test
    fun `resolved updates crossing the deadline request one fresh observation without failed write spin`() {
        val sampler = BatteryHistorySampler(0)
        assertNull(sampler.statusAge(record, record, 119_999))
        assertEquals(1L, sampler.nextUpdateDelay(120_010, observedElapsed = 119_999))
        assertEquals(1L, sampler.nextUpdateDelay(600_000, observedElapsed = 119_999))
        assertEquals(LogDatabase.STATUS_SAMPLE, sampler.statusAge(record, record, 600_001))
        // If that overdue fresh observation fails, the next attempt must wait two minutes.
        assertEquals(
            HISTORY_SAMPLE_INTERVAL_MS, sampler.nextUpdateDelay(600_010, observedElapsed = 600_001)
        )
        assertEquals(
            HISTORY_SAMPLE_INTERVAL_MS, sampler.nextUpdateDelay(720_020, observedElapsed = 720_010)
        )
        sampler.inserted(720_010)
        assertEquals(119_990L, sampler.nextUpdateDelay(720_020, observedElapsed = 720_010))
    }

    @Test
    fun `restarts clocks imports and sleep never catch up or suppress future samples`() {
        val saved = BatteryHistorySampler.restoreElapsed(10_000, 7, 7, 20_000)
        val restarted = BatteryHistorySampler(saved)
        assertNull(restarted.statusAge(record, record, 20_000))
        for (wallTime in listOf(1L, Long.MAX_VALUE, 0L)) {
            assertEquals(
                LogDatabase.STATUS_SAMPLE, restarted.statusAge(
                    record.copy(time = wallTime), record.copy(time = wallTime), 130_000
                )
            )
        }
        restarted.inserted(130_000)
        assertNull(restarted.statusAge(record, record, 130_001))
        assertEquals(LogDatabase.STATUS_SAMPLE, restarted.statusAge(record, record, 86_400_000))
        restarted.inserted(86_400_000)
        assertNull(restarted.statusAge(record, record, 86_400_001))
        assertNull(BatteryHistorySampler.restoreElapsed(10_000, 7, 8, 50_000))
        assertNull(BatteryHistorySampler.restoreElapsed(50_000, 7, 7, 10_000))
        assertNull(BatteryHistorySampler.restoreElapsed(10_000, -1, -1, 50_000))
        val rebooted = BatteryHistorySampler()
        assertEquals(LogDatabase.STATUS_SAMPLE, rebooted.statusAge(record, record, 1))
        rebooted.inserted(1)
        assertNull(rebooted.statusAge(record, record, 2))
    }

    @Test
    fun `retention checks are skipped for unlimited history and failed export prevents deletion`() {
        var queries = 0
        var exports = 0
        var deletions = 0
        fun run(age: Int, expired: Boolean, success: Boolean) = maintainHistory(age, {
            queries++
            expired
        }, {
            exports++
            success
        }, { deletions++ })
        run(-1, expired = true, success = true)
        assertEquals(0, queries)
        run(672, expired = false, success = true)
        assertEquals(0, exports)
        run(672, expired = true, success = false)
        assertEquals(0, deletions)
        run(672, expired = true, success = true)
        assertEquals(1, deletions)
    }

    @Test
    fun `legacy unplugged percentage reset can still start a status period`() {
        val sampler = BatteryHistorySampler(0)
        assertEquals(
            LogDatabase.STATUS_NEW,
            sampler.statusAge(record, record.copy(charge = 90), 1, newStatus = true)
        )
        assertEquals(
            LogDatabase.STATUS_OLD, sampler.statusAge(record, record.copy(charge = 90), 1)
        )
    }

}
