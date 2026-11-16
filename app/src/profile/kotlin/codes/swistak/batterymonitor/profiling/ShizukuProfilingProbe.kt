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
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import codes.swistak.batterymonitor.monitoring.PrivilegedBatteryReader
import codes.swistak.batterymonitor.monitoring.PrivilegedBatteryReading
import codes.swistak.batterymonitor.monitoring.BatteryMeasurementTime
import codes.swistak.batterymonitor.monitoring.projectPrivilegedBatteryReading
import codes.swistak.batterymonitor.privileged.PrivilegedAccess
import codes.swistak.batterymonitor.settings.SettingsContract
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal object ShizukuProfilingProbe {
    private const val REPETITIONS = 5
    private const val BUDGET_MILLIS = 50_000L
    private const val OPERATION_LIMIT_MILLIS = 20_000L
    private const val READER_REFRESH_WAIT_MILLIS = 2_050L
    private const val OBSERVATION_SPACING_MILLIS = 2L
    private const val MAX_RECOVERY_ATTEMPTS = 3

    fun status(context: Context, phase: String): Bundle =
        result(context, phase, JSONObject().apply {
            put("format", "shizuku-status-v1")
            put("status", statusJson(context))
            put("statusOnly", true)
        })

    fun measure(
        context: Context, phase: String, enable: Boolean, runReaderChecks: Boolean
    ): Bundle {
        val before = statusJson(context)
        val observations = JSONArray()
        val readerCollections = JSONArray()
        var acceptingReaderResults = true
        val report = JSONObject().apply {
            put("format", "shizuku-monitoring-v1")
            put("before", before)
            put("initialObservations", 1)
            put("timedRepetitions", REPETITIONS)
            put("observations", observations)
            put("readerCollections", readerCollections)
            put("explicitEnableRequested", enable)
            put("readerChecksRequested", runReaderChecks)
            put("complete", false)
            put("acquisitionBudgetMillis", BUDGET_MILLIS)
            put("operationLimitMillis", OPERATION_LIMIT_MILLIS)
            put(
                "scope",
                "Actual app-UID PrivilegedAccess root-first monitoring path; optional separate production reader instance uses real transport and clocks, not the BIS shared singleton"
            )
            put(
                "capabilityReuseLimit",
                "BIS calls can reset the remote cache owner PID; record competing callers separately, no command-count inference"
            )
            put("commandCountsAvailable", false)
            put(
                "timeoutLimit",
                "A synchronous Binder transaction may outlive cancellation; late probe results are discarded and the caller stops waiting at the budget"
            )
        }
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ShizukuProfileTransport").apply { isDaemon = true }
        }
        val deadline = SystemClock.elapsedRealtime() + BUDGET_MILLIS
        fun remaining(): Long = (deadline - SystemClock.elapsedRealtime()).also {
            check(it > 0) { "Shizuku profiling budget exhausted" }
        }

        fun <T> await(future: java.util.concurrent.Future<T>): T = try {
            future.get(minOf(remaining(), OPERATION_LIMIT_MILLIS), TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            future.cancel(true)
            if (error is TimeoutException) report.put("timedOut", true)
            throw error
        }

        val expectedUid = before.optInt("shizukuServerUid", -1)
        var previousTimes = emptyMap<String, BatteryMeasurementTime>()
        var previousSources = emptyMap<String, String>()
        fun observation(label: String): JSONObject {
            val started = SystemClock.elapsedRealtimeNanos()
            val item = JSONObject().apply {
                put("label", label)
                put("startedElapsedNanos", started)
                put("accessRevision", PrivilegedAccess.accessRevision)
            }
            var snapshot: AdvancedBatterySnapshot? = null
            var projected: PrivilegedBatteryReading? = null
            try {
                val collected = PrivilegedAccess.readBatterySnapshot()
                snapshot = collected
                projected = collected?.let(::projectPrivilegedBatteryReading)
                item.put("snapshot", collected?.toBundle()?.json() ?: JSONObject.NULL)
                item.put(
                    "shizukuConfirmed",
                    collected?.accessMethod == AdvancedBatterySnapshot.ACCESS_SHIZUKU && collected.remoteUid == expectedUid && expectedUid >= 0
                )
                item.put("hasMeasurements", projected?.hasMeasurements() == true)
                if (collected == null) item.put("failure", "no_snapshot")
            } catch (error: Exception) {
                item.put("failure", error.javaClass.simpleName + ": " + error.message)
            } finally {
                val finished = SystemClock.elapsedRealtimeNanos()
                item.put("finishedElapsedNanos", finished)
                item.put("durationNanos", finished - started)
                projected?.let { reading ->
                    val acquired = reading.fieldMeasurementTimes
                    item.put("selectedFieldSources", JSONObject(reading.fieldSources))
                    item.put("selectedMeasurementTimes", times(reading))
                    val mapped =
                        reading.fieldSources.isNotEmpty() && reading.fieldSources.all { (field, source) ->
                            acquired[field] != null && acquired[field] == snapshot?.sourceReadTimes?.get(
                                source
                            )
                        }
                    val bounded = acquired.isNotEmpty() && acquired.values.all { time ->
                        time.wallMillis > 0 && time.elapsedMillis?.let {
                            it in (started / 1_000_000 - 1)..(finished / 1_000_000 + 1)
                        } == true
                    }
                    val common = acquired.keys.filter { field ->
                        previousTimes.containsKey(field) && previousSources[field] == reading.fieldSources[field]
                    }
                    val advanced =
                        label == "initial" || common.isNotEmpty() && common.all { field ->
                            acquired[field]?.elapsedMillis?.let { next ->
                                previousTimes[field]?.elapsedMillis?.let { next > it }
                            } == true
                        }
                    item.put("sourceTimesValid", mapped && bounded)
                    item.put("repeatedTimesAdvance", advanced)
                    item.put("comparedTimestampFields", JSONArray(common))
                    previousTimes = acquired
                    previousSources = reading.fieldSources
                }
            }
            return item
        }
        try {
            report.put(
                "setupReady", before.optBoolean("binderAlive") && before.optInt(
                    "permission", -1
                ) == PackageManager.PERMISSION_GRANTED && expectedUid >= 0
            )
            check(
                context.getSharedPreferences(SettingsContract.SETTINGS_FILE, 0)
                    .getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false)
            ) {
                "The isolated fixture must already have privileged access enabled"
            }
            PrivilegedAccess.initialize(context)
            if (enable) PrivilegedAccess.setEnabled(true)
            repeat(REPETITIONS + 1) { index ->
                // Source timestamps have millisecond resolution; space real reads accordingly.
                if (index > 0) Thread.sleep(OBSERVATION_SPACING_MILLIS)
                report.put(
                    "attemptedObservation", if (index == 0) "initial" else "repetition_$index"
                )
                observations.put(await(worker.submit<JSONObject> {
                    observation(if (index == 0) "initial" else "repetition_$index")
                }))
            }
            check((0 until observations.length()).all { index ->
                observations.getJSONObject(index)
                    .optBoolean("shizukuConfirmed") && observations.getJSONObject(index)
                    .optBoolean("hasMeasurements") && observations.getJSONObject(index)
                    .optBoolean("sourceTimesValid") && observations.getJSONObject(index)
                    .optBoolean("repeatedTimesAdvance")
            }) { "At least one actual monitoring read lacked selected Shizuku measurements or valid advancing source timestamps" }

            if (runReaderChecks) {
                val enabledBeforeReaderChecks = PrivilegedAccess.isEnabled()
                try {
                    val reader = PrivilegedBatteryReader(collector = {
                        val snapshot = PrivilegedAccess.readBatterySnapshot()
                        synchronized(readerCollections) {
                            if (acceptingReaderResults) readerCollections.put(JSONObject().apply {
                                put("snapshot", snapshot?.toBundle()?.json() ?: JSONObject.NULL)
                                put(
                                    "shizukuConfirmed",
                                    snapshot?.accessMethod == AdvancedBatterySnapshot.ACCESS_SHIZUKU && snapshot.remoteUid == expectedUid
                                )
                            })
                        }
                        snapshot
                    }, backgroundExecutor = worker)

                    fun read(): PrivilegedBatteryReading? {
                        val future = CompletableFuture<PrivilegedBatteryReading?>()
                        reader.readWhenReady(false) { future.complete(it) }
                        return await(future)
                    }

                    val first =
                        requireNotNull(read()) { "Production reader returned no measurements" }
                    val collected = readerCollections.length()
                    val cached = reader.read()
                    check(cached === first && readerCollections.length() == collected) {
                        "Immediate cache lookup must preserve the actual reading and its acquisition timestamps"
                    }
                    val cache = JSONObject().apply {
                        put("immediateSameReading", true)
                        put("firstFieldSources", JSONObject(first.fieldSources))
                        put("firstMeasurementTimes", times(first))
                    }
                    report.put("readerChecks", cache)
                    check(remaining() > READER_REFRESH_WAIT_MILLIS) { "No budget for a real refresh interval" }
                    Thread.sleep(READER_REFRESH_WAIT_MILLIS)
                    val refreshed =
                        requireNotNull(read()) { "Production reader refresh returned no measurements" }
                    check(readerCollections.length() == collected + 1 && refreshed !== first) {
                        "A due refresh must invoke the actual collector exactly once"
                    }
                    check(collected == 1 && (0..1).all {
                        readerCollections.getJSONObject(it).optBoolean("shizukuConfirmed")
                    }) { "Initial and refreshed reader collections must both return actual Shizuku measurements" }
                    cache.put("refreshedMeasurementTimes", times(refreshed))
                    cache.put("refreshCollectorDelta", 1)
                    val beforeDisable = PrivilegedAccess.accessRevision
                    cache.put("enabledBeforeDisable", PrivilegedAccess.isEnabled())
                    cache.put("revisionBeforeDisable", beforeDisable)
                    PrivilegedAccess.setEnabled(false)
                    check(reader.read() == null && PrivilegedAccess.accessRevision > beforeDisable) {
                        "Disabling privileged access must invalidate the actual reader cache"
                    }
                    await(reader.whenIdle())
                    cache.put("disabledReturnsNull", true)
                    cache.put("revisionAfterDisable", PrivilegedAccess.accessRevision)
                    PrivilegedAccess.setEnabled(true)
                    val recovery = JSONArray()
                    cache.put("recoveryAttempts", recovery)
                    var recovered = false
                    for (attempt in 1..MAX_RECOVERY_ATTEMPTS) {
                        val started = SystemClock.elapsedRealtimeNanos()
                        val collectionsBefore = readerCollections.length()
                        val record = JSONObject().apply {
                            put("attempt", attempt)
                            put("startedElapsedNanos", started)
                            put("revisionBefore", PrivilegedAccess.accessRevision)
                            put("collectorCountBefore", collectionsBefore)
                        }
                        recovery.put(record)
                        var restored: PrivilegedBatteryReading? = null
                        var completed = false
                        try {
                            restored = read()
                            completed = true
                        } finally {
                            val finished = SystemClock.elapsedRealtimeNanos()
                            record.put("finishedElapsedNanos", finished)
                            record.put("durationNanos", finished - started)
                            record.put("revisionAfter", PrivilegedAccess.accessRevision)
                            record.put("completed", completed)
                            record.put("returnedNull", completed && restored == null)
                            record.put("collectorCountAfter", readerCollections.length())
                        }
                        val reading = restored ?: continue
                        record.put("fieldSources", JSONObject(reading.fieldSources))
                        record.put("measurementTimes", times(reading))
                        val common = reading.fieldMeasurementTimes.keys.filter { field ->
                            refreshed.fieldMeasurementTimes.containsKey(field) && refreshed.fieldSources[field] == reading.fieldSources[field]
                        }
                        val advanced = common.isNotEmpty() && common.all { field ->
                            reading.fieldMeasurementTimes[field]?.elapsedMillis?.let { next ->
                                refreshed.fieldMeasurementTimes[field]?.elapsedMillis?.let { next > it }
                            } == true
                        }
                        val collectionsAfter = readerCollections.length()
                        val actualShizuku =
                            collectionsAfter > collectionsBefore && readerCollections.getJSONObject(
                                collectionsAfter - 1
                            ).optBoolean("shizukuConfirmed")
                        record.put("comparedTimestampFields", JSONArray(common))
                        record.put("timestampsAdvanceBeyondRefresh", advanced)
                        record.put("newActualShizukuCollection", actualShizuku)
                        if (actualShizuku && reading.hasMeasurements() && advanced) {
                            cache.put("restoredMeasurementTimes", times(reading))
                            cache.put("restoredViaShizuku", true)
                            recovered = true
                            break
                        }
                    }
                    check(recovered) { "Reader did not recover fresh actual Shizuku measurements within the bounded attempts" }
                } finally {
                    PrivilegedAccess.setEnabled(enabledBeforeReaderChecks)
                }
            }
            report.put("complete", true)
        } catch (error: Exception) {
            report.put("failure", error.javaClass.simpleName + ": " + error.message)
            report.put("complete", false)
        } finally {
            // Observational stages leave the live owner and its cached Binder intact.
            worker.shutdownNow()
            synchronized(readerCollections) { acceptingReaderResults = false }
            report.put("completedObservations", observations.length())
            report.put("after", statusJson(context))
        }
        return result(context, phase, report)
    }

    private fun times(reading: PrivilegedBatteryReading): JSONObject = JSONObject().apply {
        reading.fieldMeasurementTimes.forEach { (field, time) ->
            put(
                field, time.toBundle().json()
            )
        }
    }

    private fun statusJson(context: Context): JSONObject = JSONObject().apply {
        put("appPid", Process.myPid())
        put("appUid", Process.myUid())
        put("enabled", PrivilegedAccess.isEnabled())
        put("accessRevision", PrivilegedAccess.accessRevision)
        put(
            "preferenceEnabled",
            context.getSharedPreferences(SettingsContract.SETTINGS_FILE, 0)
                .getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false)
        )
        fun value(key: String, read: () -> Any) {
            runCatching(read).onSuccess { put(key, it) }
                .onFailure { put(key + "Error", it.toString()) }
        }
        value("binderAlive") { Shizuku.pingBinder() }
        value("permission") { Shizuku.checkSelfPermission() }
        value("shizukuServerUid") { Shizuku.getUid() }
        value("version") { Shizuku.getVersion() }
        value("preV11") { Shizuku.isPreV11() }
    }

    private fun result(context: Context, phase: String, report: JSONObject): Bundle {
        require(phase.matches(Regex("[A-Za-z0-9_-]{1,48}"))) { "Invalid profiling phase" }
        report.put("phase", phase)
        val directory = File(context.filesDir, "profiling/shizuku")
        check(directory.isDirectory || directory.mkdirs())
        val output = File(directory, "$phase-${SystemClock.elapsedRealtimeNanos()}.json")
        check(!output.exists())
        output.writeText(report.toString(2))
        return Bundle().apply {
            putString("outputPath", output.absolutePath)
            putString("reportJson", report.toString())
            putBoolean("complete", report.optBoolean("complete"))
        }
    }

    private fun Bundle.json(): JSONObject = JSONObject().also { json ->
        keySet().forEach { key ->
            // Diagnostic keys contain mixed types, so no typed getter can inspect every value.
            @Suppress("DEPRECATION") val value = get(key)
            json.put(
                key, when (value) {
                    null -> JSONObject.NULL
                    is Bundle -> value.json()
                    is List<*> -> JSONArray(value)
                    else -> value
                }
            )
        }
    }
}
