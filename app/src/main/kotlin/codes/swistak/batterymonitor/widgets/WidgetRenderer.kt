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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.common.DurationFormatter
import codes.swistak.batterymonitor.common.operatingStatusLabel
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.monitoring.BatteryCurrent
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.settings.LongDurationFormat
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.TemperatureUnit
import codes.swistak.batterymonitor.ui.components.historyValue
import codes.swistak.batterymonitor.ui.theme.batteryColorScheme
import codes.swistak.batterymonitor.ui.theme.brightness
import codes.swistak.batterymonitor.ui.theme.colorSource
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import kotlin.math.roundToInt

internal object WidgetRenderer {
    fun render(
        context: Context,
        appWidgetId: Int,
        family: WidgetFamily,
        config: WidgetConfiguration,
        snapshot: WidgetSnapshot?,
        widthDp: Int,
        heightDp: Int,
        trend: WidgetTrend?,
        monitoringEnabled: Boolean = snapshot?.monitoringEnabled ?: true,
        chartPixelBudget: Int? = null
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
        val compact = usesCompactLayout(heightDp, widthDp)
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
        val paddingDp = if (compact) 8 else 12
        val topDp = 12
        val shortMeasurements = family == WidgetFamily.MEASUREMENTS && heightDp < 132
        val currentReading = snapshot?.takeIf {
            family == WidgetFamily.STATUS && monitoringEnabled && canShowPrediction(
                it, System.currentTimeMillis()
            )
        }
        val status =
            currentReading?.let { operatingStatusLabel(it.status) }?.let(context::getString)
        val titleWidthDp = widthDp - 2 * paddingDp - 32 - 24
        val title = when (family) {
            WidgetFamily.STATUS -> status?.takeIf { compact }
                ?: context.getString(R.string.nav_battery_group)

            WidgetFamily.MEASUREMENTS -> context.getString(R.string.current_measurements)
            WidgetFamily.TREND -> {
                val metric = context.getString(trendMetricLabel(config.trendMetric))
                val range = context.getString(trendRangeLabel(config.trendHours))
                "$metric · $range".takeIf {
                    compactWidgetTitleFits(context, it, titleWidthDp - 6)
                } ?: metric
            }
        }
        val readingTime = time?.let { context.getString(R.string.widget_read_at, it) }
        val titleWithTime = readingTime?.takeIf { compact && !shortMeasurements }?.let {
            "$title · $it"
        }?.takeIf { compactWidgetTitleFits(context, it, titleWidthDp - 6) }
        val age = listOfNotNull(
            state, readingTime.takeIf { titleWithTime == null }).joinToString(" · ")
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
        views.setTextViewText(R.id.widget_title, titleWithTime ?: title)
        val titleLines = if (titleWithTime != null || family == WidgetFamily.TREND) 2 else 1
        views.setInt(R.id.widget_title, "setMaxLines", titleLines)
        if (family == WidgetFamily.TREND && titleWithTime == null) {
            val titleSize = (12 downTo 8).firstOrNull {
                compactWidgetTitleFits(context, title, titleWidthDp - 6, it.toFloat())
            } ?: 8
            views.setTextViewTextSize(
                R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, titleSize.toFloat()
            )
        }
        views.setViewVisibility(R.id.widget_age, if (age.isEmpty()) View.GONE else View.VISIBLE)
        views.setTextColor(R.id.widget_age, muted)
        views.setTextViewText(R.id.widget_age, age)
        val endDp = if (shortMeasurements) 48 else paddingDp
        val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        views.setViewPadding(
            R.id.widget_content,
            dp(context, if (rtl) endDp else paddingDp),
            dp(context, topDp),
            dp(context, if (rtl) paddingDp else endDp),
            dp(context, paddingDp)
        )
        views.setViewVisibility(
            R.id.widget_header, if (shortMeasurements) View.GONE else View.VISIBLE
        )
        val contentWidthDp = (widthDp - paddingDp - endDp).coerceAtLeast(1)
        val footerLines = if (heightDp >= 172) 2 else 1
        views.setInt(R.id.widget_age, "setMaxLines", footerLines)
        val bodyHeightDp =
            (heightDp - topDp - paddingDp - (if (shortMeasurements) 0 else 36) - (if (age.isEmpty()) 0 else widgetLabelHeightDp(
                context, age, contentWidthDp, 11f, footerLines
            ))).coerceAtLeast(1)
        views.removeAllViews(R.id.widget_body)
        views.setContentDescription(
            R.id.widget_customize, context.getString(R.string.widget_customize)
        )
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            views.setOnClickPendingIntent(
                R.id.widget_root, WidgetActions.open(context, appWidgetId, family, config)
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
                views.setImageViewResource(R.id.widget_family_icon, R.drawable.ui_battery)
                val body = RemoteViews(context.packageName, R.layout.widget_status_content)
                body.setTextColor(R.id.widget_level, foreground)
                body.setTextViewText(
                    R.id.widget_level, styledPercent(context, reading?.levelPercent)
                )
                body.setTextColor(R.id.widget_status, foreground)
                body.setTextViewText(R.id.widget_status, status.orEmpty())
                body.setViewVisibility(
                    R.id.widget_status, if (compact || status == null) View.GONE else View.VISIBLE
                )
                val durationFormat = LongDurationFormat.fromPreference(
                    settings.getString(
                        SettingsContract.KEY_LONG_DURATION_FORMAT, null
                    )
                )
                val prediction = when {
                    currentReading == null -> null
                    currentReading.targetReached && currentReading.predictionTarget != null -> context.getString(
                        targetReachedLabel(currentReading), currentReading.predictionTarget
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
                    R.id.widget_extra, if (extra == null) View.GONE else View.VISIBLE
                )
                val artHeightDp = if (compact) 0 else minOf(
                    bodyHeightDp - 4, ((contentWidthDp - 8) * 0.40f / BATTERY_ASPECT).toInt(), 176
                ).coerceAtLeast(0)
                val showBattery = artHeightDp >= 46
                body.setViewVisibility(
                    R.id.widget_battery_cell, if (showBattery) View.VISIBLE else View.GONE
                )
                if (showBattery) body.setImageViewBitmap(
                    R.id.widget_battery_cell,
                    widgetBatteryBitmap(context, artHeightDp, primary, reading?.levelPercent)
                )
                val readingWidthDp = if (showBattery) {
                    contentWidthDp - (artHeightDp * BATTERY_ASPECT).roundToInt() - 8
                } else contentWidthDp
                val statusHeightDp =
                    if (!compact && status != null) widgetLineHeightDp(context, 12f) else 0
                val extraHeightDp = if (extra != null) widgetLineHeightDp(context, 13f) else 0
                val showPrediction = prediction != null && (!compact || heightDp >= 140)
                val predictionLines =
                    if (bodyHeightDp >= statusHeightDp + extraHeightDp + 2 * widgetLineHeightDp(
                            context, 13f
                        ) + widgetLineHeightDp(
                            context, 34f, medium = true
                        )
                    ) 2 else 1
                body.setInt(R.id.widget_prediction, "setMaxLines", predictionLines)
                val predictionHeightDp = if (showPrediction) widgetLabelHeightDp(
                    context, prediction, readingWidthDp, 13f, predictionLines
                ) else 0
                body.setTextViewTextSize(
                    R.id.widget_level, TypedValue.COMPLEX_UNIT_SP, fitWidgetText(
                        context,
                        styledPercent(context, reading?.levelPercent ?: 100),
                        readingWidthDp,
                        bodyHeightDp - statusHeightDp - extraHeightDp - predictionHeightDp,
                        if (compact) 38f else (bodyHeightDp * 0.46f).coerceIn(34f, 82f),
                        minSp = 16f
                    )
                )
                views.addView(R.id.widget_body, body)
            }

            WidgetFamily.MEASUREMENTS -> {
                views.setImageViewResource(R.id.widget_family_icon, R.drawable.ui_current)
                val body = RemoteViews(context.packageName, R.layout.widget_measurements_content)
                val metrics = config.metrics
                val columns = measurementColumns(contentWidthDp, bodyHeightDp, metrics.size)
                val rows = metrics.chunked(columns)
                val values = metrics.associateWith { metric ->
                    metricValue(context, metric, reading, fahrenheit, multiplier) ?: unavailable
                }
                val contentWidthPx =
                    dp(context, widthDp) - dp(context, paddingDp) - dp(context, endDp)
                val cellPaddingPx = dp(context, 2) + dp(context, 4)
                // Some launcher hosts report more height than their widget view receives.
                val valueHeightPx = dp(context, bodyHeightDp - 18) / rows.size * 45 / 100
                val valueTextSize = rows.minOf { row ->
                    measurementValueTextSize(
                        context,
                        row.map(values::getValue),
                        contentWidthPx / row.size - cellPaddingPx,
                        valueHeightPx,
                        if (compact) 23f else 40f
                    )
                }
                val itemLayout = if (compact) R.layout.widget_measurement_compact_item
                else R.layout.widget_measurement_item
                val rowIds = intArrayOf(
                    R.id.widget_measurement_first_row,
                    R.id.widget_measurement_second_row,
                    R.id.widget_measurement_third_row,
                    R.id.widget_measurement_fourth_row
                )
                for ((index, rowId) in rowIds.withIndex()) {
                    val row = rows.getOrNull(index)
                    body.setViewVisibility(rowId, if (row == null) View.GONE else View.VISIBLE)
                    if (row == null) continue
                    for (metric in row) {
                        val item = RemoteViews(context.packageName, itemLayout)
                        item.setTextViewText(
                            R.id.widget_metric_label, context.getString(metricLabel(metric))
                        )
                        item.setTextViewText(R.id.widget_metric_value, values.getValue(metric))
                        item.setTextViewTextSize(
                            R.id.widget_metric_value, TypedValue.COMPLEX_UNIT_PX, valueTextSize
                        )
                        item.setTextColor(R.id.widget_metric_label, muted)
                        item.setTextColor(R.id.widget_metric_value, foreground)
                        if (appWidgetId > 0 && config.tapAction == WidgetTapAction.MEASUREMENT) {
                            item.setOnClickPendingIntent(
                                R.id.widget_metric_cell,
                                WidgetActions.openMeasurement(context, appWidgetId, metric)
                            )
                        }
                        body.addView(rowId, item)
                    }
                }
                views.addView(R.id.widget_body, body)
            }

            WidgetFamily.TREND -> {
                views.setImageViewResource(R.id.widget_family_icon, R.drawable.ui_history)
                val body = RemoteViews(context.packageName, R.layout.widget_trend_content)
                body.setTextColor(R.id.widget_level, foreground)
                val metric = config.trendMetric
                val levelTrend = metric == HistoryMetric.LEVEL
                val metricTrend = trend?.takeIf { it.metric == metric }
                val locale = context.resources.configuration.locales[0]
                fun historicalValue(value: Double) = when (metric) {
                    HistoryMetric.LEVEL -> percent(context, value.roundToInt())
                    HistoryMetric.TEMPERATURE -> DisplayStrings.formatTemp(
                        (value * 10).roundToInt(), fahrenheit, true, locale
                    )

                    HistoryMetric.VOLTAGE -> DisplayStrings.formatVoltage(
                        (value * 1000).roundToInt(), locale
                    )

                    HistoryMetric.REMAINING_CHARGE -> context.getString(
                        R.string.remaining_charge_value,
                        NumberFormat.getNumberInstance(locale).apply {
                            maximumFractionDigits = 3
                        }.format(value)
                    )

                    else -> historyValue(value, metric, fahrenheit, locale, multiplier)
                }

                val currentValue = when (metric) {
                    HistoryMetric.LEVEL -> styledPercent(context, reading?.levelPercent)
                    HistoryMetric.TEMPERATURE -> reading?.temperatureTenthsCelsius?.div(10.0)
                        ?.let(::historicalValue)

                    HistoryMetric.VOLTAGE -> reading?.voltageMillivolts?.div(1000.0)
                        ?.let(::historicalValue)

                    HistoryMetric.CURRENT -> reading?.currentMicroAmps?.div(1000.0)
                        ?.let(::historicalValue)

                    HistoryMetric.REMAINING_CHARGE -> reading?.remainingChargeMicroampHours?.div(
                        1000.0
                    )?.let(::historicalValue)

                    HistoryMetric.POWER -> BatteryCurrent.powerWatts(
                        reading?.voltageMillivolts, reading?.currentMicroAmps?.div(1000.0)
                    )?.let(::historicalValue)
                } ?: unavailable
                body.setTextViewText(R.id.widget_level, currentValue)
                val maxLevelSp = when {
                    compact || bodyHeightDp < 84 -> 22f
                    bodyHeightDp >= 150 -> 38f
                    else -> 30f
                }
                val levelSp = if (levelTrend) maxLevelSp else fitWidgetText(
                    context,
                    currentValue,
                    (contentWidthDp / 2).coerceAtLeast(1),
                    bodyHeightDp,
                    maxLevelSp,
                    minSp = 11f
                )
                val summaryLines = if (compact || bodyHeightDp < 84) 1 else 2
                body.setTextViewTextSize(R.id.widget_level, TypedValue.COMPLEX_UNIT_SP, levelSp)
                body.setTextColor(R.id.widget_trend_summary, muted)
                body.setInt(R.id.widget_trend_summary, "setLines", summaryLines)
                val invertRange =
                    multiplier < 0 && (metric == HistoryMetric.CURRENT || metric == HistoryMetric.POWER)
                val min =
                    (if (invertRange) metricTrend?.max else metricTrend?.min)?.let(::historicalValue)
                val max =
                    (if (invertRange) metricTrend?.min else metricTrend?.max)?.let(::historicalValue)
                val summary = if (min != null && max != null) {
                    context.getString(R.string.widget_observed_range, min, max)
                } else context.getString(R.string.logs_empty)
                body.setTextViewText(R.id.widget_trend_summary, summary)
                val rowHeightDp = maxOf(
                    widgetLineHeightDp(context, levelSp, medium = true),
                    widgetLineHeightDp(context, 11f) * summaryLines
                )
                val chartHeightDp = bodyHeightDp - rowHeightDp - 4
                val showChart =
                    metricTrend != null && (metricTrend.min != null || metricTrend.events.isNotEmpty()) && chartHeightDp >= 16 && contentWidthDp >= 48
                body.setViewVisibility(
                    R.id.widget_chart, if (showChart) View.VISIBLE else View.GONE
                )
                if (showChart) {
                    body.setImageViewBitmap(
                        R.id.widget_chart, WidgetTrendChart.bitmap(
                            context,
                            metricTrend,
                            dp(context, widthDp) - dp(context, paddingDp) - dp(context, endDp),
                            dp(context, chartHeightDp),
                            config.showYAxisLabels,
                            compact,
                            primary,
                            scheme.outlineVariant.toArgb(),
                            muted,
                            chartPixelBudget ?: WidgetTrendChart.payloadPixelBudget(context),
                            fahrenheit,
                            multiplier
                        )
                    )
                    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                        .withLocale(context.resources.configuration.locales[0])
                        .withZone(ZoneId.systemDefault())
                    val start = formatter.format(Instant.ofEpochMilli(metricTrend.rangeStart))
                    val end = formatter.format(Instant.ofEpochMilli(metricTrend.rangeEnd - 1))
                    val description = if (levelTrend) {
                        context.getString(
                            R.string.widget_chart_description,
                            start,
                            end,
                            min ?: unavailable,
                            max ?: unavailable
                        )
                    } else {
                        context.getString(
                            R.string.history_chart_description_fullscreen,
                            context.getString(trendMetricLabel(metric)),
                            start,
                            end
                        ) + " " + summary
                    }
                    body.setContentDescription(R.id.widget_chart, description)
                }
                views.addView(R.id.widget_body, body)
            }
        }
        return views
    }

