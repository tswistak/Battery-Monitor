/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>.
*/
package codes.swistak.batterymonitor.monitoring.presentation

import android.os.Bundle
import codes.swistak.batterymonitor.monitoring.BatteryCurrent
import codes.swistak.batterymonitor.monitoring.BatteryCurrentReading
import codes.swistak.batterymonitor.monitoring.BatteryMeasurementTime
import codes.swistak.batterymonitor.monitoring.CurrentSample

internal data class CurrentDemand(val refreshMillis: Long, val preferAverage: Boolean) {
    fun toBundle(token: Long) = Bundle().apply {
        putBoolean("current_active", true)
        putLong("current_token", token)
        putLong("current_interval", refreshMillis)
        putBoolean("current_average", preferAverage)
    }

    companion object {
        private const val DEFAULT_INTERVAL_MILLIS = 2_000L
        private const val MIN_INTERVAL_MILLIS = 1_000L
        private const val MAX_INTERVAL_MILLIS = 3_600_000L

        fun fromBundle(bundle: Bundle): CurrentDemand? {
            if (!bundle.getBoolean("current_active")) return null
            return CurrentDemand(
                bundle.getLong("current_interval", DEFAULT_INTERVAL_MILLIS)
                    .coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS),
                bundle.getBoolean("current_average")
            )
        }
    }
}

internal data class MonitoringCurrentState(
    val connected: Boolean = false,
    val sample: CurrentSample = CurrentSample(null, null),
    val processId: String? = null,
    val sequence: Long = -1L
)

internal fun CurrentSample.toBundle() = Bundle().apply {
    fun write(key: String, reading: BatteryCurrentReading?) {
        reading ?: return
        putBundle(key, Bundle().apply {
            putLong("microamps", reading.microAmps)
            putString("source", reading.source)
            reading.acquiredAt?.let { putBundle("acquired_at", it.toBundle()) }
        })
    }
    write("current_now", now)
    write("current_average", average)
}

internal fun currentSampleFromBundle(bundle: Bundle): CurrentSample {
    fun read(key: String): BatteryCurrentReading? {
        val value = bundle.getBundle(key) ?: return null
        if (!value.containsKey("microamps")) return null
        val raw = value.getLong("microamps")
        if (!BatteryCurrent.isValidMicroAmps(raw)) return null
        return BatteryCurrentReading(
            raw,
            value.getString("source"),
            BatteryMeasurementTime.fromBundle(value.getBundle("acquired_at"))
        )
    }
    return CurrentSample(read("current_now"), read("current_average"))
}
