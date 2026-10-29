/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.alarms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.alarms.ALARM_TYPES
import codes.swistak.batterymonitor.alarms.AlarmGroup
import codes.swistak.batterymonitor.alarms.AlarmRule
import codes.swistak.batterymonitor.alarms.AlarmTypeDefinition
import codes.swistak.batterymonitor.alarms.LogicalAlarm
import codes.swistak.batterymonitor.ui.components.ActionLabel
import codes.swistak.batterymonitor.ui.components.applicationDialogsAllowed
import codes.swistak.batterymonitor.ui.components.groupedCardBorder

internal fun alarmTypeLabel(type: String): Int = when (type) {
    "fully_charged" -> R.string.alarm_type_fully_charged
    "charge_rises" -> R.string.alarm_type_charge_rises
    "charge_drops" -> R.string.alarm_type_charge_drops
    "charging_limit_met" -> R.string.alarms_charging_target
    "discharging_limit_met" -> R.string.alarms_discharging_target
    "temp_rises" -> R.string.alarm_type_temperature_rises
    "temp_drops" -> R.string.alarm_type_temperature_drops
    else -> R.string.alarms_android_problem
}

@Composable
internal fun AlarmsScreen(
    state: AlarmsState,
    convertFahrenheit: Boolean,
    chargingTarget: Int?,
    dischargingTarget: Int,
    monitoringAvailable: Boolean,
    onEdit: (Int) -> Unit,
    onAdd: (String) -> Unit,
    onEnabled: (String, LogicalAlarm?, Boolean) -> Unit,
    onChannelSettings: (String) -> Unit,
    onNotificationSettings: () -> Unit,
    onTargets: () -> Unit,
    onRetry: () -> Unit,
    onDisableInvalid: (AlarmRule) -> Unit,
    onDeleteInvalid: (AlarmRule) -> Unit,
    onNoticeHandled: () -> Unit,
    onRevealHandled: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    // Keep the restored position unmeasured until saved threshold rows have loaded.
    val loadingListState = remember { LazyListState() }
    val snackbar = remember { SnackbarHostState() }
    var recoveryAction by remember { mutableStateOf<Pair<AlarmRule, Boolean>?>(null) }
    val keys = remember(state.projection) {
        buildList {
            add("summary")
            add("status")
            AlarmGroup.entries.forEach { group ->
                add("group_$group")
                state.projection.types.filter { it.definition.group == group }.forEach { rows ->
                    add(rows.definition.type)
                    if (rows.definition.bounds != null) {
                        rows.alarms.forEach { add("rule_${it.id}") }
                        add("add_${rows.definition.type}")
                    }
                }
            }
        }
    }
    LaunchedEffect(state.revealType, state.revealId) {
        state.revealType?.let { type ->
            val alarm =
                state.projection.types.flatMap { it.alarms }.find { state.revealId in it.memberIds }
            val key = alarm?.takeIf { ALARM_TYPES.single { it.type == type }.bounds != null }
                ?.let { "rule_${it.id}" } ?: type
            val index = keys.indexOf(key)
            if (index >= 0) listState.scrollToItem(index)
            onRevealHandled()
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            onNoticeHandled()
        }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = if (state.hasSnapshot) listState else loadingListState,
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .focusRestorer(),
            contentPadding = PaddingValues(20.dp)
        ) {
            item(key = "summary") {
                val count = state.enabledCount
                Surface(
                    modifier = Modifier.padding(bottom = 12.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(painterResource(R.drawable.ui_bell), null, Modifier.size(16.dp))
                        Text(
                            if (count == null) stringResource(R.string.alarms_state_unknown)
                            else pluralStringResource(R.plurals.alarms_active_count, count, count),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
            item(key = "status") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.readError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        Text(stringResource(if (state.hasSnapshot) R.string.alarms_stale else R.string.alarms_state_unknown))
                        TextButton(onClick = onRetry, enabled = !state.busy) {
                            ActionLabel(
                                stringResource(R.string.advanced_action_refresh),
                                R.drawable.ui_refresh
                            )
                        }
                    }
                    if (state.draft == null) state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        TextButton(
                            onClick = onRetry, enabled = !state.busy
                        ) { Text(stringResource(R.string.advanced_action_refresh)) }
                    }
                    if (!state.monitoringDesired || !monitoringAvailable) Text(
                        stringResource(if (!state.monitoringDesired) R.string.alarms_monitor_stopped else R.string.alarms_monitor_unconfirmed),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (state.notificationsBlocked || state.groupBlocked) {
                        Text(stringResource(if (state.notificationsBlocked) R.string.current_notifications_off else R.string.alarm_notifs_disabled_summary))
                        TextButton(onClick = onNotificationSettings) { Text(stringResource(R.string.alarm_chan_settings_b)) }
                    }
                }
            }
            AlarmGroup.entries.forEach { group ->
                item(key = "group_$group") {
                    Row(
                        Modifier.padding(start = 4.dp, top = 16.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(
                                when (group) {
                                    AlarmGroup.LEVEL -> R.drawable.ui_battery
                                    AlarmGroup.TARGETS -> R.drawable.ui_target
                                    AlarmGroup.TEMPERATURE -> R.drawable.ui_temp
                                    AlarmGroup.STATUS -> R.drawable.ui_heart
                                }
                            ),
                            null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(
                                when (group) {
                                    AlarmGroup.LEVEL -> R.string.history_battery_level
                                    AlarmGroup.TARGETS -> R.string.alarms_targets_group
                                    AlarmGroup.TEMPERATURE -> R.string.current_temperature
                                    AlarmGroup.STATUS -> R.string.widget_status
                                }
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { heading() })
                    }
                }
                val types = state.projection.types.filter { it.definition.group == group }
                types.forEachIndexed { index, rows ->
                    val definition = rows.definition
                    val type = definition.type
                    item(key = type, contentType = "type") {
                        AlarmTypeHeader(
                            definition,
                            rows.alarms.firstOrNull(),
                            state,
                            if (type == "charging_limit_met") chargingTarget else dischargingTarget,
                            first = index == 0,
                            last = index == types.lastIndex && definition.bounds == null,
                            onEnabled = { onEnabled(type, rows.alarms.firstOrNull(), it) },
                            onChannelSettings = { onChannelSettings(type) },
                            onTargets = onTargets
                        )
                    }
                    if (definition.bounds != null) {
                        items(
                            rows.alarms,
                            key = { "rule_${it.id}" },
                            contentType = { "threshold" }) { alarm ->
                            ThresholdRow(
                                alarm,
                                convertFahrenheit,
                                state.canMutate,
                                onEdit = { onEdit(alarm.id) },
                                onEnabled = { onEnabled(type, alarm, it) })
                        }
                        item(key = "add_$type", contentType = "add") {
                            AlarmSurface(last = index == types.lastIndex) {
                                TextButton(
                                    onClick = { onAdd(type) },
                                    enabled = state.canMutate,
                                    modifier = Modifier
                                        .padding(
                                            start = 16.dp, end = 14.dp, bottom = 8.dp
                                        )
                                        .heightIn(min = 48.dp)
                                ) {
                                    ActionLabel(
                                        stringResource(R.string.alarms_add_threshold),
                                        R.drawable.ui_plus
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (state.projection.invalid.isNotEmpty()) {
                item(key = "recovery") {
                    Text(
                        stringResource(R.string.alarms_invalid_records),
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                items(state.projection.invalid, key = { "invalid_${it.id}" }) { rule ->
                    AlarmSurface(first = true, last = true) {
                        Text(
                            "#${rule.id} · ${rule.type} · ${rule.threshold}",
                            Modifier.padding(16.dp)
                        )
                        Text(
                            stringResource(if (rule.enabled) R.string.alarms_enabled else R.string.currently_disabled),
                            Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (rule.enabled) TextButton(onClick = {
                                recoveryAction = rule to false
                            }, enabled = state.canMutate) {
                                Text(stringResource(R.string.alarms_disable))
                            }
                            TextButton(
                                onClick = { recoveryAction = rule to true },
                                enabled = state.canMutate
                            ) {
                                Text(stringResource(R.string.menu_delete_alarm))
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    recoveryAction?.let { (rule, delete) ->
        if (applicationDialogsAllowed()) AlertDialog(
            onDismissRequest = { recoveryAction = null },
            title = { Text(stringResource(if (delete) R.string.menu_delete_alarm else R.string.alarms_disable)) },
            text = { Text("#${rule.id} · ${rule.type} · ${rule.threshold}") },
            confirmButton = {
                TextButton(onClick = {
                    recoveryAction = null
                    if (delete) onDeleteInvalid(rule) else onDisableInvalid(rule)
                }, enabled = state.canMutate) {
                    Text(stringResource(if (delete) R.string.menu_delete_alarm else R.string.alarms_disable))
                }
            },
            dismissButton = {
                TextButton(onClick = { recoveryAction = null }) {
                    Text(
                        stringResource(
                            R.string.cancel
                        )
                    )
                }
            })
    }
}

@Composable
private fun AlarmSurface(
    first: Boolean = false, last: Boolean = false, content: @Composable ColumnScope.() -> Unit
) {
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
            .background(MaterialTheme.colorScheme.surface)
            .groupedCardBorder(first, last, MaterialTheme.colorScheme.outlineVariant),
        content = content
    )
}

@Composable
private fun AlarmTypeHeader(
    definition: AlarmTypeDefinition,
    alarm: LogicalAlarm?,
    state: AlarmsState,
    target: Int?,
    first: Boolean,
    last: Boolean,
    onEnabled: (Boolean) -> Unit,
    onChannelSettings: () -> Unit,
    onTargets: () -> Unit
) {
    val label = stringResource(alarmTypeLabel(definition.type))
    val channel = state.channels[definition.type]
    var options by rememberSaveable { mutableStateOf(false) }
    AlarmSurface(first, last) {
        if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )
            if (definition.bounds == null) {
                if (state.hasSnapshot) Switch(
                    checked = alarm?.enabled == true,
                    onCheckedChange = onEnabled,
                    enabled = state.canMutate,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = label }) else Text(
                    stringResource(R.string.alarms_state_unknown),
                    Modifier
                        .weight(0.7f)
                        .padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Box {
                IconButton(onClick = { options = true }) {
                    Icon(
                        painterResource(R.drawable.ui_more),
                        stringResource(R.string.alarms_options, label)
                    )
                }
                DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(alarmDeliveryLabel(channel))) },
                        onClick = {},
                        enabled = false
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.alarm_chan_settings_b)) },
                        onClick = {
                            options = false
                            onChannelSettings()
                        })
                }
            }
        }
        if (channel?.blocked == true) Text(
            stringResource(R.string.alarm_chan_disabled),
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        if (definition.group == AlarmGroup.TARGETS) {
            Text(
                if (target == null) stringResource(R.string.alarms_target_unknown)
                else stringResource(R.string.current_prediction_target, target),
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onTargets)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (definition.type == "health_failure") Text(
            stringResource(R.string.alarms_android_problem_summary),
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ThresholdRow(
    alarm: LogicalAlarm,
    fahrenheit: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit,
    onEnabled: (Boolean) -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    val temperature = alarm.type.startsWith("temp_")
    val value = alarmThresholdInput(
        AlarmDraft(
            type = alarm.type, threshold = alarm.members.first().threshold
        ), fahrenheit, locale
    )
    val unit = if (temperature) if (fahrenheit) "°F" else "°C" else "%"
    val description = "${stringResource(alarmTypeLabel(alarm.type))} $value $unit"
    val editLabel = stringResource(R.string.alarms_edit_threshold)
    AlarmSurface {
        HorizontalDivider(
            Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .clickable(
                        enabled = enabled,
                        role = Role.Button,
                        onClickLabel = editLabel,
                        onClick = onEdit
                    )
                    .semantics { contentDescription = description }
                    .padding(horizontal = 4.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildAnnotatedString {
                        append(value)
                        withStyle(
                            SpanStyle(
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                fontWeight = FontWeight.Medium
                            )
                        ) {
                            append(" $unit")
                        }
                    },
                    Modifier
                        .weight(1f, fill = false)
                        .clearAndSetSemantics {},
                    style = MaterialTheme.typography.titleLarge,
                    color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    painterResource(R.drawable.ui_edit),
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = alarm.enabled,
                onCheckedChange = onEnabled,
                enabled = enabled,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = description })
        }
    }
}
