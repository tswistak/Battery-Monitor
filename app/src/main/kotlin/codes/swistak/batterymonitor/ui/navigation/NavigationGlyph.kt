/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun NavigationGlyph(id: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(color, Offset(x1, y1), Offset(x2, y2), stroke.width)

            fun path(block: Path.() -> Unit) = drawPath(Path().apply(block), color, style = stroke)
            when (id) {
                "brand" -> {
                    drawRoundRect(
                        color,
                        topLeft = Offset(9f, 5f),
                        size = Size(6f, 16f),
                        cornerRadius = CornerRadius(1f)
                    )
                    drawLine(color, Offset(11f, 3f), Offset(13f, 3f), 2f)
                }

                "current" -> {
                    path {
                        moveTo(8f, 4f); lineTo(16f, 4f); quadraticTo(18f, 4f, 18f, 6f)
                        lineTo(18f, 20f); quadraticTo(18f, 22f, 16f, 22f)
                        lineTo(8f, 22f); quadraticTo(6f, 22f, 6f, 20f)
                        lineTo(6f, 6f); quadraticTo(6f, 4f, 8f, 4f); close()
                    }
                    line(10f, 2f, 14f, 2f)
                    line(9f, 17f, 15f, 17f)
                    line(9f, 19f, 15f, 19f)
                }

                "history" -> {
                    path { moveTo(3f, 3f); lineTo(3f, 21f); lineTo(21f, 21f) }
                    path { moveTo(5f, 16f); lineTo(10f, 11f); lineTo(14f, 14f); lineTo(20f, 5f) }
                }

                "alarms" -> {
                    path {
                        moveTo(5f, 17f); quadraticTo(7f, 14f, 7f, 10f)
                        quadraticTo(7f, 5f, 12f, 5f); quadraticTo(17f, 5f, 17f, 10f)
                        quadraticTo(17f, 14f, 19f, 17f); close()
                    }
                    line(10f, 20f, 14f, 20f)
                }

                "diagnostics" -> path {
                    moveTo(2f, 12f); lineTo(6f, 12f)
                    lineTo(8f, 5f); lineTo(12f, 19f); lineTo(15f, 10f)
                    lineTo(17f, 12f); lineTo(22f, 12f)
                }

                "settings" -> {
                    drawCircle(color, 8f, Offset(12f, 12f), style = stroke)
                    drawCircle(color, 3f, Offset(12f, 12f), style = stroke)
                    for (step in 0 until 8) {
                        val angle = step * PI / 4
                        line(
                            (12 + 8 * cos(angle)).toFloat(),
                            (12 + 8 * sin(angle)).toFloat(),
                            (12 + 11 * cos(angle)).toFloat(),
                            (12 + 11 * sin(angle)).toFloat()
                        )
                    }
                }

                "help" -> {
                    drawCircle(color, 10f, Offset(12f, 12f), style = stroke)
                    path {
                        moveTo(9f, 9f); quadraticTo(9f, 6f, 12f, 6f)
                        quadraticTo(15f, 6f, 15f, 9f)
                        quadraticTo(15f, 11f, 12f, 12f); lineTo(12f, 15f)
                    }
                    drawCircle(color, 0.8f, Offset(12f, 18f))
                }

                "close" -> {
                    line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f)
                }
            }
        }
    }
}
