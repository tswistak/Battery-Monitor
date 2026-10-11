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
*/
package codes.swistak.batterymonitor.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LegacyWidgetContractTest {
    @Test
    fun `placed legacy widgets keep their original receiver names process and provider metadata`() {
        assertEquals(
            "codes.swistak.batterymonitor.widgets.BatteryInfoAppWidgetProvider",
            BatteryInfoAppWidgetProvider::class.java.name
        )
        assertEquals(
            "codes.swistak.batterymonitor.widgets.FullAppWidgetProvider",
            FullAppWidgetProvider::class.java.name
        )
        assertEquals(
            BatteryInfoAppWidgetProvider::class.java, FullAppWidgetProvider::class.java.superclass
        )

        val receivers = parse("AndroidManifest.xml").getElementsByTagName("receiver")
        for ((receiverName, metadata) in providers) {
            val receiver = (0 until receivers.length).map { receivers.item(it) as Element }
                .single { it.androidAttribute("name") == receiverName }
            assertEquals("true", receiver.androidAttribute("enabled"))
            assertEquals("true", receiver.androidAttribute("exported"))
            assertEquals("codes.swistak.batterymonitor.BIS", receiver.androidAttribute("process"))
            val data = receiver.getElementsByTagName("meta-data").item(0) as Element
            assertEquals("android.appwidget.provider", data.androidAttribute("name"))
            assertEquals("@xml/$metadata", data.androidAttribute("resource"))
            val actions = receiver.getElementsByTagName("action")
            assertTrue((0 until actions.length).any {
                (actions.item(it) as Element).androidAttribute("name") == "android.appwidget.action.APPWIDGET_UPDATE"
            })
        }
    }

    @Test
    fun `legacy provider metadata retains its layouts without requiring a new configuration or polling timer`() {
        for ((_, metadata) in providers) {
            val info = parse("res/xml/$metadata.xml").documentElement
            val layout = metadata.removeSuffix("_info")
            assertEquals("@layout/$layout", info.androidAttribute("initialLayout"))
            assertEquals("0", info.androidAttribute("updatePeriodMillis"))
            assertFalse(info.hasAttributeNS(androidNamespace, "configure"))

            val views = parse("res/layout/$layout.xml").getElementsByTagName("*")
            val viewIds = (0 until views.length).map {
                (views.item(it) as Element).androidAttribute("id")
            }.toSet()
            assertTrue(viewIds.containsAll(setOf("@+id/widget_layout", "@+id/level")))
        }
    }

    @Test
    fun `legacy picker labels and previews preserve the original provider size contracts`() {
        val receivers = parse("AndroidManifest.xml").getElementsByTagName("receiver")
        for ((receiverName, metadata) in providers) {
            val resourceName = metadata.removeSuffix("_app_widget_info") + "_widget"
            val receiver = (0 until receivers.length).map { receivers.item(it) as Element }
                .single { it.androidAttribute("name") == receiverName }
            assertEquals("@string/$resourceName", receiver.androidAttribute("label"))

            val info = parse("res/xml/$metadata.xml").documentElement
            assertEquals("@layout/${resourceName}_preview", info.androidAttribute("previewLayout"))
            assertEquals("@drawable/${resourceName}_preview", info.androidAttribute("previewImage"))
            parse("res/layout/${resourceName}_preview.xml")
            for (qualifier in listOf("drawable-nodpi", "drawable-night-nodpi")) {
                assertTrue(
                    File(
                        mainDirectory(), "res/$qualifier/${resourceName}_preview.png"
                    ).isFile
                )
            }

            val circle = resourceName == "circle_widget"
            val originalDimensions = mapOf(
                "minWidth" to if (circle) "40dp" else "250dp",
                "minHeight" to "40dp",
                "minResizeWidth" to if (circle) "40dp" else "180dp",
                "minResizeHeight" to "40dp"
            )
            originalDimensions.forEach { (attribute, value) ->
                assertEquals("$metadata $attribute", value, info.androidAttribute(attribute))
            }
            assertEquals(if (circle) "" else "horizontal", info.androidAttribute("resizeMode"))
        }
    }

    @Test
    fun `new widgets retain layout previews and supply PNG fallbacks for older launchers`() {
        for (family in listOf("status", "measurements", "trend")) {
            val preview = "widget_${family}_preview"
            val info = parse("res/xml/widget_${family}_info.xml").documentElement
            assertEquals("@layout/$preview", info.androidAttribute("previewLayout"))
            assertEquals("@drawable/$preview", info.androidAttribute("previewImage"))
            val png = File(mainDirectory(), "res/drawable-nodpi/$preview.png").readBytes()
            assertTrue(png.size > 24)
            assertTrue(
                png.take(8).toByteArray().contentEquals(
                    byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                )
            )
        }
    }

    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(File(mainDirectory(), path))

    private fun mainDirectory() =
        listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(androidNamespace, name)

    private companion object {
        const val androidNamespace = "http://schemas.android.com/apk/res/android"
        val providers = mapOf(
            ".widgets.BatteryInfoAppWidgetProvider" to "circle_app_widget_info",
            ".widgets.FullAppWidgetProvider" to "full_app_widget_info"
        )
    }
}
