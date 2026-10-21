/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.help

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.app.StartupDialogViewModel
import codes.swistak.batterymonitor.app.StartupEnvironment
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.ui.navigation.SectionNavigator
import codes.swistak.batterymonitor.ui.navigation.SectionOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest

class LicenseScreenInstrumentedTest {
    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val context
        get() = instrumentation.targetContext
    private var activityMonitor: Instrumentation.ActivityMonitor? = null

    @Test
    fun inventory_preserves_component_mappings_attributions_and_all_offline_files() {
        val components = loadLicenseInventory(context.assets)
        assertTrue(components.isNotEmpty())
        assertEquals(components.size, components.map { it.id }.toSet().size)
        for ((id, name, _, _, license, attribution, sources, files) in components) {
            assertTrue(name.isNotBlank())
            assertTrue(license.isNotBlank())
            assertTrue(attribution.isNotBlank())
            assertTrue(sources.isNotEmpty())
            assertTrue(files.isNotEmpty())
            for (filename in files) {
                assertNotNull(
                    "$id: $filename", readLicenseText(context.assets, filename)
                )
            }
        }
        val application = components.single { it.id == "battery-monitor" }
        assertEquals("GPL-3.0-or-later", application.license)
        assertTrue(application.attribution.contains("Darshan"))
        assertTrue("GPL-3.0.txt" in application.files)
        assertEquals(
            "CC-BY-SA-3.0", components.single { it.id == "battery-indicator-pro-artwork" }.license
        )
        assertEquals(
            "Apache-2.0", components.single { it.id == "google-material-icons" }.license
        )
        assertTrue(components.any {
            it.id.contains(
                "shizuku", ignoreCase = true
            ) && it.license == "MIT" && it.attribution.contains("RikkaW") && "Shizuku-API-MIT.txt" in it.files
        })
    }

    @Test
    fun packaged_full_legal_texts_keep_the_audited_authoritative_bytes() {
        val hashes = mapOf(
            "GPL-3.0.txt" to "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986",
            "Apache-2.0.txt" to "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
            "CC-BY-SA-3.0.txt" to "3f941b3b89cf7b8370ceb83cc76d2120d471b58735d8ca60238a751a48d7f72f",
            "Shizuku-API-MIT.txt" to "64870037f294b6f2b75c8ebe77cc0702acb3b64f86b4b0e58bf8558525303686"
        )
        for ((filename, expected) in hashes) {
            val bytes = context.assets.open("licenses/$filename").use { it.readBytes() }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
            assertEquals(filename, expected, actual)
        }
        val gpl = requireNotNull(readLicenseText(context.assets, "GPL-3.0.txt"))
        assertTrue(gpl.length > 30_000)
        assertTrue(gpl.contains("16. Limitation of Liability."))
        assertTrue(gpl.contains("https://www.gnu.org/licenses/why-not-lgpl.html"))
    }

    @Test
    fun missing_and_invalid_legal_asset_paths_fail_safely() {
        for (filename in listOf("missing-license.txt", "../COPYING", "/COPYING", "")) {
            assertNull(filename, readLicenseText(context.assets, filename))
        }
    }

    @Test
    fun saved_navigation_restores_nested_licenses_and_existing_contextual_help() {
        val licenses = SectionNavigator(SectionOwner.HELP)
        licenses.openDetail(SectionOwner.HELP, "help:licenses")
        licenses.openDetail(SectionOwner.HELP, "help:licenses:battery-monitor")
        val saved = Bundle()
        licenses.save(saved)
        val restored = SectionNavigator.restore(saved)
        assertEquals(SectionOwner.HELP, restored.selected)
        assertEquals("help:licenses:battery-monitor", restored.detail)
        assertTrue(restored.back())
        assertEquals("help:licenses", restored.detail)
        assertTrue(restored.back())
        assertEquals(SectionOwner.HELP, restored.selected)
        assertNull(restored.detail)

        val contextual = SectionNavigator(SectionOwner.SETTINGS)
        contextual.openDetail(SectionOwner.SETTINGS, "settings:notification")
        contextual.openDetail(
            SectionOwner.HELP, "help:${SettingsContract.KEY_NOTIFICATION_SETTINGS}"
        )
        val helpSaved = Bundle()
        contextual.save(helpSaved)
        val helpRestored = SectionNavigator.restore(helpSaved)
        assertTrue(helpRestored.back())
        assertEquals(SectionOwner.SETTINGS, helpRestored.selected)
        assertEquals("settings:notification", helpRestored.detail)
        assertTrue(helpRestored.back())
        assertEquals(SectionOwner.SETTINGS, helpRestored.selected)
        assertNull(helpRestored.detail)
    }

