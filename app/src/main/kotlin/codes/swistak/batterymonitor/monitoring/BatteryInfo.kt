/*
    Copyright (c) 2009-2020 Darshan Computing, LLC
    Modified in 2026 by Tomasz Świstak <tomasz@swistak.codes> for the Battery Monitor fork.
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

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import codes.swistak.batterymonitor.monitoring.batteryvoltage.BatteryVoltageValidator
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.IOException

internal class BatteryInfo {
    companion object {
        const val LOG_TAG = "codes.swistak.batterymonitor - BatteryInfo"
        const val STATUS_UNPLUGGED: Int = 0
        const val STATUS_UNKNOWN: Int = 1
        const val STATUS_CHARGING: Int = 2
        const val STATUS_DISCHARGING: Int = 3
        const val STATUS_NOT_CHARGING: Int = 4
        const val STATUS_FULLY_CHARGED: Int = 5
        const val STATUS_MAX: Int = STATUS_FULLY_CHARGED
        const val PLUGGED_UNPLUGGED: Int = 0
        const val PLUGGED_USB: Int = 2
        const val PLUGGED_UNKNOWN: Int = 3
        const val PLUGGED_WIRELESS: Int = 4
        const val PLUGGED_MAX: Int = PLUGGED_WIRELESS
        const val HEALTH_UNKNOWN: Int = 1
        const val HEALTH_GOOD: Int = 2
        const val HEALTH_COLD: Int = 7
        const val HEALTH_MAX: Int = HEALTH_COLD
        const val KEY_LAST_STATUS_CTM: String = "last_status_cTM"
        const val KEY_LAST_STATUS: String = "last_status"
        const val KEY_LAST_PERCENT: String = "last_percent"
        const val KEY_LAST_PLUGGED: String = "last_plugged"
        private const val EXTRA_LEVEL = "level"
        private const val EXTRA_SCALE = "scale"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_HEALTH = "health"
        private const val EXTRA_PLUGGED = "plugged"
        private const val EXTRA_TEMPERATURE = "temperature"
        internal const val EXTRA_VOLTAGE = "voltage"
        private const val FIELD_PERCENT = "percent"
        private const val FIELD_STATUS = "status"
        private const val FIELD_HEALTH = "health"
        private const val FIELD_PLUGGED = "plugged"
        private const val FIELD_TEMPERATURE = "temperature"
        internal const val FIELD_VOLTAGE = "voltage"
        private const val FIELD_LAST_STATUS = "last_status"
        private const val FIELD_LAST_PLUGGED = "last_plugged"
        private const val FIELD_LAST_PERCENT = "last_percent"
        private const val FIELD_LAST_STATUS_CTM = "last_status_cTM"
        private const val FIELD_PREDICTION_DAYS = "prediction_days"
        private const val FIELD_PREDICTION_HOURS = "prediction_hours"
        private const val FIELD_PREDICTION_MINUTES = "prediction_minutes"
        private const val FIELD_PREDICTION_WHAT = "prediction_what"
        private const val FIELD_PREDICTION_WHEN = "prediction_when"
        private const val FIELD_PREDICTION_TARGET_PERCENT = "prediction_target_percent"
        private const val FIELD_PREDICTION_TARGET_REACHED = "prediction_target_reached"
        private const val FIELD_FULL_RANGE_PREDICTION_DAYS = "full_range_prediction_days"
        private const val FIELD_FULL_RANGE_PREDICTION_HOURS = "full_range_prediction_hours"
        private const val FIELD_FULL_RANGE_PREDICTION_MINUTES = "full_range_prediction_minutes"
        private const val FIELD_FULL_RANGE_PREDICTION_WHAT = "full_range_prediction_what"
        private const val FIELD_FULL_RANGE_PREDICTION_WHEN = "full_range_prediction_when"
        private const val FIELD_FULL_RANGE_PREDICTION_TARGET_PERCENT =
            "full_range_prediction_target_percent"

        private const val FIELD_REMAINING_CHARGE_UAH = "remaining_charge_uah"

        private fun attemptOnePercentHack(percent: Int): Int {
            var percent = percent
            val hackFile = File("/sys/class/power_supply/battery/charge_counter")

            if (hackFile.exists()) {
                try {
                    val fReader = FileReader(hackFile)
                    val bReader = BufferedReader(fReader, 8)
                    val line = bReader.readLine()
                    bReader.close()

                    var chargeCounter = line.toInt()

                    if (chargeCounter < percent + 10 && chargeCounter > percent - 10) {
                        if (chargeCounter > 100) chargeCounter = 100

                        if (chargeCounter < 0) chargeCounter = 0

                        percent = chargeCounter
                    } else {
                        Log.e(
                            LOG_TAG,
                            "charge_counter file exists but with value $chargeCounter which is inconsistent with percent: $percent"
                        )
                    }
                } catch (e: FileNotFoundException) {
                    Log.e(LOG_TAG, "charge_counter file doesn't exist")
                } catch (e: IOException) {
                    Log.e(LOG_TAG, "Error reading charge_counter file")
                } catch (e: NumberFormatException) {
                    Log.e(LOG_TAG, "Read charge_counter file but couldn't convert contents to int")
                }
            }

            return percent
        }
    }

    var percent: Int = 0
    var status: Int = 0
    var health: Int = 0
    var plugged: Int = 0
    var temperature: Int = 0

    var voltage: Int? = null

    var remainingChargeUah: Long? = null
    var lastStatus: Int = 0
    var lastPlugged: Int = 0
    var lastPercent: Int = 0
    var lastStatusCtm: Long = 0
    var prediction: Prediction = Prediction(this)
    var fullRangePrediction: Prediction = Prediction(this)

    internal class Prediction(private val batteryInfo: BatteryInfo) {
        companion object {
            const val NONE: Int = 0
            const val UNTIL_DRAINED: Int = 1
            const val UNTIL_CHARGED: Int = 2
            private const val MIN_PREDICTION = 60 * 1000
        }

        var whatHappened: Int = 0
        var whenHappened: Long = 0
        var targetPercent: Int = 0
        var targetReached: Boolean = false

        var lastRTime: RelativeTime = RelativeTime()

        fun clear() {
            whenHappened = 0
            whatHappened = NONE
            targetPercent = 0
            targetReached = false
        }

        fun update(ts: Long, targetPercent: Int) {
            whenHappened = ts
            this.targetPercent = targetPercent
            targetReached = false

            whatHappened =
                when (batteryInfo.status) {
                    STATUS_FULLY_CHARGED, STATUS_NOT_CHARGING, STATUS_UNKNOWN -> NONE
                    STATUS_CHARGING -> UNTIL_CHARGED
                    else -> UNTIL_DRAINED
                }
        }

        fun markTargetReached(targetPercent: Int) {
            whenHappened = 0
            whatHappened = NONE
            this.targetPercent = targetPercent
            targetReached = true
            lastRTime.update(0, 0)
        }

        fun markDischargingTargetReached(targetPercent: Int) {
            whenHappened = 0
            whatHappened = UNTIL_DRAINED
            this.targetPercent = targetPercent
            targetReached = true
            lastRTime.update(0, 0)
        }

        fun copyFrom(other: Prediction) {
            whatHappened = other.whatHappened
            whenHappened = other.whenHappened
            targetPercent = other.targetPercent
            targetReached = other.targetReached
            lastRTime.days = other.lastRTime.days
            lastRTime.hours = other.lastRTime.hours
            lastRTime.minutes = other.lastRTime.minutes
        }

        fun updateRelativeTime() {
            if (targetReached) {
                lastRTime.update(0, 0)
                return
            }
            val now = SystemClock.elapsedRealtime()

            if (whenHappened < now + MIN_PREDICTION) whenHappened = now + MIN_PREDICTION

            lastRTime.update(whenHappened, now)
        }
    }

    internal class RelativeTime {
        var days: Int = 0
        var hours: Int = 0
        var minutes: Int = 0

        fun update(to: Long, from: Long) {
            val seconds = ((to - from) / 1000).toInt()
            days = 0
            hours = seconds / (60 * 60)
            minutes = (seconds / 60) % 60

            if (hours >= 24) {
                days = hours / 24
                hours %= 24
            }
        }
    }

    fun load(intent: Intent?, sp: SharedPreferences) {
        load(intent)
        load(sp)
    }

    fun load(intent: Intent?) {
        if (intent == null) return
        val level = intent.getIntExtra(EXTRA_LEVEL, 50)
        val scale = intent.getIntExtra(EXTRA_SCALE, 100)

        status = intent.getIntExtra(EXTRA_STATUS, STATUS_UNKNOWN)
        health = intent.getIntExtra(EXTRA_HEALTH, HEALTH_UNKNOWN)
        plugged = intent.getIntExtra(EXTRA_PLUGGED, PLUGGED_UNKNOWN)
        temperature = intent.getIntExtra(EXTRA_TEMPERATURE, 0)
        val rawVoltage = intent.getIntExtra(EXTRA_VOLTAGE, 0)
        voltage =
            if (BatteryVoltageValidator.isValidBroadcastMillivolts(rawVoltage)) rawVoltage else null

        percent = level * 100 / scale
        percent = attemptOnePercentHack(percent)

        if (percent > 100) percent = 100
        if (percent < 0) percent = 0
        if (plugged == PLUGGED_UNPLUGGED) status = STATUS_UNPLUGGED
        if (status > STATUS_MAX) status = STATUS_UNKNOWN
        if (health > HEALTH_MAX) health = HEALTH_UNKNOWN
        if (plugged > PLUGGED_MAX) plugged = PLUGGED_UNKNOWN

        if (lastStatusCtm == 0L) {
            lastStatus = status
            lastPlugged = plugged
            lastPercent = percent
            lastStatusCtm = System.currentTimeMillis()
        }
    }

    fun load(sp: SharedPreferences) {
        lastStatus = sp.getInt(KEY_LAST_STATUS, status)
        lastPlugged = sp.getInt(KEY_LAST_PLUGGED, plugged)
        lastStatusCtm = sp.getLong(KEY_LAST_STATUS_CTM, System.currentTimeMillis())
        lastPercent = sp.getInt(KEY_LAST_PERCENT, percent)
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()

        bundle.putInt(FIELD_PERCENT, percent)
        bundle.putInt(FIELD_STATUS, status)
        bundle.putInt(FIELD_HEALTH, health)
        bundle.putInt(FIELD_PLUGGED, plugged)
        bundle.putInt(FIELD_TEMPERATURE, temperature)
        writeVoltageField(bundle.asFieldAccessor(), voltage)
        bundle.putInt(FIELD_LAST_STATUS, lastStatus)
        bundle.putInt(FIELD_LAST_PLUGGED, lastPlugged)
        bundle.putInt(FIELD_LAST_PERCENT, lastPercent)

        bundle.putLong(FIELD_LAST_STATUS_CTM, lastStatusCtm)

        bundle.putInt(FIELD_PREDICTION_DAYS, prediction.lastRTime.days)
        bundle.putInt(FIELD_PREDICTION_HOURS, prediction.lastRTime.hours)
        bundle.putInt(FIELD_PREDICTION_MINUTES, prediction.lastRTime.minutes)

        bundle.putInt(FIELD_PREDICTION_WHAT, prediction.whatHappened)
        bundle.putLong(FIELD_PREDICTION_WHEN, prediction.whenHappened)
        bundle.putInt(FIELD_PREDICTION_TARGET_PERCENT, prediction.targetPercent)
        bundle.putBoolean(FIELD_PREDICTION_TARGET_REACHED, prediction.targetReached)
        bundle.putInt(FIELD_FULL_RANGE_PREDICTION_DAYS, fullRangePrediction.lastRTime.days)
        bundle.putInt(FIELD_FULL_RANGE_PREDICTION_HOURS, fullRangePrediction.lastRTime.hours)
        bundle.putInt(FIELD_FULL_RANGE_PREDICTION_MINUTES, fullRangePrediction.lastRTime.minutes)
        bundle.putInt(FIELD_FULL_RANGE_PREDICTION_WHAT, fullRangePrediction.whatHappened)
        bundle.putLong(FIELD_FULL_RANGE_PREDICTION_WHEN, fullRangePrediction.whenHappened)
        bundle.putInt(
            FIELD_FULL_RANGE_PREDICTION_TARGET_PERCENT, fullRangePrediction.targetPercent
        )

        remainingChargeUah?.let { bundle.putLong(FIELD_REMAINING_CHARGE_UAH, it) }

        return bundle
    }

    fun loadBundle(bundle: Bundle) {
        percent = bundle.getInt(FIELD_PERCENT)
        status = bundle.getInt(FIELD_STATUS)
        health = bundle.getInt(FIELD_HEALTH)
        plugged = bundle.getInt(FIELD_PLUGGED)
        temperature = bundle.getInt(FIELD_TEMPERATURE)
        voltage = readVoltageField(bundle.asFieldAccessor())
        lastStatus = bundle.getInt(FIELD_LAST_STATUS)
        lastPlugged = bundle.getInt(FIELD_LAST_PLUGGED)
        lastPercent = bundle.getInt(FIELD_LAST_PERCENT)

        lastStatusCtm = bundle.getLong(FIELD_LAST_STATUS_CTM)

        prediction.lastRTime.days = bundle.getInt(FIELD_PREDICTION_DAYS)
        prediction.lastRTime.hours = bundle.getInt(FIELD_PREDICTION_HOURS)
        prediction.lastRTime.minutes = bundle.getInt(FIELD_PREDICTION_MINUTES)

        prediction.whatHappened = bundle.getInt(FIELD_PREDICTION_WHAT)
        prediction.whenHappened = bundle.getLong(FIELD_PREDICTION_WHEN)
        prediction.targetPercent = if (bundle.containsKey(FIELD_PREDICTION_TARGET_PERCENT)) {
            bundle.getInt(FIELD_PREDICTION_TARGET_PERCENT)
        } else if (prediction.whatHappened == Prediction.UNTIL_CHARGED) {
            100
        } else {
            0
        }
        prediction.targetReached = bundle.getBoolean(FIELD_PREDICTION_TARGET_REACHED, false)

        if (bundle.containsKey(FIELD_FULL_RANGE_PREDICTION_WHAT)) {
            fullRangePrediction.lastRTime.days = bundle.getInt(FIELD_FULL_RANGE_PREDICTION_DAYS)
            fullRangePrediction.lastRTime.hours = bundle.getInt(FIELD_FULL_RANGE_PREDICTION_HOURS)
            fullRangePrediction.lastRTime.minutes = bundle.getInt(
                FIELD_FULL_RANGE_PREDICTION_MINUTES
            )
            fullRangePrediction.whatHappened = bundle.getInt(FIELD_FULL_RANGE_PREDICTION_WHAT)
            fullRangePrediction.whenHappened = bundle.getLong(FIELD_FULL_RANGE_PREDICTION_WHEN)
            fullRangePrediction.targetPercent = bundle.getInt(
                FIELD_FULL_RANGE_PREDICTION_TARGET_PERCENT
            )
            fullRangePrediction.targetReached = false
        } else {
            fullRangePrediction.clear()
        }

        remainingChargeUah = if (bundle.containsKey(FIELD_REMAINING_CHARGE_UAH)) {
            bundle.getLong(FIELD_REMAINING_CHARGE_UAH)
        } else {
            null
        }
    }
}

internal interface BundleFieldAccessor {
    fun containsKey(key: String): Boolean
    fun getInt(key: String): Int
    fun putInt(key: String, value: Int)
}

private fun Bundle.asFieldAccessor(): BundleFieldAccessor = object : BundleFieldAccessor {
    override fun containsKey(key: String): Boolean = this@asFieldAccessor.containsKey(key)

    override fun getInt(key: String): Int = this@asFieldAccessor.getInt(key)

    override fun putInt(key: String, value: Int) = this@asFieldAccessor.putInt(key, value)
}

internal fun writeVoltageField(accessor: BundleFieldAccessor, voltage: Int?) {
    voltage?.let { accessor.putInt(BatteryInfo.FIELD_VOLTAGE, it) }
}

internal fun readVoltageField(accessor: BundleFieldAccessor): Int? {
    return if (accessor.containsKey(BatteryInfo.FIELD_VOLTAGE)) {
        accessor.getInt(BatteryInfo.FIELD_VOLTAGE)
    } else {
        null
    }
}
