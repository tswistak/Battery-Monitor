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

import android.os.BatteryManager
import codes.swistak.batterymonitor.common.CommandExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedBatteryStatsCollectorTest {
    @Test
    fun `monitoring collection batches only standard battery fields preserves separate currents and excludes charger and private readings`() {
        val metadata = mapOf(
            "battery/type" to "USB",
            "pack/type" to "Battery",
            "bms/type" to "Unknown",
            "usb/type" to "USB"
        )
        val files = mapOf(
            "pack/current_now" to "-4524000",
            "pack/current_avg" to "-4000000",
            "pack/charge_counter" to "-1",
            "pack/voltage_now" to "4012000",
            "pack/temp" to "255",
            "pack/health" to "Good",
            "pack/status" to "Charging",
            "pack/capacity" to "59",
            "bms/charge_counter" to "789000",
            "usb/current_now" to "2000000",
            "pack/charge_now_raw" to "9999999"
        )

        fun batch(fields: Map<String, String>) = fields.entries.joinToString("") { (key, value) ->
            "\n__BATTERY_MONITOR_FILE__/sys/class/power_supply/$key\n$value\n\n"
        }

        val calls = mutableListOf<String>()
        val snapshot = AdvancedBatteryStatsCollector.collectMonitoring(object : CommandExecutor {
            override fun run(command: String) = runRaw(command)?.trim()
            override fun runRaw(command: String): String? {
                calls += command
                return when {
                    command == "dumpsys battery" -> """
                        Current OPLUS Battery Service state:
                          Battery current: 299
                          ChargerTechnology: 99
                        Current Battery Service state:
                          Charge counter: 1000000
                          level: 59
                          scale: 100
                          status: 2
                          health: 2
                          voltage: 4012
                          temperature: 254
                          USB powered: true
                    """.trimIndent()

                    command == "ls /sys/class/power_supply 2>/dev/null" -> "battery\npack\nbms\nusb"
                    command.startsWith("for battery_file in ") -> if (command.contains("/type'")) batch(
                        metadata
                    ) else batch(files)

                    else -> error("Unexpected command: $command")
                }
            }
        })
        assertEquals(4, calls.size)
        assertTrue(calls.none { it.startsWith("cmd battery") })
        assertTrue(calls.none { it.contains("/usb/current_now'") || it.contains("/battery/current_now'") })
        assertTrue(calls.none { it.contains("charge_now_raw") || it.contains("charge_full") })
        assertEquals(-4524000L, snapshot.currentNowUa)
        assertEquals(-4000000L, snapshot.currentAverageUa)
        assertEquals(789000L, snapshot.chargeCounterUah)
        assertEquals(59, snapshot.reportedCapacityPercent)
        assertEquals("2", snapshot.chargingState)
        assertEquals(
            "/sys/class/power_supply/pack/current_now", snapshot.fieldSources["current_now"]
        )
        assertEquals(
            "/sys/class/power_supply/pack/current_avg", snapshot.fieldSources["current_average"]
        )
        assertEquals(
            "/sys/class/power_supply/bms/charge_counter", snapshot.fieldSources["charge_counter"]
        )
        assertEquals("dumpsys battery / level / scale", snapshot.fieldSources["capacity"])
        val service = snapshot.serviceLabels.zip(snapshot.serviceValues).toMap()
        assertEquals("4012", service["voltage"])
        assertEquals("254", service["temperature"])
        assertEquals("true", service["USB powered"])
        assertFalse(service.containsKey("Battery current"))
        assertFalse(service.containsKey("ChargerTechnology"))
        val sysfs = snapshot.sysfsLabels.zip(snapshot.sysfsValues).toMap()
        assertEquals("4012000", sysfs["pack/voltage_now"])
        assertEquals("255", sysfs["pack/temp"])
        assertEquals("Good", sysfs["pack/health"])
        assertTrue(sysfs.keys.none {
            it.startsWith("usb/") || it.startsWith("battery/") || it.endsWith(
                "charge_now_raw"
            )
        })
        assertTrue(snapshot.metadataLabels.isEmpty())
        assertNull(snapshot.energyCounterNwh)
        assertNull(snapshot.fullChargeUah)
        assertNull(snapshot.designChargeUah)
    }

    @Test
    fun `monitoring collection rejects unavailable commands and uses valid command or dumpsys charge with truthful provenance`() {
        var chargeCommand: String? = "-1"
        val executor = object : CommandExecutor {
            override fun run(command: String) = runRaw(command)?.trim()
            override fun runRaw(command: String): String? = when (command) {
                "dumpsys battery" -> "Current Battery Service state:\n  Charge counter: 0\n  status: 4\n"
                "ls /sys/class/power_supply 2>/dev/null" -> "battery"
                "cmd battery get -f current_now 2>/dev/null" -> Int.MIN_VALUE.toString()
                "cmd battery get current_now 2>/dev/null" -> " -500000\n"
                "cmd battery get -f current_average 2>/dev/null" -> "Unknown get option: -f"
                "cmd battery get current_average 2>/dev/null" -> "Unknown get option: current_average"
                "cmd battery get charge_counter 2>/dev/null" -> chargeCommand
                else -> null
            }
        }
        val dump = AdvancedBatteryStatsCollector.collectMonitoring(executor)
        assertEquals(-500000L, dump.currentNowUa)
        assertNull(dump.currentAverageUa)
        assertEquals("cmd battery get current_now", dump.fieldSources["current_now"])
        assertEquals(0L, dump.chargeCounterUah)
        assertEquals("dumpsys battery / Charge counter", dump.fieldSources["charge_counter"])
        assertEquals("4", dump.chargingState)
        assertEquals(
            Int.MIN_VALUE.toString(),
            dump.metadataValues[dump.metadataLabels.indexOf("cmd battery get -f current_now")]
        )
        assertTrue(dump.metadataLabels.none { "current_average" in it })

        chargeCommand = "0"
        val command = AdvancedBatteryStatsCollector.collectMonitoring(executor)
        assertEquals(0L, command.chargeCounterUah)
        assertEquals("cmd battery get charge_counter", command.fieldSources["charge_counter"])
        assertNull(command.currentAverageUa)
    }

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
                "ls /sys/class/power_supply 2>/dev/null" -> "battery\nbms"
                "ls /sys/class/power_supply/battery 2>/dev/null" -> "current_now"
                "ls /sys/class/power_supply/bms 2>/dev/null" -> "current_avg"
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
    fun `public battery counters are collected once with exact raw values and fill missing fields without replacing command readings`() {
        val calls = mutableListOf<Int>()
        val values = mapOf(
            BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER to 2651000,
            BatteryManager.BATTERY_PROPERTY_CURRENT_NOW to -493000,
            BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE to -500000,
            BatteryManager.BATTERY_PROPERTY_CAPACITY to 73,
            10 to 88
        )
        val snapshot = AdvancedBatterySnapshot()
        AdvancedBatteryStatsCollector.collectBatteryManagerReadings(snapshot, {
            calls += it
            values.getValue(it)
        }, {
            calls += it
            123456789L
        })
        assertEquals(
            listOf(
                BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER,
                BatteryManager.BATTERY_PROPERTY_CURRENT_NOW,
                BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE,
                BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER,
                BatteryManager.BATTERY_PROPERTY_CAPACITY,
                10
            ), calls
        )
        assertEquals(2651000L, snapshot.chargeCounterUah)
        assertEquals(-493000L, snapshot.currentNowUa)
        assertEquals(-500000L, snapshot.currentAverageUa)
        assertEquals(123456789L, snapshot.energyCounterNwh)
        assertEquals(
            "BatteryManager / BATTERY_PROPERTY_CURRENT_AVERAGE",
            snapshot.fieldSources["current_average"]
        )
        assertEquals(
            mapOf(
                "BATTERY_PROPERTY_CHARGE_COUNTER" to "2651000",
                "BATTERY_PROPERTY_CURRENT_NOW" to "-493000",
                "BATTERY_PROPERTY_CURRENT_AVERAGE" to "-500000",
                "BATTERY_PROPERTY_ENERGY_COUNTER" to "123456789",
                "BATTERY_PROPERTY_CAPACITY" to "73",
                "BATTERY_PROPERTY_STATE_OF_HEALTH" to "88"
            ), snapshot.metadataLabels.zip(snapshot.metadataValues).toMap()
        )

        val commands = AdvancedBatterySnapshot().apply {
            chargeCounterUah = 0
            currentNowUa = -293000
            currentAverageUa = -400000
            energyCounterNwh = 0
            reportedCapacityPercent = 75
            stateOfHealthPercent = 90
            listOf(
                "charge_counter",
                "current_now",
                "current_average",
                "energy_counter",
                "capacity",
                "state_of_health"
            ).forEach {
                fieldSources[it] = "cmd battery get $it"
            }
        }
        val commandSources = commands.fieldSources.toMap()
        AdvancedBatteryStatsCollector.collectBatteryManagerReadings(
            commands,
            { values.getValue(it) },
            { 123456789 })
        assertEquals(0L, commands.chargeCounterUah)
        assertEquals(-293000L, commands.currentNowUa)
        assertEquals(-400000L, commands.currentAverageUa)
        assertEquals(0L, commands.energyCounterNwh)
        assertEquals(75, commands.reportedCapacityPercent)
        assertEquals(90, commands.stateOfHealthPercent)
        assertEquals(commandSources, commands.fieldSources)
        assertEquals(
            snapshot.metadataLabels.zip(snapshot.metadataValues),
            commands.metadataLabels.zip(commands.metadataValues)
        )

        val unsupported = AdvancedBatterySnapshot()
        AdvancedBatteryStatsCollector.collectBatteryManagerReadings(unsupported, {
            if (it == BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) -1 else Int.MIN_VALUE
        }, { Long.MIN_VALUE })
        assertNull(unsupported.chargeCounterUah)
        assertNull(unsupported.currentNowUa)
        assertNull(unsupported.currentAverageUa)
        assertNull(unsupported.energyCounterNwh)
        assertTrue(unsupported.fieldSources.isEmpty())
        assertEquals(
            listOf(
                "-1",
                Int.MIN_VALUE.toString(),
                Int.MIN_VALUE.toString(),
                Long.MIN_VALUE.toString(),
                Int.MIN_VALUE.toString(),
                Int.MIN_VALUE.toString()
            ), unsupported.metadataValues
        )
    }

    @Test
    fun `public battery estimates preserve unsupported raw returns query each field once and allow independent fallbacks`() {
        val calls = mutableListOf<Int>()
        var chargeTimeCalls = 0
        val snapshot = AdvancedBatterySnapshot()
        AdvancedBatteryStatsCollector.collectBatteryManagerReadings(snapshot, { id ->
            calls += id
            if (id == 10 || id == 9) -1 else Int.MIN_VALUE
        }, { id ->
            calls += id
            Long.MIN_VALUE
        }, {
            chargeTimeCalls++
            -1L
        }, collectChargingPolicy = true)
        assertEquals(
            listOf(
                BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER,
                BatteryManager.BATTERY_PROPERTY_CURRENT_NOW,
                BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE,
                BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER,
                BatteryManager.BATTERY_PROPERTY_CAPACITY,
                10,
                9
            ), calls
        )
        assertEquals(1, chargeTimeCalls)
        val raw = snapshot.metadataLabels.zip(snapshot.metadataValues).toMap()
        assertEquals(Int.MIN_VALUE.toString(), raw["BATTERY_PROPERTY_CAPACITY"])
        assertEquals("-1", raw["BATTERY_PROPERTY_STATE_OF_HEALTH"])
        assertEquals("-1", raw["BATTERY_PROPERTY_CHARGING_POLICY"])
        assertEquals("-1", raw["computeChargeTimeRemaining"])
        assertNull(snapshot.reportedCapacityPercent)
        assertNull(snapshot.stateOfHealthPercent)
        assertNull(snapshot.chargeTimeRemainingMs)
        assertNull(snapshot.chargingPolicy)
        assertTrue(snapshot.fieldSources.isEmpty())

        snapshot.sysfsLabels.addAll(
            listOf(
                "battery/capacity", "battery/state_of_health", "battery/time_to_full_now"
            )
        )
        snapshot.sysfsValues.addAll(listOf("77\n", "81\n", "60\n"))
        AdvancedBatteryStatsCollector.projectCollectedFields(snapshot, emptyMap())
        assertEquals(77, snapshot.reportedCapacityPercent)
        assertEquals(81, snapshot.stateOfHealthPercent)
        assertEquals(60000L, snapshot.chargeTimeRemainingMs)
        assertEquals(
            "/sys/class/power_supply/battery/time_to_full_now",
            snapshot.fieldSources["charge_time_remaining"]
        )
        assertEquals(raw, snapshot.metadataLabels.zip(snapshot.metadataValues).toMap())

        val zero = AdvancedBatterySnapshot()
        AdvancedBatteryStatsCollector.collectBatteryManagerReadings(zero, { id ->
            if (id == BatteryManager.BATTERY_PROPERTY_CAPACITY || id == 10 || id == 9) 0 else Int.MIN_VALUE
        }, { Long.MIN_VALUE }, { 0 }, collectChargingPolicy = true)
        assertEquals(0, zero.reportedCapacityPercent)
        assertEquals(0, zero.stateOfHealthPercent)
        assertEquals(0L, zero.chargeTimeRemainingMs)
        assertEquals("0", zero.chargingPolicy)
        assertEquals(
            "BatteryManager / computeChargeTimeRemaining",
            zero.fieldSources["charge_time_remaining"]
        )
        assertEquals(
            "BatteryManager / BATTERY_PROPERTY_CHARGING_POLICY",
            zero.fieldSources["charging_policy"]
        )

        val failed = AdvancedBatterySnapshot()
        AdvancedBatteryStatsCollector.collectBatteryManagerReadings(failed, { id ->
            when (id) {
                BatteryManager.BATTERY_PROPERTY_CAPACITY -> error("Not available to this app")
                10 -> 101
                9 -> Int.MIN_VALUE
                else -> Int.MIN_VALUE
            }
        }, { 100 }, { 3600000 }, collectChargingPolicy = true)
        assertEquals(100L, failed.energyCounterNwh)
        assertEquals(3600000L, failed.chargeTimeRemainingMs)
        assertNull(failed.reportedCapacityPercent)
        assertNull(failed.stateOfHealthPercent)
        assertNull(failed.chargingPolicy)
        assertFalse(failed.metadataLabels.contains("BATTERY_PROPERTY_CAPACITY"))
        assertEquals(
            "101",
            failed.metadataValues[failed.metadataLabels.indexOf("BATTERY_PROPERTY_STATE_OF_HEALTH")]
        )
        assertEquals(
            Int.MIN_VALUE.toString(),
            failed.metadataValues[failed.metadataLabels.indexOf("BATTERY_PROPERTY_CHARGING_POLICY")]
        )
    }

    @Test
    fun `unsupported current sentinels and negative energy remain raw while valid battery file readings provide fallback`() {
        val outputs = linkedMapOf(
            "cmd battery get -f current_now 2>/dev/null" to " ${Int.MIN_VALUE}\n",
            "cmd battery get current_now 2>/dev/null" to "${Long.MIN_VALUE}\n",
            "cmd battery get -f current_average 2>/dev/null" to " ${Long.MIN_VALUE}\n",
            "cmd battery get current_average 2>/dev/null" to "${Int.MIN_VALUE}\n",
            "cmd battery get energy_counter 2>/dev/null" to " -1\n",
            "ls /sys/class/power_supply 2>/dev/null" to "battery\nbms",
            "ls /sys/class/power_supply/battery 2>/dev/null" to "type\ncurrent_now\ncurrent_avg",
            "ls /sys/class/power_supply/bms 2>/dev/null" to "type\ncurrent_now\ncurrent_avg",
            "cat /sys/class/power_supply/battery/type 2>/dev/null" to "Battery\n",
            "cat /sys/class/power_supply/battery/current_now 2>/dev/null" to "${Int.MIN_VALUE}\n",
            "cat /sys/class/power_supply/battery/current_avg 2>/dev/null" to " ${Long.MIN_VALUE}\n",
            "cat /sys/class/power_supply/bms/type 2>/dev/null" to "BMS\n",
            "cat /sys/class/power_supply/bms/current_now 2>/dev/null" to "0\n",
            "cat /sys/class/power_supply/bms/current_avg 2>/dev/null" to " -500000\n"
        )
        val snapshot = AdvancedBatteryStatsCollector.collect(object : CommandExecutor {
            override fun run(command: String) = outputs[command]?.trim()
            override fun runRaw(command: String) = outputs[command]
        }, AdvancedBatterySnapshot.ACCESS_ROOT, 0, null, false)
        assertEquals(0L, snapshot.currentNowUa)
        assertEquals(-500000L, snapshot.currentAverageUa)
        assertNull(snapshot.energyCounterNwh)
        assertNull(snapshot.fieldSources["energy_counter"])
        assertEquals(
            "/sys/class/power_supply/bms/current_now", snapshot.fieldSources["current_now"]
        )
        assertEquals(
            "/sys/class/power_supply/bms/current_avg", snapshot.fieldSources["current_average"]
        )
        assertEquals(
            outputs.filterKeys { it.startsWith("cmd battery ") }
                .mapKeys { it.key.substringBefore(" 2>/dev/null") },
            snapshot.metadataLabels.zip(snapshot.metadataValues).toMap()
        )
        assertEquals(
            outputs.filterKeys { it.startsWith("cat /sys/class/power_supply/") }.mapKeys {
                it.key.substringAfter("cat /sys/class/power_supply/")
                    .substringBefore(" 2>/dev/null")
            }, snapshot.sysfsLabels.zip(snapshot.sysfsValues).toMap()
        )
    }

    @Test
    fun `negative charge counters from commands dumpsys and files remain raw without becoming remaining charge`() {
        val outputs = mapOf(
            "cmd battery get charge_counter 2>/dev/null" to " -1\n",
            "dumpsys battery" to "Current Battery Service state:\n  Charge counter: -2\n",
            "ls /sys/class/power_supply 2>/dev/null" to "battery",
            "ls /sys/class/power_supply/battery 2>/dev/null" to "charge_counter",
            "cat /sys/class/power_supply/battery/charge_counter 2>/dev/null" to " -3\n"
        )
        val snapshot = AdvancedBatteryStatsCollector.collect(object : CommandExecutor {
            override fun run(command: String) = outputs[command]?.trim()
            override fun runRaw(command: String) = outputs[command]
        }, "App UID", 12345, null, false)
        assertNull(snapshot.chargeCounterUah)
        assertNull(snapshot.fieldSources["charge_counter"])
        assertEquals(
            " -1\n",
            snapshot.metadataValues[snapshot.metadataLabels.indexOf("cmd battery get charge_counter")]
        )
        assertEquals("-2", snapshot.serviceValues[snapshot.serviceLabels.indexOf("Charge counter")])
        assertEquals(listOf(" -3\n"), snapshot.sysfsValues)
    }

    @Test
    fun `public hardware charging status and capacity levels retain their enum sources and raw extras`() {
        for (value in 0..5) {
            val fields = mapOf(
                BatteryManager.EXTRA_CAPACITY_LEVEL to value.toString(),
                BatteryManager.EXTRA_CHARGING_STATUS to value.toString(),
                "status" to "2"
            )
            val snapshot = AdvancedBatterySnapshot().apply {
                serviceLabels.addAll(fields.keys)
                serviceValues.addAll(fields.values)
                chargingState = "2"
                fieldSources["charging_state"] = "ACTION_BATTERY_CHANGED / status"
            }
            AdvancedBatteryStatsCollector.projectBatteryIntentFields(snapshot, fields)
            assertEquals(value.toString(), snapshot.capacityLevel)
            assertEquals(value.toString(), snapshot.chargingState)
            assertEquals(
                "ACTION_BATTERY_CHANGED / ${BatteryManager.EXTRA_CAPACITY_LEVEL}",
                snapshot.fieldSources["capacity_level"]
            )
            assertEquals(
                "ACTION_BATTERY_CHANGED / ${BatteryManager.EXTRA_CHARGING_STATUS}",
                snapshot.fieldSources["charging_state"]
            )
            assertEquals(fields, snapshot.serviceLabels.zip(snapshot.serviceValues).toMap())
        }
        for (value in listOf("-1", "6", "not an enum")) {
            val snapshot = AdvancedBatterySnapshot()
            AdvancedBatteryStatsCollector.projectBatteryIntentFields(
                snapshot, mapOf(
                    BatteryManager.EXTRA_CAPACITY_LEVEL to value,
                    BatteryManager.EXTRA_CHARGING_STATUS to value,
                    "status" to "3"
                )
            )
            assertNull(snapshot.capacityLevel)
            assertEquals("3", snapshot.chargingState)
            assertEquals("ACTION_BATTERY_CHANGED / status", snapshot.fieldSources["charging_state"])
        }
    }

    @Test
    fun `named fuel gauges provide distinct current readings ahead of BMS aliases while unknown and charger nodes stay raw`() {
        val files = linkedMapOf(
            "usb/type" to "USB\n",
            "usb/current_now" to "2000000\n",
            "usb/current_avg" to "1500000\n",
            "mystery/type" to "Unknown\n",
            "mystery/current_now" to "5000000\n",
            "mystery/current_avg" to "4000000\n",
            "vendor-fuelgauge/type" to "Unknown\n",
            "vendor-fuelgauge/current_now" to " -293000\n",
            "vendor-fuelgauge/current_avg" to " -500000\n",
            "oem_fg/type" to "BMS\n",
            "oem_fg/current_now" to "-600000\n",
            "oem_fg/current_avg" to "-700000\n"
        )
        val snapshot = AdvancedBatterySnapshot().apply {
            sysfsLabels.addAll(files.keys)
            sysfsValues.addAll(files.values)
        }
        AdvancedBatteryStatsCollector.projectCollectedFields(snapshot, emptyMap())
        assertEquals(-293000L, snapshot.currentNowUa)
        assertEquals(-500000L, snapshot.currentAverageUa)
        assertEquals(
            "/sys/class/power_supply/vendor-fuelgauge/current_now",
            snapshot.fieldSources["current_now"]
        )
        assertEquals(
            "/sys/class/power_supply/vendor-fuelgauge/current_avg",
            snapshot.fieldSources["current_average"]
        )
        assertEquals(files, snapshot.sysfsLabels.zip(snapshot.sysfsValues).toMap())

        val alias = AdvancedBatterySnapshot().apply {
            val aliasFields = files.filterKeys { !it.startsWith("vendor-fuelgauge/") }
            sysfsLabels.addAll(aliasFields.keys)
            sysfsValues.addAll(aliasFields.values)
        }
        AdvancedBatteryStatsCollector.projectCollectedFields(alias, emptyMap())
        assertEquals(-600000L, alias.currentNowUa)
        assertEquals(-700000L, alias.currentAverageUa)
        assertEquals(
            "/sys/class/power_supply/oem_fg/current_avg", alias.fieldSources["current_average"]
        )

        snapshot.currentNowUa = -111000
        snapshot.currentAverageUa = -222000
        snapshot.fieldSources["current_now"] = "BatteryManager / BATTERY_PROPERTY_CURRENT_NOW"
        snapshot.fieldSources["current_average"] =
            "BatteryManager / BATTERY_PROPERTY_CURRENT_AVERAGE"
        AdvancedBatteryStatsCollector.projectCollectedFields(snapshot, emptyMap())
        assertEquals(-111000L, snapshot.currentNowUa)
        assertEquals(-222000L, snapshot.currentAverageUa)
        assertEquals(
            "BatteryManager / BATTERY_PROPERTY_CURRENT_NOW", snapshot.fieldSources["current_now"]
        )
        assertEquals(
            "BatteryManager / BATTERY_PROPERTY_CURRENT_AVERAGE",
            snapshot.fieldSources["current_average"]
        )
    }

    @Test
    fun `collected battery files fill normalized fields and matching capacities without using charger nodes or modifying raw values`() {
        val files = linkedMapOf(
            "battery/type" to "USB\n",
            "battery/charge_counter" to "9999999\n",
            "usb/type" to "USB\n",
            "usb/current_now" to "2000000\n",
            "usb/capacity" to "100\n",
            "ac/type" to "Mains\n",
            "ac/current_avg" to "3000000\n",
            "pack/type" to "Battery\n",
            "pack/charge_counter" to " 789000\n",
            "pack/current_now" to " -4524000\n",
            "pack/current_avg" to "-4000000\n",
            "pack/capacity" to "0\n",
            "pack/status" to "Charging\n",
            "pack/capacity_level" to "Normal\n",
            "pack/cycle_count" to "0\n",
            "pack/charge_full" to "3500000\n",
            "bms/type" to "Unknown\n",
            "bms/charge_full" to "3000000\n",
            "bms/charge_full_design" to "4000000\n"
        )
        val snapshot = AdvancedBatteryStatsCollector.collect(object : CommandExecutor {
            override fun run(command: String) = runRaw(command)?.trim()
            override fun runRaw(command: String): String? = when {
                command == "ls /sys/class/power_supply 2>/dev/null" -> files.keys.map {
                    it.substringBefore(
                        '/'
                    )
                }.distinct().joinToString("\n")

                command.startsWith("ls /sys/class/power_supply/") -> {
                    val supply =
                        command.substringAfter("ls /sys/class/power_supply/").substringBefore(' ')
                    files.keys.filter { it.startsWith("$supply/") }
                        .joinToString("\n") { it.substringAfter('/') }
                }

                command.startsWith("cat /sys/class/power_supply/") -> files[command.substringAfter("cat /sys/class/power_supply/")
                    .substringBefore(' ')]

                else -> null
            }
        }, AdvancedBatterySnapshot.ACCESS_ROOT, 0, null, false)

        assertEquals(789000L, snapshot.chargeCounterUah)
        assertEquals(-4524000L, snapshot.currentNowUa)
        assertEquals(-4000000L, snapshot.currentAverageUa)
        assertEquals(0, snapshot.reportedCapacityPercent)
        assertEquals("Charging", snapshot.chargingState)
        assertEquals("Normal", snapshot.capacityLevel)
        assertNull(snapshot.cycleCount)
        assertEquals(3000000L, snapshot.fullChargeUah)
        assertEquals(4000000L, snapshot.designChargeUah)
        assertEquals(
            "/sys/class/power_supply/pack/charge_counter", snapshot.fieldSources["charge_counter"]
        )
        assertEquals(
            "/sys/class/power_supply/pack/current_now", snapshot.fieldSources["current_now"]
        )
        assertEquals(
            "/sys/class/power_supply/pack/current_avg", snapshot.fieldSources["current_average"]
        )
        assertEquals("/sys/class/power_supply/pack/status", snapshot.fieldSources["charging_state"])
        assertEquals(
            "/sys/class/power_supply/bms/charge_full", snapshot.fieldSources["charge_full"]
        )
        assertEquals(
            "/sys/class/power_supply/bms/charge_full_design",
            snapshot.fieldSources["charge_full_design"]
        )
        assertEquals(files, snapshot.sysfsLabels.zip(snapshot.sysfsValues).toMap())
    }

    @Test
    fun `projection preserves existing API values and sources while using only valid standard fallbacks`() {
        val snapshot = AdvancedBatterySnapshot().apply {
            chargeCounterUah = 2651000
            currentNowUa = -493000
            currentAverageUa = -500000
            reportedCapacityPercent = 73
            chargingState = "2"
            capacityLevel = "High"
            cycleCount = 12
            fullChargeUah = 3500000
            designChargeUah = 4000000
            listOf(
                "charge_counter",
                "current_now",
                "current_average",
                "capacity",
                "charging_state",
                "capacity_level",
                "cycle_count",
                "charge_full",
                "charge_full_design"
            ).forEach {
                fieldSources[it] = "BatteryManager / $it"
            }
            sysfsLabels.addAll(
                listOf(
                    "battery/charge_now",
                    "battery/current_now",
                    "battery/current_avg",
                    "battery/capacity",
                    "battery/status",
                    "battery/capacity_level",
                    "battery/cycle_count",
                    "battery/charge_full",
                    "battery/charge_full_design"
                )
            )
            sysfsValues.addAll(
                listOf(
                    "123000\n",
                    "456000\n",
                    "789000\n",
                    "25\n",
                    "Discharging\n",
                    "Low\n",
                    "90\n",
                    "1000000\n",
                    "2000000\n"
                )
            )
        }
        val originalSources = snapshot.fieldSources.toMap()
        val originalRaw = snapshot.sysfsLabels.zip(snapshot.sysfsValues)
        AdvancedBatteryStatsCollector.projectCollectedFields(
            snapshot, mapOf("level" to "25", "scale" to "100", "status" to "3")
        )
        assertEquals(2651000L, snapshot.chargeCounterUah)
        assertEquals(-493000L, snapshot.currentNowUa)
        assertEquals(-500000L, snapshot.currentAverageUa)
        assertEquals(73, snapshot.reportedCapacityPercent)
        assertEquals("2", snapshot.chargingState)
        assertEquals("High", snapshot.capacityLevel)
        assertEquals(12L, snapshot.cycleCount)
        assertEquals(3500000L, snapshot.fullChargeUah)
        assertEquals(4000000L, snapshot.designChargeUah)
        assertEquals(originalSources, snapshot.fieldSources)
        assertEquals(originalRaw, snapshot.sysfsLabels.zip(snapshot.sysfsValues))

        val fallback = AdvancedBatterySnapshot().apply {
            sysfsLabels.addAll(
                listOf(
                    "battery/charge_now",
                    "bms/charge_now",
                    "bms/charge_now_raw",
                    "bms/capacity",
                    "bms/cycle_count"
                )
            )
            sysfsValues.addAll(listOf("4974\n", "0\n", "2571662\n", "101\n", "-1\n"))
        }
        val originalFallbackRaw = fallback.sysfsLabels.zip(fallback.sysfsValues)
        AdvancedBatteryStatsCollector.projectCollectedFields(
            fallback, mapOf("level" to "33", "scale" to "50", "status" to "3")
        )
        assertNull(fallback.chargeCounterUah)
        assertNull(fallback.fieldSources["charge_counter"])
        assertEquals(originalFallbackRaw, fallback.sysfsLabels.zip(fallback.sysfsValues))
        assertEquals(66, fallback.reportedCapacityPercent)
        assertEquals("dumpsys battery / level / scale", fallback.fieldSources["capacity"])
        assertEquals("3", fallback.chargingState)
        assertEquals("dumpsys battery / status", fallback.fieldSources["charging_state"])
        assertNull(fallback.cycleCount)

        val invalid = AdvancedBatterySnapshot().apply {
            sysfsLabels.addAll(
                listOf(
                    "battery/charge_now", "battery/capacity", "battery/cycle_count"
                )
            )
            sysfsValues.addAll(listOf("-1\n", "101\n", "-1\n"))
        }
        AdvancedBatteryStatsCollector.projectCollectedFields(
            invalid, mapOf("level" to "1", "scale" to "0", "status" to "-1")
        )
        assertNull(invalid.chargeCounterUah)
        assertNull(invalid.reportedCapacityPercent)
        assertNull(invalid.chargingState)
        assertNull(invalid.cycleCount)
    }

    @Test
    fun `unsupported dumpsys charging readings stay raw and allow genuine fallbacks while reported zero stays available`() {
        val dump = """
            Current Battery Service state:
              Max charging current: -1
              Max charging voltage: -2
              Charging policy: -1
              Charging state: -1
              capacity level: -1
        """.trimIndent()
        val outputs = mapOf(
            "dumpsys battery" to dump,
            "ls /sys/class/power_supply 2>/dev/null" to "battery\n",
            "ls /sys/class/power_supply/battery 2>/dev/null" to "status\ncapacity_level\ncycle_count\n",
            "cat /sys/class/power_supply/battery/status 2>/dev/null" to "Charging\n",
            "cat /sys/class/power_supply/battery/capacity_level 2>/dev/null" to "High\n",
            "cat /sys/class/power_supply/battery/cycle_count 2>/dev/null" to "0\n"
        )
        val snapshot = AdvancedBatteryStatsCollector.collect(object : CommandExecutor {
            override fun run(command: String) = outputs[command]?.trim()
            override fun runRaw(command: String) = outputs[command]
        }, AdvancedBatterySnapshot.ACCESS_ROOT, 0, null, false)
        assertNull(snapshot.maxChargingCurrentUa)
        assertNull(snapshot.maxChargingVoltageUv)
        assertNull(snapshot.chargingPolicy)
        assertNull(snapshot.fieldSources["max_charging_current"])
        assertNull(snapshot.fieldSources["max_charging_voltage"])
        assertNull(snapshot.fieldSources["charging_policy"])
        assertEquals("Charging", snapshot.chargingState)
        assertEquals("High", snapshot.capacityLevel)
        assertEquals(
            "/sys/class/power_supply/battery/status", snapshot.fieldSources["charging_state"]
        )
        assertEquals(
            "/sys/class/power_supply/battery/capacity_level",
            snapshot.fieldSources["capacity_level"]
        )
        assertNull(snapshot.cycleCount)
        assertNull(snapshot.fieldSources["cycle_count"])
        assertEquals(
            "0\n", snapshot.sysfsValues[snapshot.sysfsLabels.indexOf("battery/cycle_count")]
        )
        val rawService = mapOf(
            "Max charging current" to "-1",
            "Max charging voltage" to "-2",
            "Charging policy" to "-1",
            "Charging state" to "-1",
            "capacity level" to "-1"
        )
        assertEquals(rawService, snapshot.serviceLabels.zip(snapshot.serviceValues).toMap())

        AdvancedBatteryStatsCollector.projectBatteryIntentFields(
            snapshot,
            mapOf("max_charging_current" to "2000000", "max_charging_voltage" to "5000000")
        )
        assertEquals(2000000L, snapshot.maxChargingCurrentUa)
        assertEquals(5000000L, snapshot.maxChargingVoltageUv)
        assertEquals(
            "ACTION_BATTERY_CHANGED / max_charging_current",
            snapshot.fieldSources["max_charging_current"]
        )
        assertEquals(
            "ACTION_BATTERY_CHANGED / max_charging_voltage",
            snapshot.fieldSources["max_charging_voltage"]
        )
        assertEquals(rawService, snapshot.serviceLabels.zip(snapshot.serviceValues).toMap())

        val zero = AdvancedBatteryStatsCollector.collect(object : CommandExecutor {
            override fun run(command: String) =
                if (command == "dumpsys battery") dump.replace("-1", "0")
                    .replace("-2", "0") else null
        }, AdvancedBatterySnapshot.ACCESS_ROOT, 0, null, false)
        assertEquals(0L, zero.maxChargingCurrentUa)
        assertEquals(0L, zero.maxChargingVoltageUv)
        assertEquals("0", zero.chargingPolicy)
        assertEquals("0", zero.chargingState)
        assertEquals("0", zero.capacityLevel)
        assertEquals("dumpsys battery / Charging state", zero.fieldSources["charging_state"])
        assertEquals("dumpsys battery / capacity level", zero.fieldSources["capacity_level"])
    }

    @Test
    fun `standard battery energy health and charge time fallbacks use documented units reject overflow and preserve raw readings and API precedence`() {
        val files = linkedMapOf(
            "usb/type" to "USB\n",
            "usb/energy_now" to "123\n",
            "usb/state_of_health" to "99\n",
            "usb/time_to_full_now" to "1\n",
            "battery/energy_now" to "${Long.MAX_VALUE / 1000 + 1}\n",
            "battery/state_of_health" to "101\n",
            "battery/time_to_full_now" to "${Long.MAX_VALUE}\n",
            "battery/cycle_count" to "0\n",
            "bms/energy_now" to " 7654321\n",
            "bms/state_of_health" to "88\n",
            "bms/time_to_full_now" to "123\n",
            "bms/cycle_count" to "2\n"
        )
        val snapshot = AdvancedBatterySnapshot().apply {
            sysfsLabels.addAll(files.keys)
            sysfsValues.addAll(files.values)
        }
        AdvancedBatteryStatsCollector.projectCollectedFields(snapshot, emptyMap())
        assertEquals(7654321000L, snapshot.energyCounterNwh)
        assertEquals(88, snapshot.stateOfHealthPercent)
        assertEquals(123000L, snapshot.chargeTimeRemainingMs)
        assertEquals(2L, snapshot.cycleCount)
        assertEquals(
            "/sys/class/power_supply/bms/energy_now", snapshot.fieldSources["energy_counter"]
        )
        assertEquals(
            "/sys/class/power_supply/bms/state_of_health", snapshot.fieldSources["state_of_health"]
        )
        assertEquals(
            "/sys/class/power_supply/bms/time_to_full_now",
            snapshot.fieldSources["charge_time_remaining"]
        )
        assertEquals(
            "/sys/class/power_supply/bms/cycle_count", snapshot.fieldSources["cycle_count"]
        )
        assertEquals(files, snapshot.sysfsLabels.zip(snapshot.sysfsValues).toMap())

        val api = AdvancedBatterySnapshot().apply {
            energyCounterNwh = 0
            stateOfHealthPercent = 0
            chargeTimeRemainingMs = 0
            cycleCount = 0
            listOf(
                "energy_counter", "state_of_health", "charge_time_remaining", "cycle_count"
            ).forEach {
                fieldSources[it] = "BatteryManager / $it"
            }
            sysfsLabels.addAll(files.keys)
            sysfsValues.addAll(files.values)
        }
        val apiSources = api.fieldSources.toMap()
        AdvancedBatteryStatsCollector.projectCollectedFields(api, emptyMap())
        assertEquals(0L, api.energyCounterNwh)
        assertEquals(0, api.stateOfHealthPercent)
        assertEquals(0L, api.chargeTimeRemainingMs)
        assertEquals(0L, api.cycleCount)
        assertEquals(apiSources, api.fieldSources)
        assertEquals(files, api.sysfsLabels.zip(api.sysfsValues).toMap())

        for (value in listOf(-1L, 0L, Long.MAX_VALUE / 1000, Long.MAX_VALUE / 1000 + 1)) {
            val edge = AdvancedBatterySnapshot().apply {
                sysfsLabels.addAll(
                    listOf(
                        "battery/energy_now",
                        "battery/time_to_full_now",
                        "battery/state_of_health",
                        "battery/cycle_count"
                    )
                )
                sysfsValues.addAll(listOf("$value\n", "$value\n", "$value\n", "0\n"))
            }
            val raw = edge.sysfsLabels.zip(edge.sysfsValues)
            AdvancedBatteryStatsCollector.projectCollectedFields(edge, emptyMap())
            val expected =
                value.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1000 }?.let { it * 1000 }
            assertEquals(expected, edge.energyCounterNwh)
            assertEquals(expected, edge.chargeTimeRemainingMs)
            assertEquals(value.takeIf { it in 0..100 }?.toInt(), edge.stateOfHealthPercent)
            assertNull(edge.cycleCount)
            if (expected == null) {
                assertNull(edge.fieldSources["energy_counter"])
                assertNull(edge.fieldSources["charge_time_remaining"])
            }
            assertEquals(raw, edge.sysfsLabels.zip(edge.sysfsValues))
        }
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
