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

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>.
*/
package codes.swistak.batterymonitor.privileged

import codes.swistak.batterymonitor.common.CommandExecutor
import codes.swistak.batterymonitor.common.RootCommandResult
import codes.swistak.batterymonitor.common.RootExecutor
import codes.swistak.batterymonitor.common.RootSession
import codes.swistak.batterymonitor.common.RootSessionOwner
import codes.swistak.batterymonitor.monitoring.BatteryMeasurementTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PrivilegedMonitoringBackendTest {
    @Test
    fun `fresh monitoring values and source times share one root session across access revisions`() {
        val fixture = Fixture()
        val transport = fixture.executor()
        var acquisitions = 0
        var closes = 0
        val owner = RootSessionOwner(factory = {
            object : RootSession {
                override fun acquire(): Boolean {
                    acquisitions++
                    return true
                }

                override fun execute(command: String): RootCommandResult {
                    val output = transport.runRaw(command)
                    return RootCommandResult(if (output == null) 1 else 0, output)
                }

                override fun close() {
                    closes++
                }
            }
        }, clock = { fixture.elapsed }, workers = Executor { it.run() })
        owner.setEnabled(true)
        try {
            val snapshots = (1..3).map { cycle ->
                fixture.elapsed += 2_000
                fixture.current = -100_000L * cycle
                // A Shizuku event invalidates measurement caches, not this process's root shell.
                if (cycle == 3) fixture.revision++
                val revision = fixture.revision
                val executor = RootExecutor(
                    allowed = { fixture.allowed && fixture.revision == revision }, owner = owner
                )
                requireNotNull(fixture.read(executor = executor))
            }

            assertEquals(listOf(-100_000L, -200_000L, -300_000L), snapshots.map { it.currentNowUa })
            val times = snapshots.map {
                it.sourceReadTimes.getValue(it.fieldSources.getValue("current_now"))
            }
            assertEquals(3, times.distinct().size)
            assertTrue(times.zipWithNext().all { (first, next) ->
                next.wallMillis > first.wallMillis && next.elapsedMillis!! > first.elapsedMillis!!
            })
            assertEquals(1, acquisitions)
            assertEquals(0, closes)
            assertEquals(2, fixture.commands.count { it == "id" })

            val staleRevision = fixture.revision
            fixture.onDump = { fixture.revision++ }
            assertNull(
                fixture.read(
                    executor = RootExecutor(
                        allowed = { fixture.allowed && fixture.revision == staleRevision },
                        owner = owner
                    )
                )
            )
            assertEquals(0, closes)
            fixture.onDump = {}
            fixture.elapsed += 2_000
            fixture.current = -400_000
            val revision = fixture.revision
            val fresh = fixture.read(
                executor = RootExecutor(
                    allowed = { fixture.allowed && fixture.revision == revision }, owner = owner
                )
            )
            assertEquals(-400_000L, fresh?.currentNowUa)
            assertEquals(1, acquisitions)
        } finally {
            owner.setEnabled(false)
        }
        assertEquals(1, closes)
    }

    @Test
    fun `transport death or timeout after successful dumpsys discards partial snapshot and fresh wrapper recovers`() {
        for (timeout in listOf(false, true)) {
            val fixture = Fixture()
            val transport = fixture.executor()
            val release = CountDownLatch(1)
            val created = AtomicInteger()
            val owner = RootSessionOwner(factory = {
                val fails = created.incrementAndGet() == 1
                object : RootSession {
                    override fun acquire(): Boolean = true

                    override fun execute(command: String): RootCommandResult {
                        val output = transport.runRaw(command)
                        if (fails && command == "ls /sys/class/power_supply 2>/dev/null") {
                            if (timeout) release.await(3, TimeUnit.SECONDS)
                            throw IOException("Session lost after dumpsys")
                        }
                        return RootCommandResult(if (output == null) 1 else 0, output)
                    }

                    override fun close() {
                        release.countDown()
                    }
                }
            }, timeoutMillis = 200, clock = { fixture.elapsed })
            owner.setEnabled(true)
            try {
                val stale = RootExecutor(owner = owner)
                assertTrue(stale.isValid)
                assertNull(fixture.read(executor = stale))
                assertEquals(1, fixture.commands.count { it == "dumpsys battery" })
                assertTrue(
                    fixture.commands.indexOf("dumpsys battery") < fixture.commands.indexOf("ls /sys/class/power_supply 2>/dev/null")
                )
                assertFalse(stale.isValid)
                fixture.elapsed += 1_000
                val fresh = RootExecutor(owner = owner)
                assertEquals(-100_000L, fixture.read(executor = fresh)?.currentNowUa)
                assertTrue(fresh.isValid)
                assertEquals(2, created.get())
            } finally {
                release.countDown()
                owner.setEnabled(false)
            }
        }
    }

    @Test
    fun `ordinary nonzero probe after successful dumpsys preserves valid partial snapshot and session`() {
        val fixture = Fixture()
        val transport = fixture.executor()
        var acquisitions = 0
        var closes = 0
        val owner = RootSessionOwner(factory = {
            object : RootSession {
                override fun acquire(): Boolean {
                    acquisitions++
                    return true
                }

                override fun execute(command: String): RootCommandResult {
                    val output = transport.runRaw(command)
                    return if (command == "ls /sys/class/power_supply 2>/dev/null") {
                        RootCommandResult(1, null)
                    } else RootCommandResult(if (output == null) 1 else 0, output)
                }

                override fun close() {
                    closes++
                }
            }
        }, workers = Executor { it.run() })
        owner.setEnabled(true)
        try {
            val executor = RootExecutor(owner = owner)
            val snapshot = fixture.read(executor = executor)
            assertNotNull(snapshot)
            assertEquals(50, snapshot?.reportedCapacityPercent)
            assertTrue(executor.isValid)
            assertNotNull(fixture.read(executor = RootExecutor(owner = owner)))
            assertEquals(1, acquisitions)
            assertEquals(0, closes)
        } finally {
            owner.setEnabled(false)
        }
        assertEquals(1, closes)
    }

    @Test
    fun `root identity and topology survive new executor wrappers but battery values stay fresh`() {
        val fixture = Fixture()
        assertEquals(-100000L, fixture.read()?.currentNowUa)
        assertEquals(5, fixture.commands.size)
        fixture.commands.clear()
        fixture.current = -200000

        assertEquals(-200000L, fixture.read()?.currentNowUa)
        assertEquals(2, fixture.commands.size)
        assertFalse(fixture.commands.contains("id"))
        fixture.elapsed += 60_000
        fixture.commands.clear()

        assertNotNull(fixture.read())
        assertTrue(fixture.commands.contains("id"))
    }

    @Test
    fun `access revision caller process and backend restart each discard capability state`() {
        val fixture = Fixture()
        fixture.read()
        fixture.commands.clear()
        fixture.revision++

        assertNotNull(fixture.read())
        assertEquals(5, fixture.commands.size)
        fixture.commands.clear()
        assertNotNull(fixture.read(callingPid = 222))
        assertEquals(5, fixture.commands.size)
        fixture.commands.clear()
        fixture.backend = fixture.newBackend()
        assertNotNull(fixture.read(callingPid = 222))
        assertEquals(5, fixture.commands.size)
    }

    @Test
    fun `denied root checks back off for five seconds and recover without a process restart`() {
        val fixture = Fixture()
        fixture.rootGranted = false
        assertNull(fixture.read())
        assertEquals(listOf("id"), fixture.commands)
        fixture.rootGranted = true
        fixture.commands.clear()
        fixture.elapsed += 1_000
        assertNull(fixture.read())
        assertTrue(fixture.commands.isEmpty())
        fixture.elapsed += 5_000
        assertNotNull(fixture.read())
        assertEquals(5, fixture.commands.size)
    }

    @Test
    fun `revocation while a warm command runs discards output stops later launches and clears capabilities`() {
        val fixture = Fixture()
        fixture.read()
        fixture.commands.clear()
        fixture.onDump = { fixture.allowed = false }

        assertNull(fixture.read())
        assertEquals(listOf("dumpsys battery"), fixture.commands)
        fixture.commands.clear()
        assertNull(fixture.read())
        assertTrue(fixture.commands.isEmpty())
        fixture.allowed = true
        fixture.onDump = {}
        assertNotNull(fixture.read())
        assertEquals(5, fixture.commands.size)
    }

    @Test
    fun `failed required command and exceptions invalidate successful backend capabilities`() {
        val fixture = Fixture()
        fixture.read()
        fixture.dumpAvailable = false
        assertNotNull(fixture.read())
        fixture.dumpAvailable = true
        fixture.commands.clear()
        assertNotNull(fixture.read())
        assertEquals(5, fixture.commands.size)
        fixture.onDump = { error("Backend process failed") }
        assertTrue(runCatching { fixture.read() }.isFailure)
        fixture.onDump = {}
        fixture.commands.clear()
        assertNotNull(fixture.read())
        assertEquals(5, fixture.commands.size)
    }

    @Test
    fun `Shizuku backend owns its cache and never launches a root identity command`() {
        val fixture = Fixture()
        fixture.backend = fixture.newBackend(requireRoot = false)
        fixture.rootGranted = false
        assertNotNull(fixture.read())
        assertEquals(4, fixture.commands.size)
        assertFalse(fixture.commands.contains("id"))
        fixture.commands.clear()
        assertNotNull(fixture.read())
        assertEquals(2, fixture.commands.size)
        fixture.revision++
        fixture.commands.clear()
        assertNotNull(fixture.read())
        assertEquals(4, fixture.commands.size)
    }

    @Test
    fun `classification metadata alone is not a successful battery reading`() {
        val fixture = Fixture()
        fixture.measurementsAvailable = false
        fixture.dumpAvailable = false
        assertNull(fixture.read())
        assertNull(fixture.read())
    }

    private class Fixture {
        var elapsed = 100L
        var revision = 1L
        var allowed = true
        var rootGranted = true
        var dumpAvailable = true
        var measurementsAvailable = true
        var current = -100000L
        var onDump: () -> Unit = {}
        val commands = mutableListOf<String>()
        var backend = newBackend()

        fun newBackend(requireRoot: Boolean = true) = PrivilegedMonitoringBackend(
            requireRoot,
            clock = { elapsed },
            measurementTime = { BatteryMeasurementTime(10_000 + elapsed, elapsed) })

        fun read(callingPid: Int = 111, executor: CommandExecutor = executor()) =
            backend.read(executor, revision, { allowed }, callingPid = callingPid)

        fun executor(): CommandExecutor = object : CommandExecutor {
            override fun run(command: String): String? = runRaw(command)?.trim()

            override fun runRaw(command: String): String? {
                commands += command
                elapsed += 10
                return when {
                    command == "id" -> if (rootGranted) "uid=0(root)" else null
                    command == "dumpsys battery" -> {
                        onDump()
                        if (dumpAvailable) "Current Battery Service state:\n  level: 50\n  scale: 100" else null
                    }

                    command == "ls /sys/class/power_supply 2>/dev/null" -> "pack"
                    command.startsWith("for battery_file in ") -> {
                        val values = if (command.contains("/type'")) mapOf("type" to "Battery")
                        else if (measurementsAvailable) mapOf(
                            "current_now" to "$current",
                            "current_avg" to "-90000",
                            "charge_counter" to "2000000",
                            "voltage_now" to "4000000",
                            "temp" to "250",
                            "health" to "Good",
                            "status" to "Charging",
                            "capacity" to "50"
                        ) else emptyMap()
                        values.entries.joinToString("") { (key, value) ->
                            "\n__BATTERY_MONITOR_FILE__/sys/class/power_supply/pack/$key\n$value\n"
                        }
                    }

                    command.startsWith("cmd battery get ") -> null
                    else -> error("Unexpected command: $command")
                }
            }
        }
    }
}
