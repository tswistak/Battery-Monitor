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
import org.json.JSONObject


internal interface AlarmImporter {
    val schema: Map<String, Class<*>>

    fun restore(alarm: Map<String, Any?>): AlarmRecord?

    fun restoreBackup(root: JSONObject, version: Int): AlarmBackupImportPreview {
        val alarms = root.getJSONArray("alarms")
        val restored = buildList {
            for (index in 0 until alarms.length()) {
                val alarm = alarms.optJSONObject(index) ?: continue
                val values = buildMap<String, Any?> {
                    for (key in schema.keys) {
                        if (alarm.has(key) && !alarm.isNull(key)) put(key, alarm.get(key))
                    }
                }
                restore(values)?.let(::add)
            }
        }
        require(alarms.length() == 0 || restored.isNotEmpty()) {
            "The backup contains no supported alarms"
        }
        return AlarmBackupImportPreview(version, restored, emptyMap(), emptyMap())
    }
}

internal fun alarmImporterForVersion(version: Int): AlarmImporter {
    require(version >= Version1AlarmImporter.VERSION) {
        "Unsupported alarm schema version: $version"
    }
    return when {
        version >= Version3AlarmImporter.VERSION -> Version3AlarmImporter
        version >= Version2AlarmImporter.VERSION -> Version2AlarmImporter
        else -> Version1AlarmImporter
    }
}
