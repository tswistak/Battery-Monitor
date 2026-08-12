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

internal object Version1AlarmImporter : AlarmImporter {
    const val VERSION = 1

    const val KEY_ENABLED = "enabled"
    const val KEY_TYPE = "type"
    const val KEY_THRESHOLD = "threshold"

    override val schema: Map<String, Class<*>> = linkedMapOf(
        KEY_ENABLED to Boolean::class.java,
        KEY_TYPE to String::class.java,
        KEY_THRESHOLD to String::class.java
    )

    override fun restore(alarm: Map<String, Any?>): AlarmRecord? {
        val type = alarm[KEY_TYPE] as? String ?: return null
        if (type !in AlarmDatabase.SUPPORTED_TYPES) return null

        val enabled = alarm[KEY_ENABLED] as? Boolean ?: true
        val threshold =
            validThreshold(type, alarm[KEY_THRESHOLD] as? String) ?: defaultThreshold(type)
        return AlarmRecord(enabled, type, threshold)
    }

    private fun validThreshold(type: String, threshold: String?): String? {
        if (type == "fully_charged" || type == "health_failure") return ""
        val value = threshold?.toIntOrNull() ?: return null
        return when (type) {
            "charge_drops", "charge_rises" -> threshold.takeIf { value in 0..100 }
            "temp_drops", "temp_rises" -> threshold.takeIf { value in -500..1000 }
            else -> null
        }
    }

    private fun defaultThreshold(type: String): String = when (type) {
        "charge_drops" -> "20"
        "charge_rises" -> "90"
        "temp_drops" -> "60"
        "temp_rises" -> "460"
        else -> ""
    }
}
