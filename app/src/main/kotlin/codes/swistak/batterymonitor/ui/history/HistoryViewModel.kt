/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.history

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import codes.swistak.batterymonitor.data.LogsRepository
import codes.swistak.batterymonitor.devicebackup.CsvLogImporter
import codes.swistak.batterymonitor.devicebackup.DeviceDataBackup
import codes.swistak.batterymonitor.devicebackup.DeviceDataType
import codes.swistak.batterymonitor.devicebackup.LogImportMode
import codes.swistak.batterymonitor.logs.HistoryChartModel
import codes.swistak.batterymonitor.logs.HistoryExportRequest
import codes.swistak.batterymonitor.logs.HistoryKey
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.HistoryRecord
import codes.swistak.batterymonitor.logs.LogExportFormat
import codes.swistak.batterymonitor.logs.exportHistory
import codes.swistak.batterymonitor.logs.historyFilterKeys
import codes.swistak.batterymonitor.settings.SettingsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class HistoryUiState(
    val range: HistoryRangeState = HistoryRangeState.lastHours(24),
    val rangeLabel: String = "24h",
    val tab: String = "charts",
    val metric: HistoryMetric = HistoryMetric.LEVEL,
    val ascending: Boolean = false,
    val filters: Set<String> = historyFilterKeys.toSet(),
    val chart: HistoryChartModel? = null,
    val page: List<HistoryRecord> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val anchor: HistoryKey? = null,
    val backwards: Boolean = false,
    val selectedPointId: Long? = null,
    val includeAnchor: Boolean = false,
    val eventToReveal: Long? = null
)

