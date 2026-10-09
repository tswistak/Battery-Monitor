/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.diagnostics

import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.monitoring.Predictor
import codes.swistak.batterymonitor.monitoring.PredictorStoredState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictorDiagnosticsModelTest {
    @Test
    fun `stored averages keep their categories and milliseconds per percentage point`() {
        val values = listOf(864000.5f, 108000f, 144000f, 216000f)
        val durations = mutableListOf<Long>()
        val group = predictorDiagnosticGroup(
            PredictorStoredState(Predictor.KEY_AVERAGE.zip(values).toMap(), 2), 42
        ) { milliseconds ->
            durations += milliseconds
            "duration=$milliseconds"
        }

        assertEquals(listOf(864000L, 108000L, 144000L, 216000L), durations)
        assertEquals(
            listOf(
                R.string.status_discharging,
                R.string.diag_predictor_ac,
                R.string.diag_predictor_wireless,
                R.string.diag_predictor_usb
            ), group.rows.take(4).map { it.label })
        assertEquals(values.map(Float::toString), group.rows.take(4).map { it.rawValue })
        assertTrue(group.rows.take(4).all { it.unit == "ms per percentage point" })
        assertTrue(group.rows.all { it.observedAtMillis == 42L })
        assertEquals("2", group.rows.last().rawValue)
    }

    @Test
    fun `missing saved values do not turn into predictor defaults or raw entries`() {
        val group = predictorDiagnosticGroup(PredictorStoredState(emptyMap(), null), 42) {
            error("Missing values must not be formatted as durations")
        }

        assertTrue(group.rows.all { it.valueLabel == R.string.diag_predictor_not_saved })
        assertTrue(group.rows.all { it.rawValue == null })
        assertTrue(rawPredictorDiagnosticGroup(group).rows.isEmpty())
    }

    @Test
    fun `sentinels and invalid saved averages remain inspectable without becoming zero durations`() {
        val values = listOf(-1f, 0f, Float.NaN, Float.MAX_VALUE)
        val group = predictorDiagnosticGroup(
            PredictorStoredState(Predictor.KEY_AVERAGE.zip(values).toMap(), 2), 42
        ) { error("Sentinels and invalid values must not be formatted as durations") }

        assertEquals(R.string.diag_predictor_default, group.rows.first().valueLabel)
        assertTrue(
            group.rows.drop(1).take(3).all { it.valueLabel == R.string.diag_predictor_invalid })
        val raw = rawPredictorDiagnosticGroup(group)
        assertEquals(values.map(Float::toString) + "2", raw.rows.map { it.value })
        assertTrue(raw.rows.all { it.label == 0 && it.valueLabel == 0 && it.unit.isEmpty() })
    }

    @Test
    fun `diagnostics report includes saved predictor data and keeps raw projection opt in`() {
        val group = predictorDiagnosticGroup(
            PredictorStoredState(mapOf(Predictor.KEY_AVERAGE[0] to 864000f), 2), 42
        ) { "14m 24s" }
        val groups = List(3) {
            DiagnosticGroup(
                R.string.advanced_section_counters, emptyList()
            )
        } + group + rawPredictorDiagnosticGroup(group)

        val summary = diagnosticReport(groups, Int::toString, false)
        assertTrue(summary.contains("key_ave_discharge: 14m 24s"))
        assertTrue(summary.contains("unit=ms per percentage point"))
        assertFalse(summary.contains("key_ave_discharge: 864000.0"))
        assertTrue(
            diagnosticReport(groups, Int::toString, true).contains("key_ave_discharge: 864000.0")
        )
    }
}
