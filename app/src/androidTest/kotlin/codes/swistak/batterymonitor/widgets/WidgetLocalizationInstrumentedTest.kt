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
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.DisplayStrings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class WidgetLocalizationInstrumentedTest {
    @Test
    fun trend_title_uses_the_same_resource_as_the_configured_history_duration() {
        for ((hours, label) in listOf(
            24 to R.string.history_last_24h,
            72 to R.string.history_last_3d,
            168 to R.string.history_last_7d
        )) {
            assertEquals(label, trendRangeLabel(hours))
            for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("pl"))) {
                val (rendered, expected) = renderedTitle(hours, label, locale)
                assertEquals(expected, rendered)
            }
        }
    }

    private fun renderedTitle(hours: Int, label: Int, locale: Locale): Pair<String, String> {
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
                        widthDp = 350,
                        heightDp = 230,
                        trend = null
                    )
                    views.apply(context, FrameLayout(context))
                        .findViewById<TextView>(R.id.widget_title).text.toString()
                } finally {
                    DisplayStrings.setResources(base.resources)
                }
            }
        }
        return requireNotNull(result).getOrThrow() to context.getString(label)
    }
}
