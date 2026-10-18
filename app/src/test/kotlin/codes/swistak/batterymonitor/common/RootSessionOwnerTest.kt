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
package codes.swistak.batterymonitor.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RootSessionOwnerTest {
    @Test
    fun `different wrappers and refreshes reuse one verified session without caching values`() {
        val reading = AtomicInteger()
        val session = FakeSession { RootCommandResult(0, reading.incrementAndGet().toString()) }
        val created = AtomicInteger()
        val owner = enabledOwner {
            created.incrementAndGet()
            session
        }

        try {
            assertEquals("1", RootExecutor(owner = owner).run("battery reading"))
            assertEquals("2", RootExecutor(owner = owner).runRaw("battery reading"))
            assertEquals("3", RootExecutor(owner = owner).run("battery reading"))
            assertEquals(1, created.get())
            assertEquals(1, session.acquisitions.get())
            assertEquals(1L, session.closed.count)
        } finally {
            owner.setEnabled(false)
        }
    }

    @Test
    fun `concurrent first commands acquire only one shell and never interleave`() {
        val acquiring = CountDownLatch(1)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val session = FakeSession(onAcquire = {
            acquiring.countDown()
            await(release)
            true
        }) { command ->
            assertEquals(1, active.incrementAndGet())
            try {
                RootCommandResult(0, command)
            } finally {
                active.decrementAndGet()
            }
        }
        val created = AtomicInteger()
        val owner = enabledOwner { created.incrementAndGet(); session }
        val first = RootExecutor(owner = owner)
        val second = RootExecutor(owner = owner)
        val firstCall = async { first.runRaw("first") }
        await(acquiring)
        val secondCall = async { second.runRaw("second") }

        try {
            release.countDown()
            assertEquals("first", result(firstCall))
            assertEquals("second", result(secondCall))
            assertEquals(1, created.get())
            assertEquals(1, session.acquisitions.get())
            assertEquals(listOf("first", "second"), session.commands)
        } finally {
            release.countDown()
            owner.setEnabled(false)
        }
    }

    @Test
    fun `ordinary command failure and capped output do not retire a healthy shell`() {
        val session = FakeSession { command ->
            when (command) {
                "missing" -> RootCommandResult(1, "not found")
                "over cap" -> RootCommandResult(0, null)
                else -> RootCommandResult(0, "ok")
            }
        }
        val owner = enabledOwner { session }
        val executor = RootExecutor(owner = owner)

        try {
            assertNull(executor.runRaw("missing"))
            assertNull(executor.runRaw("over cap"))
            assertEquals("ok", executor.run("supported"))
            assertEquals(1, session.acquisitions.get())
            assertEquals(1L, session.closed.count)
        } finally {
            owner.setEnabled(false)
        }
    }

    @Test
    fun `executor preserves raw output and trims only regular successful output`() {
        val values = mapOf(
            "empty" to "",
            "blank" to " \r\n\t",
            "unicode" to "  Świstak 🦫\r\n",
            "no newline" to "last",
            "newline" to "last\n"
        )
        val session = FakeSession { RootCommandResult(0, values.getValue(it)) }
        val owner = enabledOwner { session }
        val executor = RootExecutor(owner = owner)

        try {
            values.forEach { (command, text) -> assertEquals(text, executor.runRaw(command)) }
            assertNull(executor.run("empty"))
            assertNull(executor.run("blank"))
            assertEquals("Świstak 🦫", executor.run("unicode"))
        } finally {
            owner.setEnabled(false)
        }
    }

    @Test
    fun `non-root shell is denied once until an explicit retry succeeds`() {
        val denied = FakeSession(onAcquire = { false })
        val granted = FakeSession()
        val created = AtomicInteger()
        val owner = enabledOwner { if (created.incrementAndGet() == 1) denied else granted }

        try {
            repeat(8) { assertNull(RootExecutor(owner = owner).run("id")) }
            assertEquals(1, created.get())
            assertTrue(denied.commands.isEmpty())
            await(denied.closed)
            assertTrue(owner.requestAccess({ true }))
            assertEquals("ok", RootExecutor(owner = owner).run("id"))
            assertEquals(2, created.get())
        } finally {
            owner.setEnabled(false)
        }
    }

    @Test
    fun `explicit access works while disabled and closes unless enabling retains the lease`() {
        val sessions = mutableListOf<FakeSession>()
        val owner = RootSessionOwner(factory = { FakeSession().also { sessions += it } })

        try {
            assertNull(RootExecutor(owner = owner).run("automatic"))
            assertTrue(sessions.isEmpty())
            assertTrue(owner.requestAccess({ true }))
            await(sessions.single().closed)
            assertNull(RootExecutor(owner = owner).run("automatic"))
            assertTrue(owner.requestAccess({ true }) { owner.setEnabled(true) })
            assertEquals("ok", RootExecutor(owner = owner).run("automatic"))
            assertEquals(2, sessions.size)
            assertEquals(1, sessions.last().acquisitions.get())
            assertEquals(1L, sessions.last().closed.count)
        } finally {
            owner.setEnabled(false)
        }
    }

    @Test
    fun `transient acquisition failures back off and stop until explicit retry`() {
        val now = AtomicLong()
        val created = AtomicInteger()
        val owner = RootSessionOwner(factory = {
            if (created.incrementAndGet() <= 3) FakeSession(onAcquire = { throw IOException("unusable") })
            else FakeSession()
        }, clock = now::get).apply { setEnabled(true) }

        try {
            assertNull(RootExecutor(owner = owner).run("read"))
            repeat(5) { assertNull(RootExecutor(owner = owner).run("read")) }
            assertEquals(1, created.get())
            now.set(1_000)
            assertNull(RootExecutor(owner = owner).run("read"))
            now.set(6_000)
            assertNull(RootExecutor(owner = owner).run("read"))
            now.set(600_000)
            assertNull(RootExecutor(owner = owner).run("read"))
            assertEquals(3, created.get())
            assertTrue(owner.requestAccess({ true }))
            assertEquals("ok", RootExecutor(owner = owner).run("read"))
            assertEquals(4, created.get())
        } finally {
            owner.setEnabled(false)
        }
    }

    @Test
    fun `session death retires the old generation and permits bounded later recovery`() {
        val now = AtomicLong()
        val dead = FakeSession { throw IOException("session died") }
        val healthy = FakeSession()
        val created = AtomicInteger()
        val owner = RootSessionOwner(factory = {
            if (created.incrementAndGet() == 1) dead else healthy
        }, clock = now::get).apply { setEnabled(true) }
        val stale = RootExecutor(owner = owner)

        try {
            assertNull(stale.run("first"))
            await(dead.closed)
            assertNull(stale.run("stale"))
            assertNull(RootExecutor(owner = owner).run("too soon"))
            assertEquals(1, created.get())
            now.set(1_000)
            assertEquals("ok", RootExecutor(owner = owner).run("recovered"))
            assertEquals(2, created.get())
            assertEquals(listOf("recovered"), healthy.commands)
        } finally {
            owner.setEnabled(false)
        }
    }

    @Test
    fun `disable rejects active and queued work while late old cleanup cannot close recovery`() {
        val started = CountDownLatch(1)
        val releaseCommand = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val old = FakeSession(onClose = {
            closing.countDown()
            await(releaseClose)
        }) {
            started.countDown()
            awaitIgnoringInterruption(releaseCommand)
            RootCommandResult(0, "stale")
        }
        val recovered = FakeSession()
        val created = AtomicInteger()
        val owner = enabledOwner { if (created.incrementAndGet() == 1) old else recovered }
        val first = async { RootExecutor(owner = owner).run("active") }
        await(started)
        val queued = RootExecutor(owner = owner)
        val second = async { queued.run("queued") }

        try {
            owner.setEnabled(false)
            await(closing)
            assertNull(result(first))
            assertNull(result(second))
            owner.setEnabled(true)
            assertEquals("ok", RootExecutor(owner = owner).run("new generation"))
            releaseCommand.countDown()
            releaseClose.countDown()
            await(old.closed)
            assertEquals("ok", RootExecutor(owner = owner).run("still healthy"))
            assertEquals(listOf("active"), old.commands)
            assertEquals(1L, recovered.closed.count)
        } finally {
            releaseCommand.countDown()
            releaseClose.countDown()
            owner.setEnabled(false)
        }
    }

    @Test
    fun `interrupted caller retires execution preserves its flag and skips later commands`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = FakeSession(onClose = release::countDown) {
            started.countDown()
            awaitIgnoringInterruption(release)
            RootCommandResult(0, "late")
        }
        val owner = enabledOwner { session }
        val interrupted = AtomicBoolean()
        val call = FutureTask {
            val executor = RootExecutor(owner = owner)
            assertNull(executor.run("active"))
            interrupted.set(Thread.currentThread().isInterrupted)
            assertNull(executor.runRaw("after interrupt"))
            assertTrue(Thread.currentThread().isInterrupted)
        }
        val caller = Thread(call, "root-interrupted-caller").apply { isDaemon = true; start() }

        try {
            await(started)
            caller.interrupt()
            call.get(3, TimeUnit.SECONDS)
            await(session.closed)
            assertTrue(interrupted.get())
            assertEquals(listOf("active"), session.commands)
        } finally {
            release.countDown()
            caller.interrupt()
            owner.setEnabled(false)
        }
    }

    @Test
    fun `timeout retires actual transport rejects queued work and lets later work recover`() {
        val now = AtomicLong()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stuck = FakeSession(onClose = release::countDown) {
            started.countDown()
            awaitIgnoringInterruption(release)
            RootCommandResult(0, "late")
        }
        val healthy = FakeSession()
        val created = AtomicInteger()
        val owner = RootSessionOwner(factory = {
            if (created.incrementAndGet() == 1) stuck else healthy
        }, timeoutMillis = 500, clock = now::get).apply { setEnabled(true) }
        val first = async { RootExecutor(owner = owner).run("stuck") }
        await(started)
        val queued = RootExecutor(owner = owner)
        val second = async { queued.runRaw("queued") }

        try {
            assertNull(result(first))
            assertNull(result(second))
            await(stuck.closed)
            assertEquals(listOf("stuck"), stuck.commands)
            now.set(1_000)
            assertEquals("ok", RootExecutor(owner = owner).run("recovered"))
            assertEquals(2, created.get())
        } finally {
            release.countDown()
            owner.setEnabled(false)
        }
    }

    @Test
    fun `main thread and canceled caller never acquire or authorize root`() {
        val created = AtomicInteger()
        val mainOwner = RootSessionOwner(
            factory = { created.incrementAndGet(); FakeSession() },
            isMainThread = { true }).apply { setEnabled(true) }
        assertNull(RootExecutor(owner = mainOwner).run("id"))
        assertFalse(mainOwner.requestAccess({ true }))
        val workerOwner = enabledOwner { created.incrementAndGet(); FakeSession() }
        try {
            assertNull(RootExecutor(allowed = { false }, owner = workerOwner).run("id"))
            assertFalse(workerOwner.requestAccess({ false }) { error("Canceled authorization callback") })
            assertEquals(0, created.get())
        } finally {
            workerOwner.setEnabled(false)
            mainOwner.setEnabled(false)
        }
    }

    @Test
    fun `reading revision changes discard active and queued results but preserve the healthy shell`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val allowed = AtomicBoolean(true)
        val session = FakeSession { command ->
            if (command == "active") {
                started.countDown()
                await(release)
            }
            RootCommandResult(0, command)
        }
        val owner = enabledOwner { session }
        val active = RootExecutor(allowed = allowed::get, owner = owner)
        val first = async { active.run("active") }
        await(started)
        val queued = RootExecutor(allowed = allowed::get, owner = owner)
        val second = async { queued.runRaw("queued") }

        try {
            allowed.set(false)
            release.countDown()
            assertNull(result(first))
            assertNull(result(second))
            assertEquals("fresh", RootExecutor(owner = owner).run("fresh"))
            assertEquals(listOf("active", "fresh"), session.commands)
            assertEquals(1, session.acquisitions.get())
            assertEquals(1L, session.closed.count)
        } finally {
            release.countDown()
            owner.setEnabled(false)
        }
    }

    @Test
    fun `canceled explicit acquisition never invokes authorization callback and later retry works`() {
        val acquiring = CountDownLatch(1)
        val release = CountDownLatch(1)
        val allowed = AtomicBoolean(true)
        val callback = AtomicBoolean()
        val canceled = FakeSession(onAcquire = {
            acquiring.countDown()
            awaitIgnoringInterruption(release)
            true
        }, onClose = release::countDown)
        val healthy = FakeSession()
        val created = AtomicInteger()
        val owner = RootSessionOwner(factory = {
            if (created.incrementAndGet() == 1) canceled else healthy
        })
        val request = async { owner.requestAccess(allowed::get) { callback.set(true) } }

        try {
            await(acquiring)
            allowed.set(false)
            assertFalse(result(request))
            await(canceled.closed)
            assertFalse(callback.get())
            assertTrue(canceled.commands.isEmpty())
            assertTrue(owner.requestAccess({ true }) { owner.setEnabled(true) })
            assertEquals("ok", RootExecutor(owner = owner).run("later"))
            assertEquals(2, created.get())
        } finally {
            release.countDown()
            owner.setEnabled(false)
        }
    }

    @Test
    fun `interruption while queued skips the command without retiring another caller's session`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = FakeSession { command ->
            if (command == "active") {
                started.countDown()
                await(release)
            }
            RootCommandResult(0, command)
        }
        val owner = enabledOwner { session }
        val first = async { RootExecutor(owner = owner).run("active") }
        await(started)
        val entering = CountDownLatch(1)
        val skipped = FutureTask {
            entering.countDown()
            assertNull(RootExecutor(owner = owner).run("interrupted queue"))
            assertTrue(Thread.currentThread().isInterrupted)
        }
        val caller = Thread(skipped, "root-interrupted-queue").apply { isDaemon = true; start() }

        try {
            await(entering)
            caller.interrupt()
            skipped.get(3, TimeUnit.SECONDS)
            release.countDown()
            assertEquals("active", result(first))
            assertEquals("healthy", RootExecutor(owner = owner).run("healthy"))
            assertEquals(listOf("active", "healthy"), session.commands)
            assertEquals(1, session.acquisitions.get())
            assertEquals(1L, session.closed.count)
        } finally {
            release.countDown()
            caller.interrupt()
            owner.setEnabled(false)
        }
    }

    @Test
    fun `worker rechecks authorization immediately before launching a delayed command`() {
        val workers = QueuedWorkers()
        val allowed = AtomicBoolean(true)
        val session = FakeSession()
        val owner =
            RootSessionOwner(factory = { session }, workers = workers).apply { setEnabled(true) }
        val call = async { RootExecutor(allowed = allowed::get, owner = owner).run("canceled") }

        try {
            workers.next().run()
            val command = workers.next()
            allowed.set(false)
            command.run()
            assertNull(result(call))
            assertTrue(session.commands.isEmpty())
            val fresh = async { RootExecutor(owner = owner).run("fresh") }
            workers.next().run()
            assertEquals("ok", result(fresh))
            assertEquals(1, session.acquisitions.get())
            assertEquals(listOf("fresh"), session.commands)
        } finally {
            owner.setEnabled(false)
            workers.drain()
        }
    }

    @Test
    fun `worker observes requester interruption before starting delayed acquisition`() {
        val workers = QueuedWorkers()
        val session = FakeSession()
        val owner = RootSessionOwner(factory = { session }, workers = workers)
        val interrupted = AtomicBoolean()
        val call = FutureTask {
            val granted =
                owner.requestAccess({ true }) { error("Interrupted requester granted root") }
            interrupted.set(Thread.currentThread().isInterrupted)
            granted
        }
        val requester = Thread(call, "root-delayed-requester").apply { isDaemon = true; start() }

        try {
            val acquisition = workers.next()
            requester.interrupt()
            acquisition.run()
            assertFalse(call.get(3, TimeUnit.SECONDS))
            assertTrue(interrupted.get())
            assertEquals(0, session.acquisitions.get())
            assertTrue(session.commands.isEmpty())
        } finally {
            owner.setEnabled(false)
            workers.drain()
            requester.interrupt()
        }
    }

    private fun enabledOwner(factory: () -> RootSession): RootSessionOwner =
        RootSessionOwner(factory).apply { setEnabled(true) }

    private fun <T> async(operation: () -> T): FutureTask<T> = FutureTask(operation).also { task ->
        Thread(task, "root-test-caller").apply { isDaemon = true; start() }
    }

    private fun <T> result(task: FutureTask<T>): T = task.get(3, TimeUnit.SECONDS)

    private fun await(latch: CountDownLatch) {
        assertTrue("Timed out waiting for test worker", latch.await(3, TimeUnit.SECONDS))
    }

    private fun awaitIgnoringInterruption(latch: CountDownLatch) {
        var interrupted = false
        try {
            while (true) {
                try {
                    await(latch)
                    return
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private class FakeSession(
        private val onAcquire: () -> Boolean = { true },
        private val onClose: () -> Unit = {},
        private val command: (String) -> RootCommandResult = { RootCommandResult(0, "ok") }
    ) : RootSession {
        val acquisitions = AtomicInteger()
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val closed = CountDownLatch(1)

        override fun acquire(): Boolean {
            acquisitions.incrementAndGet()
            return onAcquire()
        }

        override fun execute(command: String): RootCommandResult {
            commands += command
            return this.command(command)
        }

        override fun close() {
            try {
                onClose()
            } finally {
                closed.countDown()
            }
        }
    }

    private class QueuedWorkers : Executor {
        private val tasks = LinkedBlockingQueue<Runnable>()

        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun next(): Runnable = checkNotNull(tasks.poll(3, TimeUnit.SECONDS)) {
            "Timed out waiting for queued root worker"
        }

        fun drain() {
            while (true) (tasks.poll() ?: return).run()
        }
    }
}
