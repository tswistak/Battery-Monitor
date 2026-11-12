/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.diagnostics

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import kotlin.concurrent.thread

internal object DebugLogCollector {
    private const val LOG_TAG = "DebugLogCollector"
    private const val DIRECTORY_NAME = "diagnostics"
    private const val FILE_NAME = "battery-monitor-debug.log"
    private var process: Process? = null
    private var files: DebugLogFiles? = null

    fun shouldStartInProcess(processName: String?): Boolean =
        !processName.isNullOrEmpty() && !processName.endsWith(".BIS")

    @Synchronized
    fun sync(context: Context, enabled: Boolean) {
        if (!enabled) {
            stop()
            return
        }
        if (process?.isAlive == true) return

        val log = logFiles(context)
        try {
            log.append(marker("Debug log collection started"))
            val child = ProcessBuilder(
                "/system/bin/logcat", "-v", "threadtime", "-T", "1"
            ).redirectErrorStream(true).start()
            process = child
            thread(name = "BatteryMonitor-debug-log", isDaemon = true) {
                try {
                    child.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) synchronized(this) {
                            if (process !== child) return@thread
                            log.append(line)
                        }
                    }
                } catch (error: IOException) {
                    synchronized(this) {
                        if (process === child) Log.e(LOG_TAG, "Unable to collect debug logs", error)
                    }
                } finally {
                    synchronized(this) {
                        if (process === child) stop()
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(LOG_TAG, "Unable to start debug log collection", error)
            stop()
            runCatching { log.append(marker("Unable to start logcat: ${error.javaClass.simpleName}")) }
        }
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
    }

    @Synchronized
    private fun logFiles(context: Context): DebugLogFiles = files ?: DebugLogFiles(
        context.filesDir.resolve(DIRECTORY_NAME).resolve(FILE_NAME)
    ).also { files = it }

    fun appendToReport(context: Context, report: StringBuilder) = logFiles(context).appendTo(report)

    @Synchronized
    fun clear(context: Context): Boolean {
        val wasRunning = process?.isAlive == true
        stop()
        val deleted = logFiles(context).clear()
        if (wasRunning) sync(context, true)
        return deleted
    }

    private fun marker(message: String) = "--------- ${Instant.now()} $message ---------"
}

internal class DebugLogFiles(
    private val active: File, private val segmentBytes: Long = 5L * 1024 * 1024 / 2
) {
    private val previous = active.resolveSibling("${active.name}.1")
    private val trimmed = active.resolveSibling("${active.name}.trimmed")

    @Synchronized
    fun append(line: String) {
        val bytes = "$line\n".toByteArray(Charsets.UTF_8)
        if (bytes.size > segmentBytes) return
        active.parentFile?.mkdirs()
        if (active.length() > segmentBytes) {
            // Earlier releases could leave an unbounded file at this path.
            FileOutputStream(trimmed).use { output ->
                readTail(active, segmentBytes) { it.copyTo(output) }
            }
            if (!trimmed.renameTo(active)) throw IOException("Could not trim debug log")
        }
        if (active.length() + bytes.size > segmentBytes && !active.renameTo(previous)) {
            throw IOException("Could not rotate debug log")
        }
        FileOutputStream(active, true).use { it.write(bytes) }
    }

    @Synchronized
    fun appendTo(report: StringBuilder) {
        if (!active.isFile && !previous.isFile) return
        report.appendLine().appendLine("Debug logs")
        val activeBudget = minOf(active.length(), segmentBytes * 2)
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
        fun appendTail(file: File, budget: Long) = readTail(file, budget) { stream ->
            stream.reader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    report.appendRange(buffer, 0, count)
                }
            }
        }
        appendTail(previous, segmentBytes * 2 - activeBudget)
        appendTail(active, activeBudget)
    }

    @Synchronized
    fun clear(): Boolean = listOf(previous, active, trimmed).map {
        !it.exists() || it.delete()
    }.all { it }

    private inline fun readTail(file: File, budget: Long, read: (BufferedInputStream) -> Unit) {
        if (budget == 0L || !file.isFile) return
        FileInputStream(file).use { source ->
            val skipped = (source.channel.size() - budget).coerceAtLeast(0)
            if (skipped > 0) source.channel.position(skipped - 1)
            source.buffered().use { input ->
                if (skipped > 0) {
                    while (true) {
                        val byte = input.read()
                        if (byte == -1 || byte == '\n'.code) break
                    }
                }
                read(input)
            }
        }
    }
}
