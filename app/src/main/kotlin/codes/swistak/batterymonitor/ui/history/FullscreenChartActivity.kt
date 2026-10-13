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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme

internal data class FullscreenChartRequest(val metric: HistoryMetric, val range: HistoryRangeState)

internal fun fullscreenChartRequest(
    metric: String?, start: Long, end: Long
): FullscreenChartRequest? {
    val selected = HistoryMetric.entries.firstOrNull { it.name == metric } ?: return null
    if (start !in 0..<end) return null
    return FullscreenChartRequest(selected, HistoryRangeState(start, end))
}

class FullscreenChartActivity : AppCompatActivity() {
    private lateinit var viewModel: FullscreenChartViewModel
    private var observingHistory = false
    private val historyChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (::viewModel.isInitialized) viewModel.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.bi_compose_theme)
        super.onCreate(savedInstanceState)
        val request = fullscreenChartRequest(
            intent.getStringExtra(EXTRA_METRIC),
            intent.getLongExtra(EXTRA_START, -1),
            intent.getLongExtra(EXTRA_END, -1)
        )
        if (request == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        hideStatusBar()
        // Let large screens and multi-window choose their own orientation.
        if (resources.configuration.smallestScreenWidthDp < 600 && !isInMultiWindowMode) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        viewModel = ViewModelProvider(this)[FullscreenChartViewModel::class.java]
        viewModel.initialize(
            request.metric,
            request.range,
            intent.getStringExtra(EXTRA_LABEL) ?: "custom",
            savedInstanceState?.getBundle("chart_state")
        )
        setContent {
            AppBatteryTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
                state?.let {
                    FullscreenChartScreen(
                        state = it,
                        onMetric = viewModel::metric,
                        onComparison = viewModel::comparison,
                        onRange = viewModel::range,
                        onViewport = viewModel::viewport,
                        onScale = viewModel::scale,
                        onStyle = viewModel::style,
                        onValueScale = viewModel::valueScale,
                        onSelect = viewModel::select,
                        onRetry = viewModel::refresh,
                        onBack = ::finish
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::viewModel.isInitialized) {
            ContextCompat.registerReceiver(
                this,
                historyChanged,
                IntentFilter(LogDatabase.ACTION_HISTORY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            observingHistory = true
        }
    }

    override fun onStop() {
        if (observingHistory) {
            unregisterReceiver(historyChanged)
            observingHistory = false
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun hideStatusBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::viewModel.isInitialized) outState.putBundle("chart_state", viewModel.save())
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val EXTRA_METRIC = "chart.metric"
        private const val EXTRA_START = "chart.start"
        private const val EXTRA_END = "chart.end"
        private const val EXTRA_LABEL = "chart.label"

        internal fun intent(
            context: Context, metric: HistoryMetric, range: HistoryRangeState, label: String
        ) = Intent(context, FullscreenChartActivity::class.java).putExtra(EXTRA_METRIC, metric.name)
            .putExtra(EXTRA_START, range.start).putExtra(EXTRA_END, range.end)
            .putExtra(EXTRA_LABEL, label)
    }
}
