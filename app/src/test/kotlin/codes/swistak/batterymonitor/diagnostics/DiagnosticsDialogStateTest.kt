/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.diagnostics

import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingDiagnosticCondition
import codes.swistak.batterymonitor.ui.components.NumericInputResult
import codes.swistak.batterymonitor.ui.components.parseLocalizedInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DiagnosticsDialogStateTest {
    @Test
    fun `only one dialog is open and cancel has no monitoring or capture effect`() {
        val state = DiagnosticsDialogState()
        state.open(DiagnosticsDialog.STOP_MONITORING)
        state.open(DiagnosticsDialog.CHARGING_CONDITION)
        assertEquals(DiagnosticsDialog.STOP_MONITORING, state.dialog)
        assertNull(state.confirmCondition())
        state.dismiss()
        assertNull(state.dialog)
        assertFalse(state.capturing)
    }

    @Test
    fun `selecting a condition does not capture until confirmation`() {
        val state = DiagnosticsDialogState()
        state.open(DiagnosticsDialog.CHARGING_CONDITION)
        assertNull(state.confirmCondition())
        state.selectedCondition = 2
        assertFalse(state.capturing)
        assertEquals(ChargingDiagnosticCondition.Adaptive, state.confirmCondition())
        assertNull(state.dialog)
        assertNull(state.confirmCondition())
    }

    @Test
    fun `every non fixed choice preserves its charging condition`() {
        for ((selection, condition) in mapOf(
            0 to ChargingDiagnosticCondition.Off,
            2 to ChargingDiagnosticCondition.Adaptive,
            3 to ChargingDiagnosticCondition.Scheduled,
            4 to ChargingDiagnosticCondition.Other
        )) {
            val state = DiagnosticsDialogState()
            state.open(DiagnosticsDialog.CHARGING_CONDITION)
            state.selectedCondition = selection
            assertEquals(condition, state.confirmCondition())
        }
    }

    @Test
    fun `fixed choice replaces the picker and cancellation does not capture`() {
        val state = DiagnosticsDialogState()
        state.open(DiagnosticsDialog.CHARGING_CONDITION)
        state.selectedCondition = 1
        assertNull(state.confirmCondition())
        assertEquals(DiagnosticsDialog.FIXED_CHARGING_LIMIT, state.dialog)
        state.dismiss()
        assertNull(state.dialog)
        assertFalse(state.capturing)
        assertEquals(80, DEFAULT_FIXED_CHARGING_LIMIT_PERCENT)
    }

    @Test
    fun `fixed limit uses existing localized whole number validation and original range`() {
        val min = ChargingDiagnosticCondition.MIN_FIXED_PERCENT
        val max = ChargingDiagnosticCondition.MAX_FIXED_PERCENT
        for (value in listOf(60, 80, 100)) {
            assertEquals(
                NumericInputResult.Valid(value),
                parseLocalizedInt(value.toString(), Locale.US, min, max, 1)
            )
        }
        for (input in listOf("", "59", "101", "80.5", "eighty")) {
            assertTrue(parseLocalizedInt(input, Locale.US, min, max, 1) is NumericInputResult.Error)
        }
        assertEquals(
            NumericInputResult.Valid(80),
            parseLocalizedInt("٨٠", Locale.forLanguageTag("ar"), min, max, 1)
        )
    }

    @Test
    fun `capture cannot start twice and limited discovery shows one hint`() {
        val state = DiagnosticsDialogState()
        assertTrue(state.beginCapture())
        assertFalse(state.beginCapture())
        state.finishCapture(true)
        assertFalse(state.capturing)
        assertEquals(DiagnosticsDialog.PRIVILEGED_HINT, state.dialog)
        state.offerPrivilegedHint()
        state.dismiss()
        assertNull(state.dialog)
    }

    @Test
    fun `asynchronous hint waits for existing dialog instead of overlapping it`() {
        val state = DiagnosticsDialogState()
        state.open(DiagnosticsDialog.MONITOR_REPORT)
        state.finishCapture(true)
        assertEquals(DiagnosticsDialog.MONITOR_REPORT, state.dialog)
        state.dismiss()
        assertEquals(DiagnosticsDialog.PRIVILEGED_HINT, state.dialog)
        state.dismiss()
        assertNull(state.dialog)
    }

    @Test
    fun `successful unrestricted capture does not show a privileged hint`() {
        val state = DiagnosticsDialogState()
        state.beginCapture()
        state.finishCapture(false)
        assertNull(state.dialog)
        assertFalse(state.capturing)
    }

    @Test
    fun `process restoration retains dialog and selection without running actions`() {
        val previous = DiagnosticsDialogState()
        previous.open(DiagnosticsDialog.CHARGING_CONDITION)
        previous.selectedCondition = 3
        val restored = DiagnosticsDialogState()
        restored.restore(previous.dialog?.name, previous.selectedCondition)
        assertEquals(DiagnosticsDialog.CHARGING_CONDITION, restored.dialog)
        assertEquals(3, restored.selectedCondition)
        assertFalse(restored.capturing)
        restored.offerPrivilegedHint()
        assertEquals(DiagnosticsDialog.CHARGING_CONDITION, restored.dialog)
        restored.dismiss()
        assertEquals(DiagnosticsDialog.PRIVILEGED_HINT, restored.dialog)
    }

    @Test
    fun `malformed saved dialog and selection are ignored`() {
        val state = DiagnosticsDialogState()
        state.restore("missing", 9)
        assertNull(state.dialog)
        assertNull(state.selectedCondition)
    }

    @Test
    fun `hint acknowledged on another diagnostics screen is not shown again`() {
        val state = DiagnosticsDialogState()
        state.offerPrivilegedHint()
        state.clearPrivilegedHint()
        assertNull(state.dialog)
        state.open(DiagnosticsDialog.MONITOR_REPORT)
        state.offerPrivilegedHint()
        state.clearPrivilegedHint()
        assertEquals(DiagnosticsDialog.MONITOR_REPORT, state.dialog)
        state.dismiss()
        assertNull(state.dialog)
    }
}
