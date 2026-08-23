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
package codes.swistak.batterymonitor.alarms.backup

import codes.swistak.batterymonitor.alarms.AlarmRecord

internal interface AlarmImporter {
    val schema: Map<String, Class<*>>

    fun restore(alarm: Map<String, Any?>): AlarmRecord?
}

internal fun alarmImporterForVersion(version: Int): AlarmImporter {
    require(version >= Version1AlarmImporter.VERSION) {
        "Unsupported alarm schema version: $version"
    }
    return if (version >= Version2AlarmImporter.VERSION) {
        Version2AlarmImporter
    } else {
        Version1AlarmImporter
    }
}
