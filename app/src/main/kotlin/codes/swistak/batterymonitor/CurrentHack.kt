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
*//* This file is largely based on CurrentWidget by Ran Manor (GPL v3) */
package codes.swistak.batterymonitor

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import kotlin.math.abs

internal object CurrentHack {
    private const val LOG_TAG = "codes.swistak.batterymonitor - CurrentHack";
    private val BUILD_MODEL = Build.MODEL.lowercase()

    const val HACK_METHOD_NONE: Int = -1
    const val HACK_METHOD_BOTH: Int = 0
    const val HACK_METHOD_FILE_SYSTEM: Int = 1
    const val HACK_METHOD_BATTERY_MANAGER: Int = 2

    private var batteryManager: BatteryManager? = null
    private var preferFS = false
    private var multiplier = 1
    private var method = HACK_METHOD_NONE

    fun setContext(c: Context) {
        batteryManager =
            c.getApplicationContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager?
    }

    fun setPreferFS(pfs: Boolean) {
        preferFS = pfs

        val avail: Int = hackMethodsAvailable

        method = if (avail == HACK_METHOD_BOTH) if (preferFS) HACK_METHOD_FILE_SYSTEM
        else HACK_METHOD_BATTERY_MANAGER
        else avail
    }

    fun setMultiplier(m: Int) {
        multiplier = m
    }

    val hackMethodsAvailable: Int
        get() {
            var fs = false
            var bm = false

            if (bMCurrent != null) bm = true

            if (fSCurrent != null) fs = true

            if (bm && fs) return HACK_METHOD_BOTH

            if (bm) return HACK_METHOD_BATTERY_MANAGER

            if (fs) return HACK_METHOD_FILE_SYSTEM

            return HACK_METHOD_NONE
        }

    val current: Long?
        get() {
            if (method == HACK_METHOD_NONE) return null
            if (method == HACK_METHOD_FILE_SYSTEM) return fSCurrent

            return bMCurrent
        }

    val avgCurrent: Long?
        get() {
            if (method == HACK_METHOD_NONE) return null
            if (method == HACK_METHOD_FILE_SYSTEM) return fSAvgCurrent

            return bMAvgCurrent
        }

    private val bMCurrent: Long?
        get() {
            if (batteryManager == null) return null

            val current =
                batteryManager!!.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

            return if (current > Int.MIN_VALUE) current.toLong() * multiplier / 1000
            else null
        }

    private val bMAvgCurrent: Long?
        get() {
            if (batteryManager == null) return null

            val current =
                batteryManager!!.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)

