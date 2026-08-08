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
package codes.swistak.batterymonitor.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ChipContentOrderTest {
    @Test
    fun `serialized order round trips`() {
        val order = listOf(
            SettingsContract.CHIP_CONTENT_CHARGE,
            SettingsContract.CHIP_CONTENT_CURRENT,
            SettingsContract.CHIP_CONTENT_PERCENTAGE,
            SettingsContract.CHIP_CONTENT_VOLTAGE,
            SettingsContract.CHIP_CONTENT_TEMPERATURE
        )

        assertEquals(order, ChipContentOrder.parse(ChipContentOrder.serialize(order)))
    }

    @Test
    fun `invalid duplicate and missing values are normalized`() {
        assertEquals(
            listOf(
                SettingsContract.CHIP_CONTENT_CHARGE,
                SettingsContract.CHIP_CONTENT_PERCENTAGE,
                SettingsContract.CHIP_CONTENT_TEMPERATURE,
                SettingsContract.CHIP_CONTENT_VOLTAGE,
                SettingsContract.CHIP_CONTENT_CURRENT
            ), ChipContentOrder.parse("unknown,charge,charge,percentage")
        )
    }

    @Test
    fun `missing stored order uses the default order`() {
        assertEquals(SettingsContract.ALL_CHIP_CONTENT, ChipContentOrder.parse(null))
    }
}
