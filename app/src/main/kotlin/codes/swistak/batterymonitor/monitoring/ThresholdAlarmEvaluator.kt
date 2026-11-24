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
package codes.swistak.batterymonitor.monitoring

import codes.swistak.batterymonitor.alarms.LogicalAlarm

internal class ThresholdAlarmEvaluator {
    private data class Threshold(val id: Int, val type: String, val value: Int)

    private var previousPercent: Int? = null
    private var previousTemperature: Int? = null
    private var previousThresholds: Set<Threshold> = emptySet()

    fun reset() {
        previousPercent = null
        previousTemperature = null
        previousThresholds = emptySet()
    }

    fun evaluate(percent: Int, temperature: Int, alarms: List<LogicalAlarm>): List<LogicalAlarm> {
        val active = alarms.filter(LogicalAlarm::enabled).mapNotNull { alarm ->
            alarm.threshold?.let { Threshold(alarm.id, alarm.type, it) to alarm }
        }
        val reached = active.mapNotNull { (threshold, alarm) ->
            if (threshold !in previousThresholds) return@mapNotNull null
            val crossed = when (threshold.type) {
                "charge_rises" -> previousPercent?.let { threshold.value in (it + 1)..percent }
                "charge_drops" -> previousPercent?.let { threshold.value in percent..<it }
                "temp_rises" -> previousTemperature?.let { threshold.value in (it + 1)..temperature }
                "temp_drops" -> previousTemperature?.let { threshold.value in temperature..<it }
                else -> false
            }
            alarm.takeIf { crossed == true }
        }
        previousPercent = percent
        previousTemperature = temperature
        previousThresholds = active.mapTo(mutableSetOf()) { it.first }
        return reached
    }
}

internal fun shouldDismissAlarmAfterBatteryUpdate(
    type: String,
    value: Int?,
    repeating: Boolean,
    battery: BatteryInfo,
    chargingTarget: Int,
    dischargingTarget: Int,
    dismissLowBatteryOnRecovery: Boolean
): Boolean {
    val plugged = battery.plugged != BatteryInfo.PLUGGED_UNPLUGGED
    return when (type) {
        "fully_charged" -> !plugged || (repeating && battery.status != BatteryInfo.STATUS_FULLY_CHARGED)
        "charge_rises" -> !plugged || (repeating && value != null && battery.percent < value)
        "charge_drops" -> dismissLowBatteryOnRecovery && value != null && value in 0..100 && battery.percent > value
        "charging_limit_met" -> !plugged || value != chargingTarget || (repeating && battery.percent < chargingTarget)
        "discharging_limit_met" -> plugged || value != dischargingTarget || ((repeating || dismissLowBatteryOnRecovery) && battery.percent > dischargingTarget)
        "temp_rises" -> repeating && value != null && battery.temperature < value
        "temp_drops" -> repeating && value != null && battery.temperature > value
        "health_failure" -> repeating && battery.health == BatteryInfo.HEALTH_GOOD
        else -> false
    }
}
