/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.diagnostics

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.pm.PackageInfoCompat
import codes.swistak.batterymonitor.monitoring.BackgroundServiceWatchdog
import codes.swistak.batterymonitor.monitoring.MonitoringHealthStore
import codes.swistak.batterymonitor.settings.SettingsContract
import java.time.Instant
import java.util.Locale

internal object DiagnosticsReport {
    private const val MAX_PROCESS_EXITS = 20
    private const val MAX_EXIT_DESCRIPTION_LENGTH = 240

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            appendProcessExits(context, includeDebugLogs)
        }
        if (includeDebugLogs) DebugLogCollector.appendToReport(context, this)
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
        @SuppressLint("AppBundleLocaleChanges") val reportContext =
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })

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
            appendLine(
                "Battery Monitor: ${packageInfo.versionName} (${
                    PackageInfoCompat.getLongVersionCode(
                        packageInfo
                    )
                })"
            )
            appendLine(
                line(
                    "Notifications", booleanStatus(notificationManager.areNotificationsEnabled())
                )
            )
            appendLine(line("Root access", rootStatus))
            appendLine(line("Shizuku access", shizukuStatusText))
            appendLine(line("Battery optimization", optimizationStatus))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.getSystemService(ActivityManager::class.java)?.let {
                    appendLine(
                        line(
                            "Background restricted", booleanStatus(it.isBackgroundRestricted)
                        )
                    )
                }
            }
            appendLine(
                line(
                    "Monitoring service", monitoringStatus(
                        reportContext,
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
                        reportContext,
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

    @RequiresApi(Build.VERSION_CODES.R)
    private fun StringBuilder.appendProcessExits(context: Context, includeDebugLogs: Boolean) {
        appendLine().appendLine("Recent Android process exits")
        val exits = try {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, MAX_PROCESS_EXITS)
        } catch (_: RuntimeException) {
            null
        }
        if (exits.isNullOrEmpty()) {
            appendLine(if (exits == null) "Unavailable" else "None recorded by Android")
            return
        }
        for (exit in exits) {
            append("${Instant.ofEpochMilli(exit.timestamp)} ${exit.processName} (pid=${exit.pid}): ")
            append("${exitReasonName(exit.reason)} (${exit.reason}), status=${exit.status}, importance=${exit.importance}")
            if (includeDebugLogs) {
                exit.description?.lineSequence()?.firstOrNull()?.trim()
                    ?.take(MAX_EXIT_DESCRIPTION_LENGTH)?.takeIf(String::isNotEmpty)?.let {
                        append("; Android description: ").append(it)
                    }
            }
            appendLine()
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "Exited normally"
        ApplicationExitInfo.REASON_SIGNALED -> "OS signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
        ApplicationExitInfo.REASON_CRASH -> "Java crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_ANR -> "Application not responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Permission change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource use"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "Android user stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Dependency died"
        ApplicationExitInfo.REASON_OTHER -> "Other system reason"
        ApplicationExitInfo.REASON_FREEZER -> "App Freezer"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "Package state change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "Package updated"
        else -> "Unknown reason"
    }

    private fun monitoringStatus(
        context: Context, enabled: Boolean, now: Long, timestamp: Long, unavailableText: String
    ): String {
        if (!enabled) return "Disabled"
        if (timestamp !in 1..now) return unavailableText
        if (now - timestamp >= 300_000) return "Stale - no recent response"

        val age = DiagnosticsDurationFormatter.format(context, now - timestamp)
        return "Working - last response $age ago"
    }
}
