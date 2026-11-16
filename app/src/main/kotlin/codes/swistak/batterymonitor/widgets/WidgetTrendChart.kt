/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Display
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import codes.swistak.batterymonitor.logs.HistoryEvent
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistorySeries
import codes.swistak.batterymonitor.ui.components.ConnectedMarkerDash
import codes.swistak.batterymonitor.ui.components.DisconnectedMarkerDash
import codes.swistak.batterymonitor.ui.components.HistoryChartScale
import codes.swistak.batterymonitor.ui.components.PowerMarkerGap
import codes.swistak.batterymonitor.ui.components.RebootMarkerRadius
import codes.swistak.batterymonitor.ui.components.RebootMarkerSpacing
import codes.swistak.batterymonitor.ui.components.chartValue
import codes.swistak.batterymonitor.ui.components.forEachHistoryEventMarker
import codes.swistak.batterymonitor.ui.components.forEachRebootDot
import codes.swistak.batterymonitor.ui.components.historyAxisFormatter
import codes.swistak.batterymonitor.ui.components.historyChartScale
import codes.swistak.batterymonitor.ui.components.historyMarkerTimes
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.math.sqrt

internal object WidgetTrendChart {
    private const val MAX_BITMAP_BYTES = 16 * 1024 * 1024
    private const val MAX_EDGE = 4096.0
    private val cache = object : LruCache<ChartKey, Bitmap>(MAX_BITMAP_BYTES) {
        override fun sizeOf(key: ChartKey, value: Bitmap) = value.allocationByteCount
    }

    @Suppress("DEPRECATION")
    fun payloadPixelBudget(context: Context): Int {
        val screen = Point()
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.getRealSize(screen)
        val metrics = context.resources.displayMetrics
        val width = screen.x.takeIf { it > 0 } ?: metrics.widthPixels
        val height = screen.y.takeIf { it > 0 } ?: metrics.heightPixels
        return (width.toLong() * height).coerceIn(1L, MAX_BITMAP_BYTES / 4L).toInt()
    }

    fun variantPixelBudget(totalPixels: Int, size: WidgetSize, sizes: List<WidgetSize>): Int {
        fun area(bounds: WidgetSize) = bounds.widthDp.toLong() * bounds.heightDp
        return 1 + ((totalPixels - sizes.size).coerceAtLeast(0) * area(size) / sizes.sumOf(::area)).toInt()
    }

