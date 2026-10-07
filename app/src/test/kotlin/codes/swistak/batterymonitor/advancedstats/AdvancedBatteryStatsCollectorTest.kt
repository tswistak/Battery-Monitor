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
package codes.swistak.batterymonitor.advancedstats

import codes.swistak.batterymonitor.common.CommandExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedBatteryStatsCollectorTest {
    @Test
    fun `raw collection retains original multiline empty and long values and their command sources`() {
        val dump =
            "Current Battery Service state:\n  Charge counter: 2345000\n  Max charging voltage: 5000000\n"
        val longValue = "  first line\n" + "a".repeat(2000) + "\nlast line  \n"
        val outputs = mapOf(
            "dumpsys battery" to dump,
            "cmd battery get current_now 2>/dev/null" to "  -900000\n",
            "cmd battery get charge_counter 2>/dev/null" to "permission denied for uid 12345",
            "ls /sys/class/power_supply 2>/dev/null" to "battery\n",
            "ls /sys/class/power_supply/battery 2>/dev/null" to "custom_field\nempty\nuevent\n",
            "cat /sys/class/power_supply/battery/custom_field 2>/dev/null" to longValue,
            "cat /sys/class/power_supply/battery/empty 2>/dev/null" to "",
            "cat /sys/class/power_supply/battery/uevent 2>/dev/null" to "POWER_SUPPLY_STATUS=Discharging\n"
        )
        val snapshot = AdvancedBatteryStatsCollector.collect(object : CommandExecutor {
            override fun run(command: String) = outputs[command]?.trim()
            override fun runRaw(command: String) = outputs[command]
        }, "App UID", 12345, null, false)

        assertEquals(-900000L, snapshot.currentNowUa)
        assertEquals(2345000L, snapshot.chargeCounterUah)
        assertEquals("cmd battery get current_now", snapshot.fieldSources["current_now"])
        assertFalse(snapshot.serviceLabels.contains("dumpsys battery"))
        assertEquals(
            "2345000", snapshot.serviceValues[snapshot.serviceLabels.indexOf("Charge counter")]
        )
        assertEquals(
            "5000000",
            snapshot.serviceValues[snapshot.serviceLabels.indexOf("Max charging voltage")]
        )
        assertEquals(
            "dumpsys battery / Max charging voltage", snapshot.fieldSources["max_charging_voltage"]
        )
        assertEquals(
            "  -900000\n",
            snapshot.metadataValues[snapshot.metadataLabels.indexOf("cmd battery get current_now")]
        )
        assertEquals(
            longValue, snapshot.sysfsValues[snapshot.sysfsLabels.indexOf("battery/custom_field")]
        )
        assertEquals("", snapshot.sysfsValues[snapshot.sysfsLabels.indexOf("battery/empty")])
        assertTrue(snapshot.sysfsLabels.contains("battery/uevent"))
        assertTrue(snapshot.capturedAtMillis > 0)
    }

    @Test
    fun `public intent readings fill normalized fields without replacing API values or modifying raw values`() {
        val fields = mapOf(
            "charge_counter" to "2651000",
            "level" to "73",
            "scale" to "100",
            "max_charging_current" to "2000000",
            "max_charging_voltage" to "5000000",
            "status" to "4"
        )
        val snapshot = AdvancedBatterySnapshot().apply {
            serviceLabels.addAll(fields.keys)
            serviceValues.addAll(fields.values)
        }
        AdvancedBatteryStatsCollector.projectBatteryIntentFields(snapshot, fields)
        assertEquals(2651000L, snapshot.chargeCounterUah)
        assertEquals(73, snapshot.reportedCapacityPercent)
        assertEquals(2000000L, snapshot.maxChargingCurrentUa)
        assertEquals(5000000L, snapshot.maxChargingVoltageUv)
        assertEquals("4", snapshot.chargingState)
        assertEquals(
            "ACTION_BATTERY_CHANGED / charge_counter", snapshot.fieldSources["charge_counter"]
        )
        assertEquals("ACTION_BATTERY_CHANGED / level / scale", snapshot.fieldSources["capacity"])
        assertEquals(
            "ACTION_BATTERY_CHANGED / max_charging_current",
            snapshot.fieldSources["max_charging_current"]
        )
        assertEquals(
            "ACTION_BATTERY_CHANGED / max_charging_voltage",
            snapshot.fieldSources["max_charging_voltage"]
        )
        assertEquals("ACTION_BATTERY_CHANGED / status", snapshot.fieldSources["charging_state"])
        assertEquals(fields, snapshot.serviceLabels.zip(snapshot.serviceValues).toMap())

        snapshot.chargeCounterUah = 3000000
        snapshot.reportedCapacityPercent = 75
        snapshot.fieldSources["charge_counter"] = "BatteryManager / BATTERY_PROPERTY_CHARGE_COUNTER"
        snapshot.fieldSources["capacity"] = "BatteryManager / BATTERY_PROPERTY_CAPACITY"
        AdvancedBatteryStatsCollector.projectBatteryIntentFields(snapshot, fields)
        assertEquals(3000000L, snapshot.chargeCounterUah)
        assertEquals(75, snapshot.reportedCapacityPercent)
        assertEquals(
            "BatteryManager / BATTERY_PROPERTY_CHARGE_COUNTER",
            snapshot.fieldSources["charge_counter"]
        )
        assertEquals(
            "BatteryManager / BATTERY_PROPERTY_CAPACITY", snapshot.fieldSources["capacity"]
        )

        val scaled = AdvancedBatterySnapshot()
        AdvancedBatteryStatsCollector.projectBatteryIntentFields(
            scaled, mapOf("level" to "33", "scale" to "50")
        )
        assertEquals(66, scaled.reportedCapacityPercent)
        val unsupported = AdvancedBatterySnapshot()
        AdvancedBatteryStatsCollector.projectBatteryIntentFields(
            unsupported,
            mapOf("charge_counter" to "-1", "level" to "20", "scale" to "0", "status" to "-1")
        )
        assertNull(unsupported.chargeCounterUah)
        assertNull(unsupported.reportedCapacityPercent)
        assertNull(unsupported.chargingState)
    }

    @Test
    fun `current readings try fresh commands before command and sysfs fallbacks and retain valid raw output`() {
        val calls = mutableListOf<String>()
        val snapshot = AdvancedBatteryStatsCollector.collect(object : CommandExecutor {
            override fun run(command: String): String? {
                calls.add(command)
                return when (command) {
                    "cmd battery get -f current_now 2>/dev/null" -> "  -123000\n"
                    "cmd battery get -f current_average 2>/dev/null" -> "permission denied uid 1000"
                    "cmd battery get current_average 2>/dev/null" -> "  -100000\n"
                    else -> null
                }
            }
        }, AdvancedBatterySnapshot.ACCESS_SHIZUKU, 2000, null, false)
        assertEquals(-123000L, snapshot.currentNowUa)
        assertEquals(-100000L, snapshot.currentAverageUa)
        assertFalse(calls.contains("cmd battery get current_now 2>/dev/null"))
        assertTrue(calls.indexOf("cmd battery get -f current_average 2>/dev/null") < calls.indexOf("cmd battery get current_average 2>/dev/null"))
        assertEquals("cmd battery get -f current_now", snapshot.fieldSources["current_now"])
        assertEquals("cmd battery get current_average", snapshot.fieldSources["current_average"])
        assertEquals(
            "  -123000\n",
            snapshot.metadataValues[snapshot.metadataLabels.indexOf("cmd battery get -f current_now")]
        )
        assertFalse(snapshot.metadataLabels.contains("cmd battery get -f current_average"))

        val sysfs = AdvancedBatteryStatsCollector.collect(object : CommandExecutor {
            override fun run(command: String) = when (command) {
                "cat /sys/class/power_supply/battery/current_now 2>/dev/null" -> "-900000"
                "cat /sys/class/power_supply/bms/current_avg 2>/dev/null" -> "-500000"
                else -> null
            }
        }, AdvancedBatterySnapshot.ACCESS_ROOT, 0, null, false)
        assertEquals(-900000L, sysfs.currentNowUa)
        assertEquals(-500000L, sysfs.currentAverageUa)
        assertEquals(
            "/sys/class/power_supply/battery/current_now", sysfs.fieldSources["current_now"]
        )
        assertEquals(
            "/sys/class/power_supply/bms/current_avg", sysfs.fieldSources["current_average"]
        )
    }

    @Test
    fun `empty and failed battery commands do not produce raw data or discard other sources`() {
        for (output in listOf(
            null,
            "",
            " \n",
            "Can't find service: battery\n",
            "Permission Denial: can't dump BatteryService from pid=12, uid=12345\n",
            "Current Battery Service state:\n"
        )) {
            val snapshot = AdvancedBatteryStatsCollector.collect(object : CommandExecutor {
                override fun run(command: String): String? = when (command) {
                    "ls /sys/class/power_supply 2>/dev/null" -> "battery"
                    "ls /sys/class/power_supply/battery 2>/dev/null" -> "status"
                    "cat /sys/class/power_supply/battery/status 2>/dev/null" -> "Discharging\n"
                    else -> output
                }
            }, "App UID", 12345, null, false)

            assertTrue("No service data expected for $output", snapshot.serviceLabels.isEmpty())
            assertTrue(
                "No numeric command data expected for $output", snapshot.metadataLabels.isEmpty()
            )
            assertNull(snapshot.currentNowUa)
            assertEquals(listOf("battery/status"), snapshot.sysfsLabels)
            assertEquals(listOf("Discharging\n"), snapshot.sysfsValues)
        }
    }

    @Test
    fun `numeric parsing rejects errors and absent cycle counts never become zero`() {
        assertNull(AdvancedBatteryStatsCollector.parseLong("permission denied uid 1000"))
        assertNull(AdvancedBatteryStatsCollector.parseLong("123 mA"))
        assertEquals(-123L, AdvancedBatteryStatsCollector.parseLong("  -123\n"))
        assertNull(AndroidCycleCount.value(false, 0))
        assertNull(AndroidCycleCount.value(true, -1))
        assertEquals(0L, AndroidCycleCount.value(true, 0))
    }
}
