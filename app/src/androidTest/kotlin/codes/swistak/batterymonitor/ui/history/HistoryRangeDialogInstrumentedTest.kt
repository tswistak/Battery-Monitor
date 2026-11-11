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
package codes.swistak.batterymonitor.ui.history

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.view.inspector.WindowInspectorCompat
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class HistoryRangeDialogInstrumentedTest {
    @Test
    fun filters_cancel_and_back_leave_preferences_unchanged_and_ok_applies_once() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val directory =
            java.io.File(target.cacheDir, "filter-draft-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(target) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("${directory.name}-$name", mode)

            override fun getDatabasePath(name: String) = java.io.File(directory, name)
            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: android.database.sqlite.SQLiteDatabase.CursorFactory?
            ) = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
                getDatabasePath(name), factory
            )

            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,
                errorHandler: android.database.DatabaseErrorHandler?
            ) = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
                getDatabasePath(name).path, factory, errorHandler
            )
        }
        val app = object : android.app.Application() { init {
            attachBaseContext(isolated)
        }
        }
        val store = androidx.lifecycle.ViewModelStore()
        lateinit var model: HistoryViewModel
        main {
            model = HistoryViewModel(app)
            store.put("filters", model)
            model.tab("logs")
        }
        val committed = model.state.value.filters
        val key = codes.swistak.batterymonitor.logs.historyFilterKeys.first()
        val label = target.resources.getStringArray(R.array.log_filters).first()
        val preferences = isolated.getSharedPreferences(
            codes.swistak.batterymonitor.settings.SettingsContract.SP_MAIN_FILE,
            android.content.Context.MODE_PRIVATE
        )
        val changes = java.util.concurrent.atomic.AtomicInteger()
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
                if (changed == key) changes.incrementAndGet()
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        val monitor =
            instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        instrumentation.startActivitySync(
            FullscreenChartActivity.intent(
                target, HistoryMetric.LEVEL, HistoryRangeState.lastHours(24), "24h"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            await {
                monitor.lastActivity != null && !monitor.lastActivity.isDestroyed && monitor.lastActivity.hasWindowFocus() && monitor.lastActivity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            main {
                (monitor.lastActivity as FullscreenChartActivity).setContent {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalContext provides isolated,
                        androidx.activity.compose.LocalActivityResultRegistryOwner provides (monitor.lastActivity as FullscreenChartActivity)
                    ) {
                        AppBatteryTheme {
                            HistoryRoute(
                                model,
                                kotlinx.coroutines.flow.MutableStateFlow(codes.swistak.batterymonitor.monitoring.presentation.MonitoringUiState()),
                                null,
                                {},
                                { _, _, _ -> })
                        }
                    }
                }
            }
            val all = target.getString(R.string.history_all_types)
            fun toggleFirstFilter() {
                await("The filter dialog must open before editing its draft") {
                    textVisible(target.getString(R.string.configure_log_filter))
                }
                click(label)
            }
            click(all)
            toggleFirstFilter()
            assertEquals(committed, model.state.value.filters)
            click(target.getString(R.string.cancel))
            assertEquals(committed, model.state.value.filters)
            assertEquals(0, changes.get())
            click(all)
            toggleFirstFilter()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await { dialogs() == 0 }
            assertEquals(committed, model.state.value.filters)
            assertEquals(0, changes.get())
            click(all)
            toggleFirstFilter()
            assertEquals(committed, model.state.value.filters)
            val filterConfirm = confirmAction()
            main { filterConfirm(); filterConfirm() }
            await { model.state.value.filters == committed - key }
            await { changes.get() == 1 }
            assertFalse(preferences.getBoolean(key, true))
            click(
                target.getString(
                    R.string.history_filtered_types, committed.size - 1, committed.size
                )
            )
            toggleFirstFilter()
            click(target.getString(R.string.cancel))
            assertEquals(committed - key, model.state.value.filters)
            assertEquals(1, changes.get())
        } finally {
            main {
                monitor.lastActivity?.finish()
                store.clear()
            }
            instrumentation.removeMonitor(monitor)
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            for (name in listOf(
                codes.swistak.batterymonitor.settings.SettingsContract.SETTINGS_FILE,
                codes.swistak.batterymonitor.settings.SettingsContract.SP_MAIN_FILE
            )) target.deleteSharedPreferences("${directory.name}-$name")
            directory.deleteRecursively()
        }
    }

    @Test
    fun preset_selection_changes_only_the_draft_until_ok_and_reopening_discards_cancelled_choices() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val range = HistoryRangeState.lastHours(24)
        val state = mutableStateOf(HistoryUiState(range = range))
        var commits = 0
        val monitor =
            instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        instrumentation.startActivitySync(
            FullscreenChartActivity.intent(context, HistoryMetric.LEVEL, range, "24h")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            await {
                monitor.lastActivity != null && !monitor.lastActivity.isDestroyed && monitor.lastActivity.hasWindowFocus() && monitor.lastActivity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            main {
                (monitor.lastActivity as FullscreenChartActivity).setContent {
                    AppBatteryTheme {
                        HistoryRangeControl(state.value, { next, label ->
                            commits++
                            state.value = state.value.copy(range = next, rangeLabel = label)
                        }, ZoneId.systemDefault())
                    }
                }
            }
            fun awaitClosedDialog() {
                await("Range dialog must detach and return focus to its live host before reopening") {
                    val host = monitor.lastActivity
                    dialogs() == 0 && host != null && !host.isFinishing && !host.isDestroyed && host.hasWindowFocus()
                }
            }

            val original = context.getString(R.string.history_last_24h)
            val week = context.getString(R.string.history_last_7d)
            click(original)
            click(week)
            assertEquals(0, commits)
            click(context.getString(R.string.cancel))
            awaitClosedDialog()
            assertEquals(0, commits)
            assertEquals("24h", state.value.rangeLabel)
            click(original)
            click(week)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            awaitClosedDialog()
            assertEquals(0, commits)
            click(original)
            click(week)
            assertEquals(0, commits)
            click(context.getString(R.string.okay))
            await { commits == 1 }
            awaitClosedDialog()
            assertEquals("7d", state.value.rangeLabel)
            click(week)
            click(original)
            click(context.getString(R.string.cancel))
            awaitClosedDialog()
            assertEquals(1, commits)
            assertEquals("7d", state.value.rangeLabel)
            click(week)
            click(context.getString(R.string.history_custom_range))
            click(context.getString(R.string.okay))
            await { textVisible(context.getString(R.string.history_calendar_days)) }
            click(context.getString(R.string.cancel))
            awaitClosedDialog()
            assertEquals(1, commits)
            click(week)
            click(context.getString(R.string.history_custom_range))
            click(context.getString(R.string.okay))
            await { textVisible(context.getString(R.string.history_calendar_days)) }
            assertEquals(1, commits)
            val finalConfirm = confirmAction()
            main { finalConfirm(); finalConfirm() }
            await { commits == 2 }
            assertEquals("custom", state.value.rangeLabel)
        } finally {
            main { monitor.lastActivity?.finish() }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun invalid_calendar_fields_block_confirmation_until_corrected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val zone = ZoneId.of("Europe/Warsaw")
        val range =
            HistoryRangeState.days(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), zone)
        val state = mutableStateOf(HistoryUiState(range = range))
        var commits = 0
        val monitor =
            instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        instrumentation.startActivitySync(
            FullscreenChartActivity.intent(context, HistoryMetric.LEVEL, range, "24h")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            await { monitor.lastActivity?.hasWindowFocus() == true }
            main {
                (monitor.lastActivity as FullscreenChartActivity).setContent {
                    AppBatteryTheme {
                        HistoryRangeControl(state.value, { next, label ->
                            commits++
                            state.value = state.value.copy(range = next, rangeLabel = label)
                        }, zone, compact = true)
                    }
                }
            }
            val okay = context.getString(R.string.okay)
            fun fields() = windowSemanticsNodes(focusedOnly = true).filter {
                it.config.contains(SemanticsActions.SetText)
            }

            fun awaitEditor(message: String, ready: () -> Boolean) {
                await(message) {
                    var visible = false
                    main { visible = ready() }
                    visible
                }
            }

            fun setField(index: Int, value: String) {
                main {
                    val setText = fields()[index].config[SemanticsActions.SetText].action
                    assertTrue(requireNotNull(setText).invoke(AnnotatedString(value)))
                }
                awaitEditor("The calendar field must reflect the edited value") {
                    fields().getOrNull(index)?.config?.getOrNull(SemanticsProperties.EditableText)?.text == value
                }
            }

            fun checkErrors(yearInvalid: Boolean, monthInvalid: Boolean) {
                main {
                    val nodes = windowSemanticsNodes(focusedOnly = true)
                    assertEquals(
                        listOf(yearInvalid, monthInvalid),
                        fields().map { it.config.contains(SemanticsProperties.Error) })
                    val button = nodes.first {
                        it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(
                            SemanticsProperties.Text
                        )?.any { text -> text.text == okay } == true
                    }
                    assertEquals(
                        yearInvalid || monthInvalid,
                        button.config.contains(SemanticsProperties.Disabled)
                    )
                }
                assertEquals(0, commits)
            }
            click(context.getString(R.string.history_last_24h))
            val month = context.getString(R.string.history_month)
            click(month)
            awaitEditor("Month must be the selected draft before opening its editor") {
                windowSemanticsNodes(focusedOnly = true).any { node ->
                    node.config.getOrNull(SemanticsProperties.Selected) == true && node.config.getOrNull(
                        SemanticsProperties.Text
                    )?.any { it.text == month } == true
                }
            }
            click(okay)
            awaitEditor("The month editor must have focus and expose both editable fields") {
                fields().size == 2
            }

            setField(1, "13")
            checkErrors(yearInvalid = false, monthInvalid = true)
            setField(0, "")
            checkErrors(yearInvalid = true, monthInvalid = true)
            main {
                val done = fields()[1].config[SemanticsActions.OnImeAction].action
                assertTrue(requireNotNull(done).invoke())
            }
            checkErrors(yearInvalid = true, monthInvalid = true)
            setField(1, "11")
            checkErrors(yearInvalid = true, monthInvalid = false)
            setField(0, "2026")
            checkErrors(yearInvalid = false, monthInvalid = false)
            click(okay)
            await { commits == 1 }
            assertEquals("month", state.value.rangeLabel)
            assertEquals(
                HistoryRangeState.days(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), zone),
                state.value.range
            )
        } finally {
            main { monitor.lastActivity?.finish() }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun calendar_titles_use_committed_period_in_supplied_zone_and_active_locale() {
        val zone = ZoneId.of("Pacific/Kiritimati")
        val utc = ZoneOffset.UTC
        val first = LocalDate.of(2026, 1, 1)
        val last = LocalDate.of(2026, 1, 31)
        val localRange = HistoryRangeState.days(first, last, zone)
        val utcRange = HistoryRangeState.days(first, last, utc)
        for (period in listOf("month", "year")) {
            val localTitle = historyCalendarRangeTitle(localRange, period, Locale.US, zone)
            assertEquals(historyCalendarRangeTitle(utcRange, period, Locale.US, utc), localTitle)
            assertNotEquals(
                historyCalendarRangeTitle(localRange, period, Locale.US, utc), localTitle
            )
        }
        assertNotEquals(
            historyCalendarRangeTitle(localRange, "month", Locale.US, zone),
            historyCalendarRangeTitle(localRange, "month", Locale.forLanguageTag("pl-PL"), zone)
        )
    }

    @Test
    fun date_picker_replaces_parent_and_recreation_and_cancellation_preserve_custom_draft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val zone = ZoneId.systemDefault()
        val first = LocalDate.of(2026, 3, 29)
        val last = LocalDate.of(2026, 3, 31)
        val range = HistoryRangeState.days(first, last, zone)
        val monitor =
            instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        val automation = instrumentation.uiAutomation
        val serviceInfo = automation.serviceInfo
        val oldFlags = serviceInfo.flags
        serviceInfo.flags = oldFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = serviceInfo
        instrumentation.startActivitySync(
            FullscreenChartActivity.intent(context, HistoryMetric.LEVEL, range, "custom")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            val custom = context.getString(R.string.history_custom_range)
            await {
                monitor.lastActivity != null && !monitor.lastActivity.isDestroyed && monitor.lastActivity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && textVisible(
                    custom
                )
            }
            click(custom)
            click(custom)
            click(context.getString(R.string.okay))
            val locale = monitor.lastActivity.resources.configuration.locales[0]
            val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            val from = context.getString(R.string.history_from, format.format(first))
            val through = context.getString(R.string.history_through, format.format(last))
            await { textVisible(from) && textVisible(through) }
            assertOneDialog()
            click(from)
            await { !textVisible(context.getString(R.string.history_calendar_days)) }
            assertOneDialog()

            val previous = monitor.lastActivity
            main { previous.recreate() }
            await { monitor.lastActivity !== previous && !monitor.lastActivity.isDestroyed }
            await { textVisible(context.getString(R.string.cancel)) }
            assertFalse(textVisible(context.getString(R.string.history_calendar_days)))
            assertOneDialog()
            click(context.getString(R.string.cancel))
            await { textVisible(from) && textVisible(through) }
            assertOneDialog()

            click(through)
            await { !textVisible(context.getString(R.string.history_calendar_days)) }
            assertOneDialog()
            click(context.getString(R.string.okay))
            await { textVisible(from) && textVisible(through) }
            assertOneDialog()
            click(context.getString(R.string.cancel))
            await { !textVisible(context.getString(R.string.history_calendar_days)) }
            await { dialogs() == 0 }
            assertEquals(0, dialogs())

            click(custom)
            click(custom)
            click(context.getString(R.string.okay))
            await { textVisible(from) && textVisible(through) }
            assertOneDialog()
        } finally {
            main { monitor.lastActivity?.finish() }
            instrumentation.removeMonitor(monitor)
            serviceInfo.flags = oldFlags
            automation.serviceInfo = serviceInfo
        }
    }

    private fun assertOneDialog() {
        await { dialogs() == 1 }
        assertEquals("Only one Compose dialog window may be attached", 1, dialogs())
    }

    @SuppressLint("RestrictedApi")
    private fun windowSemanticsNodes(focusedOnly: Boolean = false): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)

        fun owners(view: View): List<SemanticsOwner> {
            val owner = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }
                ?.invoke(view) as? SemanticsOwner
            if (owner != null) return listOf(owner)
            if (view !is ViewGroup) return emptyList()
            return (0 until view.childCount).flatMap { owners(view.getChildAt(it)) }
        }
        return WindowInspectorCompat.getGlobalWindowViews()
            .filter { !focusedOnly || it.hasWindowFocus() }.flatMap(::owners)
            .flatMap { descendants(it.rootSemanticsNode) }
    }

    private fun dialogs(): Int {
        var count = 0
        main {
            count =
                windowSemanticsNodes().count { it.config.contains(SemanticsProperties.IsDialog) }
        }
        return count
    }

    private fun confirmAction(): () -> Boolean {
        var action: (() -> Boolean)? = null
        main {
            val label =
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.okay)
            action = windowSemanticsNodes(focusedOnly = true).first {
                it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(
                    SemanticsProperties.Text
                )?.any { text -> text.text == label } == true
            }.config[SemanticsActions.OnClick].action
        }
        return requireNotNull(action)
    }

    private fun textVisible(text: String): Boolean = find(text) != null

    private fun find(text: String): AccessibilityNodeInfo? =
        findNode { it.text?.toString() == text }

    private fun findNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && matches(node)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> search(child)?.let { return it } }
            }
            return null
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val roots =
            listOfNotNull(automation.rootInActiveWindow) + automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
        return roots.filter { it.packageName?.toString() == instrumentation.targetContext.packageName }
            .firstNotNullOfOrNull(::search)
    }

    private fun click(text: String) {
        var action: (() -> Boolean)? = null
        await("The active dialog must expose the exact control: $text") {
            main {
                val nodes = windowSemanticsNodes(focusedOnly = true)
                var node = nodes.firstOrNull {
                    it.config.getOrNull(SemanticsProperties.Text)
                        ?.any { value -> value.text == text } == true
                }
                while (node != null && !node.config.contains(SemanticsActions.OnClick)) node =
                    node.parent
                action = node?.config?.getOrNull(SemanticsActions.OnClick)?.action
                if (action == null) nodes.firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }?.config?.getOrNull(
                    SemanticsActions.ScrollBy
                )?.action?.invoke(0f, 200f)
            }
            action != null
        }
        main {
            assertTrue(
                "Could not invoke the exact control: $text", requireNotNull(action).invoke()
            )
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @SuppressLint("RestrictedApi")
    private fun failureDetails(): String {
        fun texts(node: AccessibilityNodeInfo): List<String> = buildList {
            node.text?.let { add(it.toString()) }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { addAll(texts(it)) }
            }
        }

        val windows =
            InstrumentationRegistry.getInstrumentation().uiAutomation.windows.joinToString { window ->
                val root = window.root
                "type=${window.type}, active=${window.isActive}, focused=${window.isFocused}, " + "package=${root?.packageName}, texts=${
                    root?.let(
                        ::texts
                    )?.take(40)
                }"
            }
        var views = ""
        main {
            views = WindowInspectorCompat.getGlobalWindowViews().joinToString { view ->
                val ime =
                    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())
                "focused=${view.hasWindowFocus()}, ime=$ime, focus=${view.findFocus()}"
            }
        }
        return "accessibility windows: $windows; app windows: $views"
    }

    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                action()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun await(
        message: String = "Dialog UI did not reach the expected state", condition: () -> Boolean
    ) {
        val until = android.os.SystemClock.uptimeMillis() + 10_000
        while (!condition() && android.os.SystemClock.uptimeMillis() < until) {
            android.os.SystemClock.sleep(50)
        }
        if (!condition()) {
            assertTrue("$message; ${failureDetails()}", false)
        }
    }
}
