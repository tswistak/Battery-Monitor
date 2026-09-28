/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.current

import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringAvailability
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringSnapshot
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringUiState
import codes.swistak.batterymonitor.monitoring.presentation.PredictionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentStateModelTest {
    private fun prediction(direction: Int, target: Int, reached: Boolean = false) =
        PredictionSnapshot(
            direction, target, reached, 0L, 0, 2, 20
        )

    private fun snapshot(
        status: Int = BatteryInfo.STATUS_DISCHARGING,
        level: Int = 72,
        configured: PredictionSnapshot = prediction(BatteryInfo.Prediction.UNTIL_DRAINED, 20),
        fullRange: PredictionSnapshot = prediction(BatteryInfo.Prediction.UNTIL_DRAINED, 0)
    ) = MonitoringSnapshot(
        level,
        status,
        BatteryInfo.HEALTH_GOOD,
        BatteryInfo.PLUGGED_UNPLUGGED,
        302,
        3880,
        1840000L,
        status,
        BatteryInfo.PLUGGED_UNPLUGGED,
        80,
        1000L,
        configured,
        fullRange,
        2000L
    )

    private fun live(snapshot: MonitoringSnapshot) = MonitoringUiState(
        MonitoringAvailability.LIVE, snapshot
    )

    @Test
    fun `current state distinguishes charging discharging full paused reached below target low and insufficient data`() {
        val cases = listOf(
            snapshot() to CurrentCondition.DISCHARGING,
            snapshot(
                status = BatteryInfo.STATUS_CHARGING,
                configured = prediction(BatteryInfo.Prediction.UNTIL_CHARGED, 80),
                fullRange = prediction(BatteryInfo.Prediction.UNTIL_CHARGED, 100)
            ) to CurrentCondition.CHARGING,
            snapshot(status = BatteryInfo.STATUS_FULLY_CHARGED) to CurrentCondition.FULL,
            snapshot(status = BatteryInfo.STATUS_NOT_CHARGING) to CurrentCondition.PAUSED,
            snapshot(
                status = BatteryInfo.STATUS_CHARGING,
                configured = prediction(BatteryInfo.Prediction.NONE, 80, true)
            ) to CurrentCondition.TARGET_REACHED,
            snapshot(
                level = 15, configured = prediction(BatteryInfo.Prediction.UNTIL_DRAINED, 20, true)
            ) to CurrentCondition.BELOW_TARGET,
            snapshot(level = 5) to CurrentCondition.LOW,
            snapshot(
                configured = prediction(
                    BatteryInfo.Prediction.NONE, 20
                )
            ) to CurrentCondition.INSUFFICIENT
        )
        cases.forEach { (reading, expected) ->
            assertEquals(expected, currentStateModel(live(reading)).condition)
        }
    }

    @Test
    fun `stale disabled and denied notifications remain explicit without discarding battery readings`() {
        assertEquals(CurrentCondition.WAITING, currentStateModel(MonitoringUiState()).condition)
        val reading = snapshot()
        val stale = currentStateModel(MonitoringUiState(MonitoringAvailability.STALE, reading))
        assertEquals(CurrentCondition.STALE, stale.condition)
        assertEquals(reading, stale.snapshot)
        assertEquals(
            CurrentCondition.DISABLED,
            currentStateModel(live(reading), monitoringEnabled = false).condition
        )
        val denied = currentStateModel(live(reading), notificationsEnabled = false)
        assertTrue(denied.notificationsDisabled)
        assertEquals(CurrentCondition.DISCHARGING, denied.condition)
    }

    @Test
    fun `unplugging at eighty percent replaces the charging target with the discharging target atomically`() {
        val charging = snapshot(
            status = BatteryInfo.STATUS_CHARGING,
            level = 80,
            configured = prediction(BatteryInfo.Prediction.NONE, 80, true),
            fullRange = prediction(BatteryInfo.Prediction.UNTIL_CHARGED, 100)
        )
        val unplugged = snapshot(
            status = BatteryInfo.STATUS_DISCHARGING,
            level = 80,
            configured = prediction(BatteryInfo.Prediction.UNTIL_DRAINED, 20),
            fullRange = prediction(BatteryInfo.Prediction.UNTIL_DRAINED, 0)
        )
        assertEquals(CurrentCondition.TARGET_REACHED, currentStateModel(live(charging)).condition)
        assertTrue(currentStateModel(live(charging)).hasAlternative)
        val next = currentStateModel(live(unplugged), showingFullRange = true)
        assertEquals(CurrentCondition.DISCHARGING, next.condition)
        assertEquals(BatteryInfo.Prediction.UNTIL_DRAINED, next.prediction?.direction)
        assertEquals(0, next.prediction?.targetPercent)
        assertFalse(
            currentStateModel(
                live(
                    snapshot(
                        configured = prediction(BatteryInfo.Prediction.NONE, 20)
                    )
                )
            ).hasAlternative
        )
    }
}
