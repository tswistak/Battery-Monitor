/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.widgets

import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.HistoryEvent
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.HistoryRecord
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.logs.historyChart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WidgetTrendInstrumentedTest {
    @Test
    fun recorded_events_change_the_widget_bitmap_at_their_timestamps_even_without_level_changes() {
        val start = Instant.parse("2026-11-05T16:34:00Z").toEpochMilli()
        val span = 72 * 3_600_000L
        val range = HistoryRangeState(start, start + span)
        fun row(id: Long, fraction: Int, status: Int, level: Int?) = HistoryRecord(
            id, LogRecord(status, level, start + fraction * span / 100, null, null)
        )

        val history = historyChart(
            range, sequenceOf(
                row(1, 5, 200, 63),
                row(2, 25, 12, null),
                row(3, 40, 200, null),
                row(4, 50, 0, null),
                row(5, 75, -1, null),
                row(6, 95, 200, 55)
            ), metrics = listOf(HistoryMetric.LEVEL)
        )
        val trend = WidgetTrend.fromHistory(history)
        assertEquals(
            listOf(HistoryEvent.CONNECTED, HistoryEvent.DISCONNECTED, HistoryEvent.REBOOT),
            trend.events.map { it.code })
        assertTrue(trend.points.any { it.value == null })
        val withoutEvents = renderedChart(trend.copy(events = emptyList()))
        val withEvents = renderedChart(trend)
        assertEquals(withoutEvents.width, withEvents.width)
        assertEquals(withoutEvents.height, withEvents.height)

        val before = withoutEvents.pixels()
        val after = withEvents.pixels()
        val width = withEvents.width
        val changedColumns =
            before.indices.asSequence().filter { before[it] != after[it] }.map { it % width }
                .toSet()
        val eventFractions = listOf(0.25, 0.50, 0.75)
        for (fraction in eventFractions) {
            assertTrue(changedColumns.any { kotlin.math.abs(it / width.toDouble() - fraction) < 0.02 })
        }
        assertTrue(changedColumns.all { column ->
            eventFractions.any { kotlin.math.abs(column / width.toDouble() - it) < 0.02 }
        })
    }

    private fun renderedChart(trend: WidgetTrend): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val views = WidgetRenderer.render(
            context,
            AppWidgetManager.INVALID_APPWIDGET_ID,
            WidgetFamily.TREND,
            WidgetConfiguration.defaults(WidgetFamily.TREND)
                .copy(trendHours = 72, showYAxisLabels = false),
            snapshot = null,
            widthDp = 350,
            heightDp = 230,
            trend = trend
        )
        var result: Bitmap? = null
        instrumentation.runOnMainSync {
            val root = views.apply(context, FrameLayout(context))
            val chart = root.findViewById<ImageView>(R.id.widget_chart)
            result = (chart.drawable as BitmapDrawable).bitmap
        }
        return requireNotNull(result)
    }

    private fun Bitmap.pixels() = IntArray(width * height).also {
        getPixels(it, 0, width, 0, 0, width, height)
    }
}
