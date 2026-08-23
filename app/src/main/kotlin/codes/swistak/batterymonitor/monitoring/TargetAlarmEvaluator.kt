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
package codes.swistak.batterymonitor.monitoring

internal data class TargetAlarmUpdate(
    val percent: Int, val plugged: Int, val chargingTarget: Int, val dischargingTarget: Int
)

internal data class TargetAlarmResult(
    val chargingLimitReached: Boolean, val dischargingLimitReached: Boolean
)

internal class TargetAlarmEvaluator {
    var chargingArmed: Boolean = false
        private set

    var dischargingArmed: Boolean = false
        private set

    private var lastPercent: Int? = null

    fun evaluate(update: TargetAlarmUpdate): TargetAlarmResult {
        var chargingLimitReached = false
        var dischargingLimitReached = false

        val chargingSession = update.plugged != BatteryInfo.PLUGGED_UNPLUGGED
        val dischargingSession = update.plugged == BatteryInfo.PLUGGED_UNPLUGGED

        if (update.chargingTarget < 100 && chargingSession) {
            val crossedUp =
                chargingArmed && lastPercent != null && lastPercent!! < update.chargingTarget && update.percent >= update.chargingTarget
            if (crossedUp) {
                chargingLimitReached = true
                chargingArmed = false
            } else {
                chargingArmed = update.percent < update.chargingTarget
            }
        } else {
            chargingArmed = false
        }

        if (update.dischargingTarget > 0 && dischargingSession) {
            val crossedDown =
                dischargingArmed && lastPercent != null && lastPercent!! > update.dischargingTarget && update.percent <= update.dischargingTarget
            if (crossedDown) {
                dischargingLimitReached = true
                dischargingArmed = false
            } else {
                dischargingArmed = update.percent > update.dischargingTarget
            }
        } else {
            dischargingArmed = false
        }

        lastPercent = update.percent
        return TargetAlarmResult(chargingLimitReached, dischargingLimitReached)
    }
}
