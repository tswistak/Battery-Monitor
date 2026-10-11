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
package codes.swistak.batterymonitor.ui.widgets

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.ui.components.NumericInputResult
import codes.swistak.batterymonitor.ui.components.parseLocalizedInt
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import codes.swistak.batterymonitor.ui.theme.Brightness
import codes.swistak.batterymonitor.ui.theme.ColorSource
import codes.swistak.batterymonitor.widgets.WidgetConfiguration
import codes.swistak.batterymonitor.widgets.WidgetConfigurationStore
import codes.swistak.batterymonitor.widgets.WidgetFamily
import codes.swistak.batterymonitor.widgets.WidgetMetric
import codes.swistak.batterymonitor.widgets.WidgetRenderer
import codes.swistak.batterymonitor.widgets.WidgetStatusExtra
import codes.swistak.batterymonitor.widgets.WidgetTapAction
import codes.swistak.batterymonitor.widgets.WidgetUpdateDispatcher
import codes.swistak.batterymonitor.widgets.WidgetVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class WidgetConfigurationActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val providerInfo = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        val family = providerInfo?.provider?.let { provider ->
            WidgetFamily.entries.firstOrNull {
                provider == ComponentName(
                    packageName, it.providerName
                )
            }
        }
        if (widgetId <= 0 || providerInfo == null || family == null || savedInstanceState != null && savedInstanceState.getInt(
                STATE_WIDGET_ID, widgetId
            ) != widgetId
        ) {
            Toast.makeText(this, R.string.widget_invalid_instance, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val store = WidgetConfigurationStore(this)
        val initial = store.load(widgetId, family)
        enableEdgeToEdge()
        setContent {
            AppBatteryTheme {
                WidgetConfigurationScreen(
                    widgetId,
                    family,
                    providerInfo,
                    initial,
                    onCancel = { finish() },
                    onSave = { configuration ->
                        if (familyForWidget(widgetId) != family) {
                            Toast.makeText(
                                this, R.string.widget_invalid_instance, Toast.LENGTH_SHORT
                            ).show()
                            finish()
                            false
                        } else if (!withContext(Dispatchers.IO) {
                                store.save(
                                    widgetId, configuration
                                )
                            }) {
                            false
                        } else {
                            suspendCancellableCoroutine<Unit> { continuation ->
                                WidgetUpdateDispatcher.refresh(this, intArrayOf(widgetId)) {
                                    if (continuation.isActive) continuation.resume(Unit)
                                }
                            }
                            setResult(
                                RESULT_OK,
                                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                            )
                            finish()
                            true
                        }
                    })
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_WIDGET_ID, widgetId)
        super.onSaveInstanceState(outState)
    }

    private fun familyForWidget(id: Int): WidgetFamily? {
        if (id <= 0) return null
        val provider =
            AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider ?: return null
        return WidgetFamily.entries.firstOrNull {
            provider == ComponentName(packageName, it.providerName)
        }
    }

    companion object {
        private const val STATE_WIDGET_ID = "configuration_widget_id"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigurationScreen(
    widgetId: Int,
    family: WidgetFamily,
    providerInfo: AppWidgetProviderInfo,
    initial: WidgetConfiguration,
    onCancel: () -> Unit,
    onSave: suspend (WidgetConfiguration) -> Boolean
) {
    var draftRecord by rememberSaveable(widgetId) { mutableStateOf(initial.encode()) }
    val draft = WidgetConfiguration.decode(draftRecord) ?: initial
    val update: (WidgetConfiguration) -> Unit = { draftRecord = it.encode() }
    var previewVersion by remember { mutableStateOf(0) }
    val context = LocalContext.current
    LaunchedEffect(family, draft.trendHours) {
        if (family == WidgetFamily.TREND) {
            suspendCancellableCoroutine<Unit> { continuation ->
                WidgetUpdateDispatcher.preparePreview(context, draft.trendHours) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            previewVersion++
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val numberFormat = remember(locale) {
        NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = false }
    }
    var opacityInput by rememberSaveable(widgetId) {
        mutableStateOf(numberFormat.format(draft.backgroundOpacityPercent))
    }
    val parsedOpacity = parseLocalizedInt(opacityInput, locale, 0, 100, 1)
    val opacity = (parsedOpacity as? NumericInputResult.Valid)?.value
    val opacityLabel = stringResource(R.string.widget_background_opacity)
    var saving by remember { mutableStateOf(false) }
    var saveFailed by rememberSaveable(widgetId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = saving) { }
    Scaffold(modifier = Modifier.imePadding(), topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.widget_configure_title)) },
            navigationIcon = {
                IconButton(onClick = onCancel, enabled = !saving) {
                    Icon(
                        painterResource(R.drawable.ui_close),
                        contentDescription = stringResource(R.string.cancel)
                    )
                }
            })
    }, bottomBar = {
        Surface(tonalElevation = 2.dp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(20.dp)
            ) {
                if (saveFailed) Text(
                    stringResource(R.string.widget_save_failed),
                    color = MaterialTheme.colorScheme.error
                )
                Button(
                    onClick = {
                        saving = true
                        saveFailed = false
                        scope.launch {
                            saveFailed = !runCatching { onSave(draft) }.getOrDefault(false)
                            saving = false
                        }
                    },
                    enabled = !saving && opacity != null && draft.isValid(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.charging_diagnostics_save)) }
            }
        }
    }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            WidgetPreview(family, providerInfo, draft, Color(0xFFE1E8EF), previewVersion)
            if (draft.backgroundOpacityPercent == 0) {
                WidgetPreview(family, providerInfo, draft, Color(0xFF17232D), previewVersion)
                Text(
                    stringResource(R.string.widget_transparent_warning),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                stringResource(R.string.widget_preview_note),
                style = MaterialTheme.typography.bodySmall
            )
            HorizontalDivider()
            when (family) {
                WidgetFamily.STATUS -> ChoiceField(
                    stringResource(R.string.widget_status_extra),
                    draft.statusExtra,
                    WidgetStatusExtra.entries.map {
                        it to stringResource(
                            when (it) {
                                WidgetStatusExtra.NONE -> R.string.settings_restore_none
                                WidgetStatusExtra.TEMPERATURE -> R.string.current_temperature
                                WidgetStatusExtra.REMAINING_CHARGE -> R.string.remaining_charge
                            }
                        )
                    }) { update(draft.copy(statusExtra = it)) }

                WidgetFamily.MEASUREMENTS -> MetricSelection(draft.metrics) {
                    update(
                        draft.copy(
                            metrics = it
                        )
                    )
                }

                WidgetFamily.TREND -> {
                    ChoiceField(
                        stringResource(R.string.history_range), draft.trendHours, listOf(
                            24 to stringResource(R.string.history_last_24h),
                            72 to stringResource(R.string.history_last_3d),
                            168 to stringResource(R.string.history_last_7d)
                        )
                    ) { update(draft.copy(trendHours = it)) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = draft.showYAxisLabels,
                                role = Role.Checkbox,
                                onValueChange = { update(draft.copy(showYAxisLabels = it)) }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = draft.showYAxisLabels, onCheckedChange = null)
                        Text(stringResource(R.string.widget_y_axis_labels))
                    }
                }
            }
            ChoiceField(
                stringResource(R.string.widget_presentation), draft.variant, listOf(
                    WidgetVariant.ADAPTIVE to stringResource(R.string.widget_presentation_adaptive),
                    WidgetVariant.COMPACT to stringResource(R.string.widget_presentation_compact)
                )
            ) { update(draft.copy(variant = it)) }
            if (family != WidgetFamily.TREND) ChoiceField(
                stringResource(R.string.pref_temperature_unit), draft.temperatureUnit, listOf(
                    null to stringResource(R.string.widget_follow_app),
                    WidgetConfiguration.TEMPERATURE_CELSIUS to stringResource(R.string.celsius),
                    WidgetConfiguration.TEMPERATURE_FAHRENHEIT to stringResource(R.string.fahrenheit)
                )
            ) { update(draft.copy(temperatureUnit = it)) }
            ChoiceField(
                stringResource(R.string.settings_brightness), draft.brightness, listOf(
                    null to stringResource(R.string.widget_follow_app),
                    Brightness.System to stringResource(R.string.settings_brightness_system),
                    Brightness.Light to stringResource(R.string.settings_brightness_light),
                    Brightness.Dark to stringResource(R.string.settings_brightness_dark),
                    Brightness.TrueBlack to stringResource(R.string.settings_brightness_true_black)
                )
            ) { update(draft.copy(brightness = it)) }
            ChoiceField(
                stringResource(R.string.settings_color_palette), draft.colorSource, listOf(
                    null to stringResource(R.string.widget_follow_app),
                    ColorSource.Dynamic to stringResource(R.string.settings_color_palette_material_you),
                    ColorSource.BatteryBlue to stringResource(R.string.settings_color_palette_battery_blue)
                )
            ) { update(draft.copy(colorSource = it)) }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) Text(
                stringResource(R.string.settings_color_palette_unavailable),
                style = MaterialTheme.typography.bodySmall
            )
            Column {
                OutlinedTextField(
                    value = opacityInput,
                    onValueChange = {
                        opacityInput = it
                        (parseLocalizedInt(
                            it, locale, 0, 100, 1
                        ) as? NumericInputResult.Valid)?.let { value ->
                            update(draft.copy(backgroundOpacityPercent = value.value))
                        }
                    },
                    label = { Text(opacityLabel) },
                    suffix = {
                        Text(
                            NumberFormat.getPercentInstance(locale).format(1)
                                .filterNot(Char::isDigit).trim()
                        )
                    },
                    isError = opacity == null,
                    supportingText = { if (opacity == null) Text(stringResource(R.string.widget_background_opacity_error)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number, imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Slider(
                    value = draft.backgroundOpacityPercent.toFloat(),
                    onValueChange = {
                        val value = it.roundToInt()
                        opacityInput = numberFormat.format(value)
                        update(draft.copy(backgroundOpacityPercent = value))
                    },
                    valueRange = 0f..100f,
                    steps = 99,
                    modifier = Modifier.semantics { contentDescription = opacityLabel })
            }
            ChoiceField(
                stringResource(R.string.widget_tap_action), draft.tapAction, listOf(
                    WidgetTapAction.CURRENT_STATE to stringResource(R.string.tab_current_info),
                    WidgetTapAction.HISTORY to stringResource(R.string.tab_history),
                    WidgetTapAction.MEASUREMENT to stringResource(R.string.widget_measurement_details)
                )
            ) { update(draft.copy(tapAction = it)) }
            if (draft.tapAction == WidgetTapAction.MEASUREMENT) ChoiceField(
                stringResource(R.string.widget_tap_measurement),
                draft.tapMetric,
                WidgetMetric.entries.map { it to metricLabel(it) }) { update(draft.copy(tapMetric = it)) }
        }
    }
}


