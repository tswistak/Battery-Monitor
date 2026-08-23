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

import codes.swistak.batterymonitor.alarms.AlarmDatabase
import codes.swistak.batterymonitor.alarms.AlarmRecord

internal object Version2AlarmImporter : AlarmImporter {
    const val VERSION = 2

    private val thresholdLessTypes = setOf("charging_limit_met", "discharging_limit_met")

    override val schema: Map<String, Class<*>> = linkedMapOf(
        Version1AlarmImporter.KEY_ENABLED to Boolean::class.java,
        Version1AlarmImporter.KEY_TYPE to String::class.java,
        Version1AlarmImporter.KEY_THRESHOLD to String::class.java
    )

    override fun restore(alarm: Map<String, Any?>): AlarmRecord? {
        val type = alarm[Version1AlarmImporter.KEY_TYPE] as? String ?: return null
        if (type !in AlarmDatabase.SUPPORTED_TYPES) return null
        if (type !in thresholdLessTypes) return Version1AlarmImporter.restore(alarm)

        return AlarmRecord(
            alarm[Version1AlarmImporter.KEY_ENABLED] as? Boolean ?: true, type, ""
        )
    }
}
