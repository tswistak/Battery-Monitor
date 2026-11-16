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

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock

internal data class RootCommandResult(val exitCode: Int, val output: String?)

internal interface RootSession {
    fun acquire(): Boolean
    fun execute(command: String): RootCommandResult
    fun close()
}

internal class RootSessionOwner(
    private val factory: () -> RootSession,
    private val timeoutMillis: Long = 10_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val isMainThread: () -> Boolean = { false },
    private val workers: Executor = Executors.newCachedThreadPool { task ->
        Thread(task, "battery-root-session").apply { isDaemon = true }
    }
) {
    private val state = Any()
    private val commands = ReentrantLock(true)
    private var session: RootSession? = null
    private var denied = false
    private var failures = 0
    private var retryAt = 0L

    @Volatile
    private var enabled = false

    @Volatile
    var generation = 0L
        private set

    fun setEnabled(value: Boolean, onChanged: () -> Unit = {}) {
        val retired = synchronized(state) {
            onChanged()
            if (enabled == value) return
            enabled = value
            generation++
            denied = false
            failures = 0
            retryAt = 0
            if (value) null else session.also { session = null }
        }
        retired?.let(::closeLater)
    }

    fun run(command: String, capturedGeneration: Long, allowed: () -> Boolean): String? {
        val requester = Thread.currentThread()
        val permitted = {
            !requester.isInterrupted && permitted(capturedGeneration, explicit = false, allowed)
        }
        if (!enter(permitted)) return null
        try {
            val current = acquire(permitted, explicit = false) ?: return null
            val result = await(current, permitted) { current.execute(command) } ?: return null
            return synchronized(state) {
                if (!permitted() || session !== current) null else {
                    failures = 0
                    retryAt = 0
                    result.output.takeIf { result.exitCode == 0 }
                }
            }
        } finally {
            commands.unlock()
        }
    }

    fun requestAccess(allowed: () -> Boolean, onGranted: () -> Unit = {}): Boolean {
        val requester = Thread.currentThread()
        val capturedGeneration = generation
        val permitted = {
            !requester.isInterrupted && permitted(capturedGeneration, explicit = true, allowed)
        }
        if (!enter(permitted)) return false
        try {
            val current = acquire(permitted, explicit = true) ?: return false
            return synchronized(state) {
                if (!permitted() || session !== current) false else {
                    // The settings callback can enable automatic use before this explicit lease ends.
                    onGranted()
                    failures = 0
                    retryAt = 0
                    !Thread.currentThread().isInterrupted && allowed() && session === current
                }
            }
        } finally {
            val retired = synchronized(state) {
                if (enabled) null else session.also { session = null }
            }
            retired?.let(::closeLater)
            commands.unlock()
        }
    }

    private fun permitted(captured: Long, explicit: Boolean, allowed: () -> Boolean): Boolean =
        !Thread.currentThread().isInterrupted && captured == generation && (enabled || explicit) && allowed()

    private fun enter(permitted: () -> Boolean): Boolean {
        if (isMainThread()) return false
        try {
            while (permitted()) {
                if (commands.tryLock(25, TimeUnit.MILLISECONDS)) {
                    if (permitted()) return true
                    commands.unlock()
                    return false
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return false
    }

    private fun acquire(permitted: () -> Boolean, explicit: Boolean): RootSession? {
        val current = synchronized(state) {
            if (!permitted()) return null
            session?.let { return it }
            if (!explicit && (denied || failures >= 3 || clock() < retryAt)) return null
            if (explicit) {
                denied = false
                failures = 0
                retryAt = 0
            }
            factory().also { session = it }
        }
        if (!permitted()) {
            retire(current, failed = false)
            return null
        }
        val root = await(current, permitted, current::acquire) ?: run {
            retire(current, failed = false)
            return null
        }
        if (!root) {
            synchronized(state) {
                if (session === current) denied = true
            }
            retire(current, failed = false)
            return null
        }
        return current.takeIf { permitted() }
    }

    private fun <T> await(
        current: RootSession, permitted: () -> Boolean, operation: () -> T
    ): T? {
        if (!permitted()) return null
        val task = FutureTask { if (permitted()) operation() else null }
        workers.execute(task)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            while (permitted() || canDrain(current)) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                try {
                    return task.get(
                        minOf(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS
                    )
                } catch (_: TimeoutException) {
                    // Poll caller authorization as well as the execution deadline.
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: ExecutionException) {
            retire(current, failed = true)
            return null
        } finally {
            if (!task.isDone || !permitted() && !canDrain(current)) {
                // Future cancellation alone cannot stop a root process; retire its transport too.
                retire(current, failed = true)
                task.cancel(true)
            }
        }
        return null
    }

    private fun canDrain(current: RootSession): Boolean = synchronized(state) {
        enabled && session === current && !Thread.currentThread().isInterrupted
    }

    private fun retire(current: RootSession, failed: Boolean) {
        val retired = synchronized(state) {
            if (session !== current) false else {
                session = null
                generation++
                if (failed) {
                    failures++
                    retryAt = clock() + if (failures == 1) 1_000 else 5_000
                }
                true
            }
        }
        if (retired) closeLater(current)
    }

    private fun closeLater(current: RootSession) {
        workers.execute { runCatching { current.close() } }
    }
}
