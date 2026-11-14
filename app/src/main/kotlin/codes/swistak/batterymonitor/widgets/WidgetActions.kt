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

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.ui.current.CurrentMeasurement
import codes.swistak.batterymonitor.ui.navigation.SectionOwner
import codes.swistak.batterymonitor.ui.widgets.WidgetConfigurationActivity

internal data class WidgetDestination(
    val section: SectionOwner,
    val measurement: CurrentMeasurement? = null,
    val historyHours: Int? = null,
    val historyMetric: HistoryMetric? = null
)

internal object WidgetActions {
    fun destination(family: WidgetFamily, configuration: WidgetConfiguration): WidgetDestination =
        when (configuration.tapAction) {
            WidgetTapAction.CURRENT_STATE -> WidgetDestination(SectionOwner.CURRENT)
            WidgetTapAction.HISTORY -> WidgetDestination(
                SectionOwner.HISTORY,
                historyHours = configuration.trendHours.takeIf { it in setOf(24, 72, 168) } ?: 24,
                historyMetric = configuration.trendMetric)

            WidgetTapAction.MEASUREMENT -> WidgetDestination(
                SectionOwner.CURRENT, measurement = when (family) {
                    WidgetFamily.STATUS -> null
                    WidgetFamily.MEASUREMENTS -> CurrentMeasurement.CURRENT
                    WidgetFamily.TREND -> configuration.trendMetric.currentMeasurement()
                }
            )
        }

    private fun HistoryMetric.currentMeasurement(): CurrentMeasurement = when (this) {
        HistoryMetric.LEVEL -> CurrentMeasurement.LEVEL
        HistoryMetric.TEMPERATURE -> CurrentMeasurement.TEMPERATURE
        HistoryMetric.VOLTAGE -> CurrentMeasurement.VOLTAGE
        HistoryMetric.CURRENT -> CurrentMeasurement.CURRENT
        HistoryMetric.POWER -> CurrentMeasurement.POWER
        HistoryMetric.REMAINING_CHARGE -> CurrentMeasurement.REMAINING_CHARGE
    }

    private fun WidgetMetric.currentMeasurement(): CurrentMeasurement = when (this) {
        WidgetMetric.TEMPERATURE -> CurrentMeasurement.TEMPERATURE
        WidgetMetric.VOLTAGE -> CurrentMeasurement.VOLTAGE
        WidgetMetric.CURRENT -> CurrentMeasurement.CURRENT
        WidgetMetric.REMAINING_CHARGE -> CurrentMeasurement.REMAINING_CHARGE
    }

    fun open(
        context: Context, id: Int, family: WidgetFamily, config: WidgetConfiguration
    ): PendingIntent = open(context, id, destination(family, config), "open")

    fun openMeasurement(context: Context, id: Int, metric: WidgetMetric): PendingIntent {
        val measurement = metric.currentMeasurement()
        return open(
            context,
            id,
            WidgetDestination(SectionOwner.CURRENT, measurement),
            "measurement/${measurement.route}"
        )
    }

    private fun open(
        context: Context, id: Int, destination: WidgetDestination, path: String
    ): PendingIntent {
        val intent = Intent(context, BatteryInfoActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = "batterymonitor://widget/$id/$path".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(BatteryInfoActivity.EXTRA_SECTION, destination.section.route)
            putExtra(
                BatteryInfoActivity.EXTRA_DETAIL,
                destination.measurement?.let { "measurement:${it.route}" })
            destination.historyHours?.let { putExtra(BatteryInfoActivity.EXTRA_HISTORY_HOURS, it) }
            destination.historyMetric?.let {
                putExtra(BatteryInfoActivity.EXTRA_HISTORY_METRIC, it.name)
            }
        }
        return PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun configure(context: Context, id: Int): PendingIntent = PendingIntent.getActivity(
        context, id, Intent(context, WidgetConfigurationActivity::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            data = "batterymonitor://widget/$id/configure".toUri()
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
