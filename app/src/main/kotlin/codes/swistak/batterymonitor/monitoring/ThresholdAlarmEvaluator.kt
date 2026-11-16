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

import codes.swistak.batterymonitor.alarms.AlarmRule

internal class ThresholdAlarmEvaluator {
    private var previousPercent: Int? = null
    private var previousTemperature: Int? = null
    private var previousRules: Set<AlarmRule> = emptySet()

    fun reset() {
        previousPercent = null
        previousTemperature = null
        previousRules = emptySet()
    }

    fun evaluate(percent: Int, temperature: Int, rules: List<AlarmRule>): List<AlarmRule> {
        val active = rules.filter(AlarmRule::enabled)
        val reached = active.filter { rule ->
            if (rule !in previousRules) return@filter false
            val threshold = rule.threshold.toIntOrNull() ?: return@filter false
            when (rule.type) {
                "charge_rises" -> threshold in 0..100 && previousPercent?.let {
                    threshold in (it + 1)..percent
                } == true

                "charge_drops" -> threshold in 0..100 && previousPercent?.let {
                    threshold in percent..<it
                } == true

                "temp_rises" -> threshold in -500..1000 && previousTemperature?.let {
                    threshold in (it + 1)..temperature
                } == true

                "temp_drops" -> threshold in -500..1000 && previousTemperature?.let {
                    threshold in temperature..<it
                } == true

                else -> false
            }
        }
        previousPercent = percent
        previousTemperature = temperature
        previousRules = active.toSet()
        return reached.distinctBy(AlarmRule::type)
    }
}

internal fun shouldDismissRecoveredLowBatteryAlarm(
    enabled: Boolean, percent: Int, channelId: String?, threshold: Int?
): Boolean =
    enabled && channelId == "charge_drops" && threshold != null && threshold in 0..100 && percent > threshold
