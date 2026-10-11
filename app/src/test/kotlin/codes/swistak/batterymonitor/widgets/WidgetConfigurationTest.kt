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

import codes.swistak.batterymonitor.ui.theme.Brightness
import codes.swistak.batterymonitor.ui.theme.ColorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetConfigurationTest {
    @Test
    fun `family defaults use their canonical destination and inherit application appearance and units`() {
        val actions = mapOf(
            WidgetFamily.STATUS to WidgetTapAction.CURRENT_STATE,
            WidgetFamily.MEASUREMENTS to WidgetTapAction.MEASUREMENT,
            WidgetFamily.TREND to WidgetTapAction.HISTORY
        )
        for ((family, action) in actions) {
            val configuration = WidgetConfiguration.defaults(family)
            assertTrue(configuration.isValid())
            assertEquals(action, configuration.tapAction)
            assertNull(configuration.brightness)
            assertNull(configuration.colorSource)
            assertNull(configuration.temperatureUnit)
            assertEquals(100, configuration.backgroundOpacityPercent)
            assertEquals(24, configuration.trendHours)
            assertTrue(configuration.showYAxisLabels)
        }
    }

    @Test
    fun `configuration serialization preserves explicit overrides ordered metrics and tap destination`() {
        val configuration = WidgetConfiguration(
            brightness = Brightness.TrueBlack,
            colorSource = ColorSource.Dynamic,
            temperatureUnit = WidgetConfiguration.TEMPERATURE_FAHRENHEIT,
            backgroundOpacityPercent = 0,
            metrics = listOf(WidgetMetric.CURRENT, WidgetMetric.TEMPERATURE),
            statusExtra = WidgetStatusExtra.REMAINING_CHARGE,
            variant = WidgetVariant.COMPACT,
            tapAction = WidgetTapAction.HISTORY,
            tapMetric = WidgetMetric.VOLTAGE,
            trendHours = 168,
            showYAxisLabels = false
        )
        assertEquals(configuration, WidgetConfiguration.decode(configuration.encode()))
        for (family in WidgetFamily.entries) {
            val defaults = WidgetConfiguration.defaults(family)
            assertEquals(defaults, WidgetConfiguration.decode(defaults.encode()))
        }
    }

    @Test
    fun `existing configuration records keep every override and enable Y axis labels without migration`() {
        val legacy =
            "1|Dark|BatteryBlue|fahrenheit|45|VOLTAGE,CURRENT|NONE|COMPACT|HISTORY|VOLTAGE|72"
        assertEquals(
            WidgetConfiguration(
                brightness = Brightness.Dark,
                colorSource = ColorSource.BatteryBlue,
                temperatureUnit = WidgetConfiguration.TEMPERATURE_FAHRENHEIT,
                backgroundOpacityPercent = 45,
                metrics = listOf(WidgetMetric.VOLTAGE, WidgetMetric.CURRENT),
                statusExtra = WidgetStatusExtra.NONE,
                variant = WidgetVariant.COMPACT,
                tapAction = WidgetTapAction.HISTORY,
                tapMetric = WidgetMetric.VOLTAGE,
                trendHours = 72,
                showYAxisLabels = true
            ), WidgetConfiguration.decode(legacy)
        )
        assertNull(WidgetConfiguration.decode("$legacy|false"))
    }

    @Test
    fun `configuration rejects duplicate missing and oversized measurement selections`() {
        val defaults = WidgetConfiguration()
        assertFalse(defaults.copy(metrics = emptyList()).isValid())
        assertFalse(defaults.copy(metrics = listOf(WidgetMetric.CURRENT)).isValid())
        assertFalse(
            defaults.copy(metrics = listOf(WidgetMetric.CURRENT, WidgetMetric.CURRENT)).isValid()
        )
        assertFalse(defaults.copy(metrics = WidgetMetric.entries + WidgetMetric.CURRENT).isValid())
        assertTrue(
            defaults.copy(metrics = listOf(WidgetMetric.CURRENT, WidgetMetric.VOLTAGE)).isValid()
        )
    }

    @Test
    fun `configuration accepts only supported ranges units and background opacity boundaries`() {
        val defaults = WidgetConfiguration()
        for (opacity in listOf(
            -1, 101
        )) assertFalse(defaults.copy(backgroundOpacityPercent = opacity).isValid())
        for (opacity in listOf(
            0, 1, 99, 100
        )) assertTrue(defaults.copy(backgroundOpacityPercent = opacity).isValid())
        for (hours in listOf(0, 1, 48, 169)) assertFalse(
            defaults.copy(trendHours = hours).isValid()
        )
        for (hours in listOf(24, 72, 168)) assertTrue(defaults.copy(trendHours = hours).isValid())
        assertFalse(defaults.copy(temperatureUnit = "kelvin").isValid())
        assertTrue(
            defaults.copy(temperatureUnit = WidgetConfiguration.TEMPERATURE_CELSIUS).isValid()
        )
    }

    @Test
    fun `malformed unknown future and invalid configuration records cannot become saved settings`() {
        val record = WidgetConfiguration().encode()
        for (invalid in listOf(
            "",
            record.substringBeforeLast('|'),
            "3" + record.drop(1),
            record.replace("|true", "|yes"),
            record.replace("|100|", "|101|"),
            record.replace("|100|", "|invalid|"),
            record.replace("TEMPERATURE,VOLTAGE,CURRENT,REMAINING_CHARGE", "UNKNOWN,VOLTAGE"),
            record.replace("|ADAPTIVE|", "|UNKNOWN|"),
            "$record|extra"
        )) assertNull(invalid, WidgetConfiguration.decode(invalid))
    }
}
