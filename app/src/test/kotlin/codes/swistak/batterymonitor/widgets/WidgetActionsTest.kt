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

import android.app.Application
import android.appwidget.AppWidgetManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.ui.current.CurrentMeasurement
import codes.swistak.batterymonitor.ui.navigation.SectionOwner
import codes.swistak.batterymonitor.ui.widgets.WidgetConfigurationActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WidgetActionsTest {
    @Test
    fun `status widgets open the current state parent without a measurement or history override`() {
        assertEquals(
            WidgetActions.destination(WidgetConfiguration.defaults(WidgetFamily.STATUS)),
            WidgetDestination(SectionOwner.CURRENT)
        )
    }

    @Test
    fun `each measurement widget tap uses a stable measurement route in current state`() {
        val expected = mapOf(
            WidgetMetric.TEMPERATURE to "temperature",
            WidgetMetric.VOLTAGE to "voltage",
            WidgetMetric.CURRENT to "current",
            WidgetMetric.REMAINING_CHARGE to "remaining-charge"
        )
        expected.forEach { (metric, route) ->
            val destination = WidgetActions.destination(
                WidgetConfiguration.defaults(WidgetFamily.MEASUREMENTS).copy(tapMetric = metric)
            )
            assertEquals(SectionOwner.CURRENT, destination.section)
            assertEquals(route, destination.measurement?.route)
            assertEquals(destination.measurement, CurrentMeasurement.fromRoute(route))
            assertNull(destination.historyHours)
        }
    }

    @Test
    fun `trend widgets open history with their configured rolling range and metric`() {
        val context = RuntimeEnvironment.getApplication()
        val defaults = WidgetConfiguration.defaults(WidgetFamily.TREND)
        for ((hours, metric) in listOf(
            24 to HistoryMetric.LEVEL, 72 to HistoryMetric.VOLTAGE, 168 to HistoryMetric.CURRENT
        )) {
            val action = WidgetActions.open(
                context, 41, defaults.copy(
                    trendHours = hours, trendMetric = metric
                )
            )
            val intent = shadowOf(action).savedIntent
            assertEquals(
                SectionOwner.HISTORY.route, intent.getStringExtra(BatteryInfoActivity.EXTRA_SECTION)
            )
            assertEquals(hours, intent.getIntExtra(BatteryInfoActivity.EXTRA_HISTORY_HOURS, -1))
            assertEquals(
                metric.name, intent.getStringExtra(BatteryInfoActivity.EXTRA_HISTORY_METRIC)
            )
        }
    }

    @Test
    fun `invalid widget ranges fall back to twenty four hours instead of querying an arbitrary period`() {
        listOf(Int.MIN_VALUE, -1, 0, 1, 73, Int.MAX_VALUE).forEach { hours ->
            assertEquals(
                24, WidgetActions.destination(
                    WidgetConfiguration.defaults(WidgetFamily.TREND).copy(trendHours = hours)
                ).historyHours
            )
        }
    }

    @Test
    fun `measurement deep links reject labels old positions and unknown route names`() {
        listOf(null, "", "0", "2", "Temperature", "remaining_charge", "widgets").forEach {
            assertNull(CurrentMeasurement.fromRoute(it))
        }
    }

    @Test
    @Config(sdk = [26, 36])
    fun `applied measurement cells keep their own actions after reorder resize and reapply`() {
        val context = RuntimeEnvironment.getApplication()
        val host = FrameLayout(context)
        val firstId = 41
        val secondId = 42
        val firstConfig = WidgetConfiguration.defaults(WidgetFamily.MEASUREMENTS).copy(
            tapMetric = WidgetMetric.VOLTAGE
        )
        val secondConfig = firstConfig.copy(
            metricOrder = listOf(
                WidgetMetric.REMAINING_CHARGE,
                WidgetMetric.TEMPERATURE,
                WidgetMetric.VOLTAGE,
                WidgetMetric.CURRENT
            ), selectedMetrics = setOf(WidgetMetric.REMAINING_CHARGE, WidgetMetric.TEMPERATURE)
        )

        fun render(id: Int, config: WidgetConfiguration, width: Int, height: Int) =
            WidgetRenderer.render(
                context,
                id,
                WidgetFamily.MEASUREMENTS,
                config,
                snapshot = null,
                widthDp = width,
                heightDp = height,
                trend = null,
                monitoringEnabled = false
            )

        val first = render(firstId, firstConfig, 180, 150).apply(context, host)
        val second = render(secondId, secondConfig, 340, 240).apply(context, host)
        val shadow = shadowOf(context)
        fun assertRoutes(widget: View, widgetId: Int, routes: List<String>) {
            val cells = widget.measurementCells().toList()
            assertEquals(routes.size, cells.size)
            for ((cell, route) in cells.zip(routes)) {
                assertTrue(cell.performClick())
                val opened = requireNotNull(shadow.nextStartedActivity)
                assertEquals(BatteryInfoActivity::class.java.name, opened.component?.className)
                assertEquals(
                    SectionOwner.CURRENT.route,
                    opened.getStringExtra(BatteryInfoActivity.EXTRA_SECTION)
                )
                assertEquals(
                    "measurement:$route", opened.getStringExtra(BatteryInfoActivity.EXTRA_DETAIL)
                )
                assertEquals(
                    "batterymonitor://widget/$widgetId/measurement/$route", opened.dataString
                )
            }
        }

        assertRoutes(
            first, firstId, listOf("temperature", "voltage", "current", "remaining-charge")
        )
        assertRoutes(second, secondId, listOf("remaining-charge", "temperature"))

        val reordered = firstConfig.copy(
            metricOrder = listOf(
                WidgetMetric.CURRENT,
                WidgetMetric.REMAINING_CHARGE,
                WidgetMetric.TEMPERATURE,
                WidgetMetric.VOLTAGE
            ), selectedMetrics = firstConfig.selectedMetrics - WidgetMetric.TEMPERATURE
        )
        render(firstId, reordered, 360, 150).reapply(context, first)
        assertRoutes(first, firstId, listOf("current", "remaining-charge", "voltage"))
        assertRoutes(second, secondId, listOf("remaining-charge", "temperature"))

        assertTrue(first.performClick())
        val rootIntent = requireNotNull(shadow.nextStartedActivity)
        assertEquals(
            "measurement:voltage", rootIntent.getStringExtra(BatteryInfoActivity.EXTRA_DETAIL)
        )
        assertEquals("batterymonitor://widget/$firstId/open", rootIntent.dataString)

        assertTrue(first.findViewById<View>(R.id.widget_customize).performClick())
        val configureIntent = requireNotNull(shadow.nextStartedActivity)
        assertEquals(
            WidgetConfigurationActivity::class.java.name, configureIntent.component?.className
        )
        assertEquals(firstId, configureIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))

        for (action in listOf(WidgetTapAction.HISTORY, WidgetTapAction.CURRENT_STATE)) {
            render(firstId, reordered.copy(tapAction = action), 180, 150).reapply(context, first)
            assertTrue(first.measurementCells().none { it.hasOnClickListeners() })
            assertTrue(first.performClick())
            val destination = requireNotNull(shadow.nextStartedActivity)
            val section = if (action == WidgetTapAction.HISTORY) SectionOwner.HISTORY
            else SectionOwner.CURRENT
            assertEquals(
                section.route, destination.getStringExtra(BatteryInfoActivity.EXTRA_SECTION)
            )
            assertNull(destination.getStringExtra(BatteryInfoActivity.EXTRA_DETAIL))
        }
    }

    @Test
    fun `cell PendingIntents are immutable and distinct by measurement widget and action`() {
        val context = RuntimeEnvironment.getApplication()
        val config = WidgetConfiguration.defaults(WidgetFamily.MEASUREMENTS)
        val cells = listOf(41, 42).flatMap { id ->
            WidgetMetric.entries.map { WidgetActions.openMeasurement(context, id, it) }
        }
        val root = WidgetActions.open(context, 41, config)
        val configure = WidgetActions.configure(context, 41)
        assertEquals(cells.size + 2, (cells + root + configure).toSet().size)
        assertTrue(cells.all { it.isImmutable })
        assertEquals(
            WidgetActions.openMeasurement(context, 41, WidgetMetric.TEMPERATURE), cells.first()
        )
    }

    private fun View.measurementCells(): Sequence<View> = sequence {
        if (id == R.id.widget_metric_cell) yield(this@measurementCells)
        else if (this@measurementCells is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).measurementCells())
        }
    }
}
