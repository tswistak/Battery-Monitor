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
package codes.swistak.batterymonitor.logs

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import codes.swistak.batterymonitor.devicebackup.CsvLogImporter
import codes.swistak.batterymonitor.devicebackup.GeneralBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CsvLogAppendTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://csv-append/document/history")
    private val legacyHeader = "Date,Time,Status,Charge,Temperature,Temperature F,Voltage"
    private val modernHeader = "$legacyHeader,Current (µA),Remaining charge (µAh),Power (W)\r\n"
    private val oldRow = "date,time,Discharging,50,30,86,4.0"
    private val original = "$legacyHeader\r\n$oldRow"
    private val sample = LogRecord(200, 51, 200, 300, 4100, -165_000, 3_060_000)
    private lateinit var file: File
    private lateinit var provider: ExportProvider

    @Before
    fun setUp() {
        file = temporary.newFile("history").apply { writeText(original) }
        provider = ExportProvider(temporary.root)
        provider.attachInfo(context, ProviderInfo().apply { authority = "csv-append" })
        ShadowContentResolver.registerProviderInternal("csv-append", provider)
    }

    @Test
    fun invalid_source_and_failed_open_leave_the_original_unmodified() {
        val invalid = "$legacyHeader\r\ninsufficient,columns"
        file.writeText(invalid)
        assertThrows(IllegalArgumentException::class.java) { append() }
        assertEquals(invalid, file.readText())
        assertEquals(0, provider.writes)

        file.writeText(original)
        provider.writeOverride = { throw FileNotFoundException("Storage unavailable") }
        assertThrows(Exception::class.java) { append() }
        assertEquals(original, file.readText())
        assertEquals(1, provider.writes)
        assertTrue(recoveryCopies().isEmpty())
    }

    @Test
    fun failed_write_and_silently_truncated_replacement_are_rolled_back() {
        val discarded = temporary.newFile("discarded")
        for (mode in listOf(
            ParcelFileDescriptor.MODE_READ_ONLY, ParcelFileDescriptor.MODE_WRITE_ONLY
        )) {
            provider.writeOverride = {
                file.writeText(modernHeader)
                provider.writeOverride = null
                ParcelFileDescriptor.open(discarded, mode)
            }
            assertThrows(Exception::class.java) { append() }
            assertEquals(original, file.readText())
            assertTrue(recoveryCopies().isEmpty())
        }
        assertUpgradedAndAppended()
    }

    @Test
    fun interrupted_replacement_is_recovered_before_checking_the_same_document_header() {
        provider.writeOverride = {
            file.writeText(modernHeader)
            throw SimulatedProcessDeath()
        }
        assertThrows(SimulatedProcessDeath::class.java) { append() }
        assertEquals(modernHeader, file.readText())
        assertEquals(original, recoveryCopies().single().readText())

        provider.writeOverride = null
        val other = temporary.newFile("other").apply { writeText(modernHeader) }
        LogExport.appendCsv(
            context, Uri.parse("content://csv-append/document/other"), emptySequence()
        )
        assertEquals(modernHeader, other.readText())
        assertEquals(modernHeader, file.readText())
        assertEquals(1, recoveryCopies().size)

        val treeUri = Uri.parse("content://csv-append/tree/root/document/history")
        val writes = provider.writes
        val failure = assertThrows(LogExportRecoveryException::class.java) {
            CsvLogImporter.withCsvReader(context, treeUri) { it.readText() }
        }
        assertTrue(failure.message.orEmpty().contains(recoveryCopies().single().name))
        assertEquals(writes, provider.writes)
        assertEquals(modernHeader, file.readText())

        assertUpgradedAndAppended(treeUri)
        assertEquals(
            file.readText(), CsvLogImporter.withCsvReader(context, treeUri) { it.readText() })
    }

    @Test
    fun unavailable_recovery_preserves_the_original_for_a_later_retry() {
        val discarded = temporary.newFile("discarded")
        provider.writeOverride = {
            file.writeText(modernHeader)
            ParcelFileDescriptor.open(discarded, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val failure = assertThrows(LogExportRecoveryException::class.java) { append() }
        val copy = recoveryCopies().single()
        assertTrue(failure.message.orEmpty().contains(copy.name))
        assertEquals(original, copy.readText())

        provider.writeOverride = null
        assertUpgradedAndAppended()
    }

    @Test
    fun append_that_would_exceed_the_import_limit_leaves_destination_unchanged() {
        file.writeText(modernHeader)
        val size = GeneralBackup.MAX_ENTRY_BYTES.toLong()
        RandomAccessFile(file, "rw").use { it.setLength(size) }
        assertThrows(Exception::class.java) { append() }
        assertEquals(size, file.length())
        assertEquals(0, provider.writes)
    }

    private fun append(target: Uri = uri) = LogExport.appendCsv(context, target, sequenceOf(sample))

    private fun assertUpgradedAndAppended(target: Uri = uri) {
        append(target)
        val csv = file.readText()
        val rows = CsvLogImporter.parseCsv(csv)
        assertTrue(LogExport.hasMeasurementCsvHeader(rows.first()))
        assertEquals("$oldRow,,,", rows[1].joinToString(","))
        assertEquals(
            sample,
            CsvLogImporter.decodeRecords(rows.asSequence(), { 100 }, { _, _ -> sample.time }).last()
        )
        assertEquals(3, rows.size)
        assertTrue(recoveryCopies().isEmpty())
    }

    private fun recoveryCopies(): List<File> =
        File(context.filesDir, "export-recovery").listFiles().orEmpty()
            .filter { it.extension == "csv-original" }

    private class SimulatedProcessDeath : Error()

    private class ExportProvider(private val directory: File) : ContentProvider() {
        var writeOverride: (() -> ParcelFileDescriptor)? = null
        var writes = 0

        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/csv"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
        ) = 0

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? = null

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val target = File(directory, requireNotNull(uri.lastPathSegment))
            if (mode == "r") return ParcelFileDescriptor.open(
                target, ParcelFileDescriptor.MODE_READ_ONLY
            )
            writes++
            return writeOverride?.invoke() ?: ParcelFileDescriptor.open(
                target, ParcelFileDescriptor.parseMode(mode)
            )
        }
    }
}
