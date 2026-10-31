/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class HelpLocalizationTest {
    @Test
    fun `every locale preserves resource types and formatting arguments`() {
        val master = catalog(File(resourcesDirectory(), "values/strings.xml"))
        for (file in catalogs()) {
            val translated = catalog(file)
            assertEquals(file.path, master.keys, translated.keys)
            for ((name, source) in master) {
                val actual = translated.getValue(name)
                val context = "${file.path}: $name"
                assertEquals(context, source.tagName, actual.tagName)
                if (source.tagName == "plurals") {
                    val sourceItems = pluralItems(source)
                    val actualItems = pluralItems(actual)
                    assertTrue("$context needs other", "other" in actualItems)
                    for ((quantity, text) in actualItems) {
                        val expected =
                            arguments(sourceItems[quantity] ?: sourceItems.getValue("other"))
                        val found = arguments(text)
                        val withoutCount = expected - setOf("%d", $$"%1$d")
                        assertTrue(
                            "$context/$quantity: $found instead of $expected",
                            found == expected || (quantity in setOf(
                                "zero", "one", "two"
                            ) && found == withoutCount)
                        )
                    }
                    assertEquals(
                        context, arguments(source.textContent), arguments(actual.textContent)
                    )
                } else {
                    assertEquals(
                        context, arguments(source.textContent), arguments(actual.textContent)
                    )
                }
            }
        }
    }

    private fun arguments(text: String): Set<String> =
        Regex("""%(?:\d+\$)?[a-zA-Z]""").findAll(text).map { it.value }.toSet()

    private fun pluralItems(element: Element): Map<String, String> {
        val items = element.getElementsByTagName("item")
        return (0 until items.length).associate { index ->
            val item = items.item(index) as Element
            item.getAttribute("quantity") to item.textContent
        }
    }

    private fun catalog(file: File): Map<String, Element> {
        val nodes = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(file).documentElement.childNodes
        val elements = (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
            .filter { it.hasAttribute("name") && it.getAttribute("translatable") != "false" }
        val result = elements.associateBy { it.getAttribute("name") }
        assertEquals("Duplicate resource in ${file.path}", elements.size, result.size)
        return result
    }

    private fun catalogs(): List<File> = requireNotNull(resourcesDirectory().listFiles()).filter {
        it.isDirectory && (it.name == "values" || it.name.startsWith("values-"))
    }.map { File(it, "strings.xml") }.filter { it.isFile }.sortedBy(File::getPath)

    private fun resourcesDirectory(): File =
        listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }
            ?: error("Cannot find the app resource source directory")
}
