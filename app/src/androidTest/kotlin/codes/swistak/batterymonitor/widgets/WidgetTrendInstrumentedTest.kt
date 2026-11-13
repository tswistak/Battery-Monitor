/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.HistoryEvent
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.HistoryRecord
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.logs.historyChart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.roundToInt

class WidgetTrendInstrumentedTest {
    private val fixture = WidgetTrend(
        rangeStart = 0,
        rangeEnd = 86_400_000,
        points = listOf(WidgetTrendPoint(600_000, 72.0, 0), WidgetTrendPoint(84_000_000, 48.0, 0)),
        min = 48.0,
        max = 72.0,
        events = emptyList()
    )

    @Test
    fun default_and_enlarged_charts_render_at_display_resolution_in_applied_preview_views() {
        val context = configuredContext(densityDpi = 400)
        val data = WidgetPreviewData(snapshot = null, monitoringEnabled = true, trend = fixture)
        for (size in listOf(WidgetSizes.default(WidgetFamily.TREND), WidgetSize(350, 275))) {
            val views = WidgetRenderer.preview(
                context,
                WidgetFamily.TREND,
                WidgetConfiguration.defaults(WidgetFamily.TREND),
                data,
                size.widthDp,
                size.heightDp
            )
            val chart = applyChart(context, views, size)
            val bitmap = (chart.drawable as BitmapDrawable).bitmap
            val matrix = FloatArray(9).also(chart.imageMatrix::getValues)
            val details =
                "$size ${chart.width}x${chart.height} from ${bitmap.width}x${bitmap.height}"
            assertEquals(Bitmap.DENSITY_NONE, bitmap.density)
            assertTrue(details, chart.width > 0 && chart.height > 0)
            assertTrue(details, matrix[0] in 0.97f..1.03f && matrix[4] in 0.97f..1.03f)
            assertTrue(bitmap.hasAlpha())
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun all_selected_responsive_charts_share_one_payload_budget() {
        val context = configuredContext(densityDpi = 480)
        val options = Bundle().apply {
            putParcelableArrayList(
                AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(
                    SizeF(290f, 210f), SizeF(380f, 300f), SizeF(1000f, 840f), SizeF(840f, 1000f)
                )
            )
        }
        val totalPixels = WidgetTrendChart.payloadPixelBudget(context)
        val variants = mutableMapOf<WidgetSize, RemoteViews>()
        WidgetSizes.remoteViews(WidgetFamily.TREND, options) { size, dimensions ->
            WidgetRenderer.render(
                context,
                AppWidgetManager.INVALID_APPWIDGET_ID,
                WidgetFamily.TREND,
                WidgetConfiguration.defaults(WidgetFamily.TREND),
                null,
                size.widthDp,
                size.heightDp,
                fixture,
                chartPixelBudget = WidgetTrendChart.variantPixelBudget(
                    totalPixels, size, dimensions
                )
            ).also { variants[size] = it }
        }
        val bytes = variants.entries.sumOf { (size, views) ->
            val chart = applyChart(context, views, size)
            (chart.drawable as BitmapDrawable).bitmap.allocationByteCount.toLong()
        }
        assertEquals(4, variants.size)
        assertTrue(
            "Total bitmap allocation: $bytes for $totalPixels pixels", bytes in 1..totalPixels * 4L
        )
    }

    @Test
    fun chart_cache_reuses_equivalent_output_and_refreshes_for_size_density_font_scale_and_theme() {
        val context = configuredContext(densityDpi = 400)
        val axis = 0xff434343.toInt()
        fun bitmap(
            renderContext: Context, width: Int = 600, axisColor: Int = axis, budget: Int = 200_000
        ) = WidgetTrendChart.bitmap(
            renderContext,
            fixture,
            width,
            300,
            showYAxis = true,
            compact = false,
            lineColor = 0xffee8800.toInt(),
            gridColor = 0xff888888.toInt(),
            axisColor = axisColor,
            pixelBudget = budget
        )

        val first = bitmap(context)
        assertSame(first, bitmap(context, budget = 300_000))
        assertEquals(601, bitmap(context, width = 601).width)
        assertNotSame(first, bitmap(configuredContext(densityDpi = 420)))
        assertNotSame(first, bitmap(configuredContext(densityDpi = 400, fontScale = 1.5f)))
        assertNotSame(first, bitmap(context, axisColor = 0xffdddddd.toInt()))
    }

    private fun configuredContext(densityDpi: Int, fontScale: Float = 1f): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            this.densityDpi = densityDpi
            this.fontScale = fontScale
        })
    }

    private fun applyChart(context: Context, views: RemoteViews, size: WidgetSize): ImageView {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val density = context.resources.displayMetrics.density
        val width = (size.widthDp * density).roundToInt()
        val height = (size.heightDp * density).roundToInt()
        lateinit var chart: ImageView
        instrumentation.runOnMainSync {
            val root = views.apply(context, FrameLayout(context))
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, width, height)
            chart = root.findViewById(R.id.widget_chart)
        }
        return chart
    }

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
