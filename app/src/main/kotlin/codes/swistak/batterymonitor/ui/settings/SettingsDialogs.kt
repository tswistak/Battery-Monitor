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
package codes.swistak.batterymonitor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.ui.components.NumericInputResult
import codes.swistak.batterymonitor.ui.components.parseLocalizedInt
import codes.swistak.batterymonitor.ui.theme.BatterySpacing
import java.text.NumberFormat
import java.util.Collections

internal data class SettingsOrderItem(val value: String, val label: String, val selected: Boolean)

internal sealed interface SettingsDialog {
    data class Choices(
        val title: String,
        val message: String? = null,
        val options: List<String>,
        val selected: Set<Int>,
        val multiple: Boolean = true,
        val allowEmpty: Boolean = false,
        val onConfirm: (Set<Int>) -> Unit
    ) : SettingsDialog

    data class Number(
        val title: String,
        val message: String,
        val initial: Int,
        val min: Int,
        val max: Int,
        val error: String,
        val onConfirm: (Int) -> Unit
    ) : SettingsDialog

    data class Ordered(
        val title: String,
        val message: String?,
        val items: List<SettingsOrderItem>,
        val allowEmpty: Boolean,
        val onConfirm: (List<SettingsOrderItem>) -> Unit
    ) : SettingsDialog

    data class AutoExport(
        val title: String,
        val directoryLabel: String,
        val frequencyLabels: List<String>,
        val frequencyEnabled: List<Boolean>,
        val frequencyIndex: Int,
        val modeLabels: List<String>,
        val modeIndex: Int,
        val formatLabels: List<String>,
        val formatIndex: Int,
        val configured: Boolean,
        val onConfirm: (Int, Int, Int) -> Unit,
        val onChooseDirectory: () -> Unit,
        val onDisable: () -> Unit
    ) : SettingsDialog

    data class Confirm(
        val message: String,
        val title: String? = null,
        val confirmLabel: Int = R.string.yes,
        val onConfirm: () -> Unit
    ) : SettingsDialog

    data class Message(val message: String) : SettingsDialog
}

@Composable
internal fun SettingsDialogs(dialog: SettingsDialog?, busy: Boolean, onDismiss: () -> Unit) {
    if (busy) {
        AlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BatterySpacing.md)
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.settings_operation_processing))
            }
        })
        return
    }
    if (dialog == null) return
    key(dialog) {
        when (dialog) {
            is SettingsDialog.Choices -> ChoicesDialog(dialog, onDismiss)
            is SettingsDialog.Number -> NumberDialog(dialog, onDismiss)
            is SettingsDialog.Ordered -> OrderedDialog(dialog, onDismiss)
            is SettingsDialog.AutoExport -> AutoExportDialog(dialog, onDismiss)
            is SettingsDialog.Confirm -> SettingsAlert(
                title = dialog.title,
                onDismiss = onDismiss,
                onConfirm = dialog.onConfirm,
                confirmLabel = dialog.confirmLabel
            ) { Text(dialog.message) }

            is SettingsDialog.Message -> SettingsAlert(
                onDismiss = onDismiss, onConfirm = {}, cancellable = false
            ) { Text(dialog.message) }
        }
    }
}

@Composable
private fun SettingsAlert(
    title: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    enabled: Boolean = true,
    confirmLabel: Int = R.string.okay,
    cancellable: Boolean = true,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss, title = title?.let { { Text(it) } }, text = {
        Column(
            Modifier
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(BatterySpacing.sm)
        ) { content() }
    }, confirmButton = {
        TextButton(onClick = {
            onDismiss()
            onConfirm()
        }, enabled = enabled) { Text(stringResource(confirmLabel)) }
    }, dismissButton = if (cancellable) {
        { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    } else null)
}

