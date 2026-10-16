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
package codes.swistak.batterymonitor.monitoring

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.app.StartupDialogViewModel
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.logs.BatteryHistorySampler
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.logs.historyQuery
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsSnapshot
import codes.swistak.batterymonitor.ui.navigation.SectionNavigator
import codes.swistak.batterymonitor.ui.navigation.SectionOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.ExecutorService

class MonitoringRegressionInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var service: BatteryInfoService
    private lateinit var database: LogDatabase
    private val settings get() = context.getSharedPreferences(SettingsContract.SETTINGS_FILE, 0)
    private val servicePreferences
        get() = context.getSharedPreferences(SettingsContract.SP_SERVICE_FILE, 0)

    @Before
    fun setUp() {
        val target = instrumentation.targetContext
        directory = File(target.cacheDir, "monitoring-regression-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("${directory.name}-$name", mode)

            override fun getDatabasePath(name: String): File = File(directory, name)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)

            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(
                getDatabasePath(name).path, factory, errorHandler
            )
        }
        database = LogDatabase(context)
        service = BatteryInfoService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(service, context)
        set("settings", settings)
        set("spService", servicePreferences)
        set("spsEditor", servicePreferences.edit())
        set("res", context.resources)
        set("logDb", database)
        set("historySampler", BatteryHistorySampler())
        set("currentMicroAmps", -123_000L)
        set("now", 1_000L)
        set("info", BatteryInfo().apply {
            percent = 47
            temperature = 315
            voltage = 3_920
            remainingChargeUah = 2_100_000L
            health = BatteryInfo.HEALTH_GOOD
            lastStatusCtm = 100L
        })
        DisplayStrings.setResources(context.resources)
        assertTrue(settings.edit().putString(SettingsContract.KEY_MAX_LOG_AGE, "-1").commit())
    }

    @After
    fun tearDown() {
        (field("historyMaintenance") as ExecutorService).shutdownNow()
        database.close()
        for (name in listOf(
            SettingsContract.SETTINGS_FILE,
            SettingsContract.SP_SERVICE_FILE,
            SettingsContract.SP_MAIN_FILE
        )) {
            assertTrue(context.getSharedPreferences(name, 0).edit().clear().commit())
        }
        assertTrue(directory.deleteRecursively())
    }

    @Test
    fun explicit_stop_remains_authoritative_after_reopening_storage_despite_legacy_running_state() {
        assertTrue(settings.edit().putString(SettingsContract.KEY_AUTOSTART, "always").commit())
        assertTrue(
            servicePreferences.edit().putBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, true)
                .commit()
        )
        assertTrue(MonitoringDesiredState.write(context, desired = false))
        val reopened = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
        }
        assertFalse(MonitoringDesiredState.read(reopened))
        assertFalse(
            shouldStartMonitoringAfterSystemEvent(
                Intent.ACTION_MY_PACKAGE_REPLACED, "always", MonitoringDesiredState.read(reopened)
            )
        )
        assertTrue(MonitoringDesiredState.write(reopened, desired = true))
        assertTrue(MonitoringDesiredState.read(context))
    }

    @Test
    fun service_history_path_persists_actual_readings_and_resumes_after_database_reopen() {
        val fields = mapOf(
            "level" to 47,
            "scale" to 100,
            "plugged" to 0,
            "status" to 3,
            "temperature" to 315,
            "voltage" to 3_920
        )
        val recordHistory = BatteryInfoService::class.java.getDeclaredMethod(
            "recordHistory", Map::class.java, Long::class.javaPrimitiveType
        ).apply { isAccessible = true }
        recordHistory.invoke(service, fields, 500L)
        assertTrue((field("spsEditor") as SharedPreferences.Editor).commit())
        database.close()
        database = LogDatabase(context)
        set("logDb", database)
        set("now", 121_000L)
        recordHistory.invoke(service, fields, 120_500L)
        val expected = listOf(
            LogRecord(0, 47, 1_000L, 315, 3_920, -123_000L, 2_100_000L),
            LogRecord(200, 47, 121_000L, 315, 3_920, -123_000L, 2_100_000L)
        )
        val actual =
            database.readHistory(historyQuery(HistoryRangeState(0, Long.MAX_VALUE))) { rows ->
                rows.map { it.record }.toList()
            }
        assertEquals(expected, actual)
        assertTrue(MonitoringHealthStore.read(context).databaseHeartbeatElapsedTime > 0L)
    }

    @Test
    fun ordinary_notification_has_vital_details_even_when_live_update_details_are_disabled() {
        assertTrue(
            settings.edit().putString(SettingsContract.KEY_LIVE_UPDATE_DISPLAY, "never")
                .putString(SettingsContract.KEY_TEMPERATURE_UNIT, "celsius")
                .putBoolean(SettingsContract.KEY_EXPANDED_NOTIFICATION_DETAILS, true)
                .putBoolean(SettingsContract.KEY_EXPANDED_LIVE_UPDATE_DETAILS, false).commit()
        )
        val notification = call("prepareNotification") as Notification
        assertEquals(BatteryInfoService.CHAN_ID_MAIN, notification.channelId)
        val expanded = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue("Fallback notification lost its details: $expanded", expanded.isNotBlank())
        assertTrue(
            "Fallback details lost the actual temperature: $expanded",
            expanded.contains(DisplayStrings.formatTemp(315, false))
        )
        assertTrue(
            "Fallback details lost the actual voltage: $expanded",
            expanded.contains(DisplayStrings.formatVoltage(3_920))
        )
        assertTrue(
            settings.edit().putBoolean(SettingsContract.KEY_EXPANDED_NOTIFICATION_DETAILS, false)
                .commit()
        )
        assertFalse(
            (call("prepareNotification") as Notification).extras.containsKey(Notification.EXTRA_BIG_TEXT)
        )
    }

    @Test
    fun alarm_notification_action_opens_current_state_from_the_alarms_screen() {
        val target = instrumentation.targetContext
        val mainPreferences = target.getSharedPreferences(SettingsContract.SP_MAIN_FILE, 0)
        val targetSettings = target.getSharedPreferences(SettingsContract.SETTINGS_FILE, 0)
        val mainBefore = SettingsSnapshot.capture(mainPreferences)
        val settingsBefore = SettingsSnapshot.capture(targetSettings)
        val desiredBefore = MonitoringDesiredState.read(target)
        val current = PendingIntent.getActivity(
            target,
            9_130,
            Intent(
                target, BatteryInfoActivity::class.java
            ).putExtra(BatteryInfoService.EXTRA_CURRENT_INFO, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarms = PendingIntent.getActivity(
            target,
            9_131,
            Intent(
                target, BatteryInfoActivity::class.java
            ).putExtra(BatteryInfoService.EXTRA_EDIT_ALARMS, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        set("currentInfoPendingIntent", current)
        set("alarmsPendingIntent", alarms)
        var activity: BatteryInfoActivity? = null
        try {
            assertTrue(MonitoringDesiredState.write(target, desired = false))
            @Suppress("DEPRECATION") val version = PackageInfoCompat.getLongVersionCode(
                target.packageManager.getPackageInfo(target.packageName, 0)
            )
            assertTrue(
                mainPreferences.edit().putBoolean(SettingsContract.KEY_FIRST_RUN, false)
                    .putBoolean(SettingsContract.KEY_MIGRATED_SERVICE_DESIRED, true)
                    .putLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, version).commit()
            )
            activity = instrumentation.startActivitySync(
                Intent(
                    target, BatteryInfoActivity::class.java
                ).putExtra(BatteryInfoService.EXTRA_EDIT_ALARMS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ) as BatteryInfoActivity
            val launched = activity
            instrumentation.runOnMainSync {
                assertEquals(SectionOwner.ALARMS, navigator(launched).selected)
            }
            val notification = (call("alarmNotificationBuilder") as Notification.Builder).build()
            assertEquals(current, notification.contentIntent)
            notification.contentIntent.send()
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            var selected = SectionOwner.ALARMS
            while (SystemClock.elapsedRealtime() < deadline && selected != SectionOwner.CURRENT) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { selected = navigator(launched).selected }
            }
            assertEquals(
                "Alarm notification should show Current State", SectionOwner.CURRENT, selected
            )
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            current.cancel()
            alarms.cancel()
            assertTrue(MonitoringDesiredState.write(target, desiredBefore))
            assertTrue(SettingsSnapshot.apply(targetSettings, settingsBefore))
            assertTrue(SettingsSnapshot.apply(mainPreferences, mainBefore))
        }
    }

    private fun navigator(activity: BatteryInfoActivity): SectionNavigator =
        BatteryInfoActivity::class.java.getDeclaredField("navigator").apply { isAccessible = true }
            .get(activity) as SectionNavigator

    private fun set(name: String, value: Any) {
        BatteryInfoService::class.java.getDeclaredField(name).apply { isAccessible = true }
            .set(service, value)
    }

    private fun field(name: String): Any? =
        BatteryInfoService::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(service)

    private fun call(name: String): Any? =
        BatteryInfoService::class.java.getDeclaredMethod(name).apply { isAccessible = true }
            .invoke(service)
}
