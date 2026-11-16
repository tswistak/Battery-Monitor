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

import com.topjohnwu.superuser.Shell
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class RootShellSession : RootSession {
    companion object {
        val openCount = AtomicLong()
    }

    private val stateLock = Any()
    private val writeLock = ReentrantLock()

    @Volatile
    private var closed = false

    @Volatile
    private var process: Process? = null

    @Volatile
    private var shell: Shell? = null

    @Volatile
    private var activeToken = ""

    override fun acquire(): Boolean {
        checkOpen()
        val created = try {
            ProcessBuilder("su").redirectErrorStream(true).start()
        } catch (_: IOException) {
            return false
        }
        openCount.incrementAndGet()
        synchronized(stateLock) {
            if (closed) {
                created.destroyForcibly()
                throw IOException("Root session was closed during acquisition")
            }
            process = created
        }
        try {
            write(created.outputStream, RootShellProtocol.INITIALIZATION)
        } catch (error: IOException) {
            checkOpen()
            if (exitedAfterFailure(created)) return false
            throw error
        }
        val acquired = try {
            Shell.Builder.create().setTimeout(10).build(created)
        } catch (error: Exception) {
            checkOpen()
            if (generateSequence<Throwable>(error) { it.cause }.any { it.message?.contains("Shell check timeout") == true }) {
                throw IOException("Root shell initialization timed out", error)
            }
            if (exitedAfterFailure(created)) return false
            throw IOException("Root shell initialization failed", error)
        }
        shell = acquired
        checkOpen()
        return acquired.isRoot && task { stdin, stdout ->
            val token = UUID.randomUUID().toString()
            write(stdin, RootShellProtocol.readiness(token))
            RootShellProtocol.readFrame(stdout, "\u0000$token:".toByteArray()).exitCode == 0
        }
    }

    override fun execute(command: String): RootCommandResult {
        require('\u0000' !in command) { "Shell commands cannot contain NUL" }
        return task { stdin, stdout ->
            val token = UUID.randomUUID().toString()
            activeToken = token
            write(stdin, RootShellProtocol.command(command, token))
            val result = RootShellProtocol.readFrame(stdout, RootShellProtocol.endMarker(token))
            write(stdin, RootShellProtocol.acknowledge(token))
            RootShellProtocol.readUntil(stdout, RootShellProtocol.ackMarker(token), capture = false)
            checkOpen()
            activeToken = ""
            result
        }
    }

    private fun <T> task(block: (OutputStream, InputStream) -> T): T {
        checkOpen()
        val current = shell ?: throw IOException("Root shell was not acquired")
        var value: T? = null
        var failure: Exception? = null
        current.execTask(object : Shell.Task {
            override fun run(stdin: OutputStream, stdout: InputStream, stderr: InputStream) {
                // Let libsu unwind its task scheduler even when the raw protocol fails.
                try {
                    checkOpen()
                    value = block(stdin, BufferedInputStream(stdout))
                } catch (error: Exception) {
                    failure = error
                }
            }

            override fun shellDied() {
                failure = IOException("Root shell died")
            }
        })
        failure?.let { throw it }
        checkOpen()
        @Suppress("UNCHECKED_CAST") return value as T
    }

    private fun write(stream: OutputStream, text: String) = writeLock.withLock {
        checkOpen()
        stream.write(text.toByteArray(Charsets.UTF_8))
        stream.flush()
    }

    private fun checkOpen() {
        if (closed || Thread.currentThread().isInterrupted) {
            throw IOException("Root session is closed or interrupted")
        }
    }

    private fun exitedAfterFailure(process: Process): Boolean = try {
        // Magisk can close its output just before its denied su client finishes exiting.
        process.waitFor(200, TimeUnit.MILLISECONDS)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Root acquisition was interrupted", error)
    }

    override fun close() {
        val current = synchronized(stateLock) {
            if (closed) return
            closed = true
            process
        } ?: return
        val token = activeToken
        val closer = Thread({
            try {
                if (writeLock.tryLock(100, TimeUnit.MILLISECONDS)) {
                    try {
                        current.outputStream.write(
                            RootShellProtocol.stop(token).toByteArray(Charsets.UTF_8)
                        )
                        current.outputStream.flush()
                        current.outputStream.close()
                    } finally {
                        writeLock.unlock()
                    }
                }
                if (!current.waitFor(500, TimeUnit.MILLISECONDS)) current.destroyForcibly()
            } catch (_: Exception) {
                current.destroyForcibly()
            } finally {
                current.destroyForcibly()
                runCatching { shell?.close() }
                runCatching { current.inputStream.close() }
                runCatching { current.errorStream.close() }
            }
        }, "battery-root-close").apply { isDaemon = true; start() }
        try {
            closer.join(750)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            current.destroyForcibly()
        }
    }
}

