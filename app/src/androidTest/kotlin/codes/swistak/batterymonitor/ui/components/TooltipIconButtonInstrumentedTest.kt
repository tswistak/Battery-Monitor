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

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>.
*/
package codes.swistak.batterymonitor.ui.components

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.ui.history.FullscreenChartActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TooltipIconButtonInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation

    @Test
    fun long_press_shows_the_action_without_clicking_and_a_normal_tap_fires_once() {
        val target = instrumentation.targetContext
        val label = mutableStateOf(target.getString(R.string.menu_help))
        val enabled = mutableStateOf(true)
        val clicks = AtomicInteger()
        val monitor =
            instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        val serviceInfo = automation.serviceInfo
        val previousFlags = serviceInfo.flags
        serviceInfo.flags =
            previousFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = serviceInfo
        try {
            instrumentation.startActivitySync(
                FullscreenChartActivity.intent(
                    target, HistoryMetric.LEVEL, HistoryRangeState(0, 1), "custom"
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            await {
                val activity = monitor.lastActivity as? FullscreenChartActivity
                activity != null && !activity.isDestroyed && activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && activity.lifecycle.currentState == Lifecycle.State.RESUMED
            }
            val activity = monitor.lastActivity as FullscreenChartActivity
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            TooltipIconButton(
                                label = label.value,
                                enabled = enabled.value,
                                onClick = { clicks.incrementAndGet() }) {
                                Icon(painterResource(R.drawable.ui_info), label.value)
                            }
                        }
                    }
                }
            }
            await("The fixture must expose exactly one action for its accessible label") {
                actions(label.value).size == 1
            }
            val button = actions(label.value).single()
            val bounds = Rect().also(button::getBoundsInScreen)
            val down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, down, bounds)
            try {
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
                await { nodes().any { it.text?.toString() == label.value } }
                assertEquals("Showing the tooltip must not invoke the action", 0, clicks.get())
            } finally {
                touch(MotionEvent.ACTION_UP, down, bounds)
            }
            instrumentation.waitForIdleSync()
            assertEquals("Releasing a long press must not click", 0, clicks.get())
            val oldLabel = label.value
            instrumentation.runOnMainSync {
                label.value = target.getString(R.string.settings_activity_subtitle)
            }
            await { nodes().none { it.text?.toString() == oldLabel } }
            await { actions(label.value).size == 1 }

            instrumentation.waitForIdleSync()
            val tapBounds = Rect().also(actions(label.value).single()::getBoundsInScreen)
            val tap = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, tap, tapBounds)
            SystemClock.sleep(80)
            touch(MotionEvent.ACTION_UP, tap, tapBounds)
            await { clicks.get() == 1 }
            instrumentation.waitForIdleSync()
            assertEquals("A normal tap must invoke the original action once", 1, clicks.get())

            val disabledFrame = CountDownLatch(1)
            instrumentation.runOnMainSync {
                enabled.value = false
                activity.window.decorView.postOnAnimation { disabledFrame.countDown() }
            }
            assertTrue(
                "The disabled state must render before touching it",
                disabledFrame.await(5, TimeUnit.SECONDS)
            )
            instrumentation.waitForIdleSync()
            val disabledTarget = actions(label.value).singleOrNull()
                ?: nodes().single { it.contentDescription?.toString() == label.value }
            val disabledBounds = Rect().also(disabledTarget::getBoundsInScreen)
            val disabledTap = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, disabledTap, disabledBounds)
            SystemClock.sleep(80)
            touch(MotionEvent.ACTION_UP, disabledTap, disabledBounds)
            instrumentation.waitForIdleSync()
            assertEquals("The tooltip wrapper must preserve a disabled action", 1, clicks.get())
            assertFalse(nodes().any { it.text?.toString() == oldLabel })
        } finally {
            instrumentation.runOnMainSync { monitor.lastActivity?.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(monitor)
            serviceInfo.flags = previousFlags
            automation.serviceInfo = serviceInfo
        }
    }

    private fun touch(action: Int, down: Long, bounds: Rect) {
        // A touchscreen source alone leaves the simple obtain overload's tool type unknown.
        val pointer = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = bounds.exactCenterX()
            y = bounds.exactCenterY()
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            down,
            SystemClock.uptimeMillis(),
            action,
            1,
            arrayOf(pointer),
            arrayOf(coordinates),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        try {
            assertTrue(automation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).mapNotNull(node::getChild)
                .flatMap(::descendants)

        return automation.windows.mapNotNull { it.root }.flatMap(::descendants).filter {
            it.isVisibleToUser && it.packageName?.toString() == instrumentation.targetContext.packageName
        }
    }

    private fun actions(label: String): List<AccessibilityNodeInfo> =
        nodes().filter { it.contentDescription?.toString() == label }.mapNotNull { labelledNode ->
            generateSequence(labelledNode) { it.parent }.firstOrNull { it.isClickable }
        }.distinct()

    private fun await(
        message: String = "The tooltip did not reach the expected interaction state",
        ready: () -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var reached = ready()
        while (!reached && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(20)
            reached = ready()
        }
        if (!reached) {
            val observed = nodes().joinToString { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                "${node.className}(text=${node.text}, description=${node.contentDescription}, " + "clickable=${node.isClickable}, longClickable=${node.isLongClickable}, " + "enabled=${node.isEnabled}, window=${node.windowId}, bounds=$bounds)"
            }
            throw AssertionError("$message; observed nodes: $observed")
        }
    }
}