@Composable
private fun <T> ChoiceField(
    title: String, selected: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text(options.first { it.first == selected }.second, modifier = Modifier.weight(1f))
                Icon(painterResource(R.drawable.ui_chev), contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { expanded = false; onSelect(value) },
                        modifier = Modifier.semantics { this.selected = value == selected })
                }
            }
        }
    }
}

@Composable
private fun MetricSelection(selected: List<WidgetMetric>, onChange: (List<WidgetMetric>) -> Unit) {
    Column {
        Text(
            stringResource(R.string.widget_metric_selection),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            stringResource(R.string.widget_metric_selection_hint),
            style = MaterialTheme.typography.bodySmall
        )
        val order = selected + WidgetMetric.entries.filter { it !in selected }
        order.forEach { metric ->
            val index = selected.indexOf(metric)
            val label = metricLabel(metric)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = index >= 0,
                            enabled = index < 0 || selected.size > 2,
                            role = Role.Checkbox,
                            onValueChange = { checked -> onChange(if (checked) selected + metric else selected - metric) }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = index >= 0,
                        enabled = index < 0 || selected.size > 2,
                        onCheckedChange = null
                    )
                    Text(label)
                }
                IconButton(onClick = {
                    onChange(
                        selected.toMutableList()
                            .apply { java.util.Collections.swap(this, index, index - 1) })
                }, enabled = index > 0) {
                    Icon(
                        painterResource(R.drawable.ui_up),
                        stringResource(R.string.settings_move_item_up, label)
                    )
                }
                IconButton(onClick = {
                    onChange(
                        selected.toMutableList()
                            .apply { java.util.Collections.swap(this, index, index + 1) })
                }, enabled = index >= 0 && index < selected.lastIndex) {
                    Icon(
                        painterResource(R.drawable.ui_down),
                        stringResource(R.string.settings_move_item_down, label)
                    )
                }
            }
        }
    }
}

