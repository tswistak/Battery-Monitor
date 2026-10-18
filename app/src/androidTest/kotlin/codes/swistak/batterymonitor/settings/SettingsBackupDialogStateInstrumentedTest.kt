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
package codes.swistak.batterymonitor.settings

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentHostCallback
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.alarms.backup.AlarmBackup
import codes.swistak.batterymonitor.devicebackup.CsvLogImporter
import codes.swistak.batterymonitor.devicebackup.DeviceDataBackup
import codes.swistak.batterymonitor.devicebackup.GeneralBackup
import codes.swistak.batterymonitor.settings.backup.SettingsBackup
import codes.swistak.batterymonitor.ui.settings.SettingsDialog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SettingsBackupDialogStateInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var application: Application
    private val store = ViewModelStore()
    private val models = mutableMapOf<SettingsFragment, SettingsOperationViewModel>()

    @Before
    fun setUp() {
        val target = instrumentation.targetContext
        directory =
            File(target.cacheDir, "backup-dialog-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = directory
        }
        application = object : Application() {
            init {
                attachBaseContext(context)
            }
        }
    }

    @After
    fun tearDown() {
        main { store.clear() }
        directory.deleteRecursively()
    }

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
    fun preview_reparses_cached_data_after_restoration_without_large_bundle_payloads() {
        val original = """{"version":2,"logs":[],"predictor":{}}""" + " ".repeat(200_000)
        val source = File(directory, "source.json").apply { writeText(original) }
        val first = fragment()
        read(first, request(6), Uri.fromFile(source))
        complete(first)
        assertEquals(context.getString(R.string.pref_import_device_data), choices(first).title)
        val saved = save(first)
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(saved)
            assertTrue("Backup payload must not enter the saved Bundle", parcel.dataSize() < 4096)
        } finally {
            parcel.recycle()
        }
        source.writeText("invalid document changed after preview")
        val restored = restore(saved)
        complete(restored)
        assertEquals(choices(first).options, choices(restored).options)
        cancel(restored)
        assertNull(save(restored).getBundle("settings_backup_dialog"))
        assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith("settings-import-") })
    }

    @Test
    fun standalone_uri_readers_preserve_UTF8_and_settings_line_normalization() {
        val text = "ą💡\r\n"
        val source = File(directory, "utf8-source").apply { writeText(text, Charsets.UTF_8) }
        val uri = Uri.fromFile(source)
        assertEquals("ą💡\n", SettingsBackup.readFromUri(context, uri))
        assertEquals(text, AlarmBackup.readFromUri(context, uri))
        assertEquals(text, DeviceDataBackup.readFromUri(context, uri))
        assertEquals(text, CsvLogImporter.readFromUri(context, uri))
    }

    @Test
    fun oversized_standalone_source_rolls_back_the_preview_cache() {
        val source = File(directory, "oversized-source")
        RandomAccessFile(source, "rw").use {
            it.setLength(GeneralBackup.MAX_ENTRY_BYTES.toLong() + 1)
        }
        val fragment = fragment()
        val request = request(6)
        read(fragment, request, Uri.fromFile(source))
        complete(fragment)
        assertTrue(dialog(fragment) is SettingsDialog.Message)
        assertNull(save(fragment).getBundle("settings_backup_dialog"))
        assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith("settings-import-") })
    }

    @Test
    fun device_log_mode_restores_selected_data_and_cancellation_performs_no_import() {
        val source = File(directory, "source.json").apply {
            writeText("""{"version":2,"logs":[],"predictor":{}}""")
        }
        val first = fragment()
        read(first, request(6), Uri.fromFile(source))
        complete(first)
        val preview = choices(first)
        main {
            call(first, "dismissSettingsDialog")
            preview.onConfirm(setOf(0))
        }
        val saved = save(first)
        assertEquals(
            listOf("LOGS"),
            saved.getBundle("settings_backup_dialog")!!.getStringArrayList("selected")
        )
        val restored = restore(saved)
        complete(restored)
        assertEquals(context.getString(R.string.log_import_mode_title), choices(restored).title)
        assertEquals(
            listOf("LOGS"),
            save(restored).getBundle("settings_backup_dialog")!!.getStringArrayList("selected")
        )
        cancel(restored)
        assertNull(models.getValue(restored).outcome)
        assertFalse(models.getValue(restored).busy)
    }

    @Test
    fun general_backup_log_mode_restores_selection_from_the_cached_archive() {
        val source = File(directory, "source.zip")
        ZipOutputStream(source.outputStream()).use { output ->
            for ((name, body) in mapOf(
                "metadata.json" to """{"version":1,"files":["device-specific.json"]}""",
                "device-specific.json" to """{"version":2,"logs":[],"predictor":{}}"""
            )) {
                output.putNextEntry(ZipEntry(name))
                output.write(body.toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
        }
        val first = fragment()
        read(first, request(9), Uri.fromFile(source))
        complete(first)
        val preview = choices(first)
        main {
            call(first, "dismissSettingsDialog")
            preview.onConfirm(setOf(0))
        }
        val restored = restore(save(first))
        complete(restored)
        assertEquals(context.getString(R.string.log_import_mode_title), choices(restored).title)
        assertEquals(
            listOf("LOGS"),
            save(restored).getBundle("settings_backup_dialog")!!.getStringArrayList("selected")
        )
        cancel(restored)
    }

    @Test
    fun csv_mode_and_newer_schema_warnings_restore_and_missing_cache_fails_safely() {
        for ((code, body) in listOf(
            7 to "status,charge,time\n22,50,1000\n",
            2 to """{"version":${SettingsBackup.SCHEMA_VERSION + 1}}""",
            4 to """{"version":${AlarmBackup.SCHEMA_VERSION + 1}}"""
        )) {
            val source = File(directory, "source-$code").apply { writeText(body) }
            val first = fragment()
            read(first, request(code), Uri.fromFile(source))
            complete(first)
            val saved = save(first)
            val restored = restore(saved)
            complete(restored)
            if (code == 7) assertEquals(
                context.getString(R.string.log_import_mode_title), choices(restored).title
            )
            else assertTrue(dialog(restored) is SettingsDialog.Confirm)
            val cacheName = saved.getBundle("settings_backup_dialog")!!.getString("cache")!!
            File(directory, cacheName).delete()
            val unavailable = restore(saved)
            complete(unavailable)
            assertTrue(dialog(unavailable) is SettingsDialog.Message)
            assertNull(save(unavailable).getBundle("settings_backup_dialog"))
            cancel(restored)
        }
    }

    private fun fragment(): SettingsFragment {
        lateinit var result: SettingsFragment
        main {
            result = SettingsFragment()
            val host = object :
                FragmentHostCallback<Context>(context, Handler(Looper.getMainLooper()), 0) {
                override fun onGetHost(): Context = context
            }
            Fragment::class.java.getDeclaredField("mHost").apply { isAccessible = true }
                .set(result, host)
            val model = SettingsOperationViewModel(application)
            SettingsFragment::class.java.getDeclaredField("applicationContext")
                .apply { isAccessible = true }.set(result, context)
            SettingsFragment::class.java.getDeclaredField("operationModel")
                .apply { isAccessible = true }.set(result, model)
            models[result] = model
            store.put("backup-${models.size}", model)
        }
        return result
    }

    private fun request(code: Int) = Bundle().apply {
        putString("action", "backup_import")
        putInt("request", code)
        putString("cache", "settings-import-${UUID.randomUUID()}.backup")
        putString("stage", "preview")
    }

    private fun read(fragment: SettingsFragment, request: Bundle, source: Uri?) {
        main {
            SettingsFragment::class.java.getDeclaredMethod(
                "readBackupImport", Bundle::class.java, Uri::class.java
            ).apply { isAccessible = true }.invoke(fragment, request, source)
        }
    }

    private fun complete(fragment: SettingsFragment) {
        val model = models.getValue(fragment)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (model.outcome == null && System.nanoTime() < deadline) Thread.sleep(20)
        assertNotNull("Backup read must complete", model.outcome)
        main {
            SettingsFragment::class.java.getDeclaredMethod(
                "handleOperationOutcome", SettingsOperationOutcome::class.java
            ).apply { isAccessible = true }.invoke(fragment, model.takeOutcome())
        }
    }

    private fun save(fragment: SettingsFragment) = Bundle().also {
        main { fragment.onSaveInstanceState(it) }
    }

    private fun restore(saved: Bundle) = fragment().also { fragment ->
        main {
            SettingsFragment::class.java.getDeclaredMethod("restoreDialog", Bundle::class.java)
                .apply { isAccessible = true }.invoke(fragment, saved.getBundle("settings_dialog"))
        }
    }

    private fun cancel(fragment: SettingsFragment) {
        main { call(fragment, "dismissSettingsDialog") }
        instrumentation.waitForIdleSync()
    }

    private fun dialog(fragment: SettingsFragment): SettingsDialog? {
        var result: SettingsDialog? = null
        main {
            result = SettingsFragment::class.java.getDeclaredMethod("getDialog")
                .apply { isAccessible = true }.invoke(fragment) as SettingsDialog?
        }
        return result
    }

    private fun choices(fragment: SettingsFragment) = dialog(fragment) as SettingsDialog.Choices

    private fun call(fragment: SettingsFragment, name: String) {
        SettingsFragment::class.java.getDeclaredMethod(name).apply { isAccessible = true }
            .invoke(fragment)
    }

    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                action()
            } catch (caught: Throwable) {
                failure = caught
            }
        }
        failure?.let { throw it }
    }
}
