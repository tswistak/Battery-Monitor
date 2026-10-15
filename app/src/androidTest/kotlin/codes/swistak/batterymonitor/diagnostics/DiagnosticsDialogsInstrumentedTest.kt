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

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import codes.swistak.batterymonitor.BatteryMonitorTestRunner
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.monitoring.BackgroundServiceWatchdog
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingDiagnosticStore
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.ui.history.FullscreenChartActivity
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import codes.swistak.batterymonitor.ui.theme.Brightness
import codes.swistak.batterymonitor.ui.theme.ColorSource
import codes.swistak.batterymonitor.ui.theme.batteryColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DiagnosticsDialogsInstrumentedTest {
    private val instrumentation get() = BatteryMonitorTestRunner.instrumentation
    private val context get() = instrumentation.targetContext
    private lateinit var activity: FullscreenChartActivity
    private lateinit var fragment: DiagnosticsFragment
    private lateinit var state: DiagnosticsDialogState

    @Test
    fun stop_cancellation_and_queued_privileged_hint_use_one_dialog_in_both_hosts() {
        for (actionsOnly in listOf(false, true)) withHost(actionsOnly) {
            val monitoring = BackgroundServiceWatchdog.isServiceDesired(context)
            val snapshots = ChargingDiagnosticStore.read(context).size
            main { fragment.monitorActions.getValue("monitor_stop").onClick() }
            await { dialogs() == 1 }
            main { assertTrue(hasText(context.getString(R.string.diag_stop_monitor_body))) }

            preferences().edit().putBoolean(PENDING_HINT, true).commit()
            main { state.finishCapture(true) }
            assertEquals(DiagnosticsDialog.STOP_MONITORING, state.dialog)
            click(R.string.cancel)
            await { state.dialog == DiagnosticsDialog.PRIVILEGED_HINT && dialogs() == 1 }
            main { assertTrue(hasText(context.getString(R.string.charging_diagnostics_privileged_hint))) }
            click(R.string.okay)
            await { dialogs() == 0 }
            assertNull(state.dialog)
            assertFalse(preferences().getBoolean(PENDING_HINT, false))
            assertEquals(monitoring, BackgroundServiceWatchdog.isServiceDesired(context))
            assertEquals(snapshots, ChargingDiagnosticStore.read(context).size)
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
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { dialogs() == 0 }
            assertFalse(state.capturing)
            assertEquals(snapshots, ChargingDiagnosticStore.read(context).size)
        }
    }

    @Test
    fun migrated_dialogs_use_app_light_and_true_black_material_surfaces() {
        for (actionsOnly in listOf(false, true)) {
            for (brightness in listOf(Brightness.Light, Brightness.TrueBlack)) {
                withHost(actionsOnly, brightness) {
                    main { fragment.monitorActions.getValue("monitor_stop").onClick() }
                    await { dialogs() == 1 }
                    instrumentation.waitForIdleSync()
                    val color = batteryColorScheme(
                        context,
                        ColorSource.BatteryBlue,
                        brightness
                    ).surfaceContainerHigh.toArgb()
                    await("$actionsOnly / $brightness dialog must draw the app surface") {
                        surfacePixels(color) > 100
                    }
                    click(R.string.cancel)
                    await { dialogs() == 0 }
                }
            }
        }
    }

    private fun withHost(
        actionsOnly: Boolean, brightness: Brightness = Brightness.Light, test: () -> Unit
    ) {
        assumeTrue("Dialog window inspection requires Android 10", Build.VERSION.SDK_INT >= 29)
        val preferences = preferences()
        val keys =
            listOf(SettingsContract.KEY_COLOR_SOURCE, SettingsContract.KEY_BRIGHTNESS, PENDING_HINT)
        val previous = keys.associateWith { preferences.all[it] }
        preferences.edit()
            .putString(SettingsContract.KEY_COLOR_SOURCE, ColorSource.BatteryBlue.name)
            .putString(SettingsContract.KEY_BRIGHTNESS, brightness.name).remove(PENDING_HINT)
            .commit()
        val monitor =
            instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        instrumentation.startActivitySync(
            FullscreenChartActivity.intent(
                context, HistoryMetric.LEVEL, HistoryRangeState(0, 1), "custom"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            await {
                val host = monitor.lastActivity as? FullscreenChartActivity
                host != null && !host.isDestroyed && host.lifecycle.currentState == Lifecycle.State.RESUMED && host.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            activity = monitor.lastActivity as FullscreenChartActivity
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
            main { monitor.lastActivity?.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(monitor)
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
        main { assertTrue(button(label).config[SemanticsActions.OnClick].action?.invoke() == true) }
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

    private fun surfacePixels(color: Int): Int {
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        var pixels = 0
        for (y in 0 until screenshot.height step 3) {
            for (x in 0 until screenshot.width step 3) {
                if (screenshot.getPixel(x, y) == color) pixels++
            }
        }
        screenshot.recycle()
        return pixels
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

    private fun nodes(): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)

        fun owners(view: View): List<SemanticsOwner> {
            val owner = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }
                ?.invoke(view) as? SemanticsOwner
            if (owner != null) return listOf(owner)
            if (view !is ViewGroup) return emptyList()
            return (0 until view.childCount).flatMap { owners(view.getChildAt(it)) }
        }
        return WindowInspector.getGlobalWindowViews().flatMap(::owners)
            .flatMap { descendants(it.rootSemanticsNode) }
    }

    private fun main(action: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action()
        else {
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
    }

    private fun await(
        message: String = "Diagnostics dialog did not reach the expected state",
        ready: () -> Boolean
    ) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!ready() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(message, ready())
    }

    private companion object {
        const val PENDING_HINT = "charging_diagnostics_privileged_hint_pending"
    }
}
