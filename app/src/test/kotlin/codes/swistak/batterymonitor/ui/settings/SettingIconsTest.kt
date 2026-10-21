/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.settings

import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.ui.help.helpSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SettingIconsTest {
    @Test
    fun `every searchable setting and nested export control has its own semantic icon`() {
        val keys = SettingCatalog.routes.keys + setOf(
            SettingsContract.KEY_AUTO_LOG_EXPORT_FREQUENCY,
            SettingsContract.KEY_AUTO_LOG_EXPORT_MODE,
            SettingsContract.KEY_AUTO_LOG_EXPORT_FORMAT,
            SettingsContract.KEY_AUTO_LOG_EXPORT_DIRECTORY
        )
        for (key in keys) assertNotEquals(key, R.drawable.ui_settings, settingIcon(key))
    }

    @Test
    fun `settings help uses the same icons as corresponding settings controls`() {
        val resources = resourcesDirectory()
        for (file in requireNotNull(
            File(
                resources, "xml"
            ).listFiles()
        ).filter { it.name.endsWith("pref_screen.xml") }) {
            for (element in elements(file)) {
                val key = attribute(element, "key")
                val title = attribute(element, "title").removePrefix("@string/")
                if (key !in SettingCatalog.routes || title.isBlank()) continue
                assertEquals(
                    "$key in ${file.name}",
                    settingIcon(key),
                    helpHeadingIcon(R.string::class.java.getField(title).getInt(null))
                )
            }
        }
        assertEquals(
            settingIcon(SettingsContract.KEY_COLOR_SOURCE),
            helpHeadingIcon(R.string.settings_color_palette)
        )
        assertEquals(
            settingIcon(SettingsContract.KEY_BRIGHTNESS),
            helpHeadingIcon(R.string.settings_brightness)
        )
    }

    @Test
    fun `every help topic and setting subheading has an explicit contextual icon`() {
        val topics = listOf(
            null,
            "main",
            SettingsContract.KEY_NOTIFICATION_SETTINGS,
            SettingsContract.KEY_CURRENT_STATE_SETTINGS,
            SettingsContract.KEY_OTHER_SETTINGS,
            SettingsContract.KEY_TIME_ESTIMATES_SETTINGS,
            SettingsContract.KEY_ADVANCED_SETTINGS,
            SettingsContract.KEY_BACKUP_RESTORE_SETTINGS,
            SettingsContract.KEY_DIAGNOSTICS_SETTINGS,
            SettingsContract.KEY_ADVANCED_INFO_HELP,
            SettingsContract.KEY_ALARMS_SETTINGS,
            SettingsContract.KEY_ALARM_EDIT_SETTINGS
        )
        for (topic in topics) {
            val sections = helpSections(
                topic, 37, SettingsContract.CHARGING_TARGET_MODE_CUSTOM
            )
            assertTrue("Help topic $topic must contain content", sections.isNotEmpty())
            for ((titleResource, paragraphs) in sections) {
                assertTrue(
                    "Help topic $topic must contain paragraphs", paragraphs.isNotEmpty()
                )
                val headings = listOfNotNull(titleResource) + paragraphs.filter { it.subheading }
                    .map { it.textResource }
                for (title in headings) {
                    assertNotNull("Heading $title in $topic", helpHeadingIcon(title))
                }
            }
        }
    }

    private fun attribute(element: Element, name: String): String =
        element.getAttributeNS("http://schemas.android.com/apk/res/android", name).ifBlank {
            element.getAttributeNS("http://schemas.android.com/apk/res-auto", name)
        }

    private fun elements(file: File): List<Element> {
        val nodes = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(file).getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun resourcesDirectory(): File =
        listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }
            ?: error("Cannot find the app resource source directory")
}
