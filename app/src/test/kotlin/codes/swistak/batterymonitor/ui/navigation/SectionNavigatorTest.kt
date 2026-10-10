/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionNavigatorTest {
    @Test
    fun `the release registry has six stable destinations and excludes the widgets gallery`() {
        assertEquals(
            listOf("current", "history", "alarms", "diagnostics", "settings", "help"),
            SectionRegistry.destinations.map { it.owner.route })
        assertEquals(SectionOwner.CURRENT, SectionRegistry.owner("widgets"))
        assertEquals(SectionOwner.CURRENT, SectionRegistry.owner("analysis"))
        assertEquals(SectionOwner.CURRENT, SectionRegistry.owner("unknown"))
    }

    @Test
    fun `reselecting a section and visiting another keeps its tab range filters and scroll`() {
        val navigator = SectionNavigator()
        val history = SectionState(
            selectedTab = "logs",
            rangeStartMillis = 100,
            rangeEndMillis = 200,
            filters = setOf("charging"),
            scrollIndex = 12,
            scrollOffset = 8
        )
        navigator.update(SectionOwner.HISTORY, history)
        navigator.select(SectionOwner.HISTORY)
        navigator.select(SectionOwner.ALARMS)
        navigator.select(SectionOwner.HISTORY)
        navigator.select(SectionOwner.HISTORY)

        assertEquals(history, navigator.state(SectionOwner.HISTORY))
        assertEquals(SectionOwner.HISTORY, navigator.selected)
    }

    @Test
    fun `back from a menu section returns to current without accumulating a history stack`() {
        val navigator = SectionNavigator()
        navigator.select(SectionOwner.HISTORY)
        navigator.select(SectionOwner.ALARMS)
        assertTrue(navigator.back())
        assertEquals(SectionOwner.CURRENT, navigator.selected)
        assertFalse(navigator.back())
    }

    @Test
    fun `back from a contextual detail restores its origin section`() {
        val navigator = SectionNavigator()
        navigator.select(SectionOwner.HISTORY)
        navigator.openDetail(SectionOwner.DIAGNOSTICS, "measurement", SectionOwner.HISTORY)
        assertTrue(navigator.back())
        assertEquals(SectionOwner.HISTORY, navigator.selected)
        assertEquals(null, navigator.detail)
    }

    @Test
    fun `back from settings help preserves the category before returning to the settings root`() {
        val navigator = SectionNavigator()
        navigator.select(SectionOwner.SETTINGS)
        navigator.openDetail(SectionOwner.SETTINGS, "history")
        navigator.openDetail(SectionOwner.HELP, "history")

        assertTrue(navigator.back())
        assertEquals(SectionOwner.SETTINGS, navigator.selected)
        assertEquals("history", navigator.detail)
        assertTrue(navigator.back())
        assertEquals(SectionOwner.SETTINGS, navigator.selected)
        assertEquals(null, navigator.detail)
        assertTrue(navigator.back())
        assertEquals(SectionOwner.CURRENT, navigator.selected)
        assertFalse(navigator.back())
    }

    @Test
    fun `leaving contextual help through the menu clears its nested return path`() {
        val navigator = SectionNavigator()
        navigator.select(SectionOwner.SETTINGS)
        navigator.openDetail(SectionOwner.SETTINGS, "notification")
        navigator.openDetail(SectionOwner.HELP, "notification")
        navigator.select(SectionOwner.HISTORY)

        assertTrue(navigator.back())
        assertEquals(SectionOwner.CURRENT, navigator.selected)
        assertEquals(null, navigator.detail)
        assertFalse(navigator.back())
    }

    @Test
    fun `a diagnostics search destination returns to the originating settings category`() {
        val navigator = SectionNavigator()
        navigator.select(SectionOwner.SETTINGS)
        navigator.openDetail(SectionOwner.SETTINGS, "settings:data")
        navigator.openDetail(SectionOwner.DIAGNOSTICS, "monitor:debug_logging")

        assertEquals("monitor:debug_logging", navigator.detail)
        assertTrue(navigator.back())
        assertEquals(SectionOwner.SETTINGS, navigator.selected)
        assertEquals("settings:data", navigator.detail)
        assertTrue(navigator.back())
        assertEquals(SectionOwner.SETTINGS, navigator.selected)
        assertEquals(null, navigator.detail)
    }
}
