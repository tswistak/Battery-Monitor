/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.settings

import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.settings.SettingsContract.KEY_BRIGHTNESS
import codes.swistak.batterymonitor.settings.SettingsContract.KEY_COLOR_SOURCE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SettingCatalogTest {
    @Test
    fun `every existing settings control and appearance control has a searchable destination`() {
        val keys = preferenceKeys()
        assertEquals(
            keys + setOf(KEY_COLOR_SOURCE, KEY_BRIGHTNESS) + SettingCatalog.diagnosticRoutes.keys,
            SettingCatalog.routes.keys
        )
        assertEquals(SettingsCategory.entries.toSet(), SettingCatalog.routes.values.toSet())
        assertEquals(6, SettingsCategory.entries.map { it.route }.distinct().size)
    }

    @Test
    fun `logging export and privileged access share Advanced while appearance belongs to General`() {
        for (key in listOf(
            "enable_logging", "max_log_age", "auto_log_export", "use_privileged_access"
        )) {
            assertEquals(key, SettingsCategory.Advanced, SettingCatalog.routes[key])
        }
        assertEquals(SettingsCategory.General, SettingCatalog.routes[KEY_COLOR_SOURCE])
        assertEquals(SettingsCategory.General, SettingCatalog.routes[KEY_BRIGHTNESS])
        assertEquals(
            SettingsCategory.Current, SettingCatalog.routes["prefer_average_battery_current"]
        )
        assertEquals(SettingsCategory.Backup, SettingCatalog.routes["reset_default_settings"])
    }

    @Test
    fun `removed measurement switches and internal bookkeeping are not settings controls`() {
        val keys = preferenceKeys() + SettingCatalog.routes.keys
        for (key in listOf(
            "enable_battery_current",
            "enable_advanced_stats",
            "enable_current_hack",
            "battery_current_refresh_interval",
            "battery_current_multiplier_detection_pending",
            "first_run",
            "ui_color",
            "chip_content_order",
            "vital_signs_order"
        )) {
            assertFalse(key, key in keys)
        }
    }

    @Test
    fun `old settings category links resolve to their corresponding current destination`() {
        val expected = mapOf(
            "other_settings" to SettingsCategory.General,
            "current_state_settings" to SettingsCategory.Current,
            "time_estimates_settings" to SettingsCategory.Time,
            "notification_settings" to SettingsCategory.Notification,
            "advanced_settings" to SettingsCategory.Advanced,
            "backup_restore_settings" to SettingsCategory.Backup
        )
        expected.forEach { (legacy, category) ->
            assertEquals(legacy, category, SettingsCategory.fromLegacy(legacy))
            assertEquals(category, SettingsCategory.fromRoute(category.route))
        }
        assertNull(SettingsCategory.fromLegacy("plugin_settings"))
        assertNull(SettingsCategory.fromRoute("unknown"))
        assertNull(SettingsCategory.fromRoute(null))
        assertEquals(SettingsCategory.General, SettingsCategory.fromRoute("appearance"))
        assertEquals(SettingsCategory.Advanced, SettingsCategory.fromRoute("history"))
        assertEquals(SettingsCategory.Advanced, SettingsCategory.fromRoute("data"))
    }

    @Test
    fun `search matches localized English Polish and German labels without case or accent sensitivity`() {
        val queries = mapOf(
            "values" to "AVERAGE recent",
            "values-pl" to "SREDNI prad",
            "values-de" to "DURCHSCHNITTSSTROM verfugbar"
        )
        for ((directory, query) in queries) {
            val strings = translatedStrings(directory)
            val descriptor = SettingDescriptor(
                "prefer_average_battery_current",
                SettingsCategory.Current,
                strings.getValue("pref_prefer_average_battery_current"),
                strings.getValue("pref_prefer_average_battery_current_summary"),
                strings.getValue("pref_cat_battery_current_main"),
                listOf(strings.getValue("tab_current_info"))
            )
            assertTrue("$directory: $query", descriptor.matches(query))
            assertFalse(directory, descriptor.matches("missingunrelatedword"))
        }
    }

    @Test
    fun `search combines terms from the stable key group description and old category alias`() {
        val descriptor = SettingDescriptor(
            "use_privileged_access",
            SettingsCategory.Advanced,
            "Privileged access",
            "Read additional battery information",
            "Data and permissions",
            listOf("Advanced settings")
        )
        assertTrue(descriptor.matches("USE_PRIVILEGED_ACCESS advanced battery"))
        assertTrue(descriptor.matches("permissions\tsettings\nread"))
        assertTrue(descriptor.matches("   "))
        assertFalse(descriptor.matches("advanced charging"))
    }

    @Test
    fun `search keeps right to left text and matches terms across fields`() {
        assertTrue(
            matchesSettingQuery(
                "البطارية الصلاحيات", listOf("حالة البطارية", "البيانات والصلاحيات")
            )
        )
        assertFalse(
            matchesSettingQuery(
                "البطارية مفقود", listOf("حالة البطارية", "البيانات والصلاحيات")
            )
        )
    }

    @Test
    fun `every diagnostics configuration and action resolves to its existing owner screen`() {
        val monitor = setOf(
            "background_monitoring",
            "debug_logging",
            "diagnostics_battery_optimization",
            "diagnostics_vendor_settings",
            "diagnostics_dont_kill_my_app",
            "diagnostics_notifications",
            "diagnostics_service",
            "diagnostics_database",
            "diagnostics_root",
            "diagnostics_shizuku",
            "diagnostics_export",
            "diagnostics_clear",
            "monitor_start",
            "monitor_stop"
        )
        val charging = setOf(
            "charging_diagnostics_capture",
            "charging_diagnostics_report",
            "charging_diagnostics_clear"
        )
        assertEquals(monitor + charging, SettingCatalog.diagnosticRoutes.keys)
        monitor.forEach { assertEquals(it, "monitor", SettingCatalog.diagnosticRoutes[it]) }
        charging.forEach { assertEquals(it, "charging-tools", SettingCatalog.diagnosticRoutes[it]) }
        SettingCatalog.diagnosticRoutes.keys.forEach {
            assertEquals(it, SettingsCategory.Advanced, SettingCatalog.routes[it])
        }
    }

    @Test
    fun `diagnostics search uses translated titles and opens the existing control without a preference copy`() {
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        val queries = mapOf(
            "values" to "COLLECT debug",
            "values-pl" to "ZBIERAJ logi",
            "values-de" to "DEBUG protokolle"
        )
        for ((directory, query) in queries) {
            val strings = translatedStrings("values") + translatedStrings(directory)
            val entries = SettingCatalog.diagnosticEntries { strings.getValue(names.getValue(it)) }
            val debug = entries.single { it.key == "debug_logging" }
            assertTrue("$directory: $query", debug.matches(query))
            assertEquals("monitor", debug.diagnosticDetail)
            assertNull(debug.preference)
            assertEquals(
                "charging-tools",
                entries.single { it.key == "charging_diagnostics_capture" }.diagnosticDetail
            )
        }
    }

    @Test
    fun `the former diagnostics live updates key finds the current system settings control`() {
        val aliases = SettingCatalog.aliasesFor("live_update_system_settings")
        assertEquals(listOf("diagnostics_live_updates"), aliases)
        val descriptor = SettingDescriptor(
            "live_update_system_settings",
            SettingsCategory.Notification,
            "Live Updates",
            "System settings",
            "Notification",
            aliases
        )
        assertTrue(descriptor.matches("diagnostics_live_updates"))
        assertTrue(SettingCatalog.aliasesFor("debug_logging").isEmpty())
    }

    @Test
    fun `custom charging target appears only in custom mode without removing its saved setting route`() {
        assertTrue(SettingCatalog.isVisible("custom_charging_target", 36, "custom"))
        for (mode in listOf(null, "automatic", "unknown")) {
            assertFalse(mode, SettingCatalog.isVisible("custom_charging_target", 36, mode))
            assertTrue(SettingCatalog.isVisible("charging_target_mode", 36, mode))
            assertTrue(SettingCatalog.isVisible("discharging_target", 36, mode))
        }
        assertEquals(SettingsCategory.Time, SettingCatalog.routes["custom_charging_target"])
    }

    @Test
    fun `all status bar chip controls are absent from rows and search before Android sixteen`() {
        val controls = setOf(
            "live_update_system_settings",
            "live_update_display",
            "live_update_keep_main_notification",
            "expanded_live_update_details",
            "chip_content",
            "chip_switching_interval",
            "chip_indicate_charging"
        )
        for (sdk in listOf(26, 33, 35, 36, 37)) {
            val visible = controls.filter { SettingCatalog.isVisible(it, sdk, "automatic") }.toSet()
            assertEquals("SDK $sdk", if (sdk >= 36) controls else emptySet<String>(), visible)
            assertTrue(SettingCatalog.isVisible("icon_content", sdk, "automatic"))
            assertTrue(SettingCatalog.isVisible("expanded_notification_details", sdk, "automatic"))
        }
    }

    private fun preferenceKeys(): Set<String> = listOf(
        "other_pref_screen.xml",
        "current_state_pref_screen.xml",
        "time_estimates_pref_screen.xml",
        "notification_pref_screen.xml",
        "advanced_pref_screen.xml",
        "backup_restore_pref_screen.xml"
    ).flatMap { filename ->
        val nodes = parse(File(resourcesDirectory(), "xml/$filename")).getElementsByTagName("*")
        (0 until nodes.length).mapNotNull { index ->
            val element = nodes.item(index) as Element
            if (element.tagName.endsWith("PreferenceCategory") || element.tagName.endsWith("PreferenceScreen")) null
            else element.getAttributeNS("http://schemas.android.com/apk/res-auto", "key").ifBlank {
                element.getAttributeNS(
                    "http://schemas.android.com/apk/res/android", "key"
                )
            }.takeIf(String::isNotBlank)
        }
    }.toSet()

    private fun translatedStrings(directory: String): Map<String, String> {
        val files = requireNotNull(File(resourcesDirectory(), directory).listFiles())
        return files.filter { it.extension == "xml" }.sortedBy(File::getName).flatMap { file ->
            val nodes = parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { index ->
                val element = nodes.item(index) as Element
                element.getAttribute("name") to element.textContent
            }
        }.toMap()
    }

    private fun resourcesDirectory(): File =
        listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }
            ?: error("Cannot find the app resource source directory")

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(file)
}
