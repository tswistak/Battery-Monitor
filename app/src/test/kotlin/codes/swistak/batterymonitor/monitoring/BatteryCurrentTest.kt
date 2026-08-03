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

import codes.swistak.batterymonitor.common.CommandExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale

class BatteryCurrentTest {
    @Test
    fun `privileged current prefers root and does not call Shizuku`() {
        var shizukuCalls = 0
        val executor = object : CommandExecutor {
            override fun run(command: String): String? = "-420001"
        }

        val result = BatteryCurrent.readPrivilegedMicroAmps("current_now", executor) {
            shizukuCalls++
            -390000L
        }

        assertEquals(-420001L, result)
        assertEquals(0, shizukuCalls)
    }

    @Test
    fun `privileged current falls back to Shizuku after both root commands fail`() {
        val commands = mutableListOf<String>()
        val executor = object : CommandExecutor {
            override fun run(command: String): String? {
                commands.add(command)
                return null
            }
        }

        val result = BatteryCurrent.readPrivilegedMicroAmps("current_average", executor) {
            -390001L
        }

        assertEquals(-390001L, result)
        assertEquals(
            listOf(
                "cmd battery get -f current_average 2>/dev/null",
                "cmd battery get current_average 2>/dev/null"
            ), commands
        )
    }

    @Test
    fun `preserves microamp precision when converting to milliamps`() {
        BatteryCurrent.setMultiplier(1)

        assertEquals(420.001, BatteryCurrent.scaleMicroAmps(420001), 0.0)
        assertEquals(-0.001, BatteryCurrent.scaleMicroAmps(-1), 0.0)
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
