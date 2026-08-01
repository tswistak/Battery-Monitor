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

internal class Predictor(context: Context) {
    companion object {
        private val KEY_AVERAGE = arrayOf<String?>(
            "key_ave_discharge",
            "key_ave_recharge_ac",
            "key_ave_recharge_wl",
            "key_ave_recharge_usb"
        )
    }

    private val spPredictor: SharedPreferences =
        context.getSharedPreferences("predictor_sp_store", 0)
    private val editor: SharedPreferences.Editor = spPredictor.edit()

    private val pc: PredictorCore = PredictorCore(
        spPredictor.getFloat(KEY_AVERAGE[PredictorCore.DISCHARGE], -1f),
        spPredictor.getFloat(KEY_AVERAGE[PredictorCore.RECHARGE_AC], -1f),
        spPredictor.getFloat(KEY_AVERAGE[PredictorCore.RECHARGE_WL], -1f),
        spPredictor.getFloat(KEY_AVERAGE[PredictorCore.RECHARGE_USB], -1f)
    )

    fun setPredictionType(type: String) {
        pc.setPredictionType(type.toInt())
    }

    fun update(info: BatteryInfo) {
        pc.update(info, SystemClock.elapsedRealtime())
        editor.putFloat(KEY_AVERAGE[pc.curChargingStatus], pc.longTermAverage.toFloat()).apply()
    }
}
