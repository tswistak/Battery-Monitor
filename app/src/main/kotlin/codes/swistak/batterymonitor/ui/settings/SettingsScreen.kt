/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.settings.SettingsContract.KEY_BRIGHTNESS
import codes.swistak.batterymonitor.settings.SettingsContract.KEY_COLOR_SOURCE
import codes.swistak.batterymonitor.settings.SettingsControl
import codes.swistak.batterymonitor.ui.components.NumericValueDialog
import codes.swistak.batterymonitor.ui.components.TooltipIconButton
import codes.swistak.batterymonitor.ui.components.applicationDialogsAllowed
import codes.swistak.batterymonitor.ui.components.groupedCardBorder
import codes.swistak.batterymonitor.ui.theme.Brightness
import codes.swistak.batterymonitor.ui.theme.ColorSource
import codes.swistak.batterymonitor.ui.theme.brightness
import codes.swistak.batterymonitor.ui.theme.colorSource

@Composable
internal fun SettingsScreen(
    descriptors: List<SettingDescriptor>,
    initialCategory: String?,
    highlightKey: String?,
    onActivate: (String) -> Unit,
    onChange: (String, Any) -> Unit,
    onNavigateCategory: (String?, String?) -> Unit,
    onDiagnostics: (String, String?) -> Unit
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val resources = LocalResources.current
    val category = SettingsCategory.fromRoute(initialCategory)
    var query by rememberSaveable { mutableStateOf("") }
    val states = rememberSaveableStateHolder()
    var numericKey by rememberSaveable { mutableStateOf<String?>(null) }
    var appearance by rememberSaveable { mutableStateOf<String?>(null) }

    fun activate(descriptor: SettingDescriptor) {
        descriptor.diagnosticDetail?.let { route ->
            onDiagnostics(route, descriptor.key.takeUnless { it == "background_monitoring" })
            return
        }
        when {
            descriptor.control is SettingsControl.Toggle -> onChange(
                descriptor.key, descriptor.value != true
            )

            descriptor.control is SettingsControl.Number -> numericKey = descriptor.key
            descriptor.key == KEY_COLOR_SOURCE || descriptor.key == KEY_BRIGHTNESS -> appearance =
                descriptor.key

            else -> onActivate(descriptor.key)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxHeight()
                .widthIn(max = 900.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            if (category == null) {
                OutlinedTextField(
                    query,
                    { query = it },
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 16.dp),
                    placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ui_search), null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            TooltipIconButton(
                                label = stringResource(R.string.search_clear),
                                onClick = { query = "" }) {
                                Icon(
                                    painterResource(R.drawable.ui_close),
                                    stringResource(R.string.search_clear)
                                )
                            }
                        }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp))
                if (query.isBlank()) states.SaveableStateProvider("categories") {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        itemsIndexed(
                            SettingsCategory.entries,
                            key = { _, item -> item.route }) { index, item ->
                            CategoryRow(
                                item, index == 0, index == SettingsCategory.entries.lastIndex
                            ) {
                                onNavigateCategory(item.route, null)
                            }
                        }
                    }
                } else {
                    val results = descriptors.filter { it.matches(query) }
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (results.isEmpty()) item { Text(stringResource(R.string.settings_search_no_results)) }
                        itemsIndexed(results, key = { _, item -> item.key }) { _, descriptor ->
                            ListItem(
                                headlineContent = { Text(descriptor.title) },
                                supportingContent = {
                                    Text(stringResource(descriptor.category.title) + " · " + descriptor.summary)
                                },
                                leadingContent = {
                                    Icon(
                                        painterResource(settingIcon(descriptor.key)),
                                        null,
                                        Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                trailingContent = {
                                    Icon(
                                        painterResource(R.drawable.ui_forward), null
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                                modifier = Modifier.clickable {
                                    focus.clearFocus()
                                    keyboard?.hide()
                                    if (descriptor.diagnosticDetail != null) {
                                        onDiagnostics(
                                            descriptor.diagnosticDetail,
                                            descriptor.key.takeUnless { it == "background_monitoring" })
                                    } else onNavigateCategory(
                                        descriptor.category.route, descriptor.key
                                    )
                                })
                        }
                    }
                }
            } else states.SaveableStateProvider(category.route) {
                val rows =
                    descriptors.filter { it.category == category && it.diagnosticDetail == null }
                val state = rememberLazyListState()
                LaunchedEffect(highlightKey) {
                    val index = rows.indexOfFirst { it.key == highlightKey }
                    if (index >= 0) state.scrollToItem(index)
                }
                LazyColumn(
                    state = state, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                ) {
                    itemsIndexed(rows, key = { _, item -> item.key }) { index, descriptor ->
                        val first = index == 0 || rows[index - 1].group != descriptor.group
                        val last =
                            index == rows.lastIndex || rows[index + 1].group != descriptor.group
                        if (first && descriptor.group.isNotBlank()) Text(
                            descriptor.group,
                            Modifier
                                .padding(start = 4.dp, top = 18.dp, bottom = 10.dp)
                                .semantics { heading() },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SettingItem(descriptor, first, last, descriptor.key == highlightKey) {
                            activate(descriptor)
                        }
                    }
                    if (category == SettingsCategory.Advanced) item {
                        val background = descriptors.first { it.key == "background_monitoring" }
                        TextButton(
                            onClick = { activate(background) }, Modifier.padding(top = 12.dp)
                        ) {
                            Icon(
                                painterResource(settingIcon(background.key)),
                                null,
                                Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(background.title)
                        }
                        val charging =
                            descriptors.first { it.key == "charging_diagnostics_capture" }
                        TextButton(
                            onClick = {
                                onDiagnostics(requireNotNull(charging.diagnosticDetail), null)
                            }) {
                            Icon(
                                painterResource(settingIcon(charging.key)),
                                null,
                                Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.charging_diagnostics_title))
                        }
                    }
                }
            }
        }

    }
    descriptors.firstOrNull { it.key == numericKey && it.unavailable == null }?.let { row ->
        val number = row.control as SettingsControl.Number
        NumericValueDialog(
            label = row.title,
            fieldLabel = stringResource(R.string.settings_numeric_value),
            unit = stringResource(R.string.percent_symbol),
            initialValue = (row.value as? Int ?: number.defaultValue).coerceIn(
                number.min, number.max
            ),
            min = number.min,
            max = number.max,
            step = 1,
            saveLabel = stringResource(R.string.okay),
            cancelLabel = stringResource(R.string.cancel),
            errorMessage = {
                resources.getString(R.string.alarms_numeric_range, number.min, number.max)
            },
            onSave = {
                onChange(row.key, it)
                numericKey = null
            },
            onCancel = { numericKey = null })
    }
    appearance?.let { key ->
        val choices = if (key == KEY_COLOR_SOURCE) listOf(
            ColorSource.Dynamic.name to R.string.settings_color_palette_material_you,
            ColorSource.BatteryBlue.name to R.string.settings_color_palette_battery_blue
        ) else listOf(
            Brightness.System.name to R.string.settings_brightness_system,
            Brightness.Light.name to R.string.settings_brightness_light,
            Brightness.Dark.name to R.string.settings_brightness_dark,
            Brightness.TrueBlack.name to R.string.settings_brightness_true_black
        )
        var draft by rememberSaveable(key) {
            mutableStateOf(
                descriptors.firstOrNull { it.key == key }?.value as? String ?: choices.first().first
            )
        }
        if (applicationDialogsAllowed()) AlertDialog(
            onDismissRequest = { appearance = null },
            title = { Text(stringResource(if (key == KEY_COLOR_SOURCE) R.string.settings_color_palette else R.string.settings_brightness)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    choices.forEach { (name, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = draft == name, role = Role.RadioButton, onClick = {
                                        draft = name
                                    }), verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(draft == name, null)
                            Text(stringResource(label), Modifier.padding(start = 12.dp))
                        }
                    }
                    if (key == KEY_COLOR_SOURCE && Build.VERSION.SDK_INT < 31) Text(
                        stringResource(R.string.settings_color_palette_unavailable),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = choices.any { it.first == draft }, onClick = {
                    if (appearance == key && choices.any { it.first == draft }) {
                        appearance = null
                        onChange(key, draft)
                    }
                }) { Text(stringResource(R.string.okay)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    appearance = null
                }) { Text(stringResource(R.string.cancel)) }
            })
    }
}

