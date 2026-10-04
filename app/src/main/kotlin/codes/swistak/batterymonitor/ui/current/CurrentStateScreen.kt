/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.current


import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.common.DurationFormatter
import codes.swistak.batterymonitor.common.NotificationSettingsNavigator
import codes.swistak.batterymonitor.data.LogsRepository
import codes.swistak.batterymonitor.diagnostics.BackgroundSettingsNavigator
import codes.swistak.batterymonitor.logs.HistoryChartModel
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.monitoring.BatteryCurrent
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringUiState
import codes.swistak.batterymonitor.settings.LongDurationFormat
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.temperatureUnit
import codes.swistak.batterymonitor.ui.components.ActionLabel
import codes.swistak.batterymonitor.ui.components.BatteryCellHero
import codes.swistak.batterymonitor.ui.components.CapabilityNotice
import codes.swistak.batterymonitor.ui.components.MeasurementChart
import codes.swistak.batterymonitor.ui.components.MetricDisplay
import codes.swistak.batterymonitor.ui.components.MetricGrid
import codes.swistak.batterymonitor.ui.components.historyValue
import codes.swistak.batterymonitor.ui.navigation.SectionOwner
import codes.swistak.batterymonitor.ui.theme.BatterySpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds

internal data class CurrentPreferences(
    val showRemainingCharge: Boolean,
    val preferAverageCurrent: Boolean,
    val currentRefreshMillis: Long,
    val currentMultiplier: Int,
    val fahrenheit: Boolean,
    val longDurationFormat: LongDurationFormat,
    val predictionMethod: String,
    val loggingEnabled: Boolean
)

private fun SharedPreferences.currentPreferences(defaultTemperatureUnit: String) =
    CurrentPreferences(
        showRemainingCharge = getBoolean(SettingsContract.KEY_SHOW_REMAINING_CHARGE, true),
        preferAverageCurrent = getBoolean(
            SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT, false
        ),
        currentRefreshMillis = (getString(
            SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL, "2"
        )?.toLongOrNull()?.coerceIn(1, 3600) ?: 2) * 1000,
        currentMultiplier = getString(
            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "1"
        )?.toIntOrNull() ?: 1,
        fahrenheit = temperatureUnit(defaultTemperatureUnit).convertToFahrenheit,
        longDurationFormat = LongDurationFormat.fromPreference(
            getString(SettingsContract.KEY_LONG_DURATION_FORMAT, null)
        ),
        predictionMethod = getString(SettingsContract.KEY_PREDICTION_TYPE, "-3") ?: "-3",
        loggingEnabled = getBoolean(SettingsContract.KEY_ENABLE_LOGGING, true)
    )


internal data class CurrentReading(
    val milliAmps: Double?, val observedAtMillis: Long, val average: Boolean = false
)