internal object RootShellProtocol {
    private const val MAX_OUTPUT_BYTES = 256 * 1024
    private const val STOP_JOB =
        $$"if [ -n \"$__bm_job\" ]; then kill -KILL \"$__bm_job\" 2>/dev/null; kill -KILL -- -\"$__bm_job\" 2>/dev/null; fi"
    const val INITIALIZATION = "set +m\n__bm_job=\n__bm_token=\ntrap '$STOP_JOB' EXIT\n"

    fun readiness(token: String): String =
        "if command -v setsid >/dev/null 2>&1; then printf '\\000$token:0\\000'; else printf '\\000$token:1\\000'; fi\n"

    fun command(command: String, token: String): String {
        val supervisor =
            "__bm_group=$$\n" + $$"(sleep 10; kill -KILL -- -\"$__bm_group\") </dev/null >/dev/null 2>&1 &\n" + "sh -c ${
                quote(command)
            } </dev/null 2>&1\n" + "__bm_status=$?\n" + $$"printf '\\000BM_$${token}_END:%s\\000' \"$__bm_status\"\n" + $$"kill -KILL -- -\"$__bm_group\""
        return $$"__bm_token=$${quote(token)}\nsetsid sh -c $${quote(supervisor)} </dev/null 2>&1 &\n__bm_job=$!\n"
    }

    fun acknowledge(token: String): String =
        $$"if [ \"$__bm_token\" = $${quote(token)} ]; then wait \"$__bm_job\" 2>/dev/null; __bm_job=; __bm_token=; printf '\\000BM_$${token}_ACK\\000'; fi\n"

    fun stop(token: String): String =
        $$"if [ \"$__bm_token\" = $${quote(token)} ]; then $$STOP_JOB; fi\nexit\n"

    fun endMarker(token: String) = "\u0000BM_${token}_END:".toByteArray(Charsets.UTF_8)
    fun ackMarker(token: String) = "\u0000BM_${token}_ACK\u0000".toByteArray(Charsets.UTF_8)

    fun readFrame(input: InputStream, marker: ByteArray): RootCommandResult {
        val output = readUntil(input, marker)
        val code = StringBuilder(3)
        while (true) {
            val byte = input.read()
            if (byte == 0) break
            if (byte !in '0'.code..'9'.code || code.length == 3) {
                throw IOException("Invalid root command status")
            }
            code.append(byte.toChar())
        }
        val status = code.toString().toIntOrNull()?.takeIf { it in 0..255 }
            ?: throw IOException("Invalid root command status")
        return RootCommandResult(status, output?.toString(Charsets.UTF_8))
    }

    fun readUntil(input: InputStream, marker: ByteArray, capture: Boolean = true): ByteArray? {
        val output = ByteArrayOutputStream()
        var matched = 0
        var overflow = false
        fun append(bytes: ByteArray, count: Int) {
            if (!capture || overflow) return
            if (output.size() + count > MAX_OUTPUT_BYTES) overflow = true
            else output.write(bytes, 0, count)
        }
        while (true) {
            val next = input.read()
            if (next == -1) throw IOException("Root shell output ended before completion")
            if (next.toByte() == marker[matched]) {
                matched++
                if (matched == marker.size) return if (overflow) null else output.toByteArray()
            } else {
                append(marker, matched)
                matched = 0
                if (next.toByte() == marker[0]) matched = 1
                else if (capture && !overflow) {
                    if (output.size() == MAX_OUTPUT_BYTES) overflow = true
                    else output.write(next)
                }
            }
        }
    }

    private fun quote(text: String): String = "'${text.replace("'", "'\\''")}'"
}
