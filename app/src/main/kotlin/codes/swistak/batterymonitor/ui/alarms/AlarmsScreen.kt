/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.alarms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.alarms.AlarmRule
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.ui.components.ActionLabel
import codes.swistak.batterymonitor.ui.components.CapabilityNotice
import java.text.NumberFormat

@Composable
internal fun AlarmsScreen(
    state: AlarmsState,
    convertFahrenheit: Boolean,
    chargingTarget: Int?,
    dischargingTarget: Int,
    onEdit: (Int) -> Unit,
    onAdd: () -> Unit,
    onEnabled: (Int, Boolean) -> Unit,
    onNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val typeValues = stringArrayResource(R.array.alarm_type_values)
    val typeLabels = stringArrayResource(R.array.alarm_types_display)
    val groups = remember(state.rules) {
        state.rules.groupBy { rule ->
            when (rule.type) {
                "fully_charged", "charging_limit_met", "discharging_limit_met", "charge_drops", "charge_rises" -> R.string.history_battery_level
                "temp_drops", "temp_rises" -> R.string.current_temperature
                "health_failure" -> R.string.current_android_health
                else -> R.string.nav_alarms
            }
        }
    }
    val activeCount = state.rules.count { it.enabled }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "summary") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            pluralStringResource(
                                R.plurals.alarms_active_count, activeCount, activeCount
                            ),
                            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 33.sp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(R.string.alarms_monitoring_caption),
                            Modifier.padding(top = 3.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Icon(
                            painterResource(R.drawable.ui_bell),
                            null,
                            Modifier
                                .padding(11.dp)
                                .size(24.dp)
                        )
                    }
                }
            }
            if (state.loading) item(key = "loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.error?.let { error ->
                item(key = "error") {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
            if (state.notificationsBlocked) item(key = "notifications") {
                Column {
                    CapabilityNotice(
                        stringResource(R.string.current_notifications_off),
                        stringResource(R.string.current_notifications_off_body)
                    )
                    TextButton(onClick = onNotificationSettings) {
                        ActionLabel(
                            stringResource(R.string.alarm_settings_subtitle), R.drawable.ui_settings
                        )
                    }
                }
            }
            listOf(
                R.string.history_battery_level,
                R.string.current_temperature,
                R.string.current_android_health,
                R.string.nav_alarms
            ).forEach { heading ->
                val rules = groups[heading].orEmpty()
                if (rules.isNotEmpty()) {
                    item(key = "heading_$heading") {
                        Text(
                            stringResource(heading),
                            Modifier.padding(start = 4.dp, top = 10.dp, bottom = 1.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(rules, key = { it.id }, contentType = { "alarm" }) { rule ->
                        val condition =
                            typeLabels.getOrNull(typeValues.indexOf(rule.type)) ?: rule.type
                        AlarmRuleRow(
                            rule = rule,
                            condition = condition,
                            channel = state.channels[rule.type],
                            convertFahrenheit = convertFahrenheit,
                            chargingTarget = chargingTarget,
                            dischargingTarget = dischargingTarget,
                            busy = state.busy,
                            onEdit = { onEdit(rule.id) },
                            onEnabled = { onEnabled(rule.id, it) })
                    }
                }
            }
            item(key = "add") {
                Button(
                    onClick = onAdd,
                    enabled = !state.busy && !state.loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp)
                        .heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    ActionLabel(stringResource(R.string.add_alarm), R.drawable.ui_plus)
                }
            }
            item(key = "channel_settings") {
                Column {
                    Text(
                        stringResource(R.string.alarms_channel_note),
                        Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onNotificationSettings) {
                        ActionLabel(
                            stringResource(R.string.alarm_settings_subtitle), R.drawable.ui_settings
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlarmRuleRow(
    rule: AlarmRule,
    condition: String,
    channel: AlarmChannelInfo?,
    convertFahrenheit: Boolean,
    chargingTarget: Int?,
    dischargingTarget: Int,
    busy: Boolean,
    onEdit: () -> Unit,
    onEnabled: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val numberFormat = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    val temperatureUnit = stringResource(R.string.degree_symbol) + stringResource(
        if (convertFahrenheit) R.string.fahrenheit_symbol else R.string.celsius_symbol
    )
    val number = rule.threshold.toIntOrNull()
    val (value, unit) = when (rule.type) {
        "charge_drops", "charge_rises" -> (number?.let { numberFormat.format(it) }
            ?: rule.threshold) to "%"

        "temp_drops", "temp_rises" -> (number?.let {
            DisplayStrings.formatTemp(it, convertFahrenheit).removeSuffix(temperatureUnit)
        } ?: rule.threshold) to temperatureUnit

        "charging_limit_met" -> (chargingTarget?.let { numberFormat.format(it) } ?: "") to "%"
        "discharging_limit_met" -> numberFormat.format(dischargingTarget) to "%"
        "fully_charged" -> "" to ""
        else -> "" to ""
    }
    val enabledDescription =
        stringResource(R.string.pref_alarm_enabled) + ": " + condition + if (value.isEmpty()) "" else " $value $unit"
    OutlinedCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(enabled = !busy, role = Role.Button, onClick = onEdit)
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    condition,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
                if (value.isNotEmpty()) Text(
                    buildAnnotatedString {
                        append(value)
                        withStyle(MaterialTheme.typography.bodyMedium.toSpanStyle()) { append(" $unit") }
                    },
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp),
                    color = colors.onSurface
                )
                if (rule.type == "charging_limit_met" && chargingTarget == null) Text(
                    stringResource(R.string.advanced_value_not_available),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant
                )
                if (rule.type == "charging_limit_met" || rule.type == "discharging_limit_met") Text(
                    if (rule.type == "charging_limit_met" && chargingTarget == null) {
                        stringResource(R.string.alarms_target_help)
                    } else stringResource(
                        R.string.current_prediction_target,
                        if (rule.type == "charging_limit_met") requireNotNull(chargingTarget) else dischargingTarget
                    ), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val color =
                        if (channel == null || channel.blocked) colors.error else colors.onSurfaceVariant
                    Icon(
                        painterResource(R.drawable.ui_sound),
                        null,
                        Modifier.size(14.dp),
                        tint = color
                    )
                    Text(
                        stringResource(alarmDeliveryLabel(channel)),
                        style = MaterialTheme.typography.bodySmall,
                        color = color
                    )
                }
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = onEnabled,
                enabled = !busy,
                modifier = Modifier
                    .padding(end = 14.dp)
                    .semantics {
                        contentDescription = enabledDescription
                    })
        }
    }
}
