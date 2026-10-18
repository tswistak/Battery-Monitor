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
*/
package codes.swistak.batterymonitor.monitoring

import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class PrivilegedBatteryReaderTest {
    @Test
    fun `projection keeps acquisition metadata paired with each selected exact source`() {
        val serviceTime = BatteryMeasurementTime(1_000, 100)
        val nowTime = BatteryMeasurementTime(1_100, 200)
        val averageTime = BatteryMeasurementTime(1_200, 300)
        val snapshot = snapshot(
            service = mapOf("health" to "2", "temperature" to "0", "voltage" to "3890"),
            battery = mapOf("current_now" to "2000", "current_avg" to "-1000")
        ).apply {
            capturedAtMillis = 9_000
            currentNowUa = -2_000
            currentAverageUa = Long.MIN_VALUE
            fieldSources["current_now"] = "cmd battery get current_now"
            fieldSources["current_average"] = "cmd battery get current_average"
            sourceReadTimes["dumpsys battery / health"] = serviceTime
            sourceReadTimes["dumpsys battery / temperature"] = serviceTime
            sourceReadTimes["dumpsys battery / voltage"] = serviceTime
            sourceReadTimes["cmd battery get current_now"] = nowTime
            sourceReadTimes["cmd battery get current_average"] = nowTime
            sourceReadTimes["/sys/class/power_supply/battery/current_now"] = averageTime
            sourceReadTimes["/sys/class/power_supply/battery/current_avg"] = averageTime
        }

        val reading = projectPrivilegedBatteryReading(snapshot)

        assertEquals(-2_000L, reading.currentNowUa)
        assertEquals(-1_000L, reading.currentAverageUa)
        assertEquals("cmd battery get current_now", reading.fieldSources["current_now"])
        assertEquals(
            "/sys/class/power_supply/battery/current_avg", reading.fieldSources["current_average"]
        )
        assertEquals(
            mapOf(
                "health" to serviceTime,
                "temperature" to serviceTime,
                "voltage" to serviceTime,
                "current_now" to nowTime,
                "current_average" to averageTime
            ), reading.fieldMeasurementTimes
        )
    }

    @Test
    fun `projection leaves missing acquisition metadata unknown despite snapshot completion time`() {
        val snapshot = snapshot(service = mapOf("voltage" to "3900")).apply {
            capturedAtMillis = 9_000
            sourceReadTimes["/sys/class/power_supply/battery/voltage_now"] =
                BatteryMeasurementTime(1_000, 100)
        }

        val reading = projectPrivilegedBatteryReading(snapshot)

        assertEquals(3900, reading.voltageMillivolts)
        assertEquals("dumpsys battery / voltage", reading.fieldSources["voltage"])
        assertTrue(reading.fieldMeasurementTimes.isEmpty())
    }

    @Test
    fun `measurement presence ignores metadata and preserves valid zero readings`() {
        val metadataOnly = PrivilegedBatteryReading(
            fieldSources = mapOf("current_now" to "cmd battery get current_now"),
            fieldMeasurementTimes = mapOf("current_now" to BatteryMeasurementTime(1_000, 100))
        )
        assertFalse(PrivilegedBatteryReading().hasMeasurements())
        assertFalse(metadataOnly.hasMeasurements())
        for (reading in listOf(
            PrivilegedBatteryReading(percent = 0),
            PrivilegedBatteryReading(status = 1),
            PrivilegedBatteryReading(health = 1),
            PrivilegedBatteryReading(plugged = 0),
            PrivilegedBatteryReading(temperatureTenthsC = 0),
            PrivilegedBatteryReading(voltageMillivolts = 3900),
            PrivilegedBatteryReading(remainingChargeUah = 0),
            PrivilegedBatteryReading(currentNowUa = 0),
            PrivilegedBatteryReading(currentAverageUa = 0)
        )) {
            assertTrue(reading.hasMeasurements())
        }
    }

    @Test
    fun `cached acquisition times survive lookup and failed refresh and same values acquire a new time`() {
        val harness = Harness()
        val firstTime = BatteryMeasurementTime(5_000, 0)
        val secondTime = BatteryMeasurementTime(4_000, 2_000)
        fun currentSnapshot(time: BatteryMeasurementTime) = snapshot().apply {
            currentNowUa = -200_000
            fieldSources["current_now"] = "cmd battery get current_now"
            sourceReadTimes["cmd battery get current_now"] = time
        }
        harness.nextSnapshot = currentSnapshot(firstTime)
        harness.reader.read()
        harness.executor.runNext()
        harness.now = 1_999
        assertEquals(firstTime, harness.reader.read()?.fieldMeasurementTimes?.get("current_now"))
        assertTrue(harness.executor.tasks.isEmpty())

        harness.now = 2_000
        harness.nextSnapshot = currentSnapshot(secondTime)
        assertEquals(firstTime, harness.reader.read()?.fieldMeasurementTimes?.get("current_now"))
        harness.executor.runNext()
        val refreshed = harness.reader.read()
        assertEquals(-200_000L, refreshed?.currentNowUa)
        assertEquals(secondTime, refreshed?.fieldMeasurementTimes?.get("current_now"))

        harness.now = 4_000
        harness.duringCollection = { throw IllegalStateException("Unavailable") }
        harness.reader.read()
        harness.executor.runNext()
        assertEquals(secondTime, harness.reader.read()?.fieldMeasurementTimes?.get("current_now"))
        harness.now = 12_001
        assertNull(harness.reader.read())
    }

    @Test
    fun `metadata-only snapshots release waiters without becoming an available cached reading`() {
        val harness = Harness()
        val results = mutableListOf<PrivilegedBatteryReading?>()
        harness.nextSnapshot = snapshot().apply {
            capturedAtMillis = 9_000
            fieldSources["current_now"] = "cmd battery get current_now"
            sourceReadTimes["cmd battery get current_now"] = BatteryMeasurementTime(1_000, 100)
        }

        harness.reader.readWhenReady { results += it }
        harness.executor.runNext()

        assertEquals(listOf<PrivilegedBatteryReading?>(null), results)
        assertNull(harness.reader.read())
        assertTrue(harness.executor.tasks.isEmpty())
    }

    @Test
    fun `idle barrier completes after the earlier collection and its waiting callback`() {
        val harness = Harness()
        val events = mutableListOf<String>()
        harness.reader.readWhenReady { events += "reading" }
        val idle = harness.reader.whenIdle()
        idle.thenRun { events += "idle" }

        assertFalse(idle.isDone)
        assertEquals(2, harness.executor.tasks.size)
        harness.executor.runNext()
        assertEquals(listOf("reading"), events)
        assertFalse(idle.isDone)
        harness.executor.runNext()
        assertTrue(idle.isDone)
        assertEquals(listOf("reading", "idle"), events)
    }

    @Test
    fun `idle barrier resolves if its executor has already stopped`() {
        val reader = PrivilegedBatteryReader(
            collector = { error("Barrier must not collect") },
            enabled = { true },
            accessRevision = { 0 },
            backgroundExecutor = { throw RejectedExecutionException("Stopped") },
            clock = { 0 })

        assertTrue(reader.whenIdle().isDone)
    }

    @Test
    fun `projection preserves API and service readings ahead of sysfs including valid zero values`() {
        val snapshot = snapshot(
            service = mapOf(
                "level" to "44",
                "scale" to "50",
                "status" to "2",
                "health" to "2",
                "temperature" to "0",
                "voltage" to "3890",
                "plugged" to "0"
            ), battery = mapOf(
                "capacity" to "33",
                "status" to "Discharging",
                "health" to "Cold",
                "temp" to "258",
                "voltage_now" to "3845000",
                "current_now" to "2000",
                "current_avg" to "1000"
            )
        ).apply {
            reportedCapacityPercent = 80
            currentNowUa = -2000
            currentAverageUa = -1000
            chargeCounterUah = 2500000
            fieldSources["current_now"] = "cmd battery get current_now"
            fieldSources["current_average"] = "BatteryManager / BATTERY_PROPERTY_CURRENT_AVERAGE"
            fieldSources["charge_counter"] = "BatteryManager / BATTERY_PROPERTY_CHARGE_COUNTER"
        }

        assertEquals(
            PrivilegedBatteryReading(
                80, 2, 2, 0, 0, 3890, 2500000, -2000, -1000, fieldSources = mapOf(
                    "health" to "dumpsys battery / health",
                    "temperature" to "dumpsys battery / temperature",
                    "voltage" to "dumpsys battery / voltage",
                    "charge_counter" to "BatteryManager / BATTERY_PROPERTY_CHARGE_COUNTER",
                    "current_now" to "cmd battery get current_now",
                    "current_average" to "BatteryManager / BATTERY_PROPERTY_CURRENT_AVERAGE"
                )
            ), projectPrivilegedBatteryReading(snapshot)
        )
    }

    @Test
    fun `projection normalizes battery sysfs and replaces unknown service enums`() {
        val snapshot = snapshot(
            service = mapOf(
                "level" to "50",
                "scale" to "0",
                "status" to "1",
                "health" to "1",
                "temperature" to Int.MAX_VALUE.toString(),
                "voltage" to "3"
            ), battery = mapOf(
                "capacity" to "57",
                "status" to "Discharging",
                "health" to "Good",
                "temp" to "258",
                "voltage_now" to "3845000",
                "charge_counter" to "0",
                "current_now" to "0",
                "current_avg" to "-600"
            )
        )

        assertEquals(
            PrivilegedBatteryReading(
                57, 3, 2, null, 258, 3845, 0, 0, -600, fieldSources = mapOf(
                    "health" to "/sys/class/power_supply/battery/health",
                    "temperature" to "/sys/class/power_supply/battery/temp",
                    "voltage" to "/sys/class/power_supply/battery/voltage_now",
                    "charge_counter" to "/sys/class/power_supply/battery/charge_counter",
                    "current_now" to "/sys/class/power_supply/battery/current_now",
                    "current_average" to "/sys/class/power_supply/battery/current_avg"
                )
            ), projectPrivilegedBatteryReading(snapshot)
        )
    }

    @Test
    fun `projection rejects charger supplies and unrelated hardware enums`() {
        val snapshot = snapshot(
            sysfs = mapOf(
                "usb/type" to "USB",
                "usb/capacity" to "99",
                "usb/voltage_now" to "9000000",
                "usb/current_now" to "3000000",
                "usb/temp" to "300",
                "usb/status" to "Charging",
                "usb/health" to "Good",
                "usb/charge_counter" to "8000000"
            )
        ).apply {
            chargingState = "4"
            stateOfHealthPercent = 95
            currentNowUa = Int.MIN_VALUE.toLong()
            currentAverageUa = Long.MIN_VALUE
            chargeCounterUah = -1
            reportedCapacityPercent = 101
            fieldSources["charge_counter"] = "cmd battery get charge_counter"
            fieldSources["current_now"] = "cmd battery get current_now"
            fieldSources["current_average"] = "cmd battery get current_average"
        }

        assertEquals(PrivilegedBatteryReading(), projectPrivilegedBatteryReading(snapshot))
    }

    @Test
    fun `projection accepts vendor battery supplies and normalizes service level and power flags`() {
        val snapshot = snapshot(
            service = mapOf("level" to "25", "scale" to "50", "USB powered" to "true"),
            sysfs = mapOf(
                "vendor-pack/type" to "Battery",
                "vendor-pack/voltage_now" to "3900",
                "vendor-pack/temp" to "-50",
                "vendor-pack/health" to "Over voltage",
                "vendor-pack/status" to "Not charging"
            )
        )

        assertEquals(
            PrivilegedBatteryReading(
                50, 4, 5, 2, -50, 3900, fieldSources = mapOf(
                    "health" to "/sys/class/power_supply/vendor-pack/health",
                    "temperature" to "/sys/class/power_supply/vendor-pack/temp",
                    "voltage" to "/sys/class/power_supply/vendor-pack/voltage_now"
                )
            ), projectPrivilegedBatteryReading(snapshot)
        )
        snapshot.serviceValues[snapshot.serviceLabels.indexOf("USB powered")] = "false"
        assertNull(projectPrivilegedBatteryReading(snapshot).plugged)
        snapshot.serviceLabels.addAll(listOf("AC powered", "Wireless powered"))
        snapshot.serviceValues.addAll(listOf("false", "false"))
        assertEquals(0, projectPrivilegedBatteryReading(snapshot).plugged)
    }

    @Test
    fun `projection suppresses unconfirmed zero charge but preserves confirmed zeros and positive charge`() {
        val snapshot = snapshot().apply { chargeCounterUah = 0 }
        for (source in listOf(
            "dumpsys battery / Charge counter", "ACTION_BATTERY_CHANGED / charge_counter", ""
        )) {
            snapshot.fieldSources["charge_counter"] = source
            val reading = projectPrivilegedBatteryReading(snapshot)
            assertNull(reading.remainingChargeUah)
            assertNull(reading.fieldSources["charge_counter"])
        }
        for (source in listOf(
            "BatteryManager / BATTERY_PROPERTY_CHARGE_COUNTER",
            "cmd battery get charge_counter",
            "/sys/class/power_supply/battery/charge_counter"
        )) {
            snapshot.fieldSources["charge_counter"] = source
            val reading = projectPrivilegedBatteryReading(snapshot)
            assertEquals(0L, reading.remainingChargeUah)
            assertEquals(source, reading.fieldSources["charge_counter"])
        }
        snapshot.fieldSources.clear()
        snapshot.chargeCounterUah = 1234
        assertEquals(1234L, projectPrivilegedBatteryReading(snapshot).remainingChargeUah)
    }

    @Test
    fun `projection pairs fallback values with the battery supply that passed validation`() {
        val snapshot = snapshot(
            battery = mapOf(
                "health" to "invalid",
                "temp" to "3000",
                "voltage_now" to "3",
                "charge_counter" to "-1",
                "current_now" to Long.MIN_VALUE.toString(),
                "current_avg" to Long.MIN_VALUE.toString()
            ), sysfs = mapOf(
                "bms/type" to "Battery",
                "bms/health" to "Good",
                "bms/temp" to "237",
                "bms/voltage_now" to "3900000",
                "bms/charge_counter" to "400",
                "bms/current_now" to "850",
                "bms/current_avg" to "500"
            )
        ).apply {
            chargeCounterUah = -1
            currentNowUa = Long.MIN_VALUE
            currentAverageUa = Long.MIN_VALUE
            fieldSources["charge_counter"] = "cmd battery get charge_counter"
            fieldSources["current_now"] = "cmd battery get current_now"
            fieldSources["current_average"] = "cmd battery get current_average"
        }

        assertEquals(
            PrivilegedBatteryReading(
                health = 2,
                temperatureTenthsC = 237,
                voltageMillivolts = 3900,
                remainingChargeUah = 400,
                currentNowUa = 850,
                currentAverageUa = 500,
                fieldSources = mapOf(
                    "health" to "/sys/class/power_supply/bms/health",
                    "temperature" to "/sys/class/power_supply/bms/temp",
                    "voltage" to "/sys/class/power_supply/bms/voltage_now",
                    "charge_counter" to "/sys/class/power_supply/bms/charge_counter",
                    "current_now" to "/sys/class/power_supply/bms/current_now",
                    "current_average" to "/sys/class/power_supply/bms/current_avg"
                )
            ), projectPrivilegedBatteryReading(snapshot)
        )
    }

    @Test
    fun `projection leaves unknown typed sources missing rather than borrowing raw sysfs provenance`() {
        val snapshot = snapshot(
            battery = mapOf(
                "charge_counter" to "400", "current_now" to "850", "current_avg" to "500"
            )
        ).apply {
            chargeCounterUah = 1234
            currentNowUa = -100
            currentAverageUa = -200
            fieldSources["current_now"] = " "
            fieldSources["current_avg"] = "/sys/class/power_supply/battery/current_avg"
        }

        assertEquals(
            PrivilegedBatteryReading(
                remainingChargeUah = 1234, currentNowUa = -100, currentAverageUa = -200
            ), projectPrivilegedBatteryReading(snapshot)
        )
    }

    @Test
    fun `unsupported dock power remains unknown instead of appearing unplugged`() {
        val snapshot = snapshot(
            service = mapOf(
                "plugged" to "8",
                "AC powered" to "false",
                "USB powered" to "false",
                "Wireless powered" to "false",
                "Dock powered" to "true"
            )
        )
        assertNull(projectPrivilegedBatteryReading(snapshot).plugged)
        assertTrue("plugged" in missingBatteryFields(mapOf("plugged" to 8)))
    }

    @Test
    fun `read schedules collection without executing shell work on its caller`() {
        val harness = Harness()

        assertNull(harness.reader.read())
        assertEquals(0, harness.collections)
        assertEquals(1, harness.executor.tasks.size)
        harness.reader.read()
        assertEquals(1, harness.executor.tasks.size)

        harness.executor.runNext()
        assertEquals(1, harness.collections)
        assertEquals(3900, harness.reader.read()?.voltageMillivolts)
    }

    @Test
    fun `fresh cache is served while a throttled background refresh runs`() {
        val harness = Harness()
        harness.reader.read()
        harness.executor.runNext()
        harness.now = 1999
        assertEquals(3900, harness.reader.read()?.voltageMillivolts)
        assertEquals(0, harness.executor.tasks.size)

        harness.now = 2000
        harness.nextSnapshot = snapshot(service = mapOf("voltage" to "4000"))
        assertEquals(3900, harness.reader.read()?.voltageMillivolts)
        assertEquals(1, harness.executor.tasks.size)
        harness.executor.runNext()
        assertEquals(4000, harness.reader.read()?.voltageMillivolts)
    }

    @Test
    fun `slow collection has a cooldown before another read can schedule collection`() {
        val harness = Harness()
        harness.duringCollection = { harness.now += 3000 }
        harness.reader.read()
        harness.executor.runNext()

        assertEquals(3900, harness.reader.read()?.voltageMillivolts)
        assertEquals(0, harness.executor.tasks.size)
        harness.now = 10001
        assertNull(harness.reader.read())
    }

    @Test
    fun `collection taking longer than cache lifetime cannot publish an already stale reading`() {
        val harness = Harness()
        harness.duringCollection = { harness.now += 10001 }
        harness.reader.read()
        harness.executor.runNext()

        assertNull(harness.reader.read())
    }

    @Test
    fun `expired current values are unavailable until refresh completes`() {
        val harness = Harness()
        harness.nextSnapshot = snapshot().apply { currentNowUa = -200000 }
        harness.reader.read()
        harness.executor.runNext()
        harness.now = 10001

        assertNull(harness.reader.read())
        assertEquals(1, harness.executor.tasks.size)
        harness.executor.runNext()
        assertEquals(-200000L, harness.reader.read()?.currentNowUa)
    }

    @Test
    fun `disabling access clears cache and re-enabling starts with an empty cache`() {
        val harness = Harness()
        harness.reader.read()
        harness.executor.runNext()
        harness.accessEnabled = false
        harness.revision++
        assertNull(harness.reader.read())
        assertEquals(0, harness.executor.tasks.size)

        harness.accessEnabled = true
        harness.revision++
        assertNull(harness.reader.read())
        assertEquals(1, harness.executor.tasks.size)
    }

    @Test
    fun `late completion after disable and re-enable cannot publish its old reading`() {
        val harness = Harness()
        harness.duringCollection = {
            harness.accessEnabled = false
            harness.revision++
            harness.accessEnabled = true
            harness.revision++
        }
        harness.reader.read()
        harness.executor.runNext()

        assertNull(harness.reader.read())
        harness.duringCollection = {}
        harness.nextSnapshot = snapshot(service = mapOf("voltage" to "4100"))
        harness.executor.runNext()
        assertEquals(4100, harness.reader.read()?.voltageMillivolts)
    }

    @Test
    fun `superseded queued refresh cannot clear a newer request`() {
        val harness = Harness()
        harness.reader.read()
        harness.accessEnabled = false
        harness.revision++
        harness.reader.read()
        harness.accessEnabled = true
        harness.revision++
        harness.reader.read()
        assertEquals(2, harness.executor.tasks.size)

        harness.executor.runNext()
        harness.reader.read()
        assertEquals(1, harness.executor.tasks.size)
        assertEquals(0, harness.collections)
        harness.executor.runNext()
        assertEquals(3900, harness.reader.read()?.voltageMillivolts)
        assertEquals(1, harness.collections)
    }

    @Test
    fun `failed refresh keeps a recent reading only until its original expiry`() {
        val harness = Harness()
        harness.reader.read()
        harness.executor.runNext()
        harness.duringCollection = { throw IllegalStateException("unavailable") }
        harness.now = 2000
        harness.reader.read()
        harness.executor.runNext()

        assertEquals(3900, harness.reader.read()?.voltageMillivolts)
        harness.now = 10001
        assertNull(harness.reader.read())
    }

    @Test
    fun `cold continuations join one collection and each receive its result`() {
        val harness = Harness()
        val results = mutableListOf<Pair<String, Int?>>()
        harness.reader.read()
        harness.reader.readWhenReady { results += "first" to it?.voltageMillivolts }
        harness.reader.readWhenReady { results += "second" to it?.voltageMillivolts }

        assertEquals(emptyList<Pair<String, Int?>>(), results)
        assertEquals(0, harness.collections)
        assertEquals(1, harness.executor.tasks.size)
        harness.executor.runNext()
        assertEquals(listOf("first" to 3900, "second" to 3900), results)
    }

    @Test
    fun `fresh cache and disabled access complete immediately`() {
        val harness = Harness()
        harness.reader.read()
        harness.executor.runNext()
        val results = mutableListOf<Int?>()
        harness.reader.readWhenReady { results += it?.voltageMillivolts }
        assertEquals(listOf(3900), results)
        assertEquals(0, harness.executor.tasks.size)

        harness.accessEnabled = false
        harness.revision++
        harness.reader.readWhenReady { results += it?.voltageMillivolts }
        assertEquals(listOf(3900, null), results)
        assertEquals(0, harness.executor.tasks.size)
    }

    @Test
    fun `expired event continuation receives the refreshed value before it can log`() {
        val harness = Harness()
        harness.reader.read()
        harness.executor.runNext()
        harness.now = 10001
        harness.nextSnapshot = snapshot(service = mapOf("voltage" to "4100"))
        val results = mutableListOf<Int?>()
        harness.reader.readWhenReady { results += it?.voltageMillivolts }

        assertEquals(emptyList<Int?>(), results)
        harness.executor.runNext()
        assertEquals(listOf(4100), results)
    }

    @Test
    fun `failed and empty collections release continuations and a cold retry bypasses cooldown`() {
        val harness = Harness()
        val results = mutableListOf<PrivilegedBatteryReading?>()
        harness.duringCollection = { throw IllegalStateException("unavailable") }
        harness.reader.readWhenReady { results += it }
        harness.executor.runNext()
        assertEquals(listOf<PrivilegedBatteryReading?>(null), results)

        harness.duringCollection = {}
        harness.nextSnapshot = snapshot()
        harness.reader.readWhenReady { results += it }
        assertEquals(1, harness.executor.tasks.size)
        harness.executor.runNext()
        assertEquals(listOf<PrivilegedBatteryReading?>(null, null), results)
    }

    @Test
    fun `already stale collection releases its event continuation with no reading`() {
        val harness = Harness()
        harness.duringCollection = { harness.now += 10001 }
        val results = mutableListOf<PrivilegedBatteryReading?>()
        harness.reader.readWhenReady { results += it }
        harness.executor.runNext()

        assertEquals(listOf<PrivilegedBatteryReading?>(null), results)
    }

    @Test
    fun `revocation releases all old continuations without consuming a new access request`() {
        val harness = Harness()
        val results = mutableListOf<Pair<String, Int?>>()
        harness.reader.readWhenReady { results += "first" to it?.voltageMillivolts }
        harness.reader.readWhenReady { results += "second" to it?.voltageMillivolts }
        harness.accessEnabled = false
        harness.revision++
        harness.reader.read()
        assertEquals(listOf("first" to null, "second" to null), results)

        harness.accessEnabled = true
        harness.revision++
        harness.reader.readWhenReady { results += "new" to it?.voltageMillivolts }
        harness.executor.runNext()
        assertEquals(listOf("first" to null, "second" to null), results)
        harness.executor.runNext()
        assertEquals(listOf("first" to null, "second" to null, "new" to 3900), results)
    }

    @Test
    fun `revocation during collection releases continuations without a later poll`() {
        val harness = Harness()
        val results = mutableListOf<PrivilegedBatteryReading?>()
        harness.duringCollection = {
            harness.accessEnabled = false
            harness.revision++
        }
        harness.reader.readWhenReady { results += it }
        harness.executor.runNext()

        assertEquals(listOf<PrivilegedBatteryReading?>(null), results)
    }

    @Test
    fun `access changed by one continuation prevents old values reaching the remaining continuations`() {
        val harness = Harness()
        val results = mutableListOf<Int?>()
        harness.reader.readWhenReady {
            results += it?.voltageMillivolts
            harness.revision++
        }
        harness.reader.readWhenReady { results += it?.voltageMillivolts }
        harness.executor.runNext()

        assertEquals(listOf(3900, null), results)
    }

    @Test
    fun `executor rejection completes each cold continuation instead of stranding it`() {
        val reader = PrivilegedBatteryReader(
            collector = { error("must not collect") },
            enabled = { true },
            accessRevision = { 0 },
            backgroundExecutor = { throw RejectedExecutionException("stopped") },
            clock = { 0 })
        val results = mutableListOf<PrivilegedBatteryReading?>()
        reader.readWhenReady { results += it }
        reader.readWhenReady { results += it }

        assertEquals(listOf<PrivilegedBatteryReading?>(null, null), results)
    }

    @Test
    fun `a failing continuation cannot strand other waiting events`() {
        val harness = Harness()
        val results = mutableListOf<Int?>()
        harness.reader.readWhenReady { throw IllegalStateException("consumer failed") }
        harness.reader.readWhenReady { results += it?.voltageMillivolts }
        harness.executor.runNext()

        assertEquals(listOf(3900), results)
    }

    private fun snapshot(
        service: Map<String, String> = emptyMap(),
        battery: Map<String, String> = emptyMap(),
        sysfs: Map<String, String> = emptyMap()
    ) = AdvancedBatterySnapshot().apply {
        serviceLabels.addAll(service.keys)
        serviceValues.addAll(service.values)
        val files =
            if (battery.isEmpty()) sysfs else mapOf("battery/type" to "Battery") + battery.mapKeys { "battery/${it.key}" } + sysfs
        sysfsLabels.addAll(files.keys)
        sysfsValues.addAll(files.values)
    }

    private inner class Harness {
        val executor = QueuedExecutor()
        var now = 0L
        var accessEnabled = true
        var revision = 0L
        var collections = 0
        var nextSnapshot: AdvancedBatterySnapshot? = snapshot(service = mapOf("voltage" to "3900"))
        var duringCollection: () -> Unit = {}
        val reader = PrivilegedBatteryReader(
            collector = {
                collections++
                duringCollection()
                nextSnapshot
            },
            enabled = { accessEnabled },
            accessRevision = { revision },
            backgroundExecutor = executor,
            clock = { now })
    }

    private class QueuedExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }

    @Test
    fun `history requests never reuse cached readings or increase the refresh frequency`() {
        var now = 0L
        var calls = 0
        val tasks = ArrayDeque<Runnable>()
        val reader = PrivilegedBatteryReader(
            collector = {
                calls++
                snapshot().apply {
                    currentNowUa = calls.toLong()
                }
            },
            enabled = { true },
            accessRevision = { 1 },
            backgroundExecutor = { tasks.add(it) },
            clock = { now })
        var result: PrivilegedBatteryReading? = null
        reader.readWhenReady(allowCached = false) { result = it }
        tasks.removeFirst().run()
        assertEquals(1L, result?.currentNowUa)
        now = 100
        reader.readWhenReady(allowCached = false) { result = it }
        assertNull(result)
        assertEquals(1, calls)
        assertEquals(0, tasks.size)
        now = 2000
        reader.readWhenReady(allowCached = false) { result = it }
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(2L, result?.currentNowUa)
        assertEquals(2, calls)
    }

}
