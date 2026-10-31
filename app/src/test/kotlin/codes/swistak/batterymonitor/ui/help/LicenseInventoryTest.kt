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
package codes.swistak.batterymonitor.ui.help

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LicenseInventoryTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun inventory_preserves_component_mappings_attributions_and_all_offline_files() {
        val components = loadLicenseInventory(context.assets)
        assertTrue(components.isNotEmpty())
        assertEquals(components.size, components.map { it.id }.toSet().size)
        for ((id, name, _, _, license, attribution, sources, files) in components) {
            assertTrue(name.isNotBlank())
            assertTrue(license.isNotBlank())
            assertTrue(attribution.isNotBlank())
            assertTrue(sources.isNotEmpty())
            assertTrue(files.isNotEmpty())
            for (filename in files) {
                assertNotNull(
                    "$id: $filename", readLicenseText(context.assets, filename)
                )
            }
        }
        val application = components.single { it.id == "battery-monitor" }
        assertEquals("GPL-3.0-or-later", application.license)
        assertTrue(application.attribution.contains("Darshan"))
        assertTrue("GPL-3.0.txt" in application.files)
        assertEquals(
            "CC-BY-SA-3.0", components.single { it.id == "battery-indicator-pro-artwork" }.license
        )
        assertEquals(
            "Apache-2.0", components.single { it.id == "google-material-icons" }.license
        )
        assertTrue(components.any {
            it.id.contains(
                "shizuku", ignoreCase = true
            ) && it.license == "MIT" && it.attribution.contains("RikkaW") && "Shizuku-API-MIT.txt" in it.files
        })
    }

    @Test
    fun packaged_full_legal_texts_keep_the_audited_authoritative_bytes() {
        val hashes = mapOf(
            "GPL-3.0.txt" to "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986",
            "Apache-2.0.txt" to "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
            "CC-BY-SA-3.0.txt" to "3f941b3b89cf7b8370ceb83cc76d2120d471b58735d8ca60238a751a48d7f72f",
            "Shizuku-API-MIT.txt" to "64870037f294b6f2b75c8ebe77cc0702acb3b64f86b4b0e58bf8558525303686"
        )
        for ((filename, expected) in hashes) {
            val bytes = context.assets.open("licenses/$filename").use { it.readBytes() }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
            assertEquals(filename, expected, actual)
        }
        val gpl = requireNotNull(readLicenseText(context.assets, "GPL-3.0.txt"))
        assertTrue(gpl.length > 30_000)
        assertTrue(gpl.contains("16. Limitation of Liability."))
        assertTrue(gpl.contains("https://www.gnu.org/licenses/why-not-lgpl.html"))
    }

    @Test
    fun missing_and_invalid_legal_asset_paths_fail_safely() {
        for (filename in listOf("missing-license.txt", "../COPYING", "/COPYING", "")) {
            assertNull(filename, readLicenseText(context.assets, filename))
        }
    }

}
