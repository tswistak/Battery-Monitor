/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundSettingsNavigatorTest {
    @Test
    fun `manufacturer and brand aliases map to supported vendor families`() {
        assertEquals(VendorFamily.XIAOMI, family("Xiaomi", "POCO"))
        assertEquals(VendorFamily.XIAOMI, family("unknown", "Redmi"))
        assertEquals(VendorFamily.HONOR, family("HONOR", "HONOR"))
        assertEquals(VendorFamily.HUAWEI, family("Huawei", "Huawei"))
        assertEquals(VendorFamily.OPPO, family("OPPO", "OPPO"))
        assertEquals(VendorFamily.ONEPLUS, family("OnePlus", "OnePlus"))
        assertEquals(VendorFamily.REALME, family("realme", "realme"))
        assertEquals(VendorFamily.VIVO, family("vivo", "iQOO"))
        assertEquals(VendorFamily.SAMSUNG, family("samsung", "samsung"))
        assertEquals(VendorFamily.ASUS, family("asus", "ROG"))
        assertEquals(VendorFamily.MEIZU, family("Meizu", "Flyme"))
        assertEquals(VendorFamily.TRANSSION, family("TECNO MOBILE LIMITED", "TECNO"))
        assertEquals(VendorFamily.TRANSSION, family("INFINIX MOBILITY LIMITED", "Infinix"))
        assertEquals(VendorFamily.TRANSSION, family("itel", "itel"))
        assertEquals(VendorFamily.NUBIA, family("ZTE", "nubia"))
        assertEquals(VendorFamily.ZTE, family("ZTE", "ZTE"))
        assertEquals(VendorFamily.LENOVO, family("Lenovo", "ZUI"))
        assertEquals(VendorFamily.NOKIA, family("HMD Global", "Nokia"))
        assertEquals(VendorFamily.LETV, family("LeEco", "LeTV"))
    }

    @Test
    fun `stock and unknown vendors do not expose manufacturer settings`() {
        assertNull(family("Google", "google"))
        assertNull(family("Motorola", "motorola"))
    }

    private fun family(manufacturer: String, brand: String): VendorFamily? =
        BackgroundSettingsNavigator.vendorFamily(manufacturer, brand)
}
