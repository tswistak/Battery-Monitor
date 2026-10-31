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
import android.content.ComponentCallbacks
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
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
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.DetailedHistoryChartModel
import codes.swistak.batterymonitor.logs.DetailedHistoryTimeAxis
import codes.swistak.batterymonitor.logs.HistoryKey
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryPoint
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogRecord
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class FullscreenChartStateInstrumentedTest {
    @Test
    fun close_and_back_return_to_the_previous_orientation_and_system_bars() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val parent = instrumentation.addMonitor(
            "codes.swistak.batterymonitor.app.BatteryInfoActivity", null, false
        )
        val viewer =
            instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        main {
            target.startActivity(
                Intent(
                    target, codes.swistak.batterymonitor.app.BatteryInfoActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
        try {
            lateinit var expected: Triple<Int, Int, Boolean>
            var initialState = "No parent Activity observed"
            await({ "Initial parent orientation and system-bar capture did not settle: $initialState" }) {
                var ready = false
                main {
                    val activity =
                        parent.lastActivity as? codes.swistak.batterymonitor.app.BatteryInfoActivity
                    val decor = activity?.window?.decorView
                    val insets = decor?.rootWindowInsets
                    val statusBarVisible = if (insets != null) {
                        WindowInsetsCompat.toWindowInsetsCompat(insets, decor)
                            .isVisible(WindowInsetsCompat.Type.statusBars())
                    } else null
                    initialState =
                        "activity=${System.identityHashCode(activity)}, " + "finishing=${activity?.isFinishing}, destroyed=${activity?.isDestroyed}, " + "lifecycle=${activity?.lifecycle?.currentState}, focus=${activity?.hasWindowFocus()}, " + "orientation=${activity?.resources?.configuration?.orientation}, " + "requestedOrientation=${activity?.requestedOrientation}, " + "insets=${insets != null}, statusBarVisible=$statusBarVisible"
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed && activity.lifecycle.currentState == Lifecycle.State.RESUMED && statusBarVisible != null) {
                        expected = Triple(
                            activity.resources.configuration.orientation,
                            activity.requestedOrientation,
                            statusBarVisible
                        )
                        ready = true
                    }
                }
                ready
            }
            val (orientation, requested, bars) = expected
            for (back in listOf(false, true)) {
                main {
                    parent.lastActivity.startActivity(
                        FullscreenChartActivity.intent(
                            target, HistoryMetric.LEVEL, range, "custom"
                        )
                    )
                }
                var viewerState = "No fullscreen viewer observed"
                await({ "Fullscreen viewer did not finish landscape restoration (back=$back): $viewerState" }) {
                    var ready = false
                    main {
                        val activity = viewer.lastActivity as? FullscreenChartActivity
                        val decor = activity?.window?.decorView
                        val insets = decor?.rootWindowInsets
                        val statusBarVisible = if (insets != null) {
                            WindowInsetsCompat.toWindowInsetsCompat(insets, decor)
                                .isVisible(WindowInsetsCompat.Type.statusBars())
                        } else null
                        viewerState =
                            "activity=${System.identityHashCode(activity)}, " + "finishing=${activity?.isFinishing}, destroyed=${activity?.isDestroyed}, " + "lifecycle=${activity?.lifecycle?.currentState}, focus=${activity?.hasWindowFocus()}, " + "orientation=${activity?.resources?.configuration?.orientation}, " + "insets=${insets != null}, statusBarVisible=$statusBarVisible"
                        ready =
                            activity != null && !activity.isFinishing && !activity.isDestroyed && activity.lifecycle.currentState == Lifecycle.State.RESUMED && activity.hasWindowFocus() && (activity.isInMultiWindowMode || android.os.Build.VERSION.SDK_INT >= 36 && activity.resources.configuration.smallestScreenWidthDp >= 600 || activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) && statusBarVisible == false
                    }
                    ready
                }
                if (back) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                else assertTrue(clickFixtureText(target.getString(R.string.nav_up)))
                var returnedState = "No returned parent Activity observed"
                await({
                    "Parent restoration after ${if (back) "Back" else "Close"} did not settle; " + "expected orientation=$orientation, requestedOrientation=$requested, statusBarVisible=$bars; " + "actual $returnedState"
                }) {
                    var restored = false
                    main {
                        val activity =
                            parent.lastActivity as? codes.swistak.batterymonitor.app.BatteryInfoActivity
                        val decor = activity?.window?.decorView
                        val insets = decor?.rootWindowInsets
                        val statusBarVisible = if (insets != null) {
                            WindowInsetsCompat.toWindowInsetsCompat(insets, decor)
                                .isVisible(WindowInsetsCompat.Type.statusBars())
                        } else null
                        returnedState =
                            "activity=${System.identityHashCode(activity)}, " + "finishing=${activity?.isFinishing}, destroyed=${activity?.isDestroyed}, " + "lifecycle=${activity?.lifecycle?.currentState}, focus=${activity?.hasWindowFocus()}, " + "orientation=${activity?.resources?.configuration?.orientation}, " + "requestedOrientation=${activity?.requestedOrientation}, " + "insets=${insets != null}, statusBarVisible=$statusBarVisible"
                        restored =
                            activity != null && !activity.isFinishing && !activity.isDestroyed && activity.lifecycle.currentState == Lifecycle.State.RESUMED && activity.resources.configuration.orientation == orientation && activity.requestedOrientation == requested && statusBarVisible == bars
                    }
                    restored
                }
            }
        } finally {
            main {
                viewer.lastActivity?.takeUnless { it.isDestroyed }?.finish()
                parent.lastActivity?.finish()
            }
            instrumentation.removeMonitor(viewer)
            instrumentation.removeMonitor(parent)
        }
    }

    @Test
    fun actual_activity_recreation_retains_window_independent_chart_state() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        instrumentation.startActivitySync(
            FullscreenChartActivity.intent(
                instrumentation.targetContext, HistoryMetric.LEVEL, range, "custom"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            await { host.lastActivity?.hasWindowFocus() == true && !host.lastActivity.isDestroyed }
            val first = host.lastActivity as FullscreenChartActivity
            lateinit var model: FullscreenChartViewModel
            main {
                model = ViewModelProvider(first)[FullscreenChartViewModel::class.java]
                model.metric(HistoryMetric.VOLTAGE)
                model.comparison(HistoryMetric.TEMPERATURE)
                model.viewport(HistoryRangeState(1000, 3000), 600)
                model.scale(ChartScaleMode.MANUAL)
                model.style(ChartDrawingStyle.POINTS)
            }
            loaded(model)
            instrumentation.waitForIdleSync()
            val before = model.state.value!!
            main { first.recreate() }
            await { host.lastActivity !== first && host.lastActivity?.hasWindowFocus() == true }
            val recreated = host.lastActivity as FullscreenChartActivity
            main {
                instrumentation.sendStatus(0, Bundle().apply {
                    putBoolean("multiWindow", recreated.isInMultiWindowMode)
                    putInt("orientation", recreated.resources.configuration.orientation)
                    putInt("requestedOrientation", recreated.requestedOrientation)
                })
                val restored = ViewModelProvider(recreated)[FullscreenChartViewModel::class.java]
                assertSame(model, restored)
                val after = restored.state.value!!
                assertEquals(before.range, after.range)
                assertEquals(before.rangeLabel, after.rangeLabel)
                assertEquals(before.viewport, after.viewport)
                assertEquals(before.metrics, after.metrics)
                assertEquals(before.scaleMode, after.scaleMode)
                assertEquals(before.style, after.style)
                assertEquals(before.valueScale, after.valueScale)
                assertEquals(before.selectedKey, after.selectedKey)
                if (recreated.isInMultiWindowMode) {
                    assertEquals(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                        recreated.requestedOrientation
                    )
                } else if ((android.os.Build.VERSION.SDK_INT < 36 || recreated.resources.configuration.smallestScreenWidthDp < 600)) {
                    assertEquals(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                        recreated.requestedOrientation
                    )
                    assertEquals(
                        Configuration.ORIENTATION_LANDSCAPE,
                        recreated.resources.configuration.orientation
                    )
                }
            }
        } finally {
            main { host.lastActivity?.finish() }
            instrumentation.removeMonitor(host)
        }
    }

    @Test
    fun touch_pan_keeps_duration_through_finger_down_load_fling_and_reversal() {
        val late = 1_800_000_000_000L
        val hour = 3_600_000L
        val retained = HistoryRangeState(0, late + 48 * hour)
        database.addLogs((0..2880).map { index ->
            observation.copy(time = late - 24 * hour + index * 60_000L, charge = 45 + index % 20)
        } + (0..120).map { index ->
            observation.copy(time = index * 60_000L, charge = 45 + index % 20)
        })
        val model = model(HistoryMetric.LEVEL, retained, "custom")
        val initialViewport = HistoryRangeState(0, hour)
        // Near a history edge, the old moving coordinate window also changed its X span.
        main { model.viewport(initialViewport, 1000) }
        loaded(model)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        instrumentation.startActivitySync(
            FullscreenChartActivity.intent(
                instrumentation.targetContext, HistoryMetric.LEVEL, retained, "custom"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            await {
                val hostActivity = host.lastActivity as? FullscreenChartActivity
                hostActivity != null && !hostActivity.isDestroyed && hostActivity.lifecycle.currentState == Lifecycle.State.RESUMED && (hostActivity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || android.os.Build.VERSION.SDK_INT >= 36 && hostActivity.resources.configuration.smallestScreenWidthDp >= 600 || hostActivity.isInMultiWindowMode)
            }
            val activity = host.lastActivity as FullscreenChartActivity
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
                                    activity::finish,
                                    onViewportObserved = { viewport, width ->
                                        observeFixtureViewport(activity, model, viewport, width)
                                    })
                            }
                        }
                    }
                }
            }
            assertFixtureRendered(
                activity,
                model,
                "Initial touch-pan loading, restoration and width reload",
                initialViewport
            )
            assertFixtureViewport(activity, model, initialViewport)
            lateinit var chart: LineChart
            main { chart = requireNotNull(nativeChart(activity.window.decorView)) }
            val initial = visibleTimes(chart)
            // A real double tap exercises the native zoom recognizer.
            repeat(2) {
                val down = android.os.SystemClock.uptimeMillis()
                touch(chart, down, MotionEvent.ACTION_DOWN, 0.5f)
                Thread.sleep(25)
                touch(chart, down, MotionEvent.ACTION_UP, 0.5f)
                Thread.sleep(50)
            }
            await { visibleTimes(chart).let { it.end - it.start < (initial.end - initial.start) * 0.8 } }
            loaded(model)
            instrumentation.waitForIdleSync()
            Thread.sleep(100)
            val zoomed = visibleTimes(chart)
            val duration = zoomed.end - zoomed.start
            var down = android.os.SystemClock.uptimeMillis()
            touch(chart, down, MotionEvent.ACTION_DOWN, 0.8f)
            for (x in listOf(0.77f, 0.68f, 0.58f)) {
                Thread.sleep(25)
                touch(chart, down, MotionEvent.ACTION_MOVE, x)
            }
            val dragged = visibleTimes(chart)
            assertTrue("Pan must move timestamps forward", dragged.start > zoomed.start)
            assertDuration(chart, duration)
            val beforeLoad = model.state.value!!.chart
            database.addLogs(
                listOf(
                    observation.copy(
                        time = dragged.start + duration / 2, charge = 97
                    )
                )
            )
            main { model.historyChanged() }
            awaitFixtureChart(
                activity, model, "Touch refresh while finger is down; requested=$dragged"
            ) { state, _ ->
                state.chart !== beforeLoad && !state.loading
            }
            Thread.sleep(180) // The finger stays down beyond the query debounce and data preparation.
            main { assertTrue("A pending replacement must not clear the plot", chart.data != null) }
            assertDuration(chart, duration)
            val loadedPosition = visibleTimes(chart)
            touch(chart, down, MotionEvent.ACTION_MOVE, 0.43f)
            assertDuration(chart, duration)
            assertTrue(
                "Dragging must continue after loading",
                visibleTimes(chart).start > loadedPosition.start
            )
            assertTrue(
                "Loading moved a stationary viewport: $dragged to $loadedPosition",
                abs(dragged.start - loadedPosition.start) <= 5
            )
            lateinit var released: HistoryRangeState
            main {
                // Deliver a frame-paced release batch without instrumentation round trips between samples.
                for (x in listOf(0.39f, 0.35f, 0.31f)) {
                    Thread.sleep(16)
                    dispatchTouch(chart, down, MotionEvent.ACTION_MOVE, x)
                }
                released = visibleTimesOnMain(chart)
                dispatchTouch(chart, down, MotionEvent.ACTION_UP, 0.31f)
            }
            repeat(8) {
                Thread.sleep(16)
                assertDuration(chart, duration)
            }
            assertTrue(
                "ACTION_UP must exercise native fling deceleration",
                visibleTimes(chart).start > released.start + 5
            )
            // Reverse direction while native deceleration may still be running.
            val beforeReverse = visibleTimes(chart)
            down = android.os.SystemClock.uptimeMillis()
            touch(chart, down, MotionEvent.ACTION_DOWN, 0.2f)
            for (x in listOf(0.23f, 0.36f, 0.5f, 0.68f)) {
                Thread.sleep(20)
                touch(chart, down, MotionEvent.ACTION_MOVE, x)
                assertDuration(chart, duration)
            }
            assertTrue(
                "Reversal must move timestamps backward",
                visibleTimes(chart).start < beforeReverse.start
            )
            touch(chart, down, MotionEvent.ACTION_UP, 0.68f)
            repeat(40) {
                Thread.sleep(16)
                assertDuration(chart, duration)
            }
            loaded(model)
            main { model.viewport(HistoryRangeState(0, duration), 1000) }
            loaded(model)
            await { visibleTimes(chart).start <= 5 }
            down = android.os.SystemClock.uptimeMillis()
            touch(chart, down, MotionEvent.ACTION_DOWN, 0.2f)
            touch(chart, down, MotionEvent.ACTION_MOVE, 0.3f)
            touch(chart, down, MotionEvent.ACTION_MOVE, 0.8f)
            touch(chart, down, MotionEvent.ACTION_UP, 0.8f)
            assertDuration(chart, duration)
            assertTrue(visibleTimes(chart).start >= 0)
            main { model.viewport(HistoryRangeState(late, late + duration), 1000) }
            loaded(model)
            await { abs(visibleTimes(chart).start - late) <= 5 }
            down = android.os.SystemClock.uptimeMillis()
            touch(chart, down, MotionEvent.ACTION_DOWN, 0.8f)
            for (x in listOf(0.77f, 0.5f, 0.2f)) {
                Thread.sleep(20)
                touch(chart, down, MotionEvent.ACTION_MOVE, x)
                assertDuration(chart, duration)
            }
            assertTrue(
                "A late viewport must keep precise relative timestamps",
                visibleTimes(chart).start > late
            )
            touch(chart, down, MotionEvent.ACTION_UP, 0.2f)
        } finally {
            reportFixtureViewportCallbacks("Touch-pan fixture")
            main { host.lastActivity?.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(host)
        }
    }

    private fun touch(chart: LineChart, down: Long, action: Int, fraction: Float) {
        main { dispatchTouch(chart, down, action, fraction) }
    }

    private fun dispatchTouch(chart: LineChart, down: Long, action: Int, fraction: Float) {
        val rect = chart.viewPortHandler.contentRect
        val event = MotionEvent.obtain(
            down,
            android.os.SystemClock.uptimeMillis(),
            action,
            rect.left + rect.width() * fraction,
            rect.centerY(),
            0
        )
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(chart.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    private fun visibleTimes(chart: LineChart): HistoryRangeState {
        var result = HistoryRangeState(0, 1)
        main { result = visibleTimesOnMain(chart) }
        return result
    }

    private fun visibleTimesOnMain(chart: LineChart): HistoryRangeState {
        val entry = requireNotNull(chart.data).dataSets.filterIsInstance<LineDataSet<*>>()
            .flatMap { it.entries }.first { it.data is HistoryKey }
        val origin = (entry.data as HistoryKey).time - (entry.x.toDouble() * 1000).toLong()
        return HistoryRangeState(
            origin + (chart.lowestVisibleX.toDouble() * 1000).toLong(),
            origin + (chart.highestVisibleX.toDouble() * 1000).toLong()
        )
    }

    private fun assertDuration(chart: LineChart, expected: Long) {
        val actual = visibleTimes(chart)
        // Relative floats round at sub-ms resolution in this hour-wide fixture; allow 5 ms end-to-end.
        assertTrue(
            "X duration changed: expected $expected, actual $actual",
            abs(actual.end - actual.start - expected) <= 5
        )
    }

    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var directory: File
    private lateinit var database: LogDatabase
    private val store = ViewModelStore()

    private val fixtureViewportCallbacks = ArrayDeque<String>()
    private var models = 0

    private val range = HistoryRangeState(0, 10_000)
    private val observation = LogRecord(200, 50, 1_000, 300, 4000, -100_000, 2_500_000)

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory =
            File(target.cacheDir, "fullscreen-state-${System.nanoTime()}").apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this

            override fun registerComponentCallbacks(callback: ComponentCallbacks) {
                baseContext.registerComponentCallbacks(callback)
            }

            override fun unregisterComponentCallbacks(callback: ComponentCallbacks) {
                baseContext.unregisterComponentCallbacks(callback)
            }

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
    fun history_refresh_retains_viewport_and_exact_selection_for_an_unchanged_level_sample() {
        database.addLogs(listOf(observation))
        val model = model(HistoryMetric.LEVEL, range, "custom")
        loaded(model)
        val key = model.state.value!!.chart!!.points.first().key
        main {
            model.viewport(HistoryRangeState(500, 2_500), 600)
            model.select(key)
        }
        loaded(model)
        await { model.state.value!!.selectedRecord?.key == key }
        val viewport = model.state.value!!.viewport
        val previousChart = model.state.value!!.chart
        database.addLogs(listOf(observation.copy(time = 2_000)))
        main {
            model.historyChanged()
            assertSame(previousChart, model.state.value!!.chart)
        }
        await { model.state.value!!.chart?.count == 2L && !model.state.value!!.loading }
        assertEquals(viewport, model.state.value!!.viewport)
        assertEquals(key, model.state.value!!.selectedKey)
        await { model.state.value!!.selectedRecord?.key == key }
        database.deleteHistory(HistoryRangeState(1_000, 1_001))
        main { model.historyChanged() }
        await { model.state.value!!.chart?.count == 1L && !model.state.value!!.loading }
        await { model.state.value!!.selectedRecord == null }
        assertNull(model.state.value!!.selectedKey)
        assertFalse(model.state.value!!.error)
    }

    @Test
    fun history_observation_coalesces_bursts_and_checks_again_after_inactive_changes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val monitor =
            instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        instrumentation.startActivitySync(
            FullscreenChartActivity.intent(target, HistoryMetric.LEVEL, range, "custom")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val activated = AtomicInteger()
        val changed = AtomicInteger()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        try {
            await { monitor.lastActivity != null && !monitor.lastActivity.isDestroyed }
            val activity = monitor.lastActivity as FullscreenChartActivity
            main {
                owner.registry.currentState = Lifecycle.State.RESUMED
                activity.setContent {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        HistoryChangeEffect(
                            onActivated = { activated.incrementAndGet() },
                            onChanged = { changed.incrementAndGet() })
                    }
                }
            }
            await { activated.get() == 1 }
            main {
                repeat(20) {
                    target.sendBroadcast(
                        Intent(LogDatabase.ACTION_HISTORY_CHANGED).setPackage(
                            target.packageName
                        )
                    )
                }
            }
            await { changed.get() == 1 }
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            assertEquals(1, changed.get())
            main { owner.registry.currentState = Lifecycle.State.CREATED }
            target.sendBroadcast(Intent(LogDatabase.ACTION_HISTORY_CHANGED).setPackage(target.packageName))
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            assertEquals(1, changed.get())
            main { owner.registry.currentState = Lifecycle.State.RESUMED }
            await { activated.get() == 2 }
        } finally {
            main {
                owner.registry.currentState = Lifecycle.State.DESTROYED
                monitor.lastActivity?.finish()
            }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(monitor)
        }
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
    fun viewport_load_checks_durable_changes_before_reusing_full_range_bounds() {
        database.addLogs(listOf(observation, observation.copy(time = 2_000, charge = 80)))
        val model = model()
        loaded(model)
        val key = model.state.value!!.chart!!.points.first().key
        main {
            model.select(key)
            model.scale(ChartScaleMode.FIXED)
        }
        await { model.state.value!!.selectedRecord?.key == key }
        assertEquals(50.0, model.state.value!!.bounds!!.min!!, 0.0)

        // No history hint reaches this detached model before its viewport request.
        database.addLogs(listOf(observation.copy(time = 6_000, charge = 0)))
        val viewport = HistoryRangeState(1_000, 3_000)
        main { model.viewport(viewport, 300) }
        loaded(model)
        val state = model.state.value!!
        assertEquals(viewport, state.viewport)
        assertEquals(ChartScaleMode.FIXED, state.scaleMode)
        assertEquals(key, state.selectedKey)
        await { model.state.value!!.selectedRecord?.key == key }
        assertEquals(50.0, state.chart!!.min!!, 0.0)
        assertEquals(0.0, state.bounds!!.min!!, 0.0)
        assertEquals(80.0, state.bounds!!.max!!, 0.0)
        assertEquals(3L, state.bounds!!.count)
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
    fun in_range_voltage_refresh_retains_native_viewport_and_selection_when_y_labels_change() {
        val hour = 3_600_000L
        val start = System.currentTimeMillis() - 3 * 24 * hour
        val fullRange = HistoryRangeState(start, start + 24 * hour)
        database.addLogs((0 until 48).map { index ->
            observation.copy(time = start + index * hour / 2, voltage = 4000)
        })
        val model = model(HistoryMetric.VOLTAGE, fullRange, "custom")
        loaded(model)
        val selected =
            model.state.value!!.charts.getValue(HistoryMetric.VOLTAGE).points.single { it.key.time == start + 10 * hour }.key
        val requested = HistoryRangeState(start + 8 * hour, start + 14 * hour)
        main {
            model.viewport(requested, 1000)
            model.select(selected)
        }
        loaded(model)
        await { model.state.value!!.selectedRecord?.key == selected }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor =
            instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        instrumentation.startActivitySync(
            FullscreenChartActivity.intent(
                instrumentation.targetContext, HistoryMetric.VOLTAGE, fullRange, "custom"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            await {
                val activity = monitor.lastActivity
                activity != null && !activity.isDestroyed && (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || android.os.Build.VERSION.SDK_INT >= 36 && activity.resources.configuration.smallestScreenWidthDp >= 600 || activity.isInMultiWindowMode)
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
                                    activity::finish,
                                    onViewportObserved = { viewport, width ->
                                        observeFixtureViewport(activity, model, viewport, width)
                                    })
                            }
                        }
                    }
                }
            }
            assertFixtureRendered(
                activity, model, "Initial voltage loading, restoration and width reload", requested
            )
            assertFixtureViewport(activity, model, requested)
            var retainedViewport = requested
            var contentLeft = 0f
            var contentWidth = 0f
            var oldAxisMaximum = 0f
            main {
                retainedViewport = model.state.value!!.viewport
                val chart = requireNotNull(nativeChart(activity.window.decorView))
                contentLeft = chart.viewPortHandler.contentRect.left
                contentWidth = chart.viewPortHandler.contentRect.width()
                oldAxisMaximum = chart.axisLeft.axisMaximum
                assertEquals(selected, model.state.value!!.selectedKey)
                val highlight = requireNotNull(chart.highlighted.singleOrNull())
                val set =
                    requireNotNull(chart.data).dataSets[highlight.dataSetIndex] as LineDataSet<*>
                assertEquals(selected, set.entries.single {
                    it.x == highlight.x && it.y == highlight.y
                }.data)
            }
            val inserted = observation.copy(time = start + 11 * hour + 971, voltage = 19_999)
            database.addLogs(listOf(inserted))
            val insertedKey = database.readHistory(
                codes.swistak.batterymonitor.logs.historyQuery(fullRange)
            ) { rows -> rows.single { it.record.time == inserted.time }.key }
            main { model.historyChanged() }
            await {
                val state = model.state.value!!
                !state.loading && state.charts[HistoryMetric.VOLTAGE]?.points?.any { point ->
                    point.key == insertedKey && point.value?.let { abs(it - 19.999) < 0.000001 } == true
                } == true
            }
            assertFixtureRendered(
                activity, model, "Refreshed in-range voltage publication", retainedViewport
            )
            await {
                var updatedGeometry = false
                main {
                    val chart = requireNotNull(nativeChart(activity.window.decorView))
                    val renderedInsertedPoint =
                        chart.data?.dataSets?.filterIsInstance<LineDataSet<*>>()
                            ?.flatMap { it.entries }?.any {
                                it.data == insertedKey && abs(it.y - 19.999f) < 0.0001f
                            } == true
                    updatedGeometry =
                        renderedInsertedPoint && chart.axisLeft.axisMaximum > oldAxisMaximum && (abs(
                            chart.viewPortHandler.contentRect.left - contentLeft
                        ) > 0.5f || abs(chart.viewPortHandler.contentRect.width() - contentWidth) > 0.5f)
                }
                updatedGeometry
            }
            assertFixtureViewport(activity, model, retainedViewport)
            main {
                val state = model.state.value!!
                assertEquals(fullRange, state.range)
                assertEquals("custom", state.rangeLabel)
                assertEquals(HistoryMetric.VOLTAGE, state.metric)
                assertEquals(ChartScaleMode.AUTOMATIC, state.scaleMode)
                assertEquals(selected, state.selectedKey)
                val record = requireNotNull(state.selectedRecord)
                assertEquals(selected, record.key)
                val chart = requireNotNull(nativeChart(activity.window.decorView))
                val highlight = requireNotNull(chart.highlighted.singleOrNull())
                val set =
                    requireNotNull(chart.data).dataSets[highlight.dataSetIndex] as LineDataSet<*>
                val entry = set.entries.single { it.x == highlight.x && it.y == highlight.y }
                assertEquals(
                    "The native highlight must retain the exact selected key", selected, entry.data
                )
                assertEquals(4f, entry.y, 0.0001f)
            }
        } finally {
            reportFixtureViewportCallbacks("Voltage refresh fixture")
            main { monitor.lastActivity?.finish() }
            instrumentation.removeMonitor(monitor)
        }
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
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
                activity != null && !activity.isDestroyed && (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || android.os.Build.VERSION.SDK_INT >= 36 && activity.resources.configuration.smallestScreenWidthDp >= 600 || activity.isInMultiWindowMode)
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
                                    activity::finish,
                                    onViewportObserved = model::observeViewport
                                )
                            }
                        }
                    }
                }
            }
            assertFixtureRendered(activity, model)
            main { model.comparison(HistoryMetric.CURRENT) }
            assertFixtureRendered(activity, model)
            main { model.style(ChartDrawingStyle.POINTS) }
            assertFixtureRendered(activity, model)
            main {
                model.metric(HistoryMetric.TEMPERATURE)
                model.comparison(HistoryMetric.VOLTAGE)
                model.style(ChartDrawingStyle.CONNECTED)
                model.valueScale(ChartValueScale.LOGARITHMIC)
            }
            assertFixtureRendered(activity, model)
            main {
                model.metric(HistoryMetric.LEVEL)
                model.comparison(HistoryMetric.CURRENT)
            }
            assertFixtureRendered(activity, model)
            await { model.state.value!!.valueScale == ChartValueScale.LINEAR }
            main { model.range(fullRange, "all") }
            assertFixtureRendered(activity, model)
            val requestedViewport = HistoryRangeState(samples[95].time, samples[107].time)
            main {
                model.viewport(requestedViewport, 1000)
                model.select(model.state.value!!.charts.getValue(HistoryMetric.LEVEL).points.first {
                    it.value != null && it.key.time > rebootTime
                }.key)
            }
            assertFixtureRendered(activity, model)
            assertFixtureViewport(activity, model, requestedViewport)
            database.addLogs(
                listOf(
                    observation.copy(
                        time = start - 3_600_000L, currentMicroAmps = 100_000
                    )
                )
            )
            main { model.refresh() }
            assertFixtureRendered(activity, model)
            assertEquals(start - 3_600_000L, model.state.value!!.range.start)
            assertFixtureViewport(activity, model, requestedViewport)
            main { model.scale(ChartScaleMode.MANUAL) }
            assertFixtureRendered(activity, model)
            awaitFixtureChart(
                activity, model, "Manual Y configuration was not applied"
            ) { state, chart ->
                state.scaleMode == ChartScaleMode.MANUAL && !state.loading && !state.error && chart.isAttachedToWindow && chart.isLaidOut && !chart.isLayoutRequested && chart.width > 0 && chart.height > 0 && chart.viewPortHandler.contentWidth > 0 && chart.viewPortHandler.contentHeight > 0 && chart.isScaleYEnabled && chart.isDragYEnabled && chart.viewPortHandler.maxScaleY > 1.5f && chart.axisLeft.axisMinimum == 0f && chart.axisLeft.axisMaximum == 100f && chart.axisRight.isEnabled && fixtureViewportMatches(
                    state, chart, requestedViewport
                )
            }
            // Model state can precede the AndroidView update that lifts the native Y-scale limit.
            main {
                requireNotNull(nativeChart(activity.window.decorView)).zoomToCenter(1f, 2f)
            }
            awaitFixtureChart(activity, model, "The single Y zoom was not applied") { _, chart ->
                chart.viewPortHandler.scaleY > 1.5f
            }
            var zoomedScaleY = 0f
            var movedTopY = 0f
            main {
                val chart = requireNotNull(nativeChart(activity.window.decorView))
                zoomedScaleY = chart.viewPortHandler.scaleY
                movedTopY = fullscreenChartTopY(chart) - 8f
                chart.addViewportJob(
                    MoveViewJob.getInstance(
                        chart.viewPortHandler,
                        chart.lowestVisibleX,
                        movedTopY,
                        chart.getTransformer(YAxis.AxisDependency.LEFT),
                        chart
                    )
                )
            }
            awaitFixtureChart(
                activity, model, "The queued vertical move was not applied"
            ) { _, chart ->
                abs(chart.viewPortHandler.scaleY - zoomedScaleY) <= 0.001f && abs(
                    fullscreenChartTopY(chart) - movedTopY
                ) <= 0.01f
            }
            var beforeScaleY = 0f
            var beforeTopY = 0f
            main {
                val chart = requireNotNull(nativeChart(activity.window.decorView))
                beforeScaleY = chart.viewPortHandler.scaleY
                beforeTopY = fullscreenChartTopY(chart)
            }
            assertTrue(
                "Manual Y zoom did not survive the vertical move: ${
                    fixtureChartDiagnostic(
                        activity, model
                    )
                }", beforeScaleY > 1.5f
            )
            val shiftedViewport =
                HistoryRangeState(requestedViewport.start + 60_000, requestedViewport.end + 60_000)
            main { model.viewport(shiftedViewport, 1000) }
            assertFixtureRendered(activity, model)
            assertFixtureViewport(activity, model, shiftedViewport)
            val diagnostic = fixtureChartDiagnostic(activity, model)
            main {
                val chart = requireNotNull(nativeChart(activity.window.decorView))
                assertEquals(
                    "Horizontal viewport change lost manual Y zoom: $diagnostic",
                    beforeScaleY,
                    chart.viewPortHandler.scaleY,
                    0.001f
                )
                assertEquals(
                    "Horizontal viewport change lost manual Y position: $diagnostic",
                    beforeTopY,
                    fullscreenChartTopY(chart),
                    0.01f
                )
            }
            main {
                model.scale(ChartScaleMode.AUTOMATIC)
                model.select(null)
                model.viewport(HistoryRangeState(start, start + 4 * 3_600_000L), 1000)
            }
            assertFixtureRendered(activity, model)
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
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun nativeChart(view: View): LineChart? {
        if (view is LineChart) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) nativeChart(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun assertFixtureRendered(
        activity: FullscreenChartActivity,
        model: FullscreenChartViewModel,
        phase: String = "Native chart rendering",
        requestedViewport: HistoryRangeState? = null
    ) {
        val deadline = System.nanoTime() + 10_000_000_000
        val handler = Handler(Looper.getMainLooper())
        var mismatch = "No completed UI frame yet; requested=$requestedViewport"
        var previousState: FullscreenChartUiState? = null
        var previousGeometry: List<Float>? = null
        var matchingFrames = 0
        while (true) {
            val frameFinished = CountDownLatch(1)
            val completeFrame = Runnable { frameFinished.countDown() }
            val callback = Choreographer.FrameCallback { handler.post(completeFrame) }
            main { Choreographer.getInstance().postFrameCallback(callback) }
            val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
            val frameArrived = try {
                frameFinished.await(remaining, TimeUnit.NANOSECONDS)
            } finally {
                main { Choreographer.getInstance().removeFrameCallback(callback) }
                handler.removeCallbacks(completeFrame)
            }
            if (!frameArrived) throw AssertionError("$phase: UI frame did not complete: $mismatch")
            var rendered = false
            main {
                val state = model.state.value
                val chart = nativeChart(activity.window.decorView)
                val requested = requestedViewport ?: state?.viewport
                if (activity.isDestroyed || activity.isFinishing) {
                    throw AssertionError(
                        "$phase: Chart Activity is finishing or destroyed; requested=$requested; " + fixtureChartDiagnostic(
                            state,
                            chart
                        )
                    )
                }
                if (state?.error == true) {
                    throw AssertionError(
                        "$phase: Chart model failed; requested=$requested; " + fixtureChartDiagnostic(
                            state,
                            chart
                        )
                    )
                }
                val failure = fixtureRenderedMismatch(activity, state, chart)
                val viewportMatches =
                    state != null && chart != null && requested != null && fixtureViewportMatches(
                        state,
                        chart,
                        requested
                    )
                if (failure == null && viewportMatches) {
                    val geometry = listOf(
                        chart.width.toFloat(),
                        chart.height.toFloat(),
                        chart.viewPortHandler.contentLeft,
                        chart.viewPortHandler.contentWidth,
                        chart.lowestVisibleX,
                        chart.highestVisibleX,
                        chart.axisLeft.axisMinimum,
                        chart.axisLeft.axisMaximum,
                        chart.axisRight.axisMinimum,
                        chart.axisRight.axisMaximum
                    )
                    matchingFrames =
                        if (state == previousState && geometry == previousGeometry) matchingFrames + 1 else 1
                    previousState = state
                    previousGeometry = geometry
                    rendered = matchingFrames >= 2
                } else {
                    matchingFrames = 0
                    previousState = null
                    previousGeometry = null
                }
                val stage = when {
                    state == null || state.loading -> "initial loading or width-triggered reload"
                    failure != null -> "layout, fullscreen rendering or dataset publication"
                    !viewportMatches -> "requested viewport restoration mismatch"
                    else -> "awaiting stable UI frames and settled chart width"
                }
                mismatch =
                    "$stage; requested=$requested; stableFrames=$matchingFrames; " + "datasetMismatch=${failure ?: "none"}; ${
                        fixtureChartDiagnostic(
                            state, chart
                        )
                    }"
            }
            if (rendered) return
            if (System.nanoTime() >= deadline) throw AssertionError("$phase: Chart fixture did not settle: $mismatch")
        }
    }

    private fun fixtureRenderedMismatch(
        activity: FullscreenChartActivity, state: FullscreenChartUiState?, chart: LineChart?
    ): String? {
        if (state == null) return "Model state is unavailable"
        if (state.loading) return "Model is still loading"
        if (chart == null) return "Native chart is unavailable"
        if (!chart.isAttachedToWindow || !chart.isLaidOut || chart.isLayoutRequested || chart.width <= 0 || chart.height <= 0 || chart.viewPortHandler.contentWidth <= 0 || chart.viewPortHandler.contentHeight <= 0) return "Native chart is not attached and laid out with a nonempty plot"
        val data = chart.data ?: return "Native chart data is unavailable"
        val sets = data.dataSets.filterIsInstance<LineDataSet<*>>()
        if (sets.size != data.dataSets.size) return "Native chart has unsupported dataset types"
        val expectedMetrics = state.metrics.map { it.name }.toSet()
        val actualMetrics = sets.map { it.label }.toSet()
        if (expectedMetrics != actualMetrics) {
            return "Metric labels expected=$expectedMetrics actual=$actualMetrics"
        }
        if (!chart.axisLeft.isEnabled || chart.axisRight.isEnabled != (state.comparison != null)) {
            return "Axes expected left=true right=${state.comparison != null}, " + "actual left=${chart.axisLeft.isEnabled} right=${chart.axisRight.isEnabled}"
        }
        val valueScale =
            if (state.valueScale == ChartValueScale.LOGARITHMIC && !fullscreenLogAvailable(
                    state.metrics, state.boundsByMetric, false, 1, state.charts
                )
            ) ChartValueScale.LINEAR else state.valueScale
        for (metric in state.metrics) {
            val model = state.charts[metric] ?: return "$metric model is unavailable"
            val segments =
                model.points.filter { it.value != null }.groupBy { it.segment }.entries.toList()
            val metricSets = sets.filter { it.label == metric.name }
            if (segments.size != metricSets.size) {
                val expectedKeys = segments.flatMap { it.value }.map { it.key }
                val actualKeys = metricSets.flatMap { it.entries }.map { it.data }
                return "$metric segment count expected=${segments.size} actual=${metricSets.size}, " + "expected segment IDs=${segments.map { it.key }}, " + "first missing=${expectedKeys.firstOrNull { it !in actualKeys }}, " + "first unexpected=${actualKeys.firstOrNull { it !in expectedKeys }}"
            }
            for (segmentIndex in segments.indices) {
                val (segmentId, points) = segments[segmentIndex]
                val set = metricSets[segmentIndex]
                val entries = set.entries
                if (points.size != entries.size) {
                    val expectedKeys = points.map { it.key }
                    val actualKeys = entries.map { it.data }
                    return "$metric segmentIndex=$segmentIndex segmentId=$segmentId entry count expected=${points.size} actual=${entries.size}, " + "segment count expected=${segments.size} actual=${metricSets.size}, " + "first missing=${expectedKeys.firstOrNull { it !in actualKeys }}, " + "first unexpected=${actualKeys.firstOrNull { it !in expectedKeys }}"
                }
                val expectedAxis =
                    if (metric == state.metric) YAxis.AxisDependency.LEFT else YAxis.AxisDependency.RIGHT
                if (set.axisDependency != expectedAxis) {
                    return "$metric segmentIndex=$segmentIndex segmentId=$segmentId axis expected=$expectedAxis actual=${set.axisDependency}"
                }
                if (!set.isVisible || state.style == ChartDrawingStyle.POINTS && (set.color != Color.TRANSPARENT || !set.isDrawCirclesEnabled) || state.style == ChartDrawingStyle.CONNECTED && set.color == Color.TRANSPARENT) return "$metric segmentIndex=$segmentIndex segmentId=$segmentId does not render style=${state.style}"
                for (index in points.indices) {
                    val point = points[index]
                    val entry = entries[index]
                    if (entry.data != point.key) {
                        return "$metric segmentIndex=$segmentIndex segmentId=$segmentId entry=$index ordered key expected=${point.key} actual=${entry.data}"
                    }
                    val expectedY = fullscreenChartValue(
                        point.value!!, metric, false, 1, valueScale
                    ).toFloat()
                    if (!expectedY.isFinite() || !entry.y.isFinite() || abs(expectedY - entry.y) > 0.0001f) {
                        return "$metric segmentIndex=$segmentIndex segmentId=$segmentId entry=$index key=${point.key} " + "transformed Y expected=$expectedY actual=${entry.y} scale=$valueScale"
                    }
                }
            }
        }
        val decor = activity.window.decorView
        val statusBarHidden = decor.rootWindowInsets?.let {
            !WindowInsetsCompat.toWindowInsetsCompat(it, decor)
                .isVisible(WindowInsetsCompat.Type.statusBars())
        } == true
        return if (statusBarHidden) null else "Fullscreen status bar is still visible or insets are unavailable"
    }

    private fun assertFixtureViewport(
        activity: FullscreenChartActivity,
        model: FullscreenChartViewModel,
        requested: HistoryRangeState
    ) {
        awaitFixtureChart(activity, model, "Requested viewport was not applied") { state, chart ->
            !state.loading && !state.error && fixtureViewportMatches(state, chart, requested)
        }
        main {
            val state = model.state.value!!
            val chart = requireNotNull(nativeChart(activity.window.decorView))
            val entry = requireNotNull(chart.data).dataSets.filterIsInstance<LineDataSet<*>>()
                .flatMap { it.entries }.first { it.data is HistoryKey }
            val origin = (entry.data as HistoryKey).time - (entry.x.toDouble() * 1000).toLong()
            val axis =
                DetailedHistoryTimeAxis(HistoryRangeState(origin.coerceAtLeast(0), state.range.end))
            val precision = maxOf(
                Math.ulp(axis.x(requested.start)),
                Math.ulp(axis.x(requested.end)),
                Math.ulp(chart.xAxis.axisRange)
            )
            // Forward/inverse zoom and move matrices round across the full coordinate span.
            val tolerance = maxOf(5L, kotlin.math.ceil(precision * 8000.0).toLong() + 1)
            assertTrue(
                "Requested start drifted: ${state.viewport} / $requested",
                abs(state.viewport.start - requested.start) <= tolerance
            )
            assertTrue(
                "Requested end drifted: ${state.viewport} / $requested",
                abs(state.viewport.end - requested.end) <= tolerance
            )
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

    private fun fixtureViewportMatches(
        state: FullscreenChartUiState, chart: LineChart, requested: HistoryRangeState
    ): Boolean {
        val entry = chart.data?.dataSets?.filterIsInstance<LineDataSet<*>>()?.flatMap { it.entries }
            ?.firstOrNull { it.data is HistoryKey } ?: return false
        val origin = (entry.data as HistoryKey).time - (entry.x.toDouble() * 1000).toLong()
        val axis = DetailedHistoryTimeAxis(
            HistoryRangeState(origin.coerceAtLeast(0), state.range.end)
        )
        val precision = maxOf(
            Math.ulp(axis.x(requested.start)),
            Math.ulp(axis.x(requested.end)),
            Math.ulp(chart.xAxis.axisRange)
        )
        val tolerance = maxOf(5L, kotlin.math.ceil(precision * 8000.0).toLong() + 1)
        return abs(state.viewport.start - requested.start) <= tolerance && abs(state.viewport.end - requested.end) <= tolerance && abs(
            axis.time(chart.lowestVisibleX) - requested.start
        ) <= tolerance && abs(axis.time(chart.highestVisibleX) - requested.end) <= tolerance
    }

    private fun awaitFixtureChart(
        activity: FullscreenChartActivity,
        model: FullscreenChartViewModel,
        phase: String,
        ready: (FullscreenChartUiState, LineChart) -> Boolean
    ) {
        await({ "$phase: ${fixtureChartDiagnostic(activity, model)}" }) {
            var matches = false
            main {
                val state = model.state.value
                val chart = nativeChart(activity.window.decorView)
                matches = state != null && chart != null && ready(state, chart)
            }
            matches
        }
    }

    private fun observeFixtureViewport(
        activity: FullscreenChartActivity,
        model: FullscreenChartViewModel,
        observed: HistoryRangeState,
        width: Int
    ) {
        val before = model.state.value
        val chart = nativeChart(activity.window.decorView)
        val observation =
            "observed=$observed width=$width; " + "nativeX=${chart?.lowestVisibleX}..${chart?.highestVisibleX}; " + "nativeDataId=${
                System.identityHashCode(chart?.data)
            }; " + fixtureChartDiagnostic(before, chart, includeCallbacks = false)
        model.observeViewport(observed, width)
        val after = model.state.value
        if (fixtureViewportCallbacks.size == 16) fixtureViewportCallbacks.removeFirst()
        fixtureViewportCallbacks.addLast(
            "$observation; afterModelViewport=${after?.viewport}; afterLoading=${after?.loading}"
        )
    }

    private fun reportFixtureViewportCallbacks(phase: String) {
        var trace: List<String> = emptyList()
        main { trace = fixtureViewportCallbacks.toList() }
        trace.forEachIndexed { index, record ->
            android.util.Log.i(
                "FullscreenChartTest", "$phase viewportCallback[$index/${trace.size}]: $record"
            )
        }
    }

    private fun fixtureChartDiagnostic(
        activity: FullscreenChartActivity, model: FullscreenChartViewModel
    ): String {
        var diagnostic = ""
        main {
            diagnostic =
                fixtureChartDiagnostic(model.state.value, nativeChart(activity.window.decorView))
        }
        return diagnostic
    }

    private fun fixtureChartDiagnostic(
        state: FullscreenChartUiState?, chart: LineChart?, includeCallbacks: Boolean = true
    ): String {
        val nativeViewport =
            chart?.data?.dataSets?.filterIsInstance<LineDataSet<*>>()?.flatMap { it.entries }
                ?.firstOrNull { it.data is HistoryKey }?.let { entry ->
                    val origin =
                        (entry.data as HistoryKey).time - (entry.x.toDouble() * 1000).toLong()
                    "${origin + (chart.lowestVisibleX.toDouble() * 1000).toLong()}.." + "${origin + (chart.highestVisibleX.toDouble() * 1000).toLong()}"
                } ?: "unavailable"
        return "mode=${state?.scaleMode}, loading=${state?.loading}, error=${state?.error}, " + "modelViewport=${state?.viewport}, nativeViewport=$nativeViewport, " + "scaleYEnabled=${chart?.isScaleYEnabled}, dragYEnabled=${chart?.isDragYEnabled}, " + "scaleY=${chart?.viewPortHandler?.scaleY}, maxScaleY=${chart?.viewPortHandler?.maxScaleY}, " + "leftY=${chart?.axisLeft?.axisMinimum}..${chart?.axisLeft?.axisMaximum}, " + "rightY=${chart?.axisRight?.axisMinimum}..${chart?.axisRight?.axisMaximum}, " + "topY=${
            chart?.let(
                ::fullscreenChartTopY
            )
        }, " + "chartWidth=${chart?.width}, size=${chart?.width}x${chart?.height}, attached=${chart?.isAttachedToWindow}, " + "laidOut=${chart?.isLaidOut}, layoutRequested=${chart?.isLayoutRequested}" + if (includeCallbacks) "; viewportCallbacks=${
            fixtureViewportCallbacks.joinToString(
                " | "
            )
        }" else ""
    }

    private fun clickFixtureText(text: String): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) {
                return node
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> find(child)?.let { return it } }
            }
            return null
        }

        val bounds = Rect()
        var lookup = "No active accessibility root"
        await({ "Fullscreen navigation action was not available: text='$text'; $lookup" }) {
            val root = automation.rootInActiveWindow
            val node = root?.let(::find)
            val candidate = Rect()
            val windowBounds = Rect()
            root?.getBoundsInScreen(windowBounds)
            node?.getBoundsInScreen(candidate)
            lookup =
                "root=${root != null}, visibleNode=${node != null}, bounds=$candidate, window=$windowBounds"
            val available = node != null && !candidate.isEmpty && windowBounds.contains(
                candidate.centerX(),
                candidate.centerY()
            )
            if (available) bounds.set(candidate)
            available
        }
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

    private fun loaded(model: FullscreenChartViewModel) {
        await { model.state.value?.loading == false }
        assertFalse(model.state.value!!.error)
    }

    private fun await(
        diagnostic: () -> String = { "History loading did not finish" }, ready: () -> Boolean
    ) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (true) {
            if (ready()) return
            if (System.nanoTime() >= deadline) throw AssertionError(diagnostic())
            Thread.sleep(20)
        }
    }
}
