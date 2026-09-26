package codes.swistak.batterymonitor.monitoring.presentation

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
            lastPlugged = if (status == 0) 0 else 1,
            lastPercent = 75,
            lastStatusTimeMillis = 1000,
            configuredPrediction = PredictionSnapshot(direction, target, false, 2000, 0, 1, 10),
            fullRangePrediction = PredictionSnapshot(
                direction, if (status == 0) 0 else 100, false, 3000, 0, 2, 0
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
    fun `unplug snapshot changes status direction and target in one state emission`() {
        val connection = MonitoringConnection()
        connection.start()
        val generation = connection.onServiceConnected()
        assertTrue(connection.onHandshake(generation, "process-a"))
        val messenger = FakeMessenger(connection)
        assertTrue(messenger.send(generation, "process-a", 1))

        assertTrue(messenger.send(generation, "process-a", 2, snapshot(0, 1, 20)))
        val state = connection.state.value.snapshot!!
        assertEquals(0, state.status)
        assertEquals(1, state.configuredPrediction.direction)
        assertEquals(20, state.configuredPrediction.targetPercent)
        assertEquals(0, state.fullRangePrediction.targetPercent)
    }
}