@Composable
private fun ChoicesDialog(dialog: SettingsDialog.Choices, onDismiss: () -> Unit) {
    var selected by remember {
        mutableStateOf(dialog.selected.filter { it in dialog.options.indices }.toSet())
    }
    SettingsAlert(
        title = dialog.title,
        onDismiss = onDismiss,
        onConfirm = { dialog.onConfirm(selected) },
        enabled = dialog.allowEmpty || selected.isNotEmpty()
    ) {
        dialog.message?.let { Text(it) }
        Column(if (dialog.multiple) Modifier else Modifier.selectableGroup()) {
            dialog.options.forEachIndexed { index, label ->
                val checked = index in selected
                val rowModifier = if (dialog.multiple) {
                    Modifier.toggleable(checked, role = Role.Checkbox) {
                        selected = if (it) selected + index else selected - index
                    }
                } else {
                    Modifier.selectable(checked, role = Role.RadioButton) {
                        selected = setOf(index)
                    }
                }
                Row(
                    rowModifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BatterySpacing.sm)
                ) {
                    if (dialog.multiple) Checkbox(checked, onCheckedChange = null)
                    else RadioButton(checked, onClick = null)
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun NumberDialog(dialog: SettingsDialog.Number, onDismiss: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) {
        NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = false }
    }
    var input by remember(locale) { mutableStateOf(formatter.format(dialog.initial)) }
    val value = (parseLocalizedInt(
        input, locale, dialog.min, dialog.max, 1
    ) as? NumericInputResult.Valid)?.value
    val confirm = {
        value?.let {
            onDismiss()
            dialog.onConfirm(it)
        }
    }
    SettingsAlert(
        title = dialog.title,
        onDismiss = onDismiss,
        onConfirm = { value?.let(dialog.onConfirm) },
        enabled = value != null
    ) {
        if (dialog.message.isNotBlank()) Text(dialog.message)
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_numeric_value)) },
            isError = value == null,
            supportingText = if (value == null) {
                { Text(dialog.error) }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number, imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { confirm() })
        )
    }
}

@Composable
private fun OrderedDialog(dialog: SettingsDialog.Ordered, onDismiss: () -> Unit) {
    var items by remember { mutableStateOf(dialog.items) }
    SettingsAlert(
        title = dialog.title,
        onDismiss = onDismiss,
        onConfirm = { dialog.onConfirm(items) },
        enabled = dialog.allowEmpty || items.any { it.selected }) {
        dialog.message?.let { Text(it) }
        items.forEachIndexed { index, item ->
            key(item.value) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .toggleable(item.selected, role = Role.Checkbox) { checked ->
                                items =
                                    items.map { if (it.value == item.value) it.copy(selected = checked) else it }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BatterySpacing.sm)
                    ) {
                        Checkbox(item.selected, onCheckedChange = null)
                        Text(item.label)
                    }
                    IconButton(onClick = {
                        items =
                            items.toMutableList().apply { Collections.swap(this, index, index - 1) }
                    }, enabled = index > 0) {
                        Icon(
                            painterResource(R.drawable.ui_up),
                            stringResource(R.string.settings_move_item_up, item.label)
                        )
                    }
                    IconButton(onClick = {
                        items =
                            items.toMutableList().apply { Collections.swap(this, index, index + 1) }
                    }, enabled = index < items.lastIndex) {
                        Icon(
                            painterResource(R.drawable.ui_down),
                            stringResource(R.string.settings_move_item_down, item.label)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoExportDialog(dialog: SettingsDialog.AutoExport, onDismiss: () -> Unit) {
    var frequency by remember { mutableStateOf(dialog.frequencyIndex) }
    var mode by remember { mutableStateOf(dialog.modeIndex) }
    var format by remember { mutableStateOf(dialog.formatIndex) }
    SettingsAlert(
        title = dialog.title,
        onDismiss = onDismiss,
        onConfirm = { dialog.onConfirm(frequency, mode, format) },
        enabled = frequency in dialog.frequencyLabels.indices && dialog.frequencyEnabled.getOrNull(
            frequency
        ) == true && mode in dialog.modeLabels.indices && format in dialog.formatLabels.indices
    ) {
        Text(dialog.directoryLabel, style = MaterialTheme.typography.bodySmall)
        SettingsDropdown(
            stringResource(R.string.pref_auto_log_export_frequency),
            dialog.frequencyLabels,
            frequency,
            { frequency = it },
            dialog.frequencyEnabled
        )
        SettingsDropdown(
            stringResource(R.string.pref_auto_log_export_mode),
            dialog.modeLabels,
            mode,
            { mode = it })
        SettingsDropdown(
            stringResource(R.string.pref_auto_log_export_format),
            dialog.formatLabels,
            format,
            { format = it })
        TextButton(onClick = {
            onDismiss()
            dialog.onChooseDirectory()
        }) { Text(stringResource(R.string.pref_auto_log_export_directory)) }
        if (dialog.configured) TextButton(onClick = {
            onDismiss()
            dialog.onDisable()
        }) {
            Text(
                stringResource(R.string.pref_disable_auto_log_export),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SettingsDropdown(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    enabled: List<Boolean> = List(options.size) { true }
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.getOrNull(selected).orEmpty(), modifier = Modifier.weight(1f))
                Icon(painterResource(R.drawable.ui_chev), contentDescription = null)
            }
            DropdownMenu(
                expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) }, onClick = {
                        expanded = false
                        onSelect(index)
                    }, enabled = enabled.getOrNull(index) == true
                    )
                }
            }
        }
    }
}
