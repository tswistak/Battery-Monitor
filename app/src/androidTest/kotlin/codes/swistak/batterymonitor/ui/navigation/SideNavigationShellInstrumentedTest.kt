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
package codes.swistak.batterymonitor.ui.navigation

import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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

class SideNavigationShellInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private lateinit var activity: FullscreenChartActivity
    private lateinit var inputModeManager: InputModeManager
    private val selected = mutableStateOf(SectionOwner.CURRENT)
    private val externalRequest = mutableStateOf(0)
    private val selections = mutableListOf<SectionOwner>()

    @Test
    fun selection_starts_before_the_actual_drawer_close_and_then_focuses_the_destination_heading() =
        withHost {
            var drawerStart = 0f
            var drawerRightAtSelection = Float.POSITIVE_INFINITY
            show { owner ->
                val drawer = pane()
                drawerRightAtSelection = drawer.positionInRoot.x + drawer.size.width
                selections += owner
                selected.value = owner
            }
            keyboard()
            main { assertTrue(hidden(item(SectionOwner.CURRENT))) }
            openDrawer()
            awaitMain { focused(item(SectionOwner.CURRENT)) }
            main {
                drawerStart = pane().positionInRoot.x
                assertTrue("The open modal hides the underlying content", hidden(content()))
                assertTrue(
                    "Keyboard opening focuses the first destination",
                    focused(item(SectionOwner.CURRENT))
                )
                click(item(SectionOwner.HISTORY))
                assertEquals(
                    "Selection dispatches in the input callback",
                    listOf(SectionOwner.HISTORY),
                    selections
                )
            }
            awaitMain { selections == listOf(SectionOwner.HISTORY) }
            main {
                assertTrue(
                    "Destination callback must start before the actual drawer pane leaves the screen",
                    drawerRightAtSelection > drawerStart + POSITION_TOLERANCE_PX
                )
            }
            awaitMain {
                !hidden(content()) && hidden(item(SectionOwner.HISTORY)) && nodes().any {
                    focused(it) && it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                        text.text == label(SectionOwner.HISTORY)
                    } == true
                }
            }
        }

    @Test
    fun newer_selection_and_external_navigation_win_without_replaying_a_dispatched_request() =
        withHost {
            var externalAfterSelection = false
            show { owner ->
                selections += owner
                selected.value = owner
                if (externalAfterSelection) {
                    selected.value = SectionOwner.HELP
                    externalRequest.value++
                }
            }
            keyboard()
            openDrawer()
            main {
                click(item(SectionOwner.HISTORY))
                click(item(SectionOwner.ALARMS))
            }
            awaitMain { hidden(item(SectionOwner.ALARMS)) && !hidden(content()) }
            main {
                assertEquals(SectionOwner.ALARMS, selected.value)
                assertEquals(listOf(SectionOwner.HISTORY, SectionOwner.ALARMS), selections)
            }

            openDrawer()
            main {
                selections.clear()
                externalAfterSelection = true
                click(item(SectionOwner.HISTORY))
            }
            awaitMain {
                hidden(item(SectionOwner.HISTORY)) && !hidden(content()) && nodes().any {
                    focused(it) && !hidden(it) && it.config.getOrNull(SemanticsProperties.Text)
                        ?.any { text ->
                            text.text == label(SectionOwner.HELP)
                        } == true
                }
            }
            main {
                assertEquals(SectionOwner.HELP, selected.value)
                assertEquals(
                    "Closing must not replay History over the external Help destination",
                    listOf(SectionOwner.HISTORY),
                    selections
                )
            }
            openDrawer()
            main {
                externalAfterSelection = false
                selections.clear()
                click(item(SectionOwner.HISTORY))
            }
            awaitMain { hidden(item(SectionOwner.HISTORY)) && !hidden(content()) }
            main {
                assertEquals(SectionOwner.HISTORY, selected.value)
                assertEquals(listOf(SectionOwner.HISTORY), selections)
            }
        }

    @Test
    fun dispatched_history_then_original_current_before_selected_publication_returns_to_current() =
        withHost {
            val publishSelected = mutableStateOf(false)
            show(selectedForComposition = {
                if (publishSelected.value) selected.value else SectionOwner.CURRENT
            }) { owner ->
                selections += owner
                selected.value = owner
                if (owner == SectionOwner.HISTORY) {
                    assertEquals(listOf(SectionOwner.HISTORY), selections)
                    assertEquals(SectionOwner.HISTORY, selected.value)
                    val originalCurrent = item(SectionOwner.CURRENT)
                    assertTrue(originalCurrent.config[SemanticsProperties.Selected])
                    assertFalse(hidden(originalCurrent))
                    click(originalCurrent)
                }
            }
            openDrawer()
            main { click(item(SectionOwner.HISTORY)) }
            awaitMain { hidden(item(SectionOwner.CURRENT)) && !hidden(content()) }
            main {
                assertEquals(
                    "A dispatched History must not cause a later Current action to be dropped",
                    listOf(SectionOwner.HISTORY, SectionOwner.CURRENT),
                    selections
                )
                assertEquals(SectionOwner.CURRENT, selected.value)
                publishSelected.value = true
            }
            awaitMain {
                !hidden(content()) && nodes().any {
                    !hidden(it) && it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                        text.text == label(SectionOwner.CURRENT)
                    } == true
                }
            }
        }

    @Test
    fun escape_and_reselecting_the_current_destination_return_focus_without_navigation() =
        withHost {
            show()
            keyboard()
            openDrawer()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE)
            awaitMain {
                hidden(item(SectionOwner.CURRENT)) && !hidden(content()) && focused(menu())
            }
            main { assertTrue(selections.isEmpty()) }

            openDrawer()
            main { click(item(SectionOwner.CURRENT)) }
            awaitMain {
                hidden(item(SectionOwner.CURRENT)) && !hidden(content()) && focused(menu())
            }
            main {
                assertFalse(hidden(menu()))
                assertTrue(
                    "Reselecting the current destination must not navigate", selections.isEmpty()
                )
            }
        }

    private fun withHost(test: () -> Unit) {
        val monitor =
            instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        try {
            instrumentation.startActivitySync(
                FullscreenChartActivity.intent(
                    target, HistoryMetric.LEVEL, HistoryRangeState(0, 1), "custom"
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            awaitMain {
                val latest = monitor.lastActivity as? FullscreenChartActivity
                latest != null && !latest.isDestroyed && latest.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && latest.lifecycle.currentState == Lifecycle.State.RESUMED
            }
            activity = monitor.lastActivity as FullscreenChartActivity
            test()
        } finally {
            main { monitor.lastActivity?.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun show(
        selectedForComposition: () -> SectionOwner = { selected.value },
        onSelect: (SectionOwner) -> Unit = { owner ->
            selections += owner
            selected.value = owner
        }
    ) {
        main {
            activity.setContent {
                val manager = LocalInputModeManager.current
                SideEffect { inputModeManager = manager }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box(
                        Modifier
                            .width(MODAL_FIXTURE_WIDTH)
                            .fillMaxHeight()
                    ) {
                        SideNavigationShell(
                            selected = selectedForComposition(),
                            externalNavigationRequest = externalRequest.value,
                            onSelect = { owner -> if (owner != selected.value) onSelect(owner) }) { modifier ->
                            Text(
                                "Navigation content", modifier.testTag(CONTENT_TAG)
                            )
                        }
                    }
                }
            }
        }
        awaitMain { nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == CONTENT_TAG } }
    }

    private fun keyboard() {
        main { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        awaitMain { inputModeManager.inputMode == InputMode.Keyboard }
    }

    private fun openDrawer() {
        main { click(menu()) }
        awaitMain {
            pane().config.contains(SemanticsActions.Dismiss) && !hidden(item(SectionOwner.CURRENT))
        }
    }

    private fun click(node: SemanticsNode) {
        assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
    }

    private fun menu() = nodes().first {
        it.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.contains(target.getString(R.string.nav_open_menu)) == true
    }

    private fun pane() = nodes().first { it.config.contains(SemanticsProperties.PaneTitle) }

    private fun content() = nodes().first {
        it.config.getOrNull(SemanticsProperties.TestTag) == CONTENT_TAG
    }

    private fun label(owner: SectionOwner) = target.getString(
        SectionRegistry.destinations.first { it.owner == owner }.label
    )

    private fun item(owner: SectionOwner) = nodes().first {
        it.config.contains(SemanticsProperties.Selected) && it.config.getOrNull(SemanticsProperties.Text)
            ?.any { text -> text.text == label(owner) } == true
    }

    private fun focused(node: SemanticsNode) =
        node.config.getOrNull(SemanticsProperties.Focused) == true

    private fun hidden(node: SemanticsNode): Boolean =
        node.config.contains(SemanticsProperties.HideFromAccessibility) || node.parent?.let(::hidden) == true

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
        return owners(activity.window.decorView).flatMap { descendants(it.rootSemanticsNode) }
    }

    private fun main(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
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

    private fun awaitMain(ready: () -> Boolean) {
        fun reached(): Boolean {
            var result = false
            main { result = ready() }
            return result
        }

        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (!reached() && System.nanoTime() < deadline) Thread.sleep(POLL_MILLIS)
        assertTrue("Navigation shell did not reach the expected state", reached())
    }

    private companion object {
        val MODAL_FIXTURE_WIDTH = 400.dp
        const val CONTENT_TAG = "navigation-content"
        const val POSITION_TOLERANCE_PX = 1f
        const val TIMEOUT_NANOS = 10_000_000_000L
        const val POLL_MILLIS = 20L
    }
}
