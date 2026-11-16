/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class DiagnosticsReportTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `Android process exits remain bounded and only the detailed report has descriptions`() {
        val activityManager = shadowOf(context.getSystemService(ActivityManager::class.java))
        val timestamp = Instant.parse("2026-11-14T10:00:00Z")
        repeat(20) { index ->
            activityManager.addApplicationExitInfo(
                ApplicationExitInfoBuilder.newBuilder()
                    .setProcessName(if (index == 0) "old-process" else context.packageName)
                    .setPid(300 + index).setReason(ApplicationExitInfo.REASON_CRASH).build()
            )
        }
        val description = "stop codes.swistak.batterymonitor due to test"
        val continuation = "private data on the next line"
        val trace = "private trace data"
        val process = "codes.swistak.batterymonitor.BIS"
        activityManager.addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder().setProcessName(process).setPid(999)
                .setReason(ApplicationExitInfo.REASON_USER_REQUESTED)
                .setDescription("$description\n$continuation")
                .setTimestamp(timestamp.toEpochMilli()).setTraceInputStream(trace.byteInputStream())
                .build()
        )

        val standard = report(includeDebugLogs = false)
        assertTrue(standard.contains("$timestamp $process (pid=999)"))
        assertTrue(standard.contains("USER_REQUESTED (${ApplicationExitInfo.REASON_USER_REQUESTED})"))
        assertFalse(standard.contains("old-process"))
        assertFalse(standard.contains(description))

        val detailed = report(includeDebugLogs = true)
        assertTrue(detailed.contains(description))
        assertFalse(detailed.contains(continuation))
        assertFalse(detailed.contains(trace))
    }

    @Test
    @Config(sdk = [28])
    fun `diagnostics report is available before Android process exit history`() {
        assertTrue(report(includeDebugLogs = false).contains("Monitoring service"))
    }

    private fun report(includeDebugLogs: Boolean) = DiagnosticsReport.create(
        context, null, DiagnosticsReport.ShizukuStatus.NOT_RUNNING, includeDebugLogs
    )
}
