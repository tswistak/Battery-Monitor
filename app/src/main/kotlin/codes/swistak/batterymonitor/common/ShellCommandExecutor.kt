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

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask

private const val COMMAND_TIMEOUT_SECONDS = 10L
private const val MAX_COMMAND_OUTPUT_BYTES = 256 * 1024

internal interface CommandExecutor {
    fun run(command: String): String?
    fun runRaw(command: String): String? = run(command)
}

internal class RootExecutor : CommandExecutor {
    override fun run(command: String): String? = runCommand(arrayOf("su", "-c", command))
    override fun runRaw(command: String): String? =
        runCommand(arrayOf("su", "-c", command), trimOutput = false)
}

internal class PrivilegedShellExecutor : CommandExecutor {
    override fun run(command: String): String? = runCommand(arrayOf("sh", "-c", command))
    override fun runRaw(command: String): String? =
        runCommand(arrayOf("sh", "-c", command), trimOutput = false)
}


private fun runCommand(command: Array<String>, trimOutput: Boolean = true): String? {
    var process: Process? = null
    var reader: Thread? = null

    try {
        process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val input = process.inputStream
        val output = FutureTask { input.use(::readFully) }
        reader = Thread(output, "battery-command-output").apply { isDaemon = true; start() }
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }

        if (process.exitValue() != 0) return null

        val text = output.get(1, TimeUnit.SECONDS) ?: return null
        return if (trimOutput) text.trim().takeIf(String::isNotEmpty) else text
    } catch (_: Exception) {
        return null
    } finally {
        process?.destroy()
        runCatching { process?.inputStream?.close() }
        reader?.interrupt()
    }
}


@Throws(Exception::class)
private fun readFully(inputStream: InputStream): String? {
    val outputStream = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    var bytesRead: Int

    while ((inputStream.read(buffer).also { bytesRead = it }) != -1) {
        if (outputStream.size() + bytesRead > MAX_COMMAND_OUTPUT_BYTES) return null

        outputStream.write(buffer, 0, bytesRead)
    }

    return String(outputStream.toByteArray(), StandardCharsets.UTF_8)
}
