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
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

internal object DebugLogCollector {
    private const val LOG_TAG = "DebugLogCollector"
    private const val DIRECTORY_NAME = "diagnostics"
    private const val FILE_NAME = "battery-monitor-debug.log"
    private const val MAX_FILE_SIZE_BYTES = 5L * 1024L * 1024L

    private var process: Process? = null

    fun shouldStartInProcess(processName: String?): Boolean =
        !processName.isNullOrEmpty() && !processName.endsWith(".BIS")

    @Synchronized
    fun sync(context: Context, enabled: Boolean) {
        if (!enabled) {
            stop()
            return
        }
        if (process?.isAlive == true) return

        val file = logFile(context)
        file.parentFile?.mkdirs()
        if (file.length() >= MAX_FILE_SIZE_BYTES) file.delete()
        appendMarker(file, "Debug log collection started")

        process = try {
            ProcessBuilder("/system/bin/logcat", "-v", "threadtime", "-T", "1").redirectErrorStream(
                true
            ).redirectOutput(ProcessBuilder.Redirect.appendTo(file)).start()
        } catch (error: Exception) {
            Log.e(LOG_TAG, "Unable to start debug log collection", error)
            appendMarker(file, "Unable to start logcat: ${error.javaClass.simpleName}")
            null
        }
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
    }

    fun logFile(context: Context): File = File(
        File(context.filesDir, DIRECTORY_NAME), FILE_NAME
    )

    fun clear(context: Context): Boolean {
        val wasRunning = synchronized(this) { process?.isAlive == true }
        stop()
        val deleted = logFile(context).let { !it.exists() || it.delete() }
        if (wasRunning) sync(context, true)
        return deleted
    }

    private fun appendMarker(file: File, message: String) {
        runCatching {
            FileOutputStream(file, true).bufferedWriter().use { writer ->
                writer.appendLine("--------- ${Instant.now()} $message ---------")
            }
        }
    }
}
