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

    val bitmap: Bitmap
    private val canvas: Canvas
    private val arcPaint: Paint
    private var mLevel = 0
    private var mColor = 0

    init {
        canvas = Canvas()

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

    fun setColor(color: Int) {
        mColor = color
        setLevel(mLevel)
    }

    fun setLevel(level: Int) {
        var level = level
        if (level < 0) level = 0

        mLevel = level

        val topLeft = (arcStrokeWidth / 2).toInt()
        val bottomRight = dimension - (arcStrokeWidth / 2).toInt()

        val oval = RectF(
            topLeft.toFloat(),
            topLeft.toFloat(),
            bottomRight.toFloat(),
            bottomRight.toFloat()
        )

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        arcPaint.setColor(mColor)
        canvas.drawArc(oval, -90.0f, level * 360.0f / 100.0f, false, arcPaint)
    }
}
