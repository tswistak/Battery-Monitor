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

    private lateinit var predictor: PredictorCore
    private lateinit var info: BatteryInfo

    @Before
    fun setUp() {
        predictor = PredictorCore(
            DISCHARGE_MS_PER_PERCENT.toFloat(),
            AC_MS_PER_PERCENT.toFloat(),
            WIRELESS_MS_PER_PERCENT.toFloat(),
            USB_MS_PER_PERCENT.toFloat()
        )

        predictor.setPredictionType(PredictorCore.LONG_TERM)
        info = BatteryInfo()
    }

    @Test
    fun `unplugged battery predicts time until fully discharged`() {
        val now = 0L

        info.status = BatteryInfo.STATUS_UNPLUGGED
        info.plugged = BatteryInfo.PLUGGED_UNPLUGGED
        info.percent = 80

        predictor.update(info, now)

        Assert.assertEquals(
            BatteryInfo.Prediction.UNTIL_DRAINED.toLong(), info.prediction.whatHappened.toLong()
        )
        Assert.assertEquals(80L * DISCHARGE_MS_PER_PERCENT, info.prediction.whenHappened)
    }

    @Test
    fun `USB charging battery predicts time until fully charged`() {
        val now = 0L

        info.status = BatteryInfo.STATUS_CHARGING
        info.plugged = BatteryInfo.PLUGGED_USB
        info.percent = 80

        predictor.update(info, now)

        Assert.assertEquals(
            BatteryInfo.Prediction.UNTIL_CHARGED.toLong(), info.prediction.whatHappened.toLong()
        )
        Assert.assertTrue(
            "Charging prediction should point into the future",
            info.prediction.whenHappened >= now + ONE_MINUTE
        )
    }

    @Test
    fun `charging battery predicts time until custom target`() {
        predictor.setTargets(chargingTargetPercent = 85, dischargingTargetPercent = 0)
        info.status = BatteryInfo.STATUS_CHARGING
        info.plugged = BatteryInfo.PLUGGED_USB
        info.percent = 80

        predictor.update(info, 0L)

        Assert.assertEquals(BatteryInfo.Prediction.UNTIL_CHARGED, info.prediction.whatHappened)
        Assert.assertEquals(85, info.prediction.targetPercent)
        Assert.assertEquals(5L * USB_MS_PER_PERCENT, info.prediction.whenHappened)
    }

    @Test
    fun `discharging battery predicts time until custom target`() {
        predictor.setTargets(chargingTargetPercent = 100, dischargingTargetPercent = 40)
        info.status = BatteryInfo.STATUS_UNPLUGGED
        info.plugged = BatteryInfo.PLUGGED_UNPLUGGED
        info.percent = 80

        predictor.update(info, 0L)

        Assert.assertEquals(BatteryInfo.Prediction.UNTIL_DRAINED, info.prediction.whatHappened)
        Assert.assertEquals(40, info.prediction.targetPercent)
        Assert.assertEquals(40L * DISCHARGE_MS_PER_PERCENT, info.prediction.whenHappened)
    }

    @Test
    fun `discharging target supports twenty percent rules`() {
        predictor.setTargets(chargingTargetPercent = 100, dischargingTargetPercent = 20)
        info.status = BatteryInfo.STATUS_UNPLUGGED
        info.plugged = BatteryInfo.PLUGGED_UNPLUGGED
        info.percent = 40

        predictor.update(info, 0L)

        Assert.assertEquals(20, info.prediction.targetPercent)
        Assert.assertEquals(20L * DISCHARGE_MS_PER_PERCENT, info.prediction.whenHappened)
    }

    @Test
    fun `charging target reached clears countdown and records target`() {
        predictor.setTargets(chargingTargetPercent = 80, dischargingTargetPercent = 0)
        info.status = BatteryInfo.STATUS_NOT_CHARGING
        info.plugged = BatteryInfo.PLUGGED_USB
        info.percent = 80

        predictor.update(info, 0L)

        Assert.assertEquals(BatteryInfo.Prediction.NONE, info.prediction.whatHappened)
        Assert.assertEquals(0L, info.prediction.whenHappened)
        Assert.assertEquals(80, info.prediction.targetPercent)
        Assert.assertTrue(info.prediction.targetReached)
    }

    @Test
    fun `charging above custom target is treated as reached`() {
        predictor.setTargets(chargingTargetPercent = 80, dischargingTargetPercent = 0)
        info.status = BatteryInfo.STATUS_CHARGING
        info.plugged = BatteryInfo.PLUGGED_USB
        info.percent = 85

        predictor.update(info, 0L)

        Assert.assertTrue(info.prediction.targetReached)
        Assert.assertEquals(80, info.prediction.targetPercent)
    }

    @Test
    fun `zero and one hundred targets retain standard prediction behavior`() {
        predictor.setTargets(chargingTargetPercent = 100, dischargingTargetPercent = 0)
        info.status = BatteryInfo.STATUS_UNPLUGGED
        info.plugged = BatteryInfo.PLUGGED_UNPLUGGED
        info.percent = 20

        predictor.update(info, 0L)

        Assert.assertFalse(info.prediction.targetReached)
        Assert.assertEquals(0, info.prediction.targetPercent)
        Assert.assertEquals(20L * DISCHARGE_MS_PER_PERCENT, info.prediction.whenHappened)
    }

    @Test
    fun `standard full charge target does not use target reached state`() {
        predictor.setTargets(chargingTargetPercent = 100, dischargingTargetPercent = 0)
        info.status = BatteryInfo.STATUS_CHARGING
        info.plugged = BatteryInfo.PLUGGED_USB
        info.percent = 100

        predictor.update(info, 0L)

        Assert.assertFalse(info.prediction.targetReached)
        Assert.assertEquals(BatteryInfo.Prediction.UNTIL_CHARGED, info.prediction.whatHappened)
        Assert.assertEquals(100, info.prediction.targetPercent)
    }

    @Test
    fun `fully charged battery clears previous prediction`() {
        info.status = BatteryInfo.STATUS_UNPLUGGED
        info.plugged = BatteryInfo.PLUGGED_UNPLUGGED
        info.percent = 50

        predictor.update(info, 0L)

        info.status = BatteryInfo.STATUS_FULLY_CHARGED
        info.percent = 100

        predictor.update(info, ONE_MINUTE)

        Assert.assertEquals(
            BatteryInfo.Prediction.NONE.toLong(), info.prediction.whatHappened.toLong()
        )
        Assert.assertEquals(0L, info.prediction.whenHappened)
    }

    @Test
    fun `connecting charger replaces discharge prediction with charge prediction`() {
        info.status = BatteryInfo.STATUS_UNPLUGGED
        info.plugged = BatteryInfo.PLUGGED_UNPLUGGED
        info.percent = 70

        predictor.update(info, 0L)

        Assert.assertEquals(
            BatteryInfo.Prediction.UNTIL_DRAINED.toLong(), info.prediction.whatHappened.toLong()
        )

        info.status = BatteryInfo.STATUS_CHARGING
        info.plugged = BatteryInfo.PLUGGED_USB

        predictor.update(info, ONE_MINUTE)

        Assert.assertEquals(
            BatteryInfo.Prediction.UNTIL_CHARGED.toLong(), info.prediction.whatHappened.toLong()
        )
        Assert.assertTrue(info.prediction.whenHappened >= 2 * ONE_MINUTE)
    }

    @Test
    fun `irregular discharge intervals still predict a future drain time`() {
        val now = replayLegacyIrregularDischargeScenario()

        Assert.assertEquals(
            BatteryInfo.Prediction.UNTIL_DRAINED.toLong(), info.prediction.whatHappened.toLong()
        )
        Assert.assertTrue(
            "Prediction should remain at least one minute in the future",
            info.prediction.whenHappened >= now + ONE_MINUTE
        )
    }

    @Test
    fun `battery level increase with unknown status clears stale discharge prediction`() {
        var now = replayLegacyIrregularDischargeScenario()

        info.status = BatteryInfo.STATUS_UNKNOWN
        info.percent = 90
        predictor.update(info, now)

        now += ONE_MINUTE
        info.percent = 91
        predictor.update(info, now)

        Assert.assertEquals(
            BatteryInfo.Prediction.NONE.toLong(), info.prediction.whatHappened.toLong()
        )
        Assert.assertEquals(0L, info.prediction.whenHappened)
    }

    private fun replayLegacyIrregularDischargeScenario(): Long {
        val minutesByLevel = intArrayOf(300, 145, 21, 11, 2, 3, 2, 2, 1, 2, 3, 1, 2)

        predictor.setPredictionType(PredictorCore.SINCE_STATUS_CHANGE)

        info.status = BatteryInfo.STATUS_UNPLUGGED
        info.plugged = BatteryInfo.PLUGGED_UNPLUGGED
        info.percent = 100

        var now = 0L

        minutesByLevel.forEach { minutesAtLevel ->
            (0..<minutesAtLevel).forEach { _ ->
                now += ONE_MINUTE
                predictor.update(info, now)
            }

            info.percent -= 1
        }

        now += ONE_MINUTE
        predictor.update(info, now)

        return now
    }
}
