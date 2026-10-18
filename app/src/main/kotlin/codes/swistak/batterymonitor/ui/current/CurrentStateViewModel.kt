/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.current

import androidx.lifecycle.ViewModel
import codes.swistak.batterymonitor.logs.HistoryChartModel
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.LogsRepository
import codes.swistak.batterymonitor.monitoring.BatteryCurrentReading
import kotlinx.coroutines.sync.Mutex

internal class CurrentStateViewModel : ViewModel() {
    var reading: BatteryCurrentReading? = null
    val currentOwner = Mutex()

    var trend: HistoryChartModel? = null
    var trendRevision: Long? = null

    suspend fun refreshTrend(
        repository: LogsRepository, activated: Boolean, now: () -> Long = System::currentTimeMillis
    ): HistoryChartModel? {
        var activation = activated
        while (true) {
            val range = HistoryRangeState.lastHours(24, now())
            val changes = repository.historyChanges(trendRevision, range)
            if (!activation && !changes.affectsRange) {
                trendRevision = changes.revision
                return trend
            }
            trend = repository.chart(range)
            trendRevision = changes.revision
            activation = false
            if (repository.historyChanges(
                    trendRevision, range
                ).revision == trendRevision
            ) return trend
        }
    }
}
