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
    @Test
    fun `paused charging target is independent of a cleared ETA and survives resuming`() {
        val reached = prediction(BatteryInfo.Prediction.NONE, 80, true).copy(hours = 0, minutes = 0)
        val cleared = prediction(BatteryInfo.Prediction.NONE, 0)
        val paused = snapshot(BatteryInfo.STATUS_NOT_CHARGING, 88, reached, cleared).copy(
            plugged = BatteryInfo.PLUGGED_USB, effectivePredictionTargetPercent = 80
        )
        val configured = currentStateModel(live(paused))
        assertTrue(configured.hasAlternative)
        assertEquals(80, configured.targetPercent)
        assertEquals(100, configured.alternativeTargetPercent)
        val full = currentStateModel(live(paused), showingFullRange = true)
        assertEquals(100, full.targetPercent)
        assertEquals(80, full.alternativeTargetPercent)
        assertEquals(BatteryInfo.Prediction.NONE, full.prediction?.direction)
        assertFalse(full.prediction!!.targetReached)
        assertEquals(CurrentCondition.PAUSED, full.condition)
        val resumed = paused.copy(
            status = BatteryInfo.STATUS_CHARGING,
            fullRangePrediction = prediction(BatteryInfo.Prediction.UNTIL_CHARGED, 100)
        )
        assertEquals(paused.predictionSelectionContext(), resumed.predictionSelectionContext())
        assertTrue(currentStateModel(live(resumed), true).showingFullRange)
        for (level in listOf(70, 80, 88)) {
            val reading = resumed.copy(levelPercent = level)
            assertTrue(currentStateModel(live(reading)).hasAlternative)
            assertEquals(100, currentStateModel(live(reading), true).targetPercent)
        }
        val noEta = paused.copy(configuredPrediction = cleared)
        assertEquals(80, currentStateModel(live(noEta)).targetPercent)
        assertTrue(currentStateModel(live(noEta)).hasAlternative)
        val missingTarget = noEta.copy(effectivePredictionTargetPercent = 0)
        assertEquals(null, currentStateModel(live(missingTarget)).targetPercent)
        assertFalse(currentStateModel(live(missingTarget)).hasAlternative)
        assertFalse(currentStateModel(live(paused.copy(effectivePredictionTargetPercent = 100))).hasAlternative)
        assertEquals(null, currentStateModel(MonitoringUiState()).targetPercent)
        val stale = currentStateModel(MonitoringUiState(MonitoringAvailability.STALE, paused), true)
        assertEquals(CurrentCondition.STALE, stale.condition)
        assertEquals(100, stale.targetPercent)
        for (plug in listOf(BatteryInfo.PLUGGED_USB, BatteryInfo.PLUGGED_UNPLUGGED)) {
            val discharge = snapshot().copy(plugged = plug)
            assertEquals(false to 20, discharge.predictionSelectionContext())
            assertEquals(0, currentStateModel(live(discharge), true).targetPercent)
            assertEquals(
                BatteryInfo.Prediction.UNTIL_DRAINED,
                currentStateModel(live(discharge), true).prediction?.direction
            )
            assertFalse(paused.predictionSelectionContext() == discharge.predictionSelectionContext())
        }
    }

    @Test
    fun `measurement details use each actual source and both power inputs`() {
        val reading = snapshot().copy(
            fieldSources = mapOf(
                "temperature" to "ACTION_BATTERY_CHANGED / temperature",
                "voltage" to "/sys/class/power_supply/bms/voltage_now",
                "health" to "dumpsys battery / health",
                "charge_counter" to "BatteryManager / BATTERY_PROPERTY_CHARGE_COUNTER"
            )
        )
        val current = "BatteryManager / BATTERY_PROPERTY_CURRENT_AVERAGE"
        val expected = mapOf(
            CurrentMeasurement.TEMPERATURE to reading.fieldSources["temperature"],
            CurrentMeasurement.VOLTAGE to reading.fieldSources["voltage"],
            CurrentMeasurement.CURRENT to current,
            CurrentMeasurement.POWER to "/sys/class/power_supply/bms/voltage_now × $current",
            CurrentMeasurement.ANDROID_HEALTH to reading.fieldSources["health"],
            CurrentMeasurement.REMAINING_CHARGE to reading.fieldSources["charge_counter"]
        )
        for ((measurement, source) in expected) {
            assertEquals(source, currentMeasurementSource(measurement, reading, current))
        }
        for (measurement in CurrentMeasurement.entries) {
            assertEquals(null, currentMeasurementSource(measurement, snapshot(), null))
            assertEquals(null, currentMeasurementSource(measurement, null, null))
        }
        assertEquals(null, currentMeasurementSource(CurrentMeasurement.POWER, reading, null))
        assertEquals(null, currentMeasurementSource(CurrentMeasurement.POWER, snapshot(), current))
    }

    private fun prediction(direction: Int, target: Int, reached: Boolean = false) =
        PredictionSnapshot(
            direction, target, reached, 0, 2, 20
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
    fun `discharge exactly at twenty percent reaches the target with an explicit full range alternative`() {
        val reading = snapshot(
            level = 20,
            configured = prediction(BatteryInfo.Prediction.UNTIL_DRAINED, 20, true),
            fullRange = prediction(BatteryInfo.Prediction.UNTIL_DRAINED, 0)
        )

        val configured = currentStateModel(live(reading))
        assertEquals(CurrentCondition.TARGET_REACHED, configured.condition)
        assertEquals(20, configured.prediction?.targetPercent)
        assertTrue(configured.prediction?.targetReached == true)
        assertTrue(configured.hasAlternative)

        val alternative = currentStateModel(live(reading), showingFullRange = true)
        assertEquals(CurrentCondition.TARGET_REACHED, alternative.condition)
        assertEquals(20, alternative.snapshot?.configuredPrediction?.targetPercent)
        assertEquals(0, alternative.prediction?.targetPercent)
        assertFalse(alternative.prediction?.targetReached == true)
    }

    @Test
    fun `discharge below twenty percent resumes the full range estimate without a reached target label`() {
        val reading = snapshot(
            level = 19,
            configured = prediction(BatteryInfo.Prediction.UNTIL_DRAINED, 0),
            fullRange = prediction(BatteryInfo.Prediction.UNTIL_DRAINED, 0)
        )

        val model = currentStateModel(live(reading), showingFullRange = true)
        assertEquals(CurrentCondition.DISCHARGING, model.condition)
        assertEquals(0, model.prediction?.targetPercent)
        assertFalse(model.prediction?.targetReached == true)
        assertFalse(model.hasAlternative)
        assertFalse(model.showingFullRange)
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
