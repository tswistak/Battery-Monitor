package codes.swistak.batterymonitor.monitoring.charginglimit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ChargingLimitSnapshotTest {
    @Test
    fun `blocking acquisition stays off the caller and coalesces refreshes`() {
        val caller = Thread.currentThread()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val delivered = LinkedBlockingQueue<() -> Unit>()
        var changes = 0
        try {
            val snapshot = ChargingLimitSnapshot(
                worker,
                { delivered.add(it) },
                { 0L },
                { ChargingLimitAccess(true, 1, 1) },
                read = {
                    check(Thread.currentThread() != caller)
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    fixed(80)
                },
                onTargetChanged = { changes++ })
            assertNull(snapshot.refresh())
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNull(snapshot.refresh())
            release.countDown()
            requireNotNull(delivered.poll(5, TimeUnit.SECONDS)).invoke()
            worker.submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(delivered.isEmpty())
            assertEquals(80, resolveDetectedChargingTarget(snapshot.refresh()).percent)
            assertEquals(1, changes)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun `access changes discard pending results and failure falls back after a refresh`() {
        val work = ArrayDeque<Runnable>()
        val deliveries = ArrayDeque<() -> Unit>()
        var clock = 0L
        var access = ChargingLimitAccess(true, 1, 1)
        var result = fixed(80)
        var fail = false
        var changes = 0
        val snapshot = ChargingLimitSnapshot(
            { work.add(it) },
            { deliveries.add(it) },
            { clock },
            { access },
            read = {
                if (fail) error("Unavailable")
                result
            },
            onTargetChanged = { changes++ })
        assertNull(snapshot.refresh())
        work.removeFirst().run()
        access = ChargingLimitAccess(false, 2, 2)
        deliveries.removeFirst().invoke()
        assertNull(snapshot.refresh())
        assertEquals(0, changes)

        result = fixed(85)
        work.removeFirst().run()
        deliveries.removeFirst().invoke()
        assertEquals(85, resolveDetectedChargingTarget(snapshot.refresh()).percent)
        clock += CHARGING_LIMIT_CACHE_MS
        fail = true
        assertEquals(85, resolveDetectedChargingTarget(snapshot.refresh()).percent)
        work.removeFirst().run()
        deliveries.removeFirst().invoke()
        assertEquals(100, resolveDetectedChargingTarget(snapshot.refresh()).percent)
        assertEquals(2, changes)
    }

    @Test
    fun `stalled refresh expires the old target and cannot publish an expired result`() {
        val work = ArrayDeque<Runnable>()
        val deliveries = ArrayDeque<() -> Unit>()
        var clock = 0L
        val snapshot = ChargingLimitSnapshot(
            { work.add(it) },
            { deliveries.add(it) },
            { clock },
            { ChargingLimitAccess(true, 1, 1) },
            read = { fixed(80) },
            onTargetChanged = {})
        snapshot.refresh()
        work.removeFirst().run()
        deliveries.removeFirst().invoke()
        clock = CHARGING_LIMIT_CACHE_MS
        assertEquals(80, resolveDetectedChargingTarget(snapshot.refresh()).percent)
        clock = CHARGING_LIMIT_MAX_AGE_MS
        assertNull(snapshot.refresh())
        assertEquals(1, work.size)
        clock += CHARGING_LIMIT_CACHE_MS
        work.removeFirst().run()
        deliveries.removeFirst().invoke()
        assertNull(snapshot.refresh())
        clock += CHARGING_LIMIT_CACHE_MS
        snapshot.refresh()
        work.removeFirst().run()
        deliveries.removeFirst().invoke()
        assertEquals(80, resolveDetectedChargingTarget(snapshot.refresh()).percent)
    }

    @Test
    fun `owner notification discards a result even when local access has not changed`() {
        val work = ArrayDeque<Runnable>()
        val deliveries = ArrayDeque<() -> Unit>()
        var epoch = 0L
        var result = fixed(80)
        val snapshot = ChargingLimitSnapshot(
            { work.add(it) },
            { deliveries.add(it) },
            { 0L },
            { ChargingLimitAccess(true, 1, 1, epoch) },
            read = { result },
            onTargetChanged = {})
        snapshot.refresh()
        work.removeFirst().run()
        epoch++
        snapshot.refresh()
        deliveries.removeFirst().invoke()
        assertNull(snapshot.refresh())
        result = fixed(85)
        work.removeFirst().run()
        deliveries.removeFirst().invoke()
        assertEquals(85, resolveDetectedChargingTarget(snapshot.refresh()).percent)
    }

    @Test
    fun `stopping during collection discards completion without starting another read`() {
        val work = ArrayDeque<Runnable>()
        val deliveries = ArrayDeque<() -> Unit>()
        var access: ChargingLimitAccess? = ChargingLimitAccess(true, 1, 1)
        val snapshot = ChargingLimitSnapshot(
            { work.add(it) },
            { deliveries.add(it) },
            { 0L },
            { access },
            read = { fixed(80) },
            onTargetChanged = { error("Stopped consumer updated") })
        snapshot.refresh()
        work.removeFirst().run()
        access = null
        deliveries.removeFirst().invoke()
        assertNull(snapshot.refresh())
        assertTrue(work.isEmpty())
    }

    private fun fixed(percent: Int) = ChargingLimitState.Fixed(
        percent, evidence = chargingLimitEvidence(ChargingLimitEvidenceKind.CONFIG_SETTING)
    )
}
