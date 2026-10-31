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
package codes.swistak.batterymonitor.macrobenchmark

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class HistorySynchronizationVerification {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val arguments = InstrumentationRegistry.getArguments()
    private val device = UiDevice.getInstance(instrumentation)
    private val resources: Resources by lazy {
        context.packageManager.getResourcesForApplication(
            TARGET
        )
    }
    private val observations = JSONArray()
    private var lastSnapshot: JSONObject? = null
    private var servicePid: Int? = null

    @Test
    fun historyUiRead() = report("history-ui-read", ::readHistoryUi)

    @Test
    fun historyEmptyUiRead() = report("history-ui-empty") { output ->
        launch("history")
        clickText("history_charts")
        awaitText("logs_empty")
        recordUi("normal-empty-history")
        output.put("appPid", applicationPid()).put(
            "scope",
            "ordinary History launch and selected-range empty chart UI; host verifies actual database is empty schema7"
        )
    }

    @Test
    fun historyMonitoringStart() = report("history-monitor-start") { output ->
        launch("diagnostics", "monitor:monitor_start")
        check(device.wait(Condition {
            it.hasObject(
                By.pkg(TARGET).text(text("diag_start_monitor")).enabled(true)
            ) || it.hasObject(By.pkg(TARGET).text(text("diag_stop_monitor")).enabled(true))
        }, TIMEOUT) == true) { "Monitoring action did not become visible" }
        val alreadyStarted =
            device.hasObject(By.pkg(TARGET).text(text("diag_stop_monitor")).enabled(true))
        if (!alreadyStarted) clickText("diag_start_monitor")
        awaitText("diag_stop_monitor")
        recordUi("monitor-start-observed")
        output.put("appPid", applicationPid()).put("alreadyStartedObserved", alreadyStarted).put(
            "scope",
            "actual Start monitoring UI; owner separately verifies durable desired state and LIVE BIS"
        )
    }

    @Test
    fun historyMonitoringStop() = report("history-monitor-stop") { output ->
        launch("diagnostics", "monitor:monitor_stop")
        check(device.wait(Condition {
            it.hasObject(
                By.pkg(TARGET).text(text("diag_start_monitor")).enabled(true)
            ) || it.hasObject(By.pkg(TARGET).text(text("diag_stop_monitor")).enabled(true))
        }, TIMEOUT) == true) { "Monitoring action did not become visible" }
        val alreadyStopped =
            device.hasObject(By.pkg(TARGET).text(text("diag_start_monitor")).enabled(true))
        if (!alreadyStopped) {
            clickText("diag_stop_monitor")
            awaitText("diag_stop_monitor_body")
            clickText("diag_stop_monitor", last = true)
        }
        awaitText("diag_start_monitor")
        recordUi("monitor-stop-observed")
        output.put("appPid", applicationPid()).put("alreadyStoppedObserved", alreadyStopped).put(
            "scope",
            "actual Stop monitoring UI and confirmation; owner separately verifies durable desired=false and stopped BIS"
        )
    }

    private fun readHistoryUi(output: JSONObject) {
        launch("history")
        clickText("history_charts")
        chooseRange("history_last_24h")
        awaitDescription("history_open_fullscreen")
        recordUi("rolling-chart")
        chooseRange("history_all_history")
        awaitDescription("history_open_fullscreen")
        recordUi("all-chart")
        clickText("history_events")
        awaitText("history_all_types")
        val row = device.wait(
            Until.findObject(By.pkg(TARGET).text(Pattern.compile(".*[0-9]+%.*"))), TIMEOUT
        ) ?: error("History events did not show a normal percentage row")
        row.click()
        awaitText("okay")
        recordUi("stored-row-details")
        clickText("okay")
        output.put("appPid", applicationPid())
        output.put(
            "scope",
            "actual History rolling/all charts, Events row and reading details; no new app hooks"
        )
    }

    @Test
    fun processBoundarySynchronization() = report("history-sync") { output ->
        fixture("HISTORY_WATCH")
        device.pressHome()
        launch("history")
        clickText("history_charts")
        chooseRange("history_last_24h")
        var before = awaitSnapshot { ready(it, "history") && live(it) }
        val mainPid = before.getInt("mainPid")
        output.put("appPid", mainPid)
        observe("A-before", before)
        var write = insert(before, longArrayOf(System.currentTimeMillis() - 1))
        var keys = write.getJSONArray("records")
        var after = awaitSnapshot(keys) {
            ready(it, "history") && it.getJSONObject("history")
                .getLong("revision") >= write.getLong("revision") && it.getJSONObject("history")
                .getLong("count") >= before.getJSONObject("history")
                .getLong("count") + 1 && it.getJSONObject("history").getJSONArray("keys")
                .getJSONObject(0).getBoolean("inLevelSeries")
        }
        assertTuple(before, after)
        assertChanged(before, after, "history")
        observe("A-unchanged-percent-history", after)

        launch("current")
        before = awaitSnapshot { ready(it, "current") && live(it) }
        observe("B-before", before)
        write = insert(before, longArrayOf(System.currentTimeMillis() - 1))
        keys = write.getJSONArray("records")
        after = awaitSnapshot(keys) {
            ready(it, "current") && it.getJSONObject("current")
                .getLong("revision") >= write.getLong("revision") && it.getJSONObject("current")
                .getLong("count") >= before.getJSONObject("current")
                .getLong("count") + 1 && it.getJSONObject("current").getJSONArray("keys")
                .getJSONObject(0).getBoolean("inLevelSeries")
        }
        assertTuple(before, after)
        assertChanged(before, after, "current")
        observe("B-unchanged-state-current-trend", after)

        launch("history")
        before = awaitSnapshot { ready(it, "history") && live(it) }
        device.pressHome()
        val stopped = awaitSnapshot { it.getString("activityStage") == "STOPPED" }
        observe("C-stopped-before", stopped)
        val now = System.currentTimeMillis()
        write = insert(before, longArrayOf(now - 9, now - 6, now - 3))
        keys = write.getJSONArray("records")
        val missed = snapshot(keys)
        assertEquals("STOPPED", missed.getString("activityStage"))
        assertEquals(
            stopped.getJSONObject("history").getLong("chartToken"),
            missed.getJSONObject("history").getLong("chartToken")
        )
        assertEquals(
            stopped.getJSONObject("history").getLong("revision"),
            missed.getJSONObject("history").getLong("revision")
        )
        observe("C-missed-while-stopped", missed)
        launch("history")
        after = awaitSnapshot(keys) {
            ready(
                it, "history"
            ) && it.getString("activityStage") == "RESUMED" && it.getJSONObject("history")
                .getLong("revision") >= write.getLong("revision") && it.getJSONObject("history")
                .getLong("count") >= stopped.getJSONObject("history").getLong("count") + 3
        }
        assertEquals(mainPid, after.getInt("mainPid"))
        assertEquals(stopped.getInt("activityIdentity"), after.getInt("activityIdentity"))
        assertTuple(before, after)
        observe("C-resume-durable-catchup", after)

        val day = Instant.ofEpochMilli(after.getLong("wallMillis"))
            .atZone(ZoneId.of(after.getString("zoneId"))).toLocalDate().minusDays(1)
        chooseCustomDay(day, Locale.forLanguageTag(after.getString("locale")))
        before = awaitSnapshot {
            ready(it, "history") && it.getJSONObject("history").getString("rangeLabel") == "custom"
        }
        val range = before.getJSONObject("history").getJSONObject("range")
        val zone = ZoneId.of(before.getString("zoneId"))
        assertEquals(day.atStartOfDay(zone).toInstant().toEpochMilli(), range.getLong("start"))
        assertEquals(
            day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), range.getLong("end")
        )
        observe("D-closed-range-before", before)
        write = insert(before, longArrayOf(range.getLong("start") - 1))
        after = awaitSnapshot(write.getJSONArray("records")) {
            ready(it, "history") && it.getJSONObject("history")
                .getLong("revision") >= write.getLong("revision")
        }
        assertEquals(
            range.toString(), after.getJSONObject("history").getJSONObject("range").toString()
        )
        assertEquals(
            before.getJSONObject("history").getLong("count"),
            after.getJSONObject("history").getLong("count")
        )
        assertEquals(
            before.getJSONObject("history").getLong("chartToken"),
            after.getJSONObject("history").getLong("chartToken")
        )
        observe("D-outside-acknowledged-same-chart", after)
        before = after
        write = insert(
            before,
            longArrayOf(range.getLong("start") + (range.getLong("end") - range.getLong("start")) / 2 + 471)
        )
        after = awaitSnapshot(write.getJSONArray("records")) {
            ready(it, "history") && it.getJSONObject("history")
                .getLong("revision") >= write.getLong("revision") && it.getJSONObject("history")
                .getLong("count") >= before.getJSONObject("history").getLong("count") + 1
        }
        assertChanged(before, after, "history")
        assertEquals(
            range.toString(), after.getJSONObject("history").getJSONObject("range").toString()
        )
        observe("D-inside-invalidates", after)

        val monitoringBeforeFullscreen = after.getJSONObject("monitoring")
        awaitDescription("history_open_fullscreen").click()
        awaitSnapshot { ready(it, "fullscreen") && it.getString("fullscreenStage") == "RESUMED" }
        clickText("history_battery_level")
        clickText("current_voltage")
        awaitSnapshot {
            ready(it, "fullscreen") && it.getJSONObject("fullscreen")
                .getString("metric") == "VOLTAGE"
        }
        zoomChartIn()
        zoomChartIn()
        val zoomed = awaitSnapshot {
            ready(it, "fullscreen") && it.getJSONObject("fullscreen").getJSONObject("viewport")
                .toString() != it.getJSONObject("fullscreen").getJSONObject("range").toString()
        }
        device.swipe(
            device.displayWidth * 3 / 4,
            device.displayHeight / 2,
            device.displayWidth / 4,
            device.displayHeight / 2,
            20
        )
        awaitSnapshot {
            ready(it, "fullscreen") && it.getJSONObject("fullscreen").getJSONObject("viewport")
                .toString() != zoomed.getJSONObject("fullscreen").getJSONObject("viewport")
                .toString()
        }
        awaitDescription("history_next_point").click()
        before = awaitStableFullscreen("E-before-insert-viewport-stability")
        val detailed = before.getJSONObject("fullscreen")
        val viewport = detailed.getJSONObject("viewport")
        assertTrue(viewport.toString() != detailed.getJSONObject("range").toString())
        observe("E-voltage-zoom-pan-selection-before", before)
        val insertTime =
            viewport.getLong("start") + (viewport.getLong("end") - viewport.getLong("start")) / 2 + 97
        write = insert(
            before, longArrayOf(insertTime), voltage = 19_999, tuple = monitoringBeforeFullscreen
        )
        awaitSnapshot(write.getJSONArray("records")) {
            ready(it, "fullscreen") && it.getJSONObject("fullscreen")
                .getLong("boundsCount") >= detailed.getLong("boundsCount") + 1 && it.getJSONObject("fullscreen")
                .getJSONArray("keys").getJSONObject(0)
                .getBoolean("inMetricSeries") && !it.getJSONObject("fullscreen")
                .isNull("selectedRecordKey")
        }
        after = awaitStableFullscreen(
            "E-after-insert-viewport-stability", write.getJSONArray("records")
        )
        val finalDetailed = after.getJSONObject("fullscreen")
        assertTrue(finalDetailed.getLong("boundsCount") >= detailed.getLong("boundsCount") + 1)
        assertTrue(finalDetailed.getJSONArray("keys").getJSONObject(0).getBoolean("inMetricSeries"))
        assertEquals("VOLTAGE", finalDetailed.getString("metric"))
        assertEquals(viewport.toString(), finalDetailed.getJSONObject("viewport").toString())
        assertEquals(
            detailed.getJSONObject("selectedKey").toString(),
            finalDetailed.getJSONObject("selectedKey").toString()
        )
        assertEquals(
            detailed.getJSONObject("selectedRecordKey").toString(),
            finalDetailed.getJSONObject("selectedRecordKey").toString()
        )
        assertEquals(
            detailed.getJSONObject("range").toString(),
            finalDetailed.getJSONObject("range").toString()
        )
        assertEquals(before.getInt("fullscreenIdentity"), after.getInt("fullscreenIdentity"))
        assertChanged(before, after, "fullscreen")
        observe("E-new-data-preserved-view-and-selection", after)
        output.put("servicePid", servicePid)
        output.put(
            "scope",
            "real existing main Activity ViewModels and running :BIS LogDatabase.addLogs; UI-only route/range/metric/zoom/pan/selection"
        )
    }

    @Test
    fun journalContract() = report("history-journal") { output ->
        val result =
            fixture("VERIFY_JOURNAL", timeout = 90_000) { putExtra("journalMode", "correctness") }
        val json = JSONObject(checkNotNull(result.getString("reportJson")))
        observations.put(JSONObject().put("stage", "journal-contract").put("result", json))
        output.put("appPid", json.getInt("appPid"))
        assertTrue("Journal contract failed: $json", result.getBoolean("complete"))
    }

    // Optional manual experiment: select this method with explicit pattern/row arguments.
    @Test
    fun journalWriteCost() = report("historical-journal-sql-experiment") { output ->
        output.put(
            "scope", "Historical synthetic SQL comparison; not production performance validation"
        )
        val pattern = requireNotNull(arguments.getString("journalPattern"))
        val initial = requireNotNull(arguments.getString("journalInitialRows")?.toIntOrNull())
        require(pattern in setOf("single", "periodic128", "bulkAdd5000", "bulkReplace5000"))
        require(initial in setOf(0, 128))
        val result = fixture("VERIFY_JOURNAL", timeout = 90_000) {
            putExtra("journalMode", "overhead").putExtra("journalPattern", pattern)
                .putExtra("journalInitialRows", initial)
        }
        val json = JSONObject(checkNotNull(result.getString("reportJson")))
        observations.put(
            JSONObject().put("stage", "historical-synthetic-journal-sql").put("result", json)
        )
        output.put("appPid", json.getInt("appPid"))
        assertEquals("overhead", json.getString("mode"))
        assertFalse(json.getBoolean("correctnessExecuted"))
        assertEquals(pattern, json.getString("selectedPattern"))
        assertEquals(initial, json.getInt("selectedSchema7InitialJournalRows"))
        assertTrue("Journal write cost failed: $json", result.getBoolean("complete"))
    }

    @Test
    fun historyIoRoundTrip() = report("history-io") { output ->
        val result = fixture("HISTORY_IO", timeout = 60_000) {
            putExtra("synthetic", true)
            putExtra("exportFilename", checkNotNull(arguments.getString("exportFilename")))
        }
        val json = JSONObject(checkNotNull(result.getString("reportJson")))
        observations.put(
            JSONObject().put("stage", "production-json-io-isolated-restore").put("result", json)
        )
        output.put("appPid", json.getInt("appPid"))
        assertTrue("Production JSON I/O round trip failed: $json", result.getBoolean("complete"))
    }

    @Test
    fun historyRestore() = report("history-restore") { output ->
        val result = fixture("HISTORY_RESTORE", timeout = 60_000) {
            putExtra("synthetic", true)
            putExtra("exportFilename", checkNotNull(arguments.getString("exportFilename")))
            putExtra(
                "expectedExportSha256", checkNotNull(arguments.getString("expectedExportSha256"))
            )
        }
        val json = JSONObject(checkNotNull(result.getString("reportJson")))
        observations.put(
            JSONObject().put("stage", "actual-empty-profile-production-restore").put("result", json)
        )
        assertTrue(result.getBoolean("complete"))
        assertEquals(0, json.getInt("beforeCount"))
        assertTrue(json.getInt("afterCount") > 0)
        assertEquals(json.getString("sourceRecordsSha256"), json.getString("restoredRecordsSha256"))
        readHistoryUi(output)
        output.put(
            "scope",
            "production JSON restore into actual empty profile logs.db, followed by actual History rolling/all charts, Events and reading details; excludes SAF picker UI"
        )
    }

    private fun insert(
        state: JSONObject,
        times: LongArray,
        voltage: Int = 4000,
        tuple: JSONObject = state.getJSONObject("monitoring")
    ): JSONObject {
        val response = fixture("HISTORY_INSERT", bis = true) {
            putExtra("synthetic", true).putExtra("times", times)
                .putExtra("percent", tuple.getInt("percent"))
                .putExtra("status", tuple.getInt("status"))
                .putExtra("plugged", tuple.getInt("plugged")).putExtra("voltage", voltage)
        }
        val write = JSONObject(checkNotNull(response.getString("reportJson")))
        observations.put(
            JSONObject().put("stage", "BIS-write").put("suppliedActualMonitoringTuple", tuple)
                .put("result", write)
        )
        assertEquals(write.getInt("pid"), write.getInt("servicePid"))
        assertEquals(state.getInt("uid"), write.getInt("uid"))
        assertEquals(state.getInt("servicePid"), write.getInt("pid"))
        assertTrue(
            "Writer must be a separate real BIS process",
            write.getInt("pid") != state.getInt("mainPid")
        )
        servicePid?.let { assertEquals(it, write.getInt("pid")) }
        servicePid = write.getInt("pid")
        return write
    }

    private fun snapshot(keys: JSONArray = JSONArray()): JSONObject =
        JSONObject(checkNotNull(fixture("HISTORY_STATE") {
            putExtra(
                "keys", keys.toString()
            )
        }.getString("reportJson"))).also { lastSnapshot = it }

    private fun awaitSnapshot(
        keys: JSONArray = JSONArray(), condition: (JSONObject) -> Boolean
    ): JSONObject {
        val end = SystemClock.elapsedRealtime() + TIMEOUT
        do {
            val state = snapshot(keys)
            if (condition(state)) return state
            SystemClock.sleep(150)
        } while (SystemClock.elapsedRealtime() < end)
        error("Actual UI state did not settle: $lastSnapshot")
    }

    private fun awaitStableFullscreen(stage: String, keys: JSONArray = JSONArray()): JSONObject {
        val settling = JSONObject().put("stage", stage).put("requiredUnchangedMillis", 1_000)
            .put("requestedKeys", keys)
        val samples = JSONArray()
        settling.put("samples", samples)
        observations.put(settling)
        val settleStarted = SystemClock.elapsedRealtime()
        var stableSince = settleStarted
        var lastSignature: String? = null
        while (true) {
            val state = snapshot(keys)
            val candidate = state.getJSONObject("fullscreen")
            val now = SystemClock.elapsedRealtime()
            val usable =
                ready(state, "fullscreen") && !candidate.isNull("selectedKey") && !candidate.isNull(
                    "selectedRecordKey"
                )
            val signature = if (usable) listOf(
                candidate.getJSONObject("viewport").toString(),
                candidate.getJSONObject("selectedKey").toString(),
                candidate.getLong("chartToken")
            ).joinToString("|") else null
            samples.put(
                JSONObject().put("elapsedMillis", now).put("usable", usable)
                    .put("viewport", candidate.getJSONObject("viewport"))
                    .put("selectedKey", candidate.opt("selectedKey"))
                    .put("chartToken", candidate.opt("chartToken"))
                    .put("loading", candidate.getBoolean("loading"))
                    .put("keys", candidate.getJSONArray("keys"))
            )
            if (signature == null || signature != lastSignature) stableSince = now
            lastSignature = signature
            if (signature != null && now - stableSince >= 1_000) {
                settling.put("unchangedMillis", now - stableSince)
                    .put("totalMillis", now - settleStarted)
                return state
            }
            check(now - settleStarted < TIMEOUT) { "Fullscreen viewport/selection/chart did not settle: $stage" }
            SystemClock.sleep(150)
        }
    }

    private fun ready(state: JSONObject, name: String): Boolean = state.optJSONObject(name)?.let {
        !it.optBoolean("loading") && !it.optBoolean("error") && !it.isNull("count") && it.getLong("count") > 0
    } == true

    private fun live(state: JSONObject) =
        state.optJSONObject("monitoring")?.optString("availability") == "LIVE"

    private fun assertTuple(before: JSONObject, after: JSONObject) {
        listOf("percent", "status", "plugged").forEach {
            assertEquals(
                "Monitoring $it changed during the scenario",
                before.getJSONObject("monitoring").getInt(it),
                after.getJSONObject("monitoring").getInt(it)
            )
        }
    }

    private fun assertChanged(before: JSONObject, after: JSONObject, name: String) = assertTrue(
        "$name must contain a newly loaded chart object",
        before.getJSONObject(name).getLong("chartToken") != after.getJSONObject(name)
            .getLong("chartToken")
    )

    private fun chooseRange(name: String) {
        clickText("history_change_range")
        clickText(name, last = true)
    }

    private fun chooseCustomDay(day: LocalDate, locale: Locale) {
        clickText("history_change_range")
        clickText("history_custom_range")
        listOf("history_from", "history_through").forEach { label ->
            val prefix = text(label).substringBefore("%")
            checkNotNull(
                device.wait(
                    Until.findObject(By.pkg(TARGET).textStartsWith(prefix)), TIMEOUT
                )
            ).click()
            val switch = device.wait(
                Until.findObject(
                    By.pkg(TARGET).desc(text("m3c_date_picker_switch_to_input_mode"))
                ), 500
            )
            switch?.click()
            val field = checkNotNull(
                device.wait(
                    Until.findObject(
                        By.pkg(TARGET).clazz("android.widget.EditText")
                    ), TIMEOUT
                )
            )
            val order = android.text.format.DateFormat.getDateFormatOrder(
                context.createConfigurationContext(
                    android.content.res.Configuration(context.resources.configuration)
                        .apply { setLocale(locale) })
            )
            field.text = order.joinToString("") { part ->
                when (part) {
                    'd' -> String.format(Locale.ROOT, "%02d", day.dayOfMonth)
                    'M' -> String.format(Locale.ROOT, "%02d", day.monthValue)
                    'y' -> String.format(Locale.ROOT, "%04d", day.year)
                    else -> error("Unknown date input order")
                }
            }
            dismissImeIfNeeded()
            clickText("okay", last = true)
            awaitText("history_calendar_days")
        }
        clickText("okay", last = true)
    }

    private fun dismissImeIfNeeded() {
        val windows = instrumentation.uiAutomation.windows
        try {
            if (windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) device.pressBack()
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    private fun zoomChartIn() {
        val wanted = text("history_chart_zoom_in")
        val overflow = text("history_chart_more")
        // Runtime action and overflow descriptions are distinct, although both are unknown to the IDE.
        @Suppress("RegExpDuplicateAlternationBranch") val descriptions =
            Pattern.compile("${Pattern.quote(wanted)}|${Pattern.quote(overflow)}")
        val node = checkNotNull(
            device.wait(
                Until.findObject(
                    By.pkg(TARGET).desc(descriptions).enabled(true)
                ), TIMEOUT
            )
        )
        val menu = node.contentDescription == overflow
        node.click()
        if (menu) clickText("history_chart_zoom_in")
    }

    private fun launch(route: String, detail: String? = null) {
        context.startActivity(
            Intent().setComponent(ComponentName(TARGET, MAIN_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("codes.swistak.batterymonitor.EXTRA_SECTION", route).apply {
                    if (detail != null) putExtra(
                        "codes.swistak.batterymonitor.EXTRA_DETAIL", detail
                    )
                })
        val navigationLabel = if (detail == null) "nav_open_menu" else "nav_up"
        val navigation = By.pkg(TARGET).desc(text(navigationLabel)).enabled(true)
        val prompt = By.pkg(TARGET).text(text("battery_optimized"))
        check(device.wait(Condition {
            it.hasObject(navigation) || it.hasObject(prompt)
        }, TIMEOUT) == true) { "Neither navigation nor battery optimization prompt became visible" }
        if (device.hasObject(prompt)) {
            clickText("cancel")
            observations.put(
                JSONObject().put("stage", "startup-battery-prompt-cancelled").put("route", route)
            )
        }
        awaitDescription(navigationLabel)
    }

    private fun fixture(
        command: String,
        bis: Boolean = false,
        timeout: Long = 30_000,
        extras: Intent.() -> Unit = {}
    ): Bundle {
        val done = CountDownLatch(1)
        val result = AtomicReference<Bundle>()
        val request = Intent("$TARGET.$command").setComponent(
            ComponentName(
                TARGET,
                "codes.swistak.batterymonitor.profiling.${if (bis) "ProfileHistoryReceiver" else "ProfileFixtureReceiver"}"
            )
        ).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).apply(extras)
        context.sendOrderedBroadcast(request, null, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                result.set(requireNotNull(getResultExtras(true)).apply {
                    putInt(
                        "resultCode", resultCode
                    )
                })
                done.countDown()
            }
        }, null, Activity.RESULT_CANCELED, null, null)
        check(done.await(timeout, TimeUnit.MILLISECONDS)) { "$command timed out" }
        return checkNotNull(result.get()).also {
            check(it.getInt("resultCode") == Activity.RESULT_OK) {
                "$command failed: ${
                    it.getString(
                        "error"
                    )
                }"
            }
        }
    }

    private fun observe(stage: String, state: JSONObject) {
        observations.put(JSONObject().put("stage", stage).put("state", state))
        recordUi(stage)
    }

    private fun recordUi(stage: String) {
        observations.put(
            JSONObject().put("stage", "$stage-visible")
                .put(
                    "texts",
                    JSONArray(
                        device.findObjects(By.pkg(TARGET).textStartsWith("")).mapNotNull { it.text }
                            .distinct())))
        val root = outputDirectory()
        device.takeScreenshot(File(root, "history-${phase()}-$stage.png"))
    }

    private fun report(name: String, block: (JSONObject) -> Unit) {
        assumeTrue(
            "Explicit finalVerification=true required",
            arguments.getString("finalVerification") == "true"
        )
        require(phase().matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val result = JSONObject().put("package", TARGET).put("phase", phase()).put("passed", false)
            .put("harnessPid", android.os.Process.myPid()).put("observations", observations)
        try {
            if (!context.getSystemService(PowerManager::class.java).isInteractive) device.wakeUp()
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            if (keyguard.isKeyguardLocked && !keyguard.isDeviceSecure) {
                device.executeShellCommand("wm dismiss-keyguard")
                val deadline = SystemClock.elapsedRealtime() + TIMEOUT
                while (keyguard.isKeyguardLocked && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(
                    50
                )
            }
            check(!keyguard.isKeyguardLocked) { "Owner must unlock secured device" }
            block(result)
            result.put("passed", true)
        } catch (error: Throwable) {
            result.put("failure", error.stackTraceToString())
            runCatching { recordUi("$name-failed") }.onFailure {
                result.put("failureEvidenceError", it.javaClass.simpleName + ": " + it.message)
            }
            throw error
        } finally {
            result.put("lastSnapshot", lastSnapshot ?: JSONObject.NULL)
            val file = File(outputDirectory(), "$name-${phase()}.json")
            file.writeText(result.toString(2))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("profilingRecord", name)
                putString("phase", phase())
                putString("outputPath", file.path)
                putString("reportJson", result.toString())
            })
        }
    }

    private fun phase() = arguments.getString("phase") ?: "verification"
    private fun outputDirectory() =
        File(checkNotNull(arguments.getString("additionalTestOutputDir")) {
            "Explicit owned additionalTestOutputDir required"
        }).apply { check(isDirectory || mkdirs()) }

    private fun applicationPid(): Int {
        val output = device.executeShellCommand("pidof $TARGET").trim()
        return output.split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
            ?: error("Main application PID unavailable: $output")
    }

    private fun text(name: String): String {
        val id = resources.getIdentifier(name, "string", TARGET).takeIf { it != 0 }
            ?: resources.getIdentifier(name, "string", "codes.swistak.batterymonitor")
        check(id != 0) { "Unknown string $name" }
        return resources.getString(id)
    }

    private fun awaitText(name: String): UiObject2 =
        device.wait(Until.findObject(By.pkg(TARGET).text(text(name)).enabled(true)), TIMEOUT)
            ?: error("Missing visible text $name")

    private fun clickText(name: String, last: Boolean = false) {
        awaitText(name)
        val matches = device.findObjects(By.pkg(TARGET).text(text(name)).enabled(true))
        (if (last) matches.last() else matches.first()).click()
    }

    private fun awaitDescription(name: String): UiObject2 =
        device.wait(Until.findObject(By.pkg(TARGET).desc(text(name)).enabled(true)), TIMEOUT)
            ?: error("Missing visible description $name")

    companion object {
        private const val TARGET = "codes.swistak.batterymonitor.profile"
        private const val MAIN_ACTIVITY = "codes.swistak.batterymonitor.app.BatteryInfoActivity"
        private const val TIMEOUT = 30_000L
    }
}
