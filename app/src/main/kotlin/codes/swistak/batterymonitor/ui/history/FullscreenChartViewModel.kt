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
package codes.swistak.batterymonitor.ui.history

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import codes.swistak.batterymonitor.logs.DetailedHistoryBounds
import codes.swistak.batterymonitor.logs.DetailedHistoryChartModel
import codes.swistak.batterymonitor.logs.HistoryKey
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.HistoryRecord
import codes.swistak.batterymonitor.logs.LogsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal enum class ChartScaleMode { AUTOMATIC, FIXED, MANUAL }
internal enum class ChartDrawingStyle { CONNECTED, POINTS }
internal enum class ChartValueScale { LINEAR, LOGARITHMIC }

internal data class FullscreenChartUiState(
    val range: HistoryRangeState,
    val rangeLabel: String,
    val metric: HistoryMetric,
    val comparison: HistoryMetric? = null,
    val viewport: HistoryRangeState = range,
    val scaleMode: ChartScaleMode = ChartScaleMode.AUTOMATIC,
    val selectedKey: HistoryKey? = null,
    val charts: Map<HistoryMetric, DetailedHistoryChartModel> = emptyMap(),
    val boundsByMetric: Map<HistoryMetric, DetailedHistoryBounds> = emptyMap(),
    val style: ChartDrawingStyle = ChartDrawingStyle.CONNECTED,
    val valueScale: ChartValueScale = ChartValueScale.LINEAR,
    val selectedRecord: HistoryRecord? = null,
    val loading: Boolean = false,
    val error: Boolean = false
) {
    val metrics: List<HistoryMetric> get() = listOfNotNull(metric, comparison)
    val chart: DetailedHistoryChartModel? get() = charts[metric]
    val bounds: DetailedHistoryBounds? get() = boundsByMetric[metric]

    fun withMetric(next: HistoryMetric): FullscreenChartUiState = copy(
        metric = next, comparison = if (next == comparison) metric else comparison
    )

    fun withComparison(next: HistoryMetric?): FullscreenChartUiState =
        if (next == metric) this else copy(comparison = next)
}

internal fun boundedChartViewport(
    range: HistoryRangeState, viewport: HistoryRangeState
): HistoryRangeState {
    val start = viewport.start.coerceIn(range.start, range.end - 1)
    return HistoryRangeState(start, viewport.end.coerceIn(start + 1, range.end))
}

