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
package codes.swistak.batterymonitor.testsupport

import android.annotation.SuppressLint
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.view.inspector.WindowInspectorCompat
import org.junit.Assert.assertTrue

internal fun composeNodes(root: View, shownOnly: Boolean = false): List<SemanticsNode> = buildList {
    fun visitNode(node: SemanticsNode) {
        add(node)
        node.children.forEach(::visitNode)
    }

    fun visitView(view: View) {
        if (shownOnly && !view.isShown) return
        val composeRoot = view as? RootForTest
        if (composeRoot != null) {
            visitNode(composeRoot.semanticsOwner.rootSemanticsNode)
        } else if (view is ViewGroup) {
            for (index in 0 until view.childCount) visitView(view.getChildAt(index))
        }
    }
    visitView(root)
}

@SuppressLint("RestrictedApi")
internal fun composeWindowNodes(includeWindow: (View) -> Boolean = { true }): List<SemanticsNode> =
    runOnMain {
        WindowInspectorCompat.getGlobalWindowViews().filter(includeWindow)
            .flatMap { composeNodes(it) }
    }

internal fun SemanticsNode.clickSemantics() {
    assertTrue(
        "The Compose click action must be handled",
        config[SemanticsActions.OnClick].action?.invoke() == true
    )
}

internal fun <T> runOnMain(action: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return action()
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
}

internal fun awaitCondition(diagnostic: () -> String, ready: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + 10_000
    while (!ready()) {
        if (SystemClock.uptimeMillis() >= deadline) throw AssertionError(diagnostic())
        SystemClock.sleep(20)
    }
}
