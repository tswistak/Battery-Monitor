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
package codes.swistak.batterymonitor.monitoring

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.util.DisplayMetrics
import androidx.core.graphics.createBitmap

internal class BatteryLevel private constructor(context: Context, sizeFactor: Float) {
    companion object {
        private const val FACTOR_LARGE = 33.25f
        private const val FACTOR_SMALL = 13.33f

        private var largeInstance: BatteryLevel? = null
        private var smallInstance: BatteryLevel? = null

        fun getLargeInstance(context: Context): BatteryLevel {
            if (largeInstance != null) return largeInstance!!

            largeInstance = BatteryLevel(context, FACTOR_LARGE)

            return largeInstance!!
        }

        fun getSmallInstance(context: Context): BatteryLevel {
            if (smallInstance != null) return smallInstance!!

            smallInstance = BatteryLevel(context, FACTOR_SMALL)

            return smallInstance!!
        }
    }

    private val canvas: Canvas = Canvas()
    private val paint: Paint
    private var battery: Bitmap
    private var mLevel = 0
    private var mColor = 0

    private val factorWidth = 12.0f
    private val factorBodH = 21.0f
    private val factorTopW = 5.0f
    private val factorTopH = 1.5f
    private val factorStroke = 1.0f

    private val width: Int = (sizeFactor * factorWidth).toInt()
    private val bodH: Int = (sizeFactor * factorBodH).toInt()
    private val topW: Int = (sizeFactor * factorTopW).toInt()
    private val topH: Int = (sizeFactor * factorTopH).toInt()
    private val totalH: Int = (bodH + topH)
    private val strokeW: Int = (sizeFactor * factorStroke).toInt()

    init {
        battery = createBitmap(width, totalH)
        battery.density = DisplayMetrics.DENSITY_DEFAULT
        canvas.setBitmap(battery)

        paint = Paint()
        paint.isAntiAlias = true
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.isDither = true
        paint.strokeWidth = strokeW.toFloat()
    }

    fun setColor(color: Int) {
        mColor = color
        setLevel(mLevel)
    }

    fun setLevel(level: Int) {
        var level = level
        if (level < 0) level = 0

        mLevel = level

        val hsw = strokeW * 0.5f
        val isw = strokeW

        val outlineRect = RectF(hsw, topH + hsw, width - hsw, totalH - hsw)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        paint.setColor(mColor)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeW.toFloat()
        canvas.drawRoundRect(outlineRect, hsw, hsw, paint)

        val top = topH + isw + ((bodH - 2 * isw) * (100 - level) / 100)
        val fillRect = RectF(
            strokeW.toFloat(),
            top.toFloat(),
            (width - strokeW).toFloat(),
            (totalH - strokeW).toFloat()
        )

        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(fillRect, 0f, 0f, paint)

        val topLeft = (width - topW) / 2
        val topRect = RectF(
            topLeft.toFloat(), 0f, (topLeft + topW).toFloat(), (topH + strokeW).toFloat()
        )
        canvas.drawRoundRect(topRect, hsw, hsw, paint)
    }

    fun getBitmap(): Bitmap = battery
}
