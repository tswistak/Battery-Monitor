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
import codes.swistak.batterymonitor.monitoring.batteryvoltage.BatteryVoltageValidator
import java.io.File
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

internal object BatteryCurrent {
    private const val LOG_TAG = "codes.swistak.batterymonitor - BatteryCurrent"
    private const val SYSFS_ROOT = "/sys/class/power_supply"
    private const val MAX_DISPLAY_DIGITS = 6
    private const val MAX_DECIMAL_PLACES = 3

    private var batteryManager: BatteryManager? = null
    private var multiplier = 1
    private var currentNowFile: File? = null
    private var currentAverageFile: File? = null

    fun setContext(context: Context) {
        val appContext = context.applicationContext
        batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    }

    fun setMultiplier(value: Int) {
        multiplier = value
    }

    val current: Double?
        get() = read(false)

    val avgCurrent: Double?
        get() = read(true)

    val rawCurrentMicroAmps: Long?
        get() = readMicroAmps(average = false)

    private fun read(average: Boolean): Double? = readMicroAmps(average)?.let { scaleMicroAmps(it) }

    private fun readMicroAmps(average: Boolean): Long? =
        readAndroidSystem(average) ?: readFileSystem(average)
        ?: PrivilegedBatteryReader.shared.read()
            ?.let { if (average) it.currentAverageUa else it.currentNowUa }

    internal fun readForMultiplierDetection(average: Boolean, onResult: (Double?) -> Unit) {
        val direct = readAndroidSystem(average) ?: readFileSystem(average)
        if (direct != null) {
            onResult(scaleMicroAmps(direct, appliedMultiplier = 1))
        } else PrivilegedBatteryReader.shared.readWhenReady { reading ->
            val raw = if (average) reading?.currentAverageUa else reading?.currentNowUa
            onResult(raw?.let { scaleMicroAmps(it, appliedMultiplier = 1) })
        }
    }

    private fun readAndroidSystem(average: Boolean): Long? {
        val manager = batteryManager ?: return null
        val property = if (average) {
            BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE
        } else {
            BatteryManager.BATTERY_PROPERTY_CURRENT_NOW
        }
        return try {
            manager.getIntProperty(property).takeUnless { it == Int.MIN_VALUE }?.toLong()
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "Unable to read Android battery current", error)
            null
        }
    }

    private fun readFileSystem(average: Boolean): Long? {
        val cachedFile = if (average) currentAverageFile else currentNowFile
        val cachedValue = cachedFile?.let(::readLong)
        if (cachedValue != null) return cachedValue

        if (average) currentAverageFile = null else currentNowFile = null
        val discoveredFile = findCurrentFile(File(SYSFS_ROOT), average) ?: return null
        val microAmps = readLong(discoveredFile) ?: return null
        if (average) {
            currentAverageFile = discoveredFile
        } else {
            currentNowFile = discoveredFile
        }

        return microAmps
    }


    internal fun findCurrentFile(root: File, average: Boolean): File? {
        val fileName = if (average) "current_avg" else "current_now"
        val directories = try {
            root.listFiles()?.filter(File::isDirectory).orEmpty()
        } catch (e: SecurityException) {
            Log.d(LOG_TAG, "Unable to enumerate ${root.path}", e)
            emptyList()
        }

        return directories.asSequence().mapNotNull { directory ->
            val rank = batterySupplyRank(directory) ?: return@mapNotNull null
            val currentFile = File(directory, fileName)
            if (readLong(currentFile) == null) return@mapNotNull null
            RankedCurrentFile(currentFile, rank, directory.name.lowercase())
        }.sortedWith(compareBy<RankedCurrentFile> { it.rank }.thenBy { it.supplyName })
            .map { it.file }.firstOrNull()
    }

    private fun batterySupplyRank(directory: File): Int? {
        val declaredType =
            readText(File(directory, "type")) ?: readPowerSupplyType(File(directory, "uevent"))
        return batterySupplyRank(directory.name, declaredType)
    }

    internal fun batterySupplyRank(name: String, declaredType: String?): Int? {
        val supplyName = name.lowercase(Locale.ROOT)
        val hasBatteryType = declaredType.equals("Battery", ignoreCase = true)
        val hasFuelGaugeType = declaredType.equals("BMS", ignoreCase = true) || declaredType.equals(
            "Unknown", ignoreCase = true
        )
        if (declaredType != null && !hasBatteryType && !hasFuelGaugeType) return null

        return when {
            supplyName == "battery" -> 0
            hasBatteryType -> 1
            supplyName == "bms" -> 2
            "battery" in supplyName -> 3
            "fuelgauge" in supplyName || "fuel-gauge" in supplyName -> 4
            supplyName.endsWith("_fg") || supplyName.endsWith("-fg") -> 5
            else -> null
        }
    }

    private fun readPowerSupplyType(ueventFile: File): String? {
        val uevent = readText(ueventFile) ?: return null
        return uevent.lineSequence().firstOrNull { it.startsWith("POWER_SUPPLY_TYPE=") }
            ?.substringAfter('=')?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun readText(file: File): String? {
        return try {
            if (!file.isFile || !file.canRead()) return null
            file.readText().trim().takeIf(String::isNotEmpty)
        } catch (_: Exception) {
            null
        }
    }

    private fun readLong(file: File): Long? {
        return try {
            if (!file.isFile || !file.canRead()) return null
            file.bufferedReader()
                .use { it.readLine()?.trim()?.toLongOrNull()?.takeIf(::isValidMicroAmps) }
        } catch (e: Exception) {
            Log.d(LOG_TAG, "Unable to read battery current from ${file.path}", e)
            null
        }
    }

    internal fun isValidMicroAmps(value: Long): Boolean =
        value != Int.MIN_VALUE.toLong() && value != Long.MIN_VALUE

    internal fun scaleMicroAmps(
        microAmps: Long, appliedMultiplier: Int = multiplier
    ): Double {
        return microAmps.toDouble() * appliedMultiplier.toDouble() / 1000.0
    }

    internal fun powerWatts(millivolts: Int?, milliAmps: Double?): Double? {
        if (millivolts == null || !BatteryVoltageValidator.isValidBroadcastMillivolts(millivolts) || milliAmps == null || !milliAmps.isFinite()) return null
        return (millivolts.toDouble() * milliAmps / 1_000_000.0).takeIf { it.isFinite() }
    }

    internal fun formatMilliAmps(
        milliAmps: Double, locale: Locale = Locale.getDefault()
    ): String {
        val absoluteValue = abs(milliAmps)
        val integerDigits = if (absoluteValue < 1.0) 1 else floor(log10(absoluteValue)).toInt() + 1
        val decimalPlaces = (MAX_DISPLAY_DIGITS - integerDigits).coerceIn(0, MAX_DECIMAL_PLACES)
        return DecimalFormat("0", DecimalFormatSymbols.getInstance(locale)).apply {
            isGroupingUsed = false
            minimumFractionDigits = 0
            maximumFractionDigits = decimalPlaces
            roundingMode = RoundingMode.HALF_UP
        }.format(if (milliAmps == 0.0) 0.0 else milliAmps)
    }

    private data class RankedCurrentFile(
        val file: File, val rank: Int, val supplyName: String
    )
}
