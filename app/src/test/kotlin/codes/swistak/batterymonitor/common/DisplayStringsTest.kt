/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.common

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class DisplayStringsTest {
    @Before
    fun setUpSymbols() {
        DisplayStrings.degreeSymbol = "°"
        DisplayStrings.fahrenheitSymbol = "F"
        DisplayStrings.celsiusSymbol = "C"
        DisplayStrings.voltSymbol = "V"
    }

    @Test
    fun `temperature uses locale decimal separator`() {
        assertEquals(
            "21.5°C", DisplayStrings.formatTemp(
                215, convertF = false, includeTenths = true, locale = Locale.US
            )
        )
        assertEquals(
            "21,5°C", DisplayStrings.formatTemp(
                215, convertF = false, includeTenths = true, locale = Locale.forLanguageTag("pl")
            )
        )
        assertEquals(
            "71,6°F", DisplayStrings.formatTemp(
                220, convertF = true, includeTenths = true, locale = Locale.forLanguageTag("pl")
            )
        )
    }

    @Test
    fun `temperature without tenths remains an integer`() {
        assertEquals(
            "22°C", DisplayStrings.formatTemp(
                215, convertF = false, includeTenths = false, locale = Locale.forLanguageTag("pl")
            )
        )
    }

    @Test
    fun `voltage uses locale decimal separator and preserves millivolt precision`() {
        assertEquals("4.217V", DisplayStrings.formatVoltage(4217, Locale.US))
        assertEquals("4,217V", DisplayStrings.formatVoltage(4217, Locale.forLanguageTag("pl")))
        assertEquals("4,0V", DisplayStrings.formatVoltage(4000, Locale.forLanguageTag("pl")))
    }

    @Test
    fun `12 hour time excludes seconds when not requested`() {
        assertTimeFormatting(
            is24Hour = false,
            includeSeconds = false,
            expectedSkeleton = "hm",
            expectedTime = "5:04 PM"
        )
    }

    @Test
    fun `12 hour time includes seconds when requested`() {
        assertTimeFormatting(
            is24Hour = false,
            includeSeconds = true,
            expectedSkeleton = "hms",
            expectedTime = "5:04:09 PM"
        )
    }

    @Test
    fun `24 hour time excludes seconds when not requested`() {
        assertTimeFormatting(
            is24Hour = true,
            includeSeconds = false,
            expectedSkeleton = "Hm",
            expectedTime = "17:04"
        )
    }

    @Test
    fun `24 hour time includes seconds when requested`() {
        assertTimeFormatting(
            is24Hour = true,
            includeSeconds = true,
            expectedSkeleton = "Hms",
            expectedTime = "17:04:09"
        )
    }

    private fun assertTimeFormatting(
        is24Hour: Boolean,
        includeSeconds: Boolean,
        expectedSkeleton: String,
        expectedTime: String
    ) {
        val date = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 1, 17, 4, 9)
            set(Calendar.MILLISECOND, 0)
        }.time
        var requestedSkeleton = ""

        val formatted = DisplayStrings.formatTime(
            date = date,
            locale = Locale.US,
            is24Hour = is24Hour,
            includeSeconds = includeSeconds
        ) { locale, skeleton ->
            assertEquals(Locale.US, locale)
            requestedSkeleton = skeleton
            when (skeleton) {
                "Hm" -> "HH:mm"
                "Hms" -> "HH:mm:ss"
                "hm" -> "h:mm a"
                "hms" -> "h:mm:ss a"
                else -> error("Unexpected time skeleton: $skeleton")
            }
        }

        assertEquals(expectedSkeleton, requestedSkeleton)
        assertEquals(expectedTime, formatted)
    }
}
