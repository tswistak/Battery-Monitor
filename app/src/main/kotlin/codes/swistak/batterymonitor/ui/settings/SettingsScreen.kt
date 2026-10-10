/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.settings

import android.content.Context
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsContract.KEY_BRIGHTNESS
import codes.swistak.batterymonitor.settings.SettingsContract.KEY_COLOR_SOURCE
import codes.swistak.batterymonitor.ui.components.NumericValueEditor
import codes.swistak.batterymonitor.ui.components.groupedCardBorder
import codes.swistak.batterymonitor.ui.theme.Brightness
import codes.swistak.batterymonitor.ui.theme.ColorSource
import codes.swistak.batterymonitor.ui.theme.brightness
import codes.swistak.batterymonitor.ui.theme.colorSource

@Composable
fun SettingsScreen(
    preferences: PreferenceScreen,
    version: Int,
    initialCategory: String?,
    highlightKey: String?,
    onActivate: (Preference) -> Unit,
    onNavigateCategory: (String?, String?) -> Unit,
    onDiagnostics: (String, String?) -> Unit
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    val descriptors = remember(preferences, version, configuration) {
        val settings =
            context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
        val chargingTargetMode =
            settings.getString(SettingsContract.KEY_CHARGING_TARGET_MODE, "automatic")
        SettingCatalog.entries(preferences, resources::getString).filter {
            SettingCatalog.isVisible(it.key, Build.VERSION.SDK_INT, chargingTargetMode)
        }
    }
    val category = SettingsCategory.fromRoute(initialCategory)
    var query by rememberSaveable { mutableStateOf("") }
    val states = rememberSaveableStateHolder()
    var numeric by remember { mutableStateOf<SeekBarPreference?>(null) }
    var appearance by remember { mutableStateOf<String?>(null) }
    var localVersion by remember { mutableIntStateOf(0) }

    fun activate(descriptor: SettingDescriptor) {
        descriptor.diagnosticDetail?.let { route ->
            onDiagnostics(route, descriptor.key.takeUnless { it == "background_monitoring" })
            return
        }
        val preference = descriptor.preference
        when (preference) {
            is CheckBoxPreference -> {
                val checked = !preference.isChecked
                if (preference.callChangeListener(checked)) preference.isChecked = checked
                localVersion++
            }

            is SeekBarPreference -> numeric = preference
            null -> appearance = descriptor.key
            else -> onActivate(preference)
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
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    painterResource(R.drawable.ui_close),
                                    stringResource(R.string.cancel)
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
                                        painterResource(descriptor.category.icon), null
                                    )
                                },
                                trailingContent = {
                                    Icon(
                                        painterResource(R.drawable.ui_forward), null
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                                modifier = Modifier.clickable {
                                    // Navigation is deliberately separate from preference activation.
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
                val prefix = if (category == SettingsCategory.Notification) 1 else 0
                LaunchedEffect(highlightKey) {
                    val index = rows.indexOfFirst { it.key == highlightKey }
                    if (index >= 0) state.scrollToItem(index + prefix)
                }
                LazyColumn(
                    state = state, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                ) {
                    if (category == SettingsCategory.Notification) item {
                        NotificationPreview(
                            preferences
                        )
                    }
                    itemsIndexed(rows, key = { _, item -> item.key }) { index, descriptor ->
                        val first = index == 0 || rows[index - 1].group != descriptor.group
                        val last =
                            index == rows.lastIndex || rows[index + 1].group != descriptor.group || descriptor.key == SettingsContract.KEY_PREDICTION_TYPE
                        if (first && descriptor.group.isNotBlank()) Text(
                            descriptor.group,
                            Modifier
                                .padding(start = 4.dp, top = 18.dp, bottom = 10.dp)
                                .semantics { heading() },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val unavailable = unavailableReason(descriptor, preferences, context)
                        SettingItem(
                            descriptor,
                            first,
                            last,
                            descriptor.key == highlightKey,
                            unavailable,
                            version + localVersion
                        ) { activate(descriptor) }
                        if (descriptor.key == SettingsContract.KEY_PREDICTION_TYPE) Text(
                            stringResource(R.string.pref_prediction_type_help),
                            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (category == SettingsCategory.Advanced) item {
                        val background = descriptors.first { it.key == "background_monitoring" }
                        TextButton(
                            onClick = { activate(background) }, Modifier.padding(top = 12.dp)
                        ) {
                            Icon(painterResource(R.drawable.ui_current), null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(background.title)
                        }
                    }
                }
            }
        }

    }
    numeric?.let { preference ->
        AlertDialog(
            onDismissRequest = { numeric = null },
            title = { Text(preference.title.toString()) },
            text = {
                NumericValueEditor(
                    preference.title.toString(),
                    "%",
                    preference.value,
                    preference.min,
                    preference.max,
                    maxOf(1, preference.seekBarIncrement),
                    stringResource(R.string.okay),
                    stringResource(R.string.cancel),
                    {
                        resources.getString(
                            R.string.alarms_numeric_range, preference.min, preference.max
                        )
                    },
                    onSave = {
                        if (preference.callChangeListener(it)) preference.value = it
                        localVersion++
                        numeric = null
                    },
                    onCancel = { numeric = null })
            },
            confirmButton = {})
    }
    appearance?.let { key ->
        val settings =
            context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
        val choices = if (key == KEY_COLOR_SOURCE) listOf(
            ColorSource.Dynamic.name to R.string.settings_color_palette_material_you,
            ColorSource.BatteryBlue.name to R.string.settings_color_palette_battery_blue
        ) else listOf(
            Brightness.System.name to R.string.settings_brightness_system,
            Brightness.Light.name to R.string.settings_brightness_light,
            Brightness.Dark.name to R.string.settings_brightness_dark,
            Brightness.TrueBlack.name to R.string.settings_brightness_true_black
        )
        val value = settings.getString(key, choices.first().first)
        AlertDialog(
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
                                    selected = value == name, role = Role.RadioButton, onClick = {
                                        settings.edit { putString(key, name) }
                                        appearance = null
                                    }), verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(value == name, null)
                            Text(stringResource(label), Modifier.padding(start = 12.dp))
                        }
                    }
                    if (key == KEY_COLOR_SOURCE && Build.VERSION.SDK_INT < 31) Text(
                        stringResource(R.string.settings_color_palette_unavailable),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {},
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
    unavailable: String?,
    version: Int,
    onClick: () -> Unit
) {
    val preference = remember(descriptor, version) { descriptor.preference }
    val enabled = unavailable == null
    val settings = LocalContext.current.getSharedPreferences(
        SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
    )
    val summary = when {
        unavailable != null -> unavailable
        preference is SeekBarPreference -> descriptor.summary.ifBlank { "${preference.value}%" }
        descriptor.key == KEY_COLOR_SOURCE -> stringResource(
            if (colorSource(
                    settings.getString(
                        KEY_COLOR_SOURCE, null
                    )
                ) == ColorSource.Dynamic
            ) R.string.settings_color_palette_material_you else R.string.settings_color_palette_battery_blue
        )

        descriptor.key == KEY_BRIGHTNESS -> stringResource(
            when (brightness(settings.getString(KEY_BRIGHTNESS, null))) {
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
                if (preference is CheckBoxPreference && enabled) Modifier.toggleable(
                    preference.isChecked,
                    role = Role.Switch,
                    onValueChange = { onClick() }) else Modifier.clickable(
                    enabled = enabled, onClick = onClick
                )
            )
            .padding(16.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(descriptor.title, color = MaterialTheme.colorScheme.onSurface)
            if (summary.isNotBlank() && summary != descriptor.title) Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        if (preference is CheckBoxPreference) Switch(preference.isChecked, null, enabled = enabled)
        else Icon(
            painterResource(R.drawable.ui_chev),
            null,
            Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun unavailableReason(
    descriptor: SettingDescriptor, screen: PreferenceScreen, context: Context
): String? {
    if (descriptor.key == "change_app_language" && Build.VERSION.SDK_INT < 33) return context.getString(
        R.string.settings_app_language_unavailable
    )
    if (descriptor.category == SettingsCategory.Notification && descriptor.key !in setOf(
            "enable_notifications_button",
            "live_update_system_settings",
            "dismiss_low_battery_on_recovery"
        )
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled() || (manager.getNotificationChannel(codes.swistak.batterymonitor.monitoring.BatteryInfoService.CHAN_ID_MAIN)?.importance
                ?: 0) == 0
        ) return context.getString(R.string.settings_notification_disabled)
    }
    if (descriptor.preference?.isEnabled == false || descriptor.preference?.isVisible == false) return context.getString(
        R.string.settings_dependency_disabled
    )
    return null
}

@Composable
private fun NotificationPreview(screen: PreferenceScreen) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.settings_notification_preview),
                style = MaterialTheme.typography.labelLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    painterResource(R.drawable.ui_battery),
                    null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        stringResource(R.string.app_full_name) + " · 72%",
                        fontWeight = FontWeight.SemiBold
                    )
                    listOf("top_line", "bottom_line").forEach { key ->
                        screen.findPreference<ListPreference>(key)?.entry?.let { Text(it.toString()) }
                    }
                }
            }
            Text(
                stringResource(R.string.settings_notification_preview_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
