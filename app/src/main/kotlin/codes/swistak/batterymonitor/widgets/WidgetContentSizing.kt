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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.createBitmap
import kotlin.math.ceil
import kotlin.math.roundToInt

internal const val BATTERY_ASPECT = 36f / 58f

internal fun widgetBatteryBitmap(context: Context, heightDp: Int, color: Int, level: Int?): Bitmap {
    val density = context.resources.displayMetrics.density
    val height = (heightDp * density).roundToInt().coerceIn(1, 384)
    val width = (height * BATTERY_ASPECT).roundToInt().coerceAtLeast(1)
    return createBitmap(width, height).apply {
        this.density = (height * 160f / heightDp).roundToInt()
        val canvas = Canvas(this)
        val scale = height / 58f
        canvas.translate((width - 36f * scale) / 2f, 0f)
        canvas.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRoundRect(11f, 0f, 25f, 6f, 2f, 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRoundRect(1.5f, 5f, 34.5f, 56.5f, 8f, 8f, paint)
        paint.style = Paint.Style.FILL
        val fill = (level ?: 0).coerceIn(0, 100) / 100f
        if (fill > 0f) {
            val bounds = RectF(5f, 9f, 31f, 53f)
            canvas.clipRect(
                bounds.left, bounds.bottom - bounds.height() * fill, bounds.right, bounds.bottom
            )
            canvas.drawRoundRect(bounds, 5f, 5f, paint)
        }
    }
}

private fun widgetTextPaint(context: Context, sizeSp: Float, medium: Boolean = false) =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface =
            if (medium) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        textLocale = context.resources.configuration.locales[0]
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sizeSp, context.resources.displayMetrics
        )
    }

internal fun widgetLineHeightDp(context: Context, sizeSp: Float, medium: Boolean = false): Int =
    widgetTextPaint(context, sizeSp, medium).fontMetricsInt.let {
        ceil((it.descent - it.ascent) / context.resources.displayMetrics.density).toInt()
    }

internal fun compactWidgetTitleFits(
    context: Context, title: String, widthDp: Int, sizeSp: Float = 12f
): Boolean {
    val density = context.resources.displayMetrics.density
    val width = (widthDp * density).roundToInt()
    if (width < 1) return false
    val layout = StaticLayout.Builder.obtain(
        title, 0, title.length, widgetTextPaint(context, sizeSp, medium = true), width
    ).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
    return layout.height <= 36 * density && layout.getLineEnd(layout.lineCount - 1) == title.length && (0 until layout.lineCount).all {
        layout.getEllipsisCount(
            it
        ) == 0
    }
}

internal fun widgetLabelHeightDp(
    context: Context, text: String, widthDp: Int, sizeSp: Float, maxLines: Int
): Int {
    val density = context.resources.displayMetrics.density
    val width = (widthDp * density).roundToInt().coerceAtLeast(1)
    val layout =
        StaticLayout.Builder.obtain(text, 0, text.length, widgetTextPaint(context, sizeSp), width)
            .setIncludePad(false).setEllipsize(TextUtils.TruncateAt.END).setMaxLines(maxLines)
            .build()
    return ceil(layout.height / density).toInt()
}

internal fun fitWidgetText(
    context: Context,
    text: CharSequence,
    widthDp: Int,
    heightDp: Int,
    maxSp: Float,
    minSp: Float,
    medium: Boolean = true
): Float {
    val density = context.resources.displayMetrics.density
    val paint = widgetTextPaint(context, maxSp, medium)
    var sizeSp = maxSp
    while (sizeSp > minSp) {
        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sizeSp, context.resources.displayMetrics
        )
        val metrics = paint.fontMetricsInt
        if (metrics.descent - metrics.ascent <= heightDp * density && Layout.getDesiredWidth(
                text, paint
            ) <= widthDp * density
        ) return sizeSp
        sizeSp -= 1
    }
    return minSp
}

internal fun measurementValueTextSize(
    context: Context, values: Collection<String>, widthPx: Int, heightPx: Int, maxSp: Float
): Float {
    val paint = widgetTextPaint(context, maxSp, medium = true)
    val width = widthPx.coerceAtLeast(1)
    val direction =
        if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            TextDirectionHeuristics.FIRSTSTRONG_RTL
        } else TextDirectionHeuristics.FIRSTSTRONG_LTR
    for (size in paint.textSize.toInt() downTo 1) {
        paint.textSize = size.toFloat()
        if (values.any { Layout.getDesiredWidth(it, paint) > width }) continue
        if (values.all { value ->
                val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
                    .setIncludePad(false).setMaxLines(1).setTextDirection(direction).apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            setUseLineSpacingFromFallbacks(true)
                        }
                    }.build()
                layout.getLineEnd(0) == value.length && layout.getLineMax(0) <= width && layout.height <= heightPx
            }) return paint.textSize
    }
    return 1f
}
