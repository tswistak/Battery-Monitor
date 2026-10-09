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
package codes.swistak.batterymonitor.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale

class BatteryCurrentTest {
    @Test
    fun `battery power uses volts and amps preserves direction and requires both readings`() {
        assertEquals(4.8, BatteryCurrent.powerWatts(4000, 1200.0)!!, 0.000001)
        assertEquals(-0.9605, BatteryCurrent.powerWatts(4000, -240.125)!!, 0.000001)
        assertEquals(0.0, BatteryCurrent.powerWatts(4000, 0.0)!!, 0.0)
        assertEquals("0", BatteryCurrent.formatMilliAmps(-0.0, Locale.US))
        assertEquals(
            -4.8,
            BatteryCurrent.powerWatts(4000, BatteryCurrent.scaleMicroAmps(1200000, -1))!!,
            0.000001
        )
        assertNull(BatteryCurrent.powerWatts(null, 1200.0))
        assertNull(BatteryCurrent.powerWatts(4000, null))
        for (voltage in listOf(0, 499, 20001)) assertNull(
            BatteryCurrent.powerWatts(
                voltage, 1200.0
            )
        )
        for (current in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(BatteryCurrent.powerWatts(4000, current))
        }
    }

    @Test
    fun `preserves microamp precision when converting to milliamps`() {
        BatteryCurrent.setMultiplier(1)

        assertEquals(420.001, BatteryCurrent.scaleMicroAmps(420001), 0.0)
        assertEquals(-0.001, BatteryCurrent.scaleMicroAmps(-1), 0.0)
    }

    @Test
    fun `current validation preserves zero and signed readings and rejects unsupported sentinels`() {
        assertTrue(BatteryCurrent.isValidMicroAmps(0))
        assertTrue(BatteryCurrent.isValidMicroAmps(-293000))
        org.junit.Assert.assertFalse(BatteryCurrent.isValidMicroAmps(Int.MIN_VALUE.toLong()))
        org.junit.Assert.assertFalse(BatteryCurrent.isValidMicroAmps(Long.MIN_VALUE))
    }

    @Test
    fun `unsupported average files are skipped without substituting instantaneous current`() {
        withSysfsRoot { root ->
            createSupply(
                root,
                "battery",
                "Battery",
                currentNow = "-293000",
                currentAverage = Int.MIN_VALUE.toString()
            )
            assertNull(BatteryCurrent.findCurrentFile(root, average = true))
            createSupply(root, "vendor-fuelgauge", "Unknown", currentAverage = "-500000")
            assertEquals(
                "vendor-fuelgauge/current_avg",
                BatteryCurrent.findCurrentFile(root, average = true)?.relativeTo(root)?.path
            )
        }
    }

    @Test
    fun `formats at most six digits with up to three decimal places`() {
        assertEquals("0.123", BatteryCurrent.formatMilliAmps(0.1234, Locale.US))
        assertEquals("123.457", BatteryCurrent.formatMilliAmps(123.4567, Locale.US))
        assertEquals("1234.57", BatteryCurrent.formatMilliAmps(1234.567, Locale.US))
        assertEquals("12345.7", BatteryCurrent.formatMilliAmps(12345.67, Locale.US))
        assertEquals("123456", BatteryCurrent.formatMilliAmps(123456.4, Locale.US))
    }

    @Test
    fun `current formatting omits trailing zeroes and uses locale decimal separator`() {
        assertEquals("420", BatteryCurrent.formatMilliAmps(420.0, Locale.US))
        assertEquals("-420.001", BatteryCurrent.formatMilliAmps(-420.001, Locale.US))
        assertEquals("420,001", BatteryCurrent.formatMilliAmps(420.001, Locale.GERMANY))
    }

    @Test
    fun `discovers an unknown battery supply and ignores USB current`() {
        withSysfsRoot { root ->
            createSupply(root, "usb-main", "USB", currentNow = "3000000")
            createSupply(root, "vendor-pack", "Battery", currentNow = "-420000")

            assertEquals(
                "vendor-pack/current_now",
                BatteryCurrent.findCurrentFile(root, average = false)?.relativeTo(root)?.path
            )
        }
    }

    @Test
    fun `prefers the canonical battery supply over another battery node`() {
        withSysfsRoot { root ->
            createSupply(root, "z-fuel-gauge", "Battery", currentNow = "-410000")
            createSupply(root, "battery", "Battery", currentNow = "-420000")

            assertEquals(
                "battery/current_now",
                BatteryCurrent.findCurrentFile(root, average = false)?.relativeTo(root)?.path
            )
        }
    }

    @Test
    fun `selects only the requested current measurement`() {
        withSysfsRoot { root ->
            createSupply(
                root, "battery", "Battery", currentNow = "-420000", currentAverage = "-390000"
            )

            assertEquals(
                "current_now", BatteryCurrent.findCurrentFile(root, average = false)?.name
            )
            assertEquals(
                "current_avg", BatteryCurrent.findCurrentFile(root, average = true)?.name
            )
        }
    }

    @Test
    fun `does not use charger current when no battery supply exists`() {
        withSysfsRoot { root ->
            createSupply(root, "wireless", "Wireless", currentNow = "1500000")
            createSupply(root, "main", "Mains", currentNow = "2000000")

            assertNull(BatteryCurrent.findCurrentFile(root, average = false))
        }
    }

    @Test
    fun `skips an unreadable value and uses the next battery candidate`() {
        withSysfsRoot { root ->
            createSupply(root, "battery", "Battery", currentNow = "not-a-number")
            createSupply(root, "vendor-pack", "Battery", currentNow = "-420000")

            assertEquals(
                "vendor-pack/current_now",
                BatteryCurrent.findCurrentFile(root, average = false)?.relativeTo(root)?.path
            )
        }
    }

    @Test
    fun `accepts a named BMS node with a fuel gauge type`() {
        withSysfsRoot { root ->
            createSupply(root, "bms", "BMS", currentNow = "-420000")

            assertEquals(
                "bms/current_now",
                BatteryCurrent.findCurrentFile(root, average = false)?.relativeTo(root)?.path
            )
        }
    }

    @Test
    fun `uses uevent type when the type file is unavailable`() {
        withSysfsRoot { root ->
            val directory = File(root, "oem-pack").apply { check(mkdirs()) }
            File(directory, "uevent").writeText(
                "POWER_SUPPLY_NAME=oem-pack\nPOWER_SUPPLY_TYPE=Battery\n"
            )
            File(directory, "current_now").writeText("-420000")

            assertEquals(
                "oem-pack/current_now",
                BatteryCurrent.findCurrentFile(root, average = false)?.relativeTo(root)?.path
            )
        }
    }

    private fun withSysfsRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("battery-current-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createSupply(
        root: File,
        name: String,
        type: String,
        currentNow: String? = null,
        currentAverage: String? = null
    ) {
        val directory = File(root, name).apply { check(mkdirs()) }
        File(directory, "type").writeText(type)
        currentNow?.let { File(directory, "current_now").writeText(it) }
        currentAverage?.let { File(directory, "current_avg").writeText(it) }
    }
}
