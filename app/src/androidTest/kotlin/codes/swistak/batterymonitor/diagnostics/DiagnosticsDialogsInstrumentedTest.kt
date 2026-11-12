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
package codes.swistak.batterymonitor.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.monitoring.BackgroundServiceWatchdog
import codes.swistak.batterymonitor.monitoring.MonitoringDesiredState
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingDiagnosticStore
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.testsupport.ComposeTestActivity
import codes.swistak.batterymonitor.testsupport.awaitCondition
import codes.swistak.batterymonitor.testsupport.clickSemantics
import codes.swistak.batterymonitor.testsupport.composeWindowNodes
import codes.swistak.batterymonitor.ui.diagnostics.DiagnosticReportDialog
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import codes.swistak.batterymonitor.ui.theme.Brightness
import codes.swistak.batterymonitor.ui.theme.ColorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import codes.swistak.batterymonitor.testsupport.runOnMain as main

class DiagnosticsDialogsInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var activity: ComposeTestActivity

    private lateinit var fragment: DiagnosticsFragment
    private lateinit var state: DiagnosticsDialogState

    // This SDK exposes primaryClip as read-only to the Kotlin compiler.
    @Suppress("UsePropertyAccessSyntax")
    @Test
    fun report_option_changes_hide_private_content_and_only_the_latest_result_can_be_copied() {
        class Request(val include: Boolean) {
            val result = CompletableFuture<String>()
            val finished = CountDownLatch(1)
        }

        val requests = LinkedBlockingQueue<Request>()
        val pending = CopyOnWriteArrayList<Request>()
        fun nextRequest(include: Boolean): Request =
            requireNotNull(requests.poll(10, TimeUnit.SECONDS)).also {
                assertEquals(include, it.include)
            }

        fun shown(text: String): Boolean {
            var present = false
            main { present = hasText(text) }
            return present
        }
        withHost(actionsOnly = true) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            var previousClip: ClipData? = null
            val clipboardMarker = "Unchanged clipboard"
            main {
                previousClip = clipboard.primaryClip
                clipboard.setPrimaryClip(ClipData.newPlainText("test", clipboardMarker))
                activity.setContent {
                    AppBatteryTheme {
                        DiagnosticReportDialog(create = { include ->
                            val request = Request(include)
                            pending.add(request)
                            requests.add(request)
                            try {
                                request.result.get(10, TimeUnit.SECONDS)
                            } finally {
                                request.finished.countDown()
                            }
                        }, onDismiss = {})
                    }
                }
            }
            try {
                nextRequest(false).result.complete("Public report")
                await { buttonDisabled(R.string.charging_diagnostics_copy) == false }
                click(R.string.diag_include_raw)
                nextRequest(true).result.complete("Private device and package details")
                await { shown("Private device and package details") }

                main {
                    val oldCopy =
                        button(R.string.charging_diagnostics_copy).config[SemanticsActions.OnClick].action
                    assertTrue(
                        button(R.string.diag_include_raw).config[SemanticsActions.OnClick].action?.invoke() == true
                    )
                    // A queued click must not copy the old private preview before recomposition.
                    requireNotNull(oldCopy).invoke()
                    assertEquals(
                        clipboardMarker, clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                    )
                }
                val obsolete = nextRequest(false)
                main {
                    assertFalse(hasText("Private device and package details"))
                    assertTrue(buttonDisabled(R.string.charging_diagnostics_copy) == true)
                    assertTrue(buttonDisabled(R.string.charging_diagnostics_save) == true)
                }
                click(R.string.diag_include_raw)
                val latest = nextRequest(true)
                latest.result.complete("Latest requested private report")
                await { shown("Latest requested private report") }
                obsolete.result.complete("Obsolete public report")
                assertTrue(obsolete.finished.await(5, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                main {
                    assertTrue(hasText("Latest requested private report"))
                    assertFalse(hasText("Obsolete public report"))
                }
                click(R.string.charging_diagnostics_copy)
                main {
                    assertEquals(
                        "Latest requested private report",
                        clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                    )
                }

                click(R.string.diag_include_raw)
                nextRequest(false).result.completeExceptionally(IllegalStateException("Report unavailable"))
                await { shown(context.getString(R.string.diagnostics_export_failed)) }
                main {
                    assertFalse(hasText("Latest requested private report"))
                    assertTrue(buttonDisabled(R.string.charging_diagnostics_copy) == true)
                    assertTrue(buttonDisabled(R.string.charging_diagnostics_save) == true)
                }
            } finally {
                pending.forEach {
                    it.result.completeExceptionally(IllegalStateException("Report fixture closed"))
                }
                main {
                    previousClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
                }
            }
        }
    }

    @Test
    fun report_title_distinguishes_monitoring_and_oem_comparison() {
        withHost(actionsOnly = true) {
            for ((dialog, title, otherTitle) in listOf(
                Triple(
                    DiagnosticsDialog.MONITOR_REPORT,
                    R.string.diagnostics_export,
                    R.string.charging_diagnostics_report
                ), Triple(
                    DiagnosticsDialog.CHARGING_REPORT,
                    R.string.charging_diagnostics_report,
                    R.string.diagnostics_export
                )
            )) {
                main { state.open(dialog) }
                await { dialogs() == 1 }
                main {
                    assertTrue(hasText(context.getString(title)))
                    assertFalse(hasText(context.getString(otherTitle)))
                }
                click(R.string.cancel)
                await { dialogs() == 0 }
            }
        }
    }

    @Test
    fun capture_completion_does_not_open_or_replace_dialogs() {
        for (actionsOnly in listOf(false, true)) withHost(actionsOnly) {
            val monitoring = BackgroundServiceWatchdog.isServiceDesired(context)
            val snapshots = ChargingDiagnosticStore.read(context).size
            main {
                activity.supportFragmentManager.beginTransaction()
                    .setMaxLifecycle(fragment, Lifecycle.State.STARTED).commitNow()
                activity.supportFragmentManager.beginTransaction()
                    .setMaxLifecycle(fragment, Lifecycle.State.RESUMED).commitNow()
                repeat(2) {
                    assertTrue(state.beginCapture())
                    state.finishCapture()
                    assertNull(state.dialog)
                }
            }
            await { dialogs() == 0 }
            main {
                fragment.monitorActions.getValue("monitor_stop").onClick()
                state.finishCapture()
            }
            await { dialogs() == 1 }
            assertEquals(DiagnosticsDialog.STOP_MONITORING, state.dialog)
            click(R.string.cancel)
            await { dialogs() == 0 }
            assertNull(state.dialog)
            assertEquals(monitoring, BackgroundServiceWatchdog.isServiceDesired(context))
            assertEquals(snapshots, ChargingDiagnosticStore.read(context).size)
        }
    }

    @Test
    fun app_operation_refresh_checks_database_once_and_ignores_results_after_pause() {
        val monitoring = BackgroundServiceWatchdog.isServiceDesired(context)
        try {
            assertTrue(MonitoringDesiredState.write(context, false))
            withHost(actionsOnly = true) {
                preferences().edit().putBoolean(SettingsContract.KEY_ENABLE_LOGGING, false).commit()
                val skipped = AtomicInteger()
                main { fragment.refreshAppOperation { skipped.incrementAndGet() } }
                assertEquals(1, skipped.get())
                assertFalse(fragment.appOperationRefreshing)
                assertFalse(BackgroundServiceWatchdog.isServiceDesired(context))
                assertFalse(preferences().getBoolean(SettingsContract.KEY_ENABLE_LOGGING, true))

                preferences().edit().putBoolean(SettingsContract.KEY_ENABLE_LOGGING, true).commit()
                val overlapping = AtomicInteger()
                main {
                    fragment.monitorActions.getValue("diagnostics_database").onClick()
                    assertTrue(fragment.appOperationRefreshing)
                    fragment.refreshAppOperation { overlapping.incrementAndGet() }
                }
                await { !fragment.appOperationRefreshing }
                assertEquals(0, overlapping.get())
                val completed = AtomicInteger()
                val started = SystemClock.elapsedRealtime()
                main {
                    fragment.refreshAppOperation { completed.incrementAndGet() }
                    assertTrue(fragment.appOperationRefreshing)
                    fragment.refreshAppOperation { completed.incrementAndGet() }
                }
                await { completed.get() == 1 && !fragment.appOperationRefreshing }
                assertTrue(fragment.latestDatabaseResponseElapsedTime >= started)
                assertFalse(BackgroundServiceWatchdog.isServiceDesired(context))

                val obsolete = AtomicInteger()
                val latest = AtomicInteger()
                main {
                    fragment.refreshAppOperation { obsolete.incrementAndGet() }
                    activity.supportFragmentManager.beginTransaction()
                        .setMaxLifecycle(fragment, Lifecycle.State.STARTED).commitNow()
                    activity.supportFragmentManager.beginTransaction()
                        .setMaxLifecycle(fragment, Lifecycle.State.RESUMED).commitNow()
                    fragment.refreshAppOperation { latest.incrementAndGet() }
                }
                await { latest.get() == 1 && !fragment.appOperationRefreshing }
                assertEquals(0, obsolete.get())
                assertFalse(BackgroundServiceWatchdog.isServiceDesired(context))
            }
        } finally {
            assertTrue(MonitoringDesiredState.write(context, monitoring))
        }
    }

    @Test
    fun condition_selection_numeric_validation_and_pause_preserve_draft_without_capture() {
        for (actionsOnly in listOf(false, true)) withHost(actionsOnly) {
            val snapshots = ChargingDiagnosticStore.read(context).size
            main { fragment.monitorActions.getValue("charging_diagnostics_capture").onClick() }
            await { dialogs() == 1 }
            await { buttonDisabled(R.string.okay) == true }
            main { assertTrue(button(R.string.okay).config.contains(SemanticsProperties.Disabled)) }
            click(R.string.charging_diagnostics_condition_adaptive)
            click(R.string.cancel)
            await { dialogs() == 0 }
            assertFalse(state.capturing)

            main { fragment.monitorActions.getValue("charging_diagnostics_capture").onClick() }
            await { dialogs() == 1 }
            click(R.string.charging_diagnostics_condition_fixed)
            click(R.string.okay)
            await { dialogs() == 1 && fieldOrNull() != null }
            assertEquals(DiagnosticsDialog.FIXED_CHARGING_LIMIT, state.dialog)
            main { assertEquals("80", field().config[SemanticsProperties.EditableText].text) }
            for (invalid in listOf("", "59", "101", "80.5", "abc")) {
                input(invalid)
                await { buttonDisabled(R.string.okay) == true }
                main {
                    assertTrue(button(R.string.okay).config.contains(SemanticsProperties.Disabled))
                    field().config[SemanticsActions.OnImeAction].action?.invoke()
                    assertFalse(state.capturing)
                }
            }
            input("90")
            await { buttonDisabled(R.string.okay) == false }
            main {
                assertFalse(button(R.string.okay).config.contains(SemanticsProperties.Disabled))
                activity.supportFragmentManager.beginTransaction()
                    .setMaxLifecycle(fragment, Lifecycle.State.STARTED).commitNow()
            }
            await { dialogs() == 0 }
            main {
                activity.supportFragmentManager.beginTransaction()
                    .setMaxLifecycle(fragment, Lifecycle.State.RESUMED).commitNow()
            }
            await { dialogs() == 1 && fieldOrNull() != null }
            main { assertEquals("90", field().config[SemanticsProperties.EditableText].text) }
            click(R.string.cancel)
            await("Cancel must dismiss the restored fixed-limit draft (actionsOnly=$actionsOnly)") {
                dialogs() == 0 && main { state.dialog == null }
            }
            assertFalse(state.capturing)
            assertEquals(snapshots, ChargingDiagnosticStore.read(context).size)
        }
    }

    private fun withHost(
        actionsOnly: Boolean, test: () -> Unit
    ) {
        assumeTrue("Dialog window inspection requires Android 10", Build.VERSION.SDK_INT >= 29)
        val preferences = preferences()
        val keys = listOf(
            SettingsContract.KEY_COLOR_SOURCE,
            SettingsContract.KEY_BRIGHTNESS,
            SettingsContract.KEY_ENABLE_LOGGING
        )
        val previous = keys.associateWith { preferences.all[it] }
        preferences.edit()
            .putString(SettingsContract.KEY_COLOR_SOURCE, ColorSource.BatteryBlue.name)
            .putString(SettingsContract.KEY_BRIGHTNESS, Brightness.Light.name).commit()
        val scenario = ActivityScenario.launch(ComposeTestActivity::class.java)
        try {
            scenario.onActivity { activity = it }
            main {
                fragment = DiagnosticsFragment().apply {
                    arguments = Bundle().apply {
                        putBoolean("actionsOnly", actionsOnly)
                        putBoolean("charging", true)
                    }
                }
                if (actionsOnly) {
                    activity.supportFragmentManager.beginTransaction()
                        .add(fragment, "diagnostics-fixture").commitNow()
                    activity.setContent { AppBatteryTheme { fragment.Dialogs() } }
                } else {
                    val container = FrameLayout(activity).apply { id = View.generateViewId() }
                    activity.setContentView(container)
                    activity.supportFragmentManager.beginTransaction()
                        .add(container.id, fragment, "diagnostics-fixture").commitNow()
                }
                state = ViewModelProvider(fragment)[DiagnosticsDialogViewModel::class.java].state
            }
            instrumentation.waitForIdleSync()
            test()
        } finally {
            scenario.close()
            preferences.edit().apply {
                for ((key, value) in previous) {
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        null -> remove(key)
                    }
                }
            }.commit()
        }
    }

    private fun preferences(): SharedPreferences =
        context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)

    private fun input(value: String) {
        await { fieldOrNull() != null }
        main {
            assertTrue(
                field().config[SemanticsActions.SetText].action?.invoke(
                    AnnotatedString(
                        value
                    )
                ) == true
            )
        }
        instrumentation.waitForIdleSync()
        await {
            var text: String? = null
            main { text = fieldOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text }
            text == value
        }
    }

    private fun click(label: Int) {
        await { buttonDisabled(label) == false }
        main { button(label).clickSemantics() }
        instrumentation.waitForIdleSync()
    }

    private fun buttonDisabled(label: Int): Boolean? {
        var disabled: Boolean? = null
        main {
            val button = nodes().firstOrNull {
                it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(
                    SemanticsProperties.Text
                )?.any { text -> text.text == context.getString(label) } == true
            }
            disabled = button?.config?.contains(SemanticsProperties.Disabled)
        }
        return disabled
    }

    private fun button(label: Int) = nodes().first {
        it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.Text)
            ?.any { text -> text.text == context.getString(label) } == true
    }

    private fun hasText(text: String) = nodes().any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
    }

    private fun field() = requireNotNull(fieldOrNull())

    private fun fieldOrNull(): SemanticsNode? {
        var found: SemanticsNode? = null
        main { found = nodes().firstOrNull { it.config.contains(SemanticsActions.SetText) } }
        return found
    }

    private fun dialogs(): Int {
        var count = 0
        main { count = nodes().count { it.config.contains(SemanticsProperties.IsDialog) } }
        return count
    }

    private fun nodes(): List<SemanticsNode> = composeWindowNodes()

    private fun await(
        message: String = "Diagnostics dialog did not reach the expected state",
        ready: () -> Boolean
    ) = awaitCondition({ message }, ready)

}
