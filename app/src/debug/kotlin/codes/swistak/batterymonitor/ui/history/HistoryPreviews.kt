/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.HistoryRecord
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.logs.historyChart
import codes.swistak.batterymonitor.ui.components.MeasurementChart
import codes.swistak.batterymonitor.ui.theme.BatteryTheme
import codes.swistak.batterymonitor.ui.theme.ColorSource

@Composable
private fun ChartPreview(
    count: Int,
    metric: HistoryMetric = HistoryMetric.LEVEL,
    gaps: Boolean = false,
    compact: Boolean = false
) {
    val start =
        java.time.LocalDate.of(2026, 9, 6).atTime(14, 32).atZone(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
    val range = HistoryRangeState(start, start + 86_400_000)
    val hours = listOf(0.0, 3.0, 6.0, 9.0, 12.0, 15.0, 18.0, 20.0, 21.30, 21.8667, 23.0, 24.0)
    val levels = listOf(80, 76, 70, 64, 60, 54, 48, 45, 42, 80, 76, 72)
    val temperatures = listOf(318, 311, 307, 303, 300, 298, 301, 300, 298, 364, 331, 316)
    val voltages = listOf(4200, 4180, 4150, 4100, 4080, 4030, 3990, 3940, 3880, 4200, 4130, 4080)
    val model = remember(count, gaps) {
        historyChart(range, sequence {
            repeat(count) { index ->
                val reboot = gaps && index == 3
                yield(
                    HistoryRecord(
                        index.toLong(), LogRecord(
                            if (reboot) -1 else if (index == 9) 22 else 100,
                            if (reboot) null else levels[index],
                            (range.start + hours[index] * 3_600_000).toLong()
                                .coerceAtMost(range.end - 1),
                            if (reboot) null else temperatures[index],
                            if (reboot || (gaps && index == 4)) null else voltages[index]
                        )
                    )
                )
            }
        })
    }
    var selected by remember { mutableStateOf<Long?>(null) }
    BatteryTheme(colorSource = ColorSource.BatteryBlue) {
        Surface {
            Column(Modifier.padding(20.dp)) {
                OutlinedCard(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            when (metric) {
                                HistoryMetric.LEVEL -> "Poziom baterii"; HistoryMetric.TEMPERATURE -> "Temperatura"; HistoryMetric.VOLTAGE -> "Napięcie"
                            }, style = MaterialTheme.typography.titleMedium
                        )
                        MeasurementChart(
                            model, metric, false, selected, { selected = it }, compact = compact
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "History · prototype curve", widthDp = 390, locale = "pl", showBackground = true)
@Composable
private fun PrototypeChartPreview() = ChartPreview(12)

@Preview(name = "Current State · mini trend", widthDp = 390, locale = "pl", showBackground = true)
@Composable
private fun MiniTrendPreview() = ChartPreview(12, compact = true)

@Preview(name = "History · temperature", widthDp = 390, locale = "pl", showBackground = true)
@Composable
private fun TemperatureChartPreview() = ChartPreview(12, HistoryMetric.TEMPERATURE)

@Preview(name = "History · empty", widthDp = 390, showBackground = true)
@Composable
private fun EmptyChartPreview() = ChartPreview(0)

@Preview(name = "History · one observation", widthDp = 390, showBackground = true)
@Composable
private fun SingleChartPreview() = ChartPreview(1)

@Preview(name = "History · reboot and connection", widthDp = 390, showBackground = true)
@Composable
private fun EventsChartPreview() = ChartPreview(12, gaps = true)

@Preview(name = "History · missing voltage", widthDp = 390, showBackground = true)
@Composable
private fun MissingChartPreview() = ChartPreview(12, HistoryMetric.VOLTAGE, gaps = true)

@Preview(
    name = "History · 320 dp font scale 2", widthDp = 320, fontScale = 2f, showBackground = true
)
@Composable
private fun LargeTextChartPreview() = ChartPreview(12)
