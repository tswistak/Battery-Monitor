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

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>.
*/
package codes.swistak.batterymonitor.devicebackup

import android.app.Application
import android.net.Uri
import codes.swistak.batterymonitor.logs.LogRecord
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class GeneralBackupArchiveTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val logsFile get() = File(temporary.root, "prepared.logs")

    @Test
    fun malformed_missing_duplicate_and_unsafe_archive_entries_are_rejected() {
        val settings = """{"version":4,"settings":{}}"""
        val metadata = metadata(listOf("settings.json"))
        val invalidArchives = listOf(
            "not a ZIP".toByteArray(),
            zip(mapOf("settings.json" to settings)),
            zip(mapOf("metadata.json" to "not JSON", "settings.json" to settings)),
            zip(mapOf("metadata.json" to metadata)),
            zip(
                mapOf(
                    "metadata.json" to metadata(listOf("settings.json", "settings.json")),
                    "settings.json" to settings
                )
            ),
            zip(
                mapOf(
                    "metadata.json" to metadata,
                    "settings.json" to settings,
                    "../escape.json" to "{}"
                )
            ),
            zip(
                mapOf(
                    "metadata.json" to metadata,
                    "settings.json" to settings,
                    "nested\\file.json" to "{}"
                )
            ),
            zip(
                mapOf(
                    "metadata.json" to metadata, "settings.json" to settings, "directory/" to ""
                )
            ),
            duplicateEntryArchive(metadata)
        )
        for ((index, archive) in invalidArchives.withIndex()) {
            assertThrows(
                "Archive $index should be rejected", Exception::class.java
            ) { read(archive) }
        }
    }

    @Test
    fun unsupported_versions_are_rejected_and_newer_compatible_schemas_are_reported() {
        for (version in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                read(
                    zip(
                        mapOf(
                            "metadata.json" to metadata(listOf("settings.json"), version),
                            "settings.json" to """{"version":4,"settings":{}}"""
                        )
                    )
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            read(
                zip(
                    mapOf(
                        "metadata.json" to metadata(listOf("future.json")), "future.json" to "{}"
                    )
                )
            )
        }
        val entries = mapOf(
            "metadata.json" to metadata(Version1GeneralBackupSchema.expectedFiles),
            "settings.json" to """{"version":4,"settings":{}}""",
            "alarms.json" to """{"version":2,"alarms":[]}""",
            "device-specific.json" to """{"version":2,"logs":[],"predictor":{"stateVersion":2}}"""
        )
        val newerVersions = entries.map { (file, contents) ->
            file to JSONObject(contents).put("version", 999).toString()
        }
        for ((file, contents) in newerVersions) {
            val newer = read(zip(entries + (file to contents)))
            assertTrue(
                "Newer $file schema was not reported", GeneralBackup.containsNewerSchema(newer)
            )
            assertEquals(
                GeneralBackupDataType.entries.toSet(), GeneralBackup.getAvailableData(newer)
            )
        }
    }

    @Test
    fun archive_limits_count_uncompressed_bytes_at_exact_boundaries() {
        val entries = mapOf(
            "metadata.json" to metadata(listOf("settings.json")),
            "settings.json" to """{"version":4,"settings":{}}""",
            "future.json" to "ą".repeat(512)
        )
        val compressed = zip(entries)
        val total = entries.values.sumOf { it.toByteArray(Charsets.UTF_8).size }
        assertTrue(compressed.size < 1024)
        val archive =
            GeneralBackup.readArchive(ByteArrayInputStream(compressed), logsFile, 1024, total)
        assertEquals(setOf(GeneralBackupDataType.SETTINGS), GeneralBackup.getAvailableData(archive))

        val entryFailure = assertThrows(IllegalArgumentException::class.java) {
            GeneralBackup.readArchive(ByteArrayInputStream(compressed), logsFile, 1023, total)
        }
        assertEquals("General backup entry is too large", entryFailure.message)
        val totalFailure = assertThrows(IllegalArgumentException::class.java) {
            GeneralBackup.readArchive(ByteArrayInputStream(compressed), logsFile, 1024, total - 1)
        }
        assertEquals("General backup is too large", totalFailure.message)
    }

    @Test
    fun empty_entries_and_UTF8_entry_names_are_bounded() {
        val base = mapOf(
            "metadata.json" to metadata(listOf("settings.json")),
            "settings.json" to """{"version":4,"settings":{}}"""
        )
        val manyEmptyEntries = (1..GeneralBackup.MAX_ENTRIES).associate { "unknown-$it" to "" }
        assertThrows(IllegalArgumentException::class.java) { read(zip(base + manyEmptyEntries)) }
        val overlongName = "ą".repeat(GeneralBackup.MAX_ENTRY_NAME_BYTES / 2 + 1)
        assertThrows(IllegalArgumentException::class.java) { read(zip(base + (overlongName to ""))) }
        val extra = ByteArray(GeneralBackup.MAX_METADATA_BYTES)
        val withOversizedExtra = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { output ->
                base.forEach { (name, content) ->
                    output.putNextEntry(ZipEntry(name).apply { this.extra = extra })
                    output.write(content.toByteArray())
                    output.closeEntry()
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { read(withOversizedExtra.toByteArray()) }
    }

    @Test
    fun truncated_central_directory_is_rejected_even_when_local_entries_are_complete() {
        val complete = zip(
            mapOf(
                "metadata.json" to metadata(listOf("settings.json")),
                "settings.json" to """{"version":4,"settings":{}}"""
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            read(complete.copyOf(complete.size - 22))
        }
        val badName = complete.copyOf()
        val centralStart = badName.indices.first { index ->
            badName[index] == 0x50.toByte() && badName[index + 1] == 0x4b.toByte() && badName[index + 2] == 1.toByte() && badName[index + 3] == 2.toByte()
        }
        badName[centralStart + 46] = 'x'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) { read(badName) }
        assertEquals(
            setOf(GeneralBackupDataType.SETTINGS), GeneralBackup.getAvailableData(read(complete))
        )
    }

    @Test
    fun lenient_quotes_cannot_bypass_backup_JSON_depth_limits() {
        val malformedManifest =
            """{"version":1,"files":["settings.json"],"ignored":'${"]".repeat(150)}',"deep":${
                "[".repeat(125)
            }0${"]".repeat(125)}}"""
        assertThrows(Exception::class.java) {
            read(
                zip(
                    mapOf(
                        "metadata.json" to malformedManifest,
                        "settings.json" to """{"version":4,"settings":{}}"""
                    )
                )
            )
        }
    }

    @Test
    fun oversized_staged_export_preserves_the_existing_destination() {
        val context = RuntimeEnvironment.getApplication()
        val export = temporary.newFile("history.csv").apply { writeText("existing history") }
        assertThrows(IOException::class.java) {
            writeStagedBackupExport(context, { Uri.fromFile(export) }) { output ->
                val bounded = BackupExportOutputStream(output, 8)
                bounded.write("header\r\n".toByteArray())
                bounded.write("row\r\n".toByteArray())
            }
        }
        assertEquals("existing history", export.readText())
    }

    @Test
    fun streamed_JSON_history_preserves_large_ordered_input_and_empty_histories() {
        val context = RuntimeEnvironment.getApplication()
        val export = temporary.newFile("history.json")
        fun record(index: Int) = LogRecord(
            status = if (index % 2 == 0) 205 else -1,
            charge = if (index % 2 == 0) 50 else null,
            time = index / 2L,
            temperature = null,
            voltage = null,
            currentMicroAmps = if (index % 2 == 0) Long.MIN_VALUE else null,
            remainingChargeMicroampHours = if (index % 2 == 0) Long.MAX_VALUE else null
        )
        for (count in listOf(0, 25_000)) {
            export.outputStream().use {
                DeviceDataBackup.writeHistory(
                    context, it, (0 until count).asSequence().map(::record)
                )
            }
            val backup = export.inputStream().use { DeviceDataBackup.read(it, logsFile) }
            assertEquals(setOf(DeviceDataType.LOGS), backup.available)
            val readCount = requireNotNull(backup.logs).getOrThrow().read { logs ->
                var actualCount = 0
                logs.forEach { assertEquals(record(actualCount++), it) }
                actualCount
            }
            assertEquals(count, readCount)
        }
    }

    @Test
    fun truncated_or_excessively_nested_device_data_leaves_no_prepared_history() {
        val complete = """{"version":2,"logs":[{"status":100,"time":1}]"""
        val invalidInputs = listOf(
            complete,
            complete + ",\"future\":" + "[".repeat(65) + "]".repeat(65) + "}",
            complete + ",\"future\":\"" + "a".repeat(64 * 1024 + 1) + "\"}"
        )
        for (invalid in invalidInputs) {
            assertThrows(Exception::class.java) {
                DeviceDataBackup.read(
                    invalid.byteInputStream(), logsFile
                )
            }
            assertTrue("Rejected import left staged log data", !logsFile.exists())
        }
    }

    private fun metadata(files: List<String>, version: Int = GeneralBackup.SCHEMA_VERSION): String =
        JSONObject().put("version", version).put("files", JSONArray(files)).toString()

    private fun zip(entries: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output ->
            for ((name, contents) in entries) {
                output.putNextEntry(ZipEntry(name))
                output.write(contents.toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun duplicateEntryArchive(metadata: String): ByteArray {
        val settings = """{"version":4,"settings":{}}"""
        val duplicateName = "settings2json".toByteArray(Charsets.UTF_8)
        val actualName = "settings.json".toByteArray(Charsets.UTF_8)
        val bytes = zip(
            mapOf(
                "metadata.json" to metadata,
                "settings.json" to settings,
                "settings2json" to settings
            )
        )
        var replacements = 0
        for (index in 0..bytes.size - duplicateName.size) {
            if (duplicateName.indices.all { bytes[index + it] == duplicateName[it] }) {
                actualName.copyInto(bytes, index)
                replacements++
            }
        }
        assertEquals(2, replacements)
        return bytes
    }

    private fun read(bytes: ByteArray): GeneralBackupArchive =
        GeneralBackup.readArchive(ByteArrayInputStream(bytes), logsFile)
}