@Composable
internal fun CurrentStateRoute(
    monitoring: StateFlow<MonitoringUiState>,
    settings: SharedPreferences,
    onSection: (SectionOwner) -> Unit,
    onBatteryUsage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val defaultTemperatureUnit = stringResource(R.string.default_temperature_unit)
    val state by monitoring.collectAsStateWithLifecycle()
    var settingsVersion by remember { mutableIntStateOf(0) }
    var notificationsEnabled by remember {
        mutableStateOf(
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        )
    }
    var powerUnrestricted by remember {
        mutableStateOf(
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(
                context.packageName
            )
        )
    }
    DisposableEffect(settings) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> settingsVersion++ }
        settings.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        powerUnrestricted =
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(
                context.packageName
            )
    }
    val preferences = remember(settingsVersion, defaultTemperatureUnit) {
        settings.currentPreferences(defaultTemperatureUnit)
    }
    var refreshCurrent by remember { mutableIntStateOf(0) }
    val currentReading by produceState<CurrentReading?>(
        initialValue = null,
        preferences.preferAverageCurrent,
        preferences.currentRefreshMillis,
        preferences.currentMultiplier,
        refreshCurrent
    ) {
        BatteryCurrent.setContext(context)
        BatteryCurrent.setMultiplier(preferences.currentMultiplier)
        while (true) {
            value = withContext(Dispatchers.IO) {
                val average =
                    if (preferences.preferAverageCurrent) BatteryCurrent.avgCurrent else null
                CurrentReading(
                    average ?: BatteryCurrent.current, System.currentTimeMillis(), average != null
                )
            }
            delay(preferences.currentRefreshMillis.milliseconds)
        }
    }
    var showFullRange by rememberSaveable { mutableStateOf(false) }
    val model = currentStateModel(state, showFullRange, notificationsEnabled = notificationsEnabled)
    LaunchedEffect(state.snapshot?.status, state.snapshot?.configuredPrediction?.targetPercent) {
        showFullRange = false
    }
    val logs = remember(context.applicationContext) { LogsRepository(context.applicationContext) }
    val trend by produceState<HistoryChartModel?>(
        null,
        preferences.loggingEnabled,
        state.snapshot?.levelPercent,
        state.snapshot?.status,
        state.snapshot?.plugged
    ) {
        if (!preferences.loggingEnabled) {
            value = null; return@produceState
        }
        value = try {
            logs.chart(HistoryRangeState.lastHours(24))
        } catch (exception: kotlinx.coroutines.CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
    }
    CurrentStateScreen(
        model = model,
        preferences = preferences,
        currentReading = currentReading,
        onToggleTarget = { showFullRange = !showFullRange },
        onSection = onSection,
        onBatteryUsage = onBatteryUsage,
        onRefreshCurrent = { refreshCurrent++ },
        powerOptimized = !powerUnrestricted,
        trend = trend,
        onNotificationSettings = { NotificationSettingsNavigator.openNotifications(context) },
        onPowerSettings = {
            if (!BackgroundSettingsNavigator.openBatteryOptimization(context)) {
                android.widget.Toast.makeText(
                    context,
                    R.string.advanced_value_not_available,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        },
        modifier = modifier
    )
}

private data class MetricDetail(
    val display: MetricDisplay,
    val unit: String,
    val source: String,
    val observedAtMillis: Long?,
    val missingReason: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CurrentStateScreen(
    model: CurrentStateModel,
    preferences: CurrentPreferences,
    currentReading: CurrentReading?,
    onToggleTarget: () -> Unit,
    onSection: (SectionOwner) -> Unit,
    onBatteryUsage: () -> Unit,
    onRefreshCurrent: () -> Unit = {},
    powerOptimized: Boolean = false,
    onNotificationSettings: () -> Unit = {},
    onPowerSettings: () -> Unit = {},
    trend: HistoryChartModel? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = context.resources
    val snapshot = model.snapshot
    val prediction = model.prediction
    val stale =
        model.condition == CurrentCondition.STALE || model.condition == CurrentCondition.DISABLED
    val status = when (model.condition) {
        CurrentCondition.WAITING -> stringResource(R.string.current_waiting)
        CurrentCondition.DISABLED -> stringResource(R.string.current_monitor_disabled)
        CurrentCondition.STALE -> stringResource(R.string.current_stale)
        CurrentCondition.FULL -> stringResource(R.string.status_fully_charged)
        CurrentCondition.PAUSED -> stringResource(R.string.current_paused)
        CurrentCondition.TARGET_REACHED -> stringResource(
            R.string.current_target_reached, prediction?.targetPercent ?: 100
        )

        CurrentCondition.BELOW_TARGET -> stringResource(
            R.string.current_below_target, prediction?.targetPercent ?: 0
        )

        CurrentCondition.LOW -> snapshot?.let { DisplayStrings.statuses.getOrNull(it.status) }
            ?: stringResource(R.string.current_low)

        CurrentCondition.INSUFFICIENT -> snapshot?.let { DisplayStrings.statuses.getOrNull(it.status) }
            ?: stringResource(R.string.current_waiting)

        CurrentCondition.UNKNOWN -> snapshot?.let { DisplayStrings.statuses.getOrNull(it.status) }
            ?: stringResource(R.string.status_unknown)

        CurrentCondition.CHARGING -> stringResource(R.string.status_charging)
        CurrentCondition.DISCHARGING -> stringResource(R.string.status_discharging)
    }
    val plug = snapshot?.takeIf { it.plugged != BatteryInfo.PLUGGED_UNPLUGGED }
        ?.let { DisplayStrings.pluggeds.getOrNull(it.plugged) }?.trim(' ', '(', ')')
    val remainingCharge =
        if (preferences.showRemainingCharge && snapshot?.remainingChargeMicroampHours != null) {
            stringResource(
                R.string.current_remaining_charge,
                DisplayStrings.formatChargeCompact(snapshot.remainingChargeMicroampHours)
            )
        } else ""
    val estimate = when {
        snapshot == null || stale -> "—"
        snapshot.status == BatteryInfo.STATUS_FULLY_CHARGED -> stringResource(R.string.status_fully_charged)
        prediction?.targetReached == true && prediction.direction == BatteryInfo.Prediction.UNTIL_DRAINED -> stringResource(
            R.string.current_below_target, prediction.targetPercent
        )

        prediction?.targetReached == true -> stringResource(
            R.string.current_target_reached, prediction.targetPercent
        )

        prediction?.direction == BatteryInfo.Prediction.NONE || prediction == null -> stringResource(
            R.string.current_no_prediction
        )

        else -> "≈" + DurationFormatter.formatShort(
            resources,
            prediction.days * 1440 + prediction.hours * 60 + prediction.minutes,
            preferences.longDurationFormat
        )
    }
    val target = when {
        prediction == null -> ""
        prediction.targetReached -> stringResource(
            R.string.current_prediction_target, prediction.targetPercent
        )

        prediction.direction == BatteryInfo.Prediction.NONE -> ""
        else -> stringResource(R.string.activity_until_target, prediction.targetPercent)
    }
    val method = when (preferences.predictionMethod) {
        "-1" -> stringResource(R.string.predictor_since_status_change)
        "-2" -> stringResource(R.string.predictor_long_term_ave)
        "-3" -> stringResource(R.string.predictor_conservative)
        else -> stringResource(R.string.predictor_sensitive)
    }
    val snapshotTime = snapshot?.observedAtMillis?.takeIf { it > 0 }
    val snapshotSource = snapshot?.source ?: stringResource(R.string.advanced_value_not_available)
    val unavailable = stringResource(R.string.current_unavailable)
    val power = BatteryCurrent.powerWatts(snapshot?.voltageMillivolts, currentReading?.milliAmps)
    val metrics = listOf(
        MetricDetail(
            MetricDisplay(stringResource(R.string.current_temperature), snapshot?.let {
                DisplayStrings.formatTemp(it.temperatureTenthsCelsius, preferences.fahrenheit)
            } ?: unavailable),
            if (preferences.fahrenheit) "°F" else "°C",
            snapshotSource,
            snapshotTime),
        MetricDetail(
            MetricDisplay(
                stringResource(R.string.current_voltage),
                snapshot?.voltageMillivolts?.let {
                    DisplayStrings.formatVoltage(it)
                } ?: unavailable),
            "V",
            snapshotSource,
            snapshotTime,
            if (snapshot?.voltageMillivolts == null) unavailable else null),
        MetricDetail(
            MetricDisplay(
                stringResource(R.string.pref_cat_battery_current_main), when {
                    currentReading?.milliAmps == null -> unavailable
                    else -> (if (currentReading.milliAmps > 0) "+" else "") + BatteryCurrent.formatMilliAmps(
                        currentReading.milliAmps, configuration.locales[0]
                    ) + " mA"
                }
            ),
            "mA",
            stringResource(R.string.current_current_source) + " · " + stringResource(if (currentReading?.average == true) R.string.advanced_field_current_average else R.string.advanced_field_current_now),
            currentReading?.observedAtMillis,
            when {
                currentReading?.milliAmps == null -> stringResource(R.string.current_current_unavailable)
                else -> null
            }
        )) + listOfNotNull(power?.let {
        MetricDetail(
            MetricDisplay(
                stringResource(R.string.battery_power),
                historyValue(it, HistoryMetric.POWER, false, configuration.locales[0])
            ),
            "W",
            snapshotSource + " · " + stringResource(
                if (currentReading?.average == true) R.string.advanced_field_current_average
                else R.string.advanced_field_current_now
            ),
            currentReading?.observedAtMillis,
            stringResource(R.string.battery_power_explanation)
        )
    }) + MetricDetail(
        MetricDisplay(
            stringResource(R.string.current_android_health),
            snapshot?.let {
                DisplayStrings.healths.getOrNull(it.health)
            } ?: unavailable),
        stringResource(R.string.current_status_unit),
        snapshotSource,
        snapshotTime,
        stringResource(R.string.current_health_explanation))
    var selectedMetric by remember { mutableStateOf<Int?>(null) }
    var showPredictionDetails by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier) {
        val singleColumn = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.6f
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(BatterySpacing.content),
            verticalArrangement = Arrangement.spacedBy(BatterySpacing.normal)
        ) {
            item {
                Text(
                    when (model.condition) {
                        CurrentCondition.STALE -> stringResource(
                            R.string.current_last_reading, formatTimestamp(snapshotTime)
                        )

                        CurrentCondition.DISABLED -> stringResource(R.string.current_monitor_disabled)
                        CurrentCondition.WAITING -> stringResource(R.string.current_waiting)
                        else -> stringResource(
                            R.string.current_monitor_data, formatTimestamp(snapshotTime)
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (model.notificationsDisabled || powerOptimized) item {
                CapabilityNotice(
                    title = stringResource(
                        when {
                            model.notificationsDisabled && powerOptimized -> R.string.current_monitor_attention
                            model.notificationsDisabled -> R.string.current_notifications_off
                            else -> R.string.current_power_optimized
                        }
                    ), message = listOfNotNull(
                        stringResource(R.string.current_notifications_off_body).takeIf { model.notificationsDisabled },
                        stringResource(R.string.current_power_optimized_body).takeIf { powerOptimized }).joinToString(
                        "\n"
                    )
                )
                if (model.notificationsDisabled) TextButton(onClick = onNotificationSettings) {
                    ActionLabel(
                        stringResource(R.string.diagnostics_notifications), R.drawable.ui_bell
                    )
                }
                if (powerOptimized) TextButton(onClick = onPowerSettings) {
                    ActionLabel(
                        stringResource(R.string.diagnostics_battery_optimization),
                        R.drawable.ui_battery
                    )
                }
            }
            if (model.condition == CurrentCondition.STALE || model.condition == CurrentCondition.DISABLED) item {
                CapabilityNotice(status, stringResource(R.string.current_monitor_action))
                TextButton(onClick = { onSection(SectionOwner.DIAGNOSTICS) }) {
                    Text(
                        stringResource(
                            R.string.nav_diagnostics
                        )
                    )
                }
            }
            item {
                BatteryCellHero(
                    title = stringResource(R.string.nav_battery_group),
                    level = snapshot?.levelPercent,
                    status = status,
                    detail = listOfNotNull(
                        plug, remainingCharge.takeIf(String::isNotBlank)
                    ).joinToString(" · "),
                    spokenSummary = listOfNotNull(
                        snapshot?.levelPercent?.let { "$it%" },
                        status,
                        plug,
                        remainingCharge,
                        prediction?.let {
                            stringResource(
                                R.string.current_prediction_target, it.targetPercent
                            )
                        }).joinToString(", "),
                    modifier = Modifier.fillMaxWidth(),
                    targetPercent = prediction?.targetPercent,
                    charging = snapshot?.status == BatteryInfo.STATUS_CHARGING
                )
            }
            if (model.condition == CurrentCondition.LOW) item {
                CapabilityNotice(
                    stringResource(R.string.current_low), stringResource(R.string.current_low_body)
                )
            }
            item {
                OutlinedCard(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(horizontal = 17.dp, vertical = 13.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.time_remaining),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(onClick = { showPredictionDetails = true }) {
                                Icon(
                                    painterResource(R.drawable.ui_info),
                                    stringResource(R.string.time_remaining),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            buildAnnotatedString {
                                append(estimate)
                                if (target.isNotBlank()) withStyle(MaterialTheme.typography.bodyLarge.toSpanStyle()) {
                                    append(" $target")
                                }
                            }, style = MaterialTheme.typography.headlineMedium
                        )
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                method,
                                Modifier.alignByBaseline(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (model.hasAlternative) TextButton(
                                onClick = onToggleTarget, modifier = Modifier.alignByBaseline()
                            ) {
                                Text(
                                    stringResource(
                                        R.string.current_show_to_target,
                                        if (model.showingFullRange) snapshot!!.configuredPrediction.targetPercent
                                        else snapshot!!.fullRangePrediction.targetPercent
                                    )
                                )
                            }
                        }
                    }
                }
            }
            if (snapshot != null && snapshot.lastStatusTimeMillis > 0 && snapshot.lastPercent >= 0) item {
                val duration =
                    ((System.currentTimeMillis() - snapshot.lastStatusTimeMillis) / 60000).coerceAtLeast(
                        0
                    ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                Text(
                    stringResource(
                        when (snapshot.lastStatus) {
                            BatteryInfo.STATUS_UNPLUGGED, BatteryInfo.STATUS_DISCHARGING -> R.string.current_since_unplugged
                            BatteryInfo.STATUS_CHARGING -> R.string.current_since_connected
                            else -> R.string.current_since_change
                        },
                        snapshot.lastPercent,
                        snapshot.levelPercent,
                        DurationFormatter.formatShort(
                            resources, duration, preferences.longDurationFormat
                        )
                    ), style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                Text(
                    stringResource(R.string.current_measurements),
                    style = MaterialTheme.typography.titleLarge
                )
            }
            item {
                MetricGrid(
                    metrics.mapIndexed { index, metric ->
                        metric.display.copy(
                            icon = listOfNotNull(
                                R.drawable.ui_temp,
                                R.drawable.ui_voltage,
                                R.drawable.ui_current,
                                R.drawable.ui_bolt.takeIf { power != null },
                                R.drawable.ui_heart
                            )[index]
                        )
                    },
                    columns = if (singleColumn) 1 else 2,
                    onMetricClick = { selectedMetric = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                if (preferences.loggingEnabled && trend != null && trend.count > 0) {
                    OutlinedCard(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(horizontal = 15.dp, vertical = 9.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.history_level_24h),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(onClick = { onSection(SectionOwner.HISTORY) }) {
                                    Text(
                                        stringResource(R.string.nav_history)
                                    )
                                }
                            }
                            MeasurementChart(
                                trend,
                                HistoryMetric.LEVEL,
                                preferences.fahrenheit,
                                selectedId = null,
                                onSelect = {},
                                compact = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else {
                    TextButton(onClick = { onSection(SectionOwner.HISTORY) }) {
                        Text(
                            if (preferences.loggingEnabled) stringResource(R.string.nav_history)
                            else stringResource(R.string.current_history_off)
                        )
                    }
                }
            }
            item {
                TextButton(onClick = { onSection(SectionOwner.ALARMS) }) {
                    ActionLabel(
                        stringResource(R.string.nav_alarms), R.drawable.ui_bell
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = onBatteryUsage,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    ActionLabel(
                        stringResource(R.string.current_system_usage), R.drawable.ui_external
                    )
                }
            }
        }
    }
    selectedMetric?.let { index ->
        val metric = metrics.getOrNull(index) ?: return@let
        ModalBottomSheet(onDismissRequest = { selectedMetric = null }) {
            Column(
                Modifier.padding(BatterySpacing.content),
                verticalArrangement = Arrangement.spacedBy(BatterySpacing.sm)
            ) {
                Text(metric.display.label, style = MaterialTheme.typography.titleLarge)
                Text(metric.display.value, style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.current_detail_unit, metric.unit))
                Text(stringResource(R.string.current_detail_source, metric.source))
                Text(
                    stringResource(
                        R.string.current_detail_time, formatTimestamp(metric.observedAtMillis)
                    )
                )
                metric.missingReason?.let { Text(it) }
                if (stale) Text(stringResource(R.string.current_stale))
                if (index == 2) TextButton(onClick = onRefreshCurrent) {
                    ActionLabel(
                        stringResource(R.string.advanced_action_refresh), R.drawable.ui_refresh
                    )
                }
                TextButton(onClick = {
                    selectedMetric = null; onSection(SectionOwner.DIAGNOSTICS)
                }) {
                    ActionLabel(stringResource(R.string.nav_diagnostics), R.drawable.ui_current)
                }
            }
        }
    }
    if (showPredictionDetails) ModalBottomSheet(onDismissRequest = {
        showPredictionDetails = false
    }) {
        Column(
            Modifier.padding(BatterySpacing.content),
            verticalArrangement = Arrangement.spacedBy(BatterySpacing.sm)
        ) {
            Text(
                stringResource(R.string.time_remaining), style = MaterialTheme.typography.titleLarge
            )
            Text(stringResource(R.string.current_prediction_method, method))
            Text(stringResource(R.string.current_prediction_target, prediction?.targetPercent ?: 0))
            Text(
                stringResource(
                    R.string.current_prediction_snapshot_time, formatTimestamp(snapshotTime)
                )
            )
            Text(stringResource(R.string.current_prediction_basis_unavailable))
            Text(stringResource(R.string.current_prediction_explanation))
        }
    }
}


@Composable
private fun formatTimestamp(value: Long?): String {
    val context = LocalContext.current
    return if (value == null || value <= 0) stringResource(R.string.status_unknown)
    else android.text.format.DateFormat.getDateFormat(context)
        .format(Date(value)) + " " + DisplayStrings.formatTime(context, Date(value))
}
