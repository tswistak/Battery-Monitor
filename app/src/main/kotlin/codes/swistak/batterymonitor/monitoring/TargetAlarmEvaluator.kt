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

import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingLimitReading
import codes.swistak.batterymonitor.monitoring.charginglimit.TargetSource
import java.util.BitSet

internal data class TargetAlarmUpdate(
    val percent: Int,
    val plugged: Int,
    val chargingTarget: Int?,
    val dischargingTarget: Int,
    val automaticLimit: ChargingLimitReading? = null
) {
    constructor(
        info: BatteryInfo, targets: ResolvedPredictionTargets, limit: ChargingLimitReading
    ) : this(
        info.percent,
        info.plugged,
        targets.charging.percent.takeUnless {
            limit.state == null && limit.pendingRead != null && targets.charging.source != TargetSource.CUSTOM
        },
        targets.discharging.percent,
        limit.takeUnless { targets.charging.source == TargetSource.CUSTOM })
}

internal data class TargetAlarmResult(
    val chargingLimitReached: Boolean, val dischargingLimitReached: Boolean
)

internal class TargetAlarmEvaluator {
    var chargingArmed: Boolean = false
        private set

    var dischargingArmed: Boolean = false
        private set

    private var lastPercent: Int? = null
    private var lastChargingPercent: Int? = null
    private var automaticTargetGeneration: Long? = null
    private var trackedRead: Long? = null
    private val pendingCrossings = BitSet(101)

    fun evaluate(update: TargetAlarmUpdate): TargetAlarmResult {
        var dischargingLimitReached = false

        val chargingSession = update.plugged != BatteryInfo.PLUGGED_UNPLUGGED
        val dischargingSession = update.plugged == BatteryInfo.PLUGGED_UNPLUGGED

        val chargingLimitReached = evaluateCharging(update, chargingSession)

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

    private fun evaluateCharging(update: TargetAlarmUpdate, plugged: Boolean): Boolean {
        val limit = update.automaticLimit
        if (!plugged || automaticTargetGeneration != limit?.generation) {
            automaticTargetGeneration = limit?.generation
            chargingArmed = false
            lastChargingPercent = null
            trackedRead = null
            pendingCrossings.clear()
        }
        if (!plugged) return false

        val percent = update.percent.coerceIn(0, 100)
        val previous = lastChargingPercent
        lastChargingPercent = percent
        val target = update.chargingTarget
        val reachedWhilePending =
            target != null && target < 100 && trackedRead != null && trackedRead == limit?.completedRead && pendingCrossings[target]
        if (trackedRead != limit?.pendingRead) {
            trackedRead = limit?.pendingRead
            pendingCrossings.clear()
        }
        if (trackedRead != null && previous != null && previous < percent) {
            pendingCrossings.set(previous + 1, percent + 1)
        }
        if (target == null) return false
        if (target >= 100) {
            chargingArmed = false
            return false
        }

        val crossedNow = previous != null && previous < target && percent >= target
        val reached = reachedWhilePending || crossedNow && (limit != null || chargingArmed)
        if (reached) pendingCrossings.clear(target)
        chargingArmed = percent < target
        return reached
    }
}