internal class FullscreenChartViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LogsRepository(application)
    private val mutable = MutableStateFlow<FullscreenChartUiState?>(null)
    val state = mutable.asStateFlow()
    private var query: Job? = null
    private var selectionQuery: Job? = null
    private var generation = 0L
    private var pixelWidth = 800
    private var boundsStale = true

    fun initialize(metric: HistoryMetric, range: HistoryRangeState, label: String, saved: Bundle?) {
        if (mutable.value != null) return
        val restoredRange = saved?.validRange("start", "end") ?: range
        val restoredMetric = saved?.getString("metric")?.let { name ->
            HistoryMetric.entries.firstOrNull { it.name == name }
        } ?: metric
        mutable.value = FullscreenChartUiState(
            range = restoredRange,
            rangeLabel = saved?.getString("label") ?: label,
            metric = restoredMetric,
            comparison = HistoryMetric.entries.firstOrNull {
                it.name == saved?.getString("comparison") && it != restoredMetric
            },
            viewport = boundedChartViewport(
                restoredRange, saved?.validRange("visibleStart", "visibleEnd") ?: restoredRange
            ),
            scaleMode = ChartScaleMode.entries.firstOrNull { it.name == saved?.getString("scale") }
                ?: ChartScaleMode.AUTOMATIC,
            style = ChartDrawingStyle.entries.firstOrNull { it.name == saved?.getString("style") }
                ?: ChartDrawingStyle.CONNECTED,
            valueScale = ChartValueScale.entries.firstOrNull { it.name == saved?.getString("valueScale") }
                ?: ChartValueScale.LINEAR,
            selectedKey = saved?.takeIf { it.containsKey("selectedId") }?.let {
                HistoryKey(it.getLong("selectedTime"), it.getLong("selectedId"))
            })
        refresh()
    }

    fun metric(metric: HistoryMetric) {
        val before = mutable.value ?: return
        if (before.metric == metric) return
        mutable.value = before.withMetric(metric)
        load(debounce = false)
    }

    fun comparison(metric: HistoryMetric?) {
        val before = mutable.value ?: return
        val after = before.withComparison(metric)
        if (before == after) return
        mutable.value = after
        load(debounce = false)
    }

    fun range(range: HistoryRangeState, label: String) {
        val before = mutable.value ?: return
        mutable.value = before.copy(
            range = range,
            rangeLabel = label,
            viewport = range,
            selectedKey = null,
            selectedRecord = null,
            charts = emptyMap(),
            boundsByMetric = emptyMap()
        )
        refresh()
    }

    fun viewport(viewport: HistoryRangeState, width: Int) {
        val before = mutable.value ?: return
        val bounded = boundedChartViewport(before.range, viewport)
        val pixels = width.coerceIn(64, 4096)
        if (before.viewport == bounded && pixels == pixelWidth) return
        pixelWidth = pixels
        mutable.value = before.copy(viewport = bounded)
        load(debounce = true)
    }

    fun scale(mode: ChartScaleMode) {
        mutable.value = mutable.value?.copy(scaleMode = mode)
    }

    fun style(style: ChartDrawingStyle) {
        mutable.value = mutable.value?.copy(style = style)
    }

    fun valueScale(scale: ChartValueScale) {
        mutable.value = mutable.value?.copy(valueScale = scale)
    }

    fun select(key: HistoryKey?) {
        mutable.value = mutable.value?.copy(selectedKey = key, selectedRecord = null)
        loadSelection(key)
    }

    fun refresh() {
        boundsStale = true
        load(debounce = false, forceBounds = true)
    }

    private fun load(debounce: Boolean, forceBounds: Boolean = false) {
        val before = mutable.value ?: return
        val request =
            if (forceBounds || boundsStale) before.copy(boundsByMetric = emptyMap()) else before
        query?.cancel()
        val requestGeneration = ++generation
        val width = pixelWidth
        mutable.value = before.copy(loading = true, error = false)
        query = viewModelScope.launch {
            try {
                if (debounce) delay(120.milliseconds)
                var resolved = request
                if (request.rangeLabel == "all") {
                    val range = repository.historyRange() ?: HistoryRangeState(0, 1)
                    if (range != request.range) {
                        resolved = request.copy(
                            range = range,
                            viewport = if (request.viewport == request.range) range
                            else boundedChartViewport(range, request.viewport),
                            charts = emptyMap(),
                            boundsByMetric = emptyMap()
                        )
                        if (requestGeneration == generation) mutable.value = mutable.value?.copy(
                            range = resolved.range,
                            viewport = resolved.viewport,
                            charts = emptyMap(),
                            boundsByMetric = emptyMap()
                        )
                    }
                }
                val bounds = if (resolved.metrics.all { it in resolved.boundsByMetric }) {
                    resolved.boundsByMetric.filterKeys { it in resolved.metrics }
                } else repository.detailedBounds(resolved.range, resolved.metrics)
                ensureActive()
                if (requestGeneration == generation) {
                    boundsStale = false
                    mutable.value = mutable.value?.copy(boundsByMetric = bounds)
                }
                val charts = repository.detailedCharts(
                    resolved.range, resolved.metrics, resolved.viewport, width
                )
                ensureActive()
                if (requestGeneration == generation) {
                    mutable.value = mutable.value?.copy(
                        charts = charts, boundsByMetric = bounds, loading = false
                    )
                    loadSelection(mutable.value?.selectedKey)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration == generation) {
                    mutable.value = mutable.value?.copy(loading = false, error = true)
                }
            }
        }
    }

    private fun loadSelection(key: HistoryKey?) {
        selectionQuery?.cancel()
        if (key == null) return
        selectionQuery = viewModelScope.launch {
            try {
                val record = repository.detailedSelection(key)
                if (mutable.value?.selectedKey == key) {
                    mutable.value = mutable.value?.copy(selectedRecord = record)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The chart remains usable if a selected row was deleted or cannot be read.
            }
        }
    }

    fun save(): Bundle = Bundle().apply {
        val current = mutable.value ?: return@apply
        putLong("start", current.range.start)
        putLong("end", current.range.end)
        putString("label", current.rangeLabel)
        putString("metric", current.metric.name)
        putString("comparison", current.comparison?.name)
        putLong("visibleStart", current.viewport.start)
        putLong("visibleEnd", current.viewport.end)
        putString("scale", current.scaleMode.name)
        putString("style", current.style.name)
        putString("valueScale", current.valueScale.name)
        current.selectedKey?.let {
            putLong("selectedId", it.id)
            putLong("selectedTime", it.time)
        }
    }

    private fun Bundle.validRange(startKey: String, endKey: String): HistoryRangeState? {
        val start = getLong(startKey, -1)
        val end = getLong(endKey, -1)
        return if (start in 0..<end) HistoryRangeState(start, end) else null
    }
}
