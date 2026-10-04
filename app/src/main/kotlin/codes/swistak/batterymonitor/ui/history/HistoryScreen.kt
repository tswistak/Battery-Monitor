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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
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
import codes.swistak.batterymonitor.ui.components.historyRangeValue
import codes.swistak.batterymonitor.ui.components.historyValue
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

@Composable
internal fun HistoryActionsMenu(enabled: Boolean, onAction: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.nav_actions)
    val preferences = LocalContext.current.getSharedPreferences(
        SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE
    )
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.semantics {
            contentDescription = label
        }) { Icon(painterResource(R.drawable.ui_more), null) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            listOf(
                R.string.log_export_mode_title to "export",
                R.string.log_import_mode_title to "import",
                R.string.menu_clear to "delete",
                (if (preferences.getBoolean(
                        "show_seconds", false
                    )
                ) R.string.menu_hide_seconds else R.string.menu_show_seconds) to "seconds",
                R.string.history_settings to "settings"
            ).forEach { (label, action) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = {
                    Icon(
                        painterResource(
                            when (action) {
                                "export" -> R.drawable.ui_share
                                "import" -> R.drawable.ui_down
                                "delete" -> R.drawable.ui_minus
                                "seconds" -> R.drawable.ui_clock
                                else -> R.drawable.ui_settings
                            }
                        ), null
                    )
                }, onClick = {
                    expanded = false
                    onAction(action)
                })
            }
        }
    }
}