    @Test
    fun help_entry_opens_scrollable_offline_text_and_back_up_and_recreation_keep_context() {
        withActivity { launched ->
            val title = context.getString(R.string.help_licenses_title)
            val summary = context.getString(R.string.help_licenses_summary)
            await { visibleText(context.getString(R.string.help_cat_version)) }
            repeat(12) {
                if (!visibleText(title)) scrollForward()
            }
            val rootScroll = scrollValue(launched, summary)
            assertTrue("Open-source licenses entry is accessible", tapText(title))
            await { visibleText("Battery Monitor") }
            assertTrue(tapText("Battery Monitor"))
            await { visibleText("GNU GENERAL PUBLIC LICENSE", contains = true) }
            assertTrue("Long legal text must scroll", scrollForward())

            val restored = recreate(launched)
            await { visibleText("GNU GENERAL PUBLIC LICENSE", contains = true) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await {
                visibleText("Battery Monitor") && !visibleText(
                    "GNU GENERAL PUBLIC LICENSE", contains = true
                )
            }
            assertTrue(tapText("Battery Monitor"))
            await { visibleText("GNU GENERAL PUBLIC LICENSE", contains = true) }
            assertTrue(tapText(context.getString(R.string.nav_up)))
            await {
                visibleText("Battery Monitor") && !visibleText(
                    "GNU GENERAL PUBLIC LICENSE", contains = true
                )
            }
            assertTrue(tapText(context.getString(R.string.nav_up)))
            await { visibleText(summary) }
            assertEquals("Help root scroll", rootScroll, scrollValue(restored, summary), 0.0001f)
            assertFalse(visibleText("GNU GENERAL PUBLIC LICENSE", contains = true))
            assertNoHelpFragments(restored)
        }
    }

    @Test
    fun list_and_individual_license_scroll_positions_survive_navigation_and_recreation() {
        withActivity(detail = "help:licenses") { launched ->
            val google =
                loadLicenseInventory(context.assets).single { it.id == "google-material-icons" }
            await { visibleText("Battery Monitor") }
            assertNavigation(launched, SectionOwner.HELP, "help:licenses")
            main {
                val list = scrollNode(launched, "Copyright")
                val index = list.config[SemanticsProperties.IndexForKey](google.id)
                assertTrue(index >= 0)
                assertTrue(list.config[SemanticsActions.ScrollToIndex].action?.invoke(index) == true)
            }
            await { visibleText(google.name) }
            val listScroll = scrollValue(launched, google.name)
            assertTrue("The list must have moved", listScroll > 0f)
            assertTrue(tapText(google.name))
            await { visibleText("Apache License", contains = true) }
            assertTrue(scrollForward())
            val apacheScroll = scrollValue(launched, "Apache License")
            assertTrue(apacheScroll > 0f)
            navigateBack(launched)
            await { visibleText(google.name) && !visibleText("Apache License", contains = true) }
            assertEquals(
                "License list scroll", listScroll, scrollValue(launched, google.name), 0.0001f
            )

            main { launched.openHelp("licenses:battery-monitor") }
            await { visibleText("GNU GENERAL PUBLIC LICENSE", contains = true) }
            repeat(2) { assertTrue(scrollForward()) }
            val gplScroll = scrollValue(launched, "GNU GENERAL PUBLIC LICENSE")
            assertTrue(gplScroll > apacheScroll)
            navigateBack(launched)
            await {
                visibleText(google.name) && !visibleText(
                    "GNU GENERAL PUBLIC LICENSE", contains = true
                )
            }
            assertEquals(
                "License list scroll", listScroll, scrollValue(launched, google.name), 0.0001f
            )
            main { launched.openHelp("licenses:battery-monitor") }
            await { visibleText("GNU GENERAL PUBLIC LICENSE", contains = true) }
            assertEquals(
                "GPL route scroll",
                gplScroll,
                scrollValue(launched, "GNU GENERAL PUBLIC LICENSE"),
                1f
            )

            val restored = recreate(launched)
            await { visibleText("GNU GENERAL PUBLIC LICENSE", contains = true) }
            assertEquals(
                "Recreated GPL scroll",
                gplScroll,
                scrollValue(restored, "GNU GENERAL PUBLIC LICENSE"),
                1f
            )
            assertNavigation(restored, SectionOwner.HELP, "help:licenses:battery-monitor")
            navigateBack(restored)
            await {
                visibleText(google.name) && !visibleText(
                    "GNU GENERAL PUBLIC LICENSE", contains = true
                )
            }
            assertEquals(
                "Recreated list scroll", listScroll, scrollValue(restored, google.name), 0.0001f
            )
            assertTrue(tapText(google.name))
            await { visibleText("Apache License", contains = true) }
            assertEquals(
                "Apache route scroll", apacheScroll, scrollValue(restored, "Apache License"), 1f
            )
            assertNoHelpFragments(restored)
        }
    }

    @Test
    fun compose_contextual_help_returns_to_the_actual_settings_category_without_help_fragments() {
        withActivity(section = "settings", detail = "settings:notification") { activity ->
            await { visibleText(context.getString(R.string.settings_notification)) }
            assertNavigation(activity, SectionOwner.SETTINGS, "settings:notification")
            repeat(2) { index ->
                main { activity.openHelp(SettingsContract.KEY_NOTIFICATION_SETTINGS) }
                await { visibleText(context.getString(R.string.pref_cat_channel_settings)) }
                assertNavigation(
                    activity,
                    SectionOwner.HELP,
                    "help:${SettingsContract.KEY_NOTIFICATION_SETTINGS}"
                )
                assertNoHelpFragments(activity)
                if (index == 0) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                else assertTrue(tapText(context.getString(R.string.nav_up)))
                await { visibleText(context.getString(R.string.settings_notification)) }
                assertNavigation(activity, SectionOwner.SETTINGS, "settings:notification")
            }
            main { activity.selectSection(SectionOwner.HELP) }
            await { visibleText(context.getString(R.string.help_cat_version)) }
            assertNavigation(activity, SectionOwner.HELP, null)
            assertNoHelpFragments(activity)
        }
    }

    @Test
    fun removed_help_fragment_saved_class_restores_through_the_factory_and_is_cleaned() {
        assumeTrue(
            "The serialized migration fixture needs post-save callbacks",
            Build.VERSION.SDK_INT >= 29
        )
        withActivity { activity ->
            await { visibleText(context.getString(R.string.help_cat_version)) }
            val legacyClass = "codes.swistak.batterymonitor.ui.help.LegacyHelpFragment"
            val legacyTag = "section:help:help:licenses"
            main {
                val manager = activity.supportFragmentManager
                val placeholder = manager.fragmentFactory.instantiate(
                    context.classLoader, legacyClass
                )
                assertEquals(Fragment::class.java, placeholder.javaClass)
                manager.commitNow { add(placeholder, legacyTag) }
            }

            // shortcut: this fixture uses Fragment 1.9.1 state fields; update it when Fragment changes.
            fun restoreLegacyClass(bundle: Bundle): Int {
                var patched = 0
                for (key in bundle.keySet()) {
                    // The saved-state fixture must inspect values whose types are not known in advance.
                    @Suppress("DEPRECATION") val value = bundle.get(key)
                    if (value is Bundle) patched += restoreLegacyClass(value)
                    else if (value?.javaClass?.name == "androidx.fragment.app.FragmentState") {
                        val tag = value.javaClass.getDeclaredField("mTag")
                        tag.isAccessible = true
                        if (tag.get(value) == legacyTag) {
                            val className = value.javaClass.getDeclaredField("mClassName")
                            className.isAccessible = true
                            className.set(value, legacyClass)
                            patched++
                        }
                    }
                }
                return patched
            }

            var patched = 0
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPostSaveInstanceState(
                    savedActivity: Activity, outState: Bundle
                ) {
                    if (savedActivity === activity) patched += restoreLegacyClass(outState)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
                    Unit

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) =
                    Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            main {
                activity.application.registerActivityLifecycleCallbacks(
                    callbacks
                )
            }
            try {
                val restored = recreate(activity)
                main {
                    assertEquals(
                        "Rewrote the serialized legacy class", 1, patched
                    )
                }
                await { visibleText(context.getString(R.string.help_cat_version)) }
                assertNavigation(restored, SectionOwner.HELP, null)
                assertNoHelpFragments(restored)
            } finally {
                main {
                    activity.application.unregisterActivityLifecycleCallbacks(
                        callbacks
                    )
                }
            }
        }
    }

    private fun withActivity(
        section: String = "help", detail: String? = null, test: (BatteryInfoActivity) -> Unit
    ) {
        val preferences =
            context.getSharedPreferences(SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE)
        val hadFirstRun = preferences.contains(SettingsContract.KEY_FIRST_RUN)
        val firstRun = preferences.getBoolean(SettingsContract.KEY_FIRST_RUN, true)
        val hadAcknowledgement =
            preferences.contains(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION)
        val acknowledgedVersion =
            preferences.getLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, 0)
        val monitor = instrumentation.addMonitor(BatteryInfoActivity::class.java.name, null, false)
        activityMonitor = monitor
        try {
            prepareOnboarding()
            val launched = instrumentation.startActivitySync(
                Intent(
                    context, BatteryInfoActivity::class.java
                ).putExtra(BatteryInfoActivity.EXTRA_SECTION, section)
                    .putExtra(BatteryInfoActivity.EXTRA_DETAIL, detail)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ) as BatteryInfoActivity
            dismissStartupDialogs(launched)
            test(launched)
        } finally {
            main { monitor.lastActivity?.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.removeMonitor(monitor)
            activityMonitor = null
            val restore = preferences.edit()
            if (hadFirstRun) restore.putBoolean(SettingsContract.KEY_FIRST_RUN, firstRun)
            else restore.remove(SettingsContract.KEY_FIRST_RUN)
            if (hadAcknowledgement) restore.putLong(
                StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, acknowledgedVersion
            )
            else restore.remove(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION)
            assertTrue("Restore the original onboarding preference", restore.commit())
        }
    }

    private fun prepareOnboarding() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val preferences =
                context.getSharedPreferences(SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE)
            assertTrue(preferences.edit().putBoolean(SettingsContract.KEY_FIRST_RUN, true).commit())
        }
    }

    private fun recreate(activity: BatteryInfoActivity): BatteryInfoActivity {
        val monitor = instrumentation.addMonitor(BatteryInfoActivity::class.java.name, null, false)
        try {
            prepareOnboarding()
            main { activity.recreate() }
            await { monitor.lastActivity != null && monitor.lastActivity !== activity }
            val restored = monitor.lastActivity as BatteryInfoActivity
            dismissStartupDialogs(restored)
            return restored
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun assertNoHelpFragments(activity: BatteryInfoActivity) {
        main {
            assertTrue(activity.supportFragmentManager.fragments.none {
                it.tag?.startsWith("section:help:") == true
            })
        }
    }

    private fun navigateBack(activity: BatteryInfoActivity) {
        main { activity.onBackPressedDispatcher.onBackPressed() }
        instrumentation.waitForIdleSync()
    }

    private fun assertNavigation(
        activity: BatteryInfoActivity, owner: SectionOwner, detail: String?
    ) {
        main {
            val field = BatteryInfoActivity::class.java.getDeclaredField("navigator")
            field.isAccessible = true
            val navigator = field.get(activity) as SectionNavigator
            assertEquals(owner, navigator.selected)
            assertEquals(detail, navigator.detail)
        }
    }

    private fun scrollNode(activity: BatteryInfoActivity, marker: String): SemanticsNode {
        fun containsText(node: SemanticsNode): Boolean =
            node.config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text.contains(marker) } == true || node.children.any(::containsText)

        fun scroll(node: SemanticsNode): SemanticsNode? {
            val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            if (range != null && containsText(node)) return node
            for (child in node.children) scroll(child)?.let { return it }
            return null
        }

        fun find(view: View): SemanticsNode? {
            if (!view.isShown) return null
            val owner = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }
                ?.invoke(view) as? SemanticsOwner
            owner?.let { scroll(it.rootSemanticsNode)?.let { position -> return position } }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            }
            return null
        }

        return requireNotNull(find(activity.window.decorView)) { "No active Compose scroll range contains $marker" }
    }

    private fun scrollValue(activity: BatteryInfoActivity, marker: String): Float {
        var value = readScrollValue(activity, marker)
        var stableSince = System.nanoTime()
        val deadline = stableSince + 5_000_000_000L
        while (System.nanoTime() < deadline && System.nanoTime() - stableSince < 150_000_000L) {
            Thread.sleep(20)
            val next = readScrollValue(activity, marker)
            if (next != value) stableSince = System.nanoTime()
            value = next
        }
        assertTrue(
            "License scroll must settle before saving or comparing it",
            System.nanoTime() - stableSince >= 150_000_000L
        )
        return value
    }

    private fun readScrollValue(activity: BatteryInfoActivity, marker: String): Float {
        var value = 0f
        main {
            value = scrollNode(
                activity, marker
            ).config[SemanticsProperties.VerticalScrollAxisRange].value()
        }
        return value
    }

    private fun dismissStartupDialogs(activity: BatteryInfoActivity) {
        main {
            val controller = ViewModelProvider(activity)[StartupDialogViewModel::class.java]
            assertTrue(controller.acknowledgeChangelog())
            controller.cancelOnboarding()
            controller.advance(
                StartupEnvironment(
                    notificationPermissionRequired = false,
                    notificationsGranted = true,
                    batteryOptimized = true
                )
            )
            controller.dismissBatteryPrompt()
            assertFalse(controller.blocksContentDialogs)
        }
        instrumentation.waitForIdleSync()
    }

    private fun visibleText(text: String, contains: Boolean = false): Boolean {
        fun matches(values: List<String>): Boolean =
            values.any { if (contains) it.contains(text) else text in it.lines() }
        return findNode { node ->
            matches(listOfNotNull(node.text?.toString(), node.contentDescription?.toString()))
        } != null || withApi26ComposeNode({ node -> matches(composeText(node)) }) { true }
    }

    private fun tapText(text: String): Boolean {
        var node = findNode {
            it.text?.toString()?.lines()
                ?.contains(text) == true || it.contentDescription?.toString() == text
        }
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync()
                return true
            }
            node = node.parent
        }
        val clicked =
            withApi26ComposeNode({ node -> composeText(node).any { text in it.lines() } }) {
                var target: SemanticsNode? = it
                var performed = false
                while (target != null && !performed) {
                    if (!target.config.contains(SemanticsProperties.Disabled)) {
                        performed =
                            target.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
                    }
                    target = target.parent
                }
                performed
            }
        if (clicked) instrumentation.waitForIdleSync()
        return clicked
    }

    private fun scrollForward(): Boolean {
        val node = findNode { it.isScrollable }
        val scrolled = node?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            ?: withApi26ComposeNode({ candidate ->
                candidate.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let {
                    it.value() < it.maxValue()
                } == true && candidate.config.contains(SemanticsActions.ScrollBy)
            }) {
                it.config[SemanticsActions.ScrollBy].action?.invoke(
                    0f, it.boundsInWindow.height * 0.8f
                ) == true
            }
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
        return scrolled
    }

    private fun composeText(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty()
            .map { it.text } + node.config.getOrNull(SemanticsProperties.ContentDescription)
            .orEmpty()

    private fun withApi26ComposeNode(
        matches: (SemanticsNode) -> Boolean, action: (SemanticsNode) -> Boolean
    ): Boolean {
        // API26 sometimes exposes no active accessibility root while the focused Compose screen is ready.
        if (Build.VERSION.SDK_INT != 26 || instrumentation.uiAutomation.rootInActiveWindow != null) return false
        var performed = false
        main {
            val activity = activityMonitor?.lastActivity as? BatteryInfoActivity ?: return@main
            if (!activity.window.decorView.hasWindowFocus()) return@main
            fun hidden(node: SemanticsNode): Boolean =
                node.config.contains(SemanticsProperties.HideFromAccessibility) || node.parent?.let(
                    ::hidden
                ) == true

            fun findSemantics(node: SemanticsNode): SemanticsNode? {
                val bounds = node.boundsInWindow
                if (bounds.width > 0 && bounds.height > 0 && !hidden(node) && matches(node)) return node
                return node.children.firstNotNullOfOrNull(::findSemantics)
            }

            fun findView(view: View): SemanticsNode? {
                if (!view.isShown) return null
                val owner = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }
                    ?.invoke(view) as? SemanticsOwner
                owner?.let { findSemantics(it.rootSemanticsNode)?.let { node -> return node } }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) findView(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            findView(activity.window.decorView)?.let { performed = action(it) }
        }
        return performed
    }

    private fun findNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && matches(node)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { find(it)?.let { found -> return found } }
            }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun await(ready: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!ready() && System.nanoTime() < deadline) Thread.sleep(20)
        val reached = ready()
        if (!reached) runCatching { reportReadinessTimeout() }
        assertTrue("License screen did not reach the expected state", reached)
    }

    private fun reportReadinessTimeout() {
        val record = Bundle().apply {
            putString("helpReadinessDiagnostic", "timeout")
            putString("contextConfiguration", context.resources.configuration.toString())
        }
        runCatching {
            main {
                val activity = activityMonitor?.lastActivity as? BatteryInfoActivity
                record.putString("activity", activity?.let {
                    "destroyed=${it.isDestroyed}, finishing=${it.isFinishing}, " + "lifecycle=${it.lifecycle.currentState}, focus=${it.window.decorView.hasWindowFocus()}, " + "configuration=${it.resources.configuration}"
                })
                activity?.let {
                    val field = BatteryInfoActivity::class.java.getDeclaredField("navigator")
                    field.isAccessible = true
                    val navigator = field.get(it) as SectionNavigator
                    val startup = ViewModelProvider(it)[StartupDialogViewModel::class.java]
                    record.putString(
                        "navigationAndStartup",
                        "owner=${navigator.selected}, detail=${navigator.detail}, " + "startup=${startup.presentation}, blocked=${startup.blocksContentDialogs}"
                    )
                    val texts = mutableListOf<String>()
                    fun collect(node: SemanticsNode) {
                        if (texts.size >= 60) return
                        val text = node.config.getOrNull(SemanticsProperties.Text)
                            ?.joinToString(" | ") { value -> value.text }
                        val description =
                            node.config.getOrNull(SemanticsProperties.ContentDescription)
                                ?.joinToString(" | ")
                        if (text != null || description != null) {
                            texts += "bounds=${node.boundsInWindow}, text=${text?.take(160)}, " + "description=${
                                description?.take(
                                    80
                                )
                            }"
                        }
                        node.children.forEach(::collect)
                    }

                    fun inspect(view: View) {
                        if (!view.isShown) return
                        val owner = view.javaClass.methods.firstOrNull { method ->
                            method.name == "getSemanticsOwner"
                        }?.invoke(view) as? SemanticsOwner
                        owner?.rootSemanticsNode?.let(::collect)
                        if (view is ViewGroup) {
                            for (index in 0 until view.childCount) inspect(view.getChildAt(index))
                        }
                    }
                    inspect(it.window.decorView)
                    record.putStringArrayList("activityComposeText", ArrayList(texts))
                }
            }
        }.onFailure { record.putString("activityDiagnosticError", it.toString()) }
        val nodes = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo) {
            if (nodes.size >= 80) return
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            nodes += "window=${node.windowId}, package=${node.packageName}, class=${node.className}, " + "visible=${node.isVisibleToUser}, focused=${node.isFocused}, bounds=$bounds, " + "text=${
                node.text?.toString()?.take(160)
            }, " + "description=${node.contentDescription?.toString()?.take(80)}"
            for (index in 0 until node.childCount) {
                if (nodes.size >= 80) break
                val child = node.getChild(index) ?: continue
                try {
                    collect(child)
                } finally {
                    // Accessibility nodes still require recycling before API 33.
                    @Suppress("DEPRECATION") child.recycle()
                }
            }
        }
        runCatching {
            instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                try {
                    collect(root)
                } finally {
                    @Suppress("DEPRECATION") root.recycle()
                }
            }
        }.onFailure { record.putString("accessibilityDiagnosticError", it.toString()) }
        record.putStringArrayList("activeWindowAccessibility", ArrayList(nodes))
        instrumentation.sendStatus(0, record)
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
}
