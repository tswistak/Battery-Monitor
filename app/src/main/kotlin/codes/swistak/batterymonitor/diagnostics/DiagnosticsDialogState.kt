/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.diagnostics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingDiagnosticCondition

internal const val DEFAULT_FIXED_CHARGING_LIMIT_PERCENT = 80

internal enum class DiagnosticsDialog {
    STOP_MONITORING, CHARGING_CONDITION, FIXED_CHARGING_LIMIT, MONITOR_REPORT, CHARGING_REPORT
}

internal class DiagnosticsDialogState {
    var dialog by mutableStateOf<DiagnosticsDialog?>(null)
    var selectedCondition by mutableStateOf<Int?>(null)
    var capturing by mutableStateOf(false)
        private set

    fun restore(dialogName: String?, selection: Int?) {
        dialog = DiagnosticsDialog.entries.firstOrNull { it.name == dialogName }
        selectedCondition = selection?.takeIf { it in 0..4 }
    }

    fun open(dialog: DiagnosticsDialog) {
        if (this.dialog != null) return
        selectedCondition = null
        this.dialog = dialog
    }

    fun dismiss() {
        dialog = null
    }

    fun confirmCondition(): ChargingDiagnosticCondition? {
        if (dialog != DiagnosticsDialog.CHARGING_CONDITION) return null
        val condition = when (selectedCondition) {
            0 -> ChargingDiagnosticCondition.Off
            1 -> {
                dialog = DiagnosticsDialog.FIXED_CHARGING_LIMIT
                return null
            }

            2 -> ChargingDiagnosticCondition.Adaptive
            3 -> ChargingDiagnosticCondition.Scheduled
            4 -> ChargingDiagnosticCondition.Other
            else -> return null
        }
        dismiss()
        return condition
    }

    fun beginCapture(): Boolean {
        if (capturing) return false
        capturing = true
        return true
    }

    fun finishCapture() {
        capturing = false
    }
}

internal class DiagnosticsDialogViewModel : ViewModel() {
    val state = DiagnosticsDialogState()
    var initialized = false
}
