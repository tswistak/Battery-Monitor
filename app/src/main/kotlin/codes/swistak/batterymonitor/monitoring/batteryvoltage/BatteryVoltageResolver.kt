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
package codes.swistak.batterymonitor.monitoring.batteryvoltage

import android.util.Log
import codes.swistak.batterymonitor.common.DirectSysfsAccessor
import codes.swistak.batterymonitor.common.SysfsAccessor

internal data class BatteryVoltageReading(
    val millivolts: Int, val source: BatteryVoltageSource, val sourceDetail: String
)

internal enum class BatteryVoltageSource {
    BROADCAST, SYSFS_DIRECT
}

private val PREFERRED_BATTERY_SUPPLY_NAMES = listOf("battery", "bms")
private const val VOLTAGE_NOW_FILE_NAME = "voltage_now"

internal fun discoverBatteryVoltage(
    accessor: SysfsAccessor, root: String = BatteryVoltageResolver.SYSFS_ROOT
): Pair<String, Int>? {
    for (name in PREFERRED_BATTERY_SUPPLY_NAMES) {
        val path = "$root/$name/$VOLTAGE_NOW_FILE_NAME"
        BatteryVoltageValidator.normalizeSysfsVoltage(accessor.read(path))?.let {
            return path to it
        }
    }

    for (name in accessor.list(root).asSequence().filter(::isValidSupplyName).sorted()) {
        if (name in PREFERRED_BATTERY_SUPPLY_NAMES) continue
        val supplyDir = "$root/$name"
        if (!isBatterySupply(accessor, supplyDir)) continue
        val path = "$supplyDir/$VOLTAGE_NOW_FILE_NAME"
        BatteryVoltageValidator.normalizeSysfsVoltage(accessor.read(path))?.let {
            return path to it
        }
    }
    return null
}

private fun isBatterySupply(accessor: SysfsAccessor, supplyDir: String): Boolean {
    val type = accessor.read("$supplyDir/type")?.trim() ?: return false
    return type.equals("Battery", ignoreCase = true)
}

private fun isValidSupplyName(name: String): Boolean {
    if (name.isEmpty() || name.indexOf('/') >= 0) return false
    for (character in name) {
        if (!(Character.isLetterOrDigit(character) || character == '_' || character == '-' || character == '.')) return false
    }
    return true
}

internal class BatteryVoltageResolver(
    private val directSysfs: SysfsAccessor = DirectSysfsAccessor(),
    private val logger: (Int, String, String) -> Unit = { priority, tag, message ->
        Log.println(priority, tag, message)
    }
) {
    companion object {
        private const val LOG_TAG = "BatteryVoltageResolver"
        const val SYSFS_ROOT: String = "/sys/class/power_supply"
    }

    private var directSysfsPath: String? = null
    private var lastResolvedSource: BatteryVoltageSource? = null
    private var lastLoggedInvalidBroadcast: Int? = null

    fun resolve(rawBroadcastMillivolts: Int): BatteryVoltageReading? {
        if (BatteryVoltageValidator.isValidBroadcastMillivolts(rawBroadcastMillivolts)) {
            logSource(BatteryVoltageSource.BROADCAST)
            return BatteryVoltageReading(
                rawBroadcastMillivolts,
                BatteryVoltageSource.BROADCAST,
                "ACTION_BATTERY_CHANGED / voltage"
            )
        }

        if (rawBroadcastMillivolts != lastLoggedInvalidBroadcast) {
            lastLoggedInvalidBroadcast = rawBroadcastMillivolts
            logger(
                Log.INFO,
                LOG_TAG,
                "Battery voltage from broadcast rejected: $rawBroadcastMillivolts mV"
            )
        }

        return readDirectSysfs().also { logSource(it?.source) }
    }

    private fun readDirectSysfs(): BatteryVoltageReading? {
        val cachedPath = directSysfsPath
        if (cachedPath != null) {
            BatteryVoltageValidator.normalizeSysfsVoltage(directSysfs.read(cachedPath))?.let {
                return BatteryVoltageReading(it, BatteryVoltageSource.SYSFS_DIRECT, cachedPath)
            }
            directSysfsPath = null
            logger(
                Log.DEBUG,
                LOG_TAG,
                "Cached direct sysfs voltage path is no longer usable: $cachedPath"
            )
        }

        val discovered = discoverBatteryVoltage(directSysfs) ?: return null
        directSysfsPath = discovered.first
        logger(
            Log.INFO,
            LOG_TAG,
            "Using battery voltage fallback: SYSFS_DIRECT, path=${discovered.first}"
        )
        return BatteryVoltageReading(
            discovered.second, BatteryVoltageSource.SYSFS_DIRECT, discovered.first
        )
    }

    private fun logSource(source: BatteryVoltageSource?) {
        if (source == lastResolvedSource) return
        lastResolvedSource = source
        if (source == null) {
            logger(Log.INFO, LOG_TAG, "No valid battery voltage source available")
        } else {
            logger(Log.INFO, LOG_TAG, "Battery voltage source changed: $source")
        }
    }
}
