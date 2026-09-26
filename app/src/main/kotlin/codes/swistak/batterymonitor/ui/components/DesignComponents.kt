package codes.swistak.batterymonitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import codes.swistak.batterymonitor.ui.theme.BatterySpacing
import codes.swistak.batterymonitor.ui.theme.LocalBatterySemanticColors

data class MetricDisplay(val label: String, val value: String, val unit: String = "")

@Composable
fun BatteryCellHero(
    title: String,
    level: Int?,
    status: String,
    detail: String,
    spokenSummary: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier.clearAndSetSemantics { contentDescription = spokenSummary },
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(BatterySpacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BatterySpacing.sm)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onPrimaryContainer
                )
                Text(level?.let { "$it%" } ?: "—",
                    style = MaterialTheme.typography.displayLarge,
                    color = colors.onPrimaryContainer)
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
            Spacer(Modifier.width(BatterySpacing.normal))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .width(32.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(colors.onPrimaryContainer.copy(alpha = 0.55f))
                )
                Box(
                    Modifier
                        .width(72.dp)
                        .height(132.dp)
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
                }
            }
        }
    }
}

@Composable
fun MetricGrid(metrics: List<MetricDisplay>, modifier: Modifier = Modifier) {
    require(metrics.size == 4)
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            repeat(2) { row ->
                Row {
                    repeat(2) { column ->
                        val metric = metrics[row * 2 + column]
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(BatterySpacing.normal),
                            verticalArrangement = Arrangement.spacedBy(BatterySpacing.xs)
                        ) {
                            Text(
                                metric.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${metric.value}${if (metric.unit.isEmpty()) "" else " ${metric.unit}"}",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
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
