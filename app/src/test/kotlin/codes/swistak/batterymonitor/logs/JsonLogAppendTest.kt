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
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import codes.swistak.batterymonitor.devicebackup.DeviceDataBackup
import codes.swistak.batterymonitor.devicebackup.DeviceDataType
import codes.swistak.batterymonitor.devicebackup.LogImportMode
import codes.swistak.batterymonitor.devicebackup.Version2DeviceDataImporter
import codes.swistak.batterymonitor.settings.SettingsContract
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class JsonLogAppendTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences
        get() = context.getSharedPreferences(
            SettingsContract.SETTINGS_FILE, 0
        )
    private val old = LogRecord(100, 50, 100, null, 4000, null, 3_060_000)
    private val observation = old.copy(
        time = 200,
        charge = null,
        voltage = null,
        currentMicroAmps = -165_000,
        remainingChargeMicroampHours = null
    )
    private val uri = Uri.parse("content://json-append/document/history")
    private lateinit var file: File
    private lateinit var provider: ExportProvider

    @Before
    fun setUp() {
        file = temporary.newFile("history.json")
        provider = ExportProvider(file)
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply {
            authority = "json-append"
        })
        ShadowContentResolver.registerProviderInternal("json-append", provider)
    }

    private fun document(vararg records: LogRecord) =
        DeviceDataBackup.exportLogsToJson(context, records.toList()).toString()

    private fun addObservation() {
        val database = LogDatabase(context)
        try {
            database.addLogs(listOf(observation))
        } finally {
            database.close()
        }
    }

    @Test
    fun slow_document_export_does_not_block_monitor_settings_updates() {
        Robolectric.setupContentProvider(
            AutoLogExportProvider::class.java, "${context.packageName}.log-export"
        )
        val monitor = context.getSharedPreferences("monitor-lock", 0)
        val snapshot = Bundle().apply {
            putBoolean(SettingsContract.KEY_ENABLE_LOGGING, false)
        }
        val documentHeld = CountDownLatch(1)
        val releaseDocument = CountDownLatch(1)
        val watermarkHeld = CountDownLatch(1)
        val storage = Thread {
            LogExport.withDocumentLock(context) {
                documentHeld.countDown()
                releaseDocument.await(10, TimeUnit.SECONDS)
            }
        }
        storage.start()
        assertTrue(documentHeld.await(10, TimeUnit.SECONDS))
        val settings = Thread {
            if (AutoLogExportProvider.applyMonitoringSnapshot(context, monitor, snapshot)) {
                watermarkHeld.countDown()
            }
        }
        try {
            settings.start()
            assertTrue(
                "Settings snapshots must not wait for slow document I/O",
                watermarkHeld.await(5, TimeUnit.SECONDS)
            )
            assertFalse(monitor.getBoolean(SettingsContract.KEY_ENABLE_LOGGING, true))
        } finally {
            releaseDocument.countDown()
            storage.join(10_000)
            settings.join(10_000)
        }
    }

    @Test
    fun current_and_historical_appends_preserve_records_metadata_and_predictor_semantics() {
        for (version in listOf(1, 2)) {
            val original = JSONObject(document(old)).put("version", version)
                .put("metadata", JSONObject().put("source", "old device")).put(
                    "predictor", JSONObject().put("averageDischarge", 2.5).put("custom", "preserve")
                )
            val oldJson =
                original.getJSONArray("logs").getJSONObject(0).put("note", "retained record")
            if (version == 1) {
                oldJson.remove(Version2DeviceDataImporter.KEY_LOG_CURRENT)
                oldJson.remove(Version2DeviceDataImporter.KEY_LOG_REMAINING_CHARGE)
            }
            val preservedOld =
                if (version == 1) old.copy(remainingChargeMicroampHours = null) else old
            val expected = listOf(preservedOld, observation, observation.copy(charge = 49))
            file.writeText(original.toString())

            LogExport.appendJson(
                context, uri, sequenceOf(observation, observation.copy(charge = 49))
            )

            val result = JSONObject(file.readText())
            assertEquals(2, result.getInt("version"))
            assertEquals(expected, DeviceDataBackup.readLogsFromJson(file.readText()))
            if (version == 1) {
                val preserved = result.getJSONArray("logs").getJSONObject(0)
                assertFalse(preserved.has(Version2DeviceDataImporter.KEY_LOG_CURRENT))
                assertFalse(preserved.has(Version2DeviceDataImporter.KEY_LOG_REMAINING_CHARGE))
            }
            assertEquals(
                "retained record", result.getJSONArray("logs").getJSONObject(0).getString("note")
            )
            assertEquals("old device", result.getJSONObject("metadata").getString("source"))
            assertEquals("preserve", result.getJSONObject("predictor").getString("custom"))
            assertEquals(2.5, result.getJSONObject("predictor").getDouble("averageDischarge"), 0.0)
            if (version == 1) assertEquals(
                1, result.getJSONObject("predictor").getInt("stateVersion")
            )
            DeviceDataBackup.importFromJson(
                context, file.readText(), setOf(DeviceDataType.LOGS), LogImportMode.REPLACE
            )
            assertEquals(expected, LogExport.loadRecords(context))
        }
    }

    @Test
    fun empty_arrays_and_verified_zero_byte_documents_are_initialized() {
        for (initial in listOf("", document())) {
            file.writeText(initial)
            LogExport.appendJson(context, uri, sequenceOf(observation))
            assertEquals(listOf(observation), DeviceDataBackup.readLogsFromJson(file.readText()))
        }
        file.writeText("")
        LogExport.appendJson(context, uri, emptySequence())
        assertEquals(emptyList<LogRecord>(), DeviceDataBackup.readLogsFromJson(file.readText()))
        file.writeText(JSONObject(document()).put("predictor", JSONObject.NULL).toString())
        LogExport.appendJson(context, uri, sequenceOf(observation))
        assertTrue(JSONObject(file.readText()).isNull("predictor"))
    }

    @Test
    fun no_new_records_leave_valid_document_bytes_unchanged() {
        val original = " \n" + document(old).replace(",", ", ") + "\n"
        file.writeText(original)
        LogExport.appendJson(context, uri, emptySequence())
        assertEquals(original, file.readText())
        assertEquals(0, provider.writes)
        runBlocking {
            exportHistory(
                context, uri, HistoryExportRequest(
                    Long.MAX_VALUE, 200, LogExportFormat.JSON, append = true
                )
            )
        }
        assertEquals(original, file.readText())
        assertEquals(0, provider.writes)
    }

    @Test
    fun invalid_or_lossy_inputs_are_rejected_without_opening_target_for_write() {
        val good = document(old)
        val rejected = listOf(
            "not JSON",
            "[]",
            "{\"version\":2}",
            "{\"version\":2,\"logs\":null}",
            "{\"version\":3,\"logs\":[]}",
            "{\"version\":2.5,\"logs\":[]}",
            "{\"version\":\"2\",\"logs\":[]}",
            "{\"version\":2,\"logs\":[{\"status\":100,\"time\":\"bad\"}]}",
            "$good {}",
            "$good trailing",
            "{version:2,logs:[]}",
            "{\"version\":2,\"logs\":[],\"metadata\":18446744073709551617}",
            "{\"version\":2,\"logs\":[],\"metadata\":{\"x\":1,\"x\":2}}"
        )
        for (original in rejected) {
            file.writeText(original)
            assertThrows(IllegalArgumentException::class.java) {
                LogExport.appendJson(context, uri, sequenceOf(observation))
            }
            assertEquals(original, file.readText())
            assertEquals(0, provider.writes)
        }
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertThrows(IllegalArgumentException::class.java) {
            LogExport.appendJson(context, uri, sequenceOf(observation))
        }
        assertEquals(0, provider.writes)
    }

    @Test
    fun failed_and_null_reads_are_not_treated_as_empty_destinations() {
        val original = document(old)
        file.writeText(original)
        for (nullRead in listOf(false, true)) {
            provider.unreadable = true
            provider.nullRead = nullRead
            assertThrows(IllegalArgumentException::class.java) {
                LogExport.appendJson(context, uri, sequenceOf(observation))
            }
            assertEquals(original, file.readText())
            assertEquals(0, provider.writes)
        }
    }

    @Test
    fun bounded_input_and_output_fail_before_replacement() {
        val original = document(old)
        file.writeText(original + " ".repeat(JsonLogAppend.MAX_BYTES))
        val length = file.length()
        assertThrows(IllegalArgumentException::class.java) {
            LogExport.appendJson(context, uri, sequenceOf(observation))
        }
        assertEquals(length, file.length())
        assertEquals(0, provider.writes)
        assertThrows(IllegalArgumentException::class.java) {
            DeviceDataBackup.prepareLogsAppend(original, sequenceOf(observation), original.length)
        }
    }

    @Test
    fun failed_replacement_restores_original_and_does_not_advance_manual_watermark() {
        val original = document(old)
        file.writeText(original)
        preferences.edit().putLong(SettingsContract.KEY_LAST_LOG_EXPORT_TIME, 100).commit()
        addObservation()
        provider.failWrites = 1

        assertThrows(Exception::class.java) {
            runBlocking {
                exportHistory(
                    context, uri, HistoryExportRequest(
                        100, 200, LogExportFormat.JSON, append = true, advanceWatermark = true
                    )
                )
            }
        }
        assertEquals(original, file.readText())
        assertEquals(100L, preferences.getLong(SettingsContract.KEY_LAST_LOG_EXPORT_TIME, 0))
        assertTrue(
            File(context.filesDir, "export-recovery").listFiles().orEmpty()
                .none { it.extension == "original" })
    }

    @Test
    fun interrupted_or_failed_restoration_keeps_original_and_retry_restores_using_tree_uri() {
        val original = document(old)
        file.writeText(original)
        provider.failWrites = 2
        assertThrows(LogExportRecoveryException::class.java) {
            LogExport.appendJson(context, uri, sequenceOf(observation))
        }
        val copies = File(context.filesDir, "export-recovery").listFiles().orEmpty()
            .filter { it.extension == "original" }
        assertEquals(1, copies.size)
        assertEquals(original, copies.single().readText())

        val treeUri = Uri.parse("content://json-append/tree/root/document/history")
        LogExport.appendJson(context, treeUri, emptySequence())
        assertEquals(original, file.readText())
        assertFalse(copies.single().exists())
    }

    @Test
    fun successful_write_with_failed_watermark_stays_visible_and_retry_keeps_all_observations() {
        file.writeText(document(old))
        val request = HistoryExportRequest(
            100, 200, LogExportFormat.JSON, append = true, advanceWatermark = true
        )
        assertThrows(IllegalStateException::class.java) {
            completeHistoryExport(
                request,
                write = { LogExport.appendJson(context, uri, sequenceOf(observation)) },
                saveWatermark = { error("Watermark storage unavailable") })
        }
        assertEquals(listOf(old, observation), DeviceDataBackup.readLogsFromJson(file.readText()))
        LogExport.appendJson(context, uri, sequenceOf(observation))
        assertEquals(
            listOf(old, observation, observation),
            DeviceDataBackup.readLogsFromJson(file.readText())
        )
    }

    @Test
    fun manual_and_automatic_append_share_json_validation_and_keep_separate_watermarks() {
        file.writeText(document(old))
        addObservation()
        preferences.edit().putLong(SettingsContract.KEY_LAST_LOG_EXPORT_TIME, 100)
            .putLong(SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME, 100)
            .putBoolean(SettingsContract.KEY_ENABLE_LOGGING, true)
            .putString(SettingsContract.KEY_AUTO_LOG_EXPORT_FREQUENCY, "24").putString(
                SettingsContract.KEY_AUTO_LOG_EXPORT_DIRECTORY, "content://json-append/tree/root"
            ).putString(SettingsContract.KEY_AUTO_LOG_EXPORT_FORMAT, "json")
            .putString(SettingsContract.KEY_AUTO_LOG_EXPORT_MODE, "append").commit()

        runBlocking {
            exportHistory(
                context, uri, HistoryExportRequest(
                    100, 200, LogExportFormat.JSON, append = true, advanceWatermark = true
                )
            )
        }
        assertEquals(200L, preferences.getLong(SettingsContract.KEY_LAST_LOG_EXPORT_TIME, 0))
        assertEquals(100L, preferences.getLong(SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME, 0))
        AutoLogExporter.exportInMainProcess(context)
        assertEquals(
            listOf(old, observation, observation),
            DeviceDataBackup.readLogsFromJson(file.readText())
        )
        assertEquals(200L, preferences.getLong(SettingsContract.KEY_LAST_LOG_EXPORT_TIME, 0))
        val automatic = preferences.getLong(SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME, 0)
        assertTrue(automatic >= 200)
        val successful = file.readText()
        Robolectric.setupContentProvider(
            AutoLogExportProvider::class.java, "${context.packageName}.log-export"
        )
        AutoLogExporter.export(context)
        assertEquals(successful, file.readText())
        val repeatedWatermark =
            preferences.getLong(SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME, 0)
        provider.unreadable = true
        assertFalse(AutoLogExporter.exportBeforeAutomaticLogPruning(context))
        assertEquals(
            repeatedWatermark,
            preferences.getLong(SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME, 0)
        )
        assertEquals(successful, file.readText())
    }

    private class ExportProvider(private val target: File) : ContentProvider() {
        var unreadable = false
        var nullRead = false
        var failWrites = 0
        var writes = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/json"
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
        ): Cursor = MatrixCursor(
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
        ).apply {
            addRow(arrayOf("history", LogExport.appendFileName(LogExportFormat.JSON)))
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (mode == "r") {
                if (unreadable) {
                    if (nullRead) return null
                    throw FileNotFoundException("Storage unavailable")
                }
                return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            writes++
            if (failWrites > 0) {
                failWrites--
                target.writeText("partial replacement")
                return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            return ParcelFileDescriptor.open(
                target,
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            )
        }
    }
}
