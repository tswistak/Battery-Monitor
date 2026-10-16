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
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class HistoryRangeDialogInstrumentedTest {
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
            await {
                monitor.lastActivity != null && !monitor.lastActivity.isDestroyed && monitor.lastActivity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && textVisible(
                    context.getString(R.string.history_range)
                )
            }
            click(context.getString(R.string.history_range))
            click(context.getString(R.string.history_custom_range))
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

            click(context.getString(R.string.history_range))
            click(context.getString(R.string.history_custom_range))
            await { textVisible(from) && textVisible(through) }
            assertOneDialog()
        } finally {
            main { monitor.lastActivity?.finish() }
            instrumentation.removeMonitor(monitor)
            serviceInfo.flags = oldFlags
            automation.serviceInfo = serviceInfo
        }
    }

    private fun applicationWindows(): Int {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        return instrumentation.uiAutomation.windows.count {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() == instrumentation.targetContext.packageName
        }
    }

    private fun assertOneDialog() {
        await { dialogs() == 1 }
        assertEquals("Only one Compose dialog window may be attached", 1, dialogs())
    }

    private fun dialogs(): Int {
        if (Build.VERSION.SDK_INT < 29) return applicationWindows() - 1
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
            count = WindowInspector.getGlobalWindowViews().flatMap(::owners)
                .flatMap { descendants(it.rootSemanticsNode) }
                .count { it.config.contains(SemanticsProperties.IsDialog) }
        }
        return count
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
        await {
            if (!textVisible(text)) {
                findNode { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            textVisible(text)
        }
        val node = requireNotNull(find(text))
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        assertTrue(
            "No clickable control for $text",
            clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
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

    private fun await(condition: () -> Boolean) {
        val until = android.os.SystemClock.uptimeMillis() + 10_000
        while (!condition() && android.os.SystemClock.uptimeMillis() < until) {
            android.os.SystemClock.sleep(50)
        }
        assertTrue("Dialog UI did not reach the expected state", condition())
    }
}
