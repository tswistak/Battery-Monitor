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
package codes.swistak.batterymonitor.settings

import android.app.Application
import android.net.Uri
import codes.swistak.batterymonitor.alarms.AlarmRecord
import codes.swistak.batterymonitor.alarms.backup.AlarmBackup
import codes.swistak.batterymonitor.devicebackup.CsvLogImporter
import codes.swistak.batterymonitor.settings.backup.SettingsBackup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SettingsBackupIoTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun general_backup_cache_copy_preserves_its_boundary_and_rejects_oversized_sources() {
        assertEquals(160L * 1024 * 1024, GENERAL_BACKUP_DIALOG_CACHE_LIMIT_BYTES)
        val allowed = ByteArrayOutputStream()
        copyGeneralBackupDialogSource(ByteArrayInputStream(ByteArray(16)), allowed, 16)
        assertEquals(16, allowed.size())
        val oversized = ByteArrayOutputStream()
        try {
            copyGeneralBackupDialogSource(ByteArrayInputStream(ByteArray(17)), oversized, 16)
            fail("Oversized archives must fail before writing beyond the limit")
        } catch (_: IllegalArgumentException) {
            assertTrue(oversized.size() <= 16)
        }
    }

    private val settingsJson = """{"version":4,"settings":{"enable_logging":false},"note":"ą💡"}"""
    private val alarmsJson =
        """{"version":2,"alarms":[{"type":"charge_rises","threshold":"83"}],"note":"ą💡"}"""

    @Test
    fun standalone_settings_and_alarm_readers_enforce_exact_UTF8_byte_limits() {
        val settings = temporaryFolder.newFile("settings.json")
        val alarms = temporaryFolder.newFile("alarms.json")
        val settingsUri = Uri.fromFile(settings)
        val alarmsUri = Uri.fromFile(alarms)

        settings.writeText(settingsJson.padToBytes(SettingsBackup.MAX_IMPORT_BYTES))
        alarms.writeText(alarmsJson.padToBytes(AlarmBackup.MAX_IMPORT_BYTES))
        assertEquals(
            false, SettingsBackup.readFromUri(
                context, settingsUri
            ).second[SettingsContract.KEY_ENABLE_LOGGING]
        )
        assertEquals(
            listOf(AlarmRecord(true, "charge_rises", "83")),
            AlarmBackup.readFromUri(context, alarmsUri).alarms
        )

        settings.appendText(" ")
        alarms.appendText(" ")
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackup.readFromUri(
                context, settingsUri
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AlarmBackup.readFromUri(
                context, alarmsUri
            )
        }
    }

    @Test
    fun standalone_JSON_limits_reject_nested_large_and_overpopulated_irrelevant_fields() {
        val source = temporaryFolder.newFile("attack.json")
        val uri = Uri.fromFile(source)
        for (value in listOf(
            "[".repeat(65) + "0" + "]".repeat(65),
            "\"" + "a".repeat(64 * 1024 + 1) + "\"",
            "[" + "0,".repeat(250_000) + "0]"
        )) {
            source.writeText(settingsJson.replace("\"ą💡\"", value))
            assertThrows(IllegalArgumentException::class.java) {
                SettingsBackup.readFromUri(
                    context, uri
                )
            }
            source.writeText(alarmsJson.replace("\"ą💡\"", value))
            assertThrows(IllegalArgumentException::class.java) {
                AlarmBackup.readFromUri(
                    context, uri
                )
            }
        }
    }

    @Test
    fun successful_standalone_exports_are_reimportable_and_oversized_exports_preserve_destination() {
        val source = temporaryFolder.newFile("export.json")
        val uri = Uri.fromFile(source)
        SettingsBackup.writeToUri(context, uri, JSONObject(settingsJson))
        assertEquals(4, SettingsBackup.readFromUri(context, uri).first)
        AlarmBackup.writeToUri(context, uri, JSONObject(alarmsJson))
        assertEquals(2, AlarmBackup.readFromUri(context, uri).version)

        val before = source.readText()
        val tooLarge = JSONObject(alarmsJson).put("note", "a".repeat(AlarmBackup.MAX_IMPORT_BYTES))
        assertThrows(IllegalArgumentException::class.java) {
            AlarmBackup.writeToUri(
                context, uri, tooLarge
            )
        }
        val longToken = JSONObject(settingsJson).put("note", "a".repeat(64 * 1024 + 1))
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackup.writeToUri(
                context, uri, longToken
            )
        }
        assertEquals(before, source.readText())
    }

    @Test
    fun csv_uri_reader_preserves_utf8_and_line_breaks() {
        val text = "ą💡\r\n"
        val source = temporaryFolder.newFile("utf8-source").apply { writeText(text) }
        assertEquals(
            text, CsvLogImporter.withCsvReader(context, Uri.fromFile(source)) { it.readText() })
    }

    private fun String.padToBytes(limit: Int) = this + " ".repeat(limit - toByteArray().size)
}
