/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.monitoring

import kotlin.math.abs

internal object BatteryCurrentMultiplierDetector {
    private const val MIN_TYPICAL_CHARGING_MILLIAMPS = 500.0
    private const val MIN_TYPICAL_DISCHARGING_MILLIAMPS = 100.0
    private const val CHARGING_TAPER_PERCENT = 90
    private val MAGNITUDE_MULTIPLIERS = intArrayOf(1, 10, 100, 1000)

    fun detect(
        milliAmpsAtMultiplierOne: Double,
        batteryStatus: Int,
        batteryPercent: Int
    ): Int? {
        if (!milliAmpsAtMultiplierOne.isFinite() || milliAmpsAtMultiplierOne == 0.0) {
            return null
        }

        val expectedSign = when (batteryStatus) {
            BatteryInfo.STATUS_CHARGING -> 1
            BatteryInfo.STATUS_DISCHARGING, BatteryInfo.STATUS_UNPLUGGED -> -1
            else -> return null
        }
        val minimumTypicalCurrent = when (batteryStatus) {
            BatteryInfo.STATUS_CHARGING -> MIN_TYPICAL_CHARGING_MILLIAMPS
            else -> MIN_TYPICAL_DISCHARGING_MILLIAMPS
        }
        val absoluteCurrent = abs(milliAmpsAtMultiplierOne)

        if (batteryStatus == BatteryInfo.STATUS_CHARGING &&
            batteryPercent >= CHARGING_TAPER_PERCENT &&
            absoluteCurrent < minimumTypicalCurrent
        ) {
            return null
        }

        val magnitudeMultiplier = MAGNITUDE_MULTIPLIERS.firstOrNull {
            absoluteCurrent * it >= minimumTypicalCurrent
        } ?: return null
        val reportedSign = if (milliAmpsAtMultiplierOne > 0.0) 1 else -1

        return magnitudeMultiplier * expectedSign * reportedSign
    }
}
