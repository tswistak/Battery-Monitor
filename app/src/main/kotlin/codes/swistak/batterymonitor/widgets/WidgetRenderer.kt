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
package codes.swistak.batterymonitor.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.common.DurationFormatter
import codes.swistak.batterymonitor.monitoring.BackgroundServiceWatchdog
import codes.swistak.batterymonitor.monitoring.BatteryCurrent
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.settings.LongDurationFormat
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.TemperatureUnit
import codes.swistak.batterymonitor.ui.theme.batteryColorScheme
import codes.swistak.batterymonitor.ui.theme.brightness
import codes.swistak.batterymonitor.ui.theme.colorSource
import java.text.NumberFormat
import java.util.Date
import kotlin.math.roundToInt

internal object WidgetRenderer {
    private const val MAX_CHARTS = 4
    private val chartCache = LinkedHashMap<WidgetChartKey, Bitmap>(MAX_CHARTS, 0.75f, true)

    fun render(
        context: Context,
        appWidgetId: Int,
        family: WidgetFamily,
        config: WidgetConfiguration,
        snapshot: WidgetSnapshot?,
        widthDp: Int,
        heightDp: Int,
        trend: WidgetTrend?,
        monitoringEnabled: Boolean = snapshot?.monitoringEnabled ?: true
    ): RemoteViews {
        DisplayStrings.setResources(context.resources)
        val settings =
            context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
        val scheme = batteryColorScheme(
            context, config.colorSource ?: colorSource(
                settings.getString(
                    SettingsContract.KEY_COLOR_SOURCE, null
                )
            ), config.brightness ?: brightness(
                settings.getString(
                    SettingsContract.KEY_BRIGHTNESS, null
                )
            )
        )
        val primary = scheme.primary.toArgb()
        val foreground = if (family == WidgetFamily.STATUS) scheme.onPrimaryContainer.toArgb()
        else scheme.onSurface.toArgb()
        val surface = if (family == WidgetFamily.STATUS) scheme.primaryContainer.toArgb()
        else scheme.surface.toArgb()
        val muted =
            if (family == WidgetFamily.STATUS) foreground else scheme.onSurfaceVariant.toArgb()
        val compact = usesCompactLayout(config.variant, heightDp, widthDp)
        val unavailable = context.getString(R.string.current_unavailable)
        val time = snapshot?.observedAtMillis?.takeIf { it > 0 }?.let {
            DisplayStrings.formatTime(context, Date(it))
        }
        val state = when {
            !monitoringEnabled -> context.getString(R.string.current_monitor_disabled)
            snapshot == null || time == null -> context.getString(R.string.current_waiting)
            snapshot.isStale(System.currentTimeMillis()) -> context.getString(R.string.current_stale)

            else -> null
        }
        val age = listOfNotNull(
            state,
            time?.let { context.getString(R.string.widget_read_at, it) }).joinToString(" · ")
        val views = RemoteViews(context.packageName, R.layout.battery_widget)
        views.setInt(
            R.id.widget_root, "setLayoutDirection", context.resources.configuration.layoutDirection
        )
        views.setInt(R.id.widget_surface, "setColorFilter", surface)
        views.setInt(
            R.id.widget_surface, "setImageAlpha", config.backgroundOpacityPercent * 255 / 100
        )
        views.setInt(R.id.widget_border, "setColorFilter", scheme.outlineVariant.toArgb())
        views.setInt(
            R.id.widget_border, "setImageAlpha", config.backgroundOpacityPercent * 255 / 100
        )
        views.setInt(R.id.widget_customize, "setColorFilter", primary)
        views.setInt(R.id.widget_family_icon, "setColorFilter", primary)
        views.setTextColor(R.id.widget_title, foreground)
        views.setTextColor(R.id.widget_age, muted)
        views.setTextViewText(R.id.widget_age, age)
        val padding = dp(context, if (compact) 10 else 16)
        views.setViewPadding(R.id.widget_content, padding, padding, padding, padding)
        val shortMeasurements = family == WidgetFamily.MEASUREMENTS && heightDp < 120
        views.setViewVisibility(
            R.id.widget_header, if (shortMeasurements) View.GONE else View.VISIBLE
        )
        views.setInt(R.id.widget_age, "setMaxLines", if (shortMeasurements) 1 else 2)
        views.removeAllViews(R.id.widget_body)
        if (shortMeasurements) {
            val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            views.setViewPadding(
                R.id.widget_content,
                if (rtl) dp(context, 48) else padding,
                padding,
                if (rtl) padding else dp(context, 48),
                padding
            )
        }
        views.setViewVisibility(R.id.widget_family_icon, if (compact) View.GONE else View.VISIBLE)
        views.setContentDescription(
            R.id.widget_customize, context.getString(R.string.widget_customize)
        )
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            views.setOnClickPendingIntent(
                R.id.widget_root, WidgetActions.open(context, appWidgetId, config)
            )
            views.setOnClickPendingIntent(
                R.id.widget_customize, WidgetActions.configure(context, appWidgetId)
            )
        }
        val fahrenheit = TemperatureUnit.fromPreference(
            config.temperatureUnit ?: settings.getString(
                SettingsContract.KEY_TEMPERATURE_UNIT,
                context.getString(R.string.default_temperature_unit)
            )
        ).convertToFahrenheit
        val multiplier =
            settings.getString(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "1")?.toIntOrNull()
                ?: 1
        val reading = snapshot?.takeIf { it.observedAtMillis > 0 }
        when (family) {
            WidgetFamily.STATUS -> {
                val currentReading = reading?.takeIf {
                    monitoringEnabled && canShowPrediction(it, System.currentTimeMillis())
                }
                val status = currentReading?.let {
                    context.resources.getStringArray(R.array.statuses).getOrNull(it.status)
                }
                views.setTextViewText(
                    R.id.widget_title,
                    if (compact && status != null) status else context.getString(R.string.nav_battery_group)
                )
                views.setImageViewResource(R.id.widget_family_icon, R.drawable.ui_battery)
                val body = RemoteViews(context.packageName, R.layout.widget_status_content)
                body.setTextColor(R.id.widget_level, foreground)
                body.setTextViewText(
                    R.id.widget_level, styledPercent(context, reading?.levelPercent)
                )
                body.setTextViewTextSize(
                    R.id.widget_level, TypedValue.COMPLEX_UNIT_SP, if (compact) 38f else 54f
                )
                body.setTextColor(R.id.widget_status, foreground)
                body.setTextViewText(R.id.widget_status, status.orEmpty())
                body.setViewVisibility(
                    R.id.widget_status, if (compact || status == null) View.GONE else View.VISIBLE
                )
                body.setViewVisibility(
                    R.id.widget_battery_cell, if (compact) View.GONE else View.VISIBLE
                )
                for (id in intArrayOf(
                    R.id.widget_battery_terminal,
                    R.id.widget_battery_outline,
                    R.id.widget_battery_fill
                )) {
                    body.setInt(id, "setColorFilter", primary)
                }
                body.setInt(
                    R.id.widget_battery_fill,
                    "setImageLevel",
                    (reading?.levelPercent ?: 0).coerceIn(0, 100) * 100
                )
                val durationFormat = LongDurationFormat.fromPreference(
                    settings.getString(
                        SettingsContract.KEY_LONG_DURATION_FORMAT, null
                    )
                )
                val prediction = when {
                    currentReading == null -> null
                    currentReading.targetReached && currentReading.predictionTarget != null -> context.getString(
                        if (currentReading.status == BatteryInfo.STATUS_CHARGING) R.string.current_target_reached else R.string.current_below_target,
                        currentReading.predictionTarget
                    )

                    currentReading.predictionMinutes != null && currentReading.predictionTarget != null -> "≈" + DurationFormatter.formatShort(
                        context.resources, currentReading.predictionMinutes, durationFormat
                    ) + " " + context.getString(
                        R.string.activity_until_target, currentReading.predictionTarget
                    )

                    else -> context.getString(R.string.current_no_prediction)
                }
                body.setTextColor(R.id.widget_prediction, foreground)
                body.setTextViewText(R.id.widget_prediction, prediction.orEmpty())
                body.setViewVisibility(
                    R.id.widget_prediction,
                    if (prediction == null || compact && heightDp < 140) View.GONE else View.VISIBLE
                )
                val extra = when (config.statusExtra) {
                    WidgetStatusExtra.NONE -> null
                    WidgetStatusExtra.TEMPERATURE -> currentReading?.let {
                        DisplayStrings.formatTemp(
                            it.temperatureTenthsCelsius, fahrenheit
                        )
                    }

                    WidgetStatusExtra.REMAINING_CHARGE -> currentReading?.remainingChargeMicroampHours?.let {
                        context.getString(
                            R.string.remaining_charge_value, DisplayStrings.formatChargeCompact(it)
                        )
                    }
                }
                body.setTextColor(R.id.widget_extra, muted)
                body.setTextViewText(R.id.widget_extra, extra.orEmpty())
                body.setViewVisibility(
                    R.id.widget_extra, if (compact || extra == null) View.GONE else View.VISIBLE
                )
                views.addView(R.id.widget_body, body)
            }

            WidgetFamily.MEASUREMENTS -> {
                views.setTextViewText(
                    R.id.widget_title, context.getString(R.string.current_measurements)
                )
                views.setImageViewResource(R.id.widget_family_icon, R.drawable.ui_current)
                val body = RemoteViews(context.packageName, R.layout.widget_measurements_content)
                val metrics = visibleMetrics(config.metrics, widthDp, heightDp)
                val columns = measurementColumns(widthDp, heightDp, compact, metrics.size)
                val rows = metrics.chunked(columns)
                for ((index, row) in rows.withIndex()) {
                    val rowId =
                        if (index == 0) R.id.widget_measurement_first_row else R.id.widget_measurement_second_row
                    for (metric in row) {
                        val item =
                            RemoteViews(context.packageName, R.layout.widget_measurement_item)
                        item.setTextViewText(
                            R.id.widget_metric_label, context.getString(metricLabel(metric))
                        )
                        item.setTextColor(R.id.widget_metric_label, muted)
                        item.setTextColor(R.id.widget_metric_value, foreground)
                        item.setTextViewText(
                            R.id.widget_metric_value,
                            metricValue(context, metric, reading, fahrenheit, multiplier)
                                ?: unavailable
                        )
                        item.setTextViewTextSize(
                            R.id.widget_metric_value,
                            TypedValue.COMPLEX_UNIT_SP,
                            if (shortMeasurements) 16f else if ((widthDp - if (compact) 20 else 32) / columns < 100) 18f else 21f
                        )
                        if (rows.size > 1 && heightDp < 190) item.setInt(
                            R.id.widget_metric_label, "setMaxLines", 1
                        )
                        body.addView(rowId, item)
                    }
                }
                body.setViewVisibility(
                    R.id.widget_measurement_second_row,
                    if (rows.size > 1) View.VISIBLE else View.GONE
                )
                views.addView(R.id.widget_body, body)
            }

            WidgetFamily.TREND -> {
                views.setTextViewText(
                    R.id.widget_title,
                    context.getString(R.string.widget_trend_range, config.trendHours)
                )
                views.setImageViewResource(R.id.widget_family_icon, R.drawable.ui_history)
                val body = RemoteViews(context.packageName, R.layout.widget_trend_content)
                body.setTextColor(R.id.widget_level, foreground)
                body.setTextViewText(
                    R.id.widget_level, styledPercent(context, reading?.levelPercent)
                )
                body.setTextViewTextSize(
                    R.id.widget_level, TypedValue.COMPLEX_UNIT_SP, if (compact) 32f else 42f
                )
                body.setTextColor(R.id.widget_trend_summary, muted)
                val hasHistory = trend?.points?.any { it.value != null } == true
                val summary = if (trend?.min != null && trend.max != null) context.getString(
                    R.string.widget_observed_range,
                    percent(context, trend.min.roundToInt()),
                    percent(context, trend.max.roundToInt())
                ) else context.getString(R.string.logs_empty)
                body.setTextViewText(R.id.widget_trend_summary, summary)
                body.setViewVisibility(
                    R.id.widget_chart, if (hasHistory) View.VISIBLE else View.GONE
                )
                if (trend != null && hasHistory) {
                    val chartWidth =
                        dp(context, (widthDp - 32).coerceAtLeast(100)).coerceAtMost(512)
                    val chartHeight = dp(
                        context, (heightDp - if (compact) 100 else 130).coerceIn(32, 120)
                    ).coerceAtMost(256)
                    body.setImageViewBitmap(
                        R.id.widget_chart, chartBitmap(
                            trend, chartWidth, chartHeight, primary, scheme.outlineVariant.toArgb()
                        )
                    )
                    val start = DisplayStrings.formatTime(context, Date(trend.rangeStart))
                    val end = DisplayStrings.formatTime(context, Date(trend.rangeEnd))
                    body.setTextViewText(R.id.widget_chart_start, start)
                    body.setTextViewText(R.id.widget_chart_end, end)
                    body.setTextColor(R.id.widget_chart_start, muted)
                    body.setTextColor(R.id.widget_chart_end, muted)
                    body.setContentDescription(
                        R.id.widget_chart,
                        context.getString(
                            R.string.widget_chart_description,
                            start,
                            end,
                            trend.min?.let { percent(context, it.roundToInt()) } ?: unavailable,
                            trend.max?.let { percent(context, it.roundToInt()) } ?: unavailable))
                }
                body.setViewVisibility(
                    R.id.widget_chart_axis, if (hasHistory && !compact) View.VISIBLE else View.GONE
                )
                body.setViewVisibility(
                    R.id.widget_trend_summary,
                    if (compact && hasHistory) View.GONE else View.VISIBLE
                )
                views.addView(R.id.widget_body, body)
            }
        }
        return views
    }

    fun preview(context: Context, family: WidgetFamily, config: WidgetConfiguration): RemoteViews =
        render(
            context,
            AppWidgetManager.INVALID_APPWIDGET_ID,
            family,
            config,
            WidgetSnapshotStore.read(context),
            if (family == WidgetFamily.STATUS) 220 else 350,
            230,
            if (family == WidgetFamily.TREND) WidgetUpdateDispatcher.cachedTrend(config.trendHours) else null,
            BackgroundServiceWatchdog.isServiceDesired(context)
        )

    internal fun usesCompactLayout(
        variant: WidgetVariant, heightDp: Int, widthDp: Int = Int.MAX_VALUE
    ): Boolean = variant == WidgetVariant.COMPACT || heightDp < 190 || widthDp < 180

    internal fun measurementColumns(
        widthDp: Int, heightDp: Int, compact: Boolean, metricCount: Int
    ): Int = if (compact && widthDp >= metricCount * 90 && heightDp < 190) metricCount else 2

    internal fun visibleMetrics(
        metrics: List<WidgetMetric>, widthDp: Int, heightDp: Int
    ): List<WidgetMetric> =
        if (heightDp < 160 && widthDp < metrics.size * 90) metrics.take(2) else metrics

    internal fun canShowPrediction(snapshot: WidgetSnapshot?, nowMillis: Long): Boolean =
        snapshot != null && snapshot.monitoringEnabled && snapshot.observedAtMillis > 0 && !snapshot.isStale(
            nowMillis
        )

    private fun metricLabel(metric: WidgetMetric): Int = when (metric) {
        WidgetMetric.TEMPERATURE -> R.string.current_temperature
        WidgetMetric.VOLTAGE -> R.string.current_voltage
        WidgetMetric.CURRENT -> R.string.pref_cat_battery_current_main
        WidgetMetric.REMAINING_CHARGE -> R.string.remaining_charge
    }

    private fun metricValue(
        context: Context,
        metric: WidgetMetric,
        snapshot: WidgetSnapshot?,
        fahrenheit: Boolean,
        multiplier: Int
    ): String? = when (metric) {
        WidgetMetric.TEMPERATURE -> snapshot?.let {
            DisplayStrings.formatTemp(
                it.temperatureTenthsCelsius, fahrenheit
            )
        }

        WidgetMetric.VOLTAGE -> snapshot?.voltageMillivolts?.let(DisplayStrings::formatVoltage)
        WidgetMetric.CURRENT -> snapshot?.currentMicroAmps?.let {
            BatteryCurrent.formatMilliAmps(
                BatteryCurrent.scaleMicroAmps(it, multiplier),
                context.resources.configuration.locales[0]
            ) + " mA"
        }

        WidgetMetric.REMAINING_CHARGE -> snapshot?.remainingChargeMicroampHours?.let {
            context.getString(
                R.string.remaining_charge_value, DisplayStrings.formatChargeCompact(it)
            )
        }
    }

    private fun percent(context: Context, level: Int): String = NumberFormat.getPercentInstance(
        context.resources.configuration.locales[0]
    ).format(level.coerceIn(0, 100) / 100.0)

    private fun styledPercent(context: Context, level: Int?): CharSequence {
        if (level == null || level !in 0..100) return "—"
        return SpannableString(percent(context, level)).apply {
            for (index in indices) if (!this[index].isDigit()) {
                setSpan(RelativeSizeSpan(0.45f), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    @Synchronized
    private fun chartBitmap(
        trend: WidgetTrend, width: Int, height: Int, line: Int, grid: Int
    ): Bitmap {
        val key = WidgetChartKey(
            trend.version, trend.rangeStart, trend.rangeEnd, width, height, line, grid
        )
        chartCache[key]?.let { return it }
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = grid
            strokeWidth = 1f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
        }
        canvas.drawLine(4f, height - 5f, width - 4f, height - 5f, paint)
        paint.color = line
        paint.strokeWidth = (width / 160f).coerceIn(2f, 4f)
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.pathEffect = null
        val start = trend.rangeStart
        val span = (trend.rangeEnd - start).coerceAtLeast(1)
        val minimum = (trend.min ?: 0.0).coerceIn(0.0, 100.0)
        val maximum = (trend.max ?: 100.0).coerceIn(minimum, 100.0)
        val lower = (minimum - 5).coerceAtLeast(0.0)
        val upper = (maximum + 5).coerceAtMost(100.0).coerceAtLeast(lower + 1)
        fun x(point: WidgetTrendPoint): Float =
            (4 + (point.time - start).toDouble() / span * (width - 8)).toFloat()

        fun y(point: WidgetTrendPoint): Float =
            (4 + (upper - point.value!!) / (upper - lower) * (height - 12)).toFloat()
        for (run in chartRuns(trend.points, start, trend.rangeEnd)) {
            if (run.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x(run[0]), y(run[0]), paint.strokeWidth, paint)
                paint.style = Paint.Style.STROKE
            } else {
                val path = Path()
                path.moveTo(x(run.first()), y(run.first()))
                run.drop(1).forEach { path.lineTo(x(it), y(it)) }
                canvas.drawPath(path, paint)
            }
        }
        chartCache[key] = bitmap
        if (chartCache.size > MAX_CHARTS) chartCache.remove(chartCache.keys.first())
        return bitmap
    }

    internal fun chartRuns(
        points: List<WidgetTrendPoint>, start: Long, end: Long
    ): List<List<WidgetTrendPoint>> {
        val runs = mutableListOf<MutableList<WidgetTrendPoint>>()
        var previous: WidgetTrendPoint? = null
        for (point in points) {
            if (point.time !in start..end || point.value == null || !point.value.isFinite() || point.value !in 0.0..100.0) {
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
}

private data class WidgetChartKey(
    val version: Long,
    val start: Long,
    val end: Long,
    val width: Int,
    val height: Int,
    val line: Int,
    val grid: Int
)