    fun preview(
        context: Context,
        family: WidgetFamily,
        config: WidgetConfiguration,
        data: WidgetPreviewData,
        widthDp: Int,
        heightDp: Int
    ): RemoteViews = render(
        context,
        AppWidgetManager.INVALID_APPWIDGET_ID,
        family,
        config,
        data.snapshot,
        widthDp,
        heightDp,
        data.trend,
        data.monitoringEnabled
    )

    internal fun usesCompactLayout(heightDp: Int, widthDp: Int): Boolean =
        heightDp < 148 || widthDp < 140

    internal fun measurementColumns(widthDp: Int, bodyHeightDp: Int, metricCount: Int): Int = when {
        bodyHeightDp < 112 && widthDp >= metricCount * 78 -> metricCount
        widthDp < 155 && bodyHeightDp >= metricCount * 54 -> 1
        else -> 2
    }

    internal fun canShowPrediction(snapshot: WidgetSnapshot?, nowMillis: Long): Boolean =
        snapshot != null && snapshot.monitoringEnabled && snapshot.observedAtMillis > 0 && !snapshot.isStale(
            nowMillis
        )

    internal fun targetReachedLabel(snapshot: WidgetSnapshot): Int =
        if ((snapshot.status == BatteryInfo.STATUS_DISCHARGING || snapshot.status == BatteryInfo.STATUS_UNPLUGGED) && snapshot.predictionTarget != null && snapshot.levelPercent < snapshot.predictionTarget) R.string.current_below_target else R.string.current_target_reached

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
            ) + "\u00A0mA"
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
}
