/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.historyChart
import codes.swistak.batterymonitor.logs.historyQuery
import codes.swistak.batterymonitor.monitoring.BackgroundServiceWatchdog
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

internal object WidgetUpdateDispatcher {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    private val policy = WidgetUpdatePolicy()
    private val lock = Any()
    private val pendingIds = mutableSetOf<Int>()
    private val completions = mutableListOf<() -> Unit>()
    private var allIds = false
    private var pendingHistoryChange = false
    private var queued = false
    private var latestSnapshot: WidgetSnapshot? = null
    private var persistedSnapshot: WidgetSnapshot? = null
    private val trends = mutableMapOf<Int, Pair<Long, WidgetTrend>>()

    suspend fun previewData(context: Context, trendHours: Int?): WidgetPreviewData =
        withContext(dispatcher) {
            WidgetPreviewData(snapshot = synchronized(lock) { latestSnapshot } ?: storedSnapshot(
                context
            ),
                monitoringEnabled = BackgroundServiceWatchdog.isServiceDesired(context),
                trend = trendHours?.let { trend(context, it) })
        }

    fun publish(context: Context, snapshot: WidgetSnapshot, force: Boolean = false) {
        synchronized(lock) {
            latestSnapshot = snapshot
            if (!policy.shouldPublish(snapshot, SystemClock.elapsedRealtime(), force)) return
            allIds = true
            enqueue(context.applicationContext)
        }
    }

    fun refresh(context: Context, ids: IntArray? = null, onComplete: (() -> Unit)? = null) {
        synchronized(lock) {
            if (ids == null) allIds = true else pendingIds.addAll(ids.toList())
            onComplete?.let(completions::add)
            enqueue(context.applicationContext)
        }
    }

    fun historyChanged(context: Context, onComplete: () -> Unit) {
        synchronized(lock) {
            pendingHistoryChange = true
            completions.add(onComplete)
            enqueue(context.applicationContext)
        }
    }

    private fun enqueue(context: Context) {
        if (queued) return
        queued = true
        executor.execute {
            while (true) {
                val work = synchronized(lock) {
                    if (!allIds && !pendingHistoryChange && pendingIds.isEmpty() && completions.isEmpty()) {
                        queued = false
                        return@execute
                    }
                    Work(
                        allIds,
                        pendingIds.toIntArray(),
                        latestSnapshot,
                        pendingHistoryChange,
                        completions.toList()
                    ).also {
                        allIds = false
                        pendingIds.clear()
                        pendingHistoryChange = false
                        completions.clear()
                    }
                }
                try {
                    if (work.historyChanged) trends.clear()
                    render(context, work)
                } catch (error: Exception) {
                    Log.w("WidgetDispatcher", "Unable to publish widgets", error)
                } finally {
                    finishCallbacks(work.completions)
                }
            }
        }
    }

    internal fun finishCallbacks(
        callbacks: List<() -> Unit>,
        onError: (Exception) -> Unit = { Log.w("WidgetDispatcher", "Widget completion failed", it) }
    ) {
        for (callback in callbacks) {
            try {
                callback()
            } catch (error: Exception) {
                onError(error)
            }
        }
    }

    private class Work(
        val all: Boolean,
        val ids: IntArray,
        val snapshot: WidgetSnapshot?,
        val historyChanged: Boolean,
        val completions: List<() -> Unit>
    )

    private fun render(context: Context, work: Work) {
        val manager = AppWidgetManager.getInstance(context)
        val targets = WidgetFamily.entries.associateWith { family ->
            val component = ComponentName(context.packageName, family.providerName)
            val ids = if (work.all || work.historyChanged && family == WidgetFamily.TREND) {
                manager.getAppWidgetIds(component)
            } else work.ids
            ids.filter { manager.getAppWidgetInfo(it)?.provider == component }
        }
        if (targets.values.all { it.isEmpty() }) return
        val store = WidgetConfigurationStore(context)
        val snapshot = work.snapshot ?: storedSnapshot(context)
        if (snapshot != null && snapshot != persistedSnapshot && WidgetSnapshotStore.write(
                context, snapshot
            )
        ) {
            persistedSnapshot = snapshot
        }
        val monitoringEnabled = BackgroundServiceWatchdog.isServiceDesired(context)
        val batchTrends = mutableMapOf<Int, WidgetTrend?>()
        for ((family, ids) in targets) {
            for (id in ids) {
                try {
                    val config = store.load(id, family)
                    val options = manager.getAppWidgetOptions(id)
                    val trend = if (family == WidgetFamily.TREND) {
                        if (!batchTrends.containsKey(config.trendHours)) {
                            batchTrends[config.trendHours] = trend(context, config.trendHours)
                        }
                        batchTrends[config.trendHours]
                    } else null
                    val views = WidgetSizes.remoteViews(family, options) { size ->
                        WidgetRenderer.render(
                            context,
                            id,
                            family,
                            config,
                            snapshot,
                            size.widthDp,
                            size.heightDp,
                            trend,
                            monitoringEnabled
                        )
                    }
                    manager.updateAppWidget(id, views)
                } catch (error: Exception) {
                    Log.w("WidgetDispatcher", "Unable to update widget $id", error)
                }
            }
        }
    }

    private fun storedSnapshot(context: Context): WidgetSnapshot? =
        persistedSnapshot ?: WidgetSnapshotStore.read(context)?.also { persistedSnapshot = it }

    private fun trend(context: Context, hours: Int): WidgetTrend? {
        val elapsed = SystemClock.elapsedRealtime()
        trends[hours]?.let { (last, trend) ->
            if (elapsed - last in 0..<15 * 60_000L) return trend
        }
        val range = HistoryRangeState.lastHours(hours)
        val db = LogDatabase(context)
        return try {
            val chart = db.readHistory(historyQuery(range)) {
                historyChart(range, it, metrics = listOf(HistoryMetric.LEVEL))
            }
            WidgetTrend.fromHistory(chart).also {
                trends[hours] = elapsed to it
            }
        } catch (error: Exception) {
            Log.w("WidgetDispatcher", "Unable to read widget history", error)
            null
        } finally {
            db.close()
        }
    }
}

internal data class WidgetPreviewData(
    val snapshot: WidgetSnapshot?, val monitoringEnabled: Boolean, val trend: WidgetTrend?
)
