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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class GeneralBackupArchiveTest {
    private val allData = GeneralBackupDataType.entries.toSet()

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
        val archive = read(
            zip(
                mapOf(
                    "metadata.json" to metadata(Version1GeneralBackupSchema.expectedFiles),
                    "settings.json" to """{"version":4,"settings":{}}""",
                    "alarms.json" to """{"version":2,"alarms":[]}""",
                    "device-specific.json" to """{"version":2,"logs":[],"predictor":{"stateVersion":2}}"""
                )
            )
        )
        val newerVersions = listOf(
            "metadata.json" to metadata(
                Version1GeneralBackupSchema.expectedFiles, GeneralBackup.SCHEMA_VERSION + 1
            ),
            "settings.json" to JSONObject(archive.fileContents.getValue("settings.json")).put(
                "version", 999
            ).toString(),
            "alarms.json" to JSONObject(archive.fileContents.getValue("alarms.json")).put(
                "version", 999
            ).toString(),
            "device-specific.json" to JSONObject(archive.fileContents.getValue("device-specific.json")).put(
                "version", 999
            ).toString()
        )
        for ((file, contents) in newerVersions) {
            val newer = read(zip(archive.fileContents + (file to contents)))
            assertTrue(
                "Newer $file schema was not reported", GeneralBackup.containsNewerSchema(newer)
            )
            GeneralBackup.validateSelectedData(newer, allData)
        }
    }

    @Test
    fun archive_limits_count_uncompressed_bytes_at_exact_boundaries() {
        assertEquals(100 * 1024 * 1024, GeneralBackup.MAX_ENTRY_BYTES)
        assertEquals(150 * 1024 * 1024, GeneralBackup.MAX_TOTAL_BYTES)
        val entries = mapOf(
            "metadata.json" to metadata(listOf("settings.json")), "settings.json" to "ą".repeat(512)
        )
        val compressed = zip(entries)
        val total = entries.values.sumOf { it.toByteArray(Charsets.UTF_8).size }
        assertTrue(compressed.size < 1024)
        val archive = GeneralBackup.readArchive(ByteArrayInputStream(compressed), 1024, total)
        assertEquals(
            entries.getValue("settings.json"), archive.fileContents.getValue("settings.json")
        )

        val entryFailure = assertThrows(IllegalArgumentException::class.java) {
            GeneralBackup.readArchive(ByteArrayInputStream(compressed), 1023, total)
        }
        assertEquals("General backup entry is too large", entryFailure.message)
        val totalFailure = assertThrows(IllegalArgumentException::class.java) {
            GeneralBackup.readArchive(ByteArrayInputStream(compressed), 1024, total - 1)
        }
        assertEquals("General backup is too large", totalFailure.message)
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
        GeneralBackup.readArchive(ByteArrayInputStream(bytes))
}
