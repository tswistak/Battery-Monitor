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

import java.io.InputStream

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
