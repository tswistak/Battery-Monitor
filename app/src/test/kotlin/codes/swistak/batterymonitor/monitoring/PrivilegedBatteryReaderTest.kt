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
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class PrivilegedBatteryReaderTest {
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
        }

        assertEquals(
            PrivilegedBatteryReading(80, 2, 2, 0, 0, 3890, 2500000, -2000, -1000),
            projectPrivilegedBatteryReading(snapshot)
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
            PrivilegedBatteryReading(57, 3, 2, null, 258, 3845, 0, 0, -600),
            projectPrivilegedBatteryReading(snapshot)
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
            PrivilegedBatteryReading(50, 4, 5, 2, -50, 3900),
            projectPrivilegedBatteryReading(snapshot)
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
            assertNull(projectPrivilegedBatteryReading(snapshot).remainingChargeUah)
        }
        for (source in listOf(
            "BatteryManager / BATTERY_PROPERTY_CHARGE_COUNTER",
            "cmd battery get charge_counter",
            "/sys/class/power_supply/battery/charge_counter"
        )) {
            snapshot.fieldSources["charge_counter"] = source
            assertEquals(0L, projectPrivilegedBatteryReading(snapshot).remainingChargeUah)
        }
        snapshot.fieldSources.clear()
        snapshot.chargeCounterUah = 1234
        assertEquals(1234L, projectPrivilegedBatteryReading(snapshot).remainingChargeUah)
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
        org.junit.Assert.assertTrue("plugged" in missingBatteryFields(mapOf("plugged" to 8)))
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
            backgroundExecutor = Executor { throw RejectedExecutionException("stopped") },
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
}
