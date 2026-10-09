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
package codes.swistak.batterymonitor.privileged

import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import codes.swistak.batterymonitor.monitoring.PrivilegedBatteryReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class PrivilegedShizukuConnectionTest {
    @Test
    fun `cold monitoring event receives the snapshot after the eligible Shizuku connection becomes ready`() {
        var now = 0L
        var connected = false
        var callbackDelivered = false
        var waits = 0
        val results = mutableListOf<Int?>()
        val reader = PrivilegedBatteryReader(
            collector = {
                val ready = awaitShizukuConnection(
                    timeoutMillis = 4_000,
                    canWait = { true },
                    isConnected = { connected },
                    awaitChange = {
                        assertFalse(callbackDelivered)
                        waits++
                        now += 25
                        connected = true
                    },
                    clock = { now })
                if (ready) AdvancedBatterySnapshot().apply {
                    serviceLabels += "voltage"
                    serviceValues += "3900"
                } else null
            },
            enabled = { true },
            accessRevision = { 0L },
            backgroundExecutor = Executor { it.run() },
            clock = { now })

        reader.readWhenReady {
            callbackDelivered = true
            results += it?.voltageMillivolts
        }

        assertEquals(1, waits)
        assertEquals(listOf(3900), results)
    }

    @Test
    fun `an already connected eligible service does not wait`() {
        assertTrue(
            awaitShizukuConnection(
                timeoutMillis = 4_000,
                canWait = { true },
                isConnected = { true },
                awaitChange = { error("Connected service must not wait") },
                clock = { 0L })
        )
    }

    @Test
    fun `unavailable denied or disabled access cannot wait or accept a stale connected service`() {
        assertFalse(
            awaitShizukuConnection(
                timeoutMillis = 4_000,
                canWait = { false },
                isConnected = { true },
                awaitChange = { error("Ineligible service must not wait") },
                clock = { 0L })
        )
    }

    @Test
    fun `spurious wakeups cannot extend the connection timeout`() {
        var now = 0L
        val waits = mutableListOf<Long>()
        assertFalse(
            awaitShizukuConnection(
                timeoutMillis = 4_000,
                canWait = { true },
                isConnected = { false },
                awaitChange = {
                    waits += it
                    now += 1_000
                },
                clock = { now })
        )
        assertEquals(listOf(4_000L, 3_000L, 2_000L, 1_000L), waits)
    }

    @Test
    fun `revoking access during the wait rejects a simultaneously delivered service`() {
        var revision = 0L
        var connected = false
        assertFalse(
            awaitShizukuConnection(
                timeoutMillis = 4_000,
                canWait = { revision == 0L },
                isConnected = { connected },
                awaitChange = {
                    revision++
                    connected = true
                },
                clock = { 0L })
        )
    }

    @Test
    fun `failed dead or replaced connections stop waiting when their pending connection is cleared`() {
        val expected = Any()
        var pending: Any? = expected
        var waits = 0
        assertFalse(
            awaitShizukuConnection(
                timeoutMillis = 4_000,
                canWait = { pending === expected },
                isConnected = { false },
                awaitChange = {
                    waits++
                    pending = null
                },
                clock = { 0L })
        )
        assertEquals(1, waits)
    }
}
