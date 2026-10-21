/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.ui.history

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.doOnPreDraw
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.DetailedHistoryChartModel
import codes.swistak.batterymonitor.logs.DetailedHistoryTimeAxis
import codes.swistak.batterymonitor.logs.HistoryEvent
import codes.swistak.batterymonitor.logs.HistoryKey
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.detailedHistoryCoordinateRange
import codes.swistak.batterymonitor.logs.historyExclusiveEnd
import codes.swistak.batterymonitor.logs.historyObservationValue
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.temperatureUnit
import codes.swistak.batterymonitor.ui.components.HistoryChartScale
import codes.swistak.batterymonitor.ui.components.historyAxisFormatter
import codes.swistak.batterymonitor.ui.components.historyUnit
import codes.swistak.batterymonitor.ui.components.historyValue
import codes.swistak.batterymonitor.ui.theme.BatterySpacing
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.compose.LineChart
import com.github.mikephil.charting.compose.rememberChartState
import com.github.mikephil.charting.compose.rememberTypeface
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.jobs.MoveViewJob
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.utils.MPPointD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.github.mikephil.charting.charts.LineChart as LineChartView

@Composable
internal fun FullscreenChartScreen(
    state: FullscreenChartUiState,
    onMetric: (HistoryMetric) -> Unit,
    onComparison: (HistoryMetric?) -> Unit,
    onRange: (HistoryRangeState, String) -> Unit,
    onViewport: (HistoryRangeState, Int) -> Unit,
    onScale: (ChartScaleMode) -> Unit,
    onStyle: (ChartDrawingStyle) -> Unit,
    onValueScale: (ChartValueScale) -> Unit,
    onSelect: (HistoryKey?) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = rememberHistoryTimeZone()
    val settings = remember(context) {
        context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
    }
    var settingsVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(settings) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> settingsVersion++ }
        settings.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val defaultTemperature = stringResource(R.string.default_temperature_unit)
    val fahrenheit = remember(settingsVersion, defaultTemperature) {
        settings.temperatureUnit(defaultTemperature).convertToFahrenheit
    }
    val currentMultiplier = remember(settingsVersion) {
        settings.getString(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "1")?.toIntOrNull()
            ?.takeIf { it == -1 || it == 1 } ?: 1
    }
    val formatter = remember(locale, zone) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(zone)
    }
    val axisFormatter = remember(locale, zone, state.viewport) {
        historyAxisFormatter(state.viewport.end - state.viewport.start, locale, zone)
    }
    val coordinateRange = remember(state.range, state.viewport) {
        detailedHistoryCoordinateRange(
            state.range, state.viewport
        )
    }
    val timeAxis = remember(coordinateRange) { DetailedHistoryTimeAxis(coordinateRange) }
    val chartState = rememberChartState()
    var chartView by remember { mutableStateOf<LineChartView?>(null) }
    var viewportReady by remember { mutableStateOf(false) }
    var appliedCoordinates by remember { mutableStateOf<HistoryRangeState?>(null) }
    var appliedViewport by remember { mutableStateOf<HistoryRangeState?>(null) }
    var viewportRevision by remember { mutableIntStateOf(0) }
    var appliedRevision by remember { mutableIntStateOf(-1) }
    var appliedLineData by remember { mutableStateOf<LineData?>(null) }
    var appliedYBounds by remember { mutableStateOf<Map<HistoryMetric, HistoryChartScale>?>(null) }
    var pendingTopY by remember { mutableStateOf<Float?>(null) }
    var chartWidth by remember { mutableIntStateOf(1) }
    var metricMenu by remember { mutableStateOf(false) }
    var comparisonMenu by remember { mutableStateOf(false) }
    var scaleMenu by remember { mutableStateOf(false) }
    var settingsMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var eventsMenu by remember { mutableStateOf(false) }
    val currentViewport by rememberUpdatedState(state.viewport)
    val reportViewport by rememberUpdatedState(onViewport)
    val selectPoint by rememberUpdatedState(onSelect)
    val metrics = listOfNotNull(state.metric, state.comparison)
    val models =
        state.charts.filter { (metric, chart) -> metric in metrics && chart.range == state.range }
    val model = models[state.metric]
    val points = remember(models) {
        models.values.flatMap { it.points }.filter { it.value != null }.distinctBy { it.key }
            .sortedWith(compareBy({ it.key.time }, { it.key.id }))
    }
    val selectedIndex = remember(points, state.selectedKey) {
        points.indexOfFirst { it.key == state.selectedKey }
    }
    val previousLabel = stringResource(R.string.history_previous_point)
    val nextLabel = stringResource(R.string.history_next_point)
    val panPreviousLabel = stringResource(R.string.history_chart_pan_left)
    val panNextLabel = stringResource(R.string.history_chart_pan_right)
    val currentScaleLabel = scaleLabel(state.scaleMode)
    val compactScaleLabel = stringResource(
        when (state.scaleMode) {
            ChartScaleMode.AUTOMATIC -> R.string.history_chart_y_auto
            ChartScaleMode.FIXED -> R.string.history_chart_y_fixed
            ChartScaleMode.MANUAL -> R.string.history_chart_y_manual
        }
    )
    val title = if (state.metric == HistoryMetric.LEVEL) {
        stringResource(R.string.history_battery_level)
    } else metricLabel(state.metric)
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    val typeface = rememberTypeface(
        typography.labelSmall.fontFamily ?: FontFamily.Default,
        typography.labelSmall.fontWeight ?: FontWeight.Normal
    )
    val chartTextSize = typography.labelSmall.fontSize.value * LocalDensity.current.fontScale
    val xLabelWidth = with(LocalDensity.current) { 200.dp.toPx() * fontScale }
    val logAvailable =
        fullscreenLogAvailable(metrics, state.boundsByMetric, fahrenheit, currentMultiplier, models)
    val valueScale = if (state.valueScale == ChartValueScale.LOGARITHMIC && !logAvailable) {
        ChartValueScale.LINEAR
    } else state.valueScale
    LaunchedEffect(logAvailable, state.valueScale, state.loading, state.boundsByMetric) {
        if (!logAvailable && state.valueScale == ChartValueScale.LOGARITHMIC && !state.loading && metrics.all { it in state.boundsByMetric }) {
            onValueScale(ChartValueScale.LINEAR)
        }
    }
    val yBounds = remember(
        models,
        state.boundsByMetric,
        state.scaleMode,
        metrics,
        fahrenheit,
        currentMultiplier,
        valueScale
    ) {
        metrics.associateWith { metric ->
            val chart = models[metric]
            val guardValues = if (chart?.min == null) chart?.points?.mapNotNull { it.value }
                .orEmpty() else emptyList()
            fullscreenYBounds(
                metric,
                state.scaleMode,
                chart?.min ?: guardValues.minOrNull(),
                chart?.max ?: guardValues.maxOrNull(),
                state.boundsByMetric[metric]?.min,
                state.boundsByMetric[metric]?.max,
                fahrenheit,
                currentMultiplier,
                valueScale
            )
        }
    }
    val prepared by produceState<PreparedChartData?>(
        null,
        models,
        fahrenheit,
        currentMultiplier,
        valueScale,
        state.style,
        metrics,
        chartWidth,
        coordinateRange
    ) {
        value = models.takeIf { it.isNotEmpty() }?.let { charts ->
            withContext(Dispatchers.Default) {
                PreparedChartData(
                    coordinateRange, metrics, state.style, valueScale, fullscreenLineData(
                        charts,
                        state.metric,
                        state.comparison,
                        coordinateRange,
                        state.style,
                        valueScale,
                        fahrenheit,
                        currentMultiplier,
                        chartWidth
                    )
                )
            }
        }
    }
    val lineData = prepared?.takeIf {
        it.coordinates == coordinateRange && it.metrics == metrics && it.style == state.style && it.valueScale == valueScale
    }?.data

    fun reading(metric: HistoryMetric, key: HistoryKey): Double? {
        val record = state.selectedRecord?.takeIf { it.id == key.id && it.record.time == key.time }
        return if (record != null) historyObservationValue(metric, record.record)
        else models[metric]?.points?.firstOrNull { it.key == key }?.value
    }

    val chartDescription = stringResource(
        R.string.history_chart_description_fullscreen,
        title,
        formatter.format(Instant.ofEpochMilli(state.viewport.start)),
        formatter.format(Instant.ofEpochMilli(state.viewport.end - 1))
    )

    fun moveViewport(viewport: HistoryRangeState) {
        viewportReady = false
        viewportRevision++
        onViewport(viewport, chartWidth)
    }

    fun fitViewport() {
        viewportReady = false
        pendingTopY = null
        viewportRevision++
        chartState.fitScreen()
        onViewport(state.range, chartWidth)
    }

    fun panWindow(previous: Boolean): Boolean {
        if (previous && state.viewport.start == state.range.start) return false
        if (!previous && state.viewport.end == state.range.end) return false
        val target = if (previous) state.viewport.start - 1 else state.viewport.end
        moveViewport(fullscreenViewportAround(state.range, state.viewport, target))
        return true
    }

    fun stepSelection(previous: Boolean): Boolean {
        val index = if (selectedIndex < 0) {
            if (previous) points.lastIndex else 0
        } else selectedIndex + if (previous) -1 else 1
        val point = points.getOrNull(index) ?: return panWindow(previous)
        onSelect(point.key)
        if (point.key.time < state.viewport.start || point.key.time >= state.viewport.end) {
            moveViewport(fullscreenViewportAround(state.range, state.viewport, point.key.time))
        }
        return true
    }

    LaunchedEffect(chartWidth) {
        if (chartWidth > 1) reportViewport(currentViewport, chartWidth)
    }
    LaunchedEffect(chartState, timeAxis, chartWidth) {
        snapshotFlow {
            if (viewportReady) chartState.lowestVisibleX to chartState.highestVisibleX else null
        }.filterNotNull().distinctUntilChanged().collect { (first, last) ->
            if (last > first && first.isFinite() && last.isFinite()) {
                val start = timeAxis.time(first).coerceIn(state.range.start, state.range.end - 1)
                val end = historyExclusiveEnd(timeAxis.time(last)).coerceIn(
                    start + 1, state.range.end
                )
                val precision = maxOf(
                    Math.ulp(timeAxis.x(currentViewport.start)),
                    Math.ulp(timeAxis.x(currentViewport.end))
                ).toDouble()
                val tolerance = maxOf(2L, (precision * 2000).toLong())
                if (kotlin.math.abs(start - currentViewport.start) > tolerance || kotlin.math.abs(
                        end - currentViewport.end
                    ) > tolerance
                ) {
                    reportViewport(HistoryRangeState(start, end), chartWidth)
                }
            }
        }
    }

    Scaffold(
        containerColor = colors.background, topBar = {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = BatterySpacing.xs)
            ) {
                val available = maxWidth.value / LocalDensity.current.fontScale
                val showZoom = available >= 800
                val showScale = available >= 640
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = BatterySpacing.touch),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BatterySpacing.xs)
                ) {
                    ChartIcon(R.drawable.ui_back, R.string.nav_up, true, onBack)
                    Box(Modifier.weight(1f)) {
                        ChartSelector(title, metricMenu, { metricMenu = true })
                        DropdownMenu(metricMenu, onDismissRequest = { metricMenu = false }) {
                            HistoryMetric.entries.forEach { metric ->
                                DropdownMenuItem(
                                    text = { Text(metricLabel(metric)) },
                                    modifier = Modifier.semantics {
                                        this.selected = metric == state.metric
                                    },
                                    onClick = {
                                        metricMenu = false
                                        onMetric(metric)
                                    })
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        ChartSelector(
                            state.comparison?.let { metricLabel(it) }
                                ?: stringResource(R.string.history_chart_compare),
                            comparisonMenu,
                            { comparisonMenu = true },
                            leadingPlus = state.comparison == null
                        )
                        DropdownMenu(
                            comparisonMenu, onDismissRequest = { comparisonMenu = false }) {
                            if (state.comparison != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.history_chart_remove_comparison)) },
                                    onClick = {
                                        comparisonMenu = false
                                        onComparison(null)
                                    })
                                HorizontalDivider()
                            }
                            HistoryMetric.entries.filter { it != state.metric }.forEach { metric ->
                                DropdownMenuItem(
                                    text = { Text(metricLabel(metric)) },
                                    modifier = Modifier.semantics {
                                        this.selected = metric == state.comparison
                                    },
                                    onClick = {
                                        comparisonMenu = false
                                        onComparison(metric)
                                    })
                            }
                        }
                    }
                    Box(Modifier.weight(1.2f)) {
                        HistoryRangeControl(
                            HistoryUiState(range = state.range, rangeLabel = state.rangeLabel),
                            { range, label ->
                                viewportReady = false
                                pendingTopY = null
                                viewportRevision++
                                chartState.fitScreen()
                                onRange(range, label)
                            },
                            zone,
                            compact = true
                        )
                    }
                    if (showZoom) {
                        ChartIcon(
                            R.drawable.ui_plus, R.string.history_chart_zoom_in, lineData != null
                        ) {
                            chartView?.zoomToCenter(1.4f, 1f)
                        }
                        ChartIcon(
                            R.drawable.ui_minus, R.string.history_chart_zoom_out, lineData != null
                        ) {
                            chartView?.zoomToCenter(0.7f, 1f)
                        }
                        ChartIcon(
                            R.drawable.ui_refresh, R.string.history_chart_reset, lineData != null
                        ) {
                            fitViewport()
                        }
                    }
                    if (showScale) Box(Modifier.width(104.dp)) {
                        ChartSelector(
                            compactScaleLabel,
                            scaleMenu,
                            { scaleMenu = true },
                            description = currentScaleLabel
                        )
                        DropdownMenu(scaleMenu, onDismissRequest = { scaleMenu = false }) {
                            ChartScaleMode.entries.forEach { mode ->
                                ChartRadioItem(scaleLabel(mode), state.scaleMode == mode) {
                                    scaleMenu = false
                                    onScale(mode)
                                }
                            }
                        }
                    }
                    Box {
                        if (showScale) ChartIcon(
                            R.drawable.ui_settings, R.string.history_chart_settings, true
                        ) { settingsMenu = true }
                        DropdownMenu(settingsMenu, onDismissRequest = { settingsMenu = false }) {
                            Text(
                                stringResource(R.string.history_chart_display_style),
                                Modifier.padding(
                                    horizontal = BatterySpacing.normal, vertical = BatterySpacing.sm
                                ),
                                style = typography.labelMedium
                            )
                            ChartDrawingStyle.entries.forEach { style ->
                                ChartRadioItem(
                                    stringResource(if (style == ChartDrawingStyle.CONNECTED) R.string.history_chart_connected else R.string.history_chart_points),
                                    state.style == style
                                ) {
                                    settingsMenu = false
                                    onStyle(style)
                                }
                            }
                            HorizontalDivider()
                            Text(
                                stringResource(R.string.history_chart_value_scale),
                                Modifier.padding(
                                    horizontal = BatterySpacing.normal, vertical = BatterySpacing.sm
                                ),
                                style = typography.labelMedium
                            )
                            ChartValueScale.entries.forEach { scale ->
                                ChartRadioItem(
                                    stringResource(if (scale == ChartValueScale.LINEAR) R.string.history_chart_linear else R.string.history_chart_logarithmic),
                                    valueScale == scale,
                                    enabled = scale == ChartValueScale.LINEAR || logAvailable
                                ) {
                                    settingsMenu = false
                                    onValueScale(scale)
                                }
                            }
                            if (!logAvailable) Text(
                                stringResource(R.string.history_chart_log_unavailable),
                                Modifier
                                    .width(280.dp)
                                    .padding(
                                        horizontal = BatterySpacing.normal,
                                        vertical = BatterySpacing.sm
                                    ),
                                style = typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                    if (!showZoom) Box {
                        ChartIcon(
                            R.drawable.ui_more, R.string.history_chart_more, true
                        ) { overflowMenu = true }
                        DropdownMenu(
                            overflowMenu, onDismissRequest = { overflowMenu = false }) {
                            if (!showScale) DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_chart_settings)) },
                                onClick = {
                                    overflowMenu = false
                                    settingsMenu = true
                                })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_chart_zoom_in)) },
                                enabled = lineData != null,
                                onClick = {
                                    overflowMenu = false
                                    chartView?.zoomToCenter(1.4f, 1f)
                                })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_chart_zoom_out)) },
                                enabled = lineData != null,
                                onClick = {
                                    overflowMenu = false
                                    chartView?.zoomToCenter(0.7f, 1f)
                                })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.history_chart_reset)) },
                                enabled = lineData != null,
                                onClick = {
                                    overflowMenu = false
                                    fitViewport()
                                })
                            if (!showScale) {
                                HorizontalDivider()
                                Text(
                                    stringResource(R.string.history_chart_scale), Modifier.padding(
                                        horizontal = BatterySpacing.normal,
                                        vertical = BatterySpacing.sm
                                    ), style = typography.labelMedium
                                )
                                ChartScaleMode.entries.forEach { mode ->
                                    ChartRadioItem(scaleLabel(mode), state.scaleMode == mode) {
                                        overflowMenu = false
                                        onScale(mode)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
        ) {
            if (state.comparison != null) Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BatterySpacing.normal),
                horizontalArrangement = Arrangement.spacedBy(BatterySpacing.normal)
            ) {
                metrics.forEach { metric ->
                    val color = if (metric == state.metric) colors.primary else colors.tertiary
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BatterySpacing.xs)
                    ) {
                        Canvas(
                            Modifier
                                .width(20.dp)
                                .height(12.dp)
                        ) {
                            if (state.style == ChartDrawingStyle.POINTS) {
                                if (metric == state.metric) drawCircle(color, 3.dp.toPx())
                                else drawCircle(color, 3.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                            } else drawLine(
                                color,
                                androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                                androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
                                strokeWidth = 2.dp.toPx(),
                                pathEffect = if (metric == state.metric) null else PathEffect.dashPathEffect(
                                    floatArrayOf(5.dp.toPx(), 3.dp.toPx())
                                )
                            )
                        }
                        Text(
                            "${metricLabel(metric)} (${historyUnit(metric, fahrenheit)})",
                            style = typography.labelSmall,
                            color = color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(BatterySpacing.xs)
            ) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = BatterySpacing.sm)
            ) {
                if (lineData != null || chartView != null) LineChart(
                    data = lineData,
                    state = chartState,
                    contentDescription = chartDescription,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { chartWidth = it.width.coerceAtLeast(1) }
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(previousLabel) { stepSelection(true) },
                                CustomAccessibilityAction(nextLabel) { stepSelection(false) },
                                CustomAccessibilityAction(panPreviousLabel) { panWindow(true) },
                                CustomAccessibilityAction(panNextLabel) { panWindow(false) })
                        }
                        .onKeyEvent { event ->
                            event.type == KeyEventType.KeyDown && when (event.key) {
                                Key.DirectionLeft -> stepSelection(true)
                                Key.DirectionRight -> stepSelection(false)
                                Key.PageUp -> panWindow(true)
                                Key.PageDown -> panWindow(false)
                                else -> false
                            }
                        }
                        .focusable(),
                    marker = { entry, _ ->
                        val key = state.selectedKey ?: entry.data as? HistoryKey
                        if (key != null) {
                            MaterialTheme(
                                colorScheme = colors, shapes = shapes, typography = typography
                            ) {
                                Surface(
                                    color = colors.surfaceContainerHigh,
                                    contentColor = colors.onSurface,
                                    shape = shapes.small
                                ) {
                                    Column(
                                        Modifier.padding(
                                            horizontal = BatterySpacing.md,
                                            vertical = BatterySpacing.sm
                                        )
                                    ) {
                                        metrics.forEach { metric ->
                                            Text(
                                                "${metricLabel(metric)}: ${
                                                    historyValue(
                                                        reading(
                                                            metric, key
                                                        ),
                                                        metric,
                                                        fahrenheit,
                                                        locale,
                                                        currentMultiplier
                                                    )
                                                }",
                                                style = typography.labelLarge,
                                                color = if (metric == state.metric) colors.primary else colors.tertiary
                                            )
                                            Text(
                                                formatter.format(Instant.ofEpochMilli(key.time)),
                                                style = typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    setup = {
                        chartView = this
                        noDataText = ""
                        isNoDataIconEnabled = false
                        description.isEnabled = false
                        legend.isEnabled = false
                        axisRight.isEnabled = false
                        isDrawGridBackgroundEnabled = false
                        isPinchZoomEnabled = false
                        isDoubleTapToZoomEnabled = true
                        isScaleXEnabled = true
                        isHighlightPerDragEnabled = true
                        isKeepPositionOnRotation = true
                        xAxis.position = XAxis.XAxisPosition.BOTTOM
                        xAxis.isDrawGridLinesEnabled = false
                        xAxis.isDrawAxisLineEnabled = false
                        xAxis.isAvoidFirstLastClippingEnabled = true
                        axisLeft.isDrawAxisLineEnabled = false
                        axisLeft.labelCount = 4
                        minOffset = 8f
                    },
                    update = {
                        val restoreViewport =
                            appliedCoordinates != coordinateRange || appliedViewport != state.viewport || appliedRevision != viewportRevision || appliedLineData !== lineData || appliedYBounds != yBounds
                        if (restoreViewport && viewportReady && state.scaleMode == ChartScaleMode.MANUAL) {
                            pendingTopY = fullscreenChartTopY(this)
                        }
                        val preservedTopY = pendingTopY
                        if (restoreViewport) viewportReady = false
                        setBackgroundColor(colors.background.toArgb())
                        isDragXEnabled = true
                        isDragYEnabled = state.scaleMode == ChartScaleMode.MANUAL
                        isScaleYEnabled = state.scaleMode == ChartScaleMode.MANUAL
                        isAutoScaleMinMaxEnabled = false
                        onChartValueSelectedListener = object : OnChartValueSelectedListener {
                            override fun onValueSelected(e: Entry<*>, h: Highlight) {
                                if (viewportReady) (e.data as? HistoryKey)?.let(selectPoint)
                            }

                            override fun onNothingSelected() {
                                if (viewportReady) selectPoint(null)
                            }
                        }
                        xAxis.axisMinimum = 0f
                        xAxis.axisMaximum = timeAxis.x(coordinateRange.end).coerceAtLeast(0.001f)
                        xAxis.textColor = colors.onSurfaceVariant.toArgb()
                        xAxis.typeface = typeface
                        xAxis.textSize = chartTextSize
                        xAxis.labelCount = (chartWidth / xLabelWidth).toInt().coerceIn(2, 4)
                        xAxis.valueFormatter = IAxisValueFormatter { value, _ ->
                            axisFormatter.format(Instant.ofEpochMilli(timeAxis.time(value)))
                        }
                        axisRight.isEnabled = state.comparison != null
                        axisRight.isDrawGridLinesEnabled = false
                        metrics.forEach { metric ->
                            val axis = if (metric == state.metric) axisLeft else axisRight
                            val bounds = yBounds.getValue(metric)
                            axis.textColor =
                                (if (metric == state.metric) colors.primary else colors.tertiary).toArgb()
                            axis.gridColor = colors.outlineVariant.toArgb()
                            axis.typeface = typeface
                            axis.textSize = chartTextSize
                            axis.labelCount = 4
                            axis.isDrawAxisLineEnabled = false
                            axis.axisMinimum = bounds.minimum.toFloat()
                            axis.axisMaximum = bounds.maximum.toFloat()
                            axis.granularity = ((bounds.maximum - bounds.minimum) / 8).toFloat()
                                .coerceAtLeast(0.0001f)
                            axis.valueFormatter = IAxisValueFormatter { value, _ ->
                                historyValue(
                                    fullscreenRawValue(
                                        value.toDouble(),
                                        metric,
                                        fahrenheit,
                                        currentMultiplier,
                                        valueScale
                                    ), metric, fahrenheit, locale, currentMultiplier
                                )
                            }
                        }
                        lineData?.dataSets?.forEach { set ->
                            (set as? LineDataSet<*>)?.apply {
                                val primary = label == state.metric.name
                                isVisible = metrics.any { it.name == label }
                                axisDependency =
                                    if (primary) YAxis.AxisDependency.LEFT else YAxis.AxisDependency.RIGHT
                                val seriesColor =
                                    (if (primary) colors.primary else colors.tertiary).toArgb()
                                color =
                                    if (state.style == ChartDrawingStyle.POINTS) android.graphics.Color.TRANSPARENT else seriesColor
                                circleColor = seriesColor
                                circleHoleColor = colors.background.toArgb()
                                highlightColor = seriesColor
                            }
                        }
                        xAxis.removeAllLimitLines()
                        val eventColumns = mutableSetOf<Pair<Int, Int>>()
                        model?.events?.forEach { event ->
                            val column =
                                ((event.key.time - state.viewport.start).toDouble() / (state.viewport.end - state.viewport.start) * chartWidth / 6).toInt()
                            if (eventColumns.add(column to event.code)) {
                                xAxis.addLimitLine(LimitLine(timeAxis.x(event.key.time)).apply {
                                    lineColor = colors.outline.toArgb()
                                    lineWidth = 0.7f
                                    when (event.code) {
                                        HistoryEvent.CONNECTED -> enableDashedLine(4f, 5f, 0f)
                                        HistoryEvent.DISCONNECTED -> enableDashedLine(10f, 7f, 0f)
                                        else -> enableDashedLine(2f, 5f, 0f)
                                    }
                                })
                            }
                        }
                        notifyDataSetChanged()
                        setVisibleXRangeMinimum(minOf(1f, xAxis.axisMaximum))
                        setVisibleXRangeMaximum(xAxis.axisMaximum)
                        viewPortHandler.setMaximumScaleY(if (state.scaleMode == ChartScaleMode.MANUAL) 100f else 1f)
                        if (restoreViewport && lineData != null) {
                            appliedCoordinates = coordinateRange
                            appliedViewport = state.viewport
                            appliedRevision = viewportRevision
                            appliedLineData = lineData
                            appliedYBounds = yBounds
                            val restoringRevision = viewportRevision
                            addViewportJob {
                                // Run after Compose's queued restoration of the saved vertical scroll.
                                post {
                                    if (appliedCoordinates == coordinateRange && appliedViewport == state.viewport && appliedRevision == restoringRevision && appliedLineData === lineData && appliedYBounds == yBounds) {
                                        restoreFullscreenViewport(
                                            this,
                                            coordinateRange,
                                            state.viewport,
                                            preservedTopY ?: fullscreenChartTopY(this)
                                        )
                                        post {
                                            doOnPreDraw {
                                                if (appliedCoordinates == coordinateRange && appliedViewport == state.viewport && appliedRevision == restoringRevision && appliedLineData === lineData && appliedYBounds == yBounds) {
                                                    viewportReady = true
                                                    pendingTopY = null
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val index = lineData?.dataSets?.indexOfFirst { set ->
                            (set as? LineDataSet<*>)?.entries?.any { it.data == state.selectedKey } == true
                        } ?: -1
                        val highlightedMetric =
                            lineData?.dataSets?.getOrNull(index)?.label?.let { label ->
                                HistoryMetric.entries.firstOrNull { it.name == label }
                            }
                        val highlightedValue = state.selectedKey?.let { key ->
                            highlightedMetric?.let {
                                reading(
                                    it, key
                                )
                            }
                        }
                        if (index >= 0 && state.selectedKey != null && highlightedMetric != null && highlightedValue != null) {
                            highlightValue(
                                Highlight(
                                    timeAxis.x(state.selectedKey.time), fullscreenChartValue(
                                        highlightedValue,
                                        highlightedMetric,
                                        fahrenheit,
                                        currentMultiplier,
                                        valueScale
                                    ).toFloat(), index
                                ), callListener = false
                            )
                        } else highlightValue(null, callListener = false)
                    })
                if (state.error) Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(BatterySpacing.normal)
                ) {
                    Text(stringResource(R.string.history_load_failed), color = colors.error)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.advanced_action_refresh)) }
                } else if (!state.loading && points.isEmpty()) {
                    Text(
                        stringResource(R.string.current_unavailable),
                        Modifier.align(Alignment.Center),
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BatterySpacing.normal),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChartIcon(
                    R.drawable.ui_back,
                    if (points.isEmpty() || selectedIndex == 0) R.string.history_chart_pan_left else R.string.history_previous_point,
                    (points.isNotEmpty() && selectedIndex != 0) || state.viewport.start > state.range.start
                ) {
                    stepSelection(true)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = BatterySpacing.sm)
                        .semantics { liveRegion = LiveRegionMode.Polite }) {
                    val key = state.selectedKey
                    if (key == null) Text(
                        stringResource(R.string.history_chart_select_point),
                        style = typography.labelLarge,
                        color = colors.onSurfaceVariant
                    )
                    else {
                        metrics.forEach { metric ->
                            Text(
                                "${metricLabel(metric)}: ${
                                    historyValue(
                                        reading(metric, key),
                                        metric,
                                        fahrenheit,
                                        locale,
                                        currentMultiplier
                                    )
                                }",
                                style = typography.labelLarge,
                                color = if (metric == state.metric) colors.primary else colors.tertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            formatter.format(Instant.ofEpochMilli(key.time)),
                            style = typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                ChartIcon(
                    R.drawable.ui_forward,
                    if (points.isEmpty() || selectedIndex == points.lastIndex) R.string.history_chart_pan_right else R.string.history_next_point,
                    (points.isNotEmpty() && selectedIndex != points.lastIndex) || state.viewport.end < state.range.end
                ) {
                    stepSelection(false)
                }
                val events = model?.events.orEmpty()
                if (events.isNotEmpty()) Box {
                    ChartIcon(
                        R.drawable.ui_info, R.string.history_chart_events, true
                    ) { eventsMenu = true }
                    DropdownMenu(eventsMenu, onDismissRequest = { eventsMenu = false }) {
                        Text(
                            stringResource(
                                R.string.history_chart_event_summary,
                                events.filter { it.code == HistoryEvent.CONNECTED }
                                    .sumOf { it.count },
                                events.filter { it.code == HistoryEvent.DISCONNECTED }
                                    .sumOf { it.count },
                                events.filter { it.code == HistoryEvent.REBOOT }
                                    .sumOf { it.count }), Modifier
                                .width(280.dp)
                                .padding(
                                    horizontal = BatterySpacing.normal, vertical = BatterySpacing.sm
                                ), style = typography.bodySmall, color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private data class PreparedChartData(
    val coordinates: HistoryRangeState,
    val metrics: List<HistoryMetric>,
    val style: ChartDrawingStyle,
    val valueScale: ChartValueScale,
    val data: LineData
)

internal fun fullscreenChartTopY(chart: LineChartView): Float {
    val topLeft = MPPointD(0.0, 0.0)
    chart.getValuesByTouchPoint(
        chart.viewPortHandler.contentLeft,
        chart.viewPortHandler.contentTop,
        YAxis.AxisDependency.LEFT,
        topLeft
    )
    return topLeft.y.toFloat()
}

internal fun restoreFullscreenViewport(
    chart: LineChartView,
    coordinates: HistoryRangeState,
    viewport: HistoryRangeState,
    preservedTopY: Float
) {
    val timeAxis = DetailedHistoryTimeAxis(coordinates)
    val span = (viewport.end - viewport.start).toDouble() / 1000
    val targetScale = (chart.xAxis.axisRange / span).toFloat().coerceAtLeast(1f)
    chart.zoomToCenter(targetScale / chart.viewPortHandler.scaleX, 1f)
    chart.addViewportJob(
        MoveViewJob.getInstance(
            chart.viewPortHandler,
            timeAxis.x(viewport.start),
            preservedTopY,
            chart.getTransformer(YAxis.AxisDependency.LEFT),
            chart
        )
    )
}

internal fun fullscreenLineData(
    charts: Map<HistoryMetric, DetailedHistoryChartModel>,
    primary: HistoryMetric,
    comparison: HistoryMetric?,
    coordinateRange: HistoryRangeState,
    style: ChartDrawingStyle,
    valueScale: ChartValueScale,
    fahrenheit: Boolean,
    currentMultiplier: Int,
    pixelWidth: Int
): LineData {
    require(comparison != primary)
    val timeAxis = DetailedHistoryTimeAxis(coordinateRange)
    val sets = listOfNotNull(primary, comparison).flatMap { metric ->
        val chart = charts[metric] ?: return@flatMap emptyList()
        chart.points.filter { it.value != null }.groupBy { it.segment }.values.map { segment ->
            LineDataSet(segment.map { point ->
                Entry(
                    timeAxis.x(point.key.time), fullscreenChartValue(
                        point.value!!, metric, fahrenheit, currentMultiplier, valueScale
                    ).toFloat(), data = point.key
                )
            }, metric.name).apply {
                axisDependency =
                    if (metric == primary) YAxis.AxisDependency.LEFT else YAxis.AxisDependency.RIGHT
                lineWidth = 2f
                mode = LineDataSet.Mode.LINEAR
                isDrawValuesEnabled = false
                isDrawCirclesEnabled =
                    style == ChartDrawingStyle.POINTS || segment.size == 1 || (chart.raw && chart.count <= pixelWidth / 10)
                isDrawCircleHoleEnabled = metric != primary && style == ChartDrawingStyle.POINTS
                circleRadius = if (chart.count <= pixelWidth / 4) 2.5f else 1.5f
                circleHoleRadius = circleRadius / 2
                isDrawHighlightCircleEnabled = true
                highlightCircleRadius = 4f
                isHorizontalHighlightIndicatorEnabled = false
                if (metric != primary) enableDashedLine(7f, 4f, 0f)
                if (style == ChartDrawingStyle.POINTS) color = android.graphics.Color.TRANSPARENT
            }
        }
    }
    return LineData(*sets.toTypedArray())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChartIcon(drawable: Int, label: Int, enabled: Boolean, onClick: () -> Unit) {
    val text = stringResource(label)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(androidx.compose.material3.TooltipAnchorPosition.Below),
        tooltip = {
            PlainTooltip(Modifier.semantics {
                liveRegion = LiveRegionMode.Assertive
                paneTitle = text
            }) { Text(text) }
        },
        state = rememberTooltipState()
    ) {
        IconButton(
            onClick = onClick, enabled = enabled, modifier = Modifier.size(BatterySpacing.touch)
        ) {
            Icon(painterResource(drawable), text, Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ChartSelector(
    label: String,
    expanded: Boolean,
    onClick: () -> Unit,
    leadingPlus: Boolean = false,
    description: String = label
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.primary,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BatterySpacing.touch)
            .semantics {
                stateDescription = description
                selected = expanded
            }) {
        Row(
            Modifier.padding(horizontal = BatterySpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(BatterySpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingPlus) Icon(painterResource(R.drawable.ui_plus), null, Modifier.size(18.dp))
            Text(
                label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(painterResource(R.drawable.ui_down), null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ChartRadioItem(
    label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        modifier = Modifier.semantics { this.selected = selected },
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun scaleLabel(mode: ChartScaleMode): String = stringResource(
    when (mode) {
        ChartScaleMode.AUTOMATIC -> R.string.history_chart_scale_auto
        ChartScaleMode.FIXED -> R.string.history_chart_scale_fixed
        ChartScaleMode.MANUAL -> R.string.history_chart_scale_manual
    }
)
