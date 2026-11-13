/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.widgets

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.util.TypedValueCompat
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.monitoring.BatteryCurrent
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.settings.SettingsContract
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.roundToInt

class WidgetMeasurementsInstrumentedTest {
    @Test
    fun complete_readings_and_labels_fit_measured_cells_after_resize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        var result: Result<List<String>>? = null
        instrumentation.runOnMainSync {
            result = runCatching {
                try {
                    buildList {
                        addAll(inspect(base, "pl", 1f, 190, 206, 4))
                        addAll(inspect(base, "pl", 1f, 342, 206, 4))
                        addAll(inspect(base, "pl", 1.5f, 190, 206, 4))
                        addAll(inspect(base, "pl", 1.5f, 190, 150, 3))
                        addAll(inspect(base, "en", 1.5f, 342, 150, 2))
                        addAll(inspect(base, "ar", 1.5f, 190, 206, 4))
                        addAll(inspect(base, "pl", 1f, 190, 206, 4, WidgetVariant.COMPACT))
                    }
                } finally {
                    DisplayStrings.setResources(base.resources)
                }
            }
        }
        val failures = requireNotNull(result).getOrThrow()
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun inspect(
        base: Context,
        language: String,
        fontScale: Float,
        widthDp: Int,
        heightDp: Int,
        count: Int,
        variant: WidgetVariant = WidgetVariant.ADAPTIVE
    ): List<String> = buildList {
        val locale = Locale.forLanguageTag(language)
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            this.fontScale = fontScale
        }
        val context = base.createConfigurationContext(configuration)
        val metrics = WidgetMetric.entries.reversed().take(count)
        val config = WidgetConfiguration.defaults(WidgetFamily.MEASUREMENTS).copy(
            metricOrder = WidgetMetric.entries.reversed(),
            selectedMetrics = metrics.toSet(),
            temperatureUnit = WidgetConfiguration.TEMPERATURE_CELSIUS,
            variant = variant
        )
        val snapshot = WidgetSnapshot(
            45,
            BatteryInfo.STATUS_DISCHARGING,
            327,
            3764,
            -62_000,
            2_804_000,
            null,
            null,
            false,
            System.currentTimeMillis()
        )
        val dm = context.resources.displayMetrics
        val size =
            "$language ${widthDp}x$heightDp dp density=${dm.density} fontScale=$fontScale ($count, $variant)"
        val width = (widthDp * dm.density).roundToInt()
        val height = (heightDp * dm.density).roundToInt()
        val root = WidgetRenderer.render(
            context, 123, WidgetFamily.MEASUREMENTS, config, snapshot, widthDp, heightDp, null
        ).apply(context, FrameLayout(context)) as ViewGroup
        root.measure(exact(width), exact(height))
        root.layout(0, 0, width, height)
        val body = root.findViewById<ViewGroup>(R.id.widget_body).getChildAt(0) as ViewGroup
        val rows = (0 until body.childCount).map { body.getChildAt(it) as ViewGroup }
            .filter { it.visibility == View.VISIBLE }
        val cells = rows.flatMap { row -> (0 until row.childCount).map(row::getChildAt) }
        val content = root.findViewById<View>(R.id.widget_content)
        Log.i(
            TAG,
            "$size root=${root.bounds()} content=${content.bounds()} " + "padding=${content.paddingStart}/${content.paddingEnd} body=${body.bounds()} " + "rows=${rows.map { it.bounds() }}"
        )
        if (cells.size != metrics.size) add("$size: expected ${metrics.size} cells, got ${cells.size}")
        val valueSizes =
            cells.map { it.findViewById<TextView>(R.id.widget_metric_value).textSize }.toSet()
        if (valueSizes.size != 1) add("$size: readings use different font sizes in pixels: $valueSizes")
        for ((cell, metric) in cells.zip(metrics)) {
            val label = cell.findViewById<TextView>(R.id.widget_metric_label)
            val value = cell.findViewById<TextView>(R.id.widget_metric_value)
            val expectedLabel = context.getString(
                when (metric) {
                    WidgetMetric.TEMPERATURE -> R.string.current_temperature
                    WidgetMetric.VOLTAGE -> R.string.current_voltage
                    WidgetMetric.CURRENT -> R.string.pref_cat_battery_current_main
                    WidgetMetric.REMAINING_CHARGE -> R.string.remaining_charge
                }
            )
            val multiplier =
                context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
                    .getString(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "1")?.toIntOrNull()
                    ?: 1
            val expectedValue = when (metric) {
                WidgetMetric.TEMPERATURE -> DisplayStrings.formatTemp(
                    327, convertF = false, includeTenths = true, locale = locale
                )

                WidgetMetric.VOLTAGE -> DisplayStrings.formatVoltage(3764, locale)
                WidgetMetric.CURRENT -> BatteryCurrent.formatMilliAmps(
                    -62.0 * multiplier, locale
                ) + "\u00a0mA"

                WidgetMetric.REMAINING_CHARGE -> context.getString(
                    R.string.remaining_charge_value,
                    DisplayStrings.formatChargeCompact(2_804_000, locale)
                )
            }
            if (!cell.hasOnClickListeners()) add("$size $metric: lost its measurement click action")
            for ((text, expected, allowedLines) in listOf(
                Triple(label, expectedLabel, 3), Triple(value, expectedValue, 1)
            )) {
                val layout = text.layout
                val paddingWidth = text.totalPaddingStart + text.totalPaddingEnd
                val paddingHeight = text.totalPaddingTop + text.totalPaddingBottom
                val lineEnds = (0 until layout.lineCount).map(layout::getLineEnd)
                val ellipses = (0 until layout.lineCount).map(layout::getEllipsisCount)
                val sizes =
                    "cell=${cell.bounds()} padding=${cell.paddingStart}/${cell.paddingEnd} " + "text=${text.bounds()} padding=${text.totalPaddingStart}/${text.totalPaddingEnd} " + "font=${text.typeface} size=${text.textSize}px/${
                        TypedValueCompat.pxToSp(
                            text.textSize, dm
                        )
                    }sp " + "lines=$lineEnds ellipsis=$ellipses layout=${layout.width}x${layout.height} string=${text.text}"
                Log.i(TAG, "$size $metric $sizes")
                if (text.text.toString() != expected || layout.lineCount !in 1..allowedLines || lineEnds.lastOrNull() != text.text.length || ellipses.any { it != 0 } || layout.height > text.height - paddingHeight || (0 until layout.lineCount).any {
                        layout.getLineMax(
                            it
                        ) > text.width - paddingWidth + 1f
                    } || text.left < cell.paddingLeft || text.right > cell.width - cell.paddingRight || text.top < 0 || text.bottom > cell.height) add(
                    "$size $metric did not fit $expected: $sizes"
                )
            }
            if (label.bottom > value.top) add("$size $metric: value overlaps its label")
        }
    }

    private fun View.bounds(): String = "[$left,$top..$right,$bottom]"

    private fun exact(pixels: Int) =
        View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY)

    private companion object {
        const val TAG = "WidgetMeasurementsTest"
    }
}
