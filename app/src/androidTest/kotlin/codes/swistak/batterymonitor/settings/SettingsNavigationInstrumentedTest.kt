/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
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
import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import codes.swistak.batterymonitor.logs.HistoryMetric
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.monitoring.BatteryMeasurementTime
import codes.swistak.batterymonitor.ui.diagnostics.DiagnosticsFormat
import codes.swistak.batterymonitor.ui.diagnostics.DiagnosticsProjectionCache
import codes.swistak.batterymonitor.ui.diagnostics.DiagnosticsScreen
import codes.swistak.batterymonitor.ui.diagnostics.DiagnosticsState
import codes.swistak.batterymonitor.ui.diagnostics.diagnosticTime
import codes.swistak.batterymonitor.ui.help.HelpScreen
import codes.swistak.batterymonitor.ui.help.helpSections
import codes.swistak.batterymonitor.ui.history.FullscreenChartActivity
import codes.swistak.batterymonitor.ui.settings.SettingsDialog
import codes.swistak.batterymonitor.ui.settings.SettingsDialogs
import codes.swistak.batterymonitor.ui.settings.SettingsScreen
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import codes.swistak.batterymonitor.ui.theme.Brightness
import codes.swistak.batterymonitor.ui.theme.ColorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class SettingsNavigationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var activity: FullscreenChartActivity

    @Test
    fun appearance_summaries_keep_one_localized_prefix_when_choices_change() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        for (language in listOf("en", "pl")) withHost {
            val configuration = Configuration(activity.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val localized = activity.createConfigurationContext(configuration)
            val settings = context.getSharedPreferences(
                SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
            )
            val keys = listOf(SettingsContract.KEY_COLOR_SOURCE, SettingsContract.KEY_BRIGHTNESS)
            val before = keys.associateWith { settings.getString(it, null) }
            var version by mutableStateOf(0)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
            val prefix = localized.getString(R.string.currently_set_to)
            var registry = SaveableStateRegistry(null) { true }

            fun content() {
                val screen = PreferenceManager(activity).createPreferenceScreen(activity)
                activity.setContent {
                    CompositionLocalProvider(
                        LocalContext provides localized,
                        LocalResources provides localized.resources,
                        LocalConfiguration provides configuration,
                        LocalSaveableStateRegistry provides registry
                    ) {
                        AppBatteryTheme {
                            SettingsScreen(
                                screen,
                                version,
                                "general",
                                null,
                                {},
                                { _, _ -> },
                                { _, _ -> })
                        }
                    }
                }
            }

            fun hasText(text: String) = nodes(includeDialogs = true).any {
                it.config.getOrNull(SemanticsProperties.Text)
                    ?.any { value -> value.text == text } == true
            }

            fun select(label: Int) {
                main {
                    val text = localized.getString(label)
                    val node = nodes(includeDialogs = true).first {
                        it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(
                            SemanticsProperties.Text
                        )?.any { value -> value.text == text } == true
                    }
                    assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
                }
            }

            try {
                settings.edit().putString(keys[0], ColorSource.Dynamic.name)
                    .putString(keys[1], Brightness.System.name).commit()
                settings.registerOnSharedPreferenceChangeListener(listener)
                main { content() }
                await {
                    hasText(prefix + localized.getString(R.string.settings_color_palette_material_you)) && hasText(
                        prefix + localized.getString(R.string.settings_brightness_system)
                    )
                }
                for ((title, choice, keyAndValue) in listOf(
                    Triple(
                        R.string.settings_color_palette,
                        R.string.settings_color_palette_battery_blue,
                        keys[0] to ColorSource.BatteryBlue.name
                    ), Triple(
                        R.string.settings_brightness,
                        R.string.settings_brightness_dark,
                        keys[1] to Brightness.Dark.name
                    )
                )) {
                    val committed = settings.getString(keyAndValue.first, null)
                    select(title)
                    await { hasText(localized.getString(choice)) }
                    select(choice)
                    assertEquals(committed, settings.getString(keyAndValue.first, null))
                    select(R.string.cancel)
                    assertEquals(committed, settings.getString(keyAndValue.first, null))
                    select(title)
                    await { hasText(localized.getString(choice)) }
                    main {
                        assertFalse(nodes(includeDialogs = true).any {
                            it.config.getOrNull(SemanticsProperties.Selected) == true && it.config.getOrNull(
                                SemanticsProperties.Text
                            )?.any { value -> value.text == localized.getString(choice) } == true
                        })
                    }
                    select(choice)
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    instrumentation.waitForIdleSync()
                    assertEquals(committed, settings.getString(keyAndValue.first, null))
                    await { hasText(localized.getString(title)) }
                    select(title)
                    await { hasText(localized.getString(choice)) }
                    select(choice)
                    main {
                        val saved = registry.performSave()
                        activity.setContentView(FrameLayout(activity))
                        registry = SaveableStateRegistry(saved) { true }
                        content()
                    }
                    await { hasText(localized.getString(R.string.okay)) }
                    assertEquals(committed, settings.getString(keyAndValue.first, null))
                    main {
                        val confirm = nodes(includeDialogs = true).first {
                            it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(
                                SemanticsProperties.Text
                            )
                                ?.any { value -> value.text == localized.getString(R.string.okay) } == true
                        }.config[SemanticsActions.OnClick].action!!
                        confirm()
                        confirm()
                    }
                    await { hasText(prefix + localized.getString(choice)) }
                    assertEquals(keyAndValue.second, settings.getString(keyAndValue.first, null))
                }
            } finally {
                settings.unregisterOnSharedPreferenceChangeListener(listener)
                settings.edit().apply {
                    for ((key, value) in before) if (value == null) remove(key) else putString(
                        key, value
                    )
                }.commit()
            }
        }
    }

    @Test
    fun diagnostics_access_requests_settings_without_changing_the_saved_preference() = withHost {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val settings =
            context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
        val before = settings.all[SettingsContract.KEY_USE_PRIVILEGED_ACCESS]
        var navigationRequests = 0
        main {
            activity.setContent {
                AppBatteryTheme {
                    DiagnosticsScreen(
                        DiagnosticsState(),
                        {},
                        {},
                        {},
                        { navigationRequests++ },
                        tab = 3
                    )
                }
            }
        }
        await { nodes().any { it.config.contains(SemanticsActions.ScrollBy) } }
        main {
            nodes().first { it.config.contains(SemanticsActions.ScrollBy) }.config[SemanticsActions.ScrollBy].action?.invoke(
                0f, 100_000f
            )
        }
        fun accessRow() = nodes().firstOrNull {
            it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.Text)
                ?.any { value -> value.text == context.getString(R.string.pref_cat_privileged_access) } == true
        }
        await { accessRow() != null }
        main { assertTrue(accessRow()!!.config[SemanticsActions.OnClick].action?.invoke() == true) }
        instrumentation.waitForIdleSync()
        assertEquals(1, navigationRequests)
        assertEquals(before, settings.all[SettingsContract.KEY_USE_PRIVILEGED_ACCESS])
        assertFalse(nodes(includeDialogs = true).any {
            it.config.contains(SemanticsProperties.IsDialog)
        })
    }

    @Suppress("UsePropertyAccessSyntax")
    @Test
    fun technology_details_and_copy_preserve_android_and_battery_sysfs_readings() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        for (language in listOf("en", "pl")) for (sysfs in listOf(false, true)) withHost {
            val configuration = Configuration(activity.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val localized = activity.createConfigurationContext(configuration)
            val chemistry = if (sysfs) "Li-polymer" else "Li-ion"
            val raw = "  $chemistry\n"
            val key = if (sysfs) "battery/technology" else "technology"
            val source = if (sysfs) "/sys/class/power_supply/battery/technology"
            else "ACTION_BATTERY_CHANGED / technology"
            val acquired = 1_794_225_600_000L
            val snapshot = AdvancedBatterySnapshot().apply {
                accessMethod = if (sysfs) AdvancedBatterySnapshot.ACCESS_ROOT else "Android"
                capturedAtMillis = acquired + 60_000
                sourceReadTimes[source] = BatteryMeasurementTime(acquired, 100)
                if (sysfs) {
                    sysfsLabels.addAll(listOf("battery/type", key))
                    sysfsValues.addAll(listOf("Battery", raw))
                } else {
                    serviceLabels.add(key)
                    serviceValues.add(raw)
                }
            }
            val state = if (sysfs) DiagnosticsState(advanced = snapshot, privilegedEnabled = true)
            else DiagnosticsState(basic = snapshot, privilegedEnabled = false)
            assertEquals(
                chemistry, DiagnosticsProjectionCache().groups(
                listOfNotNull(state.basic, state.appRaw, state.advanced),
                DiagnosticsFormat(1, false, Locale.forLanguageTag(language)),
                tab = 1
            ).flatMap { it.rows }.single { it.label == R.string.diag_battery_technology }.value
            )
            val settings =
                context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
            val before = settings.all[SettingsContract.KEY_USE_PRIVILEGED_ACCESS]
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val previousClip = clipboard.primaryClip
            var tab by mutableStateOf(1)

            fun texts(node: SemanticsNode) =
                node.config.getOrNull(SemanticsProperties.Text).orEmpty()

            fun hasText(value: String) = nodes(includeDialogs = true).any { node ->
                texts(node).any { it.text == value }
            }

            fun action(value: String) = nodes(includeDialogs = true).firstOrNull { node ->
                node.config.contains(SemanticsActions.OnClick) && texts(node).any { it.text == value }
            }

            fun select(value: String) {
                await { action(value) != null }
                main { assertTrue(action(value)!!.config[SemanticsActions.OnClick].action?.invoke() == true) }
                instrumentation.waitForIdleSync()
            }

            fun dialogText(): List<String> {
                fun descendants(node: SemanticsNode): List<SemanticsNode> =
                    listOf(node) + node.children.flatMap(::descendants)

                val dialog = nodes(includeDialogs = true).first {
                    it.config.contains(SemanticsProperties.IsDialog)
                }
                return descendants(dialog).flatMap { texts(it) }.map { it.text }
            }

            fun capture(name: String) {
                instrumentation.waitForIdleSync()
                // Let dialog animations settle before recording visual evidence.
                Thread.sleep(350)
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val directory = requireNotNull(context.getExternalFilesDir("diagnostics-fourteen"))
                directory.mkdirs()
                File(
                    directory, "$language-${if (sysfs) "sysfs" else "android"}-$name.png"
                ).outputStream()
                    .use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }

            try {
                main {
                    activity.setContent {
                        CompositionLocalProvider(
                            LocalContext provides localized,
                            LocalResources provides localized.resources,
                            LocalConfiguration provides configuration
                        ) {
                            AppBatteryTheme {
                                DiagnosticsScreen(
                                    state,
                                    {},
                                    {},
                                    {},
                                    {},
                                    onTab = { tab = it },
                                    tab = tab
                                )
                            }
                        }
                    }
                }
                capture("before-scroll")
                await {
                    nodes().any { it.config.contains(SemanticsActions.ScrollToIndex) } && nodes().any { node ->
                        texts(
                            node
                        ).any { it.text.contains(if (sysfs) "Root" else "Android") }
                    }
                }
                main {
                    val capacityIndex =
                        nodes().first { it.config.contains(SemanticsProperties.IndexForKey) }.config[SemanticsProperties.IndexForKey](
                            "1:1:${R.string.diag_section_capacity}"
                        )
                    assertTrue(capacityIndex >= 0)
                    assertTrue(
                        nodes().first { it.config.contains(SemanticsActions.ScrollToIndex) }.config[SemanticsActions.ScrollToIndex].action?.invoke(
                            capacityIndex
                        ) == true
                    )
                }
                try {
                    await { action(chemistry) != null }
                } catch (failure: AssertionError) {
                    capture("battery-failure")
                    var visible = ""
                    main { visible = nodes().flatMap { texts(it) }.joinToString("\n") { it.text } }
                    File(
                        context.getExternalFilesDir("diagnostics-fourteen"),
                        "$language-${if (sysfs) "sysfs" else "android"}-battery-failure.txt"
                    ).writeText(visible)
                    throw failure
                }
                main {
                    assertEquals(
                        1, nodes().count { texts(it).any { value -> value.text == chemistry } })
                }
                capture("battery")
                select(chemistry)
                await { nodes(includeDialogs = true).any { it.config.contains(SemanticsProperties.IsDialog) } }
                main {
                    val text = dialogText()
                    assertTrue(chemistry in text)
                    assertTrue(text.any { it.contains(if (sysfs) "Root" else "Android") })
                    assertTrue(text.any { it.contains(diagnosticTime(acquired)) })
                    assertFalse(text.any { it.contains(diagnosticTime(snapshot.capturedAtMillis)) })
                }
                capture("detail")
                select(localized.getString(R.string.charging_diagnostics_copy))
                main {
                    assertEquals(
                        chemistry,
                        clipboard.primaryClip!!.getItemAt(0).text.toString().substringAfter(": ")
                    )
                }
                select(localized.getString(R.string.cancel))
                select(localized.getString(R.string.diag_raw))
                await { nodes().any { it.config.contains(SemanticsActions.SetText) } }
                main {
                    assertTrue(
                        nodes().first { it.config.contains(SemanticsActions.SetText) }.config[SemanticsActions.SetText].action?.invoke(
                            AnnotatedString("technology")
                        ) == true
                    )
                }
                await { action(key) != null }
                select(key)
                await {
                    hasText(raw) && nodes(includeDialogs = true).any {
                        it.config.contains(
                            SemanticsProperties.IsDialog
                        )
                    }
                }
                main {
                    val text = dialogText()
                    assertTrue(raw in text)
                    assertTrue(text.any { it.contains(if (sysfs) "/sys/class/power_supply / $key (Root)" else "$source (Android)") })
                    assertTrue(text.any { it.contains(diagnosticTime(acquired)) })
                }
                capture("raw-detail")
                select(localized.getString(R.string.charging_diagnostics_copy))
                main {
                    assertEquals(
                        "$key=$raw", clipboard.primaryClip!!.getItemAt(0).text.toString()
                    )
                }
                assertEquals(before, settings.all[SettingsContract.KEY_USE_PRIVILEGED_ACCESS])
            } finally {
                main {
                    if (previousClip == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(
                        previousClip
                    )
                }
            }
        }
    }

    @Test
    fun automatic_export_preserves_folder_step_drafts_and_only_confirms_the_current_session_once() =
        withHost {
            assumeTrue(Build.VERSION.SDK_INT >= 29)
            var dialog by mutableStateOf<SettingsDialog?>(null)
            var registry = SaveableStateRegistry(null) { true }
            val folderRequests = mutableListOf<Triple<Int, Int, Int>>()
            val confirmations = mutableListOf<Pair<String, Triple<Int, Int, Int>>>()

            fun configuration(directory: String): SettingsDialog.AutoExport =
                SettingsDialog.AutoExport(
                    title = "Synthetic automatic export",
                    directoryLabel = directory,
                    frequencyLabels = listOf("Original frequency", "Draft frequency"),
                    frequencyEnabled = listOf(true, true),
                    frequencyIndex = 0,
                    modeLabels = listOf("Original mode", "Draft mode"),
                    modeIndex = 0,
                    formatLabels = listOf("Original format", "Draft format"),
                    formatIndex = 0,
                    configured = true,
                    onConfirm = { frequency, mode, format ->
                        confirmations += directory to Triple(frequency, mode, format)
                    },
                    onChooseDirectory = { frequency, mode, format ->
                        folderRequests += Triple(frequency, mode, format)
                        dialog = configuration("Chosen folder")
                    },
                    onDisable = { error("Disable is an explicit operation outside this editing check") })

            fun content() {
                activity.setContent {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                        AppBatteryTheme { SettingsDialogs(dialog, false) { dialog = null } }
                    }
                }
            }

            fun action(text: String) = nodes(includeDialogs = true).firstOrNull {
                it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(
                    SemanticsProperties.Text
                )?.any { value -> value.text == text } == true
            }

            fun select(text: String) {
                await { action(text) != null }
                main { assertTrue(action(text)!!.config[SemanticsActions.OnClick].action?.invoke() == true) }
                instrumentation.waitForIdleSync()
            }

            fun draft() {
                for ((initial, edited) in listOf(
                    "Original frequency" to "Draft frequency",
                    "Original mode" to "Draft mode",
                    "Original format" to "Draft format"
                )) {
                    select(initial)
                    select(edited)
                }
            }

            main { dialog = configuration("Original folder"); content() }
            draft()
            select(context.getString(R.string.pref_auto_log_export_directory))
            assertEquals(listOf(Triple(1, 1, 1)), folderRequests)
            assertTrue(confirmations.isEmpty())
            await { action("Draft frequency") != null && action("Draft mode") != null && action("Draft format") != null }
            select(context.getString(R.string.cancel))
            await {
                dialog == null && nodes(includeDialogs = true).none {
                    it.config.contains(SemanticsProperties.IsDialog)
                }
            }
            assertTrue(confirmations.isEmpty())

            main { dialog = configuration("Original folder") }
            await {
                action("Original frequency") != null && action("Original mode") != null && action(
                    "Original format"
                ) != null
            }
            draft()
            select(context.getString(R.string.pref_auto_log_export_directory))
            main {
                val saved = registry.performSave()
                activity.setContentView(FrameLayout(activity))
                registry = SaveableStateRegistry(saved) { true }
                content()
            }
            await { action("Draft frequency") != null && action("Draft mode") != null && action("Draft format") != null }
            assertTrue(confirmations.isEmpty())
            main {
                val confirm =
                    action(context.getString(R.string.okay))!!.config[SemanticsActions.OnClick].action!!
                confirm()
                confirm()
            }
            assertEquals(listOf("Chosen folder" to Triple(1, 1, 1)), confirmations)
            assertEquals(listOf(Triple(1, 1, 1), Triple(1, 1, 1)), folderRequests)
        }

    @Test
    fun time_estimates_does_not_repeat_estimator_help_below_the_selector() = withHost {
        main {
            val screen = PreferenceManager(activity).createPreferenceScreen(activity)
            screen.addPreference(Preference(activity).apply {
                key = SettingsContract.KEY_PREDICTION_TYPE
                title = context.getString(R.string.pref_prediction_type)
            })
            activity.setContent {
                AppBatteryTheme {
                    SettingsScreen(
                        preferences = screen,
                        version = 0,
                        initialCategory = "time",
                        highlightKey = null,
                        onActivate = {},
                        onNavigateCategory = { _, _ -> },
                        onDiagnostics = { _, _ -> })
                }
            }
        }
        await { button(R.string.pref_prediction_type) != null }
        main {
            val displayedText = nodes().flatMap {
                it.config.getOrNull(SemanticsProperties.Text).orEmpty()
            }.map { it.text }
            assertFalse(context.getString(R.string.pref_prediction_type_help) in displayedText)
        }
    }

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
            click(R.string.diag_monitor_operation)
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
            R.string.diag_monitor_operation to ("monitor" to null),
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
                    0f, 100_000f
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

    private fun nodes(includeDialogs: Boolean = false): List<SemanticsNode> {
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

        val roots = if (includeDialogs && Build.VERSION.SDK_INT >= 29) {
            WindowInspector.getGlobalWindowViews().filter { it.isShown }
        } else listOf(activity.window.decorView)
        return roots.flatMap(::owners).flatMap { descendants(it.rootSemanticsNode) }
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
