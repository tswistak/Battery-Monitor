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
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.app.StartupDialogViewModel
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.ui.navigation.SectionNavigator
import codes.swistak.batterymonitor.ui.navigation.SectionOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import codes.swistak.batterymonitor.testsupport.awaitCondition
import codes.swistak.batterymonitor.testsupport.composeNodes
import codes.swistak.batterymonitor.testsupport.runOnMain as main


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
        try {
            assertTrue(
                preferences.edit().putBoolean(SettingsContract.KEY_FIRST_RUN, false)
                    .putLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, installedVersion)
                    .commit()
            )
            for ((section, detail, expectedTopic) in listOf(
                Triple(SectionOwner.DIAGNOSTICS, "monitor", MONITORING_HELP_TOPIC),
                Triple(
                    SectionOwner.DIAGNOSTICS, "monitor:diagnostics_export", MONITORING_HELP_TOPIC
                ),
                Triple(SectionOwner.DIAGNOSTICS, "monitor:diagnostics_root", MONITORING_HELP_TOPIC),
                Triple(
                    SectionOwner.DIAGNOSTICS, "monitor:diagnostics_shizuku", MONITORING_HELP_TOPIC
                ),
                Triple(SectionOwner.DIAGNOSTICS, "charging-tools", CHARGING_TOOLS_HELP_TOPIC),
                Triple(
                    SectionOwner.DIAGNOSTICS,
                    "charging-tools:charging_diagnostics_capture",
                    CHARGING_TOOLS_HELP_TOPIC
                ),
                Triple(
                    SectionOwner.SETTINGS,
                    "settings:advanced",
                    SettingsContract.KEY_ADVANCED_SETTINGS
                )
            )) {
                ActivityScenario.launch<BatteryInfoActivity>(
                    Intent(
                        context, BatteryInfoActivity::class.java
                    ).putExtra(BatteryInfoActivity.EXTRA_SECTION, section.route)
                        .putExtra(BatteryInfoActivity.EXTRA_DETAIL, detail)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                ).use { scenario ->
                    scenario.onActivity { launched ->
                        activity = launched
                        ViewModelProvider(activity)[StartupDialogViewModel::class.java].dismissBatteryPrompt()
                    }
                    await { helpButton() != null }
                    main {
                        assertTrue(
                            requireNotNull(helpButton()).config[SemanticsActions.OnClick].action?.invoke() == true
                        )
                    }
                    assertSavedNavigation(scenario, SectionOwner.HELP, "help:$expectedTopic")
                    main { activity.onBackPressedDispatcher.onBackPressed() }
                    assertSavedNavigation(scenario, section, detail)
                }
            }
        } finally {
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

    private fun assertSavedNavigation(
        scenario: ActivityScenario<BatteryInfoActivity>, owner: SectionOwner, detail: String
    ) {
        val saved = Bundle()
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.onActivity { instrumentation.callActivityOnSaveInstanceState(it, saved) }
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.onActivity { activity = it }
        val navigation = SectionNavigator.restore(saved)
        assertEquals(owner, navigation.selected)
        assertEquals(detail, navigation.detail)
    }

    private fun hidden(node: SemanticsNode): Boolean =
        node.config.contains(SemanticsProperties.HideFromAccessibility) || node.parent?.let(::hidden) == true

    private fun nodes(): List<SemanticsNode> = composeNodes(activity.window.decorView)

    private fun await(ready: () -> Boolean) =
        awaitCondition({ "Help routing did not reach the expected screen" }) { main(ready) }
}
