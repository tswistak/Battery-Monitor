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
package codes.swistak.batterymonitor.devicebackup

import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.monitoring.Predictor

internal object Version1DeviceDataImporter {
    const val VERSION = 1

    const val KEY_LOG_STATUS = "status"
    const val KEY_LOG_CHARGE = "charge"
    const val KEY_LOG_TIME = "time"
    const val KEY_LOG_TEMPERATURE = "temperature"
    const val KEY_LOG_VOLTAGE = "voltage"

    const val KEY_AVERAGE_DISCHARGE = "averageDischarge"
    const val KEY_AVERAGE_RECHARGE_AC = "averageRechargeAc"
    const val KEY_AVERAGE_RECHARGE_WIRELESS = "averageRechargeWireless"
    const val KEY_AVERAGE_RECHARGE_USB = "averageRechargeUsb"

    val predictorPreferenceKeysByBackupKey: Map<String, String> = linkedMapOf(
        KEY_AVERAGE_DISCHARGE to Predictor.KEY_AVERAGE[0],
        KEY_AVERAGE_RECHARGE_AC to Predictor.KEY_AVERAGE[1],
        KEY_AVERAGE_RECHARGE_WIRELESS to Predictor.KEY_AVERAGE[2],
        KEY_AVERAGE_RECHARGE_USB to Predictor.KEY_AVERAGE[3]
    )

    fun restoreLog(values: Map<String, Any?>): LogRecord = LogRecord(
        status = values.requiredInt(KEY_LOG_STATUS),
        charge = values.optionalInt(KEY_LOG_CHARGE),
        time = values.requiredLong(KEY_LOG_TIME),
        temperature = values.optionalInt(KEY_LOG_TEMPERATURE),
        voltage = values.optionalInt(KEY_LOG_VOLTAGE)
    )

    fun restorePredictor(values: Map<String, Any?>): Map<String, Float> = buildMap {
        for ((backupKey, preferenceKey) in predictorPreferenceKeysByBackupKey) {
            if (!values.containsKey(backupKey)) continue
            val value = values[backupKey]
            require(value is Number) { "Invalid predictor value for '$backupKey'" }
            val floatValue = value.toFloat()
            require(floatValue.isFinite()) { "Invalid predictor value for '$backupKey'" }
            put(preferenceKey, floatValue)
        }
    }

    private fun Map<String, Any?>.requiredInt(key: String): Int {
        require(containsKey(key)) { "Missing log value '$key'" }
        return integerValue(key, get(key))
    }

    private fun Map<String, Any?>.optionalInt(key: String): Int? {
        val value = get(key) ?: return null
        return integerValue(key, value)
    }

    private fun Map<String, Any?>.requiredLong(key: String): Long {
        require(containsKey(key)) { "Missing log value '$key'" }
        val value = get(key)
        require(value is Number) { "Invalid log value for '$key'" }
        val longValue = value.toLong()
        require(value.toDouble().isFinite() && value.toDouble() == longValue.toDouble()) {
            "Invalid log value for '$key'"
        }
        return longValue
    }

    private fun integerValue(key: String, value: Any?): Int {
        require(value is Number) { "Invalid log value for '$key'" }
        val longValue = value.toLong()
        require(
            value.toDouble()
                .isFinite() && value.toDouble() == longValue.toDouble() && longValue in Int.MIN_VALUE..Int.MAX_VALUE
        ) { "Invalid log value for '$key'" }
        return longValue.toInt()
    }
}
