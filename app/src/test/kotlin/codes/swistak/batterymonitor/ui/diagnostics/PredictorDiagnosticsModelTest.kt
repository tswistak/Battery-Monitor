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
import codes.swistak.batterymonitor.monitoring.PredictorCore
import codes.swistak.batterymonitor.monitoring.PredictorRecentState
import codes.swistak.batterymonitor.monitoring.PredictorStoredState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictorDiagnosticsModelTest {
    private fun recentState() = PredictorRecentState(
        predictionType = PredictorCore.AUTOMAGIC,
        chargingStatus = PredictorCore.DISCHARGE,
        effectiveMsPerPoint = 120000.5,
        fiveMinuteMsPerPoint = 100000.25,
        fivePointMsPerPoint = 200000.75,
        sessionMsPerPoint = 120000.5,
        longTermMsPerPoint = 864000.0,
        observedPoints = 2,
        observationDurationMillis = 240001,
        currentLevelDurationMillis = 30000,
        usesPartial = true
    )

    @Test
    fun `recent inputs retain original precision and format rates and elapsed durations independently`() {
        val durations = mutableListOf<Long>()
        val group = recentPredictorDiagnosticGroup(recentState(), 42) {
            durations += it
            "duration=$it"
        }
        val rows = group.rows.associateBy { it.rawKey }

        assertEquals(R.string.diag_predictor_recent, group.title)
        assertEquals("120000.5", rows.getValue("effectiveMsPerPoint").rawValue)
        assertEquals("duration=120000", rows.getValue("effectiveMsPerPoint").value)
        assertEquals(
            listOf(120000L, 100000L, 200000L, 120000L, 864000L, 240001L, 30000L), durations
        )
        assertEquals("ms per percentage point", rows.getValue("fiveMinuteMsPerPoint").unit)
        assertEquals("ms", rows.getValue("observationDurationMillis").unit)
        assertEquals("2", rows.getValue("observedPoints").rawValue)
        assertEquals(R.string.yes, rows.getValue("usesPartial").valueLabel)
        assertTrue(group.rows.all { it.source == "PredictorCore" && it.observedAtMillis == 42L })

        val raw = rawPredictorDiagnosticGroup(group)
        assertEquals(group.rows.map { it.rawValue }, raw.rows.map { it.value })
        assertTrue(raw.rows.all { it.label == 0 && it.valueLabel == 0 && it.unit.isEmpty() })
        assertEquals("true", raw.rows.single { it.rawKey == "usesPartial" }.value)
    }

    @Test
    fun `recent inputs label the actual method and active charging category`() {
        listOf(
            PredictorCore.AUTOMAGIC to R.string.predictor_conservative,
            PredictorCore.FIVE_MINUTES to R.string.predictor_sensitive,
            PredictorCore.SINCE_STATUS_CHANGE to R.string.predictor_since_status_change,
            PredictorCore.LONG_TERM to R.string.predictor_long_term_ave
        ).forEach { (type, label) ->
            val group = recentPredictorDiagnosticGroup(
                recentState().copy(predictionType = type), 42, Long::toString
            )
            assertEquals(label, group.rows.single { it.rawKey == "predictionType" }.valueLabel)
            assertEquals(
                type.toString(), group.rows.single { it.rawKey == "predictionType" }.rawValue
            )
        }
        listOf(
            PredictorCore.DISCHARGE to R.string.status_discharging,
            PredictorCore.RECHARGE_AC to R.string.diag_predictor_ac,
            PredictorCore.RECHARGE_WL to R.string.diag_predictor_wireless,
            PredictorCore.RECHARGE_USB to R.string.diag_predictor_usb
        ).forEach { (state, label) ->
            val group = recentPredictorDiagnosticGroup(
                recentState().copy(chargingStatus = state), 42, Long::toString
            )
            assertEquals(label, group.rows.single { it.rawKey == "chargingStatus" }.valueLabel)
        }
    }

    @Test
    fun `invalid recent rates remain inspectable in raw without becoming valid durations`() {
        val group = recentPredictorDiagnosticGroup(
            recentState().copy(
                effectiveMsPerPoint = Double.NaN,
                fiveMinuteMsPerPoint = Double.POSITIVE_INFINITY,
                fivePointMsPerPoint = 0.0,
                sessionMsPerPoint = -1.0,
                longTermMsPerPoint = Double.MAX_VALUE
            ), 42
        ) { milliseconds ->
            assertTrue(milliseconds in listOf(240001L, 30000L))
            milliseconds.toString()
        }
        assertTrue(
            group.rows.subList(2, 7).all { it.valueLabel == R.string.diag_value_not_available })
        assertEquals(
            listOf("NaN", "Infinity", "0.0", "-1.0", Double.MAX_VALUE.toString()),
            rawPredictorDiagnosticGroup(group).rows.subList(2, 7).map { it.value })
    }

    @Test
    fun `one predictor group keeps every original key and handles unavailable parts independently`() {
        val stored = predictorDiagnosticGroup(
            PredictorStoredState(mapOf(Predictor.KEY_AVERAGE[0] to 864000f), 2), 42, Long::toString
        )
        val recent = recentPredictorDiagnosticGroup(recentState(), 42, Long::toString)
        val combined = requireNotNull(combinedPredictorDiagnosticGroup(stored, recent))

        assertEquals(R.string.device_data_predictor, combined.title)
        assertEquals(stored.rows + recent.rows, combined.rows)
        assertEquals(combined.rows.size, combined.rows.map { it.rawKey }.distinct().size)
        assertEquals(
            "864000.0", combined.rows.single { it.rawKey == "longTermMsPerPoint" }.rawValue
        )
        assertEquals(stored.rows, combinedPredictorDiagnosticGroup(stored, null)?.rows)
        assertEquals(recent.rows, combinedPredictorDiagnosticGroup(null, recent)?.rows)
        assertEquals(null, combinedPredictorDiagnosticGroup(null, null))
    }

    @Test
    fun `normal report includes recent predictor labels and requires raw opt in for raw rows at any position`() {
        val recent = recentPredictorDiagnosticGroup(recentState(), 42) { "duration=$it" }
        val stored = predictorDiagnosticGroup(
            PredictorStoredState(mapOf(Predictor.KEY_AVERAGE[0] to 864000f), 2), 42
        ) { "stored=$it" }
        val combined = requireNotNull(combinedPredictorDiagnosticGroup(stored, recent))
        val raw = rawPredictorDiagnosticGroup(combined).let {
            it.copy(
                rows = it.rows + it.rows.first()
                    .copy(rawKey = "rawOnlyExtra", value = "123", rawValue = "123")
            )
        }
        val normalized = List(3) { DiagnosticGroup(R.string.diag_section_counters, emptyList()) }
        val groups = listOf(raw) + normalized + combined
        val labels = { id: Int ->
            when (id) {
                R.string.predictor_conservative -> "Conservative"
                R.string.status_discharging -> "Discharging"
                R.string.yes -> "Yes"
                R.string.device_data_predictor -> "Predictor data"
                else -> id.toString()
            }
        }

        val summary = diagnosticReport(groups, labels, false)
        assertTrue(summary.contains("key_ave_discharge: stored=864000"))
        assertTrue(summary.contains("predictionType: Conservative"))
        assertTrue(summary.contains("chargingStatus: Discharging"))
        assertTrue(summary.contains("usesPartial: Yes"))
        assertTrue(summary.contains("effectiveMsPerPoint: duration=120000"))
        assertFalse(summary.contains("rawOnlyExtra"))
        assertFalse(summary.contains("usesPartial: true"))
        val formatted = diagnosticReport(normalized + combined, labels, false)
        assertEquals(1, formatted.lineSequence().count { it == "Predictor data" })
        val full = diagnosticReport(groups, labels, true)
        assertTrue(full.contains("rawOnlyExtra: 123"))
        assertTrue(full.contains("usesPartial: true"))
    }

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
                R.string.diag_section_counters, emptyList()
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
