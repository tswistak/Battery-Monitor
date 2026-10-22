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

internal enum class CurrentMeasurement(val route: String) {
    TEMPERATURE("temperature"), VOLTAGE("voltage"), CURRENT("current"), POWER("power"), ANDROID_HEALTH(
        "android-health"
    ),
    REMAINING_CHARGE("remaining-charge");

    companion object {
        fun fromRoute(route: String?): CurrentMeasurement? =
            entries.firstOrNull { it.route == route }
    }
}

internal enum class CurrentCondition {
    WAITING, DISABLED, STALE, CHARGING, DISCHARGING, FULL, PAUSED, TARGET_REACHED, BELOW_TARGET, LOW, INSUFFICIENT, UNKNOWN
}

internal fun currentMeasurementSource(
    measurement: CurrentMeasurement, snapshot: MonitoringSnapshot?, currentSource: String?
): String? = when (measurement) {
    CurrentMeasurement.TEMPERATURE -> snapshot?.fieldSources?.get("temperature")
    CurrentMeasurement.VOLTAGE -> snapshot?.fieldSources?.get("voltage")
    CurrentMeasurement.ANDROID_HEALTH -> snapshot?.fieldSources?.get("health")
    CurrentMeasurement.REMAINING_CHARGE -> snapshot?.fieldSources?.get("charge_counter")
    CurrentMeasurement.CURRENT -> currentSource
    CurrentMeasurement.POWER -> snapshot?.fieldSources?.get("voltage")?.let { voltage ->
        currentSource?.let { "$voltage × $it" }
    }
}

internal data class CurrentStateModel(
    val snapshot: MonitoringSnapshot?,
    val condition: CurrentCondition,
    val prediction: PredictionSnapshot?,
    val hasAlternative: Boolean,
    val showingFullRange: Boolean,
    val notificationsDisabled: Boolean,
    val targetPercent: Int? = prediction?.targetPercent,
    val alternativeTargetPercent: Int? = null
)

internal fun MonitoringSnapshot.predictionSelectionContext(): Pair<Boolean, Int>? {
    val charging = when (status) {
        BatteryInfo.STATUS_DISCHARGING, BatteryInfo.STATUS_UNPLUGGED -> false
        BatteryInfo.STATUS_CHARGING -> true
        BatteryInfo.STATUS_NOT_CHARGING, BatteryInfo.STATUS_FULLY_CHARGED -> if (plugged != BatteryInfo.PLUGGED_UNPLUGGED) true else return null

        else -> return null
    }
    val target = effectivePredictionTargetPercent
    if (charging && target !in 1..100 || !charging && target !in 0..99) return null
    return charging to target
}

internal fun currentStateModel(
    state: MonitoringUiState,
    showingFullRange: Boolean = false,
    monitoringEnabled: Boolean = true,
    notificationsEnabled: Boolean = true
): CurrentStateModel {
    val snapshot = state.snapshot
    val configured = snapshot?.configuredPrediction
    val fullRange = snapshot?.fullRangePrediction
    val context = snapshot?.predictionSelectionContext()
    val configuredTarget = context?.second
    val fullTarget = context?.let { if (it.first) 100 else 0 }
    val alternative = if (context?.first == true) {
        configuredTarget in 1..99
    } else {
        context != null && configuredTarget in 1..99 && (configured?.direction == BatteryInfo.Prediction.UNTIL_DRAINED || configured?.targetReached == true) && fullRange?.direction == BatteryInfo.Prediction.UNTIL_DRAINED
    }
    val useFullRange = showingFullRange && alternative
    val prediction = if (useFullRange) fullRange else configured
    val target = if (useFullRange) fullTarget else configuredTarget
    val condition = when {
        !monitoringEnabled -> CurrentCondition.DISABLED
        state.availability == MonitoringAvailability.STALE -> CurrentCondition.STALE
        snapshot == null -> CurrentCondition.WAITING
        snapshot.status == BatteryInfo.STATUS_FULLY_CHARGED -> CurrentCondition.FULL
        snapshot.status == BatteryInfo.STATUS_NOT_CHARGING -> CurrentCondition.PAUSED
        configured?.targetReached == true && configured.direction == BatteryInfo.Prediction.UNTIL_DRAINED && snapshot.levelPercent < configured.targetPercent -> CurrentCondition.BELOW_TARGET
        configured?.targetReached == true -> CurrentCondition.TARGET_REACHED
        snapshot.levelPercent <= 10 -> CurrentCondition.LOW
        configured?.direction == BatteryInfo.Prediction.NONE -> CurrentCondition.INSUFFICIENT
        snapshot.status == BatteryInfo.STATUS_CHARGING -> CurrentCondition.CHARGING
        snapshot.status == BatteryInfo.STATUS_DISCHARGING || snapshot.status == BatteryInfo.STATUS_UNPLUGGED -> CurrentCondition.DISCHARGING

        else -> CurrentCondition.UNKNOWN
    }
    return CurrentStateModel(
        snapshot,
        condition,
        prediction,
        alternative,
        useFullRange,
        !notificationsEnabled,
        target,
        if (useFullRange) configuredTarget else fullTarget
    )
}
