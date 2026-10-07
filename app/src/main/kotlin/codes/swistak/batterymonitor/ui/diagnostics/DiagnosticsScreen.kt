/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.showToast
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.ui.components.ActionLabel
import codes.swistak.batterymonitor.ui.components.SettingRow
import codes.swistak.batterymonitor.ui.components.groupedCardBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DiagnosticsRoute(
    viewModel: DiagnosticsViewModel,
    onMonitor: () -> Unit,
    onChargingTools: () -> Unit,
    monitoring: kotlinx.coroutines.flow.StateFlow<codes.swistak.batterymonitor.monitoring.presentation.MonitoringUiState>,
    monitorActions: Map<String, MonitorAction>
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val monitor by monitoring.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.setActive(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.setActive(false) }
    DisposableEffect(viewModel) { onDispose { viewModel.setActive(false) } }
    DiagnosticsScreen(
        state,
        viewModel::refreshStats,
        onMonitor,
        onChargingTools,
        viewModel::setPrivilegedEnabled,
        viewModel::setTab,
        monitor.snapshot,
        monitorActions
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsScreen(
    state: DiagnosticsState,
    onRefresh: () -> Unit,
    onMonitor: () -> Unit,
    onChargingTools: () -> Unit,
    onPrivileged: (Boolean) -> Unit,
    onTab: (Int) -> Unit = {},
    monitor: codes.swistak.batterymonitor.monitoring.presentation.MonitoringSnapshot? = null,
    monitorActions: Map<String, MonitorAction> = emptyMap()
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val preferences = context.getSharedPreferences(SettingsContract.SETTINGS_FILE, 0)
    val multiplier =
        preferences.getString(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "1")?.toIntOrNull()
            ?: 1
    val basic = state.basic?.let { diagnosticGroups(it, multiplier) }.orEmpty()
    val advanced = state.advanced?.let { diagnosticGroups(it, multiplier) }.orEmpty()
    val app = state.appRaw?.let { diagnosticGroups(it, multiplier) }.orEmpty()
    val snapshots = listOfNotNull(state.basic, state.appRaw, state.advanced)
    val normalized = mergedDiagnosticGroups(snapshots, multiplier)
    val raw = basic.drop(3) + app.drop(3) + advanced.drop(3)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(tab) { onTab(tab) }
    var access by rememberSaveable { mutableStateOf(false) }
    var report by rememberSaveable { mutableStateOf(false) }
    var selectedRow by remember { mutableStateOf<DiagnosticRow?>(null) }
    val labels = listOf(
        R.string.diag_overview,
        R.string.nav_battery_group,
        R.string.advanced_section_charging,
        R.string.diag_raw
    )
    val scrollStates = labels.map { rememberLazyListState() }
    val groups = when (tab) {
        1 -> normalized.take(2)
        2 -> normalized.drop(2)
        3 -> filterRawGroups(raw, query)
        else -> emptyList()
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painterResource(R.drawable.ui_shield),
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            val sources =
                (if (tab == 0) listOfNotNull(state.basic) else snapshots).filter { it.hasStats() }
            Text(
                stringResource(
                R.string.current_detail_source,
                sources.map(::diagnosticAccessName).distinct().joinToString(" · ")
                    .ifEmpty { "Android" }) + " · " + (sources.maxOfOrNull { it.capturedAtMillis }
                ?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: "—"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BoxWithConstraints {
            val tabs: @Composable () -> Unit = {
                labels.forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = {
                        Text(
                            stringResource(label),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal
                        )
                    })
                }
            }
            if (maxWidth < 340.dp * LocalDensity.current.fontScale) {
                SecondaryScrollableTabRow(
                    selectedTabIndex = tab,
                    edgePadding = 12.dp,
                    containerColor = MaterialTheme.colorScheme.background,
                    tabs = tabs
                )
            } else SecondaryTabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
                indicator = {
                    TabRowDefaults.PrimaryIndicator(
                        Modifier.tabIndicatorOffset(
                            tab, matchContentSize = false
                        ), width = 46.dp
                    )
                },
                tabs = tabs
            )
        }
        if (tab == 3) Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.diag_search_key)) },
                leadingIcon = { Icon(painterResource(R.drawable.ui_search), null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = {
                        query = ""
                    }) {
                        Icon(
                            painterResource(R.drawable.ui_close), stringResource(R.string.cancel)
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh, enabled = !state.loading && !state.rawLoading) {
                Icon(
                    painterResource(R.drawable.ui_refresh),
                    stringResource(R.string.advanced_action_refresh)
                )
            }
        }
        if (state.loading || state.rawLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            Modifier.weight(1f),
            state = scrollStates[tab],
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(if (tab == 3) 0.dp else 16.dp)
        ) {
            if (tab == 0) item {
                DiagnosticCard(stringResource(R.string.diag_monitor_operation)) {
                    MonitorOperationValues(monitorActions)
                }
            }
            if (tab in 1..2 && state.status != 0 && state.status != R.string.currently_disabled) item {
                Text(
                    stringResource(state.status), color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.advanced != null && tab != 0) item {
                Text(
                    "${diagnosticAccessName(state.advanced)} · UID: ${state.advanced.remoteUid}" + if (state.advanced.shizukuVersion >= 0) " · Shizuku: ${state.advanced.shizukuVersion}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(
                        R.string.current_detail_time,
                        diagnosticTime(state.advanced.capturedAtMillis)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.stale) Text(
                    stringResource(R.string.diag_privileged_stale),
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (tab == 3 && state.rawStatus != 0) item {
                Text(
                    stringResource(state.rawStatus), color = MaterialTheme.colorScheme.error
                )
            }
            if (tab == 3 && groups.isEmpty() && !state.rawLoading) item {
                Text(
                    stringResource(R.string.diag_no_keys),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            groups.forEachIndexed { index, group ->
                if (tab == 3) {
                    val groupKey = "${group.title}:${group.rows.first().accessMethod}"
                    item(key = "raw:$groupKey:header", contentType = "rawHeader") {
                        if (index > 0) Spacer(Modifier.height(16.dp))
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .groupedCardBorder(
                                    true, false, MaterialTheme.colorScheme.outlineVariant, 24.dp
                                )
                                .padding(16.dp)
                        ) {
                            Text(
                                stringResource(if (index == 0) R.string.diag_raw_values else group.title),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                diagnosticGroupSources(group),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(
                        group.rows.size,
                        key = { "raw:$groupKey:${group.rows[it].rawKey}" },
                        contentType = { "rawValue" }) { rowIndex ->
                        val row = group.rows[rowIndex]
                        val last = rowIndex == group.rows.lastIndex
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(
                                    RoundedCornerShape(
                                        bottomStart = if (last) 24.dp else 0.dp,
                                        bottomEnd = if (last) 24.dp else 0.dp
                                    )
                                )
                                .background(MaterialTheme.colorScheme.surface)
                                .groupedCardBorder(
                                    false, last, MaterialTheme.colorScheme.outlineVariant, 24.dp
                                )
                        ) {
                            DiagnosticValue(
                                row.rawKey,
                                row.rawValue.orEmpty(),
                                onClick = { selectedRow = row },
                                raw = true
                            )
                            if (!last) HorizontalDivider(
                                Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                } else item(key = "${tab}:${index}:${group.title}") {
                    DiagnosticCard(stringResource(group.title)) {
                        if (group.rows.isEmpty()) Text(
                            stringResource(R.string.advanced_status_no_stats),
                            Modifier.padding(16.dp)
                        )
                        group.rows.forEachIndexed { rowIndex, row ->
                            DiagnosticValue(
                                if (row.label == 0) row.rawKey else stringResource(row.label),
                                row.value ?: stringResource(R.string.advanced_value_not_available),
                                onClick = { selectedRow = row })
                            if (rowIndex < group.rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        val readings = group.rows.filter { it.value != null }
                        if (readings.isNotEmpty()) Text(
                            stringResource(
                                R.string.current_detail_source, diagnosticGroupSources(group)
                            ),
                            Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (readings.isNotEmpty()) Text(
                            stringResource(
                                R.string.current_detail_time,
                                diagnosticTime(readings.maxOf { it.observedAtMillis })
                            ),
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (tab == 2) item {
                val chargingPrediction =
                    monitor?.configuredPrediction?.takeIf { monitor.plugged != 0 && it.targetPercent in 1..100 }
                if (chargingPrediction != null) {
                    DiagnosticCard(
                        stringResource(
                            R.string.current_prediction_target, chargingPrediction.targetPercent
                        )
                    ) {
                        Text(
                            stringResource(R.string.current_detail_source, monitor.source),
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            stringResource(R.string.current_prediction_explanation),
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                OutlinedButton(onClick = onChargingTools, modifier = Modifier.fillMaxWidth()) {
                    ActionLabel(
                        stringResource(R.string.charging_diagnostics_title), R.drawable.ui_bolt
                    )
                }
            }
            if (tab == 3) item { Spacer(Modifier.height(16.dp)) }
            item {
                Text(
                    stringResource(R.string.diag_additional_sources),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                DiagnosticCard {
                    DiagnosticsLink(
                        stringResource(R.string.pref_cat_privileged_access),
                        stringResource(if (!state.privilegedEnabled) R.string.currently_disabled else if (state.status != 0) state.status else R.string.yes),
                        R.drawable.ui_storage,
                        { access = true })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DiagnosticsLink(
                        stringResource(R.string.diagnostics_monitoring),
                        stringResource(R.string.diagnostics_vendor_settings_summary),
                        R.drawable.ui_battery,
                        onMonitor
                    )
                }
            }
            item {
                if (tab == 3) Spacer(Modifier.height(16.dp))
                if (tab != 3) OutlinedButton(
                    onClick = onRefresh,
                    enabled = !state.loading && !state.rawLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ActionLabel(
                        stringResource(R.string.advanced_action_refresh), R.drawable.ui_refresh
                    )
                }
                TextButton(onClick = { report = true }, modifier = Modifier.fillMaxWidth()) {
                    ActionLabel(stringResource(R.string.diagnostics_export), R.drawable.ui_share)
                }
                Text(
                    stringResource(R.string.diag_source_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    selectedRow?.let { row ->
        val title = if (row.label == 0) row.rawKey else stringResource(row.label)
        val raw = row.rawValue ?: stringResource(R.string.advanced_value_not_available)
        AlertDialog(onDismissRequest = { selectedRow = null }, title = { Text(title) }, text = {
            SelectionContainer {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(row.rawKey, fontFamily = FontFamily.Monospace)
                    Text(raw, fontFamily = FontFamily.Monospace)
                    if (row.unit.isNotEmpty()) Text(
                        stringResource(
                            R.string.current_detail_unit, row.unit
                        )
                    )
                    if (row.source.isNotEmpty()) Text(
                        stringResource(
                            R.string.current_detail_source, "${row.source} (${row.accessMethod})"
                        )
                    )
                    Text(
                        stringResource(
                            R.string.current_detail_time, diagnosticTime(row.observedAtMillis)
                        )
                    )
                }
            }
        }, confirmButton = {
            TextButton(onClick = { copyDiagnostic(context, title, "${row.rawKey}=$raw") }) {
                Text(
                    stringResource(R.string.charging_diagnostics_copy)
                )
            }
        }, dismissButton = {
            TextButton(onClick = {
                selectedRow = null
            }) { Text(stringResource(R.string.cancel)) }
        })
    }
    if (access) AlertDialog(
        onDismissRequest = { access = false },
        title = { Text(stringResource(R.string.pref_cat_privileged_access)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingRow(
                    stringResource(R.string.pref_use_privileged_access),
                    stringResource(R.string.pref_use_privileged_access_summary),
                    { onPrivileged(!state.privilegedEnabled) },
                    checked = state.privilegedEnabled
                )
                Text(
                    stringResource(R.string.advanced_status_no_access),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                access = false
            }) { Text(stringResource(android.R.string.ok)) }
        })
    if (report) DiagnosticReportDialog({ includeRaw ->
        buildString {
            appendLine(resources.getString(R.string.nav_diagnostics))
            if (state.status != 0) appendLine(resources.getString(state.status))
            if (state.stale) appendLine(resources.getString(R.string.diag_privileged_stale))
            append(
                diagnosticReport(
                    normalized + if (includeRaw) raw else emptyList(),
                    resources::getString,
                    includeRaw
                )
            )
        }
    }, onDismiss = { report = false })
}

@Composable
internal fun DiagnosticCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        if (title != null) Text(
            title,
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        content()
    }
}

@Composable
private fun DiagnosticsLink(title: String, summary: String, icon: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            painterResource(icon),
            null,
            Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            painterResource(R.drawable.ui_back),
            null,
            Modifier
                .size(18.dp)
                .rotate(180f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun DiagnosticValue(
    label: String, value: String, onClick: (() -> Unit)? = null, raw: Boolean = false
) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp)
    ) {
        val rawValueMaxWidth = maxWidth * .45f
        if (maxWidth < 280.dp * LocalDensity.current.fontScale) Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (raw) 3 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis
            )
        } else Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top
        ) {
            Text(
                label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                if (raw) Modifier.widthIn(max = rawValueMaxWidth) else Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (raw) 3 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
    }
}

internal fun diagnosticTime(time: Long): String =
    if (time <= 0) "—" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        .format(Date(time))


internal fun copyDiagnostic(context: Context, title: String, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
        ClipData.newPlainText(title, text)
    )
    context.showToast(R.string.charging_diagnostics_copied)
}

@Composable
internal fun DiagnosticReportDialog(
    create: (Boolean) -> String, optionLabel: Int = R.string.diag_include_raw, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var include by rememberSaveable { mutableStateOf(false) }
    var reportFailed by remember { mutableStateOf(false) }
    val currentCreate by rememberUpdatedState(create)
    val report by produceState<String?>(null, include) {
        value = null
        reportFailed = false
        value = withContext(Dispatchers.IO) { runCatching { currentCreate(include) }.getOrNull() }
        reportFailed = value == null
    }
    // Keep large report text out of the Activity saved-state Bundle.
    val pendingFile = context.cacheDir.resolve("diagnostics-report-pending.txt")
    val scope = rememberCoroutineScope()
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) scope.launch {
                val success = withContext(Dispatchers.IO) {
                    runCatching {
                        requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                            pendingFile.inputStream().use { it.copyTo(output) }
                        }
                    }.isSuccess
                }
                context.showToast(if (success) R.string.diagnostics_exported else R.string.diagnostics_export_failed)
            }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diagnostics_export)) },
        text = {
            Column {
                SettingRow(
                    stringResource(optionLabel),
                    stringResource(R.string.diag_report_privacy),
                    { include = !include },
                    checked = include
                )
                SelectionContainer {
                    Text(
                        report
                            ?: stringResource(if (reportFailed) R.string.diagnostics_export_failed else R.string.diagnostics_checking),
                        Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = report != null, onClick = {
                val preview = report.orEmpty()
                scope.launch {
                    val success =
                        withContext(Dispatchers.IO) { runCatching { pendingFile.writeText(preview) }.isSuccess }
                    if (success) save.launch("battery_monitor_diagnostics_${System.currentTimeMillis()}.txt")
                    else context.showToast(R.string.diagnostics_export_failed)
                }
            }) { Text(stringResource(R.string.charging_diagnostics_save)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = report != null, onClick = {
                        copyDiagnostic(
                            context, resources.getString(R.string.nav_diagnostics), report.orEmpty()
                        )
                    }) { Text(stringResource(R.string.charging_diagnostics_copy)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        })
}
