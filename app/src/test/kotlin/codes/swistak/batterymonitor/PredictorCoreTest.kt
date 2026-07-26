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
package codes.swistak.batterymonitor

import org.junit.Assert
import org.junit.Before
import org.junit.Test

class PredictorCoreTest {
    companion object {
        private const val ONE_MINUTE = 60000L

        private const val DISCHARGE_MS_PER_PERCENT: Long = ONE_MINUTE
        private const val AC_MS_PER_PERCENT: Long = 2 * ONE_MINUTE
        private const val WIRELESS_MS_PER_PERCENT: Long = 3 * ONE_MINUTE
        private const val USB_MS_PER_PERCENT: Long = 4 * ONE_MINUTE
    }

    private var predictor: PredictorCore? = null
    private var info: BatteryInfo? = null

    @Before
    fun setUp() {
        predictor = PredictorCore(
            DISCHARGE_MS_PER_PERCENT.toFloat(),
            AC_MS_PER_PERCENT.toFloat(),
            WIRELESS_MS_PER_PERCENT.toFloat(),
            USB_MS_PER_PERCENT.toFloat()
        )

        predictor!!.setPredictionType(PredictorCore.LONG_TERM)
        info = BatteryInfo()
    }

    /**
     * Verifies that an unplugged battery produces a prediction of the remaining
     * time until it is fully discharged.
     */
    @Test
    fun unpluggedBatteryProducesDrainPrediction() {
        val now = 0L

        info!!.status = BatteryInfo.STATUS_UNPLUGGED
        info!!.plugged = BatteryInfo.PLUGGED_UNPLUGGED
        info!!.percent = 80

        predictor!!.update(info, now)

        Assert.assertEquals(
            BatteryInfo.Prediction.UNTIL_DRAINED.toLong(), info!!.prediction.what.toLong()
        )
        Assert.assertEquals(80L * DISCHARGE_MS_PER_PERCENT, info!!.prediction.`when`)
    }

    /**
     * Verifies that a battery charging over USB produces a prediction of the
     * remaining time until it is fully charged.
     */
    @Test
    fun chargingBatteryProducesChargePrediction() {
        val now = 0L

        info!!.status = BatteryInfo.STATUS_CHARGING
        info!!.plugged = BatteryInfo.PLUGGED_USB
        info!!.percent = 80

        predictor!!.update(info, now)

        Assert.assertEquals(
            BatteryInfo.Prediction.UNTIL_CHARGED.toLong(), info!!.prediction.what.toLong()
        )
        Assert.assertTrue(
            "Charging prediction should point into the future",
            info!!.prediction.`when` >= now + ONE_MINUTE
        )
    }

    /**
     * Verifies that reaching the fully charged state removes any previously
     * calculated battery prediction.
     */
    @Test
    fun fullyChargedStatusClearsPrediction() {
        info!!.status = BatteryInfo.STATUS_UNPLUGGED
        info!!.plugged = BatteryInfo.PLUGGED_UNPLUGGED
        info!!.percent = 50

        predictor!!.update(info, 0L)

        info!!.status = BatteryInfo.STATUS_FULLY_CHARGED
        info!!.percent = 100

        predictor!!.update(info, ONE_MINUTE)

        Assert.assertEquals(BatteryInfo.Prediction.NONE.toLong(), info!!.prediction.what.toLong())
        Assert.assertEquals(0L, info!!.prediction.`when`)
    }

    /**
     * Verifies that connecting a charger while the battery is discharging replaces
     * the discharge prediction with a charging prediction.
     */
    @Test
    fun changingFromDischargingToChargingChangesPredictionType() {
        info!!.status = BatteryInfo.STATUS_UNPLUGGED
        info!!.plugged = BatteryInfo.PLUGGED_UNPLUGGED
        info!!.percent = 70

        predictor!!.update(info, 0L)

        Assert.assertEquals(
            BatteryInfo.Prediction.UNTIL_DRAINED.toLong(), info!!.prediction.what.toLong()
        )

        info!!.status = BatteryInfo.STATUS_CHARGING
        info!!.plugged = BatteryInfo.PLUGGED_USB

        predictor!!.update(info, ONE_MINUTE)

        Assert.assertEquals(
            BatteryInfo.Prediction.UNTIL_CHARGED.toLong(), info!!.prediction.what.toLong()
        )
        Assert.assertTrue(info!!.prediction.`when` >= 2 * ONE_MINUTE)
    }

    /**
     * Verifies that irregular intervals between battery level changes still produce
     * a valid discharge prediction pointing to a future time.
     */
    @Test
    fun irregularDischargeIntervalsStillProduceFutureDrainPrediction() {
        val now = replayLegacyIrregularDischargeScenario()

        Assert.assertEquals(
            BatteryInfo.Prediction.UNTIL_DRAINED.toLong(), info!!.prediction.what.toLong()
        )
        Assert.assertTrue(
            "Prediction should remain at least one minute in the future",
            info!!.prediction.`when` >= now + ONE_MINUTE
        )
    }

    /**
     * Verifies that an unexpected battery level increase with an unknown status
     * removes the stale discharge prediction.
     */
    @Test
    fun increasingLevelWithUnknownStatusClearsPrediction() {
        var now = replayLegacyIrregularDischargeScenario()

        info!!.status = BatteryInfo.STATUS_UNKNOWN
        info!!.percent = 90
        predictor!!.update(info, now)

        now += ONE_MINUTE
        info!!.percent = 91
        predictor!!.update(info, now)

        Assert.assertEquals(BatteryInfo.Prediction.NONE.toLong(), info!!.prediction.what.toLong())
        Assert.assertEquals(0L, info!!.prediction.`when`)
    }

    private fun replayLegacyIrregularDischargeScenario(): Long {
        val minutesByLevel = intArrayOf(300, 145, 21, 11, 2, 3, 2, 2, 1, 2, 3, 1, 2)

        predictor!!.setPredictionType(PredictorCore.SINCE_STATUS_CHANGE)

        info!!.status = BatteryInfo.STATUS_UNPLUGGED
        info!!.plugged = BatteryInfo.PLUGGED_UNPLUGGED
        info!!.percent = 100

        var now = 0L

        minutesByLevel.forEach { minutesAtLevel ->
            (0..<minutesAtLevel).forEach { _ ->
                now += ONE_MINUTE
                predictor!!.update(info, now)
            }

            info!!.percent -= 1
        }

        now += ONE_MINUTE
        predictor!!.update(info, now)

        return now
    }
}
