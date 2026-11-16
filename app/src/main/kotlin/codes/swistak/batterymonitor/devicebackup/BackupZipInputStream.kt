/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.devicebackup

import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class BackupZipInputStream(input: InputStream) :
    FilterInputStream(BackupImportInputStream(input, GeneralBackup.MAX_ARCHIVE_BYTES)) {
    private val tail = ByteArray(
        2 * GeneralBackup.MAX_METADATA_BYTES + GeneralBackup.MAX_ENTRIES * CENTRAL_HEADER + END_HEADER
    )
    private var total = 0

    override fun read(): Int = super.read().also { value ->
        if (value != -1) tail[total++ % tail.size] = value.toByte()
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        super.read(bytes, offset, length).also { count ->
            if (count > 0) {
                val kept = minOf(count, tail.size)
                val start = (total + count - kept) % tail.size
                val first = minOf(kept, tail.size - start)
                bytes.copyInto(tail, start, offset + count - kept, offset + count - kept + first)
                bytes.copyInto(tail, 0, offset + count - kept + first, offset + count)
                total += count
            }
        }

    fun validateDirectory(entryNames: Set<String>) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (read(buffer) != -1) {
            // Capture only the bounded tail as the compressed stream is drained.
        }
        val length = minOf(total, tail.size)
        val start = (total - length) % tail.size
        val bytes = ByteArray(length)
        val first = minOf(length, tail.size - start)
        tail.copyInto(bytes, 0, start, start + first)
        tail.copyInto(bytes, first, 0, length - first)
        val directory = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun unsignedShort(offset: Int) = directory.getShort(offset).toInt() and 0xffff
        val end = (length - END_HEADER downTo 0).firstOrNull { offset ->
            directory.getInt(offset) == END_SIGNATURE && offset + END_HEADER + unsignedShort(offset + 20) == length
        } ?: throw IllegalArgumentException("Incomplete ZIP directory")

        val count = unsignedShort(end + 10)
        val size = directory.getInt(end + 12)
        val offset = directory.getInt(end + 16)
        require(
            directory.getInt(end + 4) == 0 && unsignedShort(end + 8) == count && count == entryNames.size && size in count * CENTRAL_HEADER..minOf(
                end, count * CENTRAL_HEADER + GeneralBackup.MAX_METADATA_BYTES
            ) && offset >= 0 && offset.toLong() + size == total.toLong() - length + end && unsignedShort(
                end + 20
            ) <= GeneralBackup.MAX_METADATA_BYTES
        ) {
            "Invalid ZIP directory"
        }

        var position = end - size
        val remaining = entryNames.toMutableSet()
        val localOffsets = mutableSetOf<Int>()
        repeat(count) {
            require(position <= end - CENTRAL_HEADER && directory.getInt(position) == CENTRAL_SIGNATURE) {
                "Invalid ZIP directory entry"
            }
            val nameLength = unsignedShort(position + 28)
            val localOffset = directory.getInt(position + 42)
            val nameStart = position + CENTRAL_HEADER
            val next =
                nameStart + nameLength + unsignedShort(position + 30) + unsignedShort(position + 32)
            require(
                nameLength in 1..GeneralBackup.MAX_ENTRY_NAME_BYTES && next <= end && localOffset in 0 until offset && localOffsets.add(
                    localOffset
                ) && unsignedShort(
                    position + 34
                ) == 0
            ) {
                "Invalid ZIP directory entry"
            }
            require(remaining.remove(String(bytes, nameStart, nameLength, Charsets.UTF_8))) {
                "ZIP directory does not match its entries"
            }
            position = next
        }
        require(position == end && remaining.isEmpty()) { "Invalid ZIP directory length" }
    }

    private companion object {
        const val CENTRAL_HEADER = 46
        const val END_HEADER = 22
        const val CENTRAL_SIGNATURE = 0x02014b50
        const val END_SIGNATURE = 0x06054b50
    }
}
