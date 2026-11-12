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
    private var inFlight = false
    private var refreshAt: Long? = null
    private var expiresAt = 0L
    private var state: ChargingLimitState? = null

    fun refresh(): ChargingLimitState? {
        val latest = currentAccess()
        if (latest != access) {
            access = latest
            generation++
            refreshAt = null
            state = null
        }
        if (clock() >= expiresAt) state = null
        if (latest != null && !inFlight && refreshAt?.let { clock() < it } != true) {
            inFlight = true
            val request = generation
            val startedAt = clock()
            worker.execute {
                val result = try {
                    read(latest)
                } catch (_: Exception) {
                    null
                }
                deliver {
                    inFlight = false
                    if (request != generation || latest != currentAccess()) {
                        refresh()
                    } else {
                        val previousTarget = resolveDetectedChargingTarget(state)
                        expiresAt = startedAt + CHARGING_LIMIT_MAX_AGE_MS
                        state = result.takeIf { clock() < expiresAt }
                        refreshAt = clock() + CHARGING_LIMIT_CACHE_MS
                        if (resolveDetectedChargingTarget(state) != previousTarget) onTargetChanged()
                    }
                }
            }
        }
        return state
    }
}
