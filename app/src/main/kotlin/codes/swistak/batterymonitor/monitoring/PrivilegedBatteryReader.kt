/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.monitoring

import android.os.SystemClock
import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import codes.swistak.batterymonitor.advancedstats.AdvancedBatteryStatsCollector
import codes.swistak.batterymonitor.monitoring.batteryvoltage.BatteryVoltageValidator
import codes.swistak.batterymonitor.privileged.PrivilegedAccess
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal data class PrivilegedBatteryReading(
    val percent: Int? = null,
    val status: Int? = null,
    val health: Int? = null,
    val plugged: Int? = null,
    val temperatureTenthsC: Int? = null,
    val voltageMillivolts: Int? = null,
    val remainingChargeUah: Long? = null,
    val currentNowUa: Long? = null,
    val currentAverageUa: Long? = null
)

internal fun projectPrivilegedBatteryReading(snapshot: AdvancedBatterySnapshot): PrivilegedBatteryReading {
    val service = snapshot.serviceLabels.zip(snapshot.serviceValues).toMap()
    val sysfs = snapshot.sysfsLabels.zip(snapshot.sysfsValues).toMap()
    val supplies = AdvancedBatteryStatsCollector.batterySupplyNames(sysfs)
    fun <T : Any> batteryField(key: String, parse: (String) -> T?): T? =
        supplies.firstNotNullOfOrNull { supply -> sysfs["$supply/$key"]?.let(parse) }

    fun percent(raw: String?): Int? = raw?.trim()?.toIntOrNull()?.takeIf { it in 0..100 }
    fun temperature(raw: String?): Int? = raw?.trim()?.toIntOrNull()?.takeIf { it in -1000..2000 }
    fun charge(raw: String?): Long? = raw?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
    fun current(raw: String?): Long? =
        raw?.trim()?.toLongOrNull()?.takeIf(BatteryCurrent::isValidMicroAmps)

    val level = service["level"]?.trim()?.toLongOrNull()
    val scale = service["scale"]?.trim()?.toLongOrNull()
    val servicePercent = if (level != null && scale != null && scale > 0 && level in 0..scale) {
        (level.toDouble() * 100 / scale).toInt()
    } else null
    val serviceStatus = service["status"]?.trim()?.toIntOrNull()?.takeIf { it in 1..5 }
    val serviceHealth = service["health"]?.trim()?.toIntOrNull()?.takeIf { it in 1..7 }
    val sysfsStatus = batteryField("status") {
        when (it.trim().lowercase(Locale.ROOT)) {
            "charging" -> BatteryInfo.STATUS_CHARGING
            "discharging" -> BatteryInfo.STATUS_DISCHARGING
            "not charging" -> BatteryInfo.STATUS_NOT_CHARGING
            "full" -> BatteryInfo.STATUS_FULLY_CHARGED
            "unknown" -> BatteryInfo.STATUS_UNKNOWN
            else -> null
        }
    }
    val sysfsHealth = batteryField("health") {
        when (it.trim().lowercase(Locale.ROOT)) {
            "good" -> 2
            "overheat" -> 3
            "dead" -> 4
            "over voltage", "overvoltage" -> 5
            "unspecified failure" -> 6
            "cold" -> 7
            "unknown" -> 1
            else -> null
        }
    }
    val powerSources =
        listOf("AC powered" to 1, "USB powered" to 2, "Wireless powered" to 4, "Dock powered" to 8)
    val plugged = service["plugged"]?.trim()?.toIntOrNull()?.takeIf { it in setOf(0, 1, 2, 4) }
        ?: powerSources.firstOrNull {
            service[it.first]?.trim().equals("true", ignoreCase = true)
        }?.second?.takeIf { it <= BatteryInfo.PLUGGED_MAX } ?: 0.takeIf {
            powerSources.take(3).all {
                service[it.first]?.trim().equals("false", ignoreCase = true)
            } && (service["Dock powered"] == null || service["Dock powered"]?.trim()
                .equals("false", ignoreCase = true))
        }
    val chargeSource = snapshot.fieldSources["charge_counter"].orEmpty()
    val confirmedChargeSource =
        chargeSource.startsWith("BatteryManager /") || chargeSource.startsWith("cmd battery get ") || chargeSource.startsWith(
            "/sys/class/power_supply/"
        )
    // Battery-service dumps can report zero when the charge counter is unsupported.
    val remainingCharge =
        snapshot.chargeCounterUah?.takeIf { it > 0 || it == 0L && confirmedChargeSource }
            ?: batteryField("charge_counter", ::charge)

    return PrivilegedBatteryReading(percent = snapshot.reportedCapacityPercent?.takeIf { it in 0..100 }
        ?: servicePercent ?: batteryField("capacity", ::percent),
        status = serviceStatus?.takeUnless { it == BatteryInfo.STATUS_UNKNOWN }
            ?: sysfsStatus?.takeUnless { it == BatteryInfo.STATUS_UNKNOWN } ?: serviceStatus
            ?: sysfsStatus,
        health = serviceHealth?.takeUnless { it == BatteryInfo.HEALTH_UNKNOWN }
            ?: sysfsHealth?.takeUnless { it == BatteryInfo.HEALTH_UNKNOWN } ?: serviceHealth
            ?: sysfsHealth,
        plugged = plugged,
        temperatureTenthsC = temperature(service["temperature"]) ?: batteryField(
            "temp", ::temperature
        ),
        voltageMillivolts = service["voltage"]?.trim()?.toIntOrNull()
            ?.takeIf(BatteryVoltageValidator::isValidBroadcastMillivolts)
            ?: batteryField("voltage_now", BatteryVoltageValidator::normalizeSysfsVoltage),
        remainingChargeUah = remainingCharge,
        currentNowUa = snapshot.currentNowUa?.takeIf(BatteryCurrent::isValidMicroAmps)
            ?: batteryField("current_now", ::current),
        currentAverageUa = snapshot.currentAverageUa?.takeIf(BatteryCurrent::isValidMicroAmps)
            ?: batteryField("current_avg", ::current))
}