@Composable
private fun metricLabel(metric: WidgetMetric): String = stringResource(
    when (metric) {
        WidgetMetric.TEMPERATURE -> R.string.current_temperature
        WidgetMetric.VOLTAGE -> R.string.current_voltage
        WidgetMetric.CURRENT -> R.string.pref_cat_battery_current_main
        WidgetMetric.REMAINING_CHARGE -> R.string.remaining_charge
    }
)

@Composable
private fun WidgetPreview(
    family: WidgetFamily,
    providerInfo: AppWidgetProviderInfo,
    configuration: WidgetConfiguration,
    wallpaper: Color,
    previewVersion: Int
) {
    val context = LocalContext.current
    val remoteViews = remember(context, family, configuration, previewVersion) {
        WidgetRenderer.preview(
            context, family, configuration
        )
    }
    val description = stringResource(R.string.widget_preview)
    Box(
        Modifier
            .fillMaxWidth()
            .background(wallpaper, MaterialTheme.shapes.medium)
            .padding(12.dp)
            .semantics { contentDescription = description }, contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { viewContext ->
                object : AppWidgetHostView(viewContext) {
                    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true
                    override fun onTouchEvent(event: MotionEvent): Boolean = true
                }.apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    setAppWidget(AppWidgetManager.INVALID_APPWIDGET_ID, providerInfo)
                    setPadding(0, 0, 0, 0)
                }
            },
            update = { it.updateAppWidget(remoteViews) },
            modifier = Modifier
                .widthIn(max = if (family == WidgetFamily.STATUS) 220.dp else 350.dp)
                .fillMaxWidth()
                .height(230.dp)
        )
    }
}
