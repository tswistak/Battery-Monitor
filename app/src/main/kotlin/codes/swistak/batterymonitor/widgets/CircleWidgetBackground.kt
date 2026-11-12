/*
    Copyright (c) 2009-2020 Darshan Computing, LLC
    Modified in 2026 by Tomasz Świstak <tomasz@swistak.codes> for the Battery Monitor fork.
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.core.graphics.createBitmap

internal class CircleWidgetBackground(context: Context) {
    private val dimension: Int
    private val arcStrokeWidth: Float

    private val bitmap: Bitmap
    private val canvas: Canvas = Canvas()
    private val arcPaint: Paint

    init {
        val metrics = context.resources.displayMetrics
        dimension = (72 * (metrics.densityDpi / 160.0)).toInt()
        arcStrokeWidth = dimension * 0.07f

        bitmap = createBitmap(dimension, dimension)
        canvas.setBitmap(bitmap)

        arcPaint = Paint()
        arcPaint.isAntiAlias = true
        arcPaint.strokeWidth = arcStrokeWidth
        arcPaint.style = Paint.Style.STROKE
        arcPaint.isDither = true
    }

    fun draw(percent: Int, color: Int): Bitmap {
        val level = percent.coerceAtLeast(0)

        val topLeft = (arcStrokeWidth / 2).toInt()
        val bottomRight = dimension - (arcStrokeWidth / 2).toInt()

        val oval = RectF(
            topLeft.toFloat(), topLeft.toFloat(), bottomRight.toFloat(), bottomRight.toFloat()
        )

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        arcPaint.color = color
        canvas.drawArc(oval, -90.0f, level * 360.0f / 100.0f, false, arcPaint)
        return bitmap
    }
}
