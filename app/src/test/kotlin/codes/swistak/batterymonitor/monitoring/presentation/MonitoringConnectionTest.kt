/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringConnectionTest {
    private class FakeMessenger(private val connection: MonitoringConnection) {
        fun send(
            generation: Int,
            process: String,
            sequence: Long,
            snapshot: MonitoringSnapshot = snapshot()
        ): Boolean = connection.publishSnapshot(generation, process, sequence, snapshot)
    }

    private companion object {
        fun snapshot(
            status: Int = 2, direction: Int = 2, target: Int = 80
        ) = MonitoringSnapshot(
            levelPercent = 72,
            status = status,
            health = 2,
            plugged = if (status == 0) 0 else 1,
            temperatureTenthsCelsius = 304,
            voltageMillivolts = 3900,
            remainingChargeMicroampHours = 2_100_000,
            lastStatus = status,
            lastPercent = 75,
            lastStatusTimeMillis = 1000,
            configuredPrediction = PredictionSnapshot(direction, target, false, 0, 1, 10),
            fullRangePrediction = PredictionSnapshot(
                direction, if (status == 0) 0 else 100, false, 0, 2, 0
            ),
            observedAtMillis = 1500
        )
    }

    @Test
    fun `delayed snapshot cannot overwrite a newer sequence from the same service process`() {
        val connection = MonitoringConnection()
        connection.start()
        val generation = connection.onServiceConnected()
        assertTrue(connection.onHandshake(generation, "process-a"))
        val messenger = FakeMessenger(connection)

        assertTrue(messenger.send(generation, "process-a", 12))
        assertFalse(messenger.send(generation, "process-a", 11))
        assertEquals(12L, connection.state.value.sequence)
    }

    @Test
    fun `service process death rejects old replies and accepts the new process sequence`() {
        val connection = MonitoringConnection()
        connection.start()
        val oldGeneration = connection.onServiceConnected()
        assertTrue(connection.onHandshake(oldGeneration, "process-a"))
        val messenger = FakeMessenger(connection)
        assertTrue(messenger.send(oldGeneration, "process-a", 42))

        connection.onServiceDisconnected()
        assertFalse(messenger.send(oldGeneration, "process-a", 43))
        val newGeneration = connection.onServiceConnected()
        assertTrue(connection.onHandshake(newGeneration, "process-b"))
        assertFalse(messenger.send(oldGeneration, "process-a", 44))
        assertFalse(messenger.send(newGeneration, "process-a", 44))
        assertTrue(messenger.send(newGeneration, "process-b", 0))
        assertEquals(0L, connection.state.value.sequence)
    }

    @Test
    fun `lifecycle stop and start drops late replies without creating a second subscription`() {
        val connection = MonitoringConnection()
        connection.start()
        connection.start()
        val firstGeneration = connection.onServiceConnected()
        assertEquals(1, firstGeneration)
        assertTrue(connection.onHandshake(firstGeneration, "process-a"))
        val messenger = FakeMessenger(connection)
        assertTrue(messenger.send(firstGeneration, "process-a", 1))

        connection.stop()
        assertEquals(MonitoringAvailability.STALE, connection.state.value.availability)
        assertFalse(messenger.send(firstGeneration, "process-a", 2))
        connection.start()
        val nextGeneration = connection.onServiceConnected()
        assertTrue(connection.onHandshake(nextGeneration, "process-a"))
        assertFalse(messenger.send(firstGeneration, "process-a", 3))
        assertTrue(messenger.send(nextGeneration, "process-a", 2))
    }

    @Test
    fun `missing handshake and invalid sequence do not become live data`() {
        val connection = MonitoringConnection()
        connection.start()
        val generation = connection.onServiceConnected()
        val messenger = FakeMessenger(connection)

        assertFalse(messenger.send(generation, "process-a", 0))
        assertTrue(connection.onHandshake(generation, "process-a"))
        assertFalse(messenger.send(generation, "process-a", -1))
        assertEquals(MonitoringAvailability.WAITING, connection.state.value.availability)
    }

    @Test
    fun `unplug snapshot changes status direction and target in one state emission`() =
        runBlocking {
            val connection = MonitoringConnection()
            connection.start()
            val generation = connection.onServiceConnected()
            assertTrue(connection.onHandshake(generation, "process-a"))
            val messenger = FakeMessenger(connection)
            val emissions = mutableListOf<MonitoringUiState>()
            val collector = launch(Dispatchers.Unconfined) {
                connection.state.collect { emissions += it }
            }
            try {
                val charging = snapshot()
                val unplugged = snapshot(0, 1, 20)
                assertTrue(messenger.send(generation, "process-a", 1, charging))
                assertTrue(messenger.send(generation, "process-a", 2, unplugged))
                assertEquals(
                    listOf(
                        MonitoringUiState(),
                        MonitoringUiState(MonitoringAvailability.LIVE, charging, "process-a", 1),
                        MonitoringUiState(MonitoringAvailability.LIVE, unplugged, "process-a", 2)
                    ), emissions
                )
            } finally {
                collector.cancel()
            }
        }
}
