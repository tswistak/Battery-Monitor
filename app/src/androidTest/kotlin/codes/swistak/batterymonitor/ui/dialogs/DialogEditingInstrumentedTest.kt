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
package codes.swistak.batterymonitor.ui.dialogs

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.ui.alarms.AlarmDraft
import codes.swistak.batterymonitor.ui.alarms.AlarmsState
import codes.swistak.batterymonitor.ui.alarms.ThresholdDialog
import codes.swistak.batterymonitor.ui.alarms.editThresholdInput
import codes.swistak.batterymonitor.ui.components.NumericValueDialog
import codes.swistak.batterymonitor.ui.history.FullscreenChartActivity
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DialogEditingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private lateinit var activity: FullscreenChartActivity
    private lateinit var registry: SaveableStateRegistry
    private lateinit var fixture: @Composable () -> Unit
    private lateinit var themedContext: Context

    @Test
    fun numeric_validation_keyboard_done_and_confirmation_commit_once() = withHost {
        val visible = mutableStateOf(true)
        val saved = mutableListOf<Int>()
        show {
            if (visible.value) Numeric(onSave = {
                saved += it
                visible.value = false
            }, onCancel = { visible.value = false })
        }
        for (invalid in listOf("", "abc", "0", "101", "81", "80.5")) {
            input(invalid)
            await("Invalid numeric input must disable Save") { saveDisabled() }
            main {
                assertTrue(button("Save").config.contains(SemanticsProperties.Disabled))
                field().config[SemanticsActions.OnImeAction].action?.invoke()
            }
            assertTrue(saved.isEmpty())
        }
        input("85")
        await("Valid numeric input must enable Save") { !saveDisabled() }
        main {
            assertFalse(button("Save").config.contains(SemanticsProperties.Disabled))
            assertTrue(field().config[SemanticsActions.OnImeAction].action?.invoke() == true)
        }
        await { dialogs() == 0 }
        assertEquals(listOf(85), saved)
    }

    @Test
    fun numeric_draft_restores_and_cancel_or_back_never_commits() = withHost {
        val visible = mutableStateOf(true)
        var saved: Int? = null
        show {
            if (visible.value) Numeric(
                onSave = { saved = it },
                onCancel = { visible.value = false })
        }
        input("90")
        restoreComposition()
        main { assertEquals("90", field().config[SemanticsProperties.EditableText].text) }
        click("Cancel")
        await { dialogs() == 0 }
        assertNull(saved)
        main { visible.value = true }
        await { dialogs() == 1 }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await { dialogs() == 0 }
        assertNull(saved)
    }

    @Test
    fun deletion_replaces_editor_and_cancellation_restores_the_unsaved_draft() = withHost {
        val state = mutableStateOf(
            AlarmsState(
                hasSnapshot = true, loading = false, draft = AlarmDraft(
                    id = 7,
                    type = "charge_rises",
                    threshold = "80",
                    thresholdInput = "80",
                    initialInput = "80",
                    inputLocale = "en-US"
                )
            )
        )
        var deleted = 0
        show {
            ThresholdDialog(state = state.value, onInput = { input ->
                state.value = state.value.copy(
                    draft = editThresholdInput(requireNotNull(state.value.draft), input)
                )
            }, onSave = {}, onDelete = {
                deleted++
                state.value = state.value.copy(draft = null)
            }, onDismiss = {}, onEditExisting = {}, onRetry = {})
        }
        input("91")
        click(target.getString(R.string.menu_delete_alarm))
        await { dialogs() == 1 && fieldOrNull() == null }
        restoreComposition()
        assertEquals(1, dialogs())
        assertNull(fieldOrNull())
        click(target.getString(R.string.cancel))
        await { fieldOrNull() != null }
        main {
            assertEquals("91", field().config[SemanticsProperties.EditableText].text)
            assertEquals(1, dialogNodes().size)
        }
        assertEquals(0, deleted)
        click(target.getString(R.string.menu_delete_alarm))
        await { fieldOrNull() == null }
        click(target.getString(R.string.menu_delete_alarm))
        await { dialogs() == 0 }
        assertEquals(1, deleted)
    }

    @Composable
    private fun Numeric(onSave: (Int) -> Unit, onCancel: () -> Unit) {
        NumericValueDialog(
            label = "Numeric fixture",
            fieldLabel = "Value",
            unit = "%",
            initialValue = 80,
            min = 5,
            max = 100,
            step = 5,
            saveLabel = "Save",
            cancelLabel = "Cancel",
            errorMessage = { "Invalid value" },
            onSave = onSave,
            onCancel = onCancel
        )
    }

    private fun withHost(test: () -> Unit) {
        assumeTrue(
            "Global dialog window inspection requires Android 10", Build.VERSION.SDK_INT >= 29
        )
        val preferencesName = "dialog-fixture-${System.nanoTime()}"
        themedContext = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("$preferencesName-$name", mode)
        }
        val monitor =
            instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        instrumentation.startActivitySync(
            FullscreenChartActivity.intent(
                target, HistoryMetric.LEVEL, HistoryRangeState(0, 1), "custom"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            await {
                val latest = monitor.lastActivity as? FullscreenChartActivity
                latest != null && !latest.isDestroyed && latest.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && latest.lifecycle.currentState == Lifecycle.State.RESUMED
            }
            instrumentation.waitForIdleSync()
            activity = monitor.lastActivity as FullscreenChartActivity
            registry = SaveableStateRegistry(null) { true }
            test()
        } finally {
            main { monitor.lastActivity?.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(monitor)
            themedContext.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    private fun show(content: @Composable () -> Unit) {
        fixture = content
        render()
        await { dialogs() == 1 }
    }

    private fun render() = main {
        activity.setContent {
            CompositionLocalProvider(
                LocalSaveableStateRegistry provides registry, LocalContext provides themedContext
            ) {
                AppBatteryTheme { fixture() }
            }
        }
    }

    private fun restoreComposition() {
        lateinit var saved: Map<String, List<Any?>>
        main {
            saved = registry.performSave()
            activity.setContent {}
        }
        instrumentation.waitForIdleSync()
        registry = SaveableStateRegistry(saved) { true }
        render()
        await { dialogs() == 1 }
    }

    private fun input(value: String) {
        main {
            assertTrue(
                field().config[SemanticsActions.SetText].action?.invoke(
                    AnnotatedString(
                        value
                    )
                ) == true
            )
        }
        await("The numeric field must display the requested draft") {
            var rendered = false
            main {
                rendered = field().config[SemanticsProperties.EditableText].text == value
            }
            rendered
        }
    }

    private fun saveDisabled(): Boolean {
        var disabled = false
        main { disabled = button("Save").config.contains(SemanticsProperties.Disabled) }
        return disabled
    }

    private fun click(text: String) {
        main { assertTrue(button(text).config[SemanticsActions.OnClick].action?.invoke() == true) }
        instrumentation.waitForIdleSync()
    }

    private fun button(text: String) = nodes().first {
        it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.Text)
            ?.any { value -> value.text == text } == true
    }

    private fun field() = requireNotNull(fieldOrNull())

    private fun fieldOrNull(): SemanticsNode? {
        var found: SemanticsNode? = null
        main { found = nodes().firstOrNull { it.config.contains(SemanticsActions.SetText) } }
        return found
    }

    private fun dialogs(): Int {
        var count = 0
        main { count = dialogNodes().size }
        return count
    }

    private fun dialogNodes() = nodes().filter { it.config.contains(SemanticsProperties.IsDialog) }

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
        message: String = "Dialog did not reach the expected state", ready: () -> Boolean
    ) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!ready() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(message, ready())
    }
}
