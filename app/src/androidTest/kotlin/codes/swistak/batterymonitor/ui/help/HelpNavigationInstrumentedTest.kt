/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.help

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.app.StartupDialogViewModel
import codes.swistak.batterymonitor.settings.SettingsContract
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HelpNavigationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var activity: BatteryInfoActivity

    @Test
    fun detail_toolbar_help_and_back_follow_the_destination_including_search_highlights() {
        assumeTrue(
            "Use an already authorized device; this test must not change notification permission",
            Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
        val preferences =
            context.getSharedPreferences(SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE)
        val firstRun = preferences.all[SettingsContract.KEY_FIRST_RUN]
        val acknowledged = preferences.all[StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION]
        val installedVersion = PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(
                context.packageName, 0
            )
        )
        val monitor = instrumentation.addMonitor(BatteryInfoActivity::class.java.name, null, false)
        try {
            assertTrue(
                preferences.edit().putBoolean(SettingsContract.KEY_FIRST_RUN, false)
                    .putLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, installedVersion)
                    .commit()
            )
            for ((section, detail, expectedHelp) in listOf(
                Triple("diagnostics", "monitor", R.string.monitoring_help_overview), Triple(
                    "diagnostics", "monitor:diagnostics_export", R.string.monitoring_help_overview
                ), Triple(
                    "diagnostics", "charging-tools", R.string.charging_diagnostics_help_overview
                ), Triple(
                    "diagnostics",
                    "charging-tools:charging_diagnostics_capture",
                    R.string.charging_diagnostics_help_overview
                ), Triple("settings", "settings:advanced", R.string.pref_use_privileged_access_help)
            )) {
                instrumentation.startActivitySync(
                    Intent(
                        context,
                        BatteryInfoActivity::class.java
                    ).putExtra(BatteryInfoActivity.EXTRA_SECTION, section)
                        .putExtra(BatteryInfoActivity.EXTRA_DETAIL, detail)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                await {
                    (monitor.lastActivity as? BatteryInfoActivity)?.let {
                        !it.isDestroyed && it.lifecycle.currentState == Lifecycle.State.RESUMED
                    } == true
                }
                activity = monitor.lastActivity as BatteryInfoActivity
                main { ViewModelProvider(activity)[StartupDialogViewModel::class.java].dismissBatteryPrompt() }
                await { helpButton() != null }
                main { assertTrue(requireNotNull(helpButton()).config[SemanticsActions.OnClick].action?.invoke() == true) }
                await { hasText(expectedHelp) }
                main { activity.onBackPressedDispatcher.onBackPressed() }
                await { helpButton() != null && !hasText(expectedHelp) }
                main { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        } finally {
            main { monitor.lastActivity?.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(monitor)
            val restore = preferences.edit()
            if (firstRun is Boolean) restore.putBoolean(SettingsContract.KEY_FIRST_RUN, firstRun)
            else restore.remove(SettingsContract.KEY_FIRST_RUN)
            if (acknowledged is Long) restore.putLong(
                StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, acknowledged
            )
            else restore.remove(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION)
            assertTrue("Restore the startup flags", restore.commit())
        }
    }

    private fun helpButton(): SemanticsNode? = nodes().firstOrNull {
        !hidden(it) && it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(
            SemanticsProperties.ContentDescription
        )?.contains(activity.getString(R.string.menu_help)) == true
    }

    private fun hasText(resource: Int): Boolean = nodes().any {
        !hidden(it) && it.config.getOrNull(SemanticsProperties.Text)
            ?.any { text -> text.text == activity.getString(resource) } == true
    }

    private fun hidden(node: SemanticsNode): Boolean =
        node.config.contains(SemanticsProperties.HideFromAccessibility) || node.parent?.let(::hidden) == true

    private fun nodes(): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)

        fun owners(view: View): List<SemanticsOwner> {
            val owner = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }
                ?.invoke(view) as? SemanticsOwner
            if (owner != null) return listOf(owner)
            return if (view is ViewGroup) (0 until view.childCount).flatMap {
                owners(
                    view.getChildAt(
                        it
                    )
                )
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

    private fun await(ready: () -> Boolean) {
        fun reached(): Boolean {
            var result = false
            main { result = ready() }
            return result
        }

        val deadline = System.nanoTime() + 10_000_000_000L
        while (!reached() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("Help routing did not reach the expected screen", reached())
    }
}
