/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.diagnostics

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import codes.swistak.batterymonitor.monitoring.BackgroundServiceWatchdog
import codes.swistak.batterymonitor.monitoring.MonitoringHealthStore
import codes.swistak.batterymonitor.settings.SettingsContract
import java.time.Instant
import java.util.Locale

internal object DiagnosticsReport {
    enum class ShizukuStatus {
        NOT_RUNNING, PERMISSION_GRANTED, PERMISSION_MISSING
    }

    fun create(
        context: Context,
        rootAvailable: Boolean?,
        shizukuStatus: ShizukuStatus,
        includeDebugLogs: Boolean = false
    ): String = buildString {
        append(summary(context, rootAvailable, shizukuStatus))
        if (includeDebugLogs) {
            val file = DebugLogCollector.logFile(context)
            if (file.isFile) {
                appendLine(); appendLine("Debug logs"); append(file.readText())
            }
        }
    }

    private fun summary(
        context: Context, rootAvailable: Boolean?, shizukuStatus: ShizukuStatus
    ): String {
        val settings = context.getSharedPreferences(
            SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
        )
        val healthState = MonitoringHealthStore.read(context)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val now = SystemClock.elapsedRealtime()
        val heartbeat = healthState.serviceHeartbeatElapsedTime
        val databaseHeartbeat = healthState.databaseHeartbeatElapsedTime

        fun booleanStatus(value: Boolean): String = if (value) "Yes" else "No"
        fun line(label: String, value: CharSequence): String = "$label: $value"

        val rootStatus = when (rootAvailable) {
            true -> "Yes"
            false -> "Not available"
            null -> "Not checked"
        }
        val shizukuStatusText = when (shizukuStatus) {
            ShizukuStatus.NOT_RUNNING -> "Not running"
            ShizukuStatus.PERMISSION_GRANTED -> "Yes"
            ShizukuStatus.PERMISSION_MISSING -> "Permission missing"
        }
        val optimizationStatus =
            if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) "Unrestricted" else "Restricted"

        return buildString {
            appendLine("Diagnostics")
            appendLine("Generated: ${Instant.now()}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Battery Monitor: ${packageInfo.versionName} (${versionCode(packageInfo)})")
            appendLine(
                line(
                    "Notifications", booleanStatus(notificationManager.areNotificationsEnabled())
                )
            )
            appendLine(line("Root access", rootStatus))
            appendLine(line("Shizuku access", shizukuStatusText))
            appendLine(line("Battery optimization", optimizationStatus))
            appendLine(
                line(
                    "Monitoring service", monitoringStatus(
                        BackgroundServiceWatchdog.isServiceDesired(context),
                        now,
                        heartbeat,
                        "No recent response"
                    )
                )
            )
            appendLine(
                line(
                    "Logging database", monitoringStatus(
                        settings.getBoolean(SettingsContract.KEY_ENABLE_LOGGING, true),
                        now,
                        databaseHeartbeat,
                        "No recent successful database access"
                    )
                )
            )
            appendLine(
                line(
                    "Debug logs",
                    booleanStatus(settings.getBoolean(SettingsContract.KEY_DEBUG_LOGGING, false))
                )
            )
        }
    }

    private fun monitoringStatus(
        enabled: Boolean, now: Long, timestamp: Long, unavailableText: String
    ): String {
        if (!enabled) return "Disabled"
        if (timestamp !in 1..now) return unavailableText
        if (now - timestamp >= 300_000) return "Stale - no recent response"
        val age = DiagnosticsDurationFormatter.format(Locale.ENGLISH, now - timestamp)
        return "Working - last response $age ago"
    }

    @Suppress("DEPRECATION")
    private fun versionCode(packageInfo: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
}
