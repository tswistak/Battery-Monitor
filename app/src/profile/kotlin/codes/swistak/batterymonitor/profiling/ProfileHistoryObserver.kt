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
package codes.swistak.batterymonitor.profiling

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.app.PersistentFragment
import codes.swistak.batterymonitor.logs.HistoryKey
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import codes.swistak.batterymonitor.ui.current.CurrentStateViewModel
import codes.swistak.batterymonitor.ui.history.FullscreenChartActivity
import codes.swistak.batterymonitor.ui.history.FullscreenChartViewModel
import codes.swistak.batterymonitor.ui.history.HistoryViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.time.ZoneId
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal object ProfileHistoryObserver : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private var activity = WeakReference<BatteryInfoActivity>(null)
    private var fullscreen = WeakReference<FullscreenChartActivity>(null)
    private var history = WeakReference<HistoryViewModel>(null)
    private var current = WeakReference<CurrentStateViewModel>(null)
    private var detailed = WeakReference<FullscreenChartViewModel>(null)
    private var activityStage = "UNSEEN"
    private var fullscreenStage = "UNSEEN"
    private var resumed = 0
    private var stopped = 0
    private var nextToken = 0L
    private val chartObjects = mutableMapOf<String, Pair<WeakReference<Any>, Long>>()

    fun watch(context: Context): Bundle = onMain {
        check(context.packageName == "codes.swistak.batterymonitor.profile")
        if (!registered) {
            (context.applicationContext as Application).registerActivityLifecycleCallbacks(this)
            registered = true
        }
        Bundle().apply {
            putBoolean("watching", registered)
            putInt("mainPid", Process.myPid())
        }
    }

    fun snapshot(context: Context, intent: Intent): Bundle = onMain {
        check(registered) { "HISTORY_WATCH must precede Activity launch" }
        val text = intent.getStringExtra("keys") ?: "[]"
        require(text.length <= 4096)
        val requested = JSONArray(text)
        require(requested.length() <= 16)
        val keys = (0 until requested.length()).map { index ->
            requested.getJSONObject(index).let { HistoryKey(it.getLong("time"), it.getLong("id")) }
        }
        val result = JSONObject().put(
            "scope", "read-only existing Activity ViewModels; no refresh or history receiver"
        ).put("mainPid", Process.myPid()).put("uid", Process.myUid())
            .put("elapsedMillis", SystemClock.elapsedRealtime())
            .put("wallMillis", System.currentTimeMillis()).put("zoneId", ZoneId.systemDefault().id)
            .put("locale", context.resources.configuration.locales[0].toLanguageTag())
            .put("activityStage", activityStage).put("fullscreenStage", fullscreenStage)
            .put("resumedCallbacks", resumed).put("stoppedCallbacks", stopped)

        @Suppress("DEPRECATION") val service =
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(
                100
            ).firstOrNull { it.service.className == BatteryInfoService::class.java.name }
        result.put("servicePid", service?.pid ?: JSONObject.NULL)
        result.put("serviceComponent", service?.service?.flattenToString() ?: JSONObject.NULL)
        activity.get()?.let { owner ->
            result.put("activityIdentity", System.identityHashCode(owner))
            result.put("activityLifecycle", owner.lifecycle.currentState.name)
            val connection =
                (owner.supportFragmentManager.findFragmentByTag(PersistentFragment.FRAG_TAG) as? PersistentFragment)?.monitoring
            connection?.state?.value?.let { state ->
                val monitoring = JSONObject().put("availability", state.availability.name)
                    .put("processId", state.processId ?: JSONObject.NULL)
                    .put("sequence", state.sequence)
                state.snapshot?.let { sample ->
                    monitoring.put("percent", sample.levelPercent).put("status", sample.status)
                        .put("plugged", sample.plugged)
                        .put("observedAtMillis", sample.observedAtMillis)
                }
                result.put("monitoring", monitoring)
            }
        }
        history.get()?.state?.value?.let { state ->
            val chart = state.chart
            result.put(
                "history",
                JSONObject().put("range", range(state.range)).put("rangeLabel", state.rangeLabel)
                    .put("tab", state.tab).put("metric", state.metric.name)
                    .put("revision", state.revision).put("loading", state.loading)
                    .put("error", state.error).put("chartToken", token("history", chart))
                    .put("count", chart?.count ?: JSONObject.NULL)
                    .put("selectedPointId", state.selectedPointId ?: JSONObject.NULL)
                    .put("pageCount", state.page.size).put("keys", JSONArray(keys.map { target ->
                        key(target).put("inPage", state.page.any { it.key == target })
                            .put(
                                "inLevelSeries",
                                chart?.series?.get(HistoryMetric.LEVEL)?.points?.any { it.key == target } == true)
                    }))
            )
        }
        current.get()?.let { model ->
            result.put(
                "current",
                JSONObject().put("revision", model.trendRevision ?: JSONObject.NULL)
                    .put("chartToken", token("current", model.trend))
                    .put("count", model.trend?.count ?: JSONObject.NULL)
                    .put("range", model.trend?.range?.let(::range) ?: JSONObject.NULL)
                    .put("keys", JSONArray(keys.map { target ->
                        key(target).put(
                            "inLevelSeries",
                            model.trend?.series?.get(HistoryMetric.LEVEL)?.points?.any { it.key == target } == true)
                    }))
            )
        }
        fullscreen.get()?.let { result.put("fullscreenIdentity", System.identityHashCode(it)) }
        detailed.get()?.state?.value?.let { state ->
            result.put(
                "fullscreen",
                JSONObject().put("range", range(state.range)).put("viewport", range(state.viewport))
                    .put("rangeLabel", state.rangeLabel).put("metric", state.metric.name)
                    .put("comparison", state.comparison?.name ?: JSONObject.NULL)
                    .put("selectedKey", state.selectedKey?.let(::key) ?: JSONObject.NULL).put(
                        "selectedRecordKey",
                        state.selectedRecord?.key?.let(::key) ?: JSONObject.NULL
                    ).put("chartToken", token("fullscreen", state.chart))
                    .put("count", state.chart?.count ?: JSONObject.NULL)
                    .put("boundsCount", state.bounds?.count ?: JSONObject.NULL)
                    .put("loading", state.loading).put("error", state.error)
                    .put("keys", JSONArray(keys.map { target ->
                        key(target).put(
                            "inMetricSeries",
                            state.chart?.points?.any { it.key == target } == true)
                    }))
            )
        }
        Bundle().apply { putString("reportJson", result.toString()) }
    }

    private fun token(name: String, value: Any?): Any {
        if (value == null) return JSONObject.NULL
        val old = chartObjects[name]
        if (old != null && old.first.get() === value) return old.second
        return (++nextToken).also { chartObjects[name] = WeakReference(value) to it }
    }

    private fun range(value: HistoryRangeState) =
        JSONObject().put("start", value.start).put("end", value.end)

    private fun key(value: HistoryKey) = JSONObject().put("time", value.time).put("id", value.id)

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        check(main.post(task))
        return task.get(10, TimeUnit.SECONDS)
    }

    override fun onActivityResumed(owner: Activity) {
        if (owner is BatteryInfoActivity) {
            activity = WeakReference(owner)
            history = WeakReference(ViewModelProvider(owner)[HistoryViewModel::class.java])
            current = WeakReference(ViewModelProvider(owner)[CurrentStateViewModel::class.java])
            activityStage = "RESUMED"
            resumed++
        } else if (owner is FullscreenChartActivity) {
            fullscreen = WeakReference(owner)
            detailed = WeakReference(ViewModelProvider(owner)[FullscreenChartViewModel::class.java])
            fullscreenStage = "RESUMED"
        }
    }

    override fun onActivityPaused(owner: Activity) {
        if (owner === activity.get()) activityStage = "PAUSED"
        if (owner === fullscreen.get()) fullscreenStage = "PAUSED"
    }

    override fun onActivityStopped(owner: Activity) {
        if (owner === activity.get()) {
            activityStage = "STOPPED"
            stopped++
        }
        if (owner === fullscreen.get()) fullscreenStage = "STOPPED"
    }

    override fun onActivityDestroyed(owner: Activity) {
        if (owner === activity.get()) activityStage = "DESTROYED"
        if (owner === fullscreen.get()) fullscreenStage = "DESTROYED"
    }

    override fun onActivityCreated(owner: Activity, saved: Bundle?) = Unit
    override fun onActivityStarted(owner: Activity) = Unit
    override fun onActivitySaveInstanceState(owner: Activity, saved: Bundle) = Unit
}
