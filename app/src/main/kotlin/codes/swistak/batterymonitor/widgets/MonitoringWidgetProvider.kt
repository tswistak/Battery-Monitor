/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

abstract class MonitoringWidgetProvider : AppWidgetProvider() {
    private fun owned(context: Context, id: Int): Boolean =
        AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider == ComponentName(
            context, javaClass
        )

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context, appWidgetIds.filter { owned(context, it) }.toIntArray())
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, options: Bundle
    ) {
        if (owned(context, id)) refresh(context, intArrayOf(id))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val manager = AppWidgetManager.getInstance(context)
        val deleted =
            appWidgetIds.filter { it > 0 && manager.getAppWidgetInfo(it) == null }.toIntArray()
        if (!WidgetConfigurationStore(context).delete(deleted)) Log.w(
            "WidgetConfiguration", "Unable to remove deleted widget configuration"
        )
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val manager = AppWidgetManager.getInstance(context)
        if (!canRestoreWidgetIds(
                oldWidgetIds,
                newWidgetIds,
                isOwned = { owned(context, it) },
                isBound = { manager.getAppWidgetInfo(it) != null })
        ) return
        if (!WidgetConfigurationStore(context).remap(oldWidgetIds, newWidgetIds)) {
            Log.w("WidgetConfiguration", "Unable to remap restored widget configuration")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_LOCALE_CHANGED || intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
            val ids = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, javaClass))
            refresh(context, ids)
        } else super.onReceive(context, intent)
    }

    private fun refresh(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        WidgetUpdateDispatcher.refresh(context, ids) { pending.finish() }
    }
}

internal fun canRestoreWidgetIds(
    oldIds: IntArray, newIds: IntArray, isOwned: (Int) -> Boolean, isBound: (Int) -> Boolean
): Boolean =
    oldIds.size == newIds.size && oldIds.all { it > 0 } && newIds.all { it > 0 && isOwned(it) } && oldIds.distinct().size == oldIds.size && newIds.distinct().size == newIds.size && oldIds.none {
        isBound(it) && it !in newIds
    }

class StatusWidgetProvider : MonitoringWidgetProvider()
class MeasurementsWidgetProvider : MonitoringWidgetProvider()
class TrendWidgetProvider : MonitoringWidgetProvider()
