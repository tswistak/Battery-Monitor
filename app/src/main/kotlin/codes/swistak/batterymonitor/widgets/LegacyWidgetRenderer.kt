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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.monitoring.BatteryInfoService

internal object LegacyWidgetRenderer {
    fun renderStopped(context: Context, appWidgetIds: IntArray = widgetIds(context)) {
        val manager = AppWidgetManager.getInstance(context)
        val currentState = PendingIntent.getActivity(
            context, 100, Intent(context, BatteryInfoActivity::class.java).putExtra(
                BatteryInfoService.EXTRA_CURRENT_INFO, true
            ), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopped = context.getString(R.string.current_monitor_disabled)
        val circleProvider = ComponentName(context, BatteryInfoAppWidgetProvider::class.java)
        val fullProvider = ComponentName(context, FullAppWidgetProvider::class.java)
        for (id in appWidgetIds) {
            val provider = manager.getAppWidgetInfo(id)?.provider ?: continue
            val views = when (provider) {
                circleProvider -> RemoteViews(
                    context.packageName, R.layout.circle_app_widget
                ).apply {
                    setImageViewResource(R.id.circle_widget_image_view, R.drawable.empty)
                }

                fullProvider -> RemoteViews(context.packageName, R.layout.full_app_widget).apply {
                    setImageViewResource(R.id.battery_level_view, R.drawable.empty)
                    setTextViewText(R.id.fully_charged, stopped)
                    setTextViewText(R.id.time_remaining, "")
                    setTextViewText(R.id.until_what, "")
                }

                else -> continue
            }
            views.setTextViewText(R.id.level, "—")
            views.setContentDescription(R.id.widget_layout, stopped)
            views.setOnClickPendingIntent(R.id.widget_layout, currentState)
            try {
                manager.updateAppWidget(id, views)
            } catch (error: RuntimeException) {
                Log.w("LegacyWidgetRenderer", "Unable to update stopped widget $id", error)
            }
        }
    }

    private fun widgetIds(context: Context): IntArray {
        val manager = AppWidgetManager.getInstance(context)
        return manager.getAppWidgetIds(
            ComponentName(
                context, BatteryInfoAppWidgetProvider::class.java
            )
        ) + manager.getAppWidgetIds(ComponentName(context, FullAppWidgetProvider::class.java))
    }
}