@Composable
private fun CategoryRow(
    category: SettingsCategory, first: Boolean, last: Boolean, onClick: () -> Unit
) {
    Row(
        Modifier
            .cardModifier(first, last)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            painterResource(category.icon),
            null,
            Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(category.title), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(category.summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            painterResource(R.drawable.ui_forward),
            null,
            Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Modifier.cardModifier(
    first: Boolean, last: Boolean, highlighted: Boolean = false
): Modifier {
    val shape = RoundedCornerShape(
        topStart = if (first) 20.dp else 0.dp,
        topEnd = if (first) 20.dp else 0.dp,
        bottomStart = if (last) 20.dp else 0.dp,
        bottomEnd = if (last) 20.dp else 0.dp
    )
    return this
        .fillMaxWidth()
        .background(
            if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape
        )
        .groupedCardBorder(first, last, MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingItem(
    descriptor: SettingDescriptor,
    first: Boolean,
    last: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    val checked =
        if (descriptor.control is SettingsControl.Toggle) descriptor.value == true else null
    val enabled = descriptor.unavailable == null
    val summary = when {
        descriptor.unavailable != null -> descriptor.unavailable
        descriptor.key == KEY_COLOR_SOURCE -> stringResource(R.string.currently_set_to) + stringResource(
            if (colorSource(descriptor.value as? String) == ColorSource.Dynamic) R.string.settings_color_palette_material_you
            else R.string.settings_color_palette_battery_blue
        )

        descriptor.key == KEY_BRIGHTNESS -> stringResource(R.string.currently_set_to) + stringResource(
            when (brightness(descriptor.value as? String)) {
                Brightness.System -> R.string.settings_brightness_system
                Brightness.Light -> R.string.settings_brightness_light
                Brightness.Dark -> R.string.settings_brightness_dark
                Brightness.TrueBlack -> R.string.settings_brightness_true_black
            }
        )

        else -> descriptor.summary
    }
    Row(
        Modifier
            .cardModifier(
                first, last, highlighted
            )
            .then(
                if (checked != null && enabled) Modifier.toggleable(
                    checked,
                    role = Role.Switch,
                    onValueChange = { onClick() }) else Modifier.clickable(
                    enabled = enabled, onClick = onClick
                )
            )
            .padding(16.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(settingIcon(descriptor.key)),
            null,
            Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(descriptor.title, color = MaterialTheme.colorScheme.onSurface)
            if (summary.isNotBlank() && summary != descriptor.title) Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        if (checked != null) Switch(checked, null, enabled = enabled)
        else Icon(
            painterResource(R.drawable.ui_chev),
            null,
            Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
