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

internal enum class CurrentCondition {
    WAITING, DISABLED, STALE, CHARGING, DISCHARGING, FULL, PAUSED, TARGET_REACHED, BELOW_TARGET, LOW, INSUFFICIENT, UNKNOWN
}

internal data class CurrentStateModel(
    val snapshot: MonitoringSnapshot?,
    val condition: CurrentCondition,
    val prediction: PredictionSnapshot?,
    val hasAlternative: Boolean,
    val showingFullRange: Boolean,
    val notificationsDisabled: Boolean
)

internal fun currentStateModel(
    state: MonitoringUiState,
    showingFullRange: Boolean = false,
    monitoringEnabled: Boolean = true,
    notificationsEnabled: Boolean = true
): CurrentStateModel {
    val snapshot = state.snapshot
    val configured = snapshot?.configuredPrediction
    val fullRange = snapshot?.fullRangePrediction
    val alternative =
        configured != null && fullRange != null && configured.targetPercent != fullRange.targetPercent && (configured.direction != BatteryInfo.Prediction.NONE || configured.targetReached) && fullRange.direction != BatteryInfo.Prediction.NONE
    val useFullRange = showingFullRange && alternative
    val prediction = if (useFullRange) fullRange else configured
    val condition = when {
        !monitoringEnabled -> CurrentCondition.DISABLED
        state.availability == MonitoringAvailability.STALE -> CurrentCondition.STALE
        snapshot == null -> CurrentCondition.WAITING
        snapshot.status == BatteryInfo.STATUS_FULLY_CHARGED -> CurrentCondition.FULL
        snapshot.status == BatteryInfo.STATUS_NOT_CHARGING -> CurrentCondition.PAUSED
        configured?.targetReached == true && configured.direction == BatteryInfo.Prediction.UNTIL_DRAINED -> CurrentCondition.BELOW_TARGET

        configured?.targetReached == true -> CurrentCondition.TARGET_REACHED
        snapshot.levelPercent <= 10 -> CurrentCondition.LOW
        configured?.direction == BatteryInfo.Prediction.NONE -> CurrentCondition.INSUFFICIENT
        snapshot.status == BatteryInfo.STATUS_CHARGING -> CurrentCondition.CHARGING
        snapshot.status == BatteryInfo.STATUS_DISCHARGING || snapshot.status == BatteryInfo.STATUS_UNPLUGGED -> CurrentCondition.DISCHARGING

        else -> CurrentCondition.UNKNOWN
    }
    return CurrentStateModel(
        snapshot, condition, prediction, alternative, useFullRange, !notificationsEnabled
    )
}
