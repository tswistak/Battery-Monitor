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

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingTargetResolver
import codes.swistak.batterymonitor.monitoring.charginglimit.DeviceChargingLimitProvider
import codes.swistak.batterymonitor.monitoring.charginglimit.ResolvedTarget
import codes.swistak.batterymonitor.settings.SettingsContract

internal data class ResolvedPredictionTargets(
    val charging: ResolvedTarget, val discharging: ResolvedTarget
)

internal class Predictor(context: Context) {
    companion object {
        internal const val STORE_NAME = "predictor_sp_store"
        internal val KEY_AVERAGE = arrayOf(
            "key_ave_discharge",
            "key_ave_recharge_ac",
            "key_ave_recharge_wl",
            "key_ave_recharge_usb"
        )
    }

    private val spPredictor: SharedPreferences = context.getSharedPreferences(STORE_NAME, 0)

    private val settings =
        context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
    private val targetResolver = ChargingTargetResolver(
        settings, DeviceChargingLimitProvider(
            context, privilegedAccessEnabled = {
                settings.getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false)
            })
    )

    private val editor: SharedPreferences.Editor = spPredictor.edit()

    private val pc: PredictorCore = PredictorCore(
        spPredictor.getFloat(KEY_AVERAGE[PredictorCore.DISCHARGE], -1f),
        spPredictor.getFloat(KEY_AVERAGE[PredictorCore.RECHARGE_AC], -1f),
        spPredictor.getFloat(KEY_AVERAGE[PredictorCore.RECHARGE_WL], -1f),
        spPredictor.getFloat(KEY_AVERAGE[PredictorCore.RECHARGE_USB], -1f)
    )

    private val fullRangePc: PredictorCore = PredictorCore(
        spPredictor.getFloat(KEY_AVERAGE[PredictorCore.DISCHARGE], -1f),
        spPredictor.getFloat(KEY_AVERAGE[PredictorCore.RECHARGE_AC], -1f),
        spPredictor.getFloat(KEY_AVERAGE[PredictorCore.RECHARGE_WL], -1f),
        spPredictor.getFloat(KEY_AVERAGE[PredictorCore.RECHARGE_USB], -1f)
    )
    private val fullRangeInfo = BatteryInfo()

    fun setPredictionType(type: String) {
        pc.setPredictionType(type.toInt())
        fullRangePc.setPredictionType(type.toInt())
    }

    fun update(info: BatteryInfo): ResolvedPredictionTargets {
        val chargingTarget = targetResolver.resolveChargingTarget()
        val dischargingTarget = targetResolver.resolveDischargingTarget()
        pc.setTargets(chargingTarget.percent, dischargingTarget.percent)
        fullRangePc.setTargets(chargingTargetPercent = 100, dischargingTargetPercent = 0)
        val now = SystemClock.elapsedRealtime()
        pc.update(info, now)

        fullRangeInfo.percent = info.percent
        fullRangeInfo.status = info.status
        fullRangeInfo.plugged = info.plugged
        fullRangePc.update(fullRangeInfo, now)
        info.fullRangePrediction.copyFrom(fullRangeInfo.prediction)
        editor.putFloat(KEY_AVERAGE[pc.curChargingStatus], pc.longTermAverage.toFloat()).apply()
        return ResolvedPredictionTargets(chargingTarget, dischargingTarget)
    }
}
