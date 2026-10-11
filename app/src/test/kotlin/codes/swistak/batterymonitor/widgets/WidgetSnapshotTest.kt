/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSnapshotTest {
    private val reading =
        WidgetSnapshot(72, 2, 316, 4080, -420_000, 3_020_000, 580, 80, false, 1_000_000)

    @Test
    fun `Cached snapshot preserves raw current nullable readings and stopped state`() {
        for (snapshot in listOf(
            reading, reading.copy(
                currentMicroAmps = null,
                voltageMillivolts = null,
                remainingChargeMicroampHours = null,
                predictionMinutes = null,
                predictionTarget = null,
                monitoringEnabled = false
            )
        )) {
            assertEquals(snapshot, WidgetSnapshotCodec.decode(WidgetSnapshotCodec.encode(snapshot)))
        }
    }

    @Test
    fun `Partial unsupported oversized or invalid snapshot is ignored`() {
        val bytes = WidgetSnapshotCodec.encode(reading)
        assertNull(WidgetSnapshotCodec.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(WidgetSnapshotCodec.decode(bytes + byteArrayOf(0)))
        assertNull(WidgetSnapshotCodec.decode(ByteArray(1024)))
        assertNull(WidgetSnapshotCodec.decode(bytes.copyOf().apply { this[3] = 9 }))
        assertNull(WidgetSnapshotCodec.decode(WidgetSnapshotCodec.encode(reading.copy(levelPercent = 101))))
    }

    @Test
    fun `Snapshot age handles clock changes and the stale boundary`() {
        assertFalse(reading.isStale(reading.observedAtMillis + 900_000))
        assertTrue(reading.isStale(reading.observedAtMillis + 900_001))
        assertTrue(reading.isStale(reading.observedAtMillis - 1))
    }

    @Test
    fun `Measurement ticks are limited while important changes publish immediately`() {
        val policy = WidgetUpdatePolicy()
        assertTrue(policy.shouldPublish(reading, 1_000))
        assertFalse(policy.shouldPublish(reading.copy(currentMicroAmps = -900_000), 2_000))
        assertFalse(policy.shouldPublish(reading, 60_999))
        assertTrue(policy.shouldPublish(reading, 61_000))
        assertTrue(policy.shouldPublish(reading.copy(levelPercent = 71), 61_001))
        assertTrue(
            policy.shouldPublish(
                reading.copy(levelPercent = 71, monitoringEnabled = false), 61_002
            )
        )
        assertTrue(policy.shouldPublish(reading, 61_003, force = true))
    }

    @Test
    fun `Six instances share one publish decision and do not create acquisition work`() {
        val policy = WidgetUpdatePolicy()
        var publications = 0
        var instanceUpdates = 0
        repeat(180) { tick ->
            if (policy.shouldPublish(
                    reading.copy(currentMicroAmps = tick.toLong()), tick * 1_000L
                )
            ) {
                publications++
                instanceUpdates += 6
            }
        }
        assertEquals(3, publications)
        assertEquals(18, instanceUpdates)
    }
}
