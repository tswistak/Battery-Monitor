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

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictorRecentStateTest {
    private val minute = 60_000L

    private fun predictor() = PredictorCore(
        minute.toFloat(), (2 * minute).toFloat(), (3 * minute).toFloat(), (4 * minute).toFloat()
    )

    private fun battery(
        percent: Int,
        status: Int = BatteryInfo.STATUS_UNPLUGGED,
        plugged: Int = BatteryInfo.PLUGGED_UNPLUGGED
    ) = BatteryInfo().apply {
        this.percent = percent
        this.status = status
        this.plugged = plugged
    }

    @Test
    fun `recent state is absent until a predictable battery reading arrives`() {
        val predictor = predictor()
        assertNull(predictor.recentState())

        predictor.update(battery(80, BatteryInfo.STATUS_UNKNOWN), minute)
        assertNull(predictor.recentState())

        predictor.update(battery(80), 2 * minute)
        assertNotNull(predictor.recentState())
    }

    @Test
    fun `startup rates use the active long term average without inventing observations`() {
        val predictor = predictor()
        predictor.update(battery(80), minute)

        val state = requireNotNull(predictor.recentState())
        assertEquals(PredictorCore.AUTOMAGIC, state.predictionType)
        assertEquals(PredictorCore.DISCHARGE, state.chargingStatus)
        listOf(
            state.effectiveMsPerPoint,
            state.fiveMinuteMsPerPoint,
            state.fivePointMsPerPoint,
            state.sessionMsPerPoint,
            state.longTermMsPerPoint
        ).forEach { assertEquals(minute.toDouble(), it, 0.0) }
        assertEquals(0, state.observedPoints)
        assertEquals(0L, state.observationDurationMillis)
        assertEquals(0L, state.currentLevelDurationMillis)
        assertFalse(state.usesPartial)
    }

    @Test
    fun `five minute discharge rate is the same rate used by the visible prediction`() {
        val predictor = predictor()
        predictor.setPredictionType(PredictorCore.FIVE_MINUTES)
        val info = battery(80)
        predictor.update(info, 0)
        (79 downTo 77).forEachIndexed { index, percent ->
            info.percent = percent
            predictor.update(info, (index + 1) * 2 * minute)
        }

        val state = requireNotNull(predictor.recentState())
        assertEquals(2 * minute.toDouble(), state.fiveMinuteMsPerPoint, 0.01)
        assertEquals(state.fiveMinuteMsPerPoint, state.effectiveMsPerPoint, 0.0)
        assertEquals(3, state.observedPoints)
        assertEquals(6 * minute, state.observationDurationMillis)
        assertEquals(
            6 * minute + (info.percent * state.effectiveMsPerPoint).toLong(),
            info.prediction.whenHappened
        )
    }

    @Test
    fun `charging session rate uses observed increases instead of falling back to long term`() {
        val predictor = predictor()
        predictor.setPredictionType(PredictorCore.SINCE_STATUS_CHANGE)
        predictor.setTargets(chargingTargetPercent = 85, dischargingTargetPercent = 0)
        val info = battery(20, BatteryInfo.STATUS_CHARGING, BatteryInfo.PLUGGED_USB)
        predictor.update(info, 0)
        (21..22).forEachIndexed { index, percent ->
            info.percent = percent
            predictor.update(info, (index + 1) * 5 * minute)
        }

        val state = requireNotNull(predictor.recentState())
        assertEquals(PredictorCore.RECHARGE_USB, state.chargingStatus)
        assertEquals(2, state.observedPoints)
        assertEquals(10 * minute, state.observationDurationMillis)
        assertEquals(5 * minute.toDouble(), state.sessionMsPerPoint, 0.0)
        assertEquals(state.sessionMsPerPoint, state.effectiveMsPerPoint, 0.0)
        assertTrue(state.sessionMsPerPoint > state.longTermMsPerPoint)
        assertEquals(
            10 * minute + ((85 - info.percent) * state.effectiveMsPerPoint).toLong(),
            info.prediction.whenHappened
        )
    }

    @Test
    fun `conservative rate is the median of the exact recent and long term rates`() {
        val predictor = predictor()
        val info = battery(80)
        predictor.update(info, 0)
        listOf(2L, 5L, 9L).forEachIndexed { index, minutes ->
            info.percent = 79 - index
            predictor.update(info, minutes * minute)
        }

        val state = requireNotNull(predictor.recentState())
        assertTrue(state.longTermMsPerPoint < state.fivePointMsPerPoint)
        assertTrue(state.fivePointMsPerPoint < state.fiveMinuteMsPerPoint)
        assertEquals(state.fivePointMsPerPoint, state.effectiveMsPerPoint, 0.0)
        assertEquals(
            9 * minute + (info.percent * state.effectiveMsPerPoint).toLong(),
            info.prediction.whenHappened
        )
    }

    @Test
    fun `reading diagnostic state leaves predictions and future partial intervals unchanged`() {
        val observed = predictor()
        val control = predictor()
        val observedInfo = battery(80)
        val controlInfo = battery(80)
        listOf(80 to 0L, 79 to 2L, 79 to 7L, 78 to 8L).forEach { (percent, minutes) ->
            observedInfo.percent = percent
            controlInfo.percent = percent
            observed.update(observedInfo, minutes * minute)
            control.update(controlInfo, minutes * minute)
            val first = observed.recentState()
            repeat(3) { assertEquals(first, observed.recentState()) }
            assertEquals(controlInfo.prediction.whenHappened, observedInfo.prediction.whenHappened)
            assertEquals(
                controlInfo.fullRangePrediction.whenHappened,
                observedInfo.fullRangePrediction.whenHappened
            )
            assertEquals(control.longTermAverage, observed.longTermAverage, 0.0)
            assertEquals(control.recentState(), first)
            if (percent == 79 && minutes == 7L) {
                assertEquals(5 * minute, requireNotNull(first).currentLevelDurationMillis)
                assertTrue(first.usesPartial)
            }
        }
    }

    @Test
    fun `changing estimate mode exposes the new selection without losing session history`() {
        val predictor = predictor()
        val info = battery(80)
        predictor.update(info, 0)
        info.percent = 79
        predictor.update(info, 2 * minute)
        val before = requireNotNull(predictor.recentState())

        predictor.setPredictionType(PredictorCore.LONG_TERM)
        val after = requireNotNull(predictor.recentState())
        assertEquals(PredictorCore.LONG_TERM, after.predictionType)
        assertEquals(after.longTermMsPerPoint, after.effectiveMsPerPoint, 0.0)
        assertEquals(before.observedPoints, after.observedPoints)
        assertEquals(before.observationDurationMillis, after.observationDurationMillis)
    }

    @Test
    fun `changing charging source or direction resets recent observations to that source`() {
        val predictor = predictor()
        val info = battery(20, BatteryInfo.STATUS_CHARGING, BatteryInfo.PLUGGED_USB)
        predictor.update(info, 0)
        info.percent = 21
        predictor.update(info, 5 * minute)
        assertEquals(1, requireNotNull(predictor.recentState()).observedPoints)

        info.plugged = BatteryManager.BATTERY_PLUGGED_AC
        predictor.update(info, 6 * minute)
        val ac = requireNotNull(predictor.recentState())
        assertEquals(PredictorCore.RECHARGE_AC, ac.chargingStatus)
        assertEquals(0, ac.observedPoints)
        assertEquals(0L, ac.observationDurationMillis)
        assertEquals(2 * minute.toDouble(), ac.effectiveMsPerPoint, 0.0)

        info.status = BatteryInfo.STATUS_UNPLUGGED
        info.plugged = BatteryInfo.PLUGGED_UNPLUGGED
        predictor.update(info, 7 * minute)
        val discharge = requireNotNull(predictor.recentState())
        assertEquals(PredictorCore.DISCHARGE, discharge.chargingStatus)
        assertEquals(0, discharge.observedPoints)
        assertEquals(0L, discharge.observationDurationMillis)
        assertEquals(minute.toDouble(), discharge.effectiveMsPerPoint, 0.0)
    }

    @Test
    fun `unknown full and not charging updates do not expose a previous live prediction`() {
        listOf(
            BatteryInfo.STATUS_UNKNOWN,
            BatteryInfo.STATUS_FULLY_CHARGED,
            BatteryInfo.STATUS_NOT_CHARGING
        ).forEach { status ->
            val predictor = predictor()
            predictor.update(battery(80), 0)
            assertNotNull(predictor.recentState())

            predictor.update(battery(80, status, BatteryInfo.PLUGGED_USB), minute)
            assertNull(predictor.recentState())
        }
    }
}