internal class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LogsRepository(application)
    private val preferences =
        application.getSharedPreferences(SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE)
    private val settings =
        application.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(HistoryUiState())
    val state = mutable.asStateFlow()
    private var queryJob: Job? = null
    private var isTwoPane = false

    private var restored = false
    var scrollIndex = 0
        private set
    var scrollOffset = 0
        private set

    var pendingExport: HistoryExportRequest? = null
    var pendingImportUri: String? = null
    var pendingImportJson = false

    init {
        if (!preferences.getBoolean("log_filters_migrated_to_sp_main", false)) {
            preferences.edit {
                historyFilterKeys.forEach { putBoolean(it, settings.getBoolean(it, true)) }
                putBoolean("log_filters_migrated_to_sp_main", true)
            }
        }
        mutable.value = mutable.value.copy(filters = historyFilterKeys.filter {
            preferences.getBoolean(
                it, true
            )
        }.toSet())
    }

    fun restore(bundle: Bundle?) {
        if (restored) return
        restored = true
        if (bundle == null) return
        val start = bundle.getLong("start")
        val end = bundle.getLong("end")
        val range = if (start in 0..<end) HistoryRangeState(start, end) else mutable.value.range
        scroll(bundle.getInt("scrollIndex"), bundle.getInt("scrollOffset"))
        mutable.value = mutable.value.copy(
            range = range,
            rangeLabel = bundle.getString("label", "custom"),
            tab = if (bundle.getString("tab") == "logs") "logs" else "charts",
            metric = HistoryMetric.entries.getOrElse(bundle.getInt("metric")) { HistoryMetric.LEVEL },
            ascending = bundle.getBoolean("ascending"),
            anchor = if (bundle.containsKey("anchorId")) HistoryKey(
                bundle.getLong("anchorTime"), bundle.getLong("anchorId")
            ) else null,
            backwards = bundle.getBoolean("backwards"),
            selectedPointId = if (bundle.containsKey("point")) bundle.getLong("point") else null,
            includeAnchor = bundle.getBoolean("includeAnchor")
        )
        if (bundle.containsKey("exportThrough")) pendingExport = HistoryExportRequest(
            if (bundle.getBoolean("exportHasAfter")) bundle.getLong("exportAfter") else null,
            bundle.getLong("exportThrough"),
            LogExportFormat.entries.getOrElse(bundle.getInt("exportFormat")) { LogExportFormat.CSV },
            bundle.getBoolean("exportAppend"),
            bundle.getBoolean("exportWatermark")
        )
        pendingImportUri = bundle.getString("importUri")
        pendingImportJson = bundle.getBoolean("importJson")
    }

    fun save(): Bundle = Bundle().apply {
        val value = mutable.value
        putLong("start", value.range.start); putLong("end", value.range.end); putString(
        "label", value.rangeLabel
    )
        putString("tab", value.tab); putInt("metric", value.metric.ordinal); putBoolean(
        "ascending", value.ascending
    )
        value.anchor?.let { putLong("anchorId", it.id); putLong("anchorTime", it.time) }
        putBoolean("backwards", value.backwards)
        putBoolean("includeAnchor", value.includeAnchor)
        putInt("scrollIndex", scrollIndex); putInt("scrollOffset", scrollOffset)
        value.selectedPointId?.let { putLong("point", it) }
        pendingExport?.let {
            putBoolean(
                "exportHasAfter", it.after != null
            ); it.after?.let { after -> putLong("exportAfter", after) }
            putLong("exportThrough", it.through); putInt("exportFormat", it.format.ordinal)
            putBoolean("exportAppend", it.append); putBoolean(
            "exportWatermark", it.advanceWatermark
        )
        }
        putString("importUri", pendingImportUri); putBoolean("importJson", pendingImportJson)
    }

    fun refresh(
        anchor: HistoryKey? = mutable.value.anchor,
        backwards: Boolean = mutable.value.backwards,
        forceChart: Boolean = true,
        includeAnchor: Boolean = mutable.value.includeAnchor
    ) {
        queryJob?.cancel()
        val current = mutable.value
        val range = if (forceChart) when (current.rangeLabel) {
            "24h" -> HistoryRangeState.lastHours(24)
            "7d" -> HistoryRangeState.lastHours(168)
            else -> current.range
        } else current.range
        val value = current.copy(range = range, chart = if (forceChart) null else current.chart)
        mutable.value = value.copy(
            loading = true,
            error = false,
            anchor = anchor,
            backwards = backwards,
            includeAnchor = includeAnchor
        )
        queryJob = viewModelScope.launch {
            try {
                val revealedId = value.selectedPointId.takeIf { isTwoPane }
                var records = repository.page(
                    value.range,
                    value.filters,
                    value.ascending,
                    anchor,
                    backwards,
                    includeAnchor,
                    revealedId
                )
                val resetPage = records.isEmpty() && anchor != null
                if (resetPage) {
                    records = repository.page(
                        value.range, value.filters, value.ascending, null, revealedId = revealedId
                    )
                    scroll(0, 0)
                }
                val pageAnchor = anchor.takeUnless { resetPage }
                val pageBackwards = backwards && !resetPage
                val pageIncludesAnchor = includeAnchor && !resetPage
                val page = records.take(128).let { if (pageBackwards) it.reversed() else it }
                val hasPrevious = if (pageIncludesAnchor && anchor != null) {
                    repository.hasRecordsBefore(
                        value.range, value.filters, value.ascending, anchor, revealedId
                    )
                } else if (pageBackwards) records.size > 128 else pageAnchor != null
                val chart =
                    if ((value.tab == "charts" || isTwoPane) && value.chart == null) repository.chart(
                        value.range
                    ) else value.chart
                mutable.value = mutable.value.copy(
                    chart = chart,
                    page = page,
                    loading = false,
                    hasNext = if (pageBackwards) pageAnchor != null else records.size > 128,
                    hasPrevious = hasPrevious,
                    anchor = pageAnchor,
                    backwards = pageBackwards,
                    includeAnchor = pageIncludesAnchor
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(loading = false, error = true)
            }
        }
    }

    fun range(range: HistoryRangeState, label: String) {
        scroll(0, 0)
        mutable.value = mutable.value.copy(
            range = range,
            rangeLabel = label,
            chart = null,
            page = emptyList(),
            selectedPointId = null,
            eventToReveal = null
        )
        refresh(null, false, includeAnchor = false)
    }

    fun tab(tab: String) {
        if (mutable.value.tab == tab) return
        mutable.value = mutable.value.copy(tab = tab)
        if (tab == "charts" && mutable.value.chart == null) refresh(forceChart = false)
    }

    fun twoPane(active: Boolean) {
        if (isTwoPane == active) return
        isTwoPane = active
        if (!active) mutable.value = mutable.value.copy(eventToReveal = null)
        refresh(forceChart = false)
    }

    fun metric(metric: HistoryMetric) {
        val hadSelection = mutable.value.selectedPointId != null
        mutable.value =
            mutable.value.copy(metric = metric, selectedPointId = null, eventToReveal = null)
        if (isTwoPane && hadSelection) refresh(forceChart = false)
    }

    fun selectPoint(id: Long?) {
        val current = mutable.value
        val point =
            current.chart?.series?.get(current.metric)?.points?.firstOrNull { it.key.id == id }
        mutable.value = current.copy(
            selectedPointId = id, eventToReveal = id.takeIf { isTwoPane && point != null })
        if (isTwoPane && point != null && (current.loading || current.page.none { it.id == id })) {
            scroll(0, 0)
            refresh(point.key, false, false, includeAnchor = true)
        }
    }

    fun eventRevealed(id: Long) {
        if (mutable.value.eventToReveal == id) mutable.value =
            mutable.value.copy(eventToReveal = null)
    }

    fun scroll(index: Int, offset: Int) {
        scrollIndex = index
        scrollOffset = offset
    }

    fun reverse() {
        scroll(0, 0)
        mutable.value = mutable.value.copy(ascending = !mutable.value.ascending)
        mutable.value = mutable.value.copy(eventToReveal = null)
        refresh(null, false, false, includeAnchor = false)
    }

    fun filters(filters: Set<String>) {
        preferences.edit { historyFilterKeys.forEach { putBoolean(it, it in filters) } }
        scroll(0, 0)
        mutable.value =
            mutable.value.copy(filters = filters, selectedPointId = null, eventToReveal = null)
        refresh(null, false, false, includeAnchor = false)
    }

    fun next(previous: Boolean) {
        val page = mutable.value.page
        if (page.isEmpty()) return
        scroll(0, 0)
        mutable.value = mutable.value.copy(eventToReveal = null)
        refresh(
            if (previous) page.first().key else page.last().key,
            previous,
            false,
            includeAnchor = false
        )
    }

    suspend fun duration(record: HistoryRecord) = repository.duration(record)

    fun export(uri: Uri, onDone: (Boolean, String?) -> Unit) {
        val request = pendingExport ?: return
        pendingExport = null
        var formatError: String? = null
        operation({ onDone(it, formatError) }, reload = false) {
            try {
                exportHistory(getApplication(), uri, request)
            } catch (exception: IllegalArgumentException) {
                formatError = exception.message
                throw exception
            }
        }
    }

    fun delete(all: Boolean, onDone: (Boolean) -> Unit) {
        val range = mutable.value.range
        operation(onDone) { repository.delete(if (all) null else range) }
    }

    fun import(mode: LogImportMode, onDone: (Boolean) -> Unit) {
        val uri = pendingImportUri?.let(Uri::parse) ?: return
        val json = pendingImportJson
        pendingImportUri = null
        operation(onDone) {
            withContext(Dispatchers.IO) {
                val context = getApplication<Application>()
                if (json) {
                    val text =
                        DeviceDataBackup.readFromUri(context, uri) ?: error("Unreadable JSON")
                    DeviceDataBackup.importFromJson(context, text, setOf(DeviceDataType.LOGS), mode)
                } else {
                    val text = CsvLogImporter.readFromUri(context, uri) ?: error("Unreadable CSV")
                    CsvLogImporter.importFromCsv(context, text, mode)
                }
            }
        }
    }

    private fun operation(
        onDone: (Boolean) -> Unit, reload: Boolean = true, block: suspend () -> Unit
    ) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            val success = try {
                block(); true
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                false
            }
            mutable.value = mutable.value.copy(busy = false)
            onDone(success)
            if (success && reload) {
                mutable.value = mutable.value.copy(selectedPointId = null, eventToReveal = null)
                scroll(0, 0); refresh(null, false, includeAnchor = false)
            }
        }
    }
}
