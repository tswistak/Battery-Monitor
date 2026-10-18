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
package codes.swistak.batterymonitor.advancedstats

import codes.swistak.batterymonitor.monitoring.BatteryMeasurementTime

internal class MonitoringBatteryCapabilities(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    companion object {
        private const val RETRY_INTERVAL_MS = 60_000L
    }

    data class Topology(
        val supplies: List<String>,
        val metadata: Map<String, String>,
        val sourceReadTimes: Map<String, BatteryMeasurementTime>
    )

    private var topology: Topology? = null
    private var discoveredAtMillis: Long? = null
    private val readablePaths = mutableSetOf<String>()
    private val missingPaths = mutableMapOf<String, Long>()
    private val workingCommands = mutableSetOf<String>()
    private val missingCommands = mutableMapOf<String, Long>()

    fun topology(): Topology? = topology?.takeIf { withinRetry(discoveredAtMillis) }

    fun rememberTopology(value: Topology) {
        topology = value
        discoveredAtMillis = clock()
        readablePaths.clear()
        missingPaths.clear()
    }

    fun pathsToRead(paths: List<String>): List<String> =
        paths.filterNot { withinRetry(missingPaths[it]) }

    fun recordPaths(attempted: List<String>, values: Map<String, String>) {
        val now = clock()
        for (path in attempted) {
            if (path in values) {
                readablePaths += path
                missingPaths.remove(path)
            } else {
                if (readablePaths.remove(path)) {
                    // A disappearing working node can mean that the battery topology changed.
                    topology = null
                    discoveredAtMillis = null
                }
                missingPaths[path] = now
            }
        }
    }

    fun mayTryCommand(command: String): Boolean = !withinRetry(missingCommands[command])

    fun recordCommand(command: String, succeeded: Boolean) {
        if (succeeded) {
            workingCommands += command
            missingCommands.remove(command)
        } else {
            if (workingCommands.remove(command)) missingCommands.clear()
            missingCommands[command] = clock()
        }
    }

    fun invalidate() {
        topology = null
        discoveredAtMillis = null
        readablePaths.clear()
        missingPaths.clear()
        workingCommands.clear()
        missingCommands.clear()
    }

    private fun withinRetry(recordedAt: Long?): Boolean =
        recordedAt != null && clock() - recordedAt in 0 until RETRY_INTERVAL_MS
}
