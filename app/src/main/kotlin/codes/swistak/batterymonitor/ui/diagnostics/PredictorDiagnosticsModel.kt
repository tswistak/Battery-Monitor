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
import java.text.NumberFormat

internal fun predictorDiagnosticGroup(
    data: PredictorStoredState, observedAtMillis: Long, formatDuration: (Long) -> String
): DiagnosticGroup {
    val labels = listOf(
        R.string.status_discharging,
        R.string.diag_predictor_ac,
        R.string.diag_predictor_wireless,
        R.string.diag_predictor_usb
    )
    val averages = Predictor.KEY_AVERAGE.mapIndexed { index, key ->
        val raw = data.averages[key]
        val valueLabel = when {
            raw == null -> R.string.diag_predictor_not_saved
            raw == -1f -> R.string.diag_predictor_default
            !raw.isFinite() || raw <= 0f || raw.toDouble() >= Long.MAX_VALUE.toDouble() -> R.string.diag_predictor_invalid

            else -> 0
        }
        DiagnosticRow(
            label = labels[index],
            rawKey = key,
            value = if (valueLabel == 0) formatDuration(requireNotNull(raw).toLong()) else null,
            rawValue = raw?.toString(),
            unit = "ms per percentage point",
            source = Predictor.STORE_NAME,
            observedAtMillis = observedAtMillis,
            accessMethod = "Battery Monitor",
            valueLabel = valueLabel
        )
    }
    val version = DiagnosticRow(
        label = R.string.diag_predictor_version,
        rawKey = Predictor.KEY_STATE_VERSION,
        value = data.version?.let { NumberFormat.getIntegerInstance().format(it) },
        rawValue = data.version?.toString(),
        unit = "",
        source = Predictor.STORE_NAME,
        observedAtMillis = observedAtMillis,
        accessMethod = "Battery Monitor",
        valueLabel = if (data.version == null) R.string.diag_predictor_not_saved else 0
    )
    return DiagnosticGroup(R.string.device_data_predictor, averages + version)
}

internal fun rawPredictorDiagnosticGroup(group: DiagnosticGroup): DiagnosticGroup =
    group.copy(rows = group.rows.filter { it.rawValue != null }.map {
        it.copy(label = 0, value = it.rawValue, unit = "", valueLabel = 0)
    })
