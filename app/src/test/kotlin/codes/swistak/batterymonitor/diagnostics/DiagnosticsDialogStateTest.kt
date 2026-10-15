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
    fun only_one_dialog_is_open_and_cancel_has_no_monitoring_or_capture_effect() {
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
    fun selecting_a_condition_does_not_capture_until_confirmation() {
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
    fun every_non_fixed_choice_preserves_its_charging_condition() {
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
    fun fixed_choice_replaces_the_picker_and_cancellation_does_not_capture() {
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
    fun fixed_limit_uses_existing_localized_whole_number_validation_and_original_range() {
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
    fun capture_cannot_start_twice_and_limited_discovery_shows_one_hint() {
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
    fun asynchronous_hint_waits_for_existing_dialog_instead_of_overlapping_it() {
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
    fun successful_unrestricted_capture_does_not_show_a_privileged_hint() {
        val state = DiagnosticsDialogState()
        state.beginCapture()
        state.finishCapture(false)
        assertNull(state.dialog)
        assertFalse(state.capturing)
    }

    @Test
    fun process_restoration_retains_dialog_and_selection_without_running_actions() {
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
    fun malformed_saved_dialog_and_selection_are_ignored() {
        val state = DiagnosticsDialogState()
        state.restore("missing", 9)
        assertNull(state.dialog)
        assertNull(state.selectedCondition)
    }

    @Test
    fun hint_acknowledged_on_another_diagnostics_screen_is_not_shown_again() {
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
