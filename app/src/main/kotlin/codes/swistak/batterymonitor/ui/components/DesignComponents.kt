/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.ui.theme.BatterySpacing
import codes.swistak.batterymonitor.ui.theme.LocalBatterySemanticColors

data class MetricDisplay(
    val label: String, val value: String, val unit: String = "", val icon: Int? = null
)

@Composable
fun BatteryCellHero(
    title: String,
    level: Int?,
    status: String,
    detail: String,
    spokenSummary: String,
    modifier: Modifier = Modifier,
    targetPercent: Int? = null,
    charging: Boolean = false,
    expanded: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier.clearAndSetSemantics { contentDescription = spokenSummary },
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer),
        shape = MaterialTheme.shapes.large
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (expanded) 263.dp else 208.dp)
        ) {
            val compact = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.6f
            val contentPadding = if (expanded) 31.dp else BatterySpacing.lg
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = contentPadding,
                        top = contentPadding,
                        end = if (compact) contentPadding else 120.dp,
                        bottom = contentPadding
                    ), verticalArrangement = Arrangement.spacedBy(BatterySpacing.sm)
            ) {
                Text(
                    title,
                    style = if (expanded) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleLarge,
                    color = colors.onPrimaryContainer
                )
                Text(
                    buildAnnotatedString {
                        append(level?.toString() ?: "—")
                        if (level != null) withStyle(
                            MaterialTheme.typography.headlineMedium.copy(fontSize = 34.sp)
                                .toSpanStyle()
                        ) { append("%") }
                    }, style = when {
                        compact -> MaterialTheme.typography.displayMedium
                        expanded -> MaterialTheme.typography.displayLarge.copy(
                            fontSize = 98.sp, lineHeight = 107.sp
                        )

                        else -> MaterialTheme.typography.displayLarge
                    }, color = colors.onPrimaryContainer
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onPrimaryContainer
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onPrimaryContainer
                )
            }
            if (!compact) Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = BatterySpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .width(32.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(colors.onPrimaryContainer.copy(alpha = 0.55f))
                )
                Box(
                    Modifier
                        .width(if (expanded) 80.dp else 72.dp)
                        .height(if (expanded) 150.dp else 132.dp)
                        .border(2.dp, colors.onPrimaryContainer, RoundedCornerShape(20.dp))
                        .padding(6.dp), contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight((level ?: 0).coerceIn(0, 100) / 100f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.primary)
                    )
                    Canvas(Modifier.matchParentSize()) {
                        val tickColor = colors.onPrimaryContainer.copy(alpha = 0.35f)
                        repeat(5) { tick ->
                            val y = size.height * (0.05f + tick * 0.225f)
                            drawLine(
                                tickColor,
                                Offset(4.dp.toPx(), y),
                                Offset(13.dp.toPx(), y),
                                1.dp.toPx()
                            )
                        }
                        targetPercent?.takeIf { it in 0..100 }?.let { target ->
                            val radius = 3.dp.toPx()
                            val y = (size.height * (1 - target / 100f)).coerceIn(
                                radius, size.height - radius
                            )
                            val markerColor = colors.onPrimaryContainer.copy(alpha = 0.8f)
                            drawLine(
                                markerColor, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx()
                            )
                            val center = Offset(size.width - 5.dp.toPx(), y)
                            drawCircle(markerColor, radius + 1.dp.toPx(), center)
                            drawCircle(colors.primaryContainer, radius, center)
                        }
                    }
                    if (charging) Icon(
                        painterResource(R.drawable.ui_bolt),
                        null,
                        Modifier
                            .align(Alignment.Center)
                            .size(28.dp),
                        tint = if ((level
                                ?: 0) >= 50
                        ) colors.onPrimary else colors.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun MetricGrid(
    metrics: List<MetricDisplay>,
    modifier: Modifier = Modifier,
    columns: Int = 2,
    onMetricClick: ((Int) -> Unit)? = null,
    expanded: Boolean = false
) {
    require(metrics.isNotEmpty())
    require(columns in 1..2)
    val rows = (metrics.size + columns - 1) / columns
    OutlinedCard(
        modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            repeat(rows) { row ->
                val rowColumns = minOf(columns, metrics.size - row * columns)
                Row(Modifier.height(IntrinsicSize.Min)) {
                    repeat(rowColumns) { column ->
                        val index = row * columns + column
                        val metric = metrics[index]
                        Column(
                            Modifier
                                .weight(1f)
                                .then(if (onMetricClick == null) Modifier else Modifier.clickable {
                                    onMetricClick(
                                        index
                                    )
                                })
                                .heightIn(min = if (expanded) 104.dp else 84.dp)
                                .padding(if (expanded) 19.dp else BatterySpacing.normal),
                            verticalArrangement = Arrangement.spacedBy(BatterySpacing.xs)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                metric.icon?.let {
                                    Icon(
                                        painterResource(it),
                                        null,
                                        Modifier.size(17.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    metric.label,
                                    style = if (expanded) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "${metric.value}${if (metric.unit.isEmpty()) "" else " ${metric.unit}"}",
                                style = if (expanded) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (column < rowColumns - 1) Box(
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }
                if (row < rows - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}


@Composable
fun SettingRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    checked: Boolean? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = BatterySpacing.touch)
            .then(if (checked == null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = BatterySpacing.normal, vertical = BatterySpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (checked != null) {
            Spacer(Modifier.width(BatterySpacing.normal))
            Switch(checked = checked, onCheckedChange = { onClick() })
        }
    }
}

@Composable
fun CapabilityNotice(
    title: String, message: String, modifier: Modifier = Modifier
) {
    val semantic = LocalBatterySemanticColors.current
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            Modifier.padding(BatterySpacing.normal),
            verticalArrangement = Arrangement.spacedBy(BatterySpacing.xs)
        ) {
            Text(
                title,
                color = semantic.warning,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
internal fun ActionLabel(label: String, icon: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(painterResource(icon), null, Modifier.size(19.dp))
        Text(label)
    }
}