@Composable
internal fun HistoryRoute(
    viewModel: HistoryViewModel,
    monitoring: StateFlow<MonitoringUiState>,
    requestedAction: String?,
    onActionHandled: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val state by viewModel.state.collectAsStateWithLifecycle()
    val monitoredChange by remember(monitoring) {
        monitoring.map {
            Triple(
                it.snapshot?.levelPercent, it.snapshot?.status, it.snapshot?.plugged
            )
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = Triple(null, null, null))
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
    val currentMultiplier = remember(settingsVersion) {
        settings.getString(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "1")?.toIntOrNull() ?: 1
    }
    val seconds = remember(settingsVersion) { mainPreferences.getBoolean("show_seconds", false) }
    var dialog by rememberSaveable { mutableStateOf(if (viewModel.pendingImportUri != null) "importMode" else null) }
    LaunchedEffect(requestedAction) {
        if (requestedAction != null) {
            if (requestedAction == "seconds") mainPreferences.edit {
                putBoolean("show_seconds", !seconds)
            }
            else dialog = requestedAction
            onActionHandled()
        }
    }
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
            if (it.resultCode == Activity.RESULT_OK && uri != null) viewModel.export(uri) { success, formatError ->
                if (formatError != null) Toast.makeText(appContext, formatError, Toast.LENGTH_LONG)
                    .show()
                else result(success, R.string.file_written)
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
    LaunchedEffect(monitoredChange) { viewModel.refresh() }
    val chartList = rememberLazyListState()
    val logList = rememberLazyListState(viewModel.scrollIndex, viewModel.scrollOffset)
    LaunchedEffect(logList) {
        snapshotFlow { logList.firstVisibleItemIndex to logList.firstVisibleItemScrollOffset }.distinctUntilChanged()
            .collect { (index, offset) -> viewModel.scroll(index, offset) }
    }
    val list = if (state.tab == "charts") chartList else logList
    LaunchedEffect(state.range, state.ascending, state.filters, state.anchor, state.tab) {
        if (viewModel.scrollIndex == 0 && viewModel.scrollOffset == 0) logList.scrollToItem(0)
    }
    val zone = ZoneId.systemDefault()
    val logDays = remember(state.page, zone) {
        state.page.groupBy { Instant.ofEpochMilli(it.record.time).atZone(zone).toLocalDate() }
    }
    val dateFormat = remember(locale) {
        DateTimeFormatter.ofPattern(
            android.text.format.DateFormat.getBestDateTimePattern(
                locale, "MMMMd"
            ), locale
        )
    }
    val timestampFormat = remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
            .withZone(ZoneId.systemDefault())
    }
    BoxWithConstraints(modifier) {
        val twoPane = maxWidth >= 800.dp * LocalDensity.current.fontScale && maxHeight >= 480.dp
        LaunchedEffect(twoPane) { viewModel.twoPane(twoPane) }
        val eventIndices = remember(logDays) {
            buildMap {
                var index = 1
                logDays.values.forEach { entries ->
                    index++
                    entries.forEach { put(it.id, index++) }
                }
            }
        }
        LaunchedEffect(twoPane, state.eventToReveal, state.page, state.loading) {
            val id = state.eventToReveal
            val index = eventIndices[id]
            if (twoPane && !state.loading && id != null && index != null) {
                logList.animateScrollToItem(index)
                viewModel.eventRevealed(id)
            }
        }
        val charts: LazyListScope.() -> Unit = {
            item {
                FlowRow(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HistoryMetric.entries.forEach { metric ->
                        FilterChip(
                            state.metric == metric,
                            { viewModel.metric(metric) },
                            shape = RoundedCornerShape(11.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
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
                                viewModel::selectPoint,
                                currentMultiplier = currentMultiplier,
                                linksToEvents = twoPane
                            )
                        } else if (state.loading) Spacer(Modifier.height(280.dp))
                        else Text(stringResource(R.string.logs_empty))
                    }
                }
            }
            state.chart?.takeIf { it.count > 0 }?.let { chart ->
                item {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val cardWidth =
                            if (state.metric == HistoryMetric.CURRENT || state.metric == HistoryMetric.POWER || state.metric == HistoryMetric.REMAINING_CHARGE || LocalDensity.current.fontScale >= 1.3f) maxWidth
                            else with(LocalDensity.current) {
                                ((constraints.maxWidth - 12.dp.roundToPx()) / 2).toDp()
                            }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Card(
                                Modifier.width(cardWidth),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                            ) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        historyRangeValue(
                                            chart.series.getValue(state.metric),
                                            state.metric,
                                            fahrenheit,
                                            locale,
                                            currentMultiplier
                                        ).let {
                                            if (state.metric == HistoryMetric.CURRENT || state.metric == HistoryMetric.POWER) it.replace(
                                                "–", " – "
                                            ) else it
                                        }, style = MaterialTheme.typography.titleLarge
                                    )
                                    Text(
                                        stringResource(R.string.history_reading_range_label),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            Card(
                                Modifier.width(cardWidth),
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
        }
        val events: LazyListScope.() -> Unit = {
            item {
                Row(
                    Modifier.padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.filters.size == historyFilterKeys.size,
                        onClick = { dialog = "filters" },
                        shape = RoundedCornerShape(11.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        label = {
                            Text(
                                if (state.filters.size == historyFilterKeys.size) stringResource(
                                    R.string.history_all_types
                                )
                                else stringResource(
                                    R.string.history_filtered_types, state.filters.size
                                ), fontWeight = FontWeight.SemiBold
                            )
                        })
                    OutlinedButton(
                        onClick = { viewModel.reverse() },
                        shape = RoundedCornerShape(11.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            stringResource(if (state.ascending) R.string.history_oldest else R.string.history_newest),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Icon(
                            painterResource(if (state.ascending) R.drawable.ui_up else R.drawable.ui_down),
                            null,
                            Modifier.size(16.dp)
                        )
                    }
                }
                if (!state.loading && state.page.isEmpty()) Text(stringResource(R.string.logs_empty))
            }
            logDays.forEach { (date, entries) ->
                item(key = "day:$date", contentType = "day") {
                    val day = if (date.year == LocalDate.now().year) dateFormat.format(date)
                    else DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
                        .format(date)
                    Text(
                        when (date) {
                            LocalDate.now() -> stringResource(R.string.history_today, day)
                            LocalDate.now().minusDays(1) -> stringResource(
                                R.string.history_yesterday, day
                            )

                            else -> day
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 20.dp, bottom = 10.dp)
                    )
                }
                itemsIndexed(
                    entries,
                    key = { _, entry -> entry.id },
                    contentType = { _, _ -> "log" }) { index, entry ->
                    val emphasized = twoPane && state.selectedPointId == entry.id
                    val foreground =
                        if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    val secondary =
                        if (emphasized) foreground else MaterialTheme.colorScheme.onSurfaceVariant
                    val selectionColor = MaterialTheme.colorScheme.primary
                    val first = index == 0
                    val last = index == entries.lastIndex
                    val shape = RoundedCornerShape(
                        topStart = if (first) 20.dp else 0.dp,
                        topEnd = if (first) 20.dp else 0.dp,
                        bottomStart = if (last) 20.dp else 0.dp,
                        bottomEnd = if (last) 20.dp else 0.dp
                    )
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                            .logGroupBorder(
                                first, last, MaterialTheme.colorScheme.outlineVariant
                            )
                    ) {
                        val record = entry.record
                        val code = LogDatabase.decodeStatus(record.status)
                        val icon = when {
                            record.status == -1 -> R.drawable.ui_refresh
                            code[0] == 0 && code[2] == 0 -> R.drawable.ui_plug
                            code[0] == 2 -> R.drawable.ui_bolt
                            code[0] == 5 -> R.drawable.ui_battery
                            else -> R.drawable.ui_down
                        }
                        val title = when {
                            code[0] == 0 && code[2] == 0 -> stringResource(R.string.history_disconnected)
                            code[0] == 2 && code[2] == 0 -> stringResource(R.string.history_charge_started)
                            else -> status(record).substringBefore(" · ")
                        }
                        val description =
                            if (record.status == -1) stringResource(R.string.history_system_event)
                            else listOfNotNull(
                                HistoryMetric.CURRENT.value(record)?.let {
                                    historyValue(
                                        it,
                                        HistoryMetric.CURRENT,
                                        fahrenheit,
                                        locale,
                                        currentMultiplier
                                    )
                                },
                                HistoryMetric.POWER.value(record)?.let {
                                    historyValue(
                                        it,
                                        HistoryMetric.POWER,
                                        fahrenheit,
                                        locale,
                                        currentMultiplier
                                    )
                                },
                                plugged.getOrNull(code[1])?.takeIf { code[1] > 0 },
                                HistoryMetric.TEMPERATURE.value(record)?.let {
                                    historyValue(
                                        it, HistoryMetric.TEMPERATURE, fahrenheit, locale
                                    )
                                },
                                HistoryMetric.VOLTAGE.value(record)?.let {
                                    historyValue(
                                        it, HistoryMetric.VOLTAGE, fahrenheit, locale
                                    )
                                }).joinToString(" · ")
                                .ifEmpty { stringResource(R.string.current_unavailable) }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { detailsId = entry.id }
                                .then(if (twoPane) Modifier.semantics {
                                    selected = emphasized
                                } else Modifier)
                                .drawBehind {
                                    if (emphasized) {
                                        val width = 3.dp.toPx()
                                        val x =
                                            if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                                        drawRect(
                                            selectionColor,
                                            topLeft = Offset(x, 0f),
                                            size = Size(width, size.height)
                                        )
                                    }
                                }
                                .heightIn(min = 67.dp)
                                .padding(horizontal = 13.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Box(
                                    Modifier.size(35.dp), contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painterResource(icon),
                                        null,
                                        Modifier.size(19.dp),
                                        tint = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = foreground
                                )
                                Text(
                                    description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    historyValue(
                                        HistoryMetric.LEVEL.value(record),
                                        HistoryMetric.LEVEL,
                                        fahrenheit,
                                        locale
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = foreground
                                )
                                HistoryMetric.REMAINING_CHARGE.value(record)?.let {
                                    Text(
                                        historyValue(
                                            it, HistoryMetric.REMAINING_CHARGE, fahrenheit, locale
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = secondary
                                    )
                                }
                                Text(
                                    DisplayStrings.formatTime(
                                        context, Date(record.time), seconds
                                    ), style = MaterialTheme.typography.bodySmall, color = secondary
                                )
                            }
                        }
                        if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            if (state.page.isNotEmpty()) item {
                Text(
                    stringResource(R.string.history_raw_observations),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
                )
            }
            if (state.hasPrevious || state.hasNext) item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
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
        val exportAction: @Composable (Modifier) -> Unit = { actionModifier ->
            OutlinedButton(
                onClick = { dialog = "export" },
                modifier = actionModifier.fillMaxWidth(),
                enabled = !state.busy,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                contentPadding = PaddingValues(12.dp)
            ) {
                Icon(painterResource(R.drawable.ui_share), null, Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.history_export_selected))
            }
        }
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HistoryRangeControl(state, viewModel::range)
                if (!twoPane) {
                    SecondaryTabRow(
                        selectedTabIndex = if (state.tab == "logs") 1 else 0,
                        containerColor = MaterialTheme.colorScheme.background,
                        indicator = {
                            TabRowDefaults.PrimaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(
                                    if (state.tab == "logs") 1 else 0, matchContentSize = false
                                ),
                                width = 46.dp,
                                height = 3.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }) {
                        Tab(
                            state.tab != "logs",
                            { viewModel.tab("charts") },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = {
                                Text(
                                    stringResource(R.string.history_charts),
                                    fontWeight = if (state.tab == "charts") FontWeight.SemiBold else FontWeight.Normal
                                )
                            })
                        Tab(
                            state.tab == "logs",
                            { viewModel.tab("logs") },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = {
                                Text(
                                    stringResource(R.string.history_events),
                                    fontWeight = if (state.tab == "logs") FontWeight.SemiBold else FontWeight.Normal
                                )
                            })
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
            ) {
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.error) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        stringResource(R.string.history_load_failed),
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = { viewModel.refresh() }) {
                        Text(stringResource(R.string.advanced_action_refresh))
                    }
                }
            }
            if (twoPane) {
                Row(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Column(Modifier.weight(1.1f)) {
                        Text(
                            stringResource(R.string.history_charts),
                            Modifier
                                .padding(vertical = 12.dp)
                                .semantics { heading() },
                            style = MaterialTheme.typography.titleMedium
                        )
                        LazyColumn(
                            Modifier.weight(1f),
                            state = chartList,
                            contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            content = charts
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.history_events),
                            Modifier
                                .padding(vertical = 12.dp)
                                .semantics { heading() },
                            style = MaterialTheme.typography.titleMedium
                        )
                        LazyColumn(
                            Modifier.weight(1f),
                            state = logList,
                            contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
                            content = events
                        )
                    }
                }
                HorizontalDivider(
                    Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp), contentAlignment = Alignment.Center
                ) {
                    exportAction(Modifier.widthIn(max = 480.dp))
                }
            } else {
                key(state.tab) {
                    LazyColumn(
                        Modifier.weight(1f),
                        state = list,
                        contentPadding = PaddingValues(
                            start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp
                        ),
                        verticalArrangement = if (state.tab == "logs") Arrangement.Top else Arrangement.spacedBy(
                            12.dp
                        )
                    ) {
                        if (state.tab == "charts") charts() else events()
                        item { exportAction(Modifier.padding(top = if (state.tab == "logs") 12.dp else 0.dp)) }
                    }
                }
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
                        verticalAlignment = Alignment.CenterVertically
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
            R.string.history_delete_all to { dialog = "deleteAll" })
    )
    if (dialog == "deleteSelected" || dialog == "deleteAll") AlertDialog(
        onDismissRequest = {
            dialog = null
        },
        title = { Text(stringResource(if (dialog == "deleteAll") R.string.history_delete_all else R.string.history_delete_selected)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (dialog == "deleteAll") R.string.confirm_clear_logs else R.string.history_delete_selected_confirm))
                if (dialog == "deleteSelected") Text(
                    "${timestampFormat.format(Instant.ofEpochMilli(state.range.start))} – ${
                        timestampFormat.format(
                            Instant.ofEpochMilli(state.range.end - 1)
                        )
                    }"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.delete(dialog == "deleteAll") {
                    result(
                        it, R.string.history_deleted
                    )
                }; dialog = null
            }) { Text(stringResource(R.string.menu_clear)) }
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
                        if (metric == HistoryMetric.POWER && metric.value(detail.record) == null) return@forEach
                        Text(
                            "${metricLabel(metric)}: ${
                                historyValue(
                                    metric.value(detail.record),
                                    metric,
                                    fahrenheit,
                                    locale,
                                    currentMultiplier
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
        HistoryMetric.CURRENT -> R.string.pref_cat_battery_current_main
        HistoryMetric.POWER -> R.string.battery_power
        HistoryMetric.REMAINING_CHARGE -> R.string.remaining_charge
    }
)

@Composable
private fun ChoiceDialog(title: Int, onDismiss: () -> Unit, choices: List<Pair<Int, () -> Unit>>) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                choices.forEach { (label, action) ->
                    OutlinedButton(
                        onClick = action, modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(label), Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

private fun Modifier.logGroupBorder(first: Boolean, last: Boolean, color: Color) = drawBehind {
    val width = 1.dp.toPx()
    val half = width / 2
    val radius = 20.dp.toPx().coerceAtMost(size.height / 2)
    val path = Path().apply {
        moveTo(half, if (first) radius else 0f)
        if (first) {
            arcTo(Rect(half, half, 2 * radius - half, 2 * radius - half), 180f, 90f, false)
            lineTo(size.width - radius, half)
            arcTo(
                Rect(size.width - 2 * radius + half, half, size.width - half, 2 * radius - half),
                270f,
                90f,
                false
            )
        } else moveTo(size.width - half, 0f)
        lineTo(size.width - half, if (last) size.height - radius else size.height)
        if (last) {
            arcTo(
                Rect(
                    size.width - 2 * radius + half,
                    size.height - 2 * radius + half,
                    size.width - half,
                    size.height - half
                ), 0f, 90f, false
            )
            lineTo(radius, size.height - half)
            arcTo(
                Rect(
                    half, size.height - 2 * radius + half, 2 * radius - half, size.height - half
                ), 90f, 90f, false
            )
        } else moveTo(half, size.height)
        lineTo(half, if (first) radius else 0f)
    }
    drawPath(path, color, style = Stroke(width))
}