    @Synchronized
    fun bitmap(
        context: Context,
        trend: WidgetTrend,
        width: Int,
        height: Int,
        showYAxis: Boolean,
        compact: Boolean,
        lineColor: Int,
        gridColor: Int,
        axisColor: Int,
        pixelBudget: Int,
        fahrenheit: Boolean = false,
        currentMultiplier: Int = 1
    ): Bitmap {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val axisTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, metrics)
        val locale = context.resources.configuration.locales[0]
        val zone = ZoneId.systemDefault()
        // shortcut: extreme multi-variant sizes share an evenly scaled raster; split the
        // axes out if such sizes need full-resolution text within the same budget.
        val longestEdge = maxOf(width, height)
        val scaleFactor = minOf(
            1.0,
            minOf(MAX_EDGE, pixelBudget.toDouble()) / longestEdge,
            sqrt(pixelBudget / (width.toDouble() * height))
        )
        val key = ChartKey(
            trend,
            width,
            height,
            density,
            axisTextSize,
            scaleFactor,
            locale,
            zone,
            showYAxis,
            compact,
            lineColor,
            gridColor,
            axisColor,
            fahrenheit,
            currentMultiplier
        )
        cache.get(key)?.let { return it }
        val bitmap = createBitmap(
            (width * scaleFactor).toInt().coerceAtLeast(1),
            (height * scaleFactor).toInt().coerceAtLeast(1)
        ).apply { this.density = Bitmap.DENSITY_NONE }
        val canvas = Canvas(bitmap).apply { scale(scaleFactor.toFloat(), scaleFactor.toFloat()) }
        val axisPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = axisColor
            textSize = axisTextSize
            textLocale = locale
        }
        val textDirection =
            if (TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL) {
                TextDirectionHeuristics.FIRSTSTRONG_RTL
            } else TextDirectionHeuristics.FIRSTSTRONG_LTR

        fun labelWidth(label: String) = ceil(Layout.getDesiredWidth(label, axisPaint)) + 1f
        fun drawLabel(label: String, left: Float, top: Float) {
            val layout = StaticLayout.Builder.obtain(
                label, 0, label.length, axisPaint, labelWidth(label).toInt()
            ).setTextDirection(textDirection).setIncludePad(false).build()
            canvas.withTranslation(left, top) { layout.draw(this) }
        }

        val lineHeight = axisPaint.fontMetricsInt.let { (it.descent - it.ascent).toFloat() }
        val axisGap = 4 * density
        val yGutterGap = 6 * density
        val edge = 3 * density
        val hasYScale = trend.metric == HistoryMetric.LEVEL || trend.min != null
        val hasYAxis = showYAxis && hasYScale && height >= lineHeight + 2 * edge
        val yInset = if (hasYAxis && !compact) maxOf(edge, lineHeight / 2) else edge
        val scale = if (trend.metric == HistoryMetric.LEVEL && trend.min == null) HistoryChartScale(
            0.0, 100.0, emptyList()
        ) else historyChartScale(
            HistorySeries(emptyList(), trend.min, trend.max),
            trend.metric,
            fahrenheit,
            currentMultiplier
        )
        val numberFormat = NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = if (trend.metric == HistoryMetric.LEVEL) 1 else 3
        }
        val plot = RectF(edge, yInset, width - edge, height - yInset)
        var yLabels = emptyList<Pair<Double, String>>()
        fun layoutPlot(withTimeAxis: Boolean) {
            plot.bottom = height - yInset - if (withTimeAxis) lineHeight + axisGap else 0f
            yLabels = if (!hasYAxis) emptyList() else axisValues(
                scale.minimum,
                scale.maximum,
                compact || plot.height() < lineHeight + axisGap,
                includeMiddle = plot.height() >= 2 * (lineHeight + axisGap)
            ).map { it to numberFormat.format(it) }
            val gutter = yLabels.maxOfOrNull { (_, label) -> labelWidth(label) + yGutterGap } ?: 0f
            if (plot.right - edge - gutter < 16 * density) yLabels = emptyList()
            plot.left = edge + if (yLabels.isEmpty()) 0f else gutter
        }

        val canShowTime = height - 2 * yInset - lineHeight - axisGap >= 16 * density
        layoutPlot(canShowTime)
        val timeLabels = if (canShowTime) {
            val formatter = historyAxisFormatter(trend.rangeEnd - trend.rangeStart, locale, zone)
            timeTicks(
                trend.rangeStart,
                trend.rangeEnd,
                plot.left,
                plot.right,
                axisGap,
                { formatter.format(Instant.ofEpochMilli(it)) },
                ::labelWidth
            )
        } else emptyList()
        if (canShowTime && timeLabels.isEmpty()) layoutPlot(withTimeAxis = false)

        if (plot.width() > 0 && plot.height() > 0) {
            fun x(time: Long) =
                timePosition(time, trend.rangeStart, trend.rangeEnd, plot.left, plot.right)

            fun y(value: Double) =
                plot.top + ((scale.maximum - value) / (scale.maximum - scale.minimum) * plot.height()).toFloat()

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = gridColor
                strokeWidth = density
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(3 * density, 5 * density), 0f)
            }
            if (hasYScale) {
                for (tick in axisValues(scale.minimum, scale.maximum, compact)) {
                    canvas.drawLine(plot.left, y(tick), plot.right, y(tick), paint)
                }
            }

            val shortDashes = DashPathEffect(
                floatArrayOf(ConnectedMarkerDash.value * density, PowerMarkerGap.value * density),
                0f
            )
            val longDashes = DashPathEffect(
                floatArrayOf(
                    DisconnectedMarkerDash.value * density, PowerMarkerGap.value * density
                ), 0f
            )
            val radius = RebootMarkerRadius.value * density
            val spacing = RebootMarkerSpacing.value * density
            paint.color = axisColor
            canvas.withClip(plot) {
                forEachHistoryEventMarker(
                    historyMarkerTimes(trend.events), plot.left, plot.right, radius, density, ::x
                ) { code, markerX ->
                    when (code) {
                        HistoryEvent.REBOOT -> {
                            paint.style = Paint.Style.FILL
                            paint.pathEffect = null
                            forEachRebootDot(plot.top, plot.bottom, radius, spacing) { dotY ->
                                drawCircle(markerX, dotY, radius, paint)
                            }
                        }

                        HistoryEvent.CONNECTED, HistoryEvent.DISCONNECTED -> {
                            paint.style = Paint.Style.STROKE
                            paint.pathEffect =
                                if (code == HistoryEvent.CONNECTED) shortDashes else longDashes
                            drawLine(markerX, plot.top, markerX, plot.bottom, paint)
                        }
                    }
                }
            }
            paint.color = lineColor
            paint.strokeWidth = 2 * density
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.pathEffect = null
            fun valueAt(point: WidgetTrendPoint) = y(
                chartValue(requireNotNull(point.value), trend.metric, fahrenheit, currentMultiplier)
            )
            for (run in runs(trend.points, trend.rangeStart, trend.rangeEnd, trend.metric)) {
                if (run.size == 1) {
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(x(run[0].time), valueAt(run[0]), paint.strokeWidth, paint)
                } else {
                    paint.style = Paint.Style.STROKE
                    val path = Path().apply {
                        moveTo(x(run.first().time), valueAt(run.first()))
                        for (i in 1 until run.size) lineTo(x(run[i].time), valueAt(run[i]))
                    }
                    canvas.drawPath(path, paint)
                }
            }
            for ((value, label) in yLabels) {
                drawLabel(
                    label, plot.left - yGutterGap - labelWidth(label), y(value) - lineHeight / 2
                )
            }
            for ((_, _, label, left) in timeLabels) drawLabel(label, left, plot.bottom + axisGap)
        }
        val result =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) bitmap.asShared() else bitmap
        cache.put(key, result)
        return result
    }

    internal data class TimeTick(
        val time: Long, val x: Float, val label: String, val left: Float, val width: Float
    )

    internal fun timeTicks(
        start: Long,
        end: Long,
        left: Float,
        right: Float,
        gap: Float,
        format: (Long) -> String,
        measure: (String) -> Float
    ): List<TimeTick> {
        for (count in 4 downTo 2) {
            val ticks = List(count) { index ->
                val time =
                    start + ((end - 1 - start).toDouble() * index / (count - 1)).roundToLong()
                val x = timePosition(time, start, end, left, right)
                val label = format(time)
                val width = measure(label)
                val labelLeft = when (index) {
                    0 -> x
                    count - 1 -> x - width
                    else -> x - width / 2
                }
                TimeTick(time, x, label, labelLeft, width)
            }
            val withinPlot = ticks.all { it.left >= left && it.left + it.width <= right }
            val spaced = ticks.zipWithNext().all { (previous, next) ->
                previous.left + previous.width + gap <= next.left
            }
            if (withinPlot && spaced) return ticks
        }
        return emptyList()
    }

    private fun timePosition(time: Long, start: Long, end: Long, left: Float, right: Float): Float =
        left + ((time - start).toDouble() / (end - start).coerceAtLeast(1) * (right - left)).toFloat()

    internal fun axisValues(
        minimum: Double, maximum: Double, compact: Boolean, includeMiddle: Boolean = true
    ): List<Double> = when {
        compact -> listOf((minimum + maximum) / 2)
        includeMiddle -> listOf(maximum, (minimum + maximum) / 2, minimum)
        else -> listOf(maximum, minimum)
    }

    internal fun runs(
        points: List<WidgetTrendPoint>,
        start: Long,
        end: Long,
        metric: HistoryMetric = HistoryMetric.LEVEL
    ): List<List<WidgetTrendPoint>> {
        val runs = mutableListOf<MutableList<WidgetTrendPoint>>()
        var previous: WidgetTrendPoint? = null
        for (point in points) {
            val value = point.value
            if (point.time !in start..end || value == null || !value.isFinite() || (metric == HistoryMetric.LEVEL && value !in 0.0..100.0)) {
                previous = null
                continue
            }
            if (previous == null || previous.segment != point.segment || point.time <= previous.time) {
                runs.add(mutableListOf())
            }
            runs.last().add(point)
            previous = point
        }
        return runs
    }

    private data class ChartKey(
        val trend: WidgetTrend,
        val width: Int,
        val height: Int,
        val density: Float,
        val textSize: Float,
        val scaleFactor: Double,
        val locale: Locale,
        val zone: ZoneId,
        val showYAxis: Boolean,
        val compact: Boolean,
        val lineColor: Int,
        val gridColor: Int,
        val axisColor: Int,
        val fahrenheit: Boolean,
        val currentMultiplier: Int
    )
}
