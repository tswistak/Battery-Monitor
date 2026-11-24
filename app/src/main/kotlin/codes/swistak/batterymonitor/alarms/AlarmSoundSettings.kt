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
package codes.swistak.batterymonitor.alarms

internal enum class AlarmPlayback { ONCE, REPEAT }

internal enum class AlarmVolumeCategory { NOTIFICATIONS, ALARMS, MEDIA }

internal data class AlarmSoundSettings(
    val playback: AlarmPlayback = AlarmPlayback.ONCE,
    val volumeCategory: AlarmVolumeCategory = AlarmVolumeCategory.NOTIFICATIONS
)

internal data class AlarmConfiguration(
    val sound: AlarmSoundSettings = AlarmSoundSettings(), val channelId: String? = null
)

internal data class AlarmSnapshot(
    val rules: List<AlarmRule>,
    val shared: Map<String, AlarmConfiguration> = emptyMap(),
    val custom: Map<Int, AlarmConfiguration> = emptyMap()
) {
    fun effective(type: String, alarmId: Int? = null): AlarmConfiguration =
        alarmId?.let(custom::get) ?: shared[type] ?: AlarmConfiguration()
}

internal data class AlarmThresholdKey(val type: String, val threshold: Int)

internal data class AlarmRestoreChannelIds(
    val shared: Map<String, String> = emptyMap(),
    val custom: Map<AlarmThresholdKey, String> = emptyMap()
)
