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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class RootShellProtocolTest {
    private val token = "fixed-test-token"
    private val end = RootShellProtocol.endMarker(token)

    @Test
    fun `raw frames preserve unicode whitespace empty output and final newline distinctions`() {
        for (text in listOf(
            "",
            "  Zażółć 😀\r\n\t",
            "last line",
            "last line\n",
            "\n",
            "a\u0000BM_fixed-test-x\u0000b"
        )) {
            val input = frame(text.toByteArray(), 0)
            assertEquals(RootCommandResult(0, text), RootShellProtocol.readFrame(input, end))
            assertEquals('!'.code, input.read())
        }
    }

    @Test
    fun `large output is drained and the cap counts bytes without retaining framing`() {
        val exact = ByteArray(256 * 1024) { 'x'.code.toByte() }
        assertEquals(exact.size, RootShellProtocol.readFrame(frame(exact, 0), end).output?.length)
        val over = frame(exact + byteArrayOf('y'.code.toByte()), 42)
        val result = RootShellProtocol.readFrame(over, end)
        assertEquals(42, result.exitCode)
        assertNull(result.output)
        assertEquals('!'.code, over.read())
    }

    @Test
    fun `truncated frames and invalid status fail instead of publishing partial output`() {
        assertThrows(IOException::class.java) {
            RootShellProtocol.readFrame(ByteArrayInputStream("partial".toByteArray() + end), end)
        }
        for (status in listOf("", "-1", "256", "1234", "x")) {
            assertThrows(IOException::class.java) {
                RootShellProtocol.readFrame(
                    ByteArrayInputStream(end + "$status\u0000".toByteArray()), end
                )
            }
        }
    }

    @Test
    fun `cleanup acknowledgement discards residual output and leaves the stream aligned`() {
        val marker = RootShellProtocol.ackMarker(token)
        val input =
            ByteArrayInputStream(ByteArray(300000) { 'x'.code.toByte() } + marker + "next".toByteArray())
        RootShellProtocol.readUntil(input, marker, capture = false)
        assertEquals("next", input.readBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun `generated shell scripts emit actual NUL framing and preserve nested quoting`() {
        val ready = shell("setsid() { :; }\n" + RootShellProtocol.readiness(token))
        assertEquals(
            RootCommandResult(0, ""),
            RootShellProtocol.readFrame(ByteArrayInputStream(ready), "\u0000$token:".toByteArray())
        )

        // Let sh decode the real nested argument without requiring host setsid or root.
        for ((command, expected) in listOf(
            """printf '  Zażółć "quoted"\r\n'""" to "  Zażółć \"quoted\"\r\n",
            "printf '%s' '  Zażółć 😀\r\n\t'" to "  Zażółć 😀\r\n\t"
        )) {
            // Keep the parent shell alive until its background stub has emitted the supervisor.
            val supervisor = shell(
                $$"setsid() { printf '%s' \"$3\"; }\n" + RootShellProtocol.command(
                    command, token
                ) + "wait\n"
            ).toString(Charsets.UTF_8)
            val commandStart = supervisor.indexOf("sh -c ")
            val commandEnd = supervisor.indexOf("\n__bm_status=", commandStart)
            assertEquals(
                expected,
                shell(supervisor.substring(commandStart, commandEnd)).toString(Charsets.UTF_8)
            )
            val statusLine = supervisor.lineSequence().first { it.startsWith("printf ") }
            val completed = shell("__bm_status=17\n$statusLine\n")
            assertEquals(
                RootCommandResult(17, ""),
                RootShellProtocol.readFrame(ByteArrayInputStream(completed), end)
            )
        }

        val acknowledged =
            shell("__bm_token='$token'\n__bm_job=\n" + RootShellProtocol.acknowledge(token))
        assertTrue(RootShellProtocol.ackMarker(token).contentEquals(acknowledged))
    }

    private fun frame(output: ByteArray, status: Int) =
        ByteArrayInputStream(output + end + "$status\u0000!".toByteArray())

    private fun shell(script: String): ByteArray {
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        try {
            assertTrue(
                "Generated shell script did not finish", process.waitFor(2, TimeUnit.SECONDS)
            )
            assertEquals("Generated shell script failed", 0, process.exitValue())
            return process.inputStream.readBytes()
        } finally {
            process.destroyForcibly()
        }
    }
}
