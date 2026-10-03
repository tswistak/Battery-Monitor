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
package codes.swistak.batterymonitor.devicebackup

import codes.swistak.batterymonitor.logs.LogRecord

internal object Version2DeviceDataImporter {
    const val VERSION = 2
    const val KEY_LOG_CURRENT = "currentMicroAmps"
    
    fun restoreLog(values: Map<String, Any?>): LogRecord {
        val current = values[KEY_LOG_CURRENT]
        require(current == null || current is Byte || current is Short || current is Int || current is Long) {
            "Invalid log value for '$KEY_LOG_CURRENT'"
        }
        return Version1DeviceDataImporter.restoreLog(values).copy(
            currentMicroAmps = (current as? Number)?.toLong()
        )
    }
}
