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
package codes.swistak.batterymonitor.profiling

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import codes.swistak.batterymonitor.advancedstats.AdvancedBatteryStatsCollector
import codes.swistak.batterymonitor.advancedstats.MonitoringBatteryCapabilities
import codes.swistak.batterymonitor.common.CommandExecutor
import codes.swistak.batterymonitor.common.RootExecutor
import codes.swistak.batterymonitor.privileged.PrivilegedAccess
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object PrivilegedProfilingProbe {
    private const val REPETITIONS = 5
    private const val SYSFS_DISCOVERY_COMMAND = "ls /sys/class/power_supply 2>/dev/null"
    private const val SYNTHETIC_SUPPLY = "profile_missing_battery"
    private const val ACQUISITION_BUDGET_NS = 50_000_000_000L
    private const val COMMAND_RESERVE_NS = 12_000_000_000L

    fun measureRootMonitoring(context: Context): Bundle {
        val previouslyEnabled = PrivilegedAccess.isEnabled()
        PrivilegedAccess.setEnabled(true)
        return try {
            measureEnabledRootMonitoring(context)
        } finally {
            PrivilegedAccess.setEnabled(previouslyEnabled)
        }
    }

    private fun measureEnabledRootMonitoring(context: Context): Bundle {
        val deadline = SystemClock.elapsedRealtimeNanos() + ACQUISITION_BUDGET_NS
        val repetitions = JSONArray()
        var completedRepetitions = 0
        var rootAvailable = false
        var timedOut = false
        for (index in 0 until REPETITIONS) {
            val executor = TimedExecutor(
                RootExecutor(), mayStartCommand = {
                    deadline - SystemClock.elapsedRealtimeNanos() >= COMMAND_RESERVE_NS
                })
            val started = SystemClock.elapsedRealtimeNanos()
            var hasRoot = false
            var failure: String? = null
            val snapshot = try {
                val reading = PrivilegedAccess.readRootBatterySnapshot(executor)
                hasRoot = reading?.remoteUid == 0
                reading
            } catch (_: ProbeDeadlineExceeded) {
                timedOut = true
                failure = "probe_deadline"
                null
            } catch (error: Exception) {
                failure = error.javaClass.simpleName
                null
            }
            val finished = SystemClock.elapsedRealtimeNanos()
            hasRoot = hasRoot || executor.rootIdentityAccepted
            rootAvailable = rootAvailable || hasRoot
            if (snapshot?.hasMonitoringReading() == true) completedRepetitions++
            val identityDuration =
                executor.commands.filter { it.stage == "root_identity" }.sumOf { it.durationNanos }
            val collectorCommands = executor.commands.filter { it.stage != "root_identity" }
            val collectorCommandDuration = collectorCommands.sumOf { it.durationNanos }
            val collectorDuration = finished - started - identityDuration
            repetitions.put(JSONObject().apply {
                put("repetition", index + 1)
                put("rootAvailable", hasRoot)
                put("rootIdentityDurationNs", identityDuration)
                put("acquisitionDurationNs", finished - started)
                put("collectorDurationNs", collectorDuration)
                put("collectorCommandDurationNs", collectorCommandDuration)
                put("collectorNonCommandOverheadNs", collectorDuration - collectorCommandDuration)
                put("rootExecutorInvocationCount", executor.commands.size)
                put("collectorCommandCount", collectorCommands.size)
                put("nullResultCommandCount", executor.commands.count { !it.returnedOutput })
                put("commands", JSONArray().apply {
                    executor.commands.forEach { put(it.toJson()) }
                })
                put("snapshot", snapshot?.measurementJson() ?: JSONObject.NULL)
                put("failure", failure ?: JSONObject.NULL)
            })
            if (timedOut) break
        }
        val selfCheck = checkUnavailableSources()
        val report = JSONObject().apply {
            put("format", "privileged-acquisition-v2")
            put("backend", "PrivilegedAccess root monitoring backend with RootExecutor")
            put("repetitions", REPETITIONS)
            put("completedRepetitions", completedRepetitions)
            put("rootAvailable", rootAvailable)
            put("timedOut", timedOut)
            put("acquisitionBudgetNs", ACQUISITION_BUDGET_NS)
            put("commandReserveNs", COMMAND_RESERVE_NS)
            put("durationClock", "SystemClock.elapsedRealtimeNanos")
            put(
                "processCountScope",
                "One attempted su -c launch per RootExecutor invocation; child processes excluded"
            )
            put(
                "acquisitionScope",
                "Same long-lived root monitoring backend used by PrivilegedAccess.readBatterySnapshot; Shizuku fallback and shared reader excluded"
            )
            put(
                "collectorDurationScope",
                "Shared root backend acquisition minus actual id command duration, including capability and guard bookkeeping"
            )
            put(
                "overheadScope",
                "Non-identity acquisition minus timed battery commands includes parsing, projection, allocations, cache and probe bookkeeping; not isolated parsing time"
            )
            put("unavailableSourceSelfCheck", selfCheck)
            put("measurements", repetitions)
        }
        val output = File(context.filesDir, "profiling/privileged-acquisition.json")
        check(output.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        output.writeText(report.toString(2))
        return Bundle().apply {
            putString("outputPath", output.absolutePath)
            putString("reportJson", report.toString())
            putInt("repetitions", REPETITIONS)
            putInt("completedRepetitions", completedRepetitions)
            putBoolean("rootAvailable", rootAvailable)
            putBoolean("timedOut", timedOut)
            putBoolean("selfCheckPassed", true)
        }
    }

    private fun checkUnavailableSources(): JSONObject {
        var nanos = 0L
        var delegateCalls = 0
        val fake = object : CommandExecutor {
            override fun run(command: String): String? {
                delegateCalls++
                return if (command == SYSFS_DISCOVERY_COMMAND) SYNTHETIC_SUPPLY else null
            }

            override fun runRaw(command: String): String? = run(command)
        }
        val executor = TimedExecutor(fake, clock = { nanos.also { nanos += 10 } })
        val capabilities = MonitoringBatteryCapabilities { nanos / 1_000_000 }
        val commandCounts = JSONArray()
        val discoveryCounts = JSONArray()
        val propertyCounts = JSONArray()
        repeat(2) {
            val previousCount = executor.commands.size
            val snapshot = AdvancedBatteryStatsCollector.collectMonitoring(
                executor, capabilities = capabilities
            )
            check(!snapshot.hasStats())
            val commands = executor.commands.drop(previousCount)
            commandCounts.put(commands.size)
            discoveryCounts.put(commands.count { it.stage == "sysfs_discovery" })
            propertyCounts.put(commands.count { it.stage == "command_property" })
            check(commands.all { it.durationNanos == 10L })
        }
        check(executor.commands.isNotEmpty())
        check(commandCounts.getInt(1) < commandCounts.getInt(0))
        check(discoveryCounts.getInt(1) == 0)
        check(propertyCounts.getInt(1) == 0)
        check(delegateCalls == executor.commands.size)
        val throwingExecutor = TimedExecutor(object : CommandExecutor {
            override fun run(command: String): String? = error("Synthetic executor failure")
        }, clock = { nanos.also { nanos += 10 } })
        check(runCatching { throwingExecutor.run("synthetic failure") }.isFailure)
        check(throwingExecutor.commands.single().threw)
        check(throwingExecutor.commands.single().durationNanos == 10L)
        val blockedExecutor = TimedExecutor(fake, mayStartCommand = { false })
        val previousDelegateCalls = delegateCalls
        check(runCatching { blockedExecutor.run("id") }.exceptionOrNull() is ProbeDeadlineExceeded)
        check(blockedExecutor.commands.isEmpty())
        check(delegateCalls == previousDelegateCalls)
        return JSONObject().apply {
            put("passed", true)
            put("synthetic", true)
            put("passes", 2)
            put("commandsPerPass", commandCounts)
            put("discoveryCommandsPerPass", discoveryCounts)
            put("propertyCommandsPerPass", propertyCounts)
            put("scope", "Fake unavailable supply; assertions only, no device timing")
        }
    }

    private fun AdvancedBatterySnapshot.hasMonitoringReading(): Boolean =
        currentNowUa != null || currentAverageUa != null || chargeCounterUah != null || reportedCapacityPercent != null || chargingState != null

    private fun AdvancedBatterySnapshot.measurementJson(): JSONObject = JSONObject().apply {
        put("hasStats", hasMonitoringReading())
        put("collectorCapturedAtMillis", capturedAtMillis)
        put("currentNowUa", currentNowUa ?: JSONObject.NULL)
        put("currentAverageUa", currentAverageUa ?: JSONObject.NULL)
        put("chargeCounterUah", chargeCounterUah ?: JSONObject.NULL)
        put("reportedCapacityPercent", reportedCapacityPercent ?: JSONObject.NULL)
        put("fieldSources", JSONObject().apply {
            fieldSources.forEach { (field, source) -> put(field, source) }
        })
    }

    private class TimedExecutor(
        private val delegate: CommandExecutor,
        private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
        private val mayStartCommand: () -> Boolean = { true }
    ) : CommandExecutor {
        val commands = mutableListOf<CommandMeasurement>()
        var rootIdentityAccepted = false
            private set

        override fun run(command: String): String? = measure(command) { delegate.run(command) }

        override fun runRaw(command: String): String? =
            measure(command) { delegate.runRaw(command) }

        private fun measure(command: String, operation: () -> String?): String? {
            if (!mayStartCommand()) throw ProbeDeadlineExceeded()
            val started = clock()
            var output: String? = null
            var completed = false
            try {
                output = operation()
                if (command == "id" && output?.contains("uid=0") == true) rootIdentityAccepted =
                    true
                completed = true
                return output
            } finally {
                val duration = clock() - started
                commands += CommandMeasurement(
                    command, duration, output != null, output?.length, !completed
                )
            }
        }
    }

    private class ProbeDeadlineExceeded : RuntimeException()

    private data class CommandMeasurement(
        val command: String,
        val durationNanos: Long,
        val returnedOutput: Boolean,
        val outputCharacters: Int?,
        val threw: Boolean
    ) {
        val stage: String
            get() = when {
                command == "id" -> "root_identity"
                command == "dumpsys battery" -> "battery_service"
                command == SYSFS_DISCOVERY_COMMAND -> "sysfs_discovery"
                command.startsWith("for battery_file in ") && command.contains("/type'") -> "sysfs_classification"
                command.startsWith("for battery_file in ") -> "sysfs_measurements"
                command.startsWith("cmd battery get ") -> "command_property"
                else -> "other"
            }

        fun toJson(): JSONObject = JSONObject().apply {
            put("command", command)
            put("stage", stage)
            put("durationNs", durationNanos)
            put("returnedOutput", returnedOutput)
            put("outputCharacters", outputCharacters ?: JSONObject.NULL)
            put("threw", threw)
        }
    }
}
