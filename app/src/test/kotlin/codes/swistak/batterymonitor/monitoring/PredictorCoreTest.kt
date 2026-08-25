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
    fun `discharging at target shows zero time until target`() {
        predictor.setTargets(chargingTargetPercent = 100, dischargingTargetPercent = 20)
        info.status = BatteryInfo.STATUS_UNPLUGGED
        info.plugged = BatteryInfo.PLUGGED_UNPLUGGED
        info.percent = 20

        predictor.update(info, 0L)
        info.prediction.updateRelativeTime()

        Assert.assertEquals(BatteryInfo.Prediction.UNTIL_DRAINED, info.prediction.whatHappened)
        Assert.assertEquals(20, info.prediction.targetPercent)
        Assert.assertTrue(info.prediction.targetReached)
        Assert.assertEquals(0, info.prediction.lastRTime.minutes)
    }

    @Test
    fun `discharging below target switches to fully drained estimate`() {
        predictor.setTargets(chargingTargetPercent = 100, dischargingTargetPercent = 20)
        info.status = BatteryInfo.STATUS_UNPLUGGED
        info.plugged = BatteryInfo.PLUGGED_UNPLUGGED
        info.percent = 19

        predictor.update(info, 0L)

        Assert.assertEquals(BatteryInfo.Prediction.UNTIL_DRAINED, info.prediction.whatHappened)
        Assert.assertEquals(0, info.prediction.targetPercent)
        Assert.assertFalse(info.prediction.targetReached)
        Assert.assertEquals(19L * DISCHARGE_MS_PER_PERCENT, info.prediction.whenHappened)
    }

    @Test
    fun `crossing discharging target preserves predictor state`() {
        predictor.setTargets(chargingTargetPercent = 100, dischargingTargetPercent = 20)
        setDischargingLevel(percent = 22, whenUpdated = 0L)
        setDischargingLevel(percent = 21, whenUpdated = 30 * ONE_MINUTE)
        setDischargingLevel(percent = 20, whenUpdated = 60 * ONE_MINUTE)
        info.prediction.updateRelativeTime()

        Assert.assertEquals(20, info.prediction.targetPercent)
        Assert.assertTrue(info.prediction.targetReached)
        Assert.assertEquals(0, info.prediction.lastRTime.minutes)

        val averageAtTarget = predictor.longTermAverage
        val now = 90 * ONE_MINUTE
        setDischargingLevel(percent = 19, whenUpdated = now)

        Assert.assertEquals(0, info.prediction.targetPercent)
        Assert.assertFalse(info.prediction.targetReached)
        Assert.assertTrue(info.prediction.whenHappened > now)
        Assert.assertTrue(predictor.longTermAverage > averageAtTarget)
        Assert.assertTrue(predictor.longTermAverage < 2 * ONE_MINUTE)
    }

    @Test
    fun `refreshing at discharging target does not move its timestamp`() {
        predictor.setTargets(chargingTargetPercent = 100, dischargingTargetPercent = 20)
        setDischargingLevel(percent = 21, whenUpdated = 30 * ONE_MINUTE)
        setDischargingLevel(percent = 20, whenUpdated = 60 * ONE_MINUTE)
        setDischargingLevel(percent = 20, whenUpdated = 65 * ONE_MINUTE)
        setDischargingLevel(percent = 20, whenUpdated = 70 * ONE_MINUTE)
        setDischargingLevel(percent = 20, whenUpdated = 80 * ONE_MINUTE)
        setDischargingLevel(percent = 19, whenUpdated = 90 * ONE_MINUTE)

        val expectedAverageAfterTwoThirtyMinuteSamples =
            ((DISCHARGE_MS_PER_PERCENT * 0.998 + 30 * ONE_MINUTE * 0.002) * 0.998) + (30 * ONE_MINUTE * 0.002)
        Assert.assertEquals(
            expectedAverageAfterTwoThirtyMinuteSamples, predictor.longTermAverage, 0.01
        )
    }

    @Test
    fun `custom target estimate is shorter than full range estimate`() {
        predictor.setTargets(chargingTargetPercent = 100, dischargingTargetPercent = 20)
        setDischargingLevel(percent = 79, whenUpdated = 0L)
        val customTargetEta = info.prediction.whenHappened
        val fullRangeEta = info.fullRangePrediction.whenHappened

        Assert.assertTrue(customTargetEta < fullRangeEta)
        Assert.assertEquals(59L * DISCHARGE_MS_PER_PERCENT, customTargetEta)
        Assert.assertEquals(79L * DISCHARGE_MS_PER_PERCENT, fullRangeEta)
    }

    @Test
    fun `charging target then direct unplug keeps both discharge projections on one rate`() {
        replayChargingLimitScenario(includeNotChargingState = false)
    }

    @Test
    fun `OEM charging limit then unplug keeps both discharge projections on one rate`() {
        replayChargingLimitScenario(includeNotChargingState = true)
    }

    @Test
    fun `skipping over discharging target still switches to full range safely`() {
        predictor.setTargets(chargingTargetPercent = 100, dischargingTargetPercent = 20)
        setDischargingLevel(percent = 21, whenUpdated = 0L)
        val now = 60 * ONE_MINUTE
        setDischargingLevel(percent = 19, whenUpdated = now)

        Assert.assertEquals(0, info.prediction.targetPercent)
        Assert.assertFalse(info.prediction.targetReached)
        Assert.assertTrue(info.prediction.whenHappened > now)
        Assert.assertTrue(predictor.longTermAverage < 2 * ONE_MINUTE)
    }

    @Test
    fun `starting at target and refreshing preserves the next discharge sample`() {
        predictor.setTargets(chargingTargetPercent = 100, dischargingTargetPercent = 20)
        setDischargingLevel(percent = 20, whenUpdated = 0L)
        setDischargingLevel(percent = 20, whenUpdated = 10 * ONE_MINUTE)
        setDischargingLevel(percent = 20, whenUpdated = 20 * ONE_MINUTE)
        setDischargingLevel(percent = 19, whenUpdated = 30 * ONE_MINUTE)

        Assert.assertEquals(0, info.prediction.targetPercent)
        Assert.assertFalse(info.prediction.targetReached)
        Assert.assertEquals(
            DISCHARGE_MS_PER_PERCENT * 0.998 + 30 * ONE_MINUTE * 0.002,
            predictor.longTermAverage,
            0.01
        )
    }

    @Test
    fun `starting below discharging target continues full range measurements`() {
        predictor.setTargets(chargingTargetPercent = 100, dischargingTargetPercent = 20)
        setDischargingLevel(percent = 19, whenUpdated = 0L)
        val now = 30 * ONE_MINUTE
        setDischargingLevel(percent = 18, whenUpdated = now)

        Assert.assertEquals(0, info.prediction.targetPercent)
        Assert.assertFalse(info.prediction.targetReached)
        Assert.assertTrue(info.prediction.whenHappened > now)
        Assert.assertEquals(
            DISCHARGE_MS_PER_PERCENT * 0.998 + 30 * ONE_MINUTE * 0.002,
            predictor.longTermAverage,
            0.01
        )
    }

    @Test
    fun `changing discharging target updates projection without corrupting average`() {
        predictor.setTargets(chargingTargetPercent = 100, dischargingTargetPercent = 20)
        setDischargingLevel(percent = 40, whenUpdated = 0L)
        setDischargingLevel(percent = 39, whenUpdated = 30 * ONE_MINUTE)
        val averageBeforeTargetChange = predictor.longTermAverage

        predictor.setTargets(chargingTargetPercent = 100, dischargingTargetPercent = 0)
        setDischargingLevel(percent = 38, whenUpdated = 60 * ONE_MINUTE)
        Assert.assertEquals(0, info.prediction.targetPercent)
        Assert.assertTrue(predictor.longTermAverage > averageBeforeTargetChange)
        val averageAfterZeroTarget = predictor.longTermAverage

        predictor.setTargets(chargingTargetPercent = 100, dischargingTargetPercent = 30)
        setDischargingLevel(percent = 37, whenUpdated = 90 * ONE_MINUTE)
        Assert.assertEquals(30, info.prediction.targetPercent)
        Assert.assertTrue(predictor.longTermAverage > averageAfterZeroTarget)
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

    private fun setDischargingLevel(percent: Int, whenUpdated: Long) {
        info.status = BatteryInfo.STATUS_UNPLUGGED
        info.plugged = BatteryInfo.PLUGGED_UNPLUGGED
        info.percent = percent
        predictor.update(info, whenUpdated)
    }

    private fun replayChargingLimitScenario(includeNotChargingState: Boolean) {
        predictor.setTargets(chargingTargetPercent = 80, dischargingTargetPercent = 20)

        setBatteryState(50, BatteryInfo.STATUS_UNPLUGGED, BatteryInfo.PLUGGED_UNPLUGGED, 0L)
        setBatteryState(
            49, BatteryInfo.STATUS_UNPLUGGED, BatteryInfo.PLUGGED_UNPLUGGED, 30 * ONE_MINUTE
        )
        setBatteryState(
            48, BatteryInfo.STATUS_UNPLUGGED, BatteryInfo.PLUGGED_UNPLUGGED, 60 * ONE_MINUTE
        )

        setBatteryState(48, BatteryInfo.STATUS_CHARGING, BatteryInfo.PLUGGED_USB, 62 * ONE_MINUTE)
        setBatteryState(60, BatteryInfo.STATUS_CHARGING, BatteryInfo.PLUGGED_USB, 86 * ONE_MINUTE)
        setBatteryState(79, BatteryInfo.STATUS_CHARGING, BatteryInfo.PLUGGED_USB, 124 * ONE_MINUTE)
        setBatteryState(80, BatteryInfo.STATUS_CHARGING, BatteryInfo.PLUGGED_USB, 126 * ONE_MINUTE)
        setBatteryState(80, BatteryInfo.STATUS_CHARGING, BatteryInfo.PLUGGED_USB, 128 * ONE_MINUTE)

        Assert.assertEquals(80, info.prediction.targetPercent)
        Assert.assertTrue(info.prediction.targetReached)

        if (includeNotChargingState) {
            setBatteryState(
                80, BatteryInfo.STATUS_NOT_CHARGING, BatteryInfo.PLUGGED_USB, 130 * ONE_MINUTE
            )
            setBatteryState(
                80, BatteryInfo.STATUS_NOT_CHARGING, BatteryInfo.PLUGGED_USB, 132 * ONE_MINUTE
            )
            setBatteryState(
                80, BatteryInfo.STATUS_NOT_CHARGING, BatteryInfo.PLUGGED_USB, 134 * ONE_MINUTE
            )
            Assert.assertEquals(BatteryInfo.Prediction.NONE, info.fullRangePrediction.whatHappened)
            Assert.assertTrue(info.prediction.targetReached)
        }

        val unpluggedAt = if (includeNotChargingState) 136L else 130L
        setBatteryState(
            80,
            BatteryInfo.STATUS_UNPLUGGED,
            BatteryInfo.PLUGGED_UNPLUGGED,
            unpluggedAt * ONE_MINUTE
        )
        setBatteryState(
            80,
            BatteryInfo.STATUS_UNPLUGGED,
            BatteryInfo.PLUGGED_UNPLUGGED,
            (unpluggedAt + 2) * ONE_MINUTE
        )
        setBatteryState(
            80,
            BatteryInfo.STATUS_UNPLUGGED,
            BatteryInfo.PLUGGED_UNPLUGGED,
            (unpluggedAt + 4) * ONE_MINUTE
        )

        (79 downTo 77).forEachIndexed { index, percent ->
            val whenUpdated = (unpluggedAt + 30 + index * 30) * ONE_MINUTE
            setBatteryState(
                percent, BatteryInfo.STATUS_UNPLUGGED, BatteryInfo.PLUGGED_UNPLUGGED, whenUpdated
            )
            assertDischargeProjectionsShareRate(whenUpdated)
        }
    }

    private fun setBatteryState(percent: Int, status: Int, plugged: Int, whenUpdated: Long) {
        info.percent = percent
        info.status = status
        info.plugged = plugged
        predictor.update(info, whenUpdated)
    }

    private fun assertDischargeProjectionsShareRate(whenUpdated: Long) {
        Assert.assertEquals(20, info.prediction.targetPercent)
        Assert.assertFalse(info.prediction.targetReached)
        Assert.assertEquals(0, info.fullRangePrediction.targetPercent)
        Assert.assertFalse(info.fullRangePrediction.targetReached)

        val customDuration = info.prediction.whenHappened - whenUpdated
        val fullRangeDuration = info.fullRangePrediction.whenHappened - whenUpdated
        val customPointsRemaining = info.percent - info.prediction.targetPercent
        val fullRangePointsRemaining = info.percent

        Assert.assertTrue(customDuration < fullRangeDuration)
        Assert.assertEquals(
            customDuration / customPointsRemaining.toDouble(),
            fullRangeDuration / fullRangePointsRemaining.toDouble(),
            1.0
        )
    }
}
