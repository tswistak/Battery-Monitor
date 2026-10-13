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

internal const val HISTORY_SAMPLE_INTERVAL_MS = 120_000L
internal const val HISTORY_MAINTENANCE_INTERVAL_MS = 3_600_000L

internal class BatteryHistorySampler(lastRecordedElapsed: Long? = null) {
    var lastRecordedElapsed: Long? = lastRecordedElapsed
        private set

    fun statusAge(
        previous: LogRecord?, observation: LogRecord, elapsed: Long, newStatus: Boolean = false
    ): Int? {
        val before = previous?.let { LogDatabase.decodeStatus(it.status) }
        val current = LogDatabase.decodeStatus(observation.status)
        return when {
            before == null || before[0] != current[0] || before[1] != current[1] -> LogDatabase.STATUS_NEW
            previous.charge != observation.charge -> if (newStatus) LogDatabase.STATUS_NEW else LogDatabase.STATUS_OLD
            lastRecordedElapsed == null || elapsed - lastRecordedElapsed!! !in 0..<HISTORY_SAMPLE_INTERVAL_MS -> LogDatabase.STATUS_SAMPLE
            else -> null
        }
    }

    fun inserted(elapsed: Long) {
        lastRecordedElapsed = elapsed
    }

    fun nextUpdateDelay(elapsed: Long, observedElapsed: Long = elapsed): Long {
        val lastRecorded = lastRecordedElapsed ?: return HISTORY_SAMPLE_INTERVAL_MS
        val sinceLastRecord = elapsed - lastRecorded
        return when {
            sinceLastRecord in 0..<HISTORY_SAMPLE_INTERVAL_MS -> HISTORY_SAMPLE_INTERVAL_MS - sinceLastRecord
            sinceLastRecord >= HISTORY_SAMPLE_INTERVAL_MS && observedElapsed - lastRecorded in 0..<HISTORY_SAMPLE_INTERVAL_MS -> 1L
            else -> HISTORY_SAMPLE_INTERVAL_MS
        }
    }

    companion object {
        fun restoreElapsed(savedElapsed: Long, savedBoot: Int, boot: Int, elapsed: Long): Long? =
            savedElapsed.takeIf { boot >= 0 && savedBoot == boot && it in 0..elapsed }
    }
}

internal fun maintainHistory(
    maxAgeHours: Int, hasExpired: (Int) -> Boolean, export: () -> Boolean, prune: (Int) -> Unit
) {
    if (maxAgeHours >= 0 && hasExpired(maxAgeHours) && export()) prune(maxAgeHours)
}
