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

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal class BackupImportInputStream(
    private val source: InputStream, maxBytes: Int = GeneralBackup.MAX_ENTRY_BYTES
) : InputStream() {
    private var remaining = maxBytes.toLong()

    init {
        require(maxBytes >= 0)
    }

    override fun read(): Int = source.read().also { value ->
        if (value != -1) countBytes(1)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        source.read(buffer, offset, length).also { count ->
            if (count > 0) countBytes(count)
        }

    override fun close() = source.close()

    private fun countBytes(count: Int) {
        require(count <= remaining) { "Backup source is too large" }
        remaining -= count
    }
}

internal class BackupExportOutputStream(
    private val destination: OutputStream, private val maxBytes: Int = GeneralBackup.MAX_ENTRY_BYTES
) : OutputStream() {
    var bytesWritten = 0
        private set

    override fun write(value: Int) {
        checkSpace(1)
        destination.write(value)
        bytesWritten++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkSpace(length)
        destination.write(bytes, offset, length)
        bytesWritten += length
    }

    override fun flush() = destination.flush()

    private fun checkSpace(length: Int) {
        if (length > maxBytes - bytesWritten) throw IOException("Backup export is too large to restore")
    }
}

internal fun writeStagedBackupExport(
    context: Context, destination: () -> Uri, write: (OutputStream) -> Unit
) {
    val prepared = File.createTempFile("backup-export-", ".tmp", context.cacheDir)
    try {
        prepared.outputStream().buffered().use(write)
        val output = context.contentResolver.openOutputStream(destination(), "wt")
            ?: throw IOException("Could not open backup destination")
        output.use { prepared.inputStream().use { source -> source.copyTo(it) } }
    } finally {
        prepared.delete()
    }
}
