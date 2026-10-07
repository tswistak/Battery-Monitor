/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.diagnostics

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.ui.components.SettingRow

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
    groups: List<Pair<Int, List<String>>>, actions: Map<String, MonitorAction>
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            groups.forEach { (title, keys) ->
                item(key = title) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    DiagnosticCard {
                        val rows = keys.mapNotNull { key -> actions[key]?.takeIf { it.isVisible } }
                            .toList()
                        rows.forEachIndexed { index, action ->
                            val checked = action.checked
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 64.dp)
                                    .then(
                                        if (checked == null) Modifier.clickable(
                                            enabled = action.isEnabled, onClick = action.onClick
                                        )
                                        else Modifier.toggleable(
                                            checked,
                                            enabled = action.isEnabled,
                                            role = Role.Switch,
                                            onValueChange = { action.onClick() })
                                    )
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    painterResource(actionIcon(action.title)),
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
                                }
                                checked?.let { Switch(checked = it, onCheckedChange = null) }
                            }
                            if (index < rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun actionIcon(title: Int) = when (title) {
    R.string.diagnostics_notifications, R.string.live_updates_notif_chan_name -> R.drawable.ui_bell
    R.string.diagnostics_service, R.string.diag_start_monitor -> R.drawable.ui_refresh
    R.string.diagnostics_database, R.string.diagnostics_debug_logs -> R.drawable.ui_history
    R.string.diagnostics_export, R.string.charging_diagnostics_report -> R.drawable.ui_share
    R.string.diagnostics_battery_optimization, R.string.diag_stop_monitor -> R.drawable.ui_battery
    R.string.diagnostics_vendor_settings -> R.drawable.ui_settings
    R.string.diagnostics_dont_kill_my_app, R.string.charging_diagnostics_instructions_hint -> R.drawable.ui_help
    R.string.charging_diagnostics_capture -> R.drawable.ui_bolt
    else -> R.drawable.ui_info
}
