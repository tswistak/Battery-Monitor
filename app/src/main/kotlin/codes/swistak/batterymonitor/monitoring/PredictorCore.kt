/*
    Copyright (c) 2009-2020 Darshan Computing, LLC
    Modified in 2026 by Tomasz Świstak <tomasz@swistak.codes> for the Battery Monitor fork.
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

import kotlin.math.abs

internal class PredictorCore(
    avgDischarge: Float, avgRechargeAc: Float, avgRechargeWl: Float, avgRechargeUsb: Float
) {
    companion object {
        const val DISCHARGE: Int = 0
        const val RECHARGE_AC: Int = 1
        const val RECHARGE_WL: Int = 2
        const val RECHARGE_USB: Int = 3

        const val ONE_MINUTE: Int = 60 * 1000
        const val FIVE_MINUTES: Int = ONE_MINUTE * 5

        const val SINCE_STATUS_CHANGE: Int = -1
        const val LONG_TERM: Int = -2
        const val AUTOMAGIC: Int = -3

        private const val MIN_PREDICTION: Int = ONE_MINUTE

        private const val WEIGHT_OLD_AVERAGE = 0.998
        private const val WEIGHT_NEW_DATA: Double = 1 - WEIGHT_OLD_AVERAGE

        private val DEFAULT = intArrayOf(
            24 * 60 * 60 * 1000 / 100,
            3 * 60 * 60 * 1000 / 100,
            4 * 60 * 60 * 1000 / 100,
            6 * 60 * 60 * 1000 / 100
        )
    }

    private var predictionType: Int = AUTOMAGIC
    private var chargingTargetPercent: Int = 100
    private var dischargingTargetPercent: Int = 0

    private val timestamps = LongArray(101)
    private var tsHead = 0

    private val average = DoubleArray(4)

    private var curInfo: BatteryInfo? = null
    private var lastLevel = 0
    private var lastStatus = -1
    private var lastPlugged = 0
    private var lastPrediction: Long = 0
    private var lastRecentAverage = 0.0
    private var dirInc = 0
    private var now: Long = 0
    private var usePartial = false
    private var initial = false

    var curChargingStatus: Int = 0

    init {
        average[DISCHARGE] =
            (if (avgDischarge == -1f) DEFAULT[DISCHARGE].toFloat() else avgDischarge).toDouble()
        average[RECHARGE_AC] =
            (if (avgRechargeAc == -1f) DEFAULT[RECHARGE_AC].toFloat() else avgRechargeAc).toDouble()
        average[RECHARGE_WL] =
            (if (avgRechargeWl == -1f) DEFAULT[RECHARGE_WL].toFloat() else avgRechargeWl).toDouble()
        average[RECHARGE_USB] =
            (if (avgRechargeUsb == -1f) DEFAULT[RECHARGE_USB].toFloat() else avgRechargeUsb).toDouble()
    }

    fun setPredictionType(type: Int) {
        if (type == predictionType) return

        predictionType = type

        if (curInfo == null) return

        if (!canPredict()) {
            lastPrediction = 0
            updateExposedPredictions()
            return
        }

        usePartial = false
        updateReferencePrediction()

        if (timestamps[lastLevel] != now && shouldUsePartial()) {
            usePartial = true
            updateReferencePrediction()
        }

        updateExposedPredictions()
    }

    fun setTargets(chargingTargetPercent: Int, dischargingTargetPercent: Int) {
        val newChargingTarget = chargingTargetPercent.coerceIn(1, 100)
        val newDischargingTarget = dischargingTargetPercent.coerceIn(0, 99)
        if (this.chargingTargetPercent == newChargingTarget && this.dischargingTargetPercent == newDischargingTarget) return

        this.chargingTargetPercent = newChargingTarget
        this.dischargingTargetPercent = newDischargingTarget
    }

    fun update(info: BatteryInfo, whenUpdated: Long) {
        if (info.status == BatteryInfo.STATUS_UNKNOWN) {
            info.prediction.clear()
            info.fullRangePrediction.clear()
            return
        }

        curInfo = info
        now = whenUpdated

        updatePredictionState(info)
        updateExposedPredictions()
    }

    private fun updatePredictionState(info: BatteryInfo) {
        if (!canPredict()) {
            lastPrediction = 0
            setLastsWithoutPrediction()
            return
        }

        curChargingStatus = chargingStatusForCurInfo()

        if (lastPrediction < now + MIN_PREDICTION) {
            lastPrediction = now + MIN_PREDICTION
        }

        if (info.status != lastStatus || info.plugged != lastPlugged || info.status == BatteryInfo.STATUS_FULLY_CHARGED || (info.status == BatteryInfo.STATUS_CHARGING && info.percent < tsHead) || (info.status == BatteryInfo.STATUS_UNPLUGGED && info.percent > tsHead)) {
            initial = true
            usePartial = false

            tsHead = info.percent
            dirInc = if (info.status == BatteryInfo.STATUS_CHARGING) -1 else 1

            timestamps[info.percent] = now

            finishPredictionStateUpdate()
            return
        }

        if ((info.status == BatteryInfo.STATUS_CHARGING && info.percent < lastLevel) || (info.status != BatteryInfo.STATUS_CHARGING && info.percent > lastLevel)) {
            usePartial = false
            timestamps[info.percent] = now
            finishPredictionStateUpdate()
            return
        }

        val levelDiff = abs(lastLevel - info.percent)

        if (levelDiff == 0) {
            if (shouldUsePartial()) usePartial = true
            else return
        } else {
            usePartial = false
            val msDiff = (now - timestamps[lastLevel]).toDouble()
            val msPerPoint = msDiff / levelDiff

            run {
                var i = 0
                while (i < levelDiff) {
                    timestamps[info.percent + (i * dirInc)] = now - (i * msPerPoint).toLong()
                    i += 1
                }
            }

            if (initial && msPerPoint < lastRecentAverage) {
                initial = false
                tsHead = info.percent
                setLasts()
                return
            }

            initial = false

            (0..<levelDiff).forEach { _ ->
                average[curChargingStatus] =
                    average[curChargingStatus] * WEIGHT_OLD_AVERAGE + msPerPoint * WEIGHT_NEW_DATA
            }
        }

        finishPredictionStateUpdate()
    }

    private fun updateExposedPredictions() {
        val info = curInfo!!
        if (!canPredict()) {
            info.prediction.clear()
            info.fullRangePrediction.clear()
        } else {
            info.prediction.update(
                predictionAtLeastOneMinute(
                    prediction(chargingTargetPercent, dischargingTargetPercent)
                ), targetFor(info, chargingTargetPercent, dischargingTargetPercent)
            )
            info.fullRangePrediction.update(
                predictionAtLeastOneMinute(
                    prediction(
                        chargingTargetPercent = 100, dischargingTargetPercent = 0
                    )
                ), targetFor(
                    info = info, chargingTargetPercent = 100, dischargingTargetPercent = 0
                )
            )
        }

        val target = targetFor(info, chargingTargetPercent, dischargingTargetPercent)
        when {
            isAtDischargingTarget(info) -> {
                info.prediction.markDischargingTargetReached(target)
            }

            isTargetReached(info, target) -> {
                info.prediction.markTargetReached(target)
            }
        }
    }

    val longTermAverage: Double
        get() = average[curChargingStatus]

    private fun shouldUsePartial(): Boolean {
        if (usePartial) return true

        val msDiff = (now - timestamps[lastLevel]).toDouble()
        if (msDiff <= lastRecentAverage) return false
        if (predictionIfPartial() <= lastPrediction) return false
        return true
    }

    private fun predictionIfPartial(): Long {
        return predictionIfPartialIs(true)
    }

    private fun predictionIfPartialIs(supposed: Boolean): Long {
        val oldPartial = usePartial
        usePartial = supposed
        val ret = prediction(chargingTargetPercent = 100, dischargingTargetPercent = 0)
        usePartial = oldPartial
        return ret
    }

    private fun finishPredictionStateUpdate() {
        updateReferencePrediction()
        setLasts()
    }

    private fun updateReferencePrediction() {
        lastPrediction = predictionAtLeastOneMinute(
            prediction(chargingTargetPercent = 100, dischargingTargetPercent = 0)
        )
    }

    private fun predictionAtLeastOneMinute(prediction: Long): Long =
        maxOf(prediction, now + MIN_PREDICTION)

    private fun prediction(chargingTargetPercent: Int, dischargingTargetPercent: Int): Long {
        return when (curInfo!!.status) {
            BatteryInfo.STATUS_CHARGING -> whenCharged(chargingTargetPercent)
            BatteryInfo.STATUS_UNPLUGGED -> whenDrained(dischargingTargetPercent)
            else -> 0
        }
    }

    private fun whenDrained(dischargingTargetPercent: Int): Long {
        var level = curInfo!!.percent
        var from = timestamps[curInfo!!.percent]

        if (usePartial) {
            level -= dirInc
            from = now
        }

        val target = targetFor(
            info = curInfo!!,
            chargingTargetPercent = chargingTargetPercent,
            dischargingTargetPercent = dischargingTargetPercent
        )
        return from + (recentAverage() * (level - target)).toLong()
    }

    private fun whenCharged(chargingTargetPercent: Int): Long {
        var level = curInfo!!.percent
        var from = timestamps[curInfo!!.percent]

        if (usePartial) {
            level -= dirInc
            from = now
        }

        val pointsRemaining = if (chargingTargetPercent == 100) {
            101 - level
        } else {
            chargingTargetPercent - level
        }
        return from + (pointsRemaining * recentAverage()).toLong()
    }

    private fun targetFor(
        info: BatteryInfo, chargingTargetPercent: Int, dischargingTargetPercent: Int
    ): Int = if (info.status == BatteryInfo.STATUS_UNPLUGGED) {
        if (dischargingTargetPercent > 0 && info.percent < dischargingTargetPercent) {
            0
        } else {
            dischargingTargetPercent
        }
    } else {
        chargingTargetPercent
    }

    private fun isAtDischargingTarget(info: BatteryInfo): Boolean =
        info.status == BatteryInfo.STATUS_UNPLUGGED && dischargingTargetPercent > 0 && info.percent == dischargingTargetPercent

    private fun isTargetReached(info: BatteryInfo, target: Int): Boolean {
        return when {
            info.status == BatteryInfo.STATUS_CHARGING -> chargingTargetPercent < 100 && info.percent >= target

            info.plugged != BatteryInfo.PLUGGED_UNPLUGGED && info.status in setOf(
                BatteryInfo.STATUS_NOT_CHARGING, BatteryInfo.STATUS_FULLY_CHARGED
            ) -> chargingTargetPercent < 100 && info.percent >= target

            else -> false
        }
    }

    private fun setLasts() {
        lastLevel = curInfo!!.percent
        lastStatus = curInfo!!.status
        lastPlugged = curInfo!!.plugged
        lastRecentAverage = recentAverage()
    }

    private fun setLastsWithoutPrediction() {
        lastLevel = curInfo!!.percent
        lastStatus = curInfo!!.status
        lastPlugged = curInfo!!.plugged
    }

    private fun canPredict(): Boolean {
        return curInfo!!.status == BatteryInfo.STATUS_CHARGING || curInfo!!.status == BatteryInfo.STATUS_UNPLUGGED
    }

    private fun recentAverage(): Double {
        return if (predictionType > 100) recentAverageByTime(predictionType.toDouble())
        else if (predictionType > 0) recentAverageByPoints(predictionType.toDouble())
        else if (predictionType == SINCE_STATUS_CHANGE) recentAverageBySession()
        else if (predictionType == AUTOMAGIC) middleOf(
            recentAverageByTime(FIVE_MINUTES.toDouble()),
            recentAverageByPoints(5.0),
            average[curChargingStatus]
        )
        else average[curChargingStatus]
    }

    private fun middleOf(first: Double, second: Double, third: Double): Double {
        return if ((second in third..first) || (second in first..third)) second
        else if ((first in third..second) || (first in second..third)) first
        else third
    }

    private fun recentAverageByTime(durationInMs: Double): Double {
        var totalPoints = 0.0
        var neededMs = durationInMs

        var start = curInfo!!.percent
        if (usePartial) start -= dirInc

        var i = start
        while (i != tsHead) {
            val potentialMs: Double

            // 20170803:
            if (i < 0) i = 0
            if (i > 100) i = 100
            // 20170803:
            // Timestamps is always length 101, with valid indices of 0-100
            // dir_inc is either 1 (discharging) or -1 (charging)
            // if (i + dir_inc) is outside that range, the first branch is taken
            // so an index out of range in the second branch has to be from i itself
            if ((i == start && usePartial) || (i + dirInc > 100) || (i + dirInc < 0)) potentialMs =
                (now - timestamps[curInfo!!.percent]).toDouble()
            else potentialMs = (timestamps[i] - timestamps[i + dirInc]).toDouble()

            if (potentialMs > neededMs) {
                totalPoints += neededMs / potentialMs
                neededMs = 0.0
                break
            }

            totalPoints += 1.0
            neededMs -= potentialMs
            i += dirInc
        }

        if (neededMs > 0) totalPoints += neededMs / average[curChargingStatus]

        return durationInMs / totalPoints
    }

    private fun recentAverageByPoints(durationInPoints: Double): Double {
        var totalMs = 0.0
        var neededPoints = durationInPoints

        var start = curInfo!!.percent
        if (usePartial) start -= dirInc

        if (start == tsHead || neededPoints < 1) return average[curChargingStatus]

        var i = start
        while (i != tsHead && neededPoints > 0) {
            val newMs: Double

            if ((i == start && usePartial) || (i + dirInc > 100) || (i + dirInc < 0)) newMs =
                (now - timestamps[curInfo!!.percent]).toDouble()
            else newMs = (timestamps[i] - timestamps[i + dirInc]).toDouble()

            totalMs += newMs
            neededPoints -= 1.0
            i += dirInc
        }

        if (neededPoints > 0) totalMs += neededPoints * average[curChargingStatus]

        return totalMs / durationInPoints
    }

    private fun recentAverageBySession(): Double {
        var totalMs = 0.0
        var totalPoints = 0.0

        if (usePartial) {
            totalMs += (now - timestamps[curInfo!!.percent]).toDouble()
            totalPoints += 1.0
        }

        totalMs += (timestamps[curInfo!!.percent] - timestamps[tsHead]).toDouble()
        totalPoints += (tsHead - curInfo!!.percent).toDouble()

        if (totalPoints < 1) return average[curChargingStatus]

        return totalMs / totalPoints
    }

    private fun chargingStatusForCurInfo(): Int {
        return if (curInfo!!.status == BatteryInfo.STATUS_CHARGING) {
            when (curInfo!!.plugged) {
                BatteryInfo.PLUGGED_USB -> RECHARGE_USB
                BatteryInfo.PLUGGED_WIRELESS -> RECHARGE_WL
                else -> RECHARGE_AC
            }
        } else {
            DISCHARGE
        }
    }
}
