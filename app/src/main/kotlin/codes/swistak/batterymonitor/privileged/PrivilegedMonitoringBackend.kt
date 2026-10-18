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

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>.
*/
package codes.swistak.batterymonitor.privileged

import android.content.Context
import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import codes.swistak.batterymonitor.advancedstats.AdvancedBatteryStatsCollector
import codes.swistak.batterymonitor.advancedstats.MonitoringBatteryCapabilities
import codes.swistak.batterymonitor.common.CommandExecutor
import codes.swistak.batterymonitor.monitoring.BatteryMeasurementTime
import codes.swistak.batterymonitor.monitoring.projectPrivilegedBatteryReading

internal class PrivilegedMonitoringBackend(
    private val requireRoot: Boolean,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val measurementTime: () -> BatteryMeasurementTime = { BatteryMeasurementTime.now() }
) {
    companion object {
        private const val ROOT_IDENTITY_TTL_MS = 60_000L
        private const val ROOT_RETRY_INTERVAL_MS = 5_000L
    }

    private val capabilities = MonitoringBatteryCapabilities(clock)
    private var revision: Long? = null
    private var ownerPid: Int? = null
    private var rootVerifiedAtMillis: Long? = null
    private var rootDeniedAtMillis: Long? = null

    @Synchronized
    fun read(
        executor: CommandExecutor,
        accessRevision: Long,
        allowed: () -> Boolean,
        context: Context? = null,
        callingPid: Int = 0
    ): AdvancedBatterySnapshot? {
        if (!allowed()) {
            invalidate()
            return null
        }
        if (revision != accessRevision || ownerPid != callingPid) {
            invalidate()
            revision = accessRevision
            ownerPid = callingPid
        }
        val guarded = GuardedPrivilegedCommandExecutor(executor, allowed)
        try {
            if (requireRoot && !rootAvailable(guarded)) return null
            var backendFailed = false
            val monitoringExecutor = object : CommandExecutor {
                override fun run(command: String): String? = guarded.run(command)

                override fun runRaw(command: String): String? = guarded.runRaw(command).also {
                    if (command == "dumpsys battery" && it == null) backendFailed = true
                }
            }
            val snapshot = AdvancedBatteryStatsCollector.collectMonitoring(
                monitoringExecutor, context, measurementTime, capabilities
            )
            if (backendFailed) invalidate()
            return snapshot.takeIf {
                allowed() && projectPrivilegedBatteryReading(it).hasMeasurements()
            }
        } catch (error: Throwable) {
            invalidate()
            throw error
        } finally {
            if (!allowed()) invalidate()
        }
    }

    private fun rootAvailable(executor: CommandExecutor): Boolean {
        val now = clock()
        rootVerifiedAtMillis?.let {
            if (now - it in 0 until ROOT_IDENTITY_TTL_MS) return true
        }
        rootDeniedAtMillis?.let {
            if (now - it in 0 until ROOT_RETRY_INTERVAL_MS) return false
        }
        return (executor.run("id")?.contains("uid=0") == true).also { available ->
            if (available) {
                rootVerifiedAtMillis = clock()
                rootDeniedAtMillis = null
            } else {
                capabilities.invalidate()
                rootVerifiedAtMillis = null
                rootDeniedAtMillis = clock()
            }
        }
    }

    private fun invalidate() {
        capabilities.invalidate()
        revision = null
        ownerPid = null
        rootVerifiedAtMillis = null
        rootDeniedAtMillis = null
    }
}
