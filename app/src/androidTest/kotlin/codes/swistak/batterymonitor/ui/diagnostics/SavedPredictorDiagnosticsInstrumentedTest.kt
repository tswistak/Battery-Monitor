/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.diagnostics

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.app.PersistentFragment
import codes.swistak.batterymonitor.app.StartupDialogViewModel
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import codes.swistak.batterymonitor.monitoring.MonitoringDesiredState
import codes.swistak.batterymonitor.monitoring.Predictor
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringAvailability
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SavedPredictorDiagnosticsInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var activity: BatteryInfoActivity

    @Test
    fun stopped_monitor_keeps_saved_data_readable_and_refresh_reads_storage_without_starting_monitoring() {
        assumeTrue(
            "Notification denial is covered separately; do not alter permission for this storage test",
            Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        )
        val main = context.getSharedPreferences(SettingsContract.SP_MAIN_FILE, 0)
        val settings = context.getSharedPreferences(SettingsContract.SETTINGS_FILE, 0)
        val service = context.getSharedPreferences(SettingsContract.SP_SERVICE_FILE, 0)
        val predictor = context.getSharedPreferences(Predictor.STORE_NAME, 0)
        val originals =
            listOf(main, settings, service, predictor).associateWith(SettingsSnapshot::capture)
        val desiredBefore = MonitoringDesiredState.read(context)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var originalClip: ClipData? = null
        var scenario: ActivityScenario<BatteryInfoActivity>? = null
        try {
            instrumentation.runOnMainSync { originalClip = clipboard.primaryClip }
            assertTrue(MonitoringDesiredState.write(context, false))
            context.stopService(Intent(context, BatteryInfoService::class.java))
            val version = PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0)
            )
            assertTrue(
                main.edit().putBoolean(SettingsContract.KEY_FIRST_RUN, false)
                    .putBoolean(SettingsContract.KEY_MIGRATED_SERVICE_DESIRED, true)
                    .putLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, version).commit()
            )
            assertTrue(
                settings.edit().putBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false)
                    .putBoolean(SettingsContract.KEY_ENABLE_LOGGING, false).commit()
            )
            val key = Predictor.KEY_AVERAGE[0]
            assertTrue(
                predictor.edit().clear().putFloat(key, 123456.75f)
                    .putInt(Predictor.KEY_STATE_VERSION, 2).commit()
            )
            scenario = ActivityScenario.launch(
                Intent(context, BatteryInfoActivity::class.java)
                    .putExtra(BatteryInfoActivity.EXTRA_SECTION, "diagnostics")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            lateinit var fragment: PersistentFragment
            scenario.onActivity { launched ->
                activity = launched
                ViewModelProvider(activity)[StartupDialogViewModel::class.java].dismissBatteryPrompt()
                activity.supportFragmentManager.executePendingTransactions()
                fragment = activity.supportFragmentManager.findFragmentByTag(
                    PersistentFragment.FRAG_TAG
                ) as PersistentFragment
            }
            click(R.string.diag_raw)
            setQuery(key)
            copyRawValue(key, "123456.75", clipboard)
            assertFalse(MonitoringDesiredState.read(context))
            assertFalse(fragment.monitoring.state.value.availability == MonitoringAvailability.LIVE)
            assertNull(fragment.monitoring.state.value.snapshot?.recentPredictorData)

            assertTrue(predictor.edit().putFloat(key, 345678.5f).commit())
            click(R.string.diag_overview)
            scrollToAndClick(R.string.diag_action_refresh)
            click(R.string.diag_raw)
            copyRawValue(key, "345678.5", clipboard)
            assertFalse(
                "Reading saved diagnostics restarted monitoring",
                MonitoringDesiredState.read(context)
            )
            assertFalse(fragment.monitoring.state.value.availability == MonitoringAvailability.LIVE)
            assertNull(fragment.monitoring.state.value.snapshot?.recentPredictorData)
        } finally {
            scenario?.close()
            context.stopService(Intent(context, BatteryInfoService::class.java))
            originals.forEach { (preferences, snapshot) ->
                assertTrue(SettingsSnapshot.apply(preferences, snapshot))
            }
            assertTrue(
                MonitoringDesiredState.write(
                    context,
                    desiredBefore,
                    mirrorPreferences = false
                )
            )
            instrumentation.runOnMainSync {
                originalClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
            }
            if (desiredBefore) BatteryInfoService.startForegroundServiceSafely(context)
        }
    }

    private fun copyRawValue(key: String, raw: String, clipboard: ClipboardManager) {
        await("Saved predictor value did not reach Diagnostics") {
            find { hasText(it, key) } != null && find { hasText(it, raw) } != null
        }
        clickText(raw)
        click(R.string.charging_diagnostics_copy)
        instrumentation.runOnMainSync {
            assertEquals("$key=$raw", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        click(R.string.cancel)
    }

    private fun setQuery(query: String) {
        await("Raw search field was not available") { find { it.isEditable } != null }
        assertTrue(
            find { it.isEditable }?.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        query
                    )
                }
            ) == true
        )
        instrumentation.waitForIdleSync()
    }

    private fun scrollToAndClick(label: Int) {
        val text = context.getString(label)
        fun button(): SemanticsNode? = semanticsNodes().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
                    node.config.contains(SemanticsActions.OnClick) &&
                    !node.config.contains(SemanticsProperties.Disabled)
        }

        fun list(): SemanticsNode? = semanticsNodes().firstOrNull { node ->
            node.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
                    node.config.contains(SemanticsActions.ScrollBy) &&
                    node.config.getOrNull(SemanticsProperties.IndexForKey)?.invoke("operation") == 0
        }
        await("App operation did not finish composing after the tab change") {
            var ready = false
            main {
                ready = semanticsNodes().none { it.config.contains(SemanticsActions.SetText) } &&
                        list() != null
            }
            ready
        }
        repeat(20) {
            var clicked = false
            var before = 0f
            main {
                val target = button()
                if (target != null) {
                    clicked = target.config[SemanticsActions.OnClick].action?.invoke() == true
                } else {
                    val scroll = requireNotNull(list()) { "App operation list disappeared" }
                    before = scroll.config[SemanticsProperties.VerticalScrollAxisRange].value()
                    assertTrue(
                        "App operation list rejected a vertical scroll",
                        scroll.config[SemanticsActions.ScrollBy].action?.invoke(
                            0f, (scroll.boundsInRoot.height * 0.85f).coerceAtLeast(1f)
                        ) == true
                    )
                }
            }
            instrumentation.waitForIdleSync()
            if (clicked) return
            await("App operation did not advance toward Refresh") {
                var advanced = false
                main {
                    val range =
                        list()?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    advanced = button() != null || range != null && range.value() > before
                }
                advanced
            }
        }
        throw AssertionError("Refresh was not reachable after 20 vertical scrolls")
    }

    private fun semanticsNodes(): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)

        fun owners(view: View): List<SemanticsOwner> {
            val owner = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }
                ?.invoke(view) as? SemanticsOwner
            if (owner != null) return listOf(owner)
            return if (view is ViewGroup) (0 until view.childCount).flatMap {
                owners(view.getChildAt(it))
            } else emptyList()
        }
        return owners(activity.window.decorView).flatMap { descendants(it.rootSemanticsNode) }
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

    private fun click(label: Int) = clickText(context.getString(label))

    private fun clickText(text: String) {
        await("Action was not available: $text") { find { hasText(it, text) } != null }
        var node = find { hasText(it, text) }
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync()
    }

    private fun hasText(node: AccessibilityNodeInfo, text: String): Boolean =
        node.text?.toString()?.lines()?.contains(text) == true ||
                node.contentDescription?.toString() == text

    private fun find(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && matches(node)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> search(child)?.let { return it } }
            }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
    }

    private fun await(message: String, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (!ready() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
        assertTrue(message, ready())
    }
}
