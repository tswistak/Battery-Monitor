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
package codes.swistak.batterymonitor.app

internal enum class StartupPresentation {
    ONBOARDING, CHANGELOG, BATTERY_OPTIMIZATION, REQUEST_NOTIFICATIONS, NONE
}

internal enum class NotificationOnboarding {
    LEGACY, LIVE_UPDATES_ON, LIVE_UPDATES_OFF
}

internal fun notificationOnboarding(
    supportsLiveUpdates: Boolean, liveUpdatesEnabled: Boolean
): NotificationOnboarding = when {
    !supportsLiveUpdates -> NotificationOnboarding.LEGACY
    liveUpdatesEnabled -> NotificationOnboarding.LIVE_UPDATES_ON
    else -> NotificationOnboarding.LIVE_UPDATES_OFF
}

internal data class StartupEnvironment(
    val notificationPermissionRequired: Boolean,
    val notificationsGranted: Boolean,
    val batteryOptimized: Boolean
)

internal data class StartupProgress(
    val firstRun: Boolean,
    val changelogPending: Boolean,
    val notificationAttempted: Boolean = false,
    val batteryPromptShown: Boolean = false
)

internal fun nextStartupPresentation(
    progress: StartupProgress, environment: StartupEnvironment
): StartupPresentation = when {
    progress.changelogPending -> StartupPresentation.CHANGELOG
    environment.notificationPermissionRequired && !environment.notificationsGranted && !progress.notificationAttempted -> {
        if (progress.firstRun) StartupPresentation.ONBOARDING
        else StartupPresentation.REQUEST_NOTIFICATIONS
    }

    environment.batteryOptimized && !progress.batteryPromptShown -> StartupPresentation.BATTERY_OPTIMIZATION

    else -> StartupPresentation.NONE
}

internal data class ChangelogVersionDecision(
    val baselineVersion: Long?, val pending: Boolean
)

internal fun changelogVersionDecision(
    currentVersion: Long,
    acknowledgedVersion: Long?,
    firstRun: Boolean,
    firstInstallTime: Long,
    lastUpdateTime: Long
): ChangelogVersionDecision {
    if (acknowledgedVersion != null) {
        return ChangelogVersionDecision(null, currentVersion > acknowledgedVersion)
    }
    val freshInstall = firstRun && firstInstallTime == lastUpdateTime
    return ChangelogVersionDecision(
        baselineVersion = if (freshInstall) currentVersion else null, pending = !freshInstall
    )
}

internal fun acknowledgeVersion(currentVersion: Long, acknowledgedVersion: Long?): Long =
    maxOf(currentVersion, acknowledgedVersion ?: currentVersion)
