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

class VitalSignsOrderTest {
    @Test
    fun `serialized order round trips`() {
        val order = listOf(
            SettingsContract.VITAL_SIGN_CHARGE,
            SettingsContract.VITAL_SIGN_HEALTH,
            SettingsContract.VITAL_SIGN_CURRENT,
            SettingsContract.VITAL_SIGN_TEMPERATURE,
            SettingsContract.VITAL_SIGN_STATUS_DURATION,
            SettingsContract.VITAL_SIGN_VOLTAGE
        )

        assertEquals(order, VitalSignsOrder.parse(VitalSignsOrder.serialize(order)))
    }

    @Test
    fun `invalid duplicate and missing values are normalized`() {
        assertEquals(
            listOf(
                SettingsContract.VITAL_SIGN_CHARGE,
                SettingsContract.VITAL_SIGN_HEALTH,
                SettingsContract.VITAL_SIGN_TEMPERATURE,
                SettingsContract.VITAL_SIGN_VOLTAGE,
                SettingsContract.VITAL_SIGN_CURRENT,
                SettingsContract.VITAL_SIGN_STATUS_DURATION
            ), VitalSignsOrder.parse("unknown,charge,charge,health")
        )
    }

    @Test
    fun `missing stored order uses the default order`() {
        assertEquals(SettingsContract.ALL_VITAL_SIGNS_CONTENT, VitalSignsOrder.parse(null))
    }
}
