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

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.view.inspector.WindowInspectorCompat
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.monitoring.MonitoringDesiredState
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsSnapshot
import codes.swistak.batterymonitor.ui.alarms.AlarmsViewModel
import codes.swistak.batterymonitor.ui.history.FullscreenChartActivity
import codes.swistak.batterymonitor.ui.navigation.SectionNavigator
import codes.swistak.batterymonitor.ui.navigation.SectionOwner
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import codes.swistak.batterymonitor.ui.theme.Brightness
import codes.swistak.batterymonitor.ui.theme.ColorSource
import codes.swistak.batterymonitor.ui.theme.batteryColorScheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StartupDialogsInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
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
        val target = instrumentation.targetContext
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
        for (name in listOf(SettingsContract.SP_MAIN_FILE, SettingsContract.SETTINGS_FILE)) {
            instrumentation.targetContext.deleteSharedPreferences("$preferencePrefix-$name")
        }
    }

    @Test
    fun legacy_upgrade_is_detected_before_first_run_changes_and_retries_until_acknowledged() {
        preferences.edit().putBoolean(SettingsContract.KEY_FIRST_RUN, false).commit()
        val handle = SavedStateHandle()
        val controller = controller(handle)
        assertFalse(preferences.contains(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION))
        main { repeat(4) { controller.advance(denied) } }
        assertEquals(StartupPresentation.CHANGELOG, controller.presentation)
        assertNull(controller.systemAction)
        assertTrue(controller.blocksContentDialogs)

        val restored = controller(copy(handle))
        assertEquals(StartupPresentation.CHANGELOG, restored.presentation)
        main { restored.advance(denied) }
        assertEquals(StartupPresentation.CHANGELOG, restored.presentation)
        val nextLaunch = controller()
        main { nextLaunch.advance(granted) }
        assertEquals(StartupPresentation.CHANGELOG, nextLaunch.presentation)
        assertFalse(preferences.contains(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION))
        main { assertTrue(nextLaunch.acknowledgeChangelog()) }
        assertEquals(
            installedVersion,
            preferences.getLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, -1)
        )
        val acknowledged = controller()
        main { acknowledged.advance(granted) }
        assertEquals(StartupPresentation.NONE, acknowledged.presentation)
    }

    @Test
    fun notification_requests_are_consumed_once_and_wait_across_recreation_without_overlay() {
        baseline(firstRun = true)
        val handle = SavedStateHandle()
        val controller = controller(handle)
        main { controller.advance(denied) }
        assertEquals(StartupPresentation.ONBOARDING, controller.presentation)
        main { controller.requestNotifications() }
        assertEquals(StartupPresentation.NONE, controller.presentation)
        assertTrue(controller.blocksContentDialogs)
        assertFalse(preferences.getBoolean(SettingsContract.KEY_FIRST_RUN, true))
        val beforeDispatchHandle = copy(handle)
        val beforeDispatch = controller(beforeDispatchHandle)
        main {
            assertEquals(StartupSystemAction.NOTIFICATIONS, beforeDispatch.takeSystemAction())
            assertNull(beforeDispatch.takeSystemAction())
            repeat(4) { beforeDispatch.advance(denied) }
            beforeDispatch.onLeavingActivity()
        }
        assertEquals(StartupPresentation.NONE, beforeDispatch.presentation)
        assertTrue(beforeDispatch.blocksContentDialogs)

        val afterDispatch = controller(copy(beforeDispatchHandle))
        main {
            afterDispatch.advance(denied)
            assertNull(afterDispatch.takeSystemAction())
            afterDispatch.onWindowFocusChanged(true, denied)
        }
        assertEquals(StartupPresentation.BATTERY_OPTIMIZATION, afterDispatch.presentation)
        main {
            afterDispatch.dismissBatteryPrompt()
            repeat(4) { afterDispatch.advance(denied) }
        }
        assertEquals(StartupPresentation.NONE, afterDispatch.presentation)
        assertFalse(afterDispatch.blocksContentDialogs)
    }

    @Test
    fun cancelling_onboarding_and_unexpected_system_return_do_not_duplicate_requests() {
        baseline(firstRun = true)
        val controller = controller()
        main {
            controller.advance(denied.copy(batteryOptimized = false))
            controller.cancelOnboarding()
            repeat(4) { controller.advance(denied.copy(batteryOptimized = false)) }
        }
        assertEquals(StartupPresentation.NONE, controller.presentation)
        assertNull(controller.systemAction)
        assertFalse(preferences.getBoolean(SettingsContract.KEY_FIRST_RUN, true))

        main {
            controller.openLiveUpdateSettings()
            assertEquals(StartupSystemAction.LIVE_UPDATE_SETTINGS, controller.takeSystemAction())
            controller.onLeavingActivity()
            controller.onWindowFocusChanged(true, granted)
            repeat(4) { controller.advance(granted) }
        }
        assertEquals(StartupPresentation.NONE, controller.presentation)
        assertNull(controller.systemAction)
        assertFalse(controller.blocksContentDialogs)
    }

    @Test
    fun acknowledgement_high_water_mark_and_installed_code_ignore_version_name_suffixes() {
        baseline(firstRun = false, acknowledged = installedVersion + 1)
        val controller = controller()
        assertEquals(installedVersion, controller.versionCode)
        main {
            controller.advance(granted)
            assertTrue(controller.acknowledgeChangelog())
        }
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
            main { repeat(3) { restored.advance(granted) } }
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
        main { repeat(3) { restored.advance(denied) } }
        assertEquals(StartupPresentation.CHANGELOG, restored.presentation)
        assertNull(restored.systemAction)
        assertTrue(restored.blocksContentDialogs)
        assertEquals(StartupPresentation.CHANGELOG.name, handle.get<String>("startup_presentation"))
        assertEquals(
            installedVersion - 1,
            preferences.getLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, -1)
        )
    }

    @Test
    fun a_measurement_intent_does_not_stack_its_sheet_under_the_upgrade_changelog() {
        val target = instrumentation.targetContext
        val mainPreferences =
            target.getSharedPreferences(SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE)
        val hadFirstRun = mainPreferences.contains(SettingsContract.KEY_FIRST_RUN)
        val firstRun = mainPreferences.getBoolean(SettingsContract.KEY_FIRST_RUN, true)
        val hadAcknowledgement =
            mainPreferences.contains(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION)
        val acknowledgement =
            mainPreferences.getLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, 0)
        val monitor = instrumentation.addMonitor(BatteryInfoActivity::class.java.name, null, false)
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val oldFlags = info.flags
        info.flags = oldFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        try {
            assertTrue(
                mainPreferences.edit().putBoolean(SettingsContract.KEY_FIRST_RUN, false)
                    .putLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, installedVersion - 1)
                    .commit()
            )
            instrumentation.startActivitySync(
                Intent(
                    target, BatteryInfoActivity::class.java
                ).putExtra(BatteryInfoActivity.EXTRA_SECTION, "current")
                    .putExtra(BatteryInfoActivity.EXTRA_DETAIL, "measurement:voltage")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            val title = target.getString(R.string.changelog_title)
            await { find(title) != null }
            instrumentation.waitForIdleSync()
            await { dialogs() == 1 }
            assertEquals("Only the startup dialog may have a Compose dialog window", 1, dialogs())
            assertEquals(
                installedVersion - 1,
                mainPreferences.getLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, -1)
            )
            val original = requireNotNull(monitor.lastActivity)
            main { original.recreate() }
            await {
                monitor.lastActivity != null && monitor.lastActivity !== original && !monitor.lastActivity.isDestroyed
            }
            await { find(title) != null && dialogs() == 1 }
            assertEquals("Recreation keeps exactly one changelog dialog", 1, dialogs())
            assertEquals(
                installedVersion - 1,
                mainPreferences.getLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, -1)
            )
        } finally {
            main { monitor.lastActivity?.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(monitor)
            info.flags = oldFlags
            automation.serviceInfo = info
            val restore = mainPreferences.edit()
            if (hadFirstRun) restore.putBoolean(SettingsContract.KEY_FIRST_RUN, firstRun)
            else restore.remove(SettingsContract.KEY_FIRST_RUN)
            if (hadAcknowledgement) restore.putLong(
                StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, acknowledgement
            )
            else restore.remove(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION)
            assertTrue("Restore startup preferences after the Activity test", restore.commit())
        }
    }

    @Test
    fun dirty_alarm_drawer_navigation_survives_activity_recreation_and_cancel_or_discard() {
        val target = instrumentation.targetContext
        val mainPreferences =
            target.getSharedPreferences(SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE)
        val targetSettings =
            target.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
        val mainBefore = SettingsSnapshot.capture(mainPreferences)
        val settingsBefore = SettingsSnapshot.capture(targetSettings)
        val desiredBefore = MonitoringDesiredState.read(target)
        val monitor = instrumentation.addMonitor(BatteryInfoActivity::class.java.name, null, false)
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val oldFlags = info.flags
        info.flags = oldFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        try {
            assertTrue(MonitoringDesiredState.write(target, desired = false))
            assertTrue(
                mainPreferences.edit().putBoolean(SettingsContract.KEY_FIRST_RUN, false)
                    .putLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, installedVersion)
                    .commit()
            )
            val original = instrumentation.startActivitySync(
                Intent(
                    target, BatteryInfoActivity::class.java
                ).putExtra(BatteryInfoActivity.EXTRA_SECTION, SectionOwner.ALARMS.route)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ) as BatteryInfoActivity
            lateinit var alarms: AlarmsViewModel
            lateinit var startup: StartupDialogViewModel
            main {
                alarms = ViewModelProvider(original)[AlarmsViewModel::class.java]
                startup = ViewModelProvider(original)[StartupDialogViewModel::class.java]
            }
            await {
                startup.presentation == StartupPresentation.NONE || find(target.getString(R.string.battery_optimized)) != null
            }
            if (startup.presentation == StartupPresentation.BATTERY_OPTIMIZATION) {
                click(target.getString(R.string.cancel))
            }
            await { !startup.blocksContentDialogs && alarms.state.value.canMutate }
            openActivityDrawer(original)
            main {
                val destination = activityDrawerItem(original, R.string.nav_current)
                alarms.beginThreshold(
                    "charge_rises", false, target.resources.configuration.locales[0]
                )
                alarms.changeDraftInput("47")
                assertTrue(alarms.hasUnsavedChanges)
                assertTrue(destination.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            val confirmation = target.getString(R.string.alarms_discard_confirmation)
            await { find(confirmation) != null && dialogs() == 1 }
            val focusStartedAt = android.os.SystemClock.uptimeMillis()
            var focusReady = false
            var focusWindowCount = 0
            var lastFocusWindows = ""
            var manualNodeWindowId = -1
            try {
                await {
                    focusReady = false
                    val manualConfirmation = find(confirmation)
                    manualNodeWindowId = manualConfirmation?.windowId ?: -1
                    val windows = applicationWindows()
                    focusWindowCount = windows.size
                    val states = windows.map { window ->
                        val active = window.isActive
                        val focused = window.isFocused
                        val platformContainsConfirmation =
                            window.root?.findAccessibilityNodeInfosByText(confirmation)
                                ?.isNotEmpty() == true
                        val manualContainsConfirmation =
                            manualConfirmation != null && manualNodeWindowId == window.id
                        if (active && focused && manualContainsConfirmation) focusReady = true
                        "id=${window.id},type=${window.type},active=$active,focused=$focused," + "platformContainsConfirmation=$platformContainsConfirmation," + "manualContainsConfirmation=$manualContainsConfirmation"
                    }
                    lastFocusWindows = states.take(8).joinToString(";")
                    focusReady
                }
            } finally {
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("testDiagnostic", "alarmConfirmationFocus")
                    putLong("elapsedMillis", android.os.SystemClock.uptimeMillis() - focusStartedAt)
                    putBoolean("ready", focusReady)
                    putInt("windowCount", focusWindowCount)
                    putInt("manualNodeWindowId", manualNodeWindowId)
                    putString("windows", lastFocusWindows)
                })
            }
            await {
                var closed = false
                main { closed = activityDrawerClosed(original) }
                closed
            }
            main {
                val saved = Bundle()
                instrumentation.callActivityOnSaveInstanceState(original, saved)
                assertEquals(SectionOwner.ALARMS.route, saved.getString("nav_selected"))
                assertEquals(
                    SectionOwner.CURRENT.route,
                    saved.getBundle("pending_alarm_navigation")?.getString("section")
                )
                assertEquals("47", alarms.state.value.draft?.thresholdInput)
            }
            main { original.recreate() }
            await {
                monitor.lastActivity != null && monitor.lastActivity !== original && !monitor.lastActivity.isDestroyed
            }
            val restored = monitor.lastActivity as BatteryInfoActivity
            await { find(confirmation) != null && dialogs() == 1 }
            click(target.getString(R.string.cancel))
            await { find("47") != null && find(confirmation) == null && dialogs() == 1 }
            main {
                assertEquals(
                    "47",
                    ViewModelProvider(restored)[AlarmsViewModel::class.java].state.value.draft?.thresholdInput
                )
                restored.selectSection(SectionOwner.CURRENT)
            }
            await { find(confirmation) != null && dialogs() == 1 }
            click(target.getString(R.string.alarms_discard))
            await { dialogs() == 0 && alarms.state.value.draft == null }
            instrumentation.waitForIdleSync()
            main {
                assertTrue(
                    "Discard must first activate Current State",
                    activityHasVisibleLabel(restored, R.string.nav_current)
                )
            }
            openActivityDrawer(restored)
            main {
                val destination = activityDrawerItem(restored, R.string.nav_history)
                assertTrue(destination.config[SemanticsActions.OnClick].action?.invoke() == true)
                val beforeExternal = activityNavigationState(restored)
                assertEquals(
                    "History must dispatch synchronously before the external intent",
                    SectionOwner.HISTORY.route,
                    beforeExternal.getString("nav_selected")
                )
                instrumentation.callActivityOnNewIntent(
                    restored, Intent(
                        target, BatteryInfoActivity::class.java
                    ).putExtra(BatteryInfoActivity.EXTRA_SECTION, SectionOwner.HELP.route)
                )
            }
            val externalStartedAt = android.os.SystemClock.uptimeMillis()
            var externalReady = false
            var lastExternalState = Bundle()
            try {
                await {
                    main {
                        val drawerClosed = activityDrawerClosed(restored)
                        val helpVisible = activityHasVisibleLabel(restored, R.string.nav_help)
                        val navigation = activityNavigationState(restored)
                        val nodes = activitySemantics(restored.window.decorView)
                        lastExternalState = Bundle().apply {
                            putBoolean("drawerClosed", drawerClosed)
                            putBoolean("helpVisible", helpVisible)
                            putString("navSelected", navigation.getString("nav_selected"))
                            putString("navDetail", navigation.getString("nav_detail"))
                            putBoolean(
                                "pendingAlarmNavigation",
                                navigation.getBundle("pending_alarm_navigation") != null
                            )
                            putString("lifecycle", restored.lifecycle.currentState.name)
                            putBoolean(
                                "fragmentStateSaved", restored.supportFragmentManager.isStateSaved
                            )
                            putString(
                                "visibleLabels",
                                nodes.filterNot(::hiddenActivityNode).flatMap {
                                    it.config.getOrNull(SemanticsProperties.Text).orEmpty()
                                }.take(12).joinToString("|") { it.text.take(80) })
                            putString(
                                "drawerItems",
                                nodes.filter { it.config.contains(SemanticsProperties.Selected) }
                                    .take(12).joinToString("|") {
                                        "${
                                            it.config.getOrNull(SemanticsProperties.Text).orEmpty()
                                                .joinToString { text -> text.text }.take(80)
                                        }:" + "selected=${it.config[SemanticsProperties.Selected]},hidden=${
                                            hiddenActivityNode(
                                                it
                                            )
                                        }"
                                    })
                        }
                        externalReady = drawerClosed && helpVisible
                    }
                    externalReady
                }
            } finally {
                lastExternalState.putString("testDiagnostic", "externalHelpReadiness")
                lastExternalState.putLong(
                    "elapsedMillis", android.os.SystemClock.uptimeMillis() - externalStartedAt
                )
                lastExternalState.putBoolean("ready", externalReady)
                instrumentation.sendStatus(0, lastExternalState)
            }
            main {
                val saved = activityNavigationState(restored)
                assertEquals(SectionOwner.HELP.route, saved.getString("nav_selected"))
                assertNull(saved.getString("nav_detail"))
                assertNull(
                    "Discard must consume the pending navigation",
                    saved.getBundle("pending_alarm_navigation")
                )
            }
            main { restored.openHelp("licenses") }
            await {
                var opened = false
                main {
                    val saved = activityNavigationState(restored)
                    opened =
                        saved.getString("nav_selected") == SectionOwner.HELP.route && saved.getString(
                            "nav_detail"
                        ) == "help:licenses" && activityHasVisibleLabel(
                            restored, R.string.help_licenses_title
                        )
                }
                opened
            }
            main {
                val sameOwner =
                    activityDrawerItem(restored, R.string.nav_help, includeHidden = true)
                assertTrue(sameOwner.config[SemanticsProperties.Selected])
                assertTrue(sameOwner.config[SemanticsActions.OnClick].action?.invoke() == true)
                val saved = activityNavigationState(restored)
                assertEquals(SectionOwner.HELP.route, saved.getString("nav_selected"))
                assertNull(
                    "Same-owner navigation must leave the license detail for the section root",
                    saved.getString("nav_detail")
                )
            }
            await {
                var rootVisible = false
                main {
                    rootVisible = activityDrawerClosed(restored) && activityHasVisibleLabel(
                        restored, R.string.nav_help
                    ) && activityHasVisibleLabel(restored, R.string.help_cat_version)
                }
                rootVisible
            }
        } finally {
            main { monitor.lastActivity?.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(monitor)
            info.flags = oldFlags
            automation.serviceInfo = info
            assertTrue(MonitoringDesiredState.write(target, desiredBefore))
            assertTrue(SettingsSnapshot.apply(targetSettings, settingsBefore))
            assertTrue(SettingsSnapshot.apply(mainPreferences, mainBefore))
        }
    }

    @Test
    fun changelog_uses_the_app_palette_is_non_dismissable_and_acknowledges_only_on_confirmation() {
        baseline(firstRun = false, acknowledged = installedVersion - 1)
        val controller = controller()
        main { controller.advance(granted) }
        val target = instrumentation.targetContext
        val monitor =
            instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val oldFlags = info.flags
        info.flags = oldFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        var expectedSurface = 0
        var confirmations = 0
        try {
            instrumentation.startActivitySync(
                FullscreenChartActivity.intent(
                    target, HistoryMetric.LEVEL, HistoryRangeState(0, 1), "custom"
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            await {
                monitor.lastActivity != null && !monitor.lastActivity.isDestroyed && monitor.lastActivity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            val activity = monitor.lastActivity as FullscreenChartActivity
            main {
                activity.setContent {
                    CompositionLocalProvider(LocalContext provides context) {
                        AppBatteryTheme {
                            val surface = AlertDialogDefaults.containerColor
                            SideEffect { expectedSurface = surface.toArgb() }
                            Surface(Modifier.fillMaxSize()) {
                                Text(context.getString(R.string.app_full_name))
                            }
                            StartupDialogs(controller) { confirmations++ }
                        }
                    }
                }
            }
            val title = context.getString(R.string.changelog_title)
            await { find(title) != null }
            if (Build.VERSION.SDK_INT >= 28) assertTrue(find(title)!!.isHeading)
            await { dialogs() == 1 }
            assertEquals("Only one Compose dialog window may be attached", 1, dialogs())
            assertTrue(
                find(
                    context.getString(
                        R.string.changelog_version, controller.versionName
                    )
                ) != null
            )
            assertEquals(
                installedVersion - 1,
                preferences.getLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, -1)
            )
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            assertTrue(find(title) != null)
            assertEquals(StartupPresentation.CHANGELOG, controller.presentation)

            val settings =
                context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
            for (source in listOf("BatteryBlue", "Dynamic")) {
                val surfaces = mutableSetOf<Int>()
                for (brightness in listOf("Light", "Dark", "TrueBlack")) {
                    main {
                        settings.edit().putString(SettingsContract.KEY_COLOR_SOURCE, source)
                            .putString(SettingsContract.KEY_BRIGHTNESS, brightness).commit()
                    }
                    instrumentation.waitForIdleSync()
                    val requestedSurface = batteryColorScheme(
                        context, ColorSource.valueOf(source), Brightness.valueOf(brightness), false
                    ).surfaceContainerHigh.toArgb()
                    await { expectedSurface == requestedSurface }
                    await { surfacePixels(expectedSurface) > 50 }
                    surfaces += expectedSurface
                    assertEquals("Changing theme must not attach another dialog", 1, dialogs())
                }
                assertEquals(
                    "Light, dark and true-black dialogs have distinct surfaces", 3, surfaces.size
                )
            }
            click(context.getString(R.string.changelog_got_it))
            await { controller.presentation == StartupPresentation.NONE }
            await { dialogs() == 0 }
            assertEquals(1, confirmations)
            assertEquals(
                installedVersion,
                preferences.getLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, -1)
            )
        } finally {
            main { monitor.lastActivity?.finish() }
            instrumentation.removeMonitor(monitor)
            info.flags = oldFlags
            automation.serviceInfo = info
        }
    }

    private fun controller(handle: SavedStateHandle = SavedStateHandle()): StartupDialogViewModel {
        lateinit var controller: StartupDialogViewModel
        main { controller = StartupDialogViewModel(application, handle) }
        return controller
    }

    private fun baseline(firstRun: Boolean, acknowledged: Long = installedVersion) {
        preferences.edit().putBoolean(SettingsContract.KEY_FIRST_RUN, firstRun)
            .putLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, acknowledged).commit()
    }

    private fun copy(handle: SavedStateHandle) =
        SavedStateHandle(handle.keys().associateWith { handle[it] })

    private fun applicationWindows() = instrumentation.uiAutomation.windows.filter {
        it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() == instrumentation.targetContext.packageName
    }

    @SuppressLint("RestrictedApi")
    private fun dialogs(): Int {
        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)

        fun owners(view: View): List<SemanticsOwner> {
            val owner = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }
                ?.invoke(view) as? SemanticsOwner
            if (owner != null) return listOf(owner)
            if (view !is ViewGroup) return emptyList()
            return (0 until view.childCount).flatMap { owners(view.getChildAt(it)) }
        }

        var count = 0
        main {
            count = WindowInspectorCompat.getGlobalWindowViews().flatMap(::owners)
                .flatMap { descendants(it.rootSemanticsNode) }
                .count { it.config.contains(SemanticsProperties.IsDialog) }
        }
        return count
    }

    private fun activityNavigationState(activity: BatteryInfoActivity): Bundle {
        val fields = BatteryInfoActivity::class.java
        val navigator = fields.getDeclaredField("navigator").apply { isAccessible = true }
            .get(activity) as SectionNavigator
        val pendingState = fields.getDeclaredField($$"pendingAlarmNavigation$delegate")
            .apply { isAccessible = true }.get(activity) as State<*>
        val pending = pendingState.value as AlarmEditorNavigation?
        return Bundle().also {
            navigator.save(it)
            it.putBundle("pending_alarm_navigation", pending?.save())
        }
    }

    private fun activitySemantics(root: View): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)

        fun owners(view: View): List<SemanticsOwner> {
            val owner = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }
                ?.invoke(view) as? SemanticsOwner
            if (owner != null) return listOf(owner)
            if (view !is ViewGroup) return emptyList()
            return (0 until view.childCount).flatMap { owners(view.getChildAt(it)) }
        }
        return owners(root).flatMap { descendants(it.rootSemanticsNode) }
    }

    private fun openActivityDrawer(activity: BatteryInfoActivity) {
        main {
            val menu = activitySemantics(activity.window.decorView).first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.contains(activity.getString(R.string.nav_open_menu)) == true
            }
            assertTrue(menu.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        await {
            var open = false
            main {
                open = activitySemantics(activity.window.decorView).any {
                    it.config.contains(SemanticsProperties.Selected) && !hiddenActivityNode(it)
                }
            }
            open
        }
    }

    private fun activityDrawerItem(
        activity: BatteryInfoActivity, label: Int, includeHidden: Boolean = false
    ): SemanticsNode = activitySemantics(activity.window.decorView).first {
        it.config.contains(SemanticsProperties.Selected) && (includeHidden || !hiddenActivityNode(
            it
        )) && it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
            text.text == activity.getString(label)
        } == true
    }

    private fun activityDrawerClosed(activity: BatteryInfoActivity): Boolean {
        val destinations = activitySemantics(activity.window.decorView).filter {
            it.config.contains(SemanticsProperties.Selected)
        }
        return destinations.isNotEmpty() && destinations.all(::hiddenActivityNode)
    }

    private fun activityHasVisibleLabel(activity: BatteryInfoActivity, label: Int): Boolean =
        activitySemantics(activity.window.decorView).any {
            !hiddenActivityNode(it) && it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                text.text == activity.getString(label)
            } == true
        }

    private fun hiddenActivityNode(node: SemanticsNode): Boolean =
        node.config.contains(SemanticsProperties.HideFromAccessibility) || node.parent?.let(::hiddenActivityNode) == true

    private fun surfacePixels(color: Int): Int {
        val dialog = applicationWindows().firstOrNull { it.isActive } ?: return 0
        val bounds = Rect()
        dialog.getBoundsInScreen(bounds)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return 0
        try {
            var count = 0
            for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(bitmap.height) step 5) {
                for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(bitmap.width) step 5) {
                    if (bitmap.getPixel(x, y) == color) count++
                }
            }
            return count
        } finally {
            bitmap.recycle()
        }
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.text?.toString() == text) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> search(child)?.let { return it } }
            }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
    }

    private fun click(text: String) {
        await { find(text) != null }
        var node = find(text)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync()
    }

    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                action()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun await(condition: () -> Boolean) {
        val until = android.os.SystemClock.uptimeMillis() + 10_000
        while (!condition() && android.os.SystemClock.uptimeMillis() < until) {
            android.os.SystemClock.sleep(50)
        }
        assertTrue("Startup dialogs did not reach the expected state", condition())
    }
}
