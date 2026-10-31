/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.settings

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.ui.help.HelpScreen
import codes.swistak.batterymonitor.ui.help.helpSections
import codes.swistak.batterymonitor.ui.history.FullscreenChartActivity
import codes.swistak.batterymonitor.ui.settings.SettingsScreen
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNavigationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var activity: FullscreenChartActivity

    @Test
    fun advanced_opens_existing_OEM_tools_and_background_monitoring_without_changing_settings() =
        withHost {
            val preferences = context.getSharedPreferences(
                SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
            )
            val before = preferences.all.toMap()
            var category by mutableStateOf<String?>(null)
            val destinations = mutableListOf<Pair<String, String?>>()
            main {
                val screen = PreferenceManager(activity).createPreferenceScreen(activity)
                activity.setContent {
                    AppBatteryTheme {
                        SettingsScreen(
                            preferences = screen,
                            version = 0,
                            initialCategory = category,
                            highlightKey = null,
                            onActivate = { error("Diagnostics must reuse their existing route") },
                            onNavigateCategory = { route, _ -> category = route },
                            onDiagnostics = { route, highlight -> destinations += route to highlight })
                    }
                }
            }
            await { nodes().any { it.config.contains(SemanticsActions.ScrollToIndex) } }
            main {
                nodes().first { it.config.contains(SemanticsActions.ScrollToIndex) }.config[SemanticsActions.ScrollToIndex].action?.invoke(
                    4
                )
            }
            await { button(R.string.advanced_settings) != null }
            click(R.string.advanced_settings)
            await { button(R.string.charging_diagnostics_title) != null }
            click(R.string.charging_diagnostics_title)
            click(R.string.diagnostics_monitoring)
            assertEquals(listOf("charging-tools" to null, "monitor" to null), destinations)
            assertEquals(before, preferences.all)
        }

    @Test
    fun notification_search_highlight_keeps_the_matching_first_row_visible_without_a_legacy_preview() =
        withHost {
            main {
                val screen = PreferenceManager(activity).createPreferenceScreen(activity)
                for (key in listOf(
                    SettingsContract.KEY_TOP_LINE,
                    SettingsContract.KEY_BOTTOM_LINE,
                    SettingsContract.KEY_TIME_REMAINING_VERBOSITY,
                    SettingsContract.KEY_VITAL_SIGNS_CONTENT,
                    SettingsContract.KEY_ICON_CONTENT
                )) {
                    screen.addPreference(Preference(activity).apply {
                        this.key = key
                        title = key
                    })
                }
                activity.setContent {
                    AppBatteryTheme {
                        Box(Modifier.height(120.dp)) {
                            SettingsScreen(
                                preferences = screen,
                                version = 0,
                                initialCategory = "notification",
                                highlightKey = SettingsContract.KEY_TOP_LINE,
                                onActivate = {},
                                onNavigateCategory = { _, _ -> },
                                onDiagnostics = { _, _ -> })
                        }
                    }
                }
            }
            await { nodes().any { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) } }
            instrumentation.waitForIdleSync()
            main {
                val scroll = nodes().first {
                    it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                }.config[SemanticsProperties.VerticalScrollAxisRange]
                assertEquals(0f, scroll.value(), 0f)
                assertTrue(nodes().any {
                    it.config.getOrNull(SemanticsProperties.Text)
                        ?.any { text -> text.text == SettingsContract.KEY_TOP_LINE } == true
                })
            }
        }

    @Test
    fun search_opens_the_same_monitor_and_OEM_routes_without_changing_preferences() = withHost {
        val preferences = context.getSharedPreferences(
            SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
        )
        val before = preferences.all.toMap()
        val destinations = mutableListOf<Pair<String, String?>>()
        main {
            val screen = PreferenceManager(activity).createPreferenceScreen(activity)
            activity.setContent {
                AppBatteryTheme {
                    SettingsScreen(
                        preferences = screen,
                        version = 0,
                        initialCategory = null,
                        highlightKey = null,
                        onActivate = { error("Search must route to the existing detail") },
                        onNavigateCategory = { _, _ -> error("A diagnostic result is a detail route") },
                        onDiagnostics = { route, highlight -> destinations += route to highlight })
                }
            }
        }
        for ((label, destination) in listOf(
            R.string.diagnostics_monitoring to ("monitor" to null),
            R.string.charging_diagnostics_capture to ("charging-tools" to "charging_diagnostics_capture")
        )) {
            await { nodes().any { it.config.contains(SemanticsActions.SetText) } }
            main {
                assertTrue(
                    nodes().first { it.config.contains(SemanticsActions.SetText) }.config[SemanticsActions.SetText].action?.invoke(
                        AnnotatedString(
                            context.getString(
                                label
                            )
                        )
                    ) == true
                )
            }
            await { button(label) != null }
            click(label)
            assertEquals(destination, destinations.last())
        }
        assertEquals(before, preferences.all)
    }

    @Test
    fun contextual_help_content_is_reachable_at_large_font_size() = withHost {
        val topic = SettingsContract.KEY_NOTIFICATION_SETTINGS
        val configuration =
            Configuration(activity.resources.configuration).apply { fontScale = 1.5f }
        val largeText = activity.createConfigurationContext(configuration)
        var safeContentBounds = Rect.Zero
        main {
            activity.setContent {
                CompositionLocalProvider(
                    LocalContext provides largeText,
                    LocalResources provides largeText.resources,
                    LocalConfiguration provides configuration,
                    LocalDensity provides Density(
                        configuration.densityDpi / 160f, configuration.fontScale
                    )
                ) {
                    AppBatteryTheme {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .safeDrawingPadding()
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .onGloballyPositioned {
                                        safeContentBounds = it.boundsInRoot()
                                    }) { HelpScreen(topic) }
                        }
                    }
                }
            }
        }
        await {
            nodes().any {
                it.config.getOrNull(SemanticsProperties.Text)
                    ?.any { text -> text.text == largeText.getString(R.string.pref_cat_channel_settings) } == true
            }
        }
        val sections = helpSections(topic, Build.VERSION.SDK_INT, null)
        main {
            assertTrue(
                nodes().first { it.config.contains(SemanticsActions.ScrollToIndex) }.config[SemanticsActions.ScrollToIndex].action?.invoke(
                    sections.lastIndex
                ) == true
            )
        }
        await { nodes().any { it.config.contains(SemanticsActions.ScrollBy) } }
        main {
            assertTrue(
                nodes().first { it.config.contains(SemanticsActions.ScrollBy) }.config[SemanticsActions.ScrollBy].action?.invoke(
                    0f,
                    100_000f
                ) == true
            )
        }
        val finalText = largeText.getString(sections.last().paragraphs.last().textResource)
        await {
            val range = nodes().first {
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
            }.config[SemanticsProperties.VerticalScrollAxisRange]
            range.maxValue()
                .isFinite() && range.value() >= range.maxValue() - 0.01f && nodes().any {
                it.config.getOrNull(SemanticsProperties.Text)
                    ?.any { text -> text.text == finalText } == true && it.boundsInRoot.height > 0 && safeContentBounds.height > 0 && it.boundsInRoot.top >= safeContentBounds.top && it.boundsInRoot.bottom <= safeContentBounds.bottom
            }
        }
        main {
            for (node in nodes().filter { it.config.contains(SemanticsActions.GetTextLayoutResult) }) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                for (layout in layouts) assertFalse(
                    "Large-text help must remain readable", layout.hasVisualOverflow
                )
            }
        }
    }

    private fun withHost(test: () -> Unit) {
        val monitor =
            instrumentation.addMonitor(FullscreenChartActivity::class.java.name, null, false)
        try {
            instrumentation.startActivitySync(
                FullscreenChartActivity.intent(
                    context, HistoryMetric.LEVEL, HistoryRangeState(0, 1), "custom"
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            await {
                val host = monitor.lastActivity as? FullscreenChartActivity
                host != null && host.lifecycle.currentState == Lifecycle.State.RESUMED
            }
            activity = monitor.lastActivity as FullscreenChartActivity
            test()
        } finally {
            main { monitor.lastActivity?.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun click(label: Int) {
        main {
            assertTrue(requireNotNull(button(label)).config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        instrumentation.waitForIdleSync()
    }

    private fun button(label: Int): SemanticsNode? = nodes().firstOrNull {
        it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.Text)
            ?.any { text -> text.text == context.getString(label) } == true
    }

    private fun nodes(): List<SemanticsNode> {
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

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(ready: () -> Boolean) {
        fun reached(): Boolean {
            var result = false
            main { result = ready() }
            return result
        }

        val deadline = System.nanoTime() + 10_000_000_000L
        while (!reached() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("Settings navigation did not reach its expected destination", reached())
    }
}
