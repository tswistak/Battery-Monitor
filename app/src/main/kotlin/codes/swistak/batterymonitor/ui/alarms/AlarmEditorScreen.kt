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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import codes.swistak.batterymonitor.R
import java.util.Locale

@Composable
internal fun ThresholdDialog(
    state: AlarmsState,
    onInput: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onEditExisting: () -> Unit,
    onRetry: () -> Unit
) {
    val draft = state.draft ?: return
    val temperature = draft.type.startsWith("temp_")
    val unit = if (temperature) if (draft.inputFahrenheit) "°F" else "°C" else "%"
    val bounds = alarmThresholdBounds(draft.type, draft.inputFahrenheit)
    val valid = validAlarmDraft(draft) && state.collisionId == null && !state.draftConflict
    val canSave = state.canMutate && valid
    val focus = remember(draft.id, draft.type) { FocusRequester() }
    var confirmDelete by rememberSaveable(draft.id, draft.type) { mutableStateOf(false) }
    LaunchedEffect(draft.id, draft.type) { focus.requestFocus() }
    Dialog(
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = { if (!state.busy) onDismiss() }) {
        Surface(
            Modifier
                .imePadding()
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(alarmTypeLabel(draft.type)),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    stringResource(if (draft.id == null) R.string.alarms_add_threshold else R.string.alarms_edit_threshold),
                    style = MaterialTheme.typography.titleSmall
                )
                OutlinedTextField(
                    value = draft.thresholdInput.orEmpty(),
                    onValueChange = onInput,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    enabled = state.canMutate && !state.draftConflict,
                    label = { Text(stringResource(R.string.pref_alarm_threshold)) },
                    suffix = { Text(unit) },
                    singleLine = true,
                    isError = !valid && !draft.thresholdInput.isNullOrEmpty(),
                    supportingText = {
                        Text(
                            stringResource(
                                if (state.collisionId != null) R.string.alarms_duplicate
                                else if (temperature) R.string.alarms_temperature_range else R.string.alarms_numeric_range,
                                bounds.first,
                                bounds.last
                            )
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        // A text keyboard keeps minus and localized separators available for temperature.
                        keyboardType = if (temperature) KeyboardType.Text else KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (canSave) onSave() })
                )
                if (state.collisionId != null) TextButton(
                    onClick = onEditExisting, enabled = state.canMutate
                ) {
                    Text(stringResource(R.string.alarms_edit_existing))
                }
                Text(
                    stringResource(if (draft.id == null) R.string.alarms_new_enabled else R.string.alarms_edit_keeps_enabled),
                    style = MaterialTheme.typography.bodySmall
                )
                if (state.draftConflict) Text(
                    stringResource(R.string.alarms_conflict),
                    color = MaterialTheme.colorScheme.error
                )
                (state.readError ?: state.error)?.let {
                    Text(
                        it, color = MaterialTheme.colorScheme.error
                    )
                }
                if (state.readError != null || state.draftConflict) TextButton(
                    onClick = onRetry, enabled = !state.busy
                ) {
                    Text(stringResource(R.string.advanced_action_refresh))
                }
                if (draft.id != null) TextButton(
                    onClick = { confirmDelete = true },
                    enabled = state.canMutate && !state.draftConflict
                ) {
                    Text(
                        stringResource(R.string.menu_delete_alarm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onDismiss, enabled = !state.busy
                    ) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = onSave, enabled = canSave) {
                        Text(stringResource(if (draft.id == null) R.string.alarms_add_threshold else R.string.charging_diagnostics_save))
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.menu_delete_alarm)) },
        text = {
            val original = alarmThresholdInput(
                draft.copy(threshold = draft.originalThreshold),
                draft.inputFahrenheit,
                Locale.forLanguageTag(requireNotNull(draft.inputLocale))
            )
            Text(
                stringResource(
                    R.string.alarms_delete_threshold_confirmation,
                    stringResource(alarmTypeLabel(draft.type)),
                    "$original $unit"
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    confirmDelete = false
                    onDelete()
                }, enabled = state.canMutate && !state.draftConflict
            ) {
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
