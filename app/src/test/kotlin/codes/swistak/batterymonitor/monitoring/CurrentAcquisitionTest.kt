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

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
package codes.swistak.batterymonitor.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class CurrentAcquisitionTest {
    private val now = BatteryCurrentReading(-420_000, "current_now")
    private val average = BatteryCurrentReading(-390_000, "current_avg")

    @Test
    fun `overlapping requests share instantaneous acquisition and do not read unused average`() {
        val worker = QueuedExecutor()
        val deliveries = mutableListOf<() -> Unit>()
        val results = mutableListOf<CurrentSample>()
        var nowReads = 0
        var averageReads = 0
        val acquisition = CurrentAcquisition(readNow = {
            nowReads++
            now
        }, readAverage = {
            averageReads++
            average
        }, executor = worker, deliver = { deliveries += it })

        acquisition.read(false, results::add)
        acquisition.read(false, results::add)
        assertEquals(1, worker.tasks.size)
        worker.runNext()
        assertTrue(results.isEmpty())
        acquisition.read(false, results::add)
        deliveries.removeAt(0)()

        assertEquals(1, nowReads)
        assertEquals(0, averageReads)
        assertEquals(List(3) { CurrentSample(now, null) }, results)
        acquisition.read(false, results::add)
        worker.runNext()
        deliveries.removeAt(0)()
        assertEquals(2, nowReads)
    }

    @Test
    fun `average demand added during instantaneous read uses the same acquisition`() {
        val worker = QueuedExecutor()
        val results = mutableListOf<CurrentSample>()
        var nowReads = 0
        var averageReads = 0
        lateinit var acquisition: CurrentAcquisition
        acquisition = CurrentAcquisition(readNow = {
            nowReads++
            acquisition.read(true, results::add)
            now
        }, readAverage = {
            averageReads++
            average
        }, executor = worker, deliver = { it() })

        acquisition.read(false, results::add)
        worker.runNext()

        assertEquals(1, nowReads)
        assertEquals(1, averageReads)
        assertTrue(worker.tasks.isEmpty())
        assertEquals(List(2) { CurrentSample(now, average) }, results)
    }

    @Test
    fun `average demand added before delivery reads average without rereading instantaneous`() {
        val worker = QueuedExecutor()
        val deliveries = mutableListOf<() -> Unit>()
        val results = mutableListOf<CurrentSample>()
        var nowReads = 0
        var averageReads = 0
        val acquisition = CurrentAcquisition(readNow = {
            nowReads++
            now
        }, readAverage = {
            averageReads++
            average
        }, executor = worker, deliver = { deliveries += it })

        acquisition.read(false, results::add)
        worker.runNext()
        acquisition.read(true, results::add)
        deliveries.removeAt(0)()
        assertTrue(results.isEmpty())
        worker.runNext()
        deliveries.removeAt(0)()

        assertEquals(1, nowReads)
        assertEquals(1, averageReads)
        assertEquals(List(2) { CurrentSample(now, average) }, results)
    }

    @Test
    fun `average request upgrades a pending delivery without another callback or instantaneous read`() {
        val worker = QueuedExecutor()
        val deliveries = mutableListOf<() -> Unit>()
        val results = mutableListOf<CurrentSample>()
        var nowReads = 0
        var averageReads = 0
        val acquisition = CurrentAcquisition(readNow = {
            nowReads++
            now
        }, readAverage = {
            averageReads++
            average
        }, executor = worker, deliver = { deliveries += it })

        assertFalse(acquisition.requestAverage())
        acquisition.read(false, results::add)
        worker.runNext()
        assertTrue(acquisition.requestAverage())
        deliveries.removeAt(0)()
        worker.runNext()
        deliveries.removeAt(0)()

        assertEquals(1, nowReads)
        assertEquals(1, averageReads)
        assertEquals(listOf(CurrentSample(now, average)), results)
        assertFalse(acquisition.requestAverage())
        acquisition.close()
        assertFalse(acquisition.requestAverage())
        assertTrue(worker.tasks.isEmpty())
    }

    @Test
    fun `read failures return unavailable and one failing callback does not strand others`() {
        val worker = QueuedExecutor()
        val results = mutableListOf<CurrentSample>()
        var averageReads = 0
        val acquisition =
            CurrentAcquisition(
                readNow = { throw IllegalStateException("Unavailable") },
                readAverage = {
                    averageReads++
                    throw IllegalStateException("Unavailable")
                },
                executor = worker,
                deliver = { it() })

        acquisition.read(true) { error("Callback failed") }
        acquisition.read(false, results::add)
        worker.runNext()

        assertEquals(1, averageReads)
        assertEquals(listOf(CurrentSample(null, null)), results)
        acquisition.read(false, results::add)
        worker.runNext()
        assertEquals(2, results.size)
    }

    @Test
    fun `executor rejection resolves all waiters and permits a later request`() {
        val deliveries = mutableListOf<() -> Unit>()
        val results = mutableListOf<CurrentSample>()
        var reads = 0
        val acquisition = CurrentAcquisition(readNow = {
            reads++
            now
        }, readAverage = {
            reads++
            average
        }, executor = { throw RejectedExecutionException() }, deliver = { deliveries += it })

        acquisition.read(false, results::add)
        acquisition.read(true, results::add)
        deliveries.removeAt(0)()
        assertEquals(List(2) { CurrentSample(null, null) }, results)
        acquisition.read(true, results::add)
        deliveries.removeAt(0)()
        assertEquals(3, results.size)
        assertEquals(0, reads)
    }

    @Test
    fun `close releases pending callbacks and suppresses queued acquisition and new requests`() {
        val worker = QueuedExecutor()
        var reads = 0
        var deliveries = 0
        var callbacks = 0
        val acquisition = CurrentAcquisition(readNow = {
            reads++
            now
        }, readAverage = {
            reads++
            average
        }, executor = worker, deliver = {
            deliveries++
            it()
        })

        acquisition.read(true) { callbacks++ }
        acquisition.close()
        worker.runNext()
        acquisition.read(false) { callbacks++ }

        assertEquals(0, reads)
        assertEquals(0, deliveries)
        assertEquals(0, callbacks)
        assertTrue(worker.tasks.isEmpty())
    }

    @Test
    fun `close during a read skips average and suppresses late delivery`() {
        val worker = QueuedExecutor()
        val deliveries = mutableListOf<() -> Unit>()
        var averageReads = 0
        var callbacks = 0
        lateinit var acquisition: CurrentAcquisition
        acquisition = CurrentAcquisition(readNow = {
            acquisition.close()
            now
        }, readAverage = {
            averageReads++
            average
        }, executor = worker, deliver = { deliveries += it })

        acquisition.read(true) { callbacks++ }
        worker.runNext()
        deliveries.removeAt(0)()

        assertEquals(0, averageReads)
        assertEquals(0, callbacks)
        assertTrue(worker.tasks.isEmpty())
    }

    @Test
    fun `close suppresses a result already queued for delivery`() {
        val worker = QueuedExecutor()
        val deliveries = mutableListOf<() -> Unit>()
        var callbacks = 0
        val acquisition = CurrentAcquisition(
            readNow = { now },
            readAverage = { average },
            executor = worker,
            deliver = { deliveries += it })

        acquisition.read(false) { callbacks++ }
        worker.runNext()
        acquisition.close()
        deliveries.removeAt(0)()

        assertEquals(0, callbacks)
    }

    @Test
    fun `average preference falls back to instantaneous and preserves unknown readings`() {
        assertEquals(average, CurrentSample(now, average).selected(true))
        assertEquals(now, CurrentSample(now, average).selected(false))
        assertEquals(now, CurrentSample(now, null).selected(true))
        assertNull(CurrentSample(null, average).selected(false))
        assertNull(CurrentSample(null, null).selected(true))
    }

    private class QueuedExecutor : Executor {
        val tasks = mutableListOf<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runNext() {
            tasks.removeAt(0).run()
        }
    }
}
