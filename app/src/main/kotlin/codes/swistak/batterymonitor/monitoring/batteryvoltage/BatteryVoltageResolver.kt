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

import android.os.SystemClock
import android.util.Log
import codes.swistak.batterymonitor.common.CommandExecutor
import codes.swistak.batterymonitor.common.CommandSysfsAccessor
import codes.swistak.batterymonitor.common.DirectSysfsAccessor
import codes.swistak.batterymonitor.common.SysfsAccessor
import codes.swistak.batterymonitor.privileged.PrivilegedAccess
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal data class BatteryVoltageReading(
    val millivolts: Int, val source: BatteryVoltageSource
)

internal enum class BatteryVoltageSource {
    BROADCAST, SYSFS_DIRECT, SYSFS_PRIVILEGED, DUMPSYS_PRIVILEGED
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
    private val privilegedExecutor: CommandExecutor = PrivilegedAccess,
    private val privilegedEnabled: () -> Boolean = { PrivilegedAccess.isEnabled() },
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BatteryVoltageResolver").apply { isDaemon = true }
    },
    private val onPrivilegedRefresh: (BatteryVoltageReading?) -> Unit = {},
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val logger: (Int, String, String) -> Unit = { priority, tag, message ->
        Log.println(priority, tag, message)
    }
) {
    companion object {
        private const val LOG_TAG = "BatteryVoltageResolver"
        const val SYSFS_ROOT: String = "/sys/class/power_supply"
        private const val PRIVILEGED_REFRESH_THROTTLE_MS = 30_000L
        private const val PRIVILEGED_CACHE_TTL_MS = 5 * 60_000L
    }

    private var directSysfsPath: String? = null
    private var privilegedSysfsPath: String? = null

    @Volatile
    private var cachedPrivilegedReading: BatteryVoltageReading? = null

    @Volatile
    private var cachedPrivilegedAtMs = 0L

    private var lastPrivilegedProbeAtMs = -PRIVILEGED_REFRESH_THROTTLE_MS
    private var lastResolvedSource: BatteryVoltageSource? = null
    private var lastLoggedInvalidBroadcast: Int? = null

    @Volatile
    private var privilegedProbeInFlight = false

    @Volatile
    private var shutdownRequested = false

    fun resolve(rawBroadcastMillivolts: Int): BatteryVoltageReading? {
        if (BatteryVoltageValidator.isValidBroadcastMillivolts(rawBroadcastMillivolts)) {
            logSource(BatteryVoltageSource.BROADCAST)
            return BatteryVoltageReading(rawBroadcastMillivolts, BatteryVoltageSource.BROADCAST)
        }

        if (rawBroadcastMillivolts != lastLoggedInvalidBroadcast) {
            lastLoggedInvalidBroadcast = rawBroadcastMillivolts
            logger(
                Log.INFO,
                LOG_TAG,
                "Battery voltage from broadcast rejected: $rawBroadcastMillivolts mV"
            )
        }

        readDirectSysfs()?.let {
            logSource(it.source)
            return it
        }

        freshCachedPrivileged()?.let {
            logSource(it.source)
            return it
        }

        schedulePrivilegedRefresh()
        logSource(null)
        return null
    }

    fun shutdown() {
        shutdownRequested = true
        backgroundExecutor.shutdownNow()
    }

    private fun readDirectSysfs(): BatteryVoltageReading? {
        val cachedPath = directSysfsPath
        if (cachedPath != null) {
            BatteryVoltageValidator.normalizeSysfsVoltage(directSysfs.read(cachedPath))?.let {
                return BatteryVoltageReading(it, BatteryVoltageSource.SYSFS_DIRECT)
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
        return BatteryVoltageReading(discovered.second, BatteryVoltageSource.SYSFS_DIRECT)
    }

    private fun freshCachedPrivileged(): BatteryVoltageReading? {
        val cached = cachedPrivilegedReading ?: return null
        if (clock() - cachedPrivilegedAtMs > PRIVILEGED_CACHE_TTL_MS) {
            cachedPrivilegedReading = null
            return null
        }
        return cached
    }

    private fun schedulePrivilegedRefresh() {
        if (!privilegedEnabled() || shutdownRequested) return
        if (privilegedProbeInFlight) return
        val nowMs = clock()
        if (nowMs - lastPrivilegedProbeAtMs < PRIVILEGED_REFRESH_THROTTLE_MS) return

        lastPrivilegedProbeAtMs = nowMs
        privilegedProbeInFlight = true
        try {
            backgroundExecutor.execute { runPrivilegedProbe() }
        } catch (error: RuntimeException) {
            privilegedProbeInFlight = false
            logger(Log.WARN, LOG_TAG, "Unable to schedule privileged voltage probe: $error")
        }
    }

    private fun runPrivilegedProbe() {
        if (shutdownRequested) {
            privilegedProbeInFlight = false
            return
        }
        val reading = probePrivilegedSources()
        if (shutdownRequested) {
            privilegedProbeInFlight = false
            return
        }
        privilegedProbeInFlight = false
        if (reading == null) return

        cachedPrivilegedReading = reading
        cachedPrivilegedAtMs = clock()
        try {
            onPrivilegedRefresh(reading)
        } catch (error: RuntimeException) {
            logger(Log.WARN, LOG_TAG, "Privileged voltage refresh listener failed: $error")
        }
    }

    private fun probePrivilegedSources(): BatteryVoltageReading? {
        readPrivilegedSysfs()?.let { reading ->
            logger(
                Log.INFO,
                LOG_TAG,
                "Using battery voltage fallback: SYSFS_PRIVILEGED, path=$privilegedSysfsPath"
            )
            return reading
        }

        val millivolts =
            BatteryVoltageValidator.parseDumpsysVoltageMillivolts(privilegedExecutor.run("dumpsys battery"))
        if (millivolts != null) {
            logger(Log.INFO, LOG_TAG, "Using battery voltage fallback: DUMPSYS_PRIVILEGED")
            return BatteryVoltageReading(millivolts, BatteryVoltageSource.DUMPSYS_PRIVILEGED)
        }
        return null
    }

    private fun readPrivilegedSysfs(): BatteryVoltageReading? {
        val accessor = CommandSysfsAccessor(privilegedExecutor)
        val cachedPath = privilegedSysfsPath
        if (cachedPath != null) {
            BatteryVoltageValidator.normalizeSysfsVoltage(accessor.read(cachedPath))?.let {
                return BatteryVoltageReading(it, BatteryVoltageSource.SYSFS_PRIVILEGED)
            }
            privilegedSysfsPath = null
        }

        val discovered = discoverBatteryVoltage(accessor) ?: return null
        privilegedSysfsPath = discovered.first
        return BatteryVoltageReading(discovered.second, BatteryVoltageSource.SYSFS_PRIVILEGED)
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
