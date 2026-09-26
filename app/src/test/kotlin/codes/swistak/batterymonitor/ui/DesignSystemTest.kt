/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui

import codes.swistak.batterymonitor.ui.components.NumericInputError
import codes.swistak.batterymonitor.ui.components.NumericInputResult
import codes.swistak.batterymonitor.ui.components.parseLocalizedInt
import codes.swistak.batterymonitor.ui.theme.Brightness
import codes.swistak.batterymonitor.ui.theme.ColorSource
import codes.swistak.batterymonitor.ui.theme.isDark
import codes.swistak.batterymonitor.ui.theme.useDynamicColors
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DesignSystemTest {
    @Test
    fun `numeric input accepts localized whole numbers and rejects invalid range and step`() {
        assertEquals(NumericInputResult.Valid(80), parseLocalizedInt("80", Locale.US, 0, 100, 5))
        assertEquals(
            NumericInputResult.Valid(80),
            parseLocalizedInt("٨٠", Locale.forLanguageTag("ar"), 0, 100, 5)
        )
        assertEquals(
            NumericInputResult.Error(NumericInputError.Invalid),
            parseLocalizedInt("1,2", Locale.US, 0, 100, 1)
        )
        assertEquals(
            NumericInputResult.Error(NumericInputError.Invalid),
            parseLocalizedInt("1,5", Locale.GERMANY, 0, 100, 1)
        )
        assertEquals(
            NumericInputResult.Error(NumericInputError.Invalid),
            parseLocalizedInt("broken", Locale.US, 0, 100, 1)
        )
        assertEquals(
            NumericInputResult.Error(NumericInputError.Empty),
            parseLocalizedInt(" ", Locale.US, 0, 100, 1)
        )
        assertEquals(
            NumericInputResult.Error(NumericInputError.OutOfRange),
            parseLocalizedInt("101", Locale.US, 0, 100, 1)
        )
        assertEquals(
            NumericInputResult.Error(NumericInputError.WrongStep),
            parseLocalizedInt("82", Locale.US, 0, 100, 5)
        )
    }

    @Test
    fun `brightness selection follows system mode and dynamic color API availability`() {
        assertEquals(false, isDark(Brightness.System, false))
        assertEquals(true, isDark(Brightness.System, true))
        assertEquals(false, isDark(Brightness.Light, true))
        assertEquals(true, isDark(Brightness.Dark, false))
        assertEquals(true, isDark(Brightness.TrueBlack, false))
        assertEquals(false, useDynamicColors(ColorSource.Dynamic, 26))
        assertEquals(true, useDynamicColors(ColorSource.Dynamic, 31))
        assertEquals(false, useDynamicColors(ColorSource.BatteryBlue, 37))
    }
}
