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

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelStore
import codes.swistak.batterymonitor.BatteryMonitorTestRunner
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.DetailedHistoryChartModel
import codes.swistak.batterymonitor.logs.DetailedHistoryTimeAxis
import codes.swistak.batterymonitor.logs.HistoryKey
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryPoint
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.logs.detailedHistoryCoordinateRange
import codes.swistak.batterymonitor.logs.historyObservationValue
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.jobs.MoveViewJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.abs

class FullscreenChartStateInstrumentedTest {
    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var directory: File
    private lateinit var database: LogDatabase
    private val store = ViewModelStore()
    private var models = 0
    private val range = HistoryRangeState(0, 10_000)
    private val observation = LogRecord(200, 50, 1_000, 300, 4000, -100_000, 2_500_000)

    @Before
    fun setUp() {
        val target = BatteryMonitorTestRunner.instrumentation.targetContext
        directory =
            File(target.cacheDir, "fullscreen-state-${System.nanoTime()}").apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("${directory.name}-$name", mode)

            override fun getDatabasePath(name: String) = File(directory, name)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)

            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ) = SQLiteDatabase.openOrCreateDatabase(
                getDatabasePath(name).path, factory, errorHandler
            )
        }
        application = object : Application() {
            init {
                attachBaseContext(context)
            }
        }
        database = LogDatabase(context)
    }

    @After
    fun tearDown() {
        main { store.clear() }
        database.close()
        for (name in listOf(SettingsContract.SETTINGS_FILE, SettingsContract.SP_MAIN_FILE)) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        directory.deleteRecursively()
    }

    @Test
    fun explicit_intent_roundtrips_metric_exact_range_and_label() {
        val exact = HistoryRangeState(1_800_000_000_123, 1_800_003_600_124)
        val intent = FullscreenChartActivity.intent(context, HistoryMetric.POWER, exact, "custom")
        assertEquals(FullscreenChartActivity::class.java.name, intent.component!!.className)
        assertEquals(
            FullscreenChartRequest(HistoryMetric.POWER, exact), fullscreenChartRequest(
                intent.getStringExtra("chart.metric"),
                intent.getLongExtra("chart.start", -1),
                intent.getLongExtra("chart.end", -1)
            )
        )
        assertEquals("custom", intent.getStringExtra("chart.label"))
    }

    @Test
    fun recreation_preserves_exact_range_viewport_metric_scale_and_duplicate_time_selection() {
        database.addLogs(listOf(observation, observation.copy(currentMicroAmps = -200_000)))
        val model = model()
        loaded(model)
        val second = model.state.value!!.chart!!.points[1].key
        assertEquals(HistoryKey(1_000, 2), second)
        main {
            model.viewport(HistoryRangeState(1_000, 2_000), 600)
            model.metric(HistoryMetric.CURRENT)
            model.scale(ChartScaleMode.FIXED)
            model.select(second)
        }
        loaded(model)
        val saved = model.save()
        val restored = model(HistoryMetric.LEVEL, HistoryRangeState(20_000, 30_000), "24h", saved)
        loaded(restored)
        val state = restored.state.value!!
        assertEquals(range, state.range)
        assertEquals("custom", state.rangeLabel)
        assertEquals(HistoryRangeState(1_000, 2_000), state.viewport)
        assertEquals(HistoryMetric.CURRENT, state.metric)
        assertEquals(ChartScaleMode.FIXED, state.scaleMode)
        assertEquals(second, state.selectedKey)
        assertEquals(-200.0, state.chart!!.points.single { it.key == second }.value!!, 0.0)
        main {
            restored.initialize(HistoryMetric.POWER, HistoryRangeState(50_000, 60_000), "6h", null)
            restored.metric(HistoryMetric.POWER)
        }
        loaded(restored)
        assertEquals(range, restored.state.value!!.range)
        assertEquals(state.viewport, restored.state.value!!.viewport)
        assertEquals(second, restored.state.value!!.selectedKey)
        main { restored.range(HistoryRangeState(20_000, 30_000), "1h") }
        loaded(restored)
        assertNull(restored.state.value!!.selectedKey)
        assertEquals(restored.state.value!!.range, restored.state.value!!.viewport)
    }

    @Test
    fun malformed_saved_state_falls_back_to_the_valid_launch_request() {
        val invalid = Bundle().apply {
            putLong("start", -1)
            putLong("end", 1)
            putLong("visibleStart", 100)
            putLong("visibleEnd", 99)
            putString("metric", "UNKNOWN")
            putString("scale", "UNKNOWN")
        }
        val model = model(HistoryMetric.TEMPERATURE, range, "6h", invalid)
        loaded(model)
        val state = model.state.value!!
        assertEquals(range, state.range)
        assertEquals(range, state.viewport)
        assertEquals("6h", state.rangeLabel)
        assertEquals(HistoryMetric.TEMPERATURE, state.metric)
        assertEquals(ChartScaleMode.AUTOMATIC, state.scaleMode)
        assertNull(state.selectedKey)
    }

    @Test
    fun rapid_range_metric_and_viewport_requests_only_publish_the_latest_chart() {
        database.addLogs(
            listOf(
                observation,
                observation.copy(time = 20_500, voltage = 4100),
                observation.copy(time = 21_000, voltage = 4200),
                observation.copy(time = 21_500, voltage = 4300),
                observation.copy(time = 22_000, voltage = 4400)
            )
        )
        val model = model()
        main {
            repeat(12) { index ->
                model.range(
                    if (index % 2 == 0) range else HistoryRangeState(20_000, 30_000), "custom"
                )
                model.metric(if (index % 2 == 0) HistoryMetric.LEVEL else HistoryMetric.CURRENT)
            }
            model.range(HistoryRangeState(20_000, 30_000), "custom")
            model.metric(HistoryMetric.VOLTAGE)
            model.viewport(HistoryRangeState(21_000, 22_000), 400)
            model.select(HistoryKey(21_500, 4))
        }
        loaded(model)
        val state = model.state.value!!
        val chart = state.chart!!
        val bounds = state.bounds!!
        assertEquals(HistoryMetric.VOLTAGE, chart.metric)
        assertEquals(HistoryRangeState(20_000, 30_000), chart.range)
        assertEquals(HistoryRangeState(21_000, 22_000), chart.viewport)
        assertEquals(2L, chart.count)
        assertEquals(4.2, chart.min!!, 0.0)
        assertEquals(4.3, chart.max!!, 0.0)
        assertEquals(4.1, bounds.min!!, 0.0)
        assertEquals(4.4, bounds.max!!, 0.0)
        assertEquals(HistoryKey(21_500, 4), state.selectedKey)
    }

    @Test
    fun comparison_swaps_and_removes_while_presentation_changes_reuse_loaded_observations() {
        database.addLogs(
            listOf(
                observation.copy(currentMicroAmps = 100_000),
                observation.copy(time = 2_000, currentMicroAmps = 200_000)
            )
        )
        val model = model()
        loaded(model)
        main {
            model.comparison(HistoryMetric.CURRENT)
            model.viewport(HistoryRangeState(1_000, 3_000), 600)
        }
        loaded(model)
        val selected = model.state.value!!.chart!!.points.first().key
        main { model.select(selected) }
        await { model.state.value!!.selectedRecord != null }
        val before = model.state.value!!
        main {
            model.style(ChartDrawingStyle.POINTS)
            model.valueScale(ChartValueScale.LOGARITHMIC)
            model.scale(ChartScaleMode.MANUAL)
        }
        val presented = model.state.value!!
        assertFalse(presented.loading)
        assertSame(before.charts, presented.charts)
        assertSame(before.boundsByMetric, presented.boundsByMetric)
        assertSame(before.selectedRecord, presented.selectedRecord)
        assertEquals(before.viewport, presented.viewport)
        assertEquals(selected, presented.selectedKey)
        assertEquals(ChartDrawingStyle.POINTS, presented.style)
        assertEquals(ChartValueScale.LOGARITHMIC, presented.valueScale)
        assertEquals(ChartScaleMode.MANUAL, presented.scaleMode)

        main { model.metric(HistoryMetric.CURRENT) }
        loaded(model)
        val swapped = model.state.value!!
        assertEquals(listOf(HistoryMetric.CURRENT, HistoryMetric.LEVEL), swapped.metrics)
        assertEquals(before.viewport, swapped.viewport)
        assertEquals(selected, swapped.selectedKey)
        main { model.comparison(HistoryMetric.CURRENT) }
        assertSame(swapped, model.state.value)

        main { model.comparison(HistoryMetric.POWER) }
        loaded(model)
        assertEquals(
            listOf(HistoryMetric.CURRENT, HistoryMetric.POWER), model.state.value!!.metrics
        )
        assertEquals(
            setOf(HistoryMetric.CURRENT, HistoryMetric.POWER), model.state.value!!.charts.keys
        )
        main { model.comparison(null) }
        loaded(model)
        val single = model.state.value!!
        assertNull(single.comparison)
        assertEquals(listOf(HistoryMetric.CURRENT), single.metrics)
        assertEquals(setOf(HistoryMetric.CURRENT), single.charts.keys)
        assertEquals(setOf(HistoryMetric.CURRENT), single.boundsByMetric.keys)
        assertEquals(before.viewport, single.viewport)
        assertEquals(selected, single.selectedKey)
    }

    @Test
    fun viewport_cancellation_of_refresh_still_reloads_full_range_logarithmic_eligibility() {
        database.addLogs(listOf(observation, observation.copy(time = 2_000, charge = 80)))
        val model = model()
        loaded(model)
        assertEquals(50.0, model.state.value!!.bounds!!.min!!, 0.0)
        database.addLogs(listOf(observation.copy(time = 6_000, charge = 0)))
        main {
            model.refresh()
            model.viewport(HistoryRangeState(1_000, 3_000), 300)
        }
        loaded(model)
        val state = model.state.value!!
        val bounds = requireNotNull(state.bounds)
        assertEquals(HistoryRangeState(1_000, 3_000), state.viewport)
        assertEquals(50.0, state.chart!!.min!!, 0.0)
        assertEquals(0.0, bounds.min!!, 0.0)
        assertEquals(3L, bounds.count)
        assertFalse(fullscreenLogAvailable(state.metrics, state.boundsByMetric, false, 1))
    }

    @Test
    fun exact_selection_resolves_both_physical_values_even_when_comparison_lod_omits_the_record() {
        val records = (0 until 1200).map { index ->
            observation.copy(
                time = 1_000L + index * 10,
                charge = 1 + (index * 37) % 100,
                currentMicroAmps = 100_000L + index * 100
            )
        }
        database.addLogs(records)
        val fullRange = HistoryRangeState(0, 20_000)
        val viewport = HistoryRangeState(1_000, 13_000)
        val model = model(HistoryMetric.LEVEL, fullRange)
        main {
            model.comparison(HistoryMetric.CURRENT)
            model.viewport(viewport, 64)
        }
        loaded(model)
        val state = model.state.value!!
        val primary = state.charts.getValue(HistoryMetric.LEVEL)
        val comparison = state.charts.getValue(HistoryMetric.CURRENT)
        assertFalse(primary.raw)
        assertFalse(comparison.raw)
        val comparisonKeys = comparison.points.map { it.key }.toSet()
        val key = primary.points.first { it.value != null && it.key !in comparisonKeys }.key
        val expected = records[(key.id - 1).toInt()]
        main {
            model.select(key)
            model.style(ChartDrawingStyle.POINTS)
            model.valueScale(ChartValueScale.LOGARITHMIC)
            model.scale(ChartScaleMode.MANUAL)
        }
        await { model.state.value!!.selectedRecord?.id == key.id }
        val selected = model.state.value!!.selectedRecord!!
        assertEquals(key.time, selected.record.time)
        assertEquals(expected, selected.record)
        assertEquals(
            expected.charge!!.toDouble(),
            historyObservationValue(HistoryMetric.LEVEL, selected.record)!!,
            0.0
        )
        assertEquals(
            expected.currentMicroAmps!! / 1000.0,
            historyObservationValue(HistoryMetric.CURRENT, selected.record)!!,
            0.0
        )
        lateinit var saved: Bundle
        main { saved = model.save() }
        val restored = model(HistoryMetric.POWER, range, "24h", saved)
        loaded(restored)
        await { restored.state.value!!.selectedRecord?.id == key.id }
        val restoredState = restored.state.value!!
        assertEquals(fullRange, restoredState.range)
        assertEquals("custom", restoredState.rangeLabel)
        assertEquals(viewport, restoredState.viewport)
        assertEquals(listOf(HistoryMetric.LEVEL, HistoryMetric.CURRENT), restoredState.metrics)
        assertEquals(ChartDrawingStyle.POINTS, restoredState.style)
        assertEquals(ChartValueScale.LOGARITHMIC, restoredState.valueScale)
        assertEquals(ChartScaleMode.MANUAL, restoredState.scaleMode)
        assertEquals(key, restoredState.selectedKey)
        assertEquals(selected, restoredState.selectedRecord)
    }

    @Test
    fun exact_selection_keeps_missing_comparison_values_unavailable_and_duplicate_times_distinct() {
        database.addLogs(
            listOf(
                observation.copy(currentMicroAmps = 100_000),
                observation.copy(currentMicroAmps = null, charge = 51)
            )
        )
        val model = model()
        main { model.comparison(HistoryMetric.CURRENT) }
        loaded(model)
        val key = model.state.value!!.chart!!.points.last().key
        assertEquals(HistoryKey(1_000, 2), key)
        main { model.select(key) }
        await { model.state.value!!.selectedRecord?.id == key.id }
        val selected = model.state.value!!.selectedRecord!!
        assertEquals(1_000L, selected.record.time)
        assertEquals(51.0, historyObservationValue(HistoryMetric.LEVEL, selected.record)!!, 0.0)
        assertNull(historyObservationValue(HistoryMetric.CURRENT, selected.record))
        assertEquals(key, model.state.value!!.selectedKey)
    }

    @Test
    fun viewer_all_history_tracks_retained_bounds_growth_pruning_and_empty_state_after_restore() {
        val model = model(label = "all")
        loaded(model)
        assertEquals(HistoryRangeState(0, 1), model.state.value!!.range)
        assertEquals("all", model.state.value!!.rangeLabel)
        database.addLogs(listOf(observation))
        main { model.refresh() }
        loaded(model)
        assertEquals(HistoryRangeState(1_000, 1_001), model.state.value!!.range)
        assertEquals(model.state.value!!.range, model.state.value!!.viewport)

        database.addLogs(
            listOf(
                observation.copy(status = 22, time = 100),
                observation.copy(time = 20_000),
                observation.copy(time = 20_000, charge = 51)
            )
        )
        main { model.refresh() }
        loaded(model)
        assertEquals(HistoryRangeState(100, 20_001), model.state.value!!.range)
        assertEquals(model.state.value!!.range, model.state.value!!.viewport)
        assertEquals(4L, model.state.value!!.bounds!!.count)
        main { model.viewport(HistoryRangeState(1_000, 2_000), 100) }
        loaded(model)
        database.addLogs(listOf(observation.copy(time = 30_000)))
        main { model.refresh() }
        loaded(model)
        assertEquals(HistoryRangeState(100, 30_001), model.state.value!!.range)
        assertEquals(HistoryRangeState(1_000, 2_000), model.state.value!!.viewport)

        database.deleteHistory(HistoryRangeState(100, 101))
        main { model.refresh() }
        loaded(model)
        assertEquals(HistoryRangeState(1_000, 30_001), model.state.value!!.range)
        database.prune(24)
        main { model.refresh() }
        loaded(model)
        assertEquals("all", model.state.value!!.rangeLabel)
        assertEquals(HistoryRangeState(0, 1), model.state.value!!.range)
        assertEquals(HistoryRangeState(0, 1), model.state.value!!.viewport)
        assertEquals(0L, model.state.value!!.bounds!!.count)
        assertTrue(model.state.value!!.chart!!.points.isEmpty())
        lateinit var saved: Bundle
        main { saved = model.save() }
        database.addLogs(listOf(observation.copy(time = 40_000)))
        val restored = model(HistoryMetric.CURRENT, range, "custom", saved)
        loaded(restored)
        assertEquals("all", restored.state.value!!.rangeLabel)
        assertEquals(HistoryRangeState(40_000, 40_001), restored.state.value!!.range)
        assertEquals(restored.state.value!!.range, restored.state.value!!.viewport)
    }

    @Test
    fun native_datasets_keep_physical_axes_segments_exact_keys_and_real_log_coordinates() {
        database.addLogs(
            listOf(
                observation.copy(currentMicroAmps = 100_000),
                observation.copy(time = 2_000, charge = 60, currentMicroAmps = 200_000),
                observation.copy(time = 3_000, status = LogDatabase.STATUS_BOOT_COMPLETED),
                observation.copy(time = 4_000, charge = 70, currentMicroAmps = 300_000),
                observation.copy(time = 5_000, charge = 80, currentMicroAmps = 400_000)
            )
        )
        val model = model()
        main { model.comparison(HistoryMetric.CURRENT) }
        loaded(model)
        val state = model.state.value!!
        for (style in ChartDrawingStyle.entries) {
            for (valueScale in ChartValueScale.entries) {
                val data = fullscreenLineData(
                    state.charts,
                    HistoryMetric.LEVEL,
                    HistoryMetric.CURRENT,
                    range,
                    style,
                    valueScale,
                    false,
                    1,
                    800
                )
                val sets = data.dataSets.filterIsInstance<LineDataSet<*>>()
                assertEquals(4, sets.size)
                for (set in sets) {
                    val metric = HistoryMetric.valueOf(set.label)
                    assertEquals(
                        if (metric == HistoryMetric.LEVEL) YAxis.AxisDependency.LEFT else YAxis.AxisDependency.RIGHT,
                        set.axisDependency
                    )
                    assertEquals(2, set.entries.size)
                    for (entry in set.entries) {
                        val point =
                            state.charts.getValue(metric).points.single { it.key == entry.data }
                        assertEquals((point.key.time / 1000.0).toFloat(), entry.x, 0f)
                        assertEquals(
                            fullscreenChartValue(
                                point.value!!, metric, false, 1, valueScale
                            ).toFloat(), entry.y, 0f
                        )
                    }
                    if (style == ChartDrawingStyle.POINTS) {
                        assertEquals(Color.TRANSPARENT, set.color)
                        assertTrue(set.isDrawCirclesEnabled)
                        assertEquals(metric == HistoryMetric.CURRENT, set.isDrawCircleHoleEnabled)
                    }
                }
            }
        }
        val swapped = fullscreenLineData(
            state.charts,
            HistoryMetric.CURRENT,
            HistoryMetric.LEVEL,
            range,
            ChartDrawingStyle.CONNECTED,
            ChartValueScale.LINEAR,
            false,
            1,
            800
        )
        assertTrue(swapped.dataSets.filter { it.label == "CURRENT" }
            .all { it.axisDependency == YAxis.AxisDependency.LEFT })
        val single = fullscreenLineData(
            state.charts,
            HistoryMetric.LEVEL,
            null,
            range,
            ChartDrawingStyle.CONNECTED,
            ChartValueScale.LINEAR,
            false,
            1,
            800
        )
        assertEquals(2, single.dataSets.size)
        assertTrue(single.dataSets.all { it.label == "LEVEL" && it.axisDependency == YAxis.AxisDependency.LEFT })
    }

    @Test
    fun native_coordinates_remain_precise_in_a_late_short_viewport_of_all_history() {
        val late = 1_800_000_000_000L
        val all = HistoryRangeState(0, Long.MAX_VALUE)
        val viewport = HistoryRangeState(late, late + 60_000)
        val points = listOf(
            HistoryPoint(HistoryKey(late - 60_000, 1), 50.0),
            HistoryPoint(HistoryKey(late + 60_000, 2), 51.0)
        )
        val chart = DetailedHistoryChartModel(
            all, viewport, HistoryMetric.LEVEL, 0, points, emptyList(), null, null, true
        )
        val data = fullscreenLineData(
            mapOf(HistoryMetric.LEVEL to chart),
            HistoryMetric.LEVEL,
            null,
            viewport,
            ChartDrawingStyle.CONNECTED,
            ChartValueScale.LINEAR,
            false,
            1,
            800
        )
        val entries = (data.dataSets.single() as LineDataSet<*>).entries
        assertEquals(-60f, entries[0].x, 0f)
        assertEquals(60f, entries[1].x, 0f)
        assertEquals(120f, entries[1].x - entries[0].x, 0f)
        assertEquals(points.map { it.key }, entries.map { it.data })
    }

    @Test
    fun isolated_real_viewer_renders_dense_sparse_reboot_and_presentation_fixtures() {
        val start = System.currentTimeMillis() - 24 * 3_600_000L
        val fullRange = HistoryRangeState(start, start + 24 * 3_600_000L)
        val samples = (0..200).map { index ->
            observation.copy(
                time = start + 4 * 3_600_000L + index * 300_000L + (index % 3) * 20_000L,
                charge = if (index == 200) 0 else 25 + index % 60,
                temperature = 300 + index % 80,
                voltage = 3900 + index % 200,
                currentMicroAmps = 100_000L + index * 137
            )
        }
        val rebootTime = samples[100].time + 30_000
        database.addLogs(
            (listOf(
                observation.copy(
                    time = start, status = 22, charge = 35, currentMicroAmps = 100_000
                ), observation.copy(
                    time = start + 3 * 3_600_000L,
                    status = 22,
                    charge = 30,
                    currentMicroAmps = 120_000
                ), observation.copy(time = rebootTime, status = LogDatabase.STATUS_BOOT_COMPLETED)
            ) + samples).sortedBy { it.time })
        context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE).edit()
            .putString(SettingsContract.KEY_TEMPERATURE_UNIT, "celsius")
            .putString(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "1")
            .putString(SettingsContract.KEY_BRIGHTNESS, "Light")
            .putString(SettingsContract.KEY_COLOR_SOURCE, "Dynamic").commit()
        val model = model(HistoryMetric.LEVEL, fullRange, "24h")
        loaded(model)
        val instrumentation = BatteryMonitorTestRunner.instrumentation
        val monitor =
            instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        instrumentation.startActivitySync(
            FullscreenChartActivity.intent(
                instrumentation.targetContext, HistoryMetric.LEVEL, fullRange, "24h"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            await {
                val activity = monitor.lastActivity
                activity != null && !activity.isDestroyed && activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            instrumentation.waitForIdleSync()
            val activity = monitor.lastActivity as FullscreenChartActivity
            main {
                activity.setContent {
                    CompositionLocalProvider(LocalContext provides context) {
                        AppBatteryTheme {
                            val state by model.state.collectAsState()
                            state?.let {
                                FullscreenChartScreen(
                                    it,
                                    model::metric,
                                    model::comparison,
                                    model::range,
                                    model::viewport,
                                    model::scale,
                                    model::style,
                                    model::valueScale,
                                    model::select,
                                    model::refresh,
                                    activity::finish
                                )
                            }
                        }
                    }
                }
            }
            captureFixture(activity, model, "single-connected")
            main { model.comparison(HistoryMetric.CURRENT) }
            captureFixture(activity, model, "level-current")
            main { model.style(ChartDrawingStyle.POINTS) }
            captureFixture(activity, model, "points")
            main {
                model.metric(HistoryMetric.TEMPERATURE)
                model.comparison(HistoryMetric.VOLTAGE)
                model.style(ChartDrawingStyle.CONNECTED)
                model.valueScale(ChartValueScale.LOGARITHMIC)
            }
            captureFixture(activity, model, "positive-log")
            captureFixtureMenu("positive-log-settings")
            main {
                model.metric(HistoryMetric.LEVEL)
                model.comparison(HistoryMetric.CURRENT)
            }
            captureFixture(activity, model, "unsupported-log")
            await { model.state.value!!.valueScale == ChartValueScale.LINEAR }
            captureFixtureMenu("disabled-log-settings")
            main { model.range(fullRange, "all") }
            captureFixture(activity, model, "all-history")
            val requestedViewport = HistoryRangeState(samples[95].time, samples[107].time)
            main {
                model.viewport(requestedViewport, 1000)
                model.select(model.state.value!!.charts.getValue(HistoryMetric.LEVEL).points.first {
                    it.value != null && it.key.time > rebootTime
                }.key)
            }
            captureFixture(activity, model, "periodic-reboot-zoom")
            assertFixtureViewport(activity, model, requestedViewport)
            database.addLogs(
                listOf(
                    observation.copy(
                        time = start - 3_600_000L, currentMicroAmps = 100_000
                    )
                )
            )
            main { model.refresh() }
            captureFixture(activity, model, "all-history-earlier-record-zoom")
            assertEquals(start - 3_600_000L, model.state.value!!.range.start)
            assertFixtureViewport(activity, model, requestedViewport)
            main { model.scale(ChartScaleMode.MANUAL) }
            captureFixture(activity, model, "manual-y")
            main {
                val chart = requireNotNull(nativeChart(activity.window.decorView))
                chart.zoomToCenter(1f, 2f)
                chart.addViewportJob(
                    MoveViewJob.getInstance(
                        chart.viewPortHandler,
                        chart.lowestVisibleX,
                        fullscreenChartTopY(chart) - 8f,
                        chart.getTransformer(YAxis.AxisDependency.LEFT),
                        chart
                    )
                )
            }
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(150)
            var beforeScaleY = 0f
            var beforeTopY = 0f
            main {
                val chart = requireNotNull(nativeChart(activity.window.decorView))
                beforeScaleY = chart.viewPortHandler.scaleY
                beforeTopY = fullscreenChartTopY(chart)
            }
            assertTrue(beforeScaleY > 1.5f)
            val shiftedViewport =
                HistoryRangeState(requestedViewport.start + 60_000, requestedViewport.end + 60_000)
            main { model.viewport(shiftedViewport, 1000) }
            captureFixture(activity, model, "manual-y-horizontal-rebase")
            assertFixtureViewport(activity, model, shiftedViewport)
            main {
                val chart = requireNotNull(nativeChart(activity.window.decorView))
                assertEquals(beforeScaleY, chart.viewPortHandler.scaleY, 0.001f)
                assertEquals(beforeTopY, fullscreenChartTopY(chart), 0.01f)
            }
            main {
                model.scale(ChartScaleMode.AUTOMATIC)
                model.select(null)
                model.viewport(HistoryRangeState(start, start + 4 * 3_600_000L), 1000)
            }
            captureFixture(activity, model, "legacy-sparse")
            main { model.viewport(model.state.value!!.range, 1000) }
            for (brightness in listOf("Dark", "TrueBlack")) {
                context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
                    .edit().putString(SettingsContract.KEY_BRIGHTNESS, brightness).commit()
                captureFixture(activity, model, brightness.lowercase())
            }
        } finally {
            main { monitor.lastActivity?.finish() }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun returning_to_history_preserves_exact_range_once_regardless_of_initial_monitor_effect_order() {
        val initial = Bundle().apply {
            putLong("start", range.start)
            putLong("end", range.end)
            putString("label", "24h")
            putString("tab", "logs")
            putInt("metric", HistoryMetric.CURRENT.ordinal)
            putInt("scrollIndex", 7)
            putInt("scrollOffset", 34)
        }
        val monitored = Triple<Int?, Int?, Int?>(50, 300, 4000)
        for (effectOrder in listOf("before", "during", "after")) {
            lateinit var history: HistoryViewModel
            lateinit var saved: Bundle
            main {
                history = HistoryViewModel(application)
                store.put("history-$effectOrder", history)
                history.restore(initial)
                history.retainExactRange()
                saved = history.save()
            }
            lateinit var restored: HistoryViewModel
            main {
                restored = HistoryViewModel(application)
                store.put("restored-history-$effectOrder", restored)
                restored.restore(saved)
                if (effectOrder == "before") restored.monitoringChanged(monitored)
                restored.resume()
                if (effectOrder == "during") restored.monitoringChanged(monitored)
            }
            await { !restored.state.value.loading }
            if (effectOrder == "after") {
                main { restored.monitoringChanged(monitored) }
                await { !restored.state.value.loading }
            }
            assertFalse(restored.state.value.error)
            assertEquals(effectOrder, range, restored.state.value.range)
            assertEquals("24h", restored.state.value.rangeLabel)
            assertEquals("logs", restored.state.value.tab)
            assertEquals(HistoryMetric.CURRENT, restored.state.value.metric)
            assertEquals(7, restored.scrollIndex)
            assertEquals(34, restored.scrollOffset)

            val before = System.currentTimeMillis()
            main { restored.resume() }
            await { !restored.state.value.loading }
            val after = System.currentTimeMillis()
            val refreshed = restored.state.value.range
            assertFalse(restored.state.value.error)
            assertNotEquals(range, refreshed)
            assertTrue(refreshed.end in before..after + 1)
            assertEquals(24 * 3_600_000L, refreshed.end - refreshed.start)
            assertEquals("logs", restored.state.value.tab)
            assertEquals(7, restored.scrollIndex)
            assertEquals(34, restored.scrollOffset)
        }
    }

    @Test
    fun surviving_history_model_keeps_return_range_when_monitoring_changed_in_the_viewer() {
        val oldReading = Triple<Int?, Int?, Int?>(50, 300, 4000)
        val viewerReading = Triple<Int?, Int?, Int?>(49, 310, 4100)
        val nextReading = Triple<Int?, Int?, Int?>(48, 320, 4200)
        lateinit var history: HistoryViewModel
        main {
            history = HistoryViewModel(application)
            store.put("surviving-history", history)
            history.restore(Bundle().apply {
                putLong("start", range.start)
                putLong("end", range.end)
                putString("label", "24h")
            })
            history.monitoringChanged(oldReading)
            history.retainExactRange()
            history.resume(viewerReading)
            history.monitoringChanged(viewerReading)
        }
        await { !history.state.value.loading }
        assertFalse(history.state.value.error)
        assertEquals(range, history.state.value.range)

        val before = System.currentTimeMillis()
        main { history.monitoringChanged(nextReading) }
        await { !history.state.value.loading }
        val after = System.currentTimeMillis()
        val refreshed = history.state.value.range
        assertFalse(history.state.value.error)
        assertNotEquals(range, refreshed)
        assertTrue(refreshed.end in before..(after + 1))
        assertEquals(24 * 3_600_000L, refreshed.end - refreshed.start)
    }

    @Test
    fun all_history_resolves_empty_and_new_retained_observations_and_restores_the_mode() {
        lateinit var history: HistoryViewModel
        main {
            history = HistoryViewModel(application)
            store.put("history-all-empty", history)
            history.range(range, "all")
        }
        await { !history.state.value.loading }
        assertFalse(history.state.value.error)
        assertEquals(HistoryRangeState(0, 1), history.state.value.range)
        assertTrue(history.state.value.page.isEmpty())
        assertEquals("all", history.state.value.rangeLabel)

        database.addLogs(listOf(observation))
        main { history.refresh() }
        await { !history.state.value.loading }
        assertEquals(HistoryRangeState(1_000, 1_001), history.state.value.range)
        assertEquals(1, history.state.value.page.size)

        database.addLogs(
            listOf(
                observation.copy(status = 22, time = 100),
                observation.copy(time = 20_000),
                observation.copy(time = 20_000, charge = 51)
            )
        )
        lateinit var saved: Bundle
        main {
            history.refresh()
        }
        await { !history.state.value.loading }
        main {
            history.tab("logs")
            history.metric(HistoryMetric.CURRENT)
            history.reverse()
            saved = history.save()
        }
        await { !history.state.value.loading }
        lateinit var restored: HistoryViewModel
        main {
            restored = HistoryViewModel(application)
            store.put("history-all-restored", restored)
            restored.restore(saved)
            restored.retainExactRange()
            restored.resume()
        }
        await { !restored.state.value.loading }
        val state = restored.state.value
        assertFalse(state.error)
        assertEquals("all", state.rangeLabel)
        assertEquals(HistoryRangeState(100, 20_001), state.range)
        assertEquals("logs", state.tab)
        assertEquals(HistoryMetric.CURRENT, state.metric)
        assertTrue(state.ascending)
        assertEquals(listOf(100L, 1_000L, 20_000L, 20_000L), state.page.map { it.record.time })
    }

    @Test
    fun all_history_recalculates_after_restore_delete_and_prune_without_changing_selection_mode() {
        val now = System.currentTimeMillis()
        val old = now - 3 * 24 * 3_600_000L
        database.addLogs(listOf(observation.copy(time = old), observation.copy(time = now)))
        lateinit var history: HistoryViewModel
        main {
            history = HistoryViewModel(application)
            store.put("history-all-mutations", history)
            history.range(range, "all")
        }
        await { !history.state.value.loading }
        assertEquals(HistoryRangeState(old, now + 1), history.state.value.range)

        database.prune(24)
        main { history.refresh() }
        await { !history.state.value.loading }
        assertEquals(HistoryRangeState(now, now + 1), history.state.value.range)

        database.replaceAllLogs(
            listOf(
                observation.copy(time = 100), observation.copy(time = now + 3_600_000L)
            )
        )
        main { history.refresh() }
        await { !history.state.value.loading }
        assertEquals(HistoryRangeState(100, now + 3_600_001L), history.state.value.range)

        database.deleteHistory(HistoryRangeState(100, 101))
        main { history.refresh() }
        await { !history.state.value.loading }
        assertEquals(
            HistoryRangeState(now + 3_600_000L, now + 3_600_001L), history.state.value.range
        )
        database.deleteHistory(null)
        main { history.refresh() }
        await { !history.state.value.loading }
        assertEquals("all", history.state.value.rangeLabel)
        assertEquals(HistoryRangeState(0, 1), history.state.value.range)
        assertTrue(history.state.value.page.isEmpty())
    }

    @Test
    fun all_history_keeps_bounded_event_pagination_sorting_and_measurement_filters() {
        database.addLogs((0..299).map { index ->
            observation.copy(time = 1_000L + index, status = if (index % 2 == 0) 22 else 200)
        })
        lateinit var history: HistoryViewModel
        main {
            history = HistoryViewModel(application)
            store.put("history-all-pagination", history)
            history.range(range, "all")
            history.tab("logs")
        }
        await { !history.state.value.loading }
        val first = history.state.value.page
        assertFalse(history.state.value.error)
        assertEquals(128, first.size)
        assertTrue(history.state.value.hasNext)
        main { history.next(previous = false) }
        await { !history.state.value.loading }
        val second = history.state.value.page
        assertEquals(128, second.size)
        assertTrue(first.map { it.id }.intersect(second.map { it.id }.toSet()).isEmpty())
        assertTrue(history.state.value.hasPrevious)
        assertEquals("all", history.state.value.rangeLabel)

        main {
            history.filters(setOf("battery_measurement"))
            history.reverse()
        }
        await { !history.state.value.loading }
        val filtered = history.state.value
        assertFalse(filtered.error)
        assertEquals(128, filtered.page.size)
        assertTrue(filtered.hasNext)
        assertTrue(filtered.page.all { LogDatabase.decodeStatus(it.record.status)[2] == LogDatabase.STATUS_SAMPLE })
        assertTrue(
            filtered.page.zipWithNext()
                .all { (first, second) -> first.record.time <= second.record.time })
        assertEquals(HistoryRangeState(1_000, 1_300), filtered.range)
        assertEquals("all", filtered.rangeLabel)
    }

    private fun model(
        metric: HistoryMetric = HistoryMetric.LEVEL,
        range: HistoryRangeState = this.range,
        label: String = "custom",
        saved: Bundle? = null
    ): FullscreenChartViewModel {
        lateinit var model: FullscreenChartViewModel
        main {
            model = FullscreenChartViewModel(application)
            store.put("viewer-${models++}", model)
            model.initialize(metric, range, label, saved)
        }
        return model
    }

    private fun main(action: () -> Unit) =
        BatteryMonitorTestRunner.instrumentation.runOnMainSync(action)

    private fun nativeChart(view: View): LineChart? {
        if (view is LineChart) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) nativeChart(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun captureFixture(
        activity: FullscreenChartActivity, model: FullscreenChartViewModel, name: String
    ) {
        loaded(model)
        await {
            var rendered = false
            main {
                val state = model.state.value!!
                val chart = nativeChart(activity.window.decorView)
                val labels = chart?.data?.dataSets?.map { it.label }?.toSet()
                val valueScale =
                    if (state.valueScale == ChartValueScale.LOGARITHMIC && !fullscreenLogAvailable(
                            state.metrics, state.boundsByMetric, false, 1
                        )
                    ) {
                        ChartValueScale.LINEAR
                    } else state.valueScale
                val valuesMatch = state.metrics.all { metric ->
                    val point = state.charts[metric]?.points?.firstOrNull { it.value != null }
                    val set =
                        chart?.data?.dataSets?.filterIsInstance<LineDataSet<*>>()?.firstOrNull {
                            it.label == metric.name
                        }
                    val entry = set?.entries?.firstOrNull()
                    point != null && set != null && entry != null && entry.data == point.key && abs(
                        entry.y - fullscreenChartValue(
                            point.value!!, metric, false, 1, valueScale
                        ).toFloat()
                    ) < 0.0001 && (state.style != ChartDrawingStyle.POINTS || set.color == Color.TRANSPARENT)
                }
                rendered =
                    chart != null && chart.width > 0 && chart.height > 0 && labels == state.metrics.map { it.name }
                        .toSet() && chart.axisRight.isEnabled == (state.comparison != null) && valuesMatch
                if (rendered) {
                    assertFalse(
                        ViewCompat.getRootWindowInsets(activity.window.decorView)!!
                            .isVisible(WindowInsetsCompat.Type.statusBars())
                    )
                }
            }
            rendered
        }
        BatteryMonitorTestRunner.instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(150)
        captureFixtureScreenshot(name)
    }

    private fun captureFixtureMenu(name: String) {
        val instrumentation = BatteryMonitorTestRunner.instrumentation
        if (!clickFixtureText(context.getString(R.string.history_chart_settings))) {
            assertTrue(clickFixtureText(context.getString(R.string.history_chart_more)))
            instrumentation.waitForIdleSync()
            assertTrue(clickFixtureText(context.getString(R.string.history_chart_settings)))
        }
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(150)
        captureFixtureScreenshot(name)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
    }

    private fun assertFixtureViewport(
        activity: FullscreenChartActivity,
        model: FullscreenChartViewModel,
        requested: HistoryRangeState
    ) {
        BatteryMonitorTestRunner.instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(250)
        loaded(model)
        main {
            val state = model.state.value!!
            val axis =
                DetailedHistoryTimeAxis(detailedHistoryCoordinateRange(state.range, state.viewport))
            val precision =
                maxOf(Math.ulp(axis.x(requested.start)), Math.ulp(axis.x(requested.end)))
            val tolerance = maxOf(3L, kotlin.math.ceil(precision * 2000.0).toLong() + 1)
            assertTrue(
                "Requested start drifted: ${state.viewport} / $requested",
                abs(state.viewport.start - requested.start) <= tolerance
            )
            assertTrue(
                "Requested end drifted: ${state.viewport} / $requested",
                abs(state.viewport.end - requested.end) <= tolerance
            )
            val chart = requireNotNull(nativeChart(activity.window.decorView))
            assertTrue(
                "Native start drifted",
                abs(axis.time(chart.lowestVisibleX) - requested.start) <= tolerance
            )
            assertTrue(
                "Native end drifted",
                abs(axis.time(chart.highestVisibleX) - requested.end) <= tolerance
            )
        }
    }

    private fun clickFixtureText(text: String): Boolean {
        val automation = BatteryMonitorTestRunner.instrumentation.uiAutomation
        val root = automation.rootInActiveWindow ?: return false
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) {
                return node
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> find(child)?.let { return it } }
            }
            return null
        }

        val node = find(root) ?: return false
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        val downTime = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            bounds.exactCenterX(),
            bounds.exactCenterY(),
            0
        )
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        val pressed = automation.injectInputEvent(down, true)
        down.recycle()
        android.os.SystemClock.sleep(50)
        val up = MotionEvent.obtain(
            downTime,
            android.os.SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            bounds.exactCenterX(),
            bounds.exactCenterY(),
            0
        )
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        val released = automation.injectInputEvent(up, true)
        up.recycle()
        return pressed && released
    }

    private fun captureFixtureScreenshot(name: String) {
        val instrumentation = BatteryMonitorTestRunner.instrumentation
        val screenshots =
            File(instrumentation.targetContext.cacheDir, "chart-v2-fixture").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(screenshots, "$name.png").outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
    }

    private fun loaded(model: FullscreenChartViewModel) {
        await { model.state.value?.loading == false }
        assertFalse(model.state.value!!.error)
    }

    private fun await(ready: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!ready() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("History loading did not finish", ready())
    }
}
