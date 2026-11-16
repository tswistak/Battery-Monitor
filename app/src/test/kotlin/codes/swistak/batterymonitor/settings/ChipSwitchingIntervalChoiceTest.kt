package codes.swistak.batterymonitor.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ChipSwitchingIntervalChoiceTest {
    private val entries = listOf("2", "5", "10", "15", "30", "custom")

    @Test
    fun `non-preset seconds reopen on Custom at its actual position`() {
        assertEquals(5, chipSwitchingIntervalChoiceIndex("7", entries))
        assertEquals(5, chipSwitchingIntervalChoiceIndex("3600", entries))
        val reordered = listOf("custom", "10", "5")
        assertEquals(0, chipSwitchingIntervalChoiceIndex("7", reordered))
    }

    @Test
    fun `preset seconds select their numeric entry`() {
        assertEquals(2, chipSwitchingIntervalChoiceIndex("10", entries))
        assertEquals(1, chipSwitchingIntervalChoiceIndex("5", entries))
    }

    @Test
    fun `legacy values use the existing effective interval normalization`() {
        assertEquals(1, chipSwitchingIntervalChoiceIndex(null, entries))
        assertEquals(1, chipSwitchingIntervalChoiceIndex("invalid", entries))
        assertEquals(1, chipSwitchingIntervalChoiceIndex("custom", entries))
        assertEquals(5, chipSwitchingIntervalChoiceIndex("0", entries))
        assertEquals(5, chipSwitchingIntervalChoiceIndex("3601", entries))
    }
}
