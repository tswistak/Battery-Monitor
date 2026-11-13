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
package codes.swistak.batterymonitor.monitoring.charginglimit

import java.util.concurrent.Executor

internal const val CHARGING_LIMIT_CACHE_MS = 5_000L
internal const val CHARGING_LIMIT_MAX_AGE_MS = 30_000L

internal data class ChargingLimitAccess(
    val privileged: Boolean, val revision: Long, val changes: Long, val providerEpoch: Long = 0
)

internal data class ChargingLimitReading(
    val state: ChargingLimitState?,
    val generation: Long,
    val pendingRead: Long?,
    val completedRead: Long?
)

internal class ChargingLimitSnapshot(
    private val worker: Executor,
    private val deliver: (() -> Unit) -> Unit,
    private val clock: () -> Long,
    private val currentAccess: () -> ChargingLimitAccess?,
    private val read: (ChargingLimitAccess) -> ChargingLimitState,
    private val onTargetChanged: () -> Unit
) {
    private var access: ChargingLimitAccess? = null
    private var generation = 0L
    private var nextRead = 0L
    private var inFlight = false
    private var pendingRead: Long? = null
    private var completedRead: Long? = null
    private var refreshAt: Long? = null
    private var expiresAt = 0L
    private var state: ChargingLimitState? = null

    fun refresh(): ChargingLimitReading {
        val latest = currentAccess()
        val now = clock()
        if (latest != access) {
            access = latest
            generation++
            refreshAt = null
            state = null
            completedRead = null
            pendingRead = if (latest != null && inFlight) ++nextRead else null
        }
        if (now >= expiresAt) {
            state = null
            completedRead = null
        }
        if (latest != null && !inFlight && refreshAt?.let { now < it } != true) {
            val readId = pendingRead ?: (++nextRead).also { pendingRead = it }
            inFlight = true
            val request = generation
            worker.execute {
                val result = try {
                    read(latest)
                } catch (_: Exception) {
                    ChargingLimitState.Unavailable(ChargingLimitUnavailableReason.READ_FAILED)
                }
                deliver {
                    inFlight = false
                    if (request != generation || latest != currentAccess()) {
                        refresh()
                    } else {
                        pendingRead = null
                        val completedAt = clock()
                        val wasPending = state == null
                        val previousTarget = resolveDetectedChargingTarget(state)
                        expiresAt = now + CHARGING_LIMIT_MAX_AGE_MS
                        state = result.takeIf { completedAt < expiresAt }
                        val currentTarget = resolveDetectedChargingTarget(state)
                        completedRead = if (currentTarget.percent < 100) readId else null
                        if (completedRead == null) generation++
                        refreshAt = completedAt + CHARGING_LIMIT_CACHE_MS
                        if (wasPending || currentTarget != previousTarget) onTargetChanged()
                    }
                }
            }
        }
        return ChargingLimitReading(state, generation, pendingRead, completedRead)
    }
}
