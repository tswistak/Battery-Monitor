/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.devicebackup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class BackupImportInputStreamTest {
    @Test
    fun `empty and exact limit sources preserve their content`() {
        for (text in listOf("", "1234567890123456")) {
            val source = BackupImportInputStream(text.byteInputStream(), 16)
            assertEquals(text, source.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
        BackupImportInputStream(ByteArrayInputStream(ByteArray(16)), 16).use { source ->
            repeat(16) { assertEquals(0, source.read()) }
            assertEquals(-1, source.read())
        }
    }

    @Test
    fun `one byte over the limit fails for single byte and bulk reads`() {
        BackupImportInputStream(ByteArrayInputStream(ByteArray(17)), 16).use { source ->
            repeat(16) { assertEquals(0, source.read()) }
            assertThrows(IllegalArgumentException::class.java) { source.read() }
        }
        val output = ByteArrayOutputStream()
        BackupImportInputStream(ByteArrayInputStream(ByteArray(17)), 16).use { source ->
            assertThrows(IllegalArgumentException::class.java) { source.copyTo(output) }
        }
        assertTrue(output.size() <= 16)
    }

    @Test
    fun `fragmented reads count all raw bytes`() {
        val content = "1234567890123456"
        val source = object : ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 3))
        }
        val output = ByteArrayOutputStream()
        BackupImportInputStream(source, 16).use { it.copyTo(output) }
        assertEquals(content, output.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `UTF8 limit counts bytes instead of decoded characters`() {
        val text = "ą💡"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(6, bytes.size)
        assertEquals(
            text,
            BackupImportInputStream(ByteArrayInputStream(bytes), 6).bufferedReader(Charsets.UTF_8)
                .use { it.readText() })
        assertThrows(IllegalArgumentException::class.java) {
            BackupImportInputStream(ByteArrayInputStream(bytes), 5).bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }
    }

    @Test
    fun `an endless source is stopped before writing beyond the limit and is closed`() {
        var bytesRead = 0
        var closed = false
        val source = object : InputStream() {
            override fun read(): Int {
                bytesRead++
                return 0
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = minOf(length, 3)
                buffer.fill(0, offset, offset + count)
                bytesRead += count
                return count
            }

            override fun close() {
                closed = true
            }
        }
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            BackupImportInputStream(source, 16).use { it.copyTo(output) }
        }
        assertEquals(18, bytesRead)
        assertEquals(15, output.size())
        assertTrue(closed)
    }
}
