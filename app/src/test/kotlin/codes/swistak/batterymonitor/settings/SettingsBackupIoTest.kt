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
import codes.swistak.batterymonitor.alarms.backup.AlarmBackup
import codes.swistak.batterymonitor.devicebackup.CsvLogImporter
import codes.swistak.batterymonitor.devicebackup.DeviceDataBackup
import codes.swistak.batterymonitor.settings.backup.SettingsBackup
import org.junit.Assert.assertEquals
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

    @Test
    fun standalone_uri_readers_preserve_UTF8_and_settings_line_normalization() {
        val text = "ą💡\r\n"
        val source =
            temporaryFolder.newFile("utf8-source").apply { writeText(text, Charsets.UTF_8) }
        val uri = Uri.fromFile(source)
        assertEquals("ą💡\n", SettingsBackup.readFromUri(context, uri))
        assertEquals(text, AlarmBackup.readFromUri(context, uri))
        assertEquals(text, DeviceDataBackup.readFromUri(context, uri))
        assertEquals(text, CsvLogImporter.readFromUri(context, uri))
    }
}
