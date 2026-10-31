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

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.SavedStateHandle
import codes.swistak.batterymonitor.settings.SettingsContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class StartupDialogControllerTest {
    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var preferencePrefix: String
    private val granted = StartupEnvironment(
        notificationPermissionRequired = true, notificationsGranted = true, batteryOptimized = false
    )
    private val denied = StartupEnvironment(
        notificationPermissionRequired = true, notificationsGranted = false, batteryOptimized = true
    )
    private val preferences
        get() = context.getSharedPreferences(
            SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE
        )
    private val installedVersion: Long
        get() {
            val installed = context.packageManager.getPackageInfo(context.packageName, 0)
            return PackageInfoCompat.getLongVersionCode(installed)
        }

    @Before
    fun setUp() {
        preferencePrefix = "startup-dialog-test-${System.nanoTime()}"
        val target = RuntimeEnvironment.getApplication()
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("$preferencePrefix-$name", mode)
        }
        application = object : Application() {
            init {
                attachBaseContext(context)
            }
        }
    }

    @After
    fun tearDown() {
        val target = RuntimeEnvironment.getApplication()
        for (name in listOf(SettingsContract.SP_MAIN_FILE, SettingsContract.SETTINGS_FILE)) {
            target.deleteSharedPreferences("$preferencePrefix-$name")
        }
    }

    @Test
    fun legacy_upgrade_is_detected_before_first_run_changes_and_retries_until_acknowledged() {
        preferences.edit().putBoolean(SettingsContract.KEY_FIRST_RUN, false).commit()
        val handle = SavedStateHandle()
        val controller = controller(handle)
        assertFalse(preferences.contains(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION))
        repeat(4) { controller.advance(denied) }
        assertEquals(StartupPresentation.CHANGELOG, controller.presentation)
        assertNull(controller.systemAction)
        assertTrue(controller.blocksContentDialogs)

        val restored = controller(copy(handle))
        assertEquals(StartupPresentation.CHANGELOG, restored.presentation)
        restored.advance(denied)
        assertEquals(StartupPresentation.CHANGELOG, restored.presentation)
        val nextLaunch = controller()
        nextLaunch.advance(granted)
        assertEquals(StartupPresentation.CHANGELOG, nextLaunch.presentation)
        assertFalse(preferences.contains(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION))
        assertTrue(nextLaunch.acknowledgeChangelog())
        assertEquals(
            installedVersion,
            preferences.getLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, -1)
        )
        val acknowledged = controller()
        acknowledged.advance(granted)
        assertEquals(StartupPresentation.NONE, acknowledged.presentation)
    }

    @Test
    fun notification_requests_are_consumed_once_and_wait_across_recreation_without_overlay() {
        baseline(firstRun = true)
        val handle = SavedStateHandle()
        val controller = controller(handle)
        controller.advance(denied)
        assertEquals(StartupPresentation.ONBOARDING, controller.presentation)
        controller.requestNotifications()
        assertEquals(StartupPresentation.NONE, controller.presentation)
        assertTrue(controller.blocksContentDialogs)
        assertFalse(preferences.getBoolean(SettingsContract.KEY_FIRST_RUN, true))
        val beforeDispatchHandle = copy(handle)
        val beforeDispatch = controller(beforeDispatchHandle)
        assertEquals(StartupSystemAction.NOTIFICATIONS, beforeDispatch.takeSystemAction())
        assertNull(beforeDispatch.takeSystemAction())
        repeat(4) { beforeDispatch.advance(denied) }
        beforeDispatch.onLeavingActivity()
        assertEquals(StartupPresentation.NONE, beforeDispatch.presentation)
        assertTrue(beforeDispatch.blocksContentDialogs)

        val afterDispatch = controller(copy(beforeDispatchHandle))
        afterDispatch.advance(denied)
        assertNull(afterDispatch.takeSystemAction())
        afterDispatch.onWindowFocusChanged(true, denied)
        assertEquals(StartupPresentation.BATTERY_OPTIMIZATION, afterDispatch.presentation)
        afterDispatch.dismissBatteryPrompt()
        repeat(4) { afterDispatch.advance(denied) }
        assertEquals(StartupPresentation.NONE, afterDispatch.presentation)
        assertFalse(afterDispatch.blocksContentDialogs)
    }

    @Test
    fun cancelling_onboarding_and_unexpected_system_return_do_not_duplicate_requests() {
        baseline(firstRun = true)
        val controller = controller()
        controller.advance(denied.copy(batteryOptimized = false))
        controller.cancelOnboarding()
        repeat(4) { controller.advance(denied.copy(batteryOptimized = false)) }
        assertEquals(StartupPresentation.NONE, controller.presentation)
        assertNull(controller.systemAction)
        assertFalse(preferences.getBoolean(SettingsContract.KEY_FIRST_RUN, true))

        controller.openLiveUpdateSettings()
        assertEquals(StartupSystemAction.LIVE_UPDATE_SETTINGS, controller.takeSystemAction())
        controller.onLeavingActivity()
        controller.onWindowFocusChanged(true, granted)
        repeat(4) { controller.advance(granted) }
        assertEquals(StartupPresentation.NONE, controller.presentation)
        assertNull(controller.systemAction)
        assertFalse(controller.blocksContentDialogs)
    }

    @Test
    fun acknowledgement_high_water_mark_and_installed_code_ignore_version_name_suffixes() {
        baseline(firstRun = false, acknowledged = installedVersion + 1)
        val controller = controller()
        assertEquals(installedVersion, controller.versionCode)
        controller.advance(granted)
        assertTrue(controller.acknowledgeChangelog())
        assertEquals(StartupPresentation.NONE, controller.presentation)
        assertEquals(
            installedVersion + 1,
            preferences.getLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, -1)
        )
    }

    @Test
    fun restored_changelog_cannot_override_an_acknowledged_or_downgraded_version() {
        for (acknowledged in listOf(installedVersion, installedVersion + 1)) {
            baseline(firstRun = false, acknowledged = acknowledged)
            val handle = SavedStateHandle(
                mapOf("startup_presentation" to StartupPresentation.CHANGELOG.name)
            )
            val restored = controller(handle)
            repeat(3) { restored.advance(granted) }
            assertEquals(StartupPresentation.NONE, restored.presentation)
            assertFalse(restored.blocksContentDialogs)
            assertEquals(StartupPresentation.NONE.name, handle.get<String>("startup_presentation"))
            assertEquals(
                acknowledged,
                preferences.getLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, -1)
            )
        }
    }

    @Test
    fun a_pending_upgrade_replaces_a_restored_battery_warning_before_requesting_permissions() {
        baseline(firstRun = false, acknowledged = installedVersion - 1)
        val handle = SavedStateHandle(
            mapOf(
                "startup_presentation" to StartupPresentation.BATTERY_OPTIMIZATION.name,
                "startup_battery_prompt_shown" to true
            )
        )
        val restored = controller(handle)
        repeat(3) { restored.advance(denied) }
        assertEquals(StartupPresentation.CHANGELOG, restored.presentation)
        assertNull(restored.systemAction)
        assertTrue(restored.blocksContentDialogs)
        assertEquals(StartupPresentation.CHANGELOG.name, handle.get<String>("startup_presentation"))
        assertEquals(
            installedVersion - 1,
            preferences.getLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, -1)
        )
    }

    private fun controller(handle: SavedStateHandle = SavedStateHandle()) =
        StartupDialogViewModel(application, handle)

    private fun baseline(firstRun: Boolean, acknowledged: Long = installedVersion) {
        assertTrue(
            preferences.edit().putBoolean(SettingsContract.KEY_FIRST_RUN, firstRun)
                .putLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, acknowledged).commit()
        )
    }

    private fun copy(handle: SavedStateHandle) =
        SavedStateHandle(handle.keys().associateWith { handle[it] })
}
