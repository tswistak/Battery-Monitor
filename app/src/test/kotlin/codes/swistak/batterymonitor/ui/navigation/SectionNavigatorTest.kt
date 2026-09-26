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
}
