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

import android.content.Context
import android.os.BatteryManager
import android.util.Log

internal class RemainingChargeReader(
    private val getIntProperty: (Int) -> Int,
    private val onReadFailure: (RuntimeException) -> Unit = {}
) {
    companion object {
        private const val LOG_TAG = "RemainingChargeReader"
    }

    constructor(context: Context) : this(getIntProperty = { property ->
        context.applicationContext.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(property) ?: Int.MIN_VALUE
    }, onReadFailure = { exception ->
        Log.w(LOG_TAG, "Unable to read the remaining battery charge", exception)
    })

    fun readMicroAmpHours(): Long? {
        return try {
            getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeUnless { it == Int.MIN_VALUE || it < 0 }
                ?.toLong()
        } catch (exception: RuntimeException) {
            onReadFailure(exception)
            null
        }
    }
}
