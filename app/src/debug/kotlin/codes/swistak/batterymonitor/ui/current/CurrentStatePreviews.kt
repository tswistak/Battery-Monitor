/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.current

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.tooling.preview.Preview
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringAvailability
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringSnapshot
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringUiState
import codes.swistak.batterymonitor.monitoring.presentation.PredictionSnapshot
import codes.swistak.batterymonitor.settings.LongDurationFormat
import codes.swistak.batterymonitor.ui.theme.BatteryTheme
import codes.swistak.batterymonitor.ui.theme.ColorSource

private fun previewPrediction(direction: Int, target: Int, reached: Boolean = false) =
    PredictionSnapshot(direction, target, reached, 0L, 0, 9, 40)

@Composable
private fun PreviewCurrentState(
    status: Int = BatteryInfo.STATUS_DISCHARGING,
    level: Int = 72,
    direction: Int = BatteryInfo.Prediction.UNTIL_DRAINED,
    target: Int = 20,
    reached: Boolean = false,
    availability: MonitoringAvailability = MonitoringAvailability.LIVE,
    notificationsEnabled: Boolean = true,
    monitoringEnabled: Boolean = true,
    current: Double? = -240.0
) {
    DisplayStrings.setResources(LocalResources.current)
    val snapshot = MonitoringSnapshot(
        level,
        status,
        BatteryInfo.HEALTH_GOOD,
        if (status == BatteryInfo.STATUS_CHARGING || status == BatteryInfo.STATUS_NOT_CHARGING) BatteryInfo.PLUGGED_USB
        else BatteryInfo.PLUGGED_UNPLUGGED,
        305,
        3880,
        1820000L,
        status,
        BatteryInfo.PLUGGED_UNPLUGGED,
        80,
        System.currentTimeMillis() - 7_680_000L,
        previewPrediction(direction, target, reached),
        previewPrediction(
            if (status == BatteryInfo.STATUS_CHARGING) BatteryInfo.Prediction.UNTIL_CHARGED else direction,
            if (status == BatteryInfo.STATUS_CHARGING) 100 else 0
        ),
        System.currentTimeMillis()
    )
    BatteryTheme(colorSource = ColorSource.BatteryBlue) {
        CurrentStateScreen(
            currentStateModel(
                MonitoringUiState(availability, snapshot),
                monitoringEnabled = monitoringEnabled,
                notificationsEnabled = notificationsEnabled
            ),
            CurrentPreferences(
                true, false, 2000, 1, false, LongDurationFormat.DAYS_AND_HOURS, "-2", true
            ),
            CurrentReading(current, System.currentTimeMillis()),
            onToggleTarget = {},
            onSection = {},
            onBatteryUsage = {})
    }
}


@Preview(name = "Discharging", widthDp = 390, heightDp = 900, showBackground = true)
@Composable
private fun DischargingPreview() = PreviewCurrentState()

@Preview(name = "Charging", widthDp = 390, heightDp = 900, showBackground = true)
@Composable
private fun ChargingPreview() = PreviewCurrentState(
    status = BatteryInfo.STATUS_CHARGING,
    direction = BatteryInfo.Prediction.UNTIL_CHARGED,
    target = 80,
    current = 1200.0
)

@Preview(name = "Full", widthDp = 390, heightDp = 900, showBackground = true)
@Composable
private fun FullPreview() = PreviewCurrentState(
    status = BatteryInfo.STATUS_FULLY_CHARGED, level = 100, direction = BatteryInfo.Prediction.NONE
)

@Preview(name = "Target reached", widthDp = 390, heightDp = 900, showBackground = true)
@Composable
private fun TargetReachedPreview() = PreviewCurrentState(
    status = BatteryInfo.STATUS_CHARGING,
    level = 80,
    direction = BatteryInfo.Prediction.NONE,
    target = 80,
    reached = true
)

@Preview(name = "Below target", widthDp = 390, heightDp = 900, showBackground = true)
@Composable
private fun BelowTargetPreview() = PreviewCurrentState(level = 15, reached = true)

@Preview(name = "Plugged in, not charging", widthDp = 390, heightDp = 900, showBackground = true)
@Composable
private fun PausedPreview() = PreviewCurrentState(status = BatteryInfo.STATUS_NOT_CHARGING)

@Preview(name = "Very low battery", widthDp = 320, heightDp = 900, showBackground = true)
@Composable
private fun LowPreview() = PreviewCurrentState(level = 5)

@Preview(name = "Insufficient estimate", widthDp = 390, heightDp = 900, showBackground = true)
@Composable
private fun InsufficientPreview() = PreviewCurrentState(direction = BatteryInfo.Prediction.NONE)

@Preview(name = "Stale data", widthDp = 390, heightDp = 900, showBackground = true)
@Composable
private fun StalePreview() = PreviewCurrentState(availability = MonitoringAvailability.STALE)

@Preview(name = "Current unavailable", widthDp = 390, heightDp = 900, showBackground = true)
@Composable
private fun CurrentUnavailablePreview() = PreviewCurrentState(current = null)

@Preview(name = "Notifications denied", widthDp = 390, heightDp = 900, showBackground = true)
@Composable
private fun NotificationsDeniedPreview() = PreviewCurrentState(notificationsEnabled = false)

@Preview(name = "Monitoring disabled", widthDp = 390, heightDp = 900, showBackground = true)
@Composable
private fun MonitoringDisabledPreview() =
    PreviewCurrentState(monitoringEnabled = false, current = null)


@Preview(
    name = "320 dp, font scale 2",
    widthDp = 320,
    heightDp = 900,
    fontScale = 2f,
    showBackground = true
)
@Composable
private fun LargeTextPreview() = PreviewCurrentState()

@Preview(name = "Tablet content", widthDp = 1000, heightDp = 700, showBackground = true)
@Composable
private fun TabletPreview() = PreviewCurrentState()

@Preview(
    name = "Tablet large text",
    widthDp = 1000,
    heightDp = 700,
    fontScale = 2f,
    showBackground = true
)
@Composable
private fun TabletLargeTextPreview() = PreviewCurrentState()
