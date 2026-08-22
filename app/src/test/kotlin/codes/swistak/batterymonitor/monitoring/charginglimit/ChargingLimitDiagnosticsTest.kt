/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring.charginglimit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingLimitDiagnosticsTest {
    private val redactionKey = ByteArray(32) { it.toByte() }

    @Test
    fun `numeric and known mode values remain useful`() {
        assertEquals("80", DiagnosticValueRedactor.sanitizeWithKey("80", redactionKey))
        assertEquals(
            "adaptive", DiagnosticValueRedactor.sanitizeWithKey("adaptive", redactionKey)
        )
    }

    @Test
    fun `unexpected strings get stable opaque tokens that preserve changes`() {
        val privateValue = "tomasz@example.com /Users/tomasz/private"
        val first = DiagnosticValueRedactor.sanitizeWithKey(privateValue, redactionKey)
        val second = DiagnosticValueRedactor.sanitizeWithKey(privateValue, redactionKey)
        val different = DiagnosticValueRedactor.sanitizeWithKey("different", redactionKey)

        assertEquals(first, second)
        assertFalse(first == different)
        assertFalse(first == "<redacted>")
        assertFalse(first.contains("tomasz"))
        assertFalse(first.contains("example.com"))
        assertFalse(first.contains("/Users"))
    }

    @Test
    fun `dynamic fixed conditions persist and validate percentages`() {
        val condition = ChargingDiagnosticCondition.Fixed(85)

        assertEquals("FIXED:85", condition.toStorageValue())
        assertEquals(condition, ChargingDiagnosticCondition.fromStorageValue("FIXED:85"))
        assertEquals(
            ChargingDiagnosticCondition.Fixed(70),
            ChargingDiagnosticCondition.fromStorageValue("FIXED_70")
        )
    }

    @Test
    fun `different redacted values remain visible as a candidate change`() {
        val banana = DiagnosticValueRedactor.sanitizeWithKey("banana", redactionKey)
        val pineapple = DiagnosticValueRedactor.sanitizeWithKey("pineapple", redactionKey)

        val section = ChargingDiagnosticReport.createCandidateSection(
            listOf(
                snapshot(ChargingDiagnosticCondition.Off, banana),
                snapshot(ChargingDiagnosticCondition.Fixed(80), pineapple)
            )
        )

        assertTrue(section.contains("settings/global/some_charge_mode"))
        assertTrue(section.contains("REDACTED_VALUE_CHANGED"))
        assertTrue(section.contains(banana))
        assertTrue(section.contains(pineapple))
    }

    @Test
    fun `candidate ranking separates configuration signals from volatile numbers`() {
        val section = ChargingDiagnosticReport.createCandidateSection(
            listOf(
                signalSnapshot(ChargingDiagnosticCondition.Fixed(80), "80", "1", "3", "2557059"),
                signalSnapshot(ChargingDiagnosticCondition.Fixed(85), "85", "1", "3", "2552497"),
                signalSnapshot(ChargingDiagnosticCondition.Off, "85", "0", "3", "2551461"),
                signalSnapshot(ChargingDiagnosticCondition.Adaptive, "85", "1", "1", "2550402")
            )
        )

        assertTrue(section.contains("charging_limit [MATCHES_TARGET_PERCENT]"))
        assertTrue(section.contains("charging_enabled [BOOLEAN_ENABLE_SIGNAL]"))
        assertTrue(section.contains("charging_mode [SMALL_ENUM_SIGNAL]"))
        assertTrue(section.contains("charge_now_raw [NUMERIC_VALUE_CHANGED]"))
        assertTrue(
            section.indexOf("charging_limit") < section.indexOf("Other changing values:")
        )
        assertTrue(
            section.indexOf("charge_now_raw") > section.indexOf("Other changing values:")
        )
    }

    private fun snapshot(
        condition: ChargingDiagnosticCondition, value: String
    ): ChargingDiagnosticSnapshot = ChargingDiagnosticSnapshot(
        condition = condition,
        capturedAtEpochMillis = 0,
        plugged = false,
        device = emptyMap(),
        discoveryAccess = emptyMap(),
        sectionStatus = emptyMap(),
        settings = mapOf("global/some_charge_mode" to "$value [APP_SHELL]"),
        properties = emptyMap(),
        systemPackages = emptyMap(),
        powerSupplyNodes = emptyMap(),
        batteryState = emptyMap(),
        adapterStates = emptyMap()
    )

    private fun signalSnapshot(
        condition: ChargingDiagnosticCondition,
        limit: String,
        enabled: String,
        mode: String,
        telemetry: String
    ): ChargingDiagnosticSnapshot = ChargingDiagnosticSnapshot(
        condition = condition,
        capturedAtEpochMillis = 0,
        plugged = false,
        device = emptyMap(),
        discoveryAccess = emptyMap(),
        sectionStatus = emptyMap(),
        settings = mapOf(
            "global/charging_limit" to "$limit [APP_SHELL]",
            "global/charging_enabled" to "$enabled [APP_SHELL]",
            "global/charging_mode" to "$mode [APP_SHELL]"
        ),
        properties = emptyMap(),
        systemPackages = emptyMap(),
        powerSupplyNodes = mapOf("bms/charge_now_raw" to "$telemetry [APP_FILE]"),
        batteryState = emptyMap(),
        adapterStates = emptyMap()
    )
}
