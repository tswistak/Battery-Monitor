/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
import android.os.SystemClock
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.app.PersistentFragment
import codes.swistak.batterymonitor.app.StartupDialogViewModel
import codes.swistak.batterymonitor.monitoring.notifications.NotificationChannels
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringAvailability
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringConnection
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MonitoringDiagnosticsInstrumentedTest {
    @Test
    fun visible_enabled_monitor_refreshes_and_explicit_stop_rejects_further_diagnostic_requests() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val monitoringService = ComponentName(context, BatteryInfoService::class.java)

        @Suppress("DEPRECATION")
        fun serviceRunning(): Boolean = activityManager.getRunningServices(Int.MAX_VALUE).any {
            it.service == monitoringService
        }

        val arguments = InstrumentationRegistry.getArguments()
        val permissionBefore = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        val expectedPermission = when (arguments.getString("expected_notifications")) {
            null -> permissionBefore
            "allowed" -> PackageManager.PERMISSION_GRANTED
            "denied" -> PackageManager.PERMISSION_DENIED
            else -> error("expected_notifications must be allowed or denied")
        }
        if (Build.VERSION.SDK_INT >= 33) assertEquals(expectedPermission, permissionBefore)
        val liveUpdatesDisabled = when (arguments.getString("live_updates")) {
            null -> false
            "disabled" -> true
            else -> error("live_updates must be disabled when specified")
        }
        val mainChannelBlocked = when (arguments.getString("expected_main_channel")) {
            null -> false
            "blocked" -> true
            else -> error("expected_main_channel must be blocked when specified")
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (mainChannelBlocked) assertEquals(
            NotificationManager.IMPORTANCE_NONE,
            notificationManager.getNotificationChannel(NotificationChannels.CHAN_ID_MAIN)?.importance
        )
        val main = context.getSharedPreferences(SettingsContract.SP_MAIN_FILE, 0)
        val settings = context.getSharedPreferences(SettingsContract.SETTINGS_FILE, 0)
        val mainBefore = SettingsSnapshot.capture(main)
        val settingsBefore = SettingsSnapshot.capture(settings)
        val desiredBefore = MonitoringDesiredState.read(context)
        var scenario: ActivityScenario<BatteryInfoActivity>? = null
        try {
            context.stopService(Intent(context, BatteryInfoService::class.java))
            assertTrue(MonitoringDesiredState.write(context, true))
            val version = PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0)
            )
            assertTrue(
                main.edit().putBoolean(SettingsContract.KEY_FIRST_RUN, false)
                    .putBoolean(SettingsContract.KEY_MIGRATED_SERVICE_DESIRED, true)
                    .putLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, version).commit()
            )
            assertTrue(
                settings.edit().putBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false)
                    .putBoolean(SettingsContract.KEY_ENABLE_LOGGING, false).commit()
            )
            if (liveUpdatesDisabled) assertTrue(
                settings.edit().putString(SettingsContract.KEY_LIVE_UPDATE_DISPLAY, "never")
                    .commit()
            )
            scenario = ActivityScenario.launch(
                Intent(
                    context, BatteryInfoActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            lateinit var fragment: PersistentFragment
            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                fragment = activity.supportFragmentManager.findFragmentByTag(
                    PersistentFragment.FRAG_TAG
                ) as PersistentFragment
            }
            await("Visible enabled monitor did not produce a real snapshot") {
                fragment.monitoring.state.value.availability == MonitoringAvailability.LIVE
            }
            assertTrue("The live monitor must be an actual running service", serviceRunning())
            if (liveUpdatesDisabled) assertTrue(
                "Disabled Live Updates must use the normal monitoring notification",
                notificationManager.activeNotifications.any {
                    it.notification.channelId == NotificationChannels.CHAN_ID_MAIN
                } && notificationManager.activeNotifications.none {
                    it.notification.channelId == NotificationChannels.CHAN_ID_LIVE_UPDATE
                })
            val previous = fragment.monitoring.state.value
            val requestedAt = SystemClock.elapsedRealtime()
            val completed = CountDownLatch(1)
            var result = -1
            var completedAt = 0L
            var completedSequence = -1L
            var completedProcess: String? = null
            val callback = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    result = resultCode
                    completedAt = resultData?.getLong(
                        BatteryInfoService.DIAGNOSTICS_RESULT_TIMESTAMP
                    ) ?: 0L
                    completedSequence =
                        resultData?.getLong(MonitoringConnection.FIELD_SEQUENCE) ?: -1L
                    completedProcess = resultData?.getString(MonitoringConnection.FIELD_PROCESS_ID)
                    completed.countDown()
                }
            }
            // The separate service process has the app APK but cannot load this test APK's subclass.
            val parcel = Parcel.obtain()
            val receiver = try {
                callback.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                ResultReceiver.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
            assertTrue(
                BatteryInfoService.requestDiagnosticsCheck(context, receiver) in listOf(
                    BatteryInfoService.ServiceStartResult.START_REQUESTED,
                    BatteryInfoService.ServiceStartResult.FALLBACK_REQUESTED
                )
            )
            assertTrue("Real monitor refresh timed out", completed.await(10, TimeUnit.SECONDS))
            assertEquals(BatteryInfoService.DIAGNOSTICS_RESULT_OK, result)
            assertTrue(completedAt >= requestedAt)
            assertEquals(previous.processId, completedProcess)
            assertTrue(
                "Acknowledged an update that has not completed",
                completedSequence > previous.sequence
            )
            await("Refresh response did not publish the completed monitoring snapshot") {
                fragment.monitoring.state.value.let {
                    it.processId == previous.processId && it.sequence >= completedSequence && it.snapshot?.recentPredictorData != null
                }
            }

            scenario.onActivity { fragment.stopMonitoring() }
            assertFalse(MonitoringDesiredState.read(context))
            assertEquals(
                BatteryInfoService.ServiceStartResult.NOT_ALLOWED,
                BatteryInfoService.requestDiagnosticsCheck(context, receiver)
            )
            assertEquals(
                MonitoringAvailability.STALE, fragment.monitoring.state.value.availability
            )
            await("Explicit stop did not stop the actual monitoring service") { !serviceRunning() }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            val stoppedUntil = SystemClock.elapsedRealtime() + 1_000L
            do {
                assertFalse("Activity recreation restarted the stopped monitor", serviceRunning())
                Thread.sleep(25)
            } while (SystemClock.elapsedRealtime() < stoppedUntil)
            assertFalse(MonitoringDesiredState.read(context))
            if (Build.VERSION.SDK_INT >= 33) assertEquals(
                "Monitoring must preserve the notification permission state",
                expectedPermission,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            )
            if (mainChannelBlocked) assertEquals(
                "Monitoring must preserve a blocked main notification channel",
                NotificationManager.IMPORTANCE_NONE,
                notificationManager.getNotificationChannel(NotificationChannels.CHAN_ID_MAIN)?.importance
            )
            instrumentation.sendStatus(0, Bundle().apply {
                putString(
                    "stream",
                    "Monitoring diagnostics verified: API=${Build.VERSION.SDK_INT}, " + "notificationPermission=$expectedPermission, " + "liveUpdatesDisabled=$liveUpdatesDisabled, " + "mainChannelBlocked=$mainChannelBlocked, completedSequence=$completedSequence\n"
                )
            })
        } finally {
            scenario?.close()
            context.stopService(Intent(context, BatteryInfoService::class.java))
            assertTrue(SettingsSnapshot.apply(settings, settingsBefore))
            assertTrue(SettingsSnapshot.apply(main, mainBefore))
            assertTrue(MonitoringDesiredState.write(context, desiredBefore))
            if (desiredBefore) BatteryInfoService.startForegroundServiceSafely(context)
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (!condition() && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(25)
        }
        assertTrue(message, condition())
    }
}
