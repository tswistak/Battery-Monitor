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
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

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
    fun `dumpsys battery voltage parsing accepts only plausible millivolt values`() {
        val dump = """
            Current Battery Service state:
              AC powered: false
              status: 2
              level: 87
              voltage: 3874
              temperature: 300
        """.trimIndent()

        assertEquals(3874, BatteryVoltageValidator.parseDumpsysVoltageMillivolts(dump))
        assertNull(BatteryVoltageValidator.parseDumpsysVoltageMillivolts("voltage: 3"))
        assertNull(BatteryVoltageValidator.parseDumpsysVoltageMillivolts("level: 87"))
        assertNull(BatteryVoltageValidator.parseDumpsysVoltageMillivolts(null))
    }

    @Test
    fun `valid broadcast wins without touching sysfs or privileged access`() {
        val sysfs = FakeSysfsAccessor()
        val harness =
            ResolverHarness(sysfs, privilegedCommand = { error("unexpected privileged call") })

        val reading = harness.resolver.resolve(3874)

        assertEquals(BatteryVoltageReading(3874, BatteryVoltageSource.BROADCAST), reading)
        assertEquals(0, sysfs.readCount)
        assertEquals(0, sysfs.listCount)
    }

    @Test
    fun `invalid broadcast falls back to a directly readable sysfs value`() {
        val sysfs = FakeSysfsAccessor().apply {
            files["/sys/class/power_supply/battery/voltage_now"] = "3874000"
        }
        var privilegedCalls = 0
        val harness = ResolverHarness(sysfs, privilegedCommand = { privilegedCalls++; null })

        val reading = harness.resolver.resolve(3)

        assertEquals(BatteryVoltageReading(3874, BatteryVoltageSource.SYSFS_DIRECT), reading)
        assertEquals(0, privilegedCalls)
    }

    @Test
    fun `invalid broadcast without sysfs uses a cached privileged dumpsys result`() {
        val sysfs = FakeSysfsAccessor()
        var dumpsysCalls = 0
        val harness = ResolverHarness(sysfs, privilegedCommand = { command ->
            if (command == "dumpsys battery") {
                dumpsysCalls++
                "voltage: 3874"
            } else {
                null
            }
        })

        assertNull(harness.resolver.resolve(3))
        assertEquals(1, dumpsysCalls)

        val reading = harness.resolver.resolve(3)

        assertEquals(BatteryVoltageReading(3874, BatteryVoltageSource.DUMPSYS_PRIVILEGED), reading)
        assertEquals(1, dumpsysCalls)
        assertEquals(
            listOf(BatteryVoltageReading(3874, BatteryVoltageSource.DUMPSYS_PRIVILEGED)),
            harness.refreshResults
        )
    }

    @Test
    fun `privileged probe prefers battery sysfs over dumpsys`() {
        val sysfs = FakeSysfsAccessor()
        val commands = mutableListOf<String>()
        val harness = ResolverHarness(sysfs, privilegedCommand = { command ->
            commands += command
            if (command == "cat /sys/class/power_supply/battery/voltage_now 2>/dev/null") "3874000" else null
        })

        harness.resolver.resolve(3)
        val reading = harness.resolver.resolve(3)

        assertEquals(BatteryVoltageReading(3874, BatteryVoltageSource.SYSFS_PRIVILEGED), reading)
        assertFalse(commands.contains("dumpsys battery"))
    }

    @Test
    fun `privileged probe discovers a vendor battery supply and ignores charger supplies`() {
        val sysfs = FakeSysfsAccessor()
        val harness = ResolverHarness(sysfs, privilegedCommand = { command ->
            when (command) {
                "cat /sys/class/power_supply/battery/voltage_now 2>/dev/null", "cat /sys/class/power_supply/bms/voltage_now 2>/dev/null" -> null

                "ls /sys/class/power_supply 2>/dev/null" -> "usb\nvendor-pack\nwireless"
                "cat /sys/class/power_supply/usb/type 2>/dev/null" -> "USB"
                "cat /sys/class/power_supply/wireless/type 2>/dev/null" -> "Wireless"
                "cat /sys/class/power_supply/vendor-pack/type 2>/dev/null" -> "Battery"
                "cat /sys/class/power_supply/vendor-pack/voltage_now 2>/dev/null" -> "3874000"
                else -> null
            }
        })

        harness.resolver.resolve(3)
        val reading = harness.resolver.resolve(3)

        assertEquals(BatteryVoltageReading(3874, BatteryVoltageSource.SYSFS_PRIVILEGED), reading)
    }

    @Test
    fun `all sources invalid resolves to null`() {
        val sysfs = FakeSysfsAccessor()
        val harness = ResolverHarness(sysfs, privilegedCommand = { null })

        assertNull(harness.resolver.resolve(3))
        assertNull(harness.resolver.resolve(3))
    }

    @Test
    fun `disabled privileged access never probes`() {
        val sysfs = FakeSysfsAccessor()
        var privilegedCalls = 0
        val harness = ResolverHarness(
            sysfs, privilegedCommand = { privilegedCalls++; null }, privilegedEnabled = false
        )

        assertNull(harness.resolver.resolve(3))
        assertNull(harness.resolver.resolve(3))

        assertEquals(0, privilegedCalls)
    }

    @Test
    fun `privileged probing is throttled`() {
        val sysfs = FakeSysfsAccessor()
        var dumpsysCalls = 0
        val harness = ResolverHarness(sysfs, privilegedCommand = { command ->
            if (command == "dumpsys battery") {
                dumpsysCalls++
                "voltage: 3"
            } else {
                null
            }
        })

        harness.resolver.resolve(3)
        harness.nowMs += 10_000
        harness.resolver.resolve(3)
        harness.nowMs += 10_000
        harness.resolver.resolve(3)
        assertEquals(1, dumpsysCalls)

        harness.nowMs += 30_001
        harness.resolver.resolve(3)
        assertEquals(2, dumpsysCalls)
    }

    @Test
    fun `privileged cache expires and is refreshed in the background`() {
        val sysfs = FakeSysfsAccessor()
        var dumpsysCalls = 0
        val harness = ResolverHarness(sysfs, privilegedCommand = { command ->
            if (command == "dumpsys battery") {
                dumpsysCalls++
                "voltage: 3874"
            } else {
                null
            }
        })

        assertNull(harness.resolver.resolve(3))
        assertEquals(3874, harness.resolver.resolve(3)?.millivolts)
        assertEquals(1, dumpsysCalls)

        harness.nowMs += 6 * 60_000
        assertNull(harness.resolver.resolve(3))
        assertEquals(2, dumpsysCalls)
        assertEquals(3874, harness.resolver.resolve(3)?.millivolts)
        assertEquals(2, harness.refreshResults.size)
    }

    @Test
    fun `cached direct sysfs path is rediscovered when it stops returning usable data`() {
        withSysfsRoot { root ->
            val sysfsRoot = File(root, "sys/class/power_supply").apply { check(mkdirs()) }
            createSupply(sysfsRoot, "battery", "Battery", voltageNow = "3874000")
            createSupply(sysfsRoot, "bms", "BMS", voltageNow = "3900000")
            val sysfs = PrefixedSysfsAccessor(root.path, DirectSysfsAccessor())
            val harness = ResolverHarness(sysfs, privilegedCommand = { null })

            assertEquals(3874, harness.resolver.resolve(3)?.millivolts)

            File(sysfsRoot, "battery/voltage_now").writeText("garbage")

            assertEquals(3900, harness.resolver.resolve(3)?.millivolts)
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

    private class ResolverHarness(
        directSysfs: SysfsAccessor,
        privilegedCommand: (String) -> String?,
        privilegedEnabled: Boolean = true
    ) {
        var nowMs = 0L
        val refreshResults = mutableListOf<BatteryVoltageReading?>()

        val resolver = BatteryVoltageResolver(
            directSysfs = directSysfs,
            privilegedExecutor = object : CommandExecutor {
                override fun run(command: String): String? = privilegedCommand(command)
            },
            privilegedEnabled = { privilegedEnabled },
            backgroundExecutor = InlineExecutorService,
            onPrivilegedRefresh = { refreshResults += it },
            clock = { nowMs },
            logger = { _, _, _ -> })
    }

    private object InlineExecutorService : AbstractExecutorService() {
        override fun execute(command: Runnable) {
            command.run()
        }

        override fun shutdown() {}

        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

        override fun isShutdown(): Boolean = false

        override fun isTerminated(): Boolean = false

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
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
