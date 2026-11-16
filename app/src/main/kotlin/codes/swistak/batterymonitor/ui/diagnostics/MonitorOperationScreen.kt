/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.ui.components.SettingRow
import codes.swistak.batterymonitor.ui.settings.settingIcon

internal class MonitorAction(val title: Int, summary: String = "", enabled: Boolean = true) {
    var summary by mutableStateOf(summary)
    var isEnabled by mutableStateOf(enabled)
    var isVisible by mutableStateOf(true)
    var checked by mutableStateOf<Boolean?>(null)
    var onClick: () -> Unit = {}
}

@Composable
internal fun MonitorOperationValues(actions: Map<String, MonitorAction>) {
    val rows = actions.values.filter { it.isVisible }
    rows.forEachIndexed { index, action ->
        val checked = action.checked
        if (checked == null) {
            DiagnosticValue(
                stringResource(action.title),
                action.summary,
                onClick = action.onClick.takeIf { action.isEnabled })
        } else {
            SettingRow(
                stringResource(action.title), action.summary, action.onClick, checked = checked
            )
        }
        if (index < rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun MonitorOperationScreen(
    groups: List<Pair<Int, List<String>>>,
    actions: Map<String, MonitorAction>,
    highlightKey: String? = null
) {
    val state = rememberLazyListState()
    LaunchedEffect(highlightKey) {
        val index = groups.indexOfFirst { highlightKey in it.second }
        if (index >= 0) state.scrollToItem(index)
    }
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            groups.forEach { (title, keys) ->
                item(key = title) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    DiagnosticCard {
                        val rows = keys.mapNotNull { key ->
                            actions[key]?.takeIf { it.isVisible || key == highlightKey }
                                ?.let { key to it }
                        }
                        rows.forEachIndexed { index, (key, action) ->
                            val highlighted = key == highlightKey
                            val enabled = action.isEnabled && action.isVisible
                            val bringIntoView = remember(key) { BringIntoViewRequester() }
                            LaunchedEffect(highlightKey) {
                                if (highlighted) {
                                    withFrameNanos { }
                                    bringIntoView.bringIntoView()
                                }
                            }
                            val checked = action.checked
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 64.dp)
                                    .bringIntoViewRequester(bringIntoView)
                                    .background(if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                    .semantics { selected = highlighted }
                                    .then(
                                        if (checked == null) Modifier.clickable(
                                            enabled = enabled,
                                            role = Role.Button,
                                            onClick = action.onClick
                                        )
                                        else Modifier.toggleable(
                                            checked,
                                            enabled = enabled,
                                            role = Role.Switch,
                                            onValueChange = { action.onClick() })
                                    )
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    painterResource(settingIcon(key)),
                                    null,
                                    Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(action.title),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    if (action.summary.isNotEmpty()) Text(
                                        action.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (!action.isVisible) Text(
                                        stringResource(R.string.diag_value_not_available),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                checked?.let {
                                    Switch(
                                        checked = it, onCheckedChange = null, enabled = enabled
                                    )
                                }
                            }
                            if (index < rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}