            return if (current > Int.MIN_VALUE) current.toLong() * multiplier / 1000
            else null
        }

    private val fSCurrent: Long?
        get() {
            var f: File?

            // Galaxy S3
            if (BUILD_MODEL.contains("gt-i9300") || BUILD_MODEL.contains("gt-i9300T") || BUILD_MODEL.contains(
                    "gt-i9305"
                ) || BUILD_MODEL.contains("gt-i9305N") || BUILD_MODEL.contains("gt-i9305T") || BUILD_MODEL.contains(
                    "shv-e210k"
                ) || BUILD_MODEL.contains("shv-e210l") || BUILD_MODEL.contains("shv-e210s") || BUILD_MODEL.contains(
                    "sgh-t999"
                ) || BUILD_MODEL.contains("sgh-t999l") || BUILD_MODEL.contains("sgh-t999v") || BUILD_MODEL.contains(
                    "sgh-i747"
                ) || BUILD_MODEL.contains("sgh-i747m") || BUILD_MODEL.contains("sgh-n064") || BUILD_MODEL.contains(
                    "sc-06d"
                ) || BUILD_MODEL.contains("sgh-n035") || BUILD_MODEL.contains("sc-03e") || BUILD_MODEL.contains(
                    "SCH-j021"
                ) || BUILD_MODEL.contains("scl21") || BUILD_MODEL.contains("sch-r530") || BUILD_MODEL.contains(
                    "sch-i535"
                ) || BUILD_MODEL.contains("sch-S960l") || BUILD_MODEL.contains("gt-i9308") || BUILD_MODEL.contains(
                    "sch-i939"
                ) || BUILD_MODEL.contains("sch-s968c")
            ) {
                f = File("/sys/class/power_supply/battery/current_max")
                if (f.exists()) {
                    return CurrentHackNormalFileReader.getValue(f, false)
                }
            }

            if (BUILD_MODEL.contains("nexus 7") || (BUILD_MODEL.contains("one") && !BUILD_MODEL.contains(
                    "nexus"
                )) || BUILD_MODEL.contains("lg-d851")
            ) {
                f = File("/sys/class/power_supply/battery/current_now")
                if (f.exists()) {
                    return CurrentHackNormalFileReader.getValue(f, false)
                }
            }

            if (BUILD_MODEL.contains("sl930")) {
                f = File("/sys/class/power_supply/da9052-bat/current_avg")
                if (f.exists()) {
                    return CurrentHackNormalFileReader.getValue(f, false)
                }
            }

            // Galaxy S4
            if (BUILD_MODEL.contains("sgh-i337") || BUILD_MODEL.contains("gt-i9505") || BUILD_MODEL.contains(
                    "gt-i9500"
                ) || BUILD_MODEL.contains("sch-i545") || BUILD_MODEL.contains("find 5") || BUILD_MODEL.contains(
                    "sgh-m919"
                ) || BUILD_MODEL.contains("sgh-i537")
            ) {
                f = File("/sys/class/power_supply/battery/current_now")
                if (f.exists()) {
                    return CurrentHackNormalFileReader.getValue(f, false)
                }
            }

            if (BUILD_MODEL.contains("cynus")) {
                f = File("/sys/devices/platform/mt6329-battery/FG_Battery_CurrentConsumption")
                if (f.exists()) {
                    return CurrentHackNormalFileReader.getValue(f, false)
                }
            }

            // Zopo Zp900, etc.
            if (BUILD_MODEL.contains("zp900") || BUILD_MODEL.contains("jy-g3") || BUILD_MODEL.contains(
                    "zp800"
                ) || BUILD_MODEL.contains("zp800h") || BUILD_MODEL.contains("zp810") || BUILD_MODEL.contains(
                    "w100"
                ) || BUILD_MODEL.contains("zte v987")
            ) {
                f = File("/sys/class/power_supply/battery/BatteryAverageCurrent")
                if (f.exists()) {
                    return CurrentHackNormalFileReader.getValue(f, false)
                }
            }

            // Samsung Galaxy Tab 2
            if (BUILD_MODEL.contains("gt-p31") || BUILD_MODEL.contains("gt-p51")) {
                f = File("/sys/class/power_supply/battery/current_avg")
                if (f.exists()) {
                    return CurrentHackNormalFileReader.getValue(f, false)
                }
            }

            // HTC One X
            if (BUILD_MODEL.contains("htc one x")) {
                f = File("/sys/class/power_supply/battery/batt_attr_text")
                if (f.exists()) {
                    val value: Long? = CurrentHackBattAttrTextReader.getValue(f, "I_MBAT", "I_MBAT")
                    if (value != null) return value
                }
            }

            // wildfire S
            if (BUILD_MODEL.contains("wildfire s")) {
                f = File("/sys/class/power_supply/battery/smem_text")
                if (f.exists()) {
                    val value: Long? = CurrentHackBattAttrTextReader.getValue(
                        f, "eval_current", "batt_current"
                    )
                    if (value != null) return value
                }
            }

            // trimuph with cm7, lg ls670, galaxy s3, galaxy note 2
            if (BUILD_MODEL.contains("triumph") || BUILD_MODEL.contains("ls670") || BUILD_MODEL.contains(
                    "gt-i9300"
                ) || BUILD_MODEL.contains("sm-n9005") || BUILD_MODEL.contains("gt-n7100") || BUILD_MODEL.contains(
                    "sgh-i317"
                )
            ) {
                f = File("/sys/class/power_supply/battery/current_now")
                if (f.exists()) {
                    return CurrentHackNormalFileReader.getValue(f, false)
                }
            }

            // htc desire hd / desire z / inspire?
            // htc evo view tablet
            if (BUILD_MODEL.contains("desire hd") || BUILD_MODEL.contains("desire z") || BUILD_MODEL.contains(
                    "inspire"
                ) || BUILD_MODEL.contains("pg41200")
            ) {
                f = File("/sys/class/power_supply/battery/batt_current")
                if (f.exists()) {
                    return CurrentHackNormalFileReader.getValue(f, false)
                }
            }

            // nexus one cyanogenmod
            f = File("/sys/devices/platform/ds2784-battery/getcurrent")
            if (f.exists()) {
                return CurrentHackNormalFileReader.getValue(f, true)
            }

            // sony ericsson xperia x1
            f =
                File("/sys/devices/platform/i2c-adapter/i2c-0/0-0036/power_supply/ds2746-battery/current_now")
            if (f.exists()) {
                return CurrentHackNormalFileReader.getValue(f, false)
            }

            // xdandroid
            f =
                File("/sys/devices/platform/i2c-adapter/i2c-0/0-0036/power_supply/battery/current_now")
            if (f.exists()) {
                return CurrentHackNormalFileReader.getValue(f, false)
            }

            // droid eris
            f = File("/sys/class/power_supply/battery/smem_text")
            if (f.exists()) {
                val value: Long? = CurrentHackSMTextReader.value
                if (value != null) return value
            }

            // htc sensation / evo 3d
            f = File("/sys/class/power_supply/battery/batt_attr_text")
            if (f.exists()) {
                val value: Long? = CurrentHackBattAttrTextReader.getValue(
                    f, "batt_discharge_current", "batt_current"
                )
                if (value != null) return value
            }

            // some htc devices
            f = File("/sys/class/power_supply/battery/batt_current")
            if (f.exists()) {
                return CurrentHackNormalFileReader.getValue(f, false)
            }

            // Nexus One.
            // TODO: Make this not default but specific for N1 because of the normalization.
            f = File("/sys/class/power_supply/battery/current_now")
            if (f.exists()) {
                return CurrentHackNormalFileReader.getValue(f, true)
            }

            // samsung galaxy vibrant
            f = File("/sys/class/power_supply/battery/batt_chg_current")
            if (f.exists()) return CurrentHackNormalFileReader.getValue(f, false)

            // sony ericsson x10
            f = File("/sys/class/power_supply/battery/charger_current")
            if (f.exists()) return CurrentHackNormalFileReader.getValue(f, false)

            // Nook Color
            f = File("/sys/class/power_supply/max17042-0/current_now")
            if (f.exists()) return CurrentHackNormalFileReader.getValue(f, false)

            // Xperia Arc
            f = File("/sys/class/power_supply/bq27520/current_now")
            if (f.exists()) return CurrentHackNormalFileReader.getValue(f, true)

            // Motorola Atrix
            f = File(
                "/sys/devices/platform/cpcap_battery/power_supply/usb/current_now"
            )
            if (f.exists()) return CurrentHackNormalFileReader.getValue(f, false)

            // Acer Iconia Tab A500
            f = File("/sys/EcControl/BatCurrent")
            if (f.exists()) return CurrentHackNormalFileReader.getValue(f, false)

            // charge current only, Samsung Note
            f = File("/sys/class/power_supply/battery/batt_current_now")
            if (f.exists()) return CurrentHackNormalFileReader.getValue(f, false)

            // galaxy note, galaxy s2
            f = File("/sys/class/power_supply/battery/batt_current_adc")
            if (f.exists()) return CurrentHackNormalFileReader.getValue(f, false)

            // intel
            f = File("/sys/class/power_supply/max170xx_battery/current_now")
            if (f.exists()) return CurrentHackNormalFileReader.getValue(f, true)

            // Sony Xperia U
            f = File("/sys/class/power_supply/ab8500_fg/current_now")
            if (f.exists()) return CurrentHackNormalFileReader.getValue(f, true)

            f = File("/sys/class/power_supply/android-battery/current_now")
            if (f.exists()) {
                return CurrentHackNormalFileReader.getValue(f, false)
            }

            // Nexus 10, 4.4.
            f = File("/sys/class/power_supply/ds2784-fuelgauge/current_now")
            if (f.exists()) {
                return CurrentHackNormalFileReader.getValue(f, true)
            }

            f = File("/sys/class/power_supply/Battery/current_now")
            if (f.exists()) {
                return CurrentHackNormalFileReader.getValue(f, false)
            }

            return null
        }

    private val fSAvgCurrent: Long?
        get() {
            var f: File?
            if (BUILD_MODEL.contains("nexus 7") || (BUILD_MODEL.contains("one") && !BUILD_MODEL.contains(
                    "nexus"
                )) || BUILD_MODEL.contains("lg-d851")
            ) {
                f = File("/sys/class/power_supply/battery/current_avg")
                if (f.exists()) {
                    return CurrentHackNormalFileReader.getValue(f, false)
                }
            }

            // Galaxy S4
            if (BUILD_MODEL.contains("sgh-i337") || BUILD_MODEL.contains("gt-i9505") || BUILD_MODEL.contains(
                    "gt-i9500"
                ) || BUILD_MODEL.contains("sch-i545") || BUILD_MODEL.contains("find 5") || BUILD_MODEL.contains(
                    "sgh-m919"
                ) || BUILD_MODEL.contains("sgh-i537")
            ) {
                f = File("/sys/class/power_supply/battery/current_avg")
                if (f.exists()) {
                    return CurrentHackNormalFileReader.getValue(f, false)
                }
            }

            // trimuph with cm7, lg ls670, galaxy s3, galaxy note 2
            if (BUILD_MODEL.contains("triumph") || BUILD_MODEL.contains("ls670") || BUILD_MODEL.contains(
                    "gt-i9300"
                ) || BUILD_MODEL.contains("sm-n9005") || BUILD_MODEL.contains("gt-n7100") || BUILD_MODEL.contains(
                    "sgh-i317"
                )
            ) {
                f = File("/sys/class/power_supply/battery/current_avg")
                if (f.exists()) {
                    return CurrentHackNormalFileReader.getValue(f, false)
                }
            }

            // sony ericsson xperia x1
            f =
                File("/sys/devices/platform/i2c-adapter/i2c-0/0-0036/power_supply/ds2746-battery/current_avg")
            if (f.exists()) {
                return CurrentHackNormalFileReader.getValue(f, false)
            }

            // xdandroid
            f =
                File("/sys/devices/platform/i2c-adapter/i2c-0/0-0036/power_supply/battery/current_avg")
            if (f.exists()) {
                return CurrentHackNormalFileReader.getValue(f, false)
            }

            // Nexus One.
            // TODO: Make this not default but specific for N1 because of the normalization.
            f = File("/sys/class/power_supply/battery/current_avg")
            if (f.exists()) {
                return CurrentHackNormalFileReader.getValue(f, true)
            }

            // Nook Color
            f = File("/sys/class/power_supply/max17042-0/current_avg")
            if (f.exists()) return CurrentHackNormalFileReader.getValue(f, false)

            // Xperia Arc
            f = File("/sys/class/power_supply/bq27520/current_avg")
            if (f.exists()) return CurrentHackNormalFileReader.getValue(f, true)

            // Motorola Atrix
            f = File(
                "/sys/devices/platform/cpcap_battery/power_supply/usb/current_avg"
            )
            if (f.exists()) return CurrentHackNormalFileReader.getValue(f, false)

            f = File("/sys/class/power_supply/max170xx_battery/current_avg")
            if (f.exists()) return CurrentHackNormalFileReader.getValue(f, true)

            // Sony Xperia U
            f = File("/sys/class/power_supply/ab8500_fg/current_avg")
            if (f.exists()) return CurrentHackNormalFileReader.getValue(f, true)

            f = File("/sys/class/power_supply/android-battery/current_avg")
            if (f.exists()) {
                return CurrentHackNormalFileReader.getValue(f, false)
            }

            // Nexus 10, 4.4.
            f = File("/sys/class/power_supply/ds2784-fuelgauge/current_avg")
            if (f.exists()) {
                return CurrentHackNormalFileReader.getValue(f, true)
            }

            f = File("/sys/class/power_supply/Battery/current_avg")
            if (f.exists()) {
                return CurrentHackNormalFileReader.getValue(f, false)
            }

            return null
        }

    private object CurrentHackNormalFileReader {
        fun getValue(f: File?, convertToMillis: Boolean): Long? {
            var line: String? = null
            var value: Long? = null

            try {
                val fReader = FileReader(f)
                val bReader = BufferedReader(fReader, 10)
                line = bReader.readLine()
                bReader.close()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error reading normal current hack file");
            }

            if (line != null) {
                try {
                    value = line.toLong()
                } catch (e: NumberFormatException) {
                    Log.e(LOG_TAG, "Error parsing normal current hack file");
                }

                if (value != null) value *= multiplier.toLong()

                if (convertToMillis && value != null) value /= 1000
            }

            return value
        }
    }

    private object CurrentHackBattAttrTextReader {
        fun getValue(f: File?, dischargeField: String, chargeField: String): Long? {
            var text: String?
            var value: Long? = null

            try {
                val fReader = FileReader(f)
                val bReader = BufferedReader(fReader)
                var line = bReader.readLine()

                val chargeFieldHead = "$chargeField: "
                val dischargeFieldHead = "$dischargeField: "

                while (line != null) {
                    if (line.contains(chargeField)) {
                        text =
                            line.substring(line.indexOf(chargeFieldHead) + chargeFieldHead.length)
                        try {
                            value = text.toLong()
                            if (value != 0L) break
                        } catch (e: NumberFormatException) {
                            Log.e(LOG_TAG, "Error parsing BattAttr current hack file");
                        }
                    }

                    if (line.contains(dischargeField)) {
                        text =
                            line.substring(line.indexOf(dischargeFieldHead) + dischargeFieldHead.length)
                        try {
                            value = (-1) * abs(text.toLong())
                        } catch (e: NumberFormatException) {
                            Log.e(LOG_TAG, "Error parsing BattAttr current hack file");
                        }

                        break
                    }

                    line = bReader.readLine()
                }

                bReader.close()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error reading BattAttr current hack file");
            }

            if (value != null) value *= multiplier.toLong()

            return value
        }
    }

    private object CurrentHackSMTextReader {
        val value: Long?
            get() {
                var success = false
                var text: String? = null
                var value: Long? = null

                try {
                    val fReader = FileReader("/sys/class/power_supply/battery/smem_text")
                    val bReader = BufferedReader(fReader)
                    var line = bReader.readLine()

                    while (line != null) {
                        if (line.contains("I_MBAT")) {
                            text = line.substring(line.indexOf("I_MBAT: ") + 8)
                            success = true
                            break
                        }

                        line = bReader.readLine()
                    }

                    bReader.close()
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error reading SMText current hack file");
                }

                if (success) {
                    try {
                        value = text!!.toLong()
                    } catch (e: NumberFormatException) {
                        Log.e(LOG_TAG, "Error parsing SMText current hack file");
                    }
                }

                if (value != null) value *= multiplier.toLong()

                return value
            }
    }
}
