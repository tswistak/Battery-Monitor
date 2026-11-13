/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.widgets

import android.appwidget.AppWidgetManager
import android.content.res.Configuration
import android.os.BadParcelableException
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.os.BundleCompat

internal data class WidgetSize(val widthDp: Int, val heightDp: Int)

internal object WidgetSizes {
    private const val MAX_VARIANTS = 4
    private const val MAX_DIMENSION_DP = 1200

    fun default(family: WidgetFamily): WidgetSize = when (family) {
        WidgetFamily.STATUS, WidgetFamily.MEASUREMENTS -> WidgetSize(200, 180)
        WidgetFamily.TREND -> WidgetSize(260, 180)
    }

    fun preview(family: WidgetFamily, options: Bundle, orientation: Int): WidgetSize {
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val sizes = exact(options)
        if (sizes.isNotEmpty()) return closestForOrientation(sizes, landscape).asDp()
        val (portraitSize, landscapeSize) = legacy(family, options)
        return if (landscape) landscapeSize else portraitSize
    }

    fun remoteViews(
        family: WidgetFamily, options: Bundle, render: (WidgetSize, List<WidgetSize>) -> RemoteViews
    ): RemoteViews {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sizes = exact(options)
            if (sizes.isNotEmpty()) {
                val selected = variants(sizes)
                val dimensions = selected.map { it.asDp() }
                if (selected.size == 1) return render(dimensions.single(), dimensions)
                return RemoteViews(selected.associateWith { render(it.asDp(), dimensions) })
            }
        }
        val (portrait, landscape) = legacy(family, options)
        val dimensions = listOf(portrait, landscape).distinct()
        val portraitView = render(portrait, dimensions)
        return if (landscape == portrait) portraitView else RemoteViews(
            render(landscape, dimensions), portraitView
        )
    }

    private fun exact(options: Bundle): List<SizeF> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        val sizes = try {
            BundleCompat.getParcelableArrayList(
                options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java
            )
        } catch (_: BadParcelableException) {
            null
        }
        return sizes.orEmpty().filterIsInstance<SizeF>().filter { it.width >= 1 && it.height >= 1 }
            .distinct()
    }

    private fun variants(sizes: List<SizeF>): List<SizeF> {
        if (sizes.size <= MAX_VARIANTS) return sizes
        return listOf(
            SizeF(sizes.minOf { it.width }, sizes.minOf { it.height }),
            closestForOrientation(sizes, landscape = false),
            closestForOrientation(sizes, landscape = true),
            sizes.maxBy { it.width.toDouble() * it.height }).distinct()
    }

    private fun closestForOrientation(sizes: List<SizeF>, landscape: Boolean): SizeF {
        val width = if (landscape) sizes.maxOf { it.width } else sizes.minOf { it.width }
        val height = if (landscape) sizes.minOf { it.height } else sizes.maxOf { it.height }
        return sizes.minBy {
            val dx = it.width.toDouble() - width
            val dy = it.height.toDouble() - height
            dx * dx + dy * dy
        }
    }

    private fun legacy(family: WidgetFamily, options: Bundle): Pair<WidgetSize, WidgetSize> {
        val default = default(family)
        val minWidth = options.positiveInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val maxWidth = options.positiveInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val minHeight = options.positiveInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        val maxHeight = options.positiveInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val portrait = WidgetSize(
            minWidth ?: maxWidth ?: default.widthDp, maxHeight ?: minHeight ?: default.heightDp
        )
        val landscape = WidgetSize(
            maxWidth ?: minWidth ?: default.widthDp, minHeight ?: maxHeight ?: default.heightDp
        )
        return portrait to landscape
    }

    private fun Bundle.positiveInt(key: String): Int? = try {
        getInt(key).takeIf { it > 0 }?.coerceAtMost(MAX_DIMENSION_DP)
    } catch (_: BadParcelableException) {
        null
    }

    private fun SizeF.asDp(): WidgetSize = WidgetSize(
        width.toInt().coerceAtMost(MAX_DIMENSION_DP), height.toInt().coerceAtMost(MAX_DIMENSION_DP)
    )
}
