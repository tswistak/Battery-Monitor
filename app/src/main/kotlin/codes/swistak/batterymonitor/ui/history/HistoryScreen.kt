/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.history

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.devicebackup.LogImportMode
import codes.swistak.batterymonitor.logs.HistoryExportRequest
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogExport
import codes.swistak.batterymonitor.logs.LogExportFormat
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.logs.historyFilterKeys
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringUiState
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.temperatureUnit
import codes.swistak.batterymonitor.ui.components.MeasurementChart
import codes.swistak.batterymonitor.ui.components.historyValue
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

@Composable
internal fun HistoryRoute(
    viewModel: HistoryViewModel,
    monitoring: StateFlow<MonitoringUiState>,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot by monitoring.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val settings = remember {
        appContext.getSharedPreferences(
            SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
        )
    }
    val mainPreferences = remember {
        appContext.getSharedPreferences(
            SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE
        )
    }
    var settingsVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(settings, mainPreferences) {
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> settingsVersion++ }
        settings.registerOnSharedPreferenceChangeListener(listener); mainPreferences.registerOnSharedPreferenceChangeListener(
        listener
    )
        onDispose {
            settings.unregisterOnSharedPreferenceChangeListener(listener); mainPreferences.unregisterOnSharedPreferenceChangeListener(
            listener
        )
        }
    }
    val defaultTemperature = stringResource(R.string.default_temperature_unit)
    val fahrenheit = remember(
        settingsVersion, defaultTemperature
    ) { settings.temperatureUnit(defaultTemperature).convertToFahrenheit }
    val seconds = remember(settingsVersion) { mainPreferences.getBoolean("show_seconds", false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(if (viewModel.pendingImportUri != null) "importMode" else null) }
    var detailsId by rememberSaveable { mutableStateOf<Long?>(null) }
    var importingJson by rememberSaveable { mutableStateOf(false) }
    val statuses = stringArrayResource(R.array.log_statuses)
    val oldStatuses = stringArrayResource(R.array.log_statuses_old)
    val plugged = stringArrayResource(R.array.pluggeds)
    val boot = stringResource(R.string.status_boot_completed)
    val unknown = stringResource(R.string.status_unknown)
    fun status(record: LogRecord): String {
        if (record.status == -1) return boot
        val code = LogDatabase.decodeStatus(record.status)
        return (if (code[2] == 1) oldStatuses else statuses).getOrElse(code[0]) { unknown } + (plugged.getOrNull(
            code[1]
        )?.takeIf { code[1] > 0 }?.let { " · $it" } ?: "")
    }

    fun result(success: Boolean, successText: Int) {
        Toast.makeText(
            appContext,
            if (success) successText else R.string.history_operation_failed,
            Toast.LENGTH_LONG
        ).show()
    }

    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val uri = it.data?.data
            if (it.resultCode == Activity.RESULT_OK && uri != null) viewModel.export(uri) { success ->
                result(
                    success, R.string.file_written
                )
            }
            else viewModel.pendingExport = null
        }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.pendingImportUri = uri.toString(); viewModel.pendingImportJson =
                    importingJson; dialog = "importMode"
            }
        }

    fun export(format: LogExportFormat, scope: String) {
        val through = System.currentTimeMillis()
        val since = scope == "since" || scope == "append"
        val after = when {
            since -> if (settings.contains(SettingsContract.KEY_LAST_LOG_EXPORT_TIME)) settings.getLong(
                SettingsContract.KEY_LAST_LOG_EXPORT_TIME, 0
            ) else null

            scope == "all" -> null
            else -> state.range.exportAfter
        }
        viewModel.pendingExport = HistoryExportRequest(
            after,
            if (scope == "selected") state.range.exportThrough else through,
            format,
            scope == "append",
            since || scope == "all"
        )
        exporter.launch(
            Intent(if (scope == "append") Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT).addCategory(
                Intent.CATEGORY_OPENABLE
            )
                .setType(if (scope == "append") "*/*" else if (format == LogExportFormat.CSV) "text/csv" else "application/json")
                .putExtra(Intent.EXTRA_TITLE, LogExport.fileName(format, through))
        )
        dialog = null
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    // The legacy logger deduplicates on these three fields, not temperature/current updates.
    LaunchedEffect(
        snapshot.snapshot?.levelPercent, snapshot.snapshot?.status, snapshot.snapshot?.plugged
    ) { viewModel.refresh() }
    val chartList = rememberLazyListState()
    val logList = rememberLazyListState(state.scrollIndex, state.scrollOffset)
    LaunchedEffect(logList) {
        snapshotFlow { logList.firstVisibleItemIndex to logList.firstVisibleItemScrollOffset }.distinctUntilChanged()
            .collect { (index, offset) -> viewModel.scroll(index, offset) }
    }
    val list = if (state.tab == "charts") chartList else logList
    LaunchedEffect(state.range, state.ascending, state.filters, state.anchor, state.tab) {
        if (state.scrollIndex == 0 && state.scrollOffset == 0) logList.scrollToItem(0)
    }
    val dateFormat = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    }
    val timestampFormat = remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
            .withZone(ZoneId.systemDefault())
    }
    LazyColumn(
        modifier,
        state = list,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { HistoryRangeControl(state, viewModel::range) }
        item {
            SecondaryTabRow(selectedTabIndex = if (state.tab == "logs") 1 else 0) {
                Tab(
                    state.tab != "logs",
                    { viewModel.tab("charts") },
                    text = { Text(stringResource(R.string.history_charts)) })
                Tab(
                    state.tab == "logs",
                    { viewModel.tab("logs") },
                    text = { Text(stringResource(R.string.device_data_logs)) })
            }
        }
        if (state.loading || state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.error) item {
            Text(
                stringResource(R.string.history_load_failed),
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = { viewModel.refresh() }) { Text(stringResource(R.string.advanced_action_refresh)) }
        }
        if (state.tab == "charts") {
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HistoryMetric.entries.forEach { metric ->
                        FilterChip(
                            state.metric == metric,
                            { viewModel.metric(metric) },
                            label = { Text(metricLabel(metric)) })
                    }
                }
            }
            item {
                val chart = state.chart
                OutlinedCard(
                    Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            if (state.metric == HistoryMetric.LEVEL) stringResource(R.string.history_battery_level)
                            else metricLabel(state.metric),
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (chart != null && chart.count > 0) {
                            MeasurementChart(
                                chart,
                                state.metric,
                                fahrenheit,
                                state.selectedPointId,
                                viewModel::selectPoint
                            )
                        } else if (!state.loading) Text(stringResource(R.string.logs_empty))
                    }
                }
            }
            state.chart?.takeIf { it.count > 0 }?.let { chart ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Card(
                            Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "${
                                        historyValue(
                                            chart.series.getValue(state.metric).min,
                                            state.metric,
                                            fahrenheit,
                                            locale
                                        )
                                    } – ${
                                        historyValue(
                                            chart.series.getValue(state.metric).max,
                                            state.metric,
                                            fahrenheit,
                                            locale
                                        )
                                    }", style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    stringResource(R.string.history_reading_range_label),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Card(
                            Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(chart.events.filter { it.code == 2 }.sumOf { it.count }
                                    .toString(), style = MaterialTheme.typography.titleLarge)
                                Text(
                                    stringResource(R.string.history_connections),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(
                            R.string.history_event_counts,
                            chart.events.filter { it.code == 2 }.sumOf { it.count },
                            chart.events.filter { it.code == 0 }.sumOf { it.count },
                            chart.events.filter { it.code == -1 }.sumOf { it.count }),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        } else {
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = {
                        dialog = "filters"
                    }) { Text(stringResource(R.string.menu_log_filter)) }
                    OutlinedButton(onClick = { viewModel.reverse() }) { Text(stringResource(R.string.menu_reverse)) }
                    OutlinedButton(onClick = {
                        mainPreferences.edit { putBoolean("show_seconds", !seconds) }
                    }) {
                        Text(stringResource(if (seconds) R.string.menu_hide_seconds else R.string.menu_show_seconds))
                    }
                }
                if (!state.loading && state.page.isEmpty()) Text(stringResource(R.string.logs_empty))
            }
            itemsIndexed(state.page, key = { _, entry -> entry.id }) { index, entry ->
                val record = entry.record
                val date =
                    Instant.ofEpochMilli(record.time).atZone(ZoneId.systemDefault()).toLocalDate()
                val previous = state.page.getOrNull(index - 1)?.record?.time?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                }
                if (date != previous) Text(
                    dateFormat.format(date),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { detailsId = entry.id }) {
                    Column(
                        Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(status(record), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${
                                historyValue(
                                    HistoryMetric.LEVEL.value(record),
                                    HistoryMetric.LEVEL,
                                    fahrenheit,
                                    locale
                                )
                            } · ${DisplayStrings.formatTime(context, Date(record.time), seconds)}"
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        onClick = { viewModel.next(true) },
                        enabled = state.hasPrevious && !state.loading
                    ) { Text(stringResource(R.string.history_previous_page)) }
                    TextButton(
                        onClick = { viewModel.next(false) },
                        enabled = state.hasNext && !state.loading
                    ) { Text(stringResource(R.string.history_next_page)) }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { dialog = "export" }, Modifier.fillMaxWidth(), enabled = !state.busy
            ) { Text(stringResource(R.string.history_export_selected)) }
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = { dialog = "import" }, enabled = !state.busy) {
                    Text(
                        stringResource(R.string.log_import_mode_title)
                    )
                }
                TextButton(onClick = { dialog = "delete" }, enabled = !state.busy) {
                    Text(
                        stringResource(R.string.menu_clear)
                    )
                }
                TextButton(onClick = onSettings) { Text(stringResource(R.string.history_settings)) }
            }
        }
    }
    val filterLabels = stringArrayResource(R.array.log_filters)
    if (dialog == "filters") AlertDialog(
        onDismissRequest = { dialog = null },
        title = { Text(stringResource(R.string.configure_log_filter)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                historyFilterKeys.forEachIndexed { index, key ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                key in state.filters,
                                role = Role.Checkbox,
                                onValueChange = { checked -> viewModel.filters(if (checked) state.filters + key else state.filters - key) }),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Checkbox(key in state.filters, null)
                        Text(filterLabels[index], Modifier.padding(vertical = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dialog = null
            }) { Text(stringResource(R.string.okay)) }
        })
    if (dialog == "export") ChoiceDialog(
        R.string.log_export_mode_title,
        { dialog = null },
        listOf(
            R.string.history_export_csv to { export(LogExportFormat.CSV, "selected") },
            R.string.history_export_json to { export(LogExportFormat.JSON, "selected") },
            R.string.log_export_from_last to { export(LogExportFormat.CSV, "since") },
            R.string.log_export_append to { export(LogExportFormat.CSV, "append") },
            R.string.log_export_all_logs to { export(LogExportFormat.CSV, "all") })
    )
    if (dialog == "import") ChoiceDialog(
        R.string.log_import_mode_title,
        { dialog = null },
        listOf(R.string.pref_import_logs_csv to {
            importingJson = false; importer.launch(arrayOf("*/*")); dialog = null
        }, R.string.history_import_json to {
            importingJson = true; importer.launch(arrayOf("*/*")); dialog = null
        })
    )
    if (dialog == "importMode") AlertDialog(onDismissRequest = {
        dialog = null; viewModel.pendingImportUri = null
    }, title = { Text(stringResource(R.string.log_import_mode_title)) }, text = {
        Column {
            Text(stringResource(if (viewModel.pendingImportJson) R.string.device_data_backup_warning else R.string.csv_logs_import_warning)); Text(
            stringResource(R.string.log_import_mode_message)
        )
        }
    }, confirmButton = {
        Column {
            TextButton(onClick = {
                viewModel.import(LogImportMode.ADD) {
                    result(
                        it, R.string.csv_logs_imported
                    )
                }; dialog = null
            }) { Text(stringResource(R.string.log_import_add)) }
            TextButton(onClick = {
                dialog = "replace"
            }) { Text(stringResource(R.string.log_import_replace)) }
        }
    }, dismissButton = {
        TextButton(onClick = {
            dialog = null; viewModel.pendingImportUri = null
        }) { Text(stringResource(R.string.cancel)) }
    })
    if (dialog == "replace") AlertDialog(
        onDismissRequest = {
            dialog = null; viewModel.pendingImportUri = null
        },
        title = { Text(stringResource(R.string.log_import_replace)) },
        text = { Text(stringResource(R.string.confirm_clear_logs)) },
        confirmButton = {
            TextButton(onClick = {
                viewModel.import(LogImportMode.REPLACE) {
                    result(
                        it, R.string.device_data_imported
                    )
                }; dialog = null
            }) { Text(stringResource(R.string.yes)) }
        },
        dismissButton = {
            TextButton(onClick = {
                dialog = null; viewModel.pendingImportUri = null
            }) { Text(stringResource(R.string.cancel)) }
        })
    if (dialog == "delete") ChoiceDialog(
        R.string.menu_clear,
        { dialog = null },
        listOf(
            R.string.history_delete_selected to { dialog = "deleteSelected" },
            R.string.confirm_clear_logs to { dialog = "deleteAll" })
    )
    if (dialog == "deleteSelected" || dialog == "deleteAll") AlertDialog(
        onDismissRequest = {
            dialog = null
        },
        title = { Text(stringResource(if (dialog == "deleteAll") R.string.confirm_clear_logs else R.string.history_delete_selected_confirm)) },
        text = {
            Text(
                "${timestampFormat.format(Instant.ofEpochMilli(state.range.start))} – ${
                    timestampFormat.format(
                        Instant.ofEpochMilli(state.range.end - 1)
                    )
                }"
            )
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.delete(dialog == "deleteAll") {
                    result(
                        it, R.string.history_deleted
                    )
                }; dialog = null
            }) { Text(stringResource(R.string.yes)) }
        },
        dismissButton = {
            TextButton(onClick = {
                dialog = null
            }) { Text(stringResource(R.string.cancel)) }
        })
    val detail = state.page.firstOrNull { it.id == detailsId }
    if (detail != null) {
        val duration by produceState<Long?>(null, detail.id) {
            try {
                value = viewModel.duration(detail)
            } catch (exception: kotlinx.coroutines.CancellationException) {
                throw exception
            } catch (_: Exception) {
                value = null
            }
        }
        AlertDialog(
            onDismissRequest = { detailsId = null },
            title = { Text(status(detail.record)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(timestampFormat.format(Instant.ofEpochMilli(detail.record.time)))
                    HistoryMetric.entries.forEach { metric ->
                        Text(
                            "${metricLabel(metric)}: ${
                                historyValue(
                                    metric.value(detail.record), metric, fahrenheit, locale
                                )
                            }"
                        )
                    }
                    duration?.let { millis ->
                        val mins = millis / 60_000
                        val pluggedBefore = LogDatabase.decodeStatus(detail.record.status)[0] != 2
                        Text(
                            if (mins >= 60) stringResource(
                                if (pluggedBefore) R.string.after_nh_mm_plugged_in else R.string.after_nh_mm_unplugged,
                                mins / 60,
                                mins % 60
                            )
                            else stringResource(
                                if (pluggedBefore) R.string.after_nm_ms_plugged_in else R.string.after_nm_ms_unplugged,
                                mins,
                                millis / 1000 % 60
                            )
                        )
                    }
                    Text(
                        stringResource(R.string.history_observations),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    detailsId = null
                }) { Text(stringResource(R.string.okay)) }
            })
    }
}

@Composable
private fun metricLabel(metric: HistoryMetric): String = stringResource(
    when (metric) {
        HistoryMetric.LEVEL -> R.string.history_level
        HistoryMetric.TEMPERATURE -> R.string.current_temperature
        HistoryMetric.VOLTAGE -> R.string.current_voltage
    }
)

@Composable
private fun ChoiceDialog(title: Int, onDismiss: () -> Unit, choices: List<Pair<Int, () -> Unit>>) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                choices.forEach { (label, action) ->
                    TextButton(
                        onClick = action, modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(label)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
