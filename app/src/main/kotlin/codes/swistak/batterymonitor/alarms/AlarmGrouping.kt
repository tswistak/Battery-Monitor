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
package codes.swistak.batterymonitor.alarms

import android.app.NotificationManager
import android.content.Context
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.monitoring.BatteryInfoService

internal fun refreshTargetAlarmChannelNames(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    for ((id, name) in listOf(
        BatteryInfoService.CHAN_ID_A_CHARGING_LIMIT to R.string.alarm_type_charging_target_reached,
        BatteryInfoService.CHAN_ID_A_DISCHARGING_LIMIT to R.string.alarm_type_discharging_target_reached
    )) {
        val channel = manager.getNotificationChannel(id) ?: continue
        channel.name = context.getString(name)
        manager.createNotificationChannel(channel)
    }
}

internal enum class AlarmGroup { LEVEL, TARGETS, TEMPERATURE, STATUS }

internal data class AlarmTypeDefinition(
    val type: String,
    val group: AlarmGroup,
    val bounds: IntRange? = null,
    val descending: Boolean = false
)

internal val ALARM_TYPES = listOf(
    AlarmTypeDefinition("fully_charged", AlarmGroup.LEVEL),
    AlarmTypeDefinition("charge_rises", AlarmGroup.LEVEL, 0..100),
    AlarmTypeDefinition("charge_drops", AlarmGroup.LEVEL, 0..100, descending = true),
    AlarmTypeDefinition("charging_limit_met", AlarmGroup.TARGETS),
    AlarmTypeDefinition("discharging_limit_met", AlarmGroup.TARGETS),
    AlarmTypeDefinition("temp_rises", AlarmGroup.TEMPERATURE, -500..1000),
    AlarmTypeDefinition("temp_drops", AlarmGroup.TEMPERATURE, -500..1000, descending = true),
    AlarmTypeDefinition("health_failure", AlarmGroup.STATUS)
)

internal data class LogicalAlarm(val members: List<AlarmRule>) {
    val id: Int get() = members.minOf(AlarmRule::id)
    val type: String get() = members.first().type
    val threshold: Int? get() = members.first().threshold.toIntOrNull()
    val enabled: Boolean get() = members.any(AlarmRule::enabled)
    val memberIds: List<Int> get() = members.map(AlarmRule::id)
}

internal data class AlarmTypeRows(
    val definition: AlarmTypeDefinition, val alarms: List<LogicalAlarm>
)

internal data class AlarmProjection(val types: List<AlarmTypeRows>, val invalid: List<AlarmRule>) {
    val enabledCount: Int get() = types.sumOf { it.alarms.count(LogicalAlarm::enabled) }
}

internal fun validStoredAlarm(rule: AlarmRule): Boolean {
    val definition = ALARM_TYPES.find { it.type == rule.type } ?: return false
    return definition.bounds == null || rule.threshold.toIntOrNull() in definition.bounds
}

internal fun sameAlarmKey(first: AlarmRule, second: AlarmRule): Boolean =
    first.type == second.type && (ALARM_TYPES.find { it.type == first.type }?.bounds == null || first.threshold.toIntOrNull() != null && first.threshold.toIntOrNull() == second.threshold.toIntOrNull())

internal fun projectAlarms(rules: List<AlarmRule>): AlarmProjection {
    val valid = rules.filter(::validStoredAlarm).groupBy(AlarmRule::type)
    return AlarmProjection(
        types = ALARM_TYPES.map { definition ->
            val alarms = valid[definition.type].orEmpty().groupBy {
                if (definition.bounds == null) null else it.threshold.toIntOrNull()
            }.values.map(::LogicalAlarm)
            AlarmTypeRows(
                definition,
                if (definition.descending) alarms.sortedByDescending(LogicalAlarm::threshold)
                else alarms.sortedBy(LogicalAlarm::threshold)
            )
        }, invalid = rules.filterNot(::validStoredAlarm)
    )
}

internal class AlarmConflictException : IllegalStateException()
internal class AlarmCollisionException(val existing: LogicalAlarm) : IllegalStateException()

internal fun requireAlarmMembers(current: List<AlarmRule>, expected: List<AlarmRule>) {
    if (expected.isEmpty()) throw AlarmConflictException()
    val first = expected.first()
    val actual = if (validStoredAlarm(first)) current.filter { sameAlarmKey(it, first) }
    else current.filter { it.id in expected.map(AlarmRule::id) }
    if (actual.sortedBy(AlarmRule::id) != expected.sortedBy(AlarmRule::id)) throw AlarmConflictException()
}

internal fun thresholdCollision(
    rules: List<AlarmRule>, type: String, threshold: String, excludedIds: List<Int>
): LogicalAlarm? = rules.filter {
    it.id !in excludedIds && validStoredAlarm(it) && sameAlarmKey(
        it, AlarmRule(-1, true, type, threshold)
    )
}.takeIf { it.isNotEmpty() }?.let(::LogicalAlarm)
