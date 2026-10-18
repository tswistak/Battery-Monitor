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
package codes.swistak.batterymonitor.advancedstats

import codes.swistak.batterymonitor.common.CommandExecutor
import codes.swistak.batterymonitor.monitoring.BatteryMeasurementTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringBatteryCapabilitiesTest {
    @Test
    fun `warm monitoring reads fresh values without repeating discovery missing files or unsupported properties`() {
        val fixture = Fixture()
        val first = fixture.read()
        val firstCommands = fixture.commands.toList()
        fixture.commands.clear()
        fixture.files["pack/current_now"] = "-200000"
        fixture.level = 51

        val second = fixture.read()

        assertEquals(6, firstCommands.size)
        assertEquals(2, fixture.commands.size)
        assertEquals(-100000L, first.currentNowUa)
        assertEquals(-200000L, second.currentNowUa)
        assertEquals(51, second.reportedCapacityPercent)
        assertEquals(
            first.sourceReadTimes["/sys/class/power_supply/pack/type"],
            second.sourceReadTimes["/sys/class/power_supply/pack/type"]
        )
        assertTrue(
            second.sourceReadTimes.getValue(second.fieldSources.getValue("current_now")).elapsedMillis!! > first.sourceReadTimes.getValue(
                first.fieldSources.getValue("current_now")
            ).elapsedMillis!!
        )
        assertFalse(fixture.commands.any { it.contains("/current_avg'") || it.startsWith("cmd battery") })
        assertFalse(second.sysfsValues.any { it.contains("POWER_SUPPLY_CURRENT_NOW=") })
    }

    @Test
    fun `missing measurement files and unsupported properties retry after a bounded monotonic interval`() {
        val fixture = Fixture()
        fixture.read()
        fixture.files["pack/current_avg"] = "-50000"
        fixture.commands.clear()
        fixture.elapsed += 1_000

        assertNull(fixture.read().currentAverageUa)
        assertFalse(fixture.commands.any { it.contains("/current_avg'") || it.startsWith("cmd battery") })
        fixture.elapsed += 60_000
        fixture.commands.clear()

        assertEquals(-50000L, fixture.read().currentAverageUa)
        assertTrue(fixture.commands.any { it.startsWith("ls /sys/class/power_supply ") })
        assertTrue(fixture.commands.any { it.contains("/current_avg'") })

        fixture.files.remove("pack/current_avg")
        fixture.read()
        fixture.commands.clear()
        fixture.properties["cmd battery get current_average 2>/dev/null"] = "-70000"
        fixture.elapsed += 60_000

        assertEquals(-70000L, fixture.read().currentAverageUa)
        assertTrue(fixture.commands.contains("cmd battery get current_average 2>/dev/null"))
        fixture.commands.clear()
        fixture.properties["cmd battery get current_average 2>/dev/null"] = "-80000"
        assertEquals(-80000L, fixture.read().currentAverageUa)
        assertEquals(1, fixture.commands.count { it.startsWith("cmd battery") })
    }

    @Test
    fun `failure of a previously readable node rediscovers topology on the next acquisition`() {
        val fixture = Fixture()
        fixture.read()
        fixture.files.remove("pack/current_now")
        assertNull(fixture.read().currentNowUa)
        fixture.supplies = "replacement"
        fixture.files.clear()
        fixture.files.putAll(
            mapOf(
                "replacement/type" to "Battery",
                "replacement/current_now" to "-300000",
                "replacement/charge_counter" to "1900000"
            )
        )
        fixture.commands.clear()

        val snapshot = fixture.read()

        assertEquals(-300000L, snapshot.currentNowUa)
        assertEquals(
            "/sys/class/power_supply/replacement/current_now", snapshot.fieldSources["current_now"]
        )
        assertTrue(fixture.commands.any { it.startsWith("ls /sys/class/power_supply ") })
    }

    @Test
    fun `failure of a previously supported property immediately reopens its other command variant`() {
        val fixture = Fixture()
        fixture.files.remove("pack/current_now")
        fixture.properties["cmd battery get current_now 2>/dev/null"] = "-400000"
        assertEquals(-400000L, fixture.read().currentNowUa)
        fixture.properties.remove("cmd battery get current_now 2>/dev/null")
        assertNull(fixture.read().currentNowUa)
        fixture.properties["cmd battery get -f current_now 2>/dev/null"] = "-450000"
        fixture.commands.clear()

        assertEquals(-450000L, fixture.read().currentNowUa)
        assertTrue(fixture.commands.contains("cmd battery get -f current_now 2>/dev/null"))
    }

    @Test
    fun `explicit invalidation and a backwards test clock do not permanently suppress unavailable sources`() {
        val fixture = Fixture()
        fixture.read()
        fixture.properties["cmd battery get current_average 2>/dev/null"] = "-60000"
        fixture.capabilities.invalidate()
        assertEquals(-60000L, fixture.read().currentAverageUa)
        fixture.properties.remove("cmd battery get current_average 2>/dev/null")
        fixture.read()
        fixture.properties["cmd battery get current_average 2>/dev/null"] = "-65000"
        fixture.elapsed = -1_000

        assertEquals(-65000L, fixture.read().currentAverageUa)
    }

    private class Fixture : CommandExecutor {
        var elapsed = 100L
        var level = 50
        var supplies = "pack"
        val commands = mutableListOf<String>()
        val files = mutableMapOf(
            "pack/type" to "Battery",
            "pack/uevent" to "POWER_SUPPLY_TYPE=Battery\nPOWER_SUPPLY_CURRENT_NOW=-12345",
            "pack/current_now" to "-100000",
            "pack/charge_counter" to "2000000"
        )
        val properties = mutableMapOf<String, String>()
        val capabilities = MonitoringBatteryCapabilities { elapsed }

        fun read(): AdvancedBatterySnapshot = AdvancedBatteryStatsCollector.collectMonitoring(
            this,
            now = { BatteryMeasurementTime(10_000 + elapsed, elapsed) },
            capabilities = capabilities
        )

        override fun run(command: String): String? = runRaw(command)?.trim()

        override fun runRaw(command: String): String? {
            commands += command
            elapsed += 10
            return when {
                command == "dumpsys battery" -> "Current Battery Service state:\n  level: $level\n  scale: 100\n  status: 2"

                command == "ls /sys/class/power_supply 2>/dev/null" -> supplies
                command.startsWith("for battery_file in ") -> Regex("'/sys/class/power_supply/([^']+)'").findAll(
                    command
                ).mapNotNull { match ->
                    val path = match.groupValues[1]
                    files[path]?.let { "\n__BATTERY_MONITOR_FILE__/sys/class/power_supply/$path\n$it\n" }
                }.joinToString("")

                command.startsWith("cmd battery") -> properties[command] ?: "Unknown get option"
                else -> error("Unexpected command: $command")
            }
        }
    }
}
