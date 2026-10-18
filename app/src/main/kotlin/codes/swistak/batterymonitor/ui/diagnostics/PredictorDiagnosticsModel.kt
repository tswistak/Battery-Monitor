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
import java.text.NumberFormat
import java.util.Locale

internal fun predictorDiagnosticGroup(
    data: PredictorStoredState, observedAtMillis: Long, formatDuration: (Long) -> String
): DiagnosticGroup =
    predictorDiagnosticGroup(data, observedAtMillis, Locale.getDefault(), formatDuration)

internal fun predictorDiagnosticGroup(
    data: PredictorStoredState,
    observedAtMillis: Long,
    locale: Locale,
    formatDuration: (Long) -> String
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
        value = data.version?.let { NumberFormat.getIntegerInstance(locale).format(it) },
        rawValue = data.version?.toString(),
        unit = "",
        source = Predictor.STORE_NAME,
        observedAtMillis = observedAtMillis,
        accessMethod = "Battery Monitor",
        valueLabel = if (data.version == null) R.string.diag_predictor_not_saved else 0
    )
    return DiagnosticGroup(R.string.device_data_predictor, averages + version)
}

internal fun recentPredictorDiagnosticGroup(
    data: PredictorRecentState, observedAtMillis: Long, formatDuration: (Long) -> String
): DiagnosticGroup =
    recentPredictorDiagnosticGroup(data, observedAtMillis, Locale.getDefault(), formatDuration)

internal fun recentPredictorDiagnosticGroup(
    data: PredictorRecentState,
    observedAtMillis: Long,
    locale: Locale,
    formatDuration: (Long) -> String
): DiagnosticGroup {
    fun row(
        label: Int,
        key: String,
        raw: Any,
        value: String? = null,
        valueLabel: Int = 0,
        unit: String = ""
    ) = DiagnosticRow(
        label = label,
        rawKey = key,
        value = value,
        rawValue = raw.toString(),
        unit = unit,
        source = "PredictorCore",
        observedAtMillis = observedAtMillis,
        accessMethod = "Battery Monitor",
        valueLabel = valueLabel
    )

    fun rate(label: Int, key: String, raw: Double): DiagnosticRow {
        val valid = raw.isFinite() && raw > 0 && raw < Long.MAX_VALUE.toDouble()
        return row(
            label,
            key,
            raw,
            value = if (valid) formatDuration(raw.toLong()) else null,
            valueLabel = if (valid) 0 else R.string.advanced_value_not_available,
            unit = "ms per percentage point"
        )
    }

    val method = when (data.predictionType) {
        PredictorCore.AUTOMAGIC -> R.string.predictor_conservative
        PredictorCore.SINCE_STATUS_CHANGE -> R.string.predictor_since_status_change
        else -> if (data.predictionType > 0) R.string.predictor_sensitive else R.string.predictor_long_term_ave
    }
    val activeState = when (data.chargingStatus) {
        PredictorCore.DISCHARGE -> R.string.status_discharging
        PredictorCore.RECHARGE_AC -> R.string.diag_predictor_ac
        PredictorCore.RECHARGE_WL -> R.string.diag_predictor_wireless
        PredictorCore.RECHARGE_USB -> R.string.diag_predictor_usb
        else -> R.string.status_unknown
    }
    return DiagnosticGroup(
        R.string.diag_predictor_recent, listOf(
            row(
                R.string.pref_prediction_type,
                "predictionType",
                data.predictionType,
                valueLabel = method
            ), row(
                R.string.diag_predictor_active_state,
                "chargingStatus",
                data.chargingStatus,
                valueLabel = activeState
            ), rate(
                R.string.diag_predictor_effective_rate,
                "effectiveMsPerPoint",
                data.effectiveMsPerPoint
            ), rate(
                R.string.diag_predictor_five_minute_rate,
                "fiveMinuteMsPerPoint",
                data.fiveMinuteMsPerPoint
            ), rate(
                R.string.diag_predictor_five_point_rate,
                "fivePointMsPerPoint",
                data.fivePointMsPerPoint
            ), rate(
                R.string.predictor_since_status_change, "sessionMsPerPoint", data.sessionMsPerPoint
            ), rate(
                R.string.diag_predictor_active_long_term_rate,
                "longTermMsPerPoint",
                data.longTermMsPerPoint
            ), row(
                R.string.diag_predictor_observed_points,
                "observedPoints",
                data.observedPoints,
                value = NumberFormat.getIntegerInstance(locale).format(data.observedPoints)
            ), row(
                R.string.diag_predictor_observation_duration,
                "observationDurationMillis",
                data.observationDurationMillis,
                value = formatDuration(data.observationDurationMillis),
                unit = "ms"
            ), row(
                R.string.diag_predictor_current_level_duration,
                "currentLevelDurationMillis",
                data.currentLevelDurationMillis,
                value = formatDuration(data.currentLevelDurationMillis),
                unit = "ms"
            ), row(
                R.string.diag_predictor_uses_partial,
                "usesPartial",
                data.usesPartial,
                valueLabel = if (data.usesPartial) R.string.yes else R.string.diag_no
            )
        )
    )
}


internal fun combinedPredictorDiagnosticGroup(
    stored: DiagnosticGroup?, recent: DiagnosticGroup?
): DiagnosticGroup? {
    if (stored == null && recent == null) return null
    return DiagnosticGroup(
        R.string.device_data_predictor, stored?.rows.orEmpty() + recent?.rows.orEmpty()
    )
}

internal fun rawPredictorDiagnosticGroup(group: DiagnosticGroup): DiagnosticGroup =
    group.copy(rows = group.rows.filter { it.rawValue != null }.map {
        it.copy(label = 0, value = it.rawValue, unit = "", valueLabel = 0)
    })
