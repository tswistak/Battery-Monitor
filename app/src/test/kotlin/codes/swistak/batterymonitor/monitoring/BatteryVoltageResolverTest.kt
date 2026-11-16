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

import codes.swistak.batterymonitor.common.DirectSysfsAccessor
import codes.swistak.batterymonitor.common.SysfsAccessor
import codes.swistak.batterymonitor.monitoring.batteryvoltage.BatteryVoltageReading
import codes.swistak.batterymonitor.monitoring.batteryvoltage.BatteryVoltageResolver
import codes.swistak.batterymonitor.monitoring.batteryvoltage.BatteryVoltageSource
import codes.swistak.batterymonitor.monitoring.batteryvoltage.BatteryVoltageValidator
import codes.swistak.batterymonitor.monitoring.batteryvoltage.discoverBatteryVoltage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BatteryVoltageResolverTest {
    @Test
    fun `broadcast millivolt validation covers plausible and implausible values`() {
        for (value in listOf(4000, 3500, 7600, 500, 20000)) {
            assertTrue(
                "expected $value to be plausible",
                BatteryVoltageValidator.isValidBroadcastMillivolts(
                    value
                )
            )
        }
        for (value in listOf(3, 4, 0, -1, 499, 20001, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertFalse(
                "expected $value to be implausible",
                BatteryVoltageValidator.isValidBroadcastMillivolts(
                    value
                )
            )
        }
    }

    @Test
    fun `sysfs normalization converts microvolt values to millivolts`() {
        assertEquals(3874, BatteryVoltageValidator.normalizeSysfsVoltage("3874000"))
        assertEquals(7600, BatteryVoltageValidator.normalizeSysfsVoltage("7600000"))
        assertEquals(20000, BatteryVoltageValidator.normalizeSysfsVoltage("20000000"))
    }

    @Test
    fun `sysfs normalization accepts millivolt values reported by vendor kernels`() {
        assertEquals(3874, BatteryVoltageValidator.normalizeSysfsVoltage("3874"))
        assertEquals(7600, BatteryVoltageValidator.normalizeSysfsVoltage(" 7600 "))
    }

    @Test
    fun `sysfs normalization rejects implausible values`() {
        for (raw in listOf("3", "0", "-1", "", "garbage", "20000001", "999999999999999999999")) {
            assertNull(
                "expected '$raw' to be rejected", BatteryVoltageValidator.normalizeSysfsVoltage(raw)
            )
        }
    }

    @Test
    fun `valid broadcast wins without touching sysfs`() {
        val sysfs = FakeSysfsAccessor()
        val resolver = BatteryVoltageResolver(sysfs, logger = { _, _, _ -> })

        val reading = resolver.resolve(3874)

        assertEquals(
            BatteryVoltageReading(
                3874, BatteryVoltageSource.BROADCAST, "ACTION_BATTERY_CHANGED / voltage"
            ), reading
        )
        assertEquals(0, sysfs.readCount)
        assertEquals(0, sysfs.listCount)
    }

    @Test
    fun `invalid broadcast falls back to a directly readable sysfs value`() {
        val sysfs = FakeSysfsAccessor().apply {
            files["/sys/class/power_supply/battery/voltage_now"] = "3874000"
        }
        val resolver = BatteryVoltageResolver(sysfs, logger = { _, _, _ -> })

        val reading = resolver.resolve(3)

        val expected = BatteryVoltageReading(
            3874, BatteryVoltageSource.SYSFS_DIRECT, "/sys/class/power_supply/battery/voltage_now"
        )
        assertEquals(expected, reading)
        assertEquals(expected, resolver.resolve(3))
    }

    @Test
    fun `all sources invalid resolves to null`() {
        val sysfs = FakeSysfsAccessor()
        val resolver = BatteryVoltageResolver(sysfs, logger = { _, _, _ -> })

        assertNull(resolver.resolve(3))
        assertNull(resolver.resolve(3))
    }

    @Test
    fun `cached direct sysfs path is rediscovered when it stops returning usable data`() {
        withSysfsRoot { root ->
            val sysfsRoot = File(root, "sys/class/power_supply").apply { check(mkdirs()) }
            createSupply(sysfsRoot, "battery", "Battery", voltageNow = "3874000")
            createSupply(sysfsRoot, "bms", "BMS", voltageNow = "3900000")
            val sysfs = PrefixedSysfsAccessor(root.path, DirectSysfsAccessor())
            val resolver = BatteryVoltageResolver(sysfs, logger = { _, _, _ -> })

            assertEquals(
                BatteryVoltageReading(
                    3874,
                    BatteryVoltageSource.SYSFS_DIRECT,
                    "/sys/class/power_supply/battery/voltage_now"
                ), resolver.resolve(3)
            )

            File(sysfsRoot, "battery/voltage_now").writeText("garbage")

            val expected = BatteryVoltageReading(
                3900, BatteryVoltageSource.SYSFS_DIRECT, "/sys/class/power_supply/bms/voltage_now"
            )
            assertEquals(expected, resolver.resolve(3))
            assertEquals(expected, resolver.resolve(3))
        }
    }


    @Test
    fun `discovery prefers the canonical battery supply over bms`() {
        withSysfsRoot { root ->
            createSupply(root, "bms", "BMS", voltageNow = "3900000")
            createSupply(root, "battery", "Battery", voltageNow = "3874000")

            val result = discoverBatteryVoltage(DirectSysfsAccessor(), root.path)

            assertEquals(File(root, "battery/voltage_now").path, result?.first)
            assertEquals(3874, result?.second)
        }
    }

    @Test
    fun `discovery uses bms when the battery supply is missing`() {
        withSysfsRoot { root ->
            createSupply(root, "bms", "BMS", voltageNow = "3900000")

            val result = discoverBatteryVoltage(DirectSysfsAccessor(), root.path)

            assertEquals(File(root, "bms/voltage_now").path, result?.first)
            assertEquals(3900, result?.second)
        }
    }

    @Test
    fun `discovery finds a vendor supply with Battery type`() {
        withSysfsRoot { root ->
            createSupply(root, "oem-pack", "Battery", voltageNow = "3874000")

            val result = discoverBatteryVoltage(DirectSysfsAccessor(), root.path)

            assertEquals(File(root, "oem-pack/voltage_now").path, result?.first)
            assertEquals(3874, result?.second)
        }
    }

    @Test
    fun `discovery ignores usb main and wireless supplies`() {
        withSysfsRoot { root ->
            createSupply(root, "usb", "USB", voltageNow = "5000000")
            createSupply(root, "main", "Mains", voltageNow = "9000000")
            createSupply(root, "wireless", "Wireless", voltageNow = "12000000")

            assertNull(discoverBatteryVoltage(DirectSysfsAccessor(), root.path))
        }
    }

    @Test
    fun `discovery skips an invalid voltage and tries the next battery candidate`() {
        withSysfsRoot { root ->
            createSupply(root, "battery", "Battery", voltageNow = "garbage")
            createSupply(root, "bms", "BMS", voltageNow = "3900000")

            val result = discoverBatteryVoltage(DirectSysfsAccessor(), root.path)

            assertEquals(File(root, "bms/voltage_now").path, result?.first)
            assertEquals(3900, result?.second)
        }
    }

    private class FakeSysfsAccessor : SysfsAccessor {
        val files = mutableMapOf<String, String>()
        var readCount = 0
        var listCount = 0

        override fun list(path: String): List<String> {
            listCount++
            val prefix = path.trimEnd('/') + "/"
            return files.keys.mapNotNull { key ->
                if (!key.startsWith(prefix)) return@mapNotNull null
                val rest = key.removePrefix(prefix)
                if (rest.isEmpty() || rest.contains('/')) return@mapNotNull null
                rest
            }
        }

        override fun read(path: String): String? {
            readCount++
            return files[path]
        }
    }

    private class PrefixedSysfsAccessor(
        private val prefix: String, private val delegate: SysfsAccessor
    ) : SysfsAccessor {
        override fun list(path: String): List<String> = delegate.list(prefix + path)

        override fun read(path: String): String? = delegate.read(prefix + path)
    }

    private fun withSysfsRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("battery-voltage-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createSupply(root: File, name: String, type: String, voltageNow: String?) {
        val directory = File(root, name).apply { check(mkdirs()) }
        File(directory, "type").writeText(type)
        voltageNow?.let { File(directory, "voltage_now").writeText(it) }
    }
}
