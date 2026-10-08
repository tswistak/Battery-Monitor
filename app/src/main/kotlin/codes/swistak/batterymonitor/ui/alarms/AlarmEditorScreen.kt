/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.ui.alarms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.ui.components.ActionLabel
import codes.swistak.batterymonitor.ui.components.CapabilityNotice
import codes.swistak.batterymonitor.ui.components.SettingRow
import codes.swistak.batterymonitor.ui.theme.BatterySpacing
import java.text.NumberFormat
import kotlin.math.round
import kotlin.math.roundToInt

@Composable
internal fun AlarmEditorScreen(
    draft: AlarmDraft,
    channel: AlarmChannelInfo?,
    notificationsBlocked: Boolean,
    convertFahrenheit: Boolean,
    chargingTarget: Int?,
    dischargingTarget: Int,
    busy: Boolean,
    error: String?,
    onDraftChange: (AlarmDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onChannelSettings: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val types = stringArrayResource(R.array.alarm_type_values)
    val entries = stringArrayResource(R.array.alarm_type_entries)
    val names = stringArrayResource(R.array.alarm_types_display)
    val index = types.indexOf(draft.type)
    val typeName = names.getOrNull(index) ?: draft.type
    val numeric = draft.type in setOf("charge_drops", "charge_rises", "temp_drops", "temp_rises")
    val temperature = draft.type == "temp_drops" || draft.type == "temp_rises"
    val targetAlarm = draft.type == "charging_limit_met" || draft.type == "discharging_limit_met"
    val target = when (draft.type) {
        "charging_limit_met" -> chargingTarget
        "discharging_limit_met" -> dischargingTarget
        else -> null
    }
    val threshold = draft.threshold.toIntOrNull()?.let { value ->
        if (temperature) DisplayStrings.formatTemp(value, convertFahrenheit, true, locale)
        else NumberFormat.getIntegerInstance(locale).format(value) + "%"
    } ?: draft.threshold
    val description = when {
        numeric -> "$typeName $threshold"
        target != null -> "$typeName · ${
            stringResource(
                R.string.current_prediction_target, target
            )
        }"

        else -> typeName
    }
    var chooseType by rememberSaveable(draft.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(draft.id) { mutableStateOf(false) }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(BatterySpacing.content),
            verticalArrangement = Arrangement.spacedBy(BatterySpacing.normal)
        ) {
            if (notificationsBlocked || channel?.blocked != false) CapabilityNotice(
                stringResource(R.string.notifications_disabled), stringResource(
                    if (notificationsBlocked) R.string.app_notifs_alarms_disabled_summary
                    else R.string.alarm_chan_disabled
                ), Modifier.fillMaxWidth()
            )
            Surface(
                onClick = { chooseType = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    Modifier.padding(BatterySpacing.normal),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.pref_alarm_type),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(typeName, style = MaterialTheme.typography.bodyLarge)
                    }
                    Icon(painterResource(R.drawable.ui_chev), null, Modifier.size(20.dp))
                }
            }
            if (numeric) key(draft.id, draft.type, convertFahrenheit, locale.toLanguageTag()) {
                AlarmThresholdInput(draft, convertFahrenheit, busy, onDraftChange)
            }
            if (targetAlarm) Column(verticalArrangement = Arrangement.spacedBy(BatterySpacing.sm)) {
                Text(
                    if (target != null) stringResource(R.string.current_prediction_target, target)
                    else stringResource(R.string.advanced_value_not_available),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    stringResource(R.string.alarms_target_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedCard(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                SettingRow(
                    stringResource(R.string.pref_alarm_enabled),
                    stringResource(R.string.pref_alarm_enabled_help),
                    onClick = { if (!busy) onDraftChange(draft.copy(enabled = !draft.enabled)) },
                    checked = draft.enabled
                )
            }
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    Modifier.padding(BatterySpacing.normal),
                    verticalArrangement = Arrangement.spacedBy(BatterySpacing.sm)
                ) {
                    Text(
                        stringResource(R.string.pref_cat_channel_settings),
                        style = MaterialTheme.typography.titleMedium
                    )
                    ActionLabel(
                        stringResource(alarmDeliveryLabel(channel)), R.drawable.ui_sound
                    )
                    Text(
                        stringResource(R.string.alarms_channel_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { onChannelSettings(draft.type) }, enabled = !busy) {
                        ActionLabel(
                            stringResource(R.string.alarm_chan_settings_b), R.drawable.ui_external
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(BatterySpacing.xs)) {
                Text(
                    stringResource(R.string.alarms_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (validAlarmDraft(draft)) Text(
                    description, style = MaterialTheme.typography.bodyLarge
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Button(
                onClick = onSave,
                enabled = !busy && validAlarmDraft(draft),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) { Text(stringResource(R.string.charging_diagnostics_save)) }
            if (draft.id != null) OutlinedButton(
                onClick = { confirmDelete = true },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text(
                    stringResource(R.string.menu_delete_alarm),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (chooseType) AlertDialog(
        onDismissRequest = { chooseType = false },
        title = { Text(stringResource(R.string.pref_alarm_type)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                types.forEachIndexed { position, type ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = draft.type == type,
                                enabled = !busy,
                                role = Role.RadioButton,
                                onClick = {
                                    if (draft.type != type) onDraftChange(
                                        draft.copy(
                                            type = type,
                                            threshold = defaultAlarmThreshold(type),
                                            thresholdInput = null
                                        )
                                    )
                                    chooseType = false
                                })
                            .padding(vertical = BatterySpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BatterySpacing.sm)
                    ) {
                        RadioButton(selected = draft.type == type, onClick = null)
                        Text(entries[position])
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { chooseType = false }) { Text(stringResource(R.string.cancel)) }
        })
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.menu_delete_alarm)) },
        text = { Text(stringResource(R.string.alarms_delete_confirmation)) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                onDelete()
            }, enabled = !busy) {
                Text(
                    stringResource(R.string.menu_delete_alarm),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = {
                confirmDelete = false
            }) { Text(stringResource(R.string.cancel)) }
        })
}

@Composable
private fun AlarmThresholdInput(
    draft: AlarmDraft,
    convertFahrenheit: Boolean,
    busy: Boolean,
    onDraftChange: (AlarmDraft) -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    val temperature = draft.type == "temp_drops" || draft.type == "temp_rises"
    val bounds = alarmThresholdBounds(draft.type, convertFahrenheit)
    val label = stringResource(R.string.pref_alarm_threshold)
    val unit = if (temperature) if (convertFahrenheit) "°F" else "°C" else "%"
    val rangeMessage = stringResource(
        if (temperature) R.string.alarms_temperature_range else R.string.alarms_numeric_range,
        bounds.first,
        bounds.last
    )
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var field by remember {
        val text = alarmThresholdInput(draft, convertFahrenheit, locale)
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    var selectOnFocus by remember { mutableStateOf(true) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused && selectOnFocus) {
            field = field.copy(selection = TextRange(0, field.text.length))
            selectOnFocus = false
        }
    }
    Column {
        OutlinedTextField(
            value = field,
            onValueChange = { value ->
                if (temperature || (value.text.length <= 3 && value.text.all(Char::isDigit))) {
                    val textChanged = value.text != field.text
                    field = value
                    if (textChanged) {
                        onDraftChange(
                            withAlarmThresholdInput(
                                draft, value.text, convertFahrenheit, locale
                            )
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            enabled = !busy,
            label = { Text(label) },
            suffix = { Text(unit) },
            supportingText = { Text(rangeMessage) },
            isError = !validAlarmDraft(draft),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (temperature) KeyboardType.Decimal else KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = {
                val updated = withAlarmThresholdInput(draft, field.text, convertFahrenheit, locale)
                if (validAlarmDraft(updated)) {
                    val text = alarmThresholdInput(updated, convertFahrenheit, locale)
                    field = TextFieldValue(text, TextRange(text.length))
                    if (updated != draft || updated.thresholdInput != null) {
                        onDraftChange(updated.copy(thresholdInput = null))
                    }
                }
                keyboard?.hide()
                focusManager.clearFocus()
            })
        )
        Slider(
            value = alarmThresholdValue(draft, convertFahrenheit).toFloat()
                .coerceIn(bounds.first.toFloat(), bounds.last.toFloat()),
            onValueChange = { value ->
                val rounded =
                    if (temperature) round(value * 10.0) / 10.0 else value.roundToInt().toDouble()
                val input = NumberFormat.getNumberInstance(locale).apply {
                    isGroupingUsed = false
                    maximumFractionDigits = if (temperature) 1 else 0
                }.format(rounded)
                val updated = withAlarmThresholdInput(draft, input, convertFahrenheit, locale)
                val text = alarmThresholdInput(updated, convertFahrenheit, locale)
                field = TextFieldValue(text, TextRange(text.length))
                onDraftChange(updated.copy(thresholdInput = null))
            },
            enabled = !busy && validAlarmDraft(draft),
            valueRange = bounds.first.toFloat()..bounds.last.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "$label ($unit)"
                    stateDescription = "${field.text} $unit"
                })
    }
}
