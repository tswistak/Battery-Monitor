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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
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
import codes.swistak.batterymonitor.monitoring.notifications.NotificationChannels
import codes.swistak.batterymonitor.monitoring.presentation.CurrentDemand
import codes.swistak.batterymonitor.privileged.PrivilegedAccess
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsSnapshot
import codes.swistak.batterymonitor.ui.navigation.SectionNavigator
import codes.swistak.batterymonitor.ui.navigation.SectionOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        (field("currentAcquisition") as CurrentAcquisition).close()
        (field("currentWorker") as ExecutorService).shutdownNow()
        instrumentation.runOnMainSync {
            (field("mHandler") as Handler).removeCallbacks(field("currentTick") as Runnable)
        }
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
        assertEquals(NotificationChannels.CHAN_ID_MAIN, notification.channelId)
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
            val version = PackageInfoCompat.getLongVersionCode(
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

    @Test
    fun current_refresh_joins_one_acquisition_without_history_prediction_alarm_or_widget_updates() {
        val pending = ArrayDeque<Runnable>()
        var reads = 0
        set("clientMessengers", hashMapOf<Messenger, Any>())
        set(
            "currentAcquisition", CurrentAcquisition(
                readNow = {
                reads++
                BatteryCurrentReading(
                    -400_000, "fake/current_now", BatteryMeasurementTime(2_000L, 2_000L)
                )
            },
                readAverage = { error("Average current was not requested") },
                executor = { pending.addLast(it) },
                deliver = { it() })
        )
        assertTrue(MonitoringDesiredState.write(context, true))
        val client = Messenger(Handler(Looper.getMainLooper()))
        val sequence = field("monitoringSequence")
        instrumentation.runOnMainSync {
            registerCurrentClient(client, 4)
            sendCurrentDemand(client, 4, 20, CurrentDemand(3_600_000, false))
            serviceMessage(Message.obtain().apply {
                what = BatteryInfoService.RemoteConnection.SERVICE_REFRESH_CURRENT
                replyTo = client
                arg1 = 4
                data.putLong("current_token", 20)
            })
            assertEquals(1, pending.size)
            pending.removeFirst().run()
        }
        instrumentation.waitForIdleSync()
        assertEquals(1, reads)
        assertEquals(sequence, field("monitoringSequence"))
        assertEquals(-123_000L, field("currentMicroAmps"))
        assertEquals(1_000L, field("now"))
        assertNull(field("lastWidgetSnapshot"))
        assertEquals(-400_000L, (field("latestCurrent") as CurrentSample).now?.microAmps)
        assertTrue(
            database.readHistory(
                historyQuery(
                    HistoryRangeState(
                        0, Long.MAX_VALUE
                    )
                )
            ) { it.none() })
        instrumentation.runOnMainSync { service.onUnbind(null) }
    }

    @Test
    fun current_demand_expires_on_cancel_unregister_binder_death_and_last_unbind() {
        set("clientMessengers", hashMapOf<Messenger, Any>())
        set(
            "currentAcquisition",
            CurrentAcquisition(
                readNow = { null },
                readAverage = { null },
                executor = { },
                deliver = { it() })
        )
        assertTrue(MonitoringDesiredState.write(context, true))
        for (ending in listOf("cancel", "unregister", "death", "unbind")) {
            val client = Messenger(Handler(Looper.getMainLooper()))
            instrumentation.runOnMainSync {
                registerCurrentClient(client, 1)
                sendCurrentDemand(client, 1, 1, CurrentDemand(3_600_000, false))
                val registration = currentClients().getValue(client)
                when (ending) {
                    "cancel" -> sendCurrentDemand(client, 1, 2, null)
                    "unregister" -> serviceMessage(Message.obtain().apply {
                        what = BatteryInfoService.RemoteConnection.SERVICE_UNREGISTER_CLIENT
                        replyTo = client
                        arg1 = 1
                    })

                    "death" -> {
                        val death = registration.javaClass.getDeclaredField("deathRecipient")
                            .apply { isAccessible = true }
                            .get(registration) as IBinder.DeathRecipient
                        death.binderDied()
                    }

                    "unbind" -> service.onUnbind(null)
                }
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val registration = currentClients()[client]
                if (registration != null) {
                    assertNull(
                        registration.javaClass.getDeclaredField("demand")
                            .apply { isAccessible = true }.get(registration)
                    )
                }
                service.onUnbind(null)
            }
        }
        assertTrue(currentClients().isEmpty())
    }

    @Test
    fun average_demand_upgrades_the_in_flight_service_read_without_waiting_for_the_next_interval() {
        val pending = ArrayDeque<Runnable>()
        var nowReads = 0
        var averageReads = 0
        set("clientMessengers", hashMapOf<Messenger, Any>())
        set("currentAcquisition", CurrentAcquisition(readNow = {
            nowReads++
            BatteryCurrentReading(
                -400_000, "fake/current_now", BatteryMeasurementTime(2_000, 2_000)
            )
        }, readAverage = {
            averageReads++
            BatteryCurrentReading(
                -350_000, "fake/current_average", BatteryMeasurementTime(2_001, 2_001)
            )
        }, executor = { pending.addLast(it) }, deliver = { it() }))
        assertTrue(MonitoringDesiredState.write(context, true))
        val client = Messenger(Handler(Looper.getMainLooper()))
        instrumentation.runOnMainSync {
            registerCurrentClient(client, 1)
            sendCurrentDemand(client, 1, 1, CurrentDemand(3_600_000, false))
            sendCurrentDemand(client, 1, 2, CurrentDemand(3_600_000, true))
            assertEquals(1, pending.size)
            pending.removeFirst().run()
        }
        instrumentation.waitForIdleSync()
        assertEquals(1, nowReads)
        assertEquals(1, averageReads)
        assertEquals(-350_000L, (field("latestCurrent") as CurrentSample).selected(true)?.microAmps)
        instrumentation.runOnMainSync { service.onUnbind(null) }
    }

    @Test
    fun delayed_unknown_time_fallback_cannot_replace_a_newer_direct_observation() {
        val record = BatteryInfoService::class.java.getDeclaredMethod(
            "recordDirectCurrent", CurrentSample::class.java
        ).apply { isAccessible = true }
        val publish = BatteryInfoService::class.java.getDeclaredMethod(
            "publishCurrent", CurrentSample::class.java, Long::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val direct = CurrentSample(
            BatteryCurrentReading(
                -500_000, "direct", BatteryMeasurementTime(2_000, 2_000)
            ), null
        )
        instrumentation.runOnMainSync {
            val oldRequest = record.invoke(service, CurrentSample(null, null)) as Long
            val newRequest = record.invoke(service, direct) as Long
            publish.invoke(service, direct, newRequest)
            publish.invoke(
                service,
                CurrentSample(BatteryCurrentReading(-400_000, "old privileged"), null),
                oldRequest
            )
            assertEquals(direct, field("latestCurrent"))
        }
    }

    @Test
    fun old_generation_cannot_remove_a_reregistered_client_or_install_current_demand() {
        set("clientMessengers", hashMapOf<Messenger, Any>())
        val client = Messenger(Handler(Looper.getMainLooper()))
        instrumentation.runOnMainSync {
            registerCurrentClient(client, 1)
            registerCurrentClient(client, 2)
            sendCurrentDemand(client, 1, 99, CurrentDemand(1_000, false))
            serviceMessage(Message.obtain().apply {
                what = BatteryInfoService.RemoteConnection.SERVICE_UNREGISTER_CLIENT
                replyTo = client
                arg1 = 1
            })
            val registration = currentClients().getValue(client)
            assertNull(
                registration.javaClass.getDeclaredField("demand")
                .apply { isAccessible = true }.get(registration))
            service.onUnbind(null)
        }
    }

    @Test
    fun current_demand_cannot_restart_explicitly_disabled_monitoring() {
        set("clientMessengers", hashMapOf<Messenger, Any>())
        var acquisitions = 0
        set(
            "currentAcquisition", CurrentAcquisition(
                readNow = { null },
                readAverage = { null },
                executor = { acquisitions++ },
                deliver = { it() })
        )
        assertTrue(MonitoringDesiredState.write(context, false))
        val client = Messenger(Handler(Looper.getMainLooper()))
        instrumentation.runOnMainSync {
            registerCurrentClient(client, 1)
            sendCurrentDemand(client, 1, 1, CurrentDemand(1_000, false))
            val registration = currentClients().getValue(client)
            assertNull(
                registration.javaClass.getDeclaredField("demand")
                .apply { isAccessible = true }.get(registration))
            assertEquals(0, acquisitions)
            assertFalse(MonitoringDesiredState.read(context))
            service.onUnbind(null)
        }
    }

    @Test
    fun service_destruction_stops_acquisition_and_revokes_process_privileged_access() {
        val wasEnabled = PrivilegedAccess.isEnabled()
        try {
            set("clientMessengers", hashMapOf<Messenger, Any>())
            instrumentation.runOnMainSync {
                PrivilegedAccess.setEnabled(true)
                call("stopAcquisition")
                assertFalse(PrivilegedAccess.isEnabled())
                assertEquals(true, field("destroyed"))
                assertTrue((field("currentWorker") as ExecutorService).isShutdown)
                (field("currentAcquisition") as CurrentAcquisition).read(false) {
                    error("Destroyed service must not deliver another native reading")
                }
            }
        } finally {
            instrumentation.runOnMainSync { PrivilegedAccess.setEnabled(wasEnabled) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun currentClients() = field("clientMessengers") as MutableMap<Messenger, Any>

    private fun registerCurrentClient(client: Messenger, generation: Int) {
        BatteryInfoService::class.java.getDeclaredMethod(
            "registerClient", Messenger::class.java, Int::class.javaPrimitiveType
        ).apply { isAccessible = true }.invoke(service, client, generation)
    }

    private fun sendCurrentDemand(
        client: Messenger, generation: Int, token: Long, demand: CurrentDemand?
    ) {
        serviceMessage(Message.obtain().apply {
            what = BatteryInfoService.RemoteConnection.SERVICE_SET_CURRENT_DEMAND
            replyTo = client
            arg1 = generation
            data = demand?.toBundle(token) ?: android.os.Bundle()
                .apply { putLong("current_token", token) }
        })
    }

    private fun serviceMessage(message: Message) {
        val type =
            BatteryInfoService::class.java.declaredClasses.first { it.simpleName == "MessageHandler" }
        val handler = type.getDeclaredConstructor(BatteryInfoService::class.java)
            .apply { isAccessible = true }.newInstance(service) as Handler
        handler.handleMessage(message)
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