internal class PrivilegedBatteryReader(
    private val collector: () -> AdvancedBatterySnapshot? = PrivilegedAccess::readBatterySnapshot,
    private val enabled: () -> Boolean = PrivilegedAccess::isEnabled,
    private val accessRevision: () -> Long = { PrivilegedAccess.accessRevision },
    private val backgroundExecutor: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PrivilegedBatteryReader").apply { isDaemon = true }
    },
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    companion object {
        val shared by lazy { PrivilegedBatteryReader() }
        private const val REFRESH_INTERVAL_MS = 2_000L
        private const val CACHE_TTL_MS = 10_000L
    }

    private val lock = Any()
    private var revision: Long? = null
    private var cache: PrivilegedBatteryReading? = null
    private var cachedAtMillis = 0L
    private var lastRefreshAtMillis: Long? = null
    private var refreshInFlight = false
    private var requestId = 0L
    private val waiters = mutableListOf<(PrivilegedBatteryReading?) -> Unit>()

    fun read(): PrivilegedBatteryReading? = readInternal(null)

    fun readWhenReady(onResult: (PrivilegedBatteryReading?) -> Unit) {
        readInternal(onResult)
    }

    private fun readInternal(onResult: ((PrivilegedBatteryReading?) -> Unit)?): PrivilegedBatteryReading? {
        var request: Pair<Long, Long>? = null
        var revoked = emptyList<(PrivilegedBatteryReading?) -> Unit>()
        var immediate = false
        var readingRevision = 0L
        val reading = synchronized(lock) {
            val currentRevision = accessRevision()
            readingRevision = currentRevision
            if (revision != currentRevision || !enabled()) revoked = invalidate(currentRevision)
            if (!enabled()) {
                immediate = onResult != null
                null
            } else {
                val now = clock()
                if (now - cachedAtMillis !in 0..CACHE_TTL_MS) cache = null
                if (onResult != null) {
                    if (cache == null) waiters += onResult else immediate = true
                }
                val lastRefresh = lastRefreshAtMillis
                val mustResolve = onResult != null && cache == null
                if (!refreshInFlight && (mustResolve || lastRefresh == null || now - lastRefresh >= REFRESH_INTERVAL_MS)) {
                    refreshInFlight = true
                    lastRefreshAtMillis = now
                    request = ++requestId to currentRevision
                }
                cache
            }
        }
        deliver(revoked, null)
        if (immediate && onResult != null) deliver(listOf(onResult), reading, readingRevision)
        request?.let { (id, currentRevision) ->
            try {
                backgroundExecutor.execute { refresh(id, currentRevision) }
            } catch (_: RuntimeException) {
                val rejected = synchronized(lock) {
                    if (requestId != id) emptyList() else {
                        refreshInFlight = false
                        takeWaiters()
                    }
                }
                deliver(rejected, null)
            }
        }
        return reading?.takeIf { enabled() && accessRevision() == readingRevision }
    }

    private fun invalidate(currentRevision: Long): List<(PrivilegedBatteryReading?) -> Unit> {
        revision = currentRevision
        cache = null
        lastRefreshAtMillis = null
        refreshInFlight = false
        requestId++
        return takeWaiters()
    }

    private fun takeWaiters(): List<(PrivilegedBatteryReading?) -> Unit> =
        waiters.toList().also { waiters.clear() }

    private fun deliver(
        callbacks: List<(PrivilegedBatteryReading?) -> Unit>,
        reading: PrivilegedBatteryReading?,
        readingRevision: Long? = null
    ) {
        for (callback in callbacks) {
            val available = reading?.takeIf {
                readingRevision == null || enabled() && accessRevision() == readingRevision
            }
            try {
                callback(available)
            } catch (_: RuntimeException) {
            }
        }
    }

    private fun refresh(id: Long, currentRevision: Long) {
        val allowed = synchronized(lock) {
            requestId == id && enabled() && accessRevision() == currentRevision
        }
        val collectedAtMillis = clock()
        val reading = if (allowed) {
            try {
                collector()?.let(::projectPrivilegedBatteryReading)
            } catch (_: Exception) {
                null
            }
        } else null
        var callbacks = emptyList<(PrivilegedBatteryReading?) -> Unit>()
        var result: PrivilegedBatteryReading? = null
        synchronized(lock) {
            if (requestId != id) return@synchronized
            refreshInFlight = false
            if (!enabled() || accessRevision() != currentRevision) {
                callbacks = invalidate(accessRevision())
                return@synchronized
            }
            val now = clock()
            lastRefreshAtMillis = now
            if (now - collectedAtMillis in 0..CACHE_TTL_MS && reading != null && reading != PrivilegedBatteryReading()) {
                cache = reading
                cachedAtMillis = collectedAtMillis
                result = reading
            }
            callbacks = takeWaiters()
        }
        deliver(callbacks, result, currentRevision)
    }
}
