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
import android.os.Build
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.testsupport.ComposeTestActivity
import codes.swistak.batterymonitor.testsupport.awaitCondition
import codes.swistak.batterymonitor.testsupport.clickSemantics
import codes.swistak.batterymonitor.testsupport.composeWindowNodes
import codes.swistak.batterymonitor.ui.alarms.AlarmDraft
import codes.swistak.batterymonitor.ui.alarms.AlarmsState
import codes.swistak.batterymonitor.ui.alarms.ThresholdDialog
import codes.swistak.batterymonitor.ui.alarms.editThresholdInput
import codes.swistak.batterymonitor.ui.components.NumericValueDialog
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import codes.swistak.batterymonitor.testsupport.runOnMain as main

class DialogEditingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private lateinit var activity: ComposeTestActivity

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
    fun persistent_numeric_guidance_keeps_error_semantics_and_blocks_invalid_keyboard_commits() =
        withHost {
            val visible = mutableStateOf(true)
            val saved = mutableListOf<Int>()
            val guidance = "Synthetic interval guidance: 1–3600."
            show {
                if (visible.value) NumericValueDialog(
                    label = "Interval fixture",
                    fieldLabel = "Value",
                    unit = "",
                    initialValue = 7,
                    min = 1,
                    max = 3600,
                    step = 1,
                    saveLabel = "Save",
                    cancelLabel = "Cancel",
                    errorMessage = { "Synthetic error" },
                    supportingText = guidance,
                    onSave = {
                        saved += it
                        visible.value = false
                    },
                    onCancel = { visible.value = false })
            }

            assertGuidanceAndValidation(guidance, invalid = false)
            for (invalid in listOf("", "abc", "0", "3601")) {
                input(invalid)
                await { saveDisabled() }
                assertGuidanceAndValidation(guidance, invalid = true)
                main { field().config[SemanticsActions.OnImeAction].action?.invoke() }
                assertTrue(saved.isEmpty())
            }
            restoreComposition()
            main { assertEquals("3601", field().config[SemanticsProperties.EditableText].text) }
            assertGuidanceAndValidation(guidance, invalid = true)
            input("3600")
            await { !saveDisabled() }
            assertGuidanceAndValidation(guidance, invalid = false)
            main { assertTrue(field().config[SemanticsActions.OnImeAction].action?.invoke() == true) }
            await { dialogs() == 0 }
            assertEquals(listOf(3600), saved)
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
            }, onDismiss = {}, onCancel = {}, onEditExisting = {}, onRetry = {})
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

    @Test
    fun explicit_alarm_cancel_discards_add_edit_and_invalid_drafts_without_guarded_dismissal() =
        withHost {
            val state = mutableStateOf(AlarmsState(hasSnapshot = true, loading = false))
            var guardedDismissals = 0
            var cancellations = 0
            fixture = {
                ThresholdDialog(
                    state = state.value,
                    onInput = { input ->
                        state.value = state.value.copy(
                            draft = editThresholdInput(requireNotNull(state.value.draft), input)
                        )
                    },
                    onSave = { error("Cancel must not save") },
                    onDelete = { error("Cancel must not delete") },
                    onDismiss = { guardedDismissals++ },
                    onCancel = {
                        cancellations++
                        state.value = state.value.copy(
                            draft = null, collisionId = null, draftConflict = false
                        )
                    },
                    onEditExisting = {},
                    onRetry = {})
            }
            for ((id, typedInput) in listOf(null to "91", 7 to "92", null to "", 7 to "")) {
                main {
                    state.value = state.value.copy(
                        draft = AlarmDraft(
                            id = id,
                            type = "charge_rises",
                            threshold = "80",
                            thresholdInput = "80",
                            initialInput = "80",
                            inputLocale = "en-US"
                        )
                    )
                }
                render()
                await { dialogs() == 1 }
                input(typedInput)
                click(target.getString(R.string.cancel))
                await { dialogs() == 0 }
                assertNull(state.value.draft)
            }
            assertEquals(4, cancellations)
            assertEquals(0, guardedDismissals)
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
        val scenario = ActivityScenario.launch(ComposeTestActivity::class.java)
        try {
            scenario.onActivity { activity = it }
            registry = SaveableStateRegistry(null) { true }
            test()
        } finally {
            scenario.close()
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

    @Suppress("SameParameterValue")
    private fun assertGuidanceAndValidation(guidance: String, invalid: Boolean) {
        main {
            assertTrue(nodes().any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                    text.text == guidance
                } == true
            })
            assertEquals(invalid, field().config.contains(SemanticsProperties.Error))
            assertEquals(invalid, button("Save").config.contains(SemanticsProperties.Disabled))
        }
    }

    private fun click(text: String) {
        main { button(text).clickSemantics() }
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

    private fun nodes(): List<SemanticsNode> = composeWindowNodes()

    private fun await(
        message: String = "Dialog did not reach the expected state", ready: () -> Boolean
    ) = awaitCondition({ message }, ready)
}
