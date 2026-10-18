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
package codes.swistak.batterymonitor.monitoring

import android.os.Bundle
import android.os.SystemClock

internal data class BatteryMeasurementTime(val wallMillis: Long, val elapsedMillis: Long? = null) {
    fun toBundle() = Bundle().apply {
        putLong("wall", wallMillis)
        elapsedMillis?.let { putLong("elapsed", it) }
    }

    companion object {
        fun now() = BatteryMeasurementTime(
            System.currentTimeMillis(), runCatching { SystemClock.elapsedRealtime() }.getOrNull()
        )

        fun fromBundle(bundle: Bundle?): BatteryMeasurementTime? {
            if (bundle == null || !bundle.containsKey("wall")) return null
            return BatteryMeasurementTime(
                bundle.getLong("wall"),
                bundle.getLong("elapsed").takeIf { bundle.containsKey("elapsed") && it >= 0 })
        }

        fun readMap(bundle: Bundle?): Map<String, BatteryMeasurementTime> =
            bundle?.keySet()?.mapNotNull { key ->
                fromBundle(bundle.getBundle(key))?.let { key to it }
            }?.toMap().orEmpty()
    }
}

internal fun oldestMeasurementTime(
    first: BatteryMeasurementTime?, second: BatteryMeasurementTime?
): BatteryMeasurementTime? {
    val firstElapsed = first?.elapsedMillis ?: return null
    val secondElapsed = second?.elapsedMillis ?: return null
    if (first.wallMillis <= 0 || second.wallMillis <= 0 || firstElapsed < 0 || secondElapsed < 0) return null
    return if (firstElapsed <= secondElapsed) first else second
}
