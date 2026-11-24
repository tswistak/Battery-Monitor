/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.alarms.backup

import codes.swistak.batterymonitor.alarms.ALARM_TYPES
import codes.swistak.batterymonitor.alarms.AlarmDatabase
import codes.swistak.batterymonitor.alarms.AlarmPlayback
import codes.swistak.batterymonitor.alarms.AlarmSoundSettings
import codes.swistak.batterymonitor.alarms.AlarmThresholdKey
import codes.swistak.batterymonitor.alarms.AlarmVolumeCategory
import org.json.JSONObject

internal object Version3AlarmImporter : AlarmImporter {
    const val VERSION = 3
    const val KEY_SHARED_SETTINGS = "sharedSettings"
    const val KEY_CUSTOM_SETTINGS = "customSettings"
    const val KEY_PLAYBACK = "playback"
    const val KEY_VOLUME_CATEGORY = "volumeCategory"

    override val schema = Version2AlarmImporter.schema

    override fun restore(alarm: Map<String, Any?>) = Version2AlarmImporter.restore(alarm)

    override fun restoreBackup(root: JSONObject, version: Int): AlarmBackupImportPreview {
        val backup = super.restoreBackup(root, version)
        val shared = buildMap {
            val settings = root.optJSONObject(KEY_SHARED_SETTINGS)
            for (type in AlarmDatabase.SUPPORTED_TYPES) {
                settings?.optJSONObject(type)?.let { put(type, readSoundSettings(it)) }
            }
        }
        val thresholdTypes = ALARM_TYPES.filter { it.bounds != null }.mapTo(hashSetOf()) { it.type }
        val restoredKeys = backup.alarms.mapNotNullTo(hashSetOf()) { alarm ->
            alarm.threshold.toIntOrNull()?.takeIf { alarm.type in thresholdTypes }
                ?.let { AlarmThresholdKey(alarm.type, it) }
        }
        val custom = buildMap {
            val settings = root.optJSONArray(KEY_CUSTOM_SETTINGS) ?: return@buildMap
            for (index in 0 until settings.length()) {
                val item = settings.optJSONObject(index) ?: continue
                val type = item.opt(Version1AlarmImporter.KEY_TYPE) as? String ?: continue
                val threshold = item.opt(Version1AlarmImporter.KEY_THRESHOLD) as? Int ?: continue
                val key = AlarmThresholdKey(type, threshold)
                if (key in restoredKeys && key !in this) put(key, readSoundSettings(item))
            }
        }
        return backup.copy(shared = shared, custom = custom)
    }

    private fun readSoundSettings(json: JSONObject) =
        AlarmSoundSettings(playback = AlarmPlayback.entries.firstOrNull {
            it.name == json.opt(KEY_PLAYBACK)
        } ?: AlarmPlayback.ONCE, volumeCategory = AlarmVolumeCategory.entries.firstOrNull {
            it.name == json.opt(KEY_VOLUME_CATEGORY)
        } ?: AlarmVolumeCategory.NOTIFICATIONS)
}
