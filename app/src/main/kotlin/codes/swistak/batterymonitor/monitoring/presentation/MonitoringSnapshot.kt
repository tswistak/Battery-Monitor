package codes.swistak.batterymonitor.monitoring.presentation

import android.os.Bundle
import codes.swistak.batterymonitor.monitoring.BatteryInfo

internal data class MonitoringReading<T>(
    val value: T?, val source: String, val observedAtMillis: Long
) {
    val available: Boolean get() = value != null
}

internal data class PredictionSnapshot(
    val direction: Int,
    val targetPercent: Int,
    val targetReached: Boolean,
    val expectedAtElapsedRealtime: Long,
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
    val lastPlugged: Int,
    val lastPercent: Int,
    val lastStatusTimeMillis: Long,
    val configuredPrediction: PredictionSnapshot,
    val fullRangePrediction: PredictionSnapshot,
    val observedAtMillis: Long,
    val source: String = "BatteryInfoService bundle"
) {
    val voltage: MonitoringReading<Int>
        get() = MonitoringReading(voltageMillivolts, source, observedAtMillis)
    val remainingCharge: MonitoringReading<Long>
        get() = MonitoringReading(remainingChargeMicroampHours, source, observedAtMillis)

    companion object {
        fun fromBundle(bundle: Bundle): MonitoringSnapshot {
            val info = BatteryInfo().apply { loadBundle(bundle) }
            return MonitoringSnapshot(
                levelPercent = info.percent,
                status = info.status,
                health = info.health,
                plugged = info.plugged,
                temperatureTenthsCelsius = info.temperature,
                voltageMillivolts = info.voltage,
                remainingChargeMicroampHours = info.remainingChargeUah,
                lastStatus = info.lastStatus,
                lastPlugged = info.lastPlugged,
                lastPercent = info.lastPercent,
                lastStatusTimeMillis = info.lastStatusCtm,
                configuredPrediction = info.prediction.toSnapshot(),
                fullRangePrediction = info.fullRangePrediction.toSnapshot(),
                observedAtMillis = bundle.getLong(MonitoringConnection.FIELD_OBSERVED_AT)
            )
        }
    }
}

private fun BatteryInfo.Prediction.toSnapshot() = PredictionSnapshot(
    direction = whatHappened,
    targetPercent = targetPercent,
    targetReached = targetReached,
    expectedAtElapsedRealtime = whenHappened,
    days = lastRTime.days,
    hours = lastRTime.hours,
    minutes = lastRTime.minutes
)
