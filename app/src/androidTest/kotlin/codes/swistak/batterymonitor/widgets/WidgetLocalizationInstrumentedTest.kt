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
import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date
import java.util.Locale

class WidgetLocalizationInstrumentedTest {
    @Test
    fun status_widget_uses_operating_state_instead_of_connection_event_in_both_layouts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val snapshot = WidgetSnapshot(
            45,
            BatteryInfo.STATUS_UNPLUGGED,
            327,
            3764,
            -62_000L,
            2_804_000L,
            null,
            null,
            false,
            System.currentTimeMillis()
        )
        val cases = mapOf(
            BatteryInfo.STATUS_UNPLUGGED to R.string.status_discharging,
            BatteryInfo.STATUS_DISCHARGING to R.string.status_discharging,
            BatteryInfo.STATUS_CHARGING to R.string.status_charging,
            BatteryInfo.STATUS_NOT_CHARGING to R.string.status_not_charging,
            BatteryInfo.STATUS_FULLY_CHARGED to R.string.status_fully_charged,
            BatteryInfo.STATUS_UNKNOWN to R.string.status_unknown
        )
        for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("pl"))) {
            val context = base.createConfigurationContext(
                Configuration(base.resources.configuration).apply { setLocale(locale) })
            var result: Result<Unit>? = null
            instrumentation.runOnMainSync {
                result = runCatching {
                    fun render(
                        reading: WidgetSnapshot,
                        height: Int,
                        width: Int = 230,
                        extra: WidgetStatusExtra = WidgetStatusExtra.TEMPERATURE
                    ) = WidgetRenderer.render(
                        context,
                        AppWidgetManager.INVALID_APPWIDGET_ID,
                        WidgetFamily.STATUS,
                        WidgetConfiguration.defaults(WidgetFamily.STATUS).copy(
                            statusExtra = extra,
                            variant = if (height < 148) WidgetVariant.COMPACT else WidgetVariant.ADAPTIVE
                        ),
                        reading,
                        widthDp = width,
                        heightDp = height,
                        trend = null
                    ).apply(context, FrameLayout(context))
                    try {
                        for ((state, label) in cases) {
                            val reading = snapshot.copy(status = state)
                            val normal =
                                render(reading, 220).findViewById<TextView>(R.id.widget_status)
                            val compact =
                                render(reading, 115).findViewById<TextView>(R.id.widget_title)
                            assertEquals(context.getString(label), normal.text.toString())
                            assertEquals(
                                context.getString(label),
                                compact.text.toString().substringBefore(" · ")
                            )
                        }
                        for (reading in listOf(
                            snapshot.copy(monitoringEnabled = false),
                            snapshot.copy(observedAtMillis = snapshot.observedAtMillis - 16 * 60_000)
                        )) {
                            val status =
                                render(reading, 220).findViewById<TextView>(R.id.widget_status)
                            assertEquals(View.GONE, status.visibility)
                        }
                        val time =
                            DisplayStrings.formatTime(context, Date(snapshot.observedAtMillis))
                        val compact = render(
                            snapshot, 135, width = 380, extra = WidgetStatusExtra.REMAINING_CHARGE
                        )
                        val title = compact.findViewById<TextView>(R.id.widget_title)
                        val age = compact.findViewById<TextView>(R.id.widget_age)
                        val extra = compact.findViewById<TextView>(R.id.widget_extra)
                        assertEquals(
                            context.getString(R.string.status_discharging) + " · " + context.getString(
                                R.string.widget_read_at, time
                            ), title.text.toString()
                        )
                        assertEquals(View.GONE, age.visibility)
                        assertEquals(View.VISIBLE, extra.visibility)
                        val disabled =
                            render(snapshot.copy(monitoringEnabled = false), 135, width = 380)
                        assertEquals(
                            View.VISIBLE, disabled.findViewById<View>(R.id.widget_age).visibility
                        )
                        assertEquals(
                            View.GONE, disabled.findViewById<View>(R.id.widget_extra).visibility
                        )
                    } finally {
                        DisplayStrings.setResources(base.resources)
                    }
                }
            }
            requireNotNull(result).getOrThrow()
        }
    }

    @Test
    fun level_trend_title_includes_the_localized_metric_name_and_configured_duration() {
        for ((hours, label) in listOf(
            24 to R.string.history_last_24h,
            72 to R.string.history_last_3d,
            168 to R.string.history_last_7d
        )) {
            assertEquals(label, trendRangeLabel(hours))
            for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("pl"))) {
                assertTrendTitle(hours, locale, 350, rangeLabel = label)
            }
        }
        assertTrendTitle(24, Locale.forLanguageTag("pl"), 180)
    }

    private fun assertTrendTitle(
        hours: Int, locale: Locale, widthDp: Int, rangeLabel: Int? = null
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val configuration = Configuration(base.resources.configuration).apply { setLocale(locale) }
        val context = base.createConfigurationContext(configuration)
        var result: Result<String>? = null
        instrumentation.runOnMainSync {
            result = runCatching {
                try {
                    val views = WidgetRenderer.render(
                        context,
                        AppWidgetManager.INVALID_APPWIDGET_ID,
                        WidgetFamily.TREND,
                        WidgetConfiguration.defaults(WidgetFamily.TREND).copy(trendHours = hours),
                        snapshot = null,
                        widthDp = widthDp,
                        heightDp = 230,
                        trend = null
                    )
                    val root = views.apply(context, FrameLayout(context))
                    val density = context.resources.displayMetrics.density
                    val width = View.MeasureSpec.makeMeasureSpec(
                        (widthDp * density).toInt(), View.MeasureSpec.EXACTLY
                    )
                    val height = View.MeasureSpec.makeMeasureSpec(
                        (230 * density).toInt(), View.MeasureSpec.EXACTLY
                    )
                    root.measure(width, height)
                    val title = root.findViewById<TextView>(R.id.widget_title)
                    assertEquals(
                        0, (0 until title.layout.lineCount).sumOf(title.layout::getEllipsisCount)
                    )
                    title.text.toString()
                } finally {
                    DisplayStrings.setResources(base.resources)
                }
            }
        }
        val metric = context.getString(R.string.history_battery_level)
        val expected = rangeLabel?.let { "$metric · ${context.getString(it)}" } ?: metric
        assertEquals(expected, requireNotNull(result).getOrThrow())
    }
}
