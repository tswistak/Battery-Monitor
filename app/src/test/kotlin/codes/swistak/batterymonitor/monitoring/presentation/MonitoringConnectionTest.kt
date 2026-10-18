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
package codes.swistak.batterymonitor.monitoring.presentation

import codes.swistak.batterymonitor.monitoring.BatteryCurrentReading
import codes.swistak.batterymonitor.monitoring.BatteryMeasurementTime
import codes.swistak.batterymonitor.monitoring.CurrentSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringConnectionTest {
    @Test
    fun `current replies require the active handshake generation process demand and valid sequence`() {
        val connection = MonitoringConnection()
        connection.start()
        val generation = connection.onServiceConnected()
        val sample = CurrentSample(BatteryCurrentReading(-420_000, "current_now"), null)
        connection.expectCurrent(7)

        assertFalse(connection.current.value.connected)
        assertFalse(connection.publishCurrent(generation, "process-a", 0, 7, sample))
        assertTrue(connection.onHandshake(generation, "process-a"))
        assertTrue(connection.current.value.connected)
        assertFalse(connection.publishCurrent(generation + 1, "process-a", 0, 7, sample))
        assertFalse(connection.publishCurrent(generation, null, 0, 7, sample))
        assertFalse(connection.publishCurrent(generation, "process-b", 0, 7, sample))
        assertFalse(connection.publishCurrent(generation, "process-a", -1, 7, sample))
        assertFalse(connection.publishCurrent(generation, "process-a", 0, 8, sample))
        assertEquals(CurrentSample(null, null), connection.current.value.sample)
        assertTrue(connection.publishCurrent(generation, "process-a", 0, 7, sample))
        assertEquals(sample, connection.current.value.sample)
    }

    @Test
    fun `current and full snapshot channels advance independently without changing each other's state`() {
        val connection = MonitoringConnection()
        connection.start()
        val generation = connection.onServiceConnected()
        assertTrue(connection.onHandshake(generation, "process-a"))
        connection.expectCurrent(7)
        val waiting = connection.state.value
        val sample = CurrentSample(
            BatteryCurrentReading(-420_000, "current_now", BatteryMeasurementTime(1_000, 100)), null
        )

        assertTrue(connection.publishCurrent(generation, "process-a", 100, 7, sample))
        assertEquals(waiting, connection.state.value)
        assertEquals(MonitoringAvailability.WAITING, connection.state.value.availability)
        assertTrue(connection.publishSnapshot(generation, "process-a", 5, snapshot()))
        assertEquals(100L, connection.current.value.sequence)
        val fullState = connection.state.value
        val nextSample = sample.copy(now = sample.now?.copy(microAmps = -430_000))
        assertTrue(connection.publishCurrent(generation, "process-a", 101, 7, nextSample))
        assertEquals(fullState, connection.state.value)
        assertTrue(connection.publishSnapshot(generation, "process-a", 6, snapshot()))
        assertEquals(nextSample, connection.current.value.sample)
        assertEquals(101L, connection.current.value.sequence)
        assertEquals(6L, connection.state.value.sequence)
    }

    @Test
    fun `out of order current replies cannot replace the latest reading`() {
        val connection = MonitoringConnection()
        connection.start()
        val generation = connection.onServiceConnected()
        assertTrue(connection.onHandshake(generation, "process-a"))
        connection.expectCurrent(7)
        val newest = CurrentSample(BatteryCurrentReading(-420_000, "current_now"), null)
        val older = CurrentSample(BatteryCurrentReading(-430_000, "current_now"), null)

        assertTrue(connection.publishCurrent(generation, "process-a", 12, 7, newest))
        assertFalse(connection.publishCurrent(generation, "process-a", 11, 7, older))
        assertEquals(newest, connection.current.value.sample)
        assertEquals(12L, connection.current.value.sequence)
    }

    @Test
    fun `changing or clearing demand rejects old token replies even with newer sequence numbers`() {
        val connection = MonitoringConnection()
        connection.start()
        val generation = connection.onServiceConnected()
        assertTrue(connection.onHandshake(generation, "process-a"))
        val sample = CurrentSample(BatteryCurrentReading(-420_000, "current_now"), null)
        connection.expectCurrent(7)
        assertTrue(connection.publishCurrent(generation, "process-a", 1, 7, sample))

        connection.expectCurrent(8)
        assertFalse(connection.publishCurrent(generation, "process-a", 2, 7, sample))
        assertTrue(connection.publishCurrent(generation, "process-a", 2, 8, sample))
        connection.expectCurrent(null)
        assertFalse(connection.publishCurrent(generation, "process-a", 3, 8, sample))
        assertEquals(2L, connection.current.value.sequence)
    }

    @Test
    fun `service death clears current demand and accepts the new process with its own sequence`() {
        val connection = MonitoringConnection()
        connection.start()
        val oldGeneration = connection.onServiceConnected()
        assertTrue(connection.onHandshake(oldGeneration, "process-a"))
        connection.expectCurrent(7)
        val sample = CurrentSample(BatteryCurrentReading(-420_000, "current_now"), null)
        assertTrue(connection.publishCurrent(oldGeneration, "process-a", 42, 7, sample))

        connection.onServiceDisconnected()
        assertFalse(connection.current.value.connected)
        assertFalse(connection.publishCurrent(oldGeneration, "process-a", 43, 7, sample))
        val newGeneration = connection.onServiceConnected()
        assertTrue(connection.onHandshake(newGeneration, "process-b"))
        assertTrue(connection.current.value.connected)
        assertEquals(CurrentSample(null, null), connection.current.value.sample)
        assertEquals(-1L, connection.current.value.sequence)
        assertFalse(connection.publishCurrent(newGeneration, "process-b", 0, 7, sample))
        connection.expectCurrent(8)
        assertFalse(connection.publishCurrent(oldGeneration, "process-a", 44, 8, sample))
        assertFalse(connection.publishCurrent(newGeneration, "process-a", 44, 8, sample))
        assertTrue(connection.publishCurrent(newGeneration, "process-b", 0, 8, sample))
        assertEquals(0L, connection.current.value.sequence)
    }

    @Test
    fun `lifecycle stop clears current demand and rejects queued replies after restarting`() {
        val connection = MonitoringConnection()
        connection.start()
        val oldGeneration = connection.onServiceConnected()
        assertTrue(connection.onHandshake(oldGeneration, "process-a"))
        connection.expectCurrent(7)
        val sample = CurrentSample(BatteryCurrentReading(-420_000, "current_now"), null)
        assertTrue(connection.publishCurrent(oldGeneration, "process-a", 1, 7, sample))

        connection.stop()
        assertFalse(connection.current.value.connected)
        assertFalse(connection.publishCurrent(oldGeneration, "process-a", 2, 7, sample))
        connection.start()
        val newGeneration = connection.onServiceConnected()
        assertTrue(connection.onHandshake(newGeneration, "process-a"))
        assertFalse(connection.publishCurrent(newGeneration, "process-a", 2, 7, sample))
        connection.expectCurrent(8)
        assertFalse(connection.publishCurrent(oldGeneration, "process-a", 2, 8, sample))
        assertTrue(connection.publishCurrent(newGeneration, "process-a", 2, 8, sample))
    }

    @Test
    fun `unavailable current reply acknowledges service ownership without making full monitoring live`() {
        val connection = MonitoringConnection()
        connection.start()
        val generation = connection.onServiceConnected()
        assertTrue(connection.onHandshake(generation, "process-a"))
        connection.expectCurrent(7)

        assertTrue(
            connection.publishCurrent(
                generation, "process-a", 0, 7, CurrentSample(null, null)
            )
        )

        assertTrue(connection.current.value.connected)
        assertEquals(0L, connection.current.value.sequence)
        assertEquals(MonitoringAvailability.WAITING, connection.state.value.availability)
        assertEquals(-1L, connection.state.value.sequence)
    }

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
