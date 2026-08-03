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

private const val COMMAND_TIMEOUT_SECONDS = 10L
private const val MAX_COMMAND_OUTPUT_BYTES = 256 * 1024

internal interface CommandExecutor {
    fun run(command: String): String?
}

internal class RootExecutor : CommandExecutor {
    override fun run(command: String): String? {
        return runCommand(arrayOf("su", "-c", command))
    }
}

internal class PrivilegedShellExecutor : CommandExecutor {
    override fun run(command: String): String? {
        return runCommand(arrayOf("sh", "-c", command))
    }
}

private fun runCommand(command: Array<String>): String? {
    var process: Process? = null

    try {
        process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }

        if (process.exitValue() != 0) return null

        val output = readFully(process.inputStream)?.trim() ?: return null
        return output.takeIf(String::isNotEmpty)
    } catch (_: Exception) {
        return null
    } finally {
        process?.destroy()
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
