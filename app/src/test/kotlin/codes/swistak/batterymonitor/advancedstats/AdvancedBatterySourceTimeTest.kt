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

import android.os.BatteryManager
import codes.swistak.batterymonitor.common.CommandExecutor
import codes.swistak.batterymonitor.monitoring.BatteryMeasurementTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AdvancedBatterySourceTimeTest {
    @Test
    fun `monitoring fields retain the start time of their actual dump batch or command`() {
        var elapsed = 0L
        fun now() = BatteryMeasurementTime(10_000 + elapsed, elapsed)
        val commandStarts = linkedMapOf<String, BatteryMeasurementTime>()
        val executor = object : CommandExecutor {
            override fun run(command: String) = runRaw(command)?.trim()
            override fun runRaw(command: String): String? {
                commandStarts[command] = now()
                elapsed += 40
                return when {
                    command == "dumpsys battery" -> "Current Battery Service state:\n  level: 50\n  scale: 100\n  voltage: 4000\n"

                    command == "ls /sys/class/power_supply 2>/dev/null" -> "pack"
                    command.startsWith("for battery_file in ") && command.contains("/type'") -> "\n__BATTERY_MONITOR_FILE__/sys/class/power_supply/pack/type\nBattery\n"

                    command.startsWith("for battery_file in ") -> """
                        __BATTERY_MONITOR_FILE__/sys/class/power_supply/pack/current_now
                        -100000
                        __BATTERY_MONITOR_FILE__/sys/class/power_supply/pack/charge_counter
                        2000000
                    """.trimIndent()

                    command == "cmd battery get -f current_average 2>/dev/null" -> Int.MIN_VALUE.toString()

                    command == "cmd battery get current_average 2>/dev/null" -> "-90000"
                    else -> error("Unexpected command: $command")
                }
            }
        }

        val snapshot = AdvancedBatteryStatsCollector.collectMonitoring(executor, now = ::now)
        val batches =
            commandStarts.filterKeys { it.startsWith("for battery_file in ") }.values.toList()
        assertEquals(
            commandStarts["dumpsys battery"], snapshot.sourceReadTimes["dumpsys battery / voltage"]
        )
        assertEquals(
            commandStarts["dumpsys battery"],
            snapshot.sourceReadTimes[snapshot.fieldSources["capacity"]]
        )
        assertEquals(batches[0], snapshot.sourceReadTimes["/sys/class/power_supply/pack/type"])
        assertEquals(batches[1], snapshot.sourceReadTimes[snapshot.fieldSources["current_now"]])
        assertEquals(batches[1], snapshot.sourceReadTimes[snapshot.fieldSources["charge_counter"]])
        assertEquals(
            commandStarts["cmd battery get current_average 2>/dev/null"],
            snapshot.sourceReadTimes[snapshot.fieldSources["current_average"]]
        )
        assertEquals(
            commandStarts["cmd battery get -f current_average 2>/dev/null"],
            snapshot.sourceReadTimes["cmd battery get -f current_average"]
        )
        assertNull(snapshot.sourceReadTimes["/sys/class/power_supply/pack/temp"])
        assertFalse(snapshot.sourceReadTimes.keys.any { "2>/dev/null" in it })
    }

    @Test
    fun `raw collection keeps independent start times for individual sysfs reads including empty raw values`() {
        var elapsed = 500L
        fun now() = BatteryMeasurementTime(20_000 + elapsed, elapsed)
        val starts = linkedMapOf<String, BatteryMeasurementTime>()
        val outputs = mapOf(
            "dumpsys battery" to "Current Battery Service state:\n  Charge counter: 1000\n  level: 3\n  scale: 10\n",
            "cmd battery get -f current_now 2>/dev/null" to "-200000",
            "ls /sys/class/power_supply 2>/dev/null" to "battery",
            "ls /sys/class/power_supply/battery 2>/dev/null" to "type\ncurrent_avg\nempty\nmissing",
            "cat /sys/class/power_supply/battery/type 2>/dev/null" to "Battery",
            "cat /sys/class/power_supply/battery/current_avg 2>/dev/null" to "-150000",
            "cat /sys/class/power_supply/battery/empty 2>/dev/null" to ""
        )
        val executor = object : CommandExecutor {
            override fun run(command: String) = runRaw(command)?.trim()
            override fun runRaw(command: String): String? {
                starts[command] = now()
                elapsed += 100
                return outputs[command]
            }
        }

        val snapshot =
            AdvancedBatteryStatsCollector.collect(executor, "App UID", 12345, null, false, ::now)
        assertEquals(
            starts["dumpsys battery"],
            snapshot.sourceReadTimes[snapshot.fieldSources["charge_counter"]]
        )
        assertEquals(
            starts["dumpsys battery"], snapshot.sourceReadTimes[snapshot.fieldSources["capacity"]]
        )
        assertEquals(
            starts["cmd battery get -f current_now 2>/dev/null"],
            snapshot.sourceReadTimes[snapshot.fieldSources["current_now"]]
        )
        assertEquals(
            starts["cat /sys/class/power_supply/battery/current_avg 2>/dev/null"],
            snapshot.sourceReadTimes[snapshot.fieldSources["current_average"]]
        )
        assertEquals(
            starts["cat /sys/class/power_supply/battery/empty 2>/dev/null"],
            snapshot.sourceReadTimes["/sys/class/power_supply/battery/empty"]
        )
        assertNull(snapshot.sourceReadTimes["/sys/class/power_supply/battery/missing"])
        assertNull(snapshot.sourceReadTimes["cmd battery get charge_counter"])
    }

    @Test
    fun `BatteryManager times precede each property call and failed or absent readings have no invented time`() {
        var elapsed = 1000L
        fun now() = BatteryMeasurementTime(30_000 + elapsed, elapsed)
        val starts = linkedMapOf<Int, BatteryMeasurementTime>()
        val snapshot = AdvancedBatterySnapshot().apply { capturedAtMillis = 999_999 }
        AdvancedBatteryStatsCollector.collectBatteryManagerReadings(
            snapshot = snapshot,
            getIntProperty = { property ->
                starts[property] = now()
                elapsed += 10
                when (property) {
                    BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER -> 2000000
                    BatteryManager.BATTERY_PROPERTY_CURRENT_NOW -> -300000
                    BatteryManager.BATTERY_PROPERTY_CAPACITY -> error("Unavailable property")
                    else -> Int.MIN_VALUE
                }
            },
            getLongProperty = {
                elapsed += 10
                Long.MIN_VALUE
            },
            getChargeTimeRemaining = {
                elapsed += 10
                null
            },
            now = ::now
        )
        assertEquals(
            starts[BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER],
            snapshot.sourceReadTimes[snapshot.fieldSources["charge_counter"]]
        )
        assertEquals(
            starts[BatteryManager.BATTERY_PROPERTY_CURRENT_NOW],
            snapshot.sourceReadTimes[snapshot.fieldSources["current_now"]]
        )
        assertEquals(
            starts[BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE],
            snapshot.sourceReadTimes["BatteryManager / BATTERY_PROPERTY_CURRENT_AVERAGE"]
        )
        assertNull(snapshot.currentAverageUa)
        assertNull(snapshot.sourceReadTimes["BatteryManager / BATTERY_PROPERTY_CAPACITY"])
        assertNull(snapshot.sourceReadTimes["BatteryManager / computeChargeTimeRemaining"])
    }
}
