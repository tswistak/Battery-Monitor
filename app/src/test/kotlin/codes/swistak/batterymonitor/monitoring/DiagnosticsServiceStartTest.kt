/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DiagnosticsServiceStartTest {
    @Test
    fun diagnostic_refresh_cannot_start_a_stopped_monitor_and_does_not_require_notification_permission() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        )
        var starts = 0
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName {
                starts++
                return requireNotNull(service.component)
            }
        }
        val receiver = ResultReceiver(Handler(Looper.getMainLooper()))
        assertTrue(MonitoringDesiredState.write(context, false))
        assertEquals(
            BatteryInfoService.ServiceStartResult.NOT_ALLOWED,
            BatteryInfoService.requestDiagnosticsCheck(context, receiver)
        )
        assertEquals(0, starts)

        assertTrue(MonitoringDesiredState.write(context, true))
        assertEquals(
            BatteryInfoService.ServiceStartResult.START_REQUESTED,
            BatteryInfoService.requestDiagnosticsCheck(context, receiver)
        )
        assertEquals(1, starts)
    }
}
