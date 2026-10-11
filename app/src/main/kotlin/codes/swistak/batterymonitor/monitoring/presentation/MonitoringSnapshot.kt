/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring.presentation

import android.os.Bundle
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.monitoring.Predictor
import codes.swistak.batterymonitor.monitoring.PredictorRecentState
import codes.swistak.batterymonitor.monitoring.PredictorStoredState

internal data class PredictionSnapshot(
    val direction: Int,
    val targetPercent: Int,
    val targetReached: Boolean,
    val days: Int,
    val hours: Int,
    val minutes: Int
)

internal data class MonitoringSnapshot(
    val levelPercent: Int,
    val status: Int,
    val health: Int,
    val plugged: Int,
    val temperatureTenthsCelsius: Int,
    val voltageMillivolts: Int?,
    val remainingChargeMicroampHours: Long?,
    val lastStatus: Int,
    val lastPercent: Int,
    val lastStatusTimeMillis: Long,
    val configuredPrediction: PredictionSnapshot,
    val fullRangePrediction: PredictionSnapshot,
    val observedAtMillis: Long,
    val source: String = "BatteryInfoService bundle",
    val predictorData: PredictorStoredState? = null,
    val recentPredictorData: PredictorRecentState? = null
) {
    companion object {
        const val FIELD_PREDICTOR_DATA = "monitoring_predictor_data"

        const val FIELD_RECENT_PREDICTOR_DATA = "monitoring_recent_predictor_data"
        private const val RECENT_PREDICTION_TYPE = "prediction_type"
        private const val RECENT_CHARGING_STATUS = "charging_status"
        private const val RECENT_EFFECTIVE_MS_PER_POINT = "effective_ms_per_point"
        private const val RECENT_FIVE_MINUTE_MS_PER_POINT = "five_minute_ms_per_point"
        private const val RECENT_FIVE_POINT_MS_PER_POINT = "five_point_ms_per_point"
        private const val RECENT_SESSION_MS_PER_POINT = "session_ms_per_point"
        private const val RECENT_LONG_TERM_MS_PER_POINT = "long_term_ms_per_point"
        private const val RECENT_OBSERVED_POINTS = "observed_points"
        private const val RECENT_OBSERVATION_DURATION = "observation_duration_millis"
        private const val RECENT_CURRENT_LEVEL_DURATION = "current_level_duration_millis"
        private const val RECENT_USES_PARTIAL = "uses_partial"

        fun recentPredictorBundle(state: PredictorRecentState): Bundle = Bundle().apply {
            putInt(RECENT_PREDICTION_TYPE, state.predictionType)
            putInt(RECENT_CHARGING_STATUS, state.chargingStatus)
            putDouble(RECENT_EFFECTIVE_MS_PER_POINT, state.effectiveMsPerPoint)
            putDouble(RECENT_FIVE_MINUTE_MS_PER_POINT, state.fiveMinuteMsPerPoint)
            putDouble(RECENT_FIVE_POINT_MS_PER_POINT, state.fivePointMsPerPoint)
            putDouble(RECENT_SESSION_MS_PER_POINT, state.sessionMsPerPoint)
            putDouble(RECENT_LONG_TERM_MS_PER_POINT, state.longTermMsPerPoint)
            putInt(RECENT_OBSERVED_POINTS, state.observedPoints)
            putLong(RECENT_OBSERVATION_DURATION, state.observationDurationMillis)
            putLong(RECENT_CURRENT_LEVEL_DURATION, state.currentLevelDurationMillis)
            putBoolean(RECENT_USES_PARTIAL, state.usesPartial)
        }

        private fun recentPredictorState(bundle: Bundle): PredictorRecentState =
            PredictorRecentState(
                predictionType = bundle.getInt(RECENT_PREDICTION_TYPE),
                chargingStatus = bundle.getInt(RECENT_CHARGING_STATUS),
                effectiveMsPerPoint = bundle.getDouble(RECENT_EFFECTIVE_MS_PER_POINT),
                fiveMinuteMsPerPoint = bundle.getDouble(RECENT_FIVE_MINUTE_MS_PER_POINT),
                fivePointMsPerPoint = bundle.getDouble(RECENT_FIVE_POINT_MS_PER_POINT),
                sessionMsPerPoint = bundle.getDouble(RECENT_SESSION_MS_PER_POINT),
                longTermMsPerPoint = bundle.getDouble(RECENT_LONG_TERM_MS_PER_POINT),
                observedPoints = bundle.getInt(RECENT_OBSERVED_POINTS),
                observationDurationMillis = bundle.getLong(RECENT_OBSERVATION_DURATION),
                currentLevelDurationMillis = bundle.getLong(RECENT_CURRENT_LEVEL_DURATION),
                usesPartial = bundle.getBoolean(RECENT_USES_PARTIAL)
            )

        fun fromBundle(bundle: Bundle): MonitoringSnapshot {
            val info = BatteryInfo().apply { loadBundle(bundle) }
            @Suppress("DEPRECATION") val predictorData =
                bundle.getBundle(FIELD_PREDICTOR_DATA)?.let { data ->
                    Predictor.readStoredState(data.keySet().associateWith { data[it] })
                }
            return MonitoringSnapshot(
                levelPercent = info.percent,
                status = info.status,
                health = info.health,
                plugged = info.plugged,
                temperatureTenthsCelsius = info.temperature,
                voltageMillivolts = info.voltage,
                remainingChargeMicroampHours = info.remainingChargeUah,
                lastStatus = info.lastStatus,
                lastPercent = info.lastPercent,
                lastStatusTimeMillis = info.lastStatusCtm,
                configuredPrediction = info.prediction.toSnapshot(),
                fullRangePrediction = info.fullRangePrediction.toSnapshot(),
                observedAtMillis = bundle.getLong(MonitoringConnection.FIELD_OBSERVED_AT),
                predictorData = predictorData,
                recentPredictorData = bundle.getBundle(FIELD_RECENT_PREDICTOR_DATA)
                    ?.let(::recentPredictorState)
            )
        }
    }
}

private fun BatteryInfo.Prediction.toSnapshot() = PredictionSnapshot(
    direction = whatHappened,
    targetPercent = targetPercent,
    targetReached = targetReached,
    days = lastRTime.days,
    hours = lastRTime.hours,
    minutes = lastRTime.minutes
)
