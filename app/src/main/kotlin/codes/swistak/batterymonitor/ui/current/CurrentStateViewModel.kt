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

internal class CurrentStateViewModel : ViewModel() {
    var reading: CurrentReading? = null
    var trend: HistoryChartModel? = null
}
