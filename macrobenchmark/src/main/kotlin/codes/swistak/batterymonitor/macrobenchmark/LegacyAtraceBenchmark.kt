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
package codes.swistak.batterymonitor.macrobenchmark

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class LegacyAtraceBenchmark(
    private val instrumentation: Instrumentation, private val packageName: String
) {
    private val directory = File(
        requireNotNull(
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        ) { "Legacy capture requires a fresh additionalTestOutputDir" })
    private val privateAuditDirectory =
        File(instrumentation.context.filesDir, "legacy-trace-audit-${UUID.randomUUID()}")
    private var commandNumber = 0
    private var prefix = "legacy"
    private var measuredStartup: String? = null
    private var iterationRecord: JSONObject? = null

    init {
        require(Build.VERSION.SDK_INT >= 31) { "Legacy capture needs UiAutomation's closeable shell stdin (API 31)" }
        require(packageName == "codes.swistak.batterymonitor.profile")
        check(directory.isDirectory || directory.mkdirs())
        check(privateAuditDirectory.mkdir()) { "Cannot create private trace cleanup audit directory" }
        require(directory.absolutePath.matches(Regex("/[A-Za-z0-9_./-]+"))) { "Unsupported shell output path" }
        frameParserSelfCheck()
        commandFramingSelfCheck()
    }

    fun measure(
        scenario: String,
        iterations: Int,
        startupMode: String? = null,
        setup: () -> Unit,
        action: () -> Unit
    ) {
        require(scenario.matches(Regex("[A-Za-z][A-Za-z0-9]+")))
        val output = File(directory, "legacy-$scenario.json")
        check(!output.exists()) { "Preserve existing results and use a fresh output directory" }
        val records = JSONArray()
        val result = JSONObject().put("schemaVersion", 1).put("measurementBackend", "legacyAtrace")
            .put("scenario", scenario).put("packageName", packageName).put("iterations", records)
        try {
            repeat(iterations) { index ->
                prefix = "legacy-$scenario-${index + 1}"
                val section = "battery-profile/$scenario/${index + 1}"
                val cookie = NEXT_MARKER_COOKIE.incrementAndGet()
                check(cookie > 0) { "Native marker cookie exhausted" }
                val trace = fresh("$prefix.atrace")
                val frames = fresh("$prefix-gfxinfo.txt")
                val stateFile = fresh("$prefix-trace-state.json")
                val cpuBefore = fresh("$prefix-proc-before.txt")
                val cpuAfter = fresh("$prefix-proc-after.txt")
                val record = JSONObject().put("iteration", index + 1).put("status", "failed")
                    .put("traceCaptureStatus", "unavailable").put("traceFile", trace.name)
                    .put("frameStatsFile", frames.name).put("traceStateFile", stateFile.name)
                    .put("cpuCountersBeforeFile", cpuBefore.name)
                    .put("cpuCountersAfterFile", cpuAfter.name).put(
                        "cpuCounterWindowDefinition",
                        "proc snapshots immediately outside native S/F commands; includes edge overhead and CLK_TCK quantization"
                    ).put("traceSection", section).put("traceMarkerPid", Process.myPid())
                    .put("markerType", "nativeAsync").put("markerCookie", cookie).put(
                        "markerWindowDefinition",
                        "root trace_marker S before action to F after action; includes shell command edge overhead"
                    ).put("startup", JSONObject.NULL).put("functionalStatus", "not_run")
                    .put("frameMetricsStatus", "unavailable").put(
                        "gfxinfoWindow", JSONObject().put("clock", "SystemClock.uptimeMillis").put(
                            "population",
                            "eligible retained reset-to-dump rows; action boundaries classified separately"
                        )
                    )
                records.put(record)
                iterationRecord = record
                val pids = linkedMapOf(
                    packageName to linkedSetOf<Int>(), "$packageName:BIS" to linkedSetOf()
                )
                val tids = linkedMapOf<Int, MutableSet<Int>>()
                val snapshots = JSONObject()
                var session: Session? = null
                var failure: Throwable? = null
                var frameCaptureAttempted = false
                try {
                    setup()
                    observeProcesses("before", pids, tids, snapshots)
                    session = acquireTrace(stateFile)
                    startTrace(session, scenario)
                    record.put("javaTraceEnabled", Trace.isEnabled())
                    val frameWindow = record.getJSONObject("gfxinfoWindow")
                    frameWindow.put("resetBeforeUptimeMillis", SystemClock.uptimeMillis())
                    try {
                        command("dumpsys gfxinfo $packageName reset")
                    } finally {
                        frameWindow.put("resetAfterUptimeMillis", SystemClock.uptimeMillis())
                    }
                    writeMarker(session, section, cookie, start = true, cpuSnapshot = cpuBefore)
                    measuredStartup = startupMode
                    record.put("actionStartUptimeMillis", SystemClock.uptimeMillis())
                    record.put("actionStartWallMillis", System.currentTimeMillis())
                    record.put("functionalStatus", "running")
                    try {
                        action()
                        record.put("functionalStatus", "passed")
                    } catch (error: Throwable) {
                        failure = error
                        throw error
                    } finally {
                        record.put("actionEndWallMillis", System.currentTimeMillis())
                        record.put("actionEndUptimeMillis", SystemClock.uptimeMillis())
                        measuredStartup = null
                        try {
                            writeMarker(
                                session, section, cookie, start = false, cpuSnapshot = cpuAfter
                            )
                        } catch (error: Throwable) {
                            val actionFailure = failure
                            if (actionFailure == null) throw error else actionFailure.addSuppressed(
                                error
                            )
                        }
                    }
                    frameCaptureAttempted = true
                    captureFrameStats(frames, record, scenario, startupMode)
                    observeProcesses("after", pids, tids, snapshots)
                    validateStartup(record, snapshots, startupMode)
                } catch (error: Throwable) {
                    if (record.getString("functionalStatus") == "running") record.put(
                        "functionalStatus", "failed"
                    )
                    failure = error
                } finally {
                    measuredStartup = null
                    if (!frameCaptureAttempted) {
                        try {
                            captureFrameStats(frames, record, scenario, startupMode)
                        } catch (error: Throwable) {
                            failure = combine(failure, error)
                        }
                    }
                    if (session != null) {
                        try {
                            stopTrace(session, trace)
                            check(hasMarkerPair(trace, section, cookie)) {
                                "Atrace output lacks the benchmark's exact native async marker pair"
                            }
                            record.put("traceCaptureStatus", "captured")
                        } catch (error: Throwable) {
                            record.put("traceCaptureStatus", "failed")
                            failure = combine(failure, error)
                        }
                        try {
                            restoreTrace(session, stateFile)
                        } catch (error: Throwable) {
                            record.put("traceRestorationStatus", "failed")
                            failure = combine(failure, error)
                        }
                    }
                    try {
                        observeProcesses("final", pids, tids, snapshots)
                    } catch (error: Throwable) {
                        record.put("processObservationFailure", error.toString())
                    }
                    record.put("processSnapshots", snapshots)
                    record.put("processPids", JSONObject().apply {
                        pids.forEach { (name, values) -> put(name, JSONArray(values.toList())) }
                    })
                    record.put("processTids", JSONObject().apply {
                        tids.forEach { (pid, values) ->
                            put(
                                pid.toString(), JSONArray(values.toList())
                            )
                        }
                    })
                    record.put(
                        "threadCoverage",
                        "observed task snapshots; short-lived threads may be absent"
                    )
                    record.put("status", if (failure == null) "passed" else "failed")
                    failure?.let { record.put("failureReason", it.stackTraceToString()) }
                    output.writeText(result.toString(2) + "\n")
                    iterationRecord = null
                }
                failure?.let { throw it }
            }
        } finally {
            output.writeText(result.toString(2) + "\n")
        }
    }

    private fun captureFrameStats(
        frames: File, record: JSONObject, scenario: String, startupMode: String?
    ) {
        val window = record.getJSONObject("gfxinfoWindow")
        window.put("dumpBeforeUptimeMillis", SystemClock.uptimeMillis())
        try {
            val raw = try {
                command("dumpsys gfxinfo $packageName framestats")
            } finally {
                window.put("dumpAfterUptimeMillis", SystemClock.uptimeMillis())
            }
            frames.writeText(raw)
            val usable = usableFrames(raw)
            record.put("usableRetainedFrames", usable)
            record.put("frameMetricsStatus", if (usable > 0) "available" else "unavailable")
            // Idle and startup may legitimately render no eligible retained frames.
            check(usable > 0 || startupMode != null || scenario == "currentIdleUpdates") {
                "No usable retained gfxinfo frames for an interactive frame scenario"
            }
        } catch (error: Throwable) {
            record.put("frameMetricsFailure", error.toString())
            throw error
        }
    }

    fun launch(intent: Intent) {
        val launchIntent = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (measuredStartup == "WARM") {
            // Launcher Back backgrounds the root Activity on Android 12+, so recreate its task explicitly.
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val raw = fresh("$prefix-am-start-${commandNumber + 1}.txt")
        val output =
            command("am start -W ${quote(launchIntent.toUri(Intent.URI_INTENT_SCHEME))} </dev/null 2>&1")
        raw.writeText(output)
        measuredStartup?.let { requested ->
            val state = Regex("(?m)^LaunchState:\\s*(\\S+)\\s*$").find(output)?.groupValues?.get(1)
            checkNotNull(iterationRecord).put(
                "startup",
                JSONObject().put("requestedMode", requested).put("amStartFile", raw.name)
                    .put("reportedLaunchState", state ?: JSONObject.NULL)
                    .put("timingSource", "am start -W; not Macrobenchmark StartupTimingMetric")
            )
            check(state == requested) { "Requested $requested startup but am reported $state; see ${raw.name}" }
        }
        check(Regex("(?m)^Status:\\s*ok\\s*$").containsMatchIn(output)) { "Native AM launch failed: ${raw.name}" }
    }

    private fun validateStartup(record: JSONObject, snapshots: JSONObject, mode: String?) {
        if (mode == null) return
        val startup = record.optJSONObject("startup") ?: error("Missing native startup result")
        val before = snapshots.getJSONObject("before").getJSONArray(packageName)
        val after = snapshots.getJSONObject("after").getJSONArray(packageName)
        startup.put("mainPidsBefore", before).put("mainPidsAfter", after)
        check(after.length() == 1) { "Expected one target process after startup" }
        if ((InstrumentationRegistry.getArguments().getString("monitoring")
                ?: "true").toBooleanStrict()
        ) {
            check(snapshots.getJSONObject("after").getJSONArray("$packageName:BIS").length() == 1) {
                "Expected one monitoring process after startup"
            }
        }
        if (mode == "COLD") {
            check(before.length() == 0) { "Cold startup had an existing target process" }
        } else {
            check(before.length() == 1 && before.getInt(0) == after.getInt(0)) { "$mode startup did not preserve the target process" }
        }
    }

    private data class Session(
        val token: String,
        val state: JSONObject,
        val buffer: String,
        val clock: String,
        val overwrite: String
    )

    private fun acquireTrace(stateFile: File): Session {
        val query = command("perfetto --query", root = true)
        val plainQuery = query.replace(Regex("\u001B\\[[0-9;]*m"), "")
        check(
            Regex("(?m)^\\s*Tracing sessions:\\s*0\\s+\\(started:\\s*\\d+\\)\\s*$").containsMatchIn(
                plainQuery
            )
        ) {
            "Cannot prove there are zero active Perfetto sessions"
        }
        val state = traceState()
        state.put("perfettoQuery", query)
        state.put(
            "bufferRestorationLimit",
            "Restore allocated KiB; Linux deferred-expansion flag is one-way"
        )
        stateFile.writeText(JSONObject().put("before", state).toString(2) + "\n")
        requireIdle(state)
        val buffer =
            Regex("^(\\d+)(?: \\(expanded: \\d+\\))?$").matchEntire(state.getString("buffer_size_kb"))?.groupValues?.get(
                1
            ) ?: error("Unsupported saved trace buffer size")
        val clock = Regex("\\[([a-z_]+)]").find(state.getString("trace_clock"))?.groupValues?.get(1)
            ?: error("Unsupported saved trace clock")
        val overwrite = state.getString("options/overwrite")
        require(overwrite in setOf("0", "1"))
        val token = UUID.randomUUID().toString()
        command("mkdir $LOCK\necho ${quote(token)} > $LOCK/owner", root = true)
        val session = Session(token, state, buffer, clock, overwrite)
        try {
            requireIdle(traceState())
            val previous = fresh("$prefix-stopped-trace-before.atrace")
            command(
                "${ownerCheck(session)}\ncat $TRACE_ROOT/trace > ${quote(previous.absolutePath)}",
                root = true
            )
            return session
        } catch (error: Throwable) {
            try {
                command("${ownerCheck(session)}\nrm $LOCK/owner\nrmdir $LOCK", root = true)
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    private fun startTrace(session: Session, scenario: String) {
        val available = Regex("(?m)^\\s*([a-z_]+)\\s+-\\s+").findAll(
            command(
                "atrace --list_categories", root = true
            )
        ).map { it.groupValues[1] }.toSet()
        val required = setOf("sched", "gfx", "view", "am", "wm")
        check(available.containsAll(required)) { "Missing required atrace categories: ${required - available}" }
        val categories = listOf(
            "sched",
            "gfx",
            "view",
            "am",
            "wm",
            "input",
            "freq",
            "idle",
            "dalvik",
            "database",
            "binder_driver"
        ).filter { it in available }
        session.state.put("captureCategories", JSONArray(categories))
        iterationRecord?.put("captureCategories", JSONArray(categories))
        val bufferSizeKb = traceBufferSizeKb(scenario)
        val buffer = JSONObject().put("requested_buffer_size_kb", bufferSizeKb)
        session.state.put("captureBuffer", buffer)
        iterationRecord?.put("captureBuffer", buffer)
        val apps = "$packageName,$packageName:BIS,${instrumentation.context.packageName}"
        val output = command(
            "${ownerCheck(session)}\necho 0 > $TRACE_ROOT/tracing_on\n" + "atrace --async_start -b $bufferSizeKb -a ${
                quote(
                    apps
                )
            } ${
                categories.joinToString(
                    " "
                )
            }\n" + "test \"$(cat $TRACE_ROOT/tracing_on)\" = 1\n" + "printf '\\nbuffer_size_kb:'; cat $TRACE_ROOT/buffer_size_kb\n" + "printf 'buffer_total_size_kb:'; cat $TRACE_ROOT/buffer_total_size_kb",
            root = true
        )
        val sizes = traceBufferSizes(output, bufferSizeKb)
        sizes.forEach { (key, value) -> buffer.put(key, value) }
    }

    private fun traceBufferSizes(output: String, requestedKb: Int): Map<String, Int> {
        val sizes = Regex("(?m)^(buffer_size_kb|buffer_total_size_kb):(\\d+)$").findAll(output)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        check(sizes["buffer_size_kb"] == requestedKb && (sizes["buffer_total_size_kb"] ?: 0) > 0) {
            "Active trace buffer does not match the requested size"
        }
        return sizes
    }

    private fun traceBufferSizeKb(scenario: String): Int = when (scenario) {
        "settingsAlarmsAndUnsavedDraft", "drawerFirstAndRepeatedNavigation", "historyRangesTabsAndSameLevelInsertion" -> LONG_SCENARIO_TRACE_BUFFER_KB

        else -> DEFAULT_TRACE_BUFFER_KB
    }

    private fun stopTrace(session: Session, output: File) {
        command(
            "${ownerCheck(session)}\natrace --async_stop -o ${quote(output.absolutePath)}",
            root = true
        )
        val stopped = traceState()
        check(stopped.getString("tracing_on") == "0") { "Owned atrace did not stop" }
        requireIdle(stopped)
    }

    private fun writeMarker(
        session: Session, section: String, cookie: Int, start: Boolean, cpuSnapshot: File
    ) {
        val marker = "${if (start) "S" else "F"}|${Process.myPid()}|$section|$cookie"
        val emitMarker = "echo ${quote(marker)} > $TRACE_ROOT/trace_marker"
        val snapshot =
            "{\n" + $$"if ticks=$(getconf CLK_TCK); then echo \"clockTicksPerSecond:$ticks\"; " + "else echo 'clockTicksPerSecond:unavailable'; fi\n" + $$"if uptime=$(cat /proc/uptime); then echo \"snapshotUptime:$uptime\"; " + "else echo 'snapshotUptime:unavailable'; fi\n" + "for name in ${
                quote(packageName)
            } ${quote("$packageName:BIS")}; do\n" + $$"echo \"process:$name\"\n" + $$"for pid in $(pidof \"$name\" || true); do\n" + $$"case \"$pid\" in ''|*[!0-9]*) echo 'Invalid pidof result' >&2; exit 1;; esac\n" + $$"echo \"pid:$pid\"\n" + $$"if stamp=$(cat /proc/uptime); then echo \"statReadStartUptime:$stamp\"; " + "else echo 'statReadStartUptime:unavailable'; fi\n" + $$"if stat=$(cat /proc/$pid/stat); then echo \"stat:$stat\"; " + "else echo 'stat:unavailable'; fi\n" + $$"if stamp=$(cat /proc/uptime); then echo \"statReadEndUptime:$stamp\"; " + "else echo 'statReadEndUptime:unavailable'; fi\n" + "done\ndone\n} > ${
                quote(
                    cpuSnapshot.absolutePath
                )
            }"
        command(
            "${ownerCheck(session)}\n" + if (start) "$snapshot\n$emitMarker" else "$emitMarker\n$snapshot",
            root = true
        )
    }

    private fun hasMarkerPair(trace: File, section: String, cookie: Int): Boolean =
        trace.bufferedReader().use { reader ->
            if (reader.readLine() != "TRACE:") return@use false
            val begin = "S|${Process.myPid()}|$section|$cookie"
            val end = "F|${Process.myPid()}|$section|$cookie"
            var starts = 0
            var finishes = 0
            var ordered = true
            reader.forEachLine { line ->
                if (line.trimEnd().endsWith(begin)) starts += 1
                if (line.trimEnd().endsWith(end)) {
                    finishes += 1
                    if (starts != 1) ordered = false
                }
            }
            ordered && starts == 1 && finishes == 1
        }

    private fun restoreTrace(session: Session, stateFile: File) {
        val observed = traceState()
        check(observed.getString("tracing_on") == "0") { "Refusing to restore an active trace" }
        requireIdle(observed)
        val bit = session.state.getString("tracing_on")
        require(bit == "0" || bit == "1")
        command(
            "${ownerCheck(session)}\necho ${session.buffer} > $TRACE_ROOT/buffer_size_kb\n" + "echo ${session.clock} > $TRACE_ROOT/trace_clock\n" + "echo ${session.overwrite} > $TRACE_ROOT/options/overwrite\necho $bit > $TRACE_ROOT/tracing_on",
            root = true
        )
        val restored = traceState()
        requireIdle(restored)
        check(restored.getString("tracing_on") == bit)
        check(restored.getString("buffer_size_kb").substringBefore(' ') == session.buffer)
        check(restored.getString("trace_clock").contains("[${session.clock}]"))
        check(restored.getString("options/overwrite") == session.overwrite)
        OPTIONAL_TRACE_PATHS.forEach { path ->
            check(session.state.isNull(path) || !restored.isNull(path)) { "Previously present trace option disappeared: $path" }
        }
        command("${ownerCheck(session)}\nrm $LOCK/owner\nrmdir $LOCK", root = true)
        iterationRecord?.put("traceRestorationStatus", "restored_allocated_buffer_clock_and_bit")
        val audit = JSONObject().put("before", session.state).put("stopped", observed)
            .put("restored", restored)
            .put("restorationStatus", "restored_allocated_buffer_clock_and_bit").toString(2) + "\n"
        try {
            stateFile.writeText(audit)
        } catch (error: IOException) {
            val retained = File(privateAuditDirectory, stateFile.name)
            val fallback = JSONObject().put("externalFile", stateFile.absolutePath)
                .put("privateFile", retained.absolutePath).put("externalFailure", error.toString())
            runCatching { retained.writeText(audit) }.onFailure {
                fallback.put(
                    "privateWriteFailure", it.toString()
                )
            }
            iterationRecord?.put("traceStatePublicationFallback", fallback)
            reportPrivateAudit(fallback)
        }
    }

    private fun traceState(): JSONObject {
        val paths = listOf(
            "tracing_on",
            "current_tracer",
            "events/enable",
            "buffer_size_kb",
            "buffer_total_size_kb",
            "trace_clock",
            "options/overwrite"
        )
        val properties = listOf(
            "debug.atrace.user_initiated",
            "debug.atrace.tags.enableflags",
            "debug.atrace.app_number"
        )
        val script =
            paths.joinToString("\n") { "echo ${quote(it)}; cat $TRACE_ROOT/$it" } + "\n" + OPTIONAL_TRACE_PATHS.joinToString(
                "\n"
            ) { path ->
                "echo ${quote(path)}\nif [ -e $TRACE_ROOT/$path ]; then\n" + $$"test -r $$TRACE_ROOT/$$path\nvalue=$(tr '\\n' '\\t' < $$TRACE_ROOT/$$path)\n" + $$"echo \"1:$value\"\nelse\necho '0:'\nfi"
            } + "\n" + properties.joinToString("\n") { "echo $it; getprop $it" }
        val lines = command(script, root = true).lines()
        val names = paths + OPTIONAL_TRACE_PATHS + properties
        check(lines.size >= names.size * 2) { "Incomplete trace state" }
        return JSONObject().apply {
            names.forEachIndexed { index, name ->
                check(lines[index * 2] == name) { "Unexpected trace state field" }
                val value = lines[index * 2 + 1].trim()
                if (name in OPTIONAL_TRACE_PATHS) {
                    check(value == "0:" || value.startsWith("1:")) { "Invalid optional trace state" }
                    put(name, if (value == "0:") JSONObject.NULL else value.substring(2).trim())
                } else put(name, value)
            }
        }
    }

    private fun requireIdle(state: JSONObject) {
        check(
            state.getString("tracing_on") in setOf(
                "0", "1"
            ) && state.getString("current_tracer") == "nop" && state.getString("events/enable") == "0" && listOf(
                "debug.atrace.user_initiated",
                "debug.atrace.tags.enableflags",
                "debug.atrace.app_number"
            ).all { state.getString(it) in setOf("", "0", "0x0") }) {
            "Tracing is configured by another session; refusing to replace it"
        }
        check(
            listOf(
                "options/print-tgid", "options/record-tgid"
            ).all { state.isNull(it) || state.getString(it) == "0" } && (state.isNull("set_ftrace_filter") || state.getString(
                "set_ftrace_filter"
            ) in setOf("", "#### all functions enabled ####"))) {
            "Nondefault TGID option or function filter would be overwritten by atrace"
        }
    }

    private fun ownerCheck(session: Session) =
        "test \"$(cat $LOCK/owner)\" = ${quote(session.token)}"

    private fun observeProcesses(
        stage: String,
        processes: MutableMap<String, LinkedHashSet<Int>>,
        threads: MutableMap<Int, MutableSet<Int>>,
        snapshots: JSONObject
    ) {
        val snapshot = JSONObject()
        processes.forEach { (name, known) ->
            val output = command("pidof ${quote(name)} || true")
            val current =
                output.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt() }
            known.addAll(current)
            snapshot.put(name, JSONArray(current))
            current.forEach { pid ->
                val observed =
                    command("ls /proc/$pid/task 2>/dev/null || true", root = true).lineSequence()
                        .mapNotNull { it.trim().toIntOrNull() }.toList()
                threads.getOrPut(pid) { linkedSetOf() }.addAll(observed)
            }
        }
        snapshots.put(stage, snapshot)
    }

    private fun command(script: String, root: Boolean = false): String {
        commandNumber += 1
        val stem = "$prefix-command-$commandNumber"
        val artifacts = try {
            commandArtifacts(directory, stem, script)
        } catch (error: IOException) {
            if (!root) throw error
            val fallback = JSONObject().put("stem", stem)
                .put("privateDirectory", privateAuditDirectory.absolutePath)
                .put("externalFailure", error.toString())
            val retained = commandArtifacts(privateAuditDirectory, stem, script)
            val records =
                iterationRecord?.optJSONArray("privateCommandArtifacts") ?: JSONArray().also {
                    iterationRecord?.put("privateCommandArtifacts", it)
                }
            records.put(fallback)
            reportPrivateAudit(fallback)
            retained.copy(fallback = fallback)
        }
        val stderr = artifacts.stderr
        val stdout = artifacts.stdout
        val wrapped =
            "exec 2> ${quote(stderr.absolutePath)}\n(\nset -eu\n$script\n)\n" + $$"status=$?\necho\necho $${
                quote(
                    EXIT_MARKER
                )
            }$status\n"
        val descriptors = instrumentation.uiAutomation.executeShellCommandRw(
            if (root) "toybox timeout 20 su -mm -c sh" else "toybox timeout 20 sh"
        )
        val reader = FutureTask {
            ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { input ->
                stdout.outputStream().use { destination ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_COMMAND_OUTPUT_BYTES) { "Unexpectedly large shell output; retained partial $stem.stdout" }
                        destination.write(buffer, 0, count)
                    }
                }
            }
        }
        val thread = Thread(reader, "legacy-shell-output").apply {
            isDaemon = true
            start()
        }
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1])
                .use { it.write(wrapped.toByteArray()) }
            reader.get(25, TimeUnit.SECONDS)
            return commandOutput(stdout.readText(), stem)
        } finally {
            descriptors.forEach { runCatching { it.close() } }
            reader.cancel(true)
            thread.join(100)
            artifacts.fallback?.let { fallback ->
                val mirrors = JSONArray()
                listOf(artifacts.stderr, artifacts.stdout, artifacts.script).forEach { retained ->
                    val mirror = JSONObject().put("privateFile", retained.absolutePath)
                        .put("externalFile", File(directory, retained.name).absolutePath)
                    runCatching {
                        retained.copyTo(
                            File(directory, retained.name), overwrite = true
                        )
                    }.onSuccess { mirror.put("status", "mirrored") }.onFailure {
                        mirror.put("status", "retained_private").put("failure", it.toString())
                    }
                    mirrors.put(mirror)
                }
                fallback.put("mirrors", mirrors)
                reportPrivateAudit(fallback)
            }
        }
    }

    private data class CommandArtifacts(
        val stderr: File, val stdout: File, val script: File, val fallback: JSONObject? = null
    )

    private fun commandArtifacts(base: File, stem: String, script: String): CommandArtifacts {
        val stderr = fresh("$stem.stderr", base)
        val stdout = fresh("$stem.stdout", base)
        val source = fresh("$stem.sh", base).also { it.writeText(script + "\n") }
        return CommandArtifacts(stderr, stdout, source)
    }

    private fun reportPrivateAudit(audit: JSONObject) {
        runCatching {
            instrumentation.sendStatus(
                0, Bundle().apply { putString("legacyPrivateAudit", audit.toString()) })
        }
    }

    private fun fresh(name: String, base: File = directory) = File(base, name).also {
        check(it.createNewFile()) { "Artifact already exists: ${it.absolutePath}" }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun combine(previous: Throwable?, error: Throwable): Throwable =
        previous?.apply { addSuppressed(error) } ?: error

    private fun usableFrames(raw: String): Int {
        var flags = -1
        var intended = -1
        var completed = -1
        return raw.lineSequence().count { line ->
            val columns = line.split(',').map { it.trim() }
            if (columns.firstOrNull() == "Flags") {
                flags = columns.indexOf("Flags")
                intended = columns.indexOf("IntendedVsync")
                completed = columns.indexOf("FrameCompleted")
                false
            } else if (flags >= 0 && intended >= 0 && completed >= 0) {
                val start = columns.getOrNull(intended)?.toLongOrNull()
                val end = columns.getOrNull(completed)?.toLongOrNull()
                columns.getOrNull(flags)
                    ?.toLongOrNull() == 0L && start != null && start > 0 && end != null && end != Long.MAX_VALUE && end >= start
            } else false
        }
    }

    private fun frameParserSelfCheck() {
        check(usableFrames("Flags,IntendedVsync,FrameCompleted\n0,100,150\n1,100,200\n0,100,9223372036854775807\n0,100,99\n0,0,50\n") == 1)
        check(usableFrames("No process found for package") == 0)
    }

    private fun commandOutput(output: String, stem: String): String {
        val match = Regex("(?m)^${Regex.escape(EXIT_MARKER)}(\\d+)\\s*$").find(output)
            ?: error("Shell command timed out or did not finish; see $stem artifacts")
        check(match.groupValues[1] == "0") { "Shell command failed (${match.groupValues[1]}); see $stem artifacts" }
        return output.substring(0, match.range.first).removeSuffix("\n")
    }

    private fun commandFramingSelfCheck() {
        check(
            commandOutput(
                "capturing trace...\n${EXIT_MARKER}0\n", "self-check"
            ) == "capturing trace..."
        )
        check(commandOutput("complete\n\n${EXIT_MARKER}0\n", "self-check") == "complete\n")
        check(runCatching { commandOutput("partial", "self-check") }.isFailure)
        check(runCatching { commandOutput("\n${EXIT_MARKER}1\n", "self-check") }.isFailure)
        val bufferOutput = "capturing trace...\nbuffer_size_kb:16384\nbuffer_total_size_kb:131072\n"
        check(
            traceBufferSizes(bufferOutput, LONG_SCENARIO_TRACE_BUFFER_KB) == mapOf(
                "buffer_size_kb" to LONG_SCENARIO_TRACE_BUFFER_KB, "buffer_total_size_kb" to 131072
            )
        )
        check(runCatching { traceBufferSizes(bufferOutput, DEFAULT_TRACE_BUFFER_KB) }.isFailure)
        check(runCatching {
            traceBufferSizes(
                bufferOutput.replace("...\n", "..."), LONG_SCENARIO_TRACE_BUFFER_KB
            )
        }.isFailure)
    }

    companion object {
        private const val TRACE_ROOT = "/sys/kernel/debug/tracing"
        private const val LOCK = "/data/local/tmp/battery-profile-atrace.lock"
        private const val EXIT_MARKER = "__BATTERY_PROFILE_EXIT__"
        private const val MAX_COMMAND_OUTPUT_BYTES = 4 * 1024 * 1024
        private const val DEFAULT_TRACE_BUFFER_KB = 8192
        private const val LONG_SCENARIO_TRACE_BUFFER_KB = 16384
        private val OPTIONAL_TRACE_PATHS =
            listOf("options/print-tgid", "options/record-tgid", "set_ftrace_filter")
        private val NEXT_MARKER_COOKIE = AtomicInteger(0)
    }
}
