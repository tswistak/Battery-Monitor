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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal object WidgetUpdateDispatcher {
    private val executor = Executors.newSingleThreadExecutor()
    private val policy = WidgetUpdatePolicy()
    private val lock = Any()
    private val pendingIds = mutableSetOf<Int>()
    private val completions = mutableListOf<() -> Unit>()
    private var allIds = false
    private var pendingForce = false
    private var queued = false
    private var pendingSnapshot: WidgetSnapshot? = null
    private var pendingPublish = false
    private val trends = ConcurrentHashMap<Int, Pair<Long, WidgetTrend>>()

    fun cachedTrend(hours: Int): WidgetTrend? = trends[hours]?.second

    fun preparePreview(context: Context, hours: Int, onComplete: () -> Unit) {
        executor.execute {
            try {
                trend(context.applicationContext, hours, force = false)
            } finally {
                finishCallbacks(listOf(onComplete))
            }
        }
    }

    fun publish(context: Context, snapshot: WidgetSnapshot, force: Boolean = false) {
        synchronized(lock) {
            pendingSnapshot = snapshot
            pendingPublish = pendingPublish || policy.shouldPublish(
                snapshot, SystemClock.elapsedRealtime(), force
            )
            pendingForce = pendingForce || force
            allIds = true
            enqueue(context.applicationContext)
        }
    }

    fun refresh(context: Context, ids: IntArray? = null, onComplete: (() -> Unit)? = null) {
        synchronized(lock) {
            if (ids == null) allIds = true else pendingIds.addAll(ids.toList())
            pendingForce = true
            pendingPublish = true
            onComplete?.let(completions::add)
            enqueue(context.applicationContext)
        }
    }

    private fun enqueue(context: Context) {
        if (queued) return
        queued = true
        executor.execute {
            while (true) {
                val work = synchronized(lock) {
                    if (!allIds && pendingIds.isEmpty() && pendingSnapshot == null && completions.isEmpty()) {
                        queued = false
                        return@execute
                    }
                    Work(
                        allIds,
                        pendingIds.toIntArray(),
                        pendingSnapshot,
                        pendingPublish,
                        pendingForce,
                        completions.toList()
                    ).also {
                        allIds = false
                        pendingIds.clear()
                        pendingSnapshot = null
                        pendingPublish = false
                        pendingForce = false
                        completions.clear()
                    }
                }
                try {
                    work.snapshot?.let { WidgetSnapshotStore.write(context, it) }
                    if (work.publish) render(context, work)
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

    private data class Work(
        val all: Boolean,
        val ids: IntArray,
        val snapshot: WidgetSnapshot?,
        val publish: Boolean,
        val force: Boolean,
        val completions: List<() -> Unit>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Work

            if (all != other.all) return false
            if (publish != other.publish) return false
            if (force != other.force) return false
            if (!ids.contentEquals(other.ids)) return false
            if (snapshot != other.snapshot) return false
            if (completions != other.completions) return false

            return true
        }

        override fun hashCode(): Int {
            var result = all.hashCode()
            result = 31 * result + publish.hashCode()
            result = 31 * result + force.hashCode()
            result = 31 * result + ids.contentHashCode()
            result = 31 * result + snapshot.hashCode()
            result = 31 * result + completions.hashCode()
            return result
        }
    }

    private fun render(context: Context, work: Work) {
        val manager = AppWidgetManager.getInstance(context)
        val store = WidgetConfigurationStore(context)
        val snapshot = work.snapshot ?: WidgetSnapshotStore.read(context)
        val monitoringEnabled = BackgroundServiceWatchdog.isServiceDesired(context)
        val batchTrends = mutableMapOf<Int, WidgetTrend?>()
        for (family in WidgetFamily.entries) {
            val component = ComponentName(context.packageName, family.providerName)
            val ids = if (work.all) manager.getAppWidgetIds(component) else work.ids
            for (id in ids) {
                if (manager.getAppWidgetInfo(id)?.provider != component) continue
                try {
                    val config = store.load(id, family)
                    val options = manager.getAppWidgetOptions(id)
                    val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
                        .coerceIn(100, 1200)
                    val maxHeight =
                        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180)
                            .coerceIn(80, 1200)
                    val maxWidth =
                        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
                            .coerceIn(100, 1200)
                    val minHeight =
                        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, maxHeight)
                            .coerceIn(80, 1200)
                    val trend = if (family == WidgetFamily.TREND) {
                        if (!batchTrends.containsKey(config.trendHours)) {
                            batchTrends[config.trendHours] =
                                trend(context, config.trendHours, work.force)
                        }
                        batchTrends[config.trendHours]
                    } else null
                    val portrait = WidgetRenderer.render(
                        context,
                        id,
                        family,
                        config,
                        snapshot,
                        minWidth,
                        maxHeight,
                        trend,
                        monitoringEnabled
                    )
                    val views = if (minWidth == maxWidth && minHeight == maxHeight) portrait else {
                        val landscape = WidgetRenderer.render(
                            context,
                            id,
                            family,
                            config,
                            snapshot,
                            maxWidth,
                            minHeight,
                            trend,
                            monitoringEnabled
                        )
                        android.widget.RemoteViews(landscape, portrait)
                    }
                    manager.updateAppWidget(id, views)
                } catch (error: Exception) {
                    Log.w("WidgetDispatcher", "Unable to update widget $id", error)
                }
            }
        }
    }

    private fun trend(context: Context, hours: Int, force: Boolean): WidgetTrend? {
        val elapsed = SystemClock.elapsedRealtime()
        trends[hours]?.let { (last, trend) ->
            if (!force && elapsed - last in 0..<15 * 60_000L) return trend
        }
        val range = HistoryRangeState.lastHours(hours)
        val db = LogDatabase(context)
        return try {
            val chart = db.readHistory(historyQuery(range)) { historyChart(range, it) }
            val series = chart.series.getValue(HistoryMetric.LEVEL)
            val points = series.points.map { WidgetTrendPoint(it.key.time, it.value, it.segment) }
            WidgetTrend(
                range.start, range.end, points, series.min, series.max, points.hashCode().toLong()
            ).also {
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
