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

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.alarms.AlarmDatabase
import codes.swistak.batterymonitor.alarms.AlarmRecord
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.monitoring.Predictor
import codes.swistak.batterymonitor.monitoring.PredictorStoredState
import codes.swistak.batterymonitor.settings.SettingsContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GeneralBackupInstrumentedTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var preferences: SharedPreferences
    private val allData = GeneralBackupDataType.entries.toSet()
    private val expectedAlarms = listOf(
        AlarmRecord(true, "charge_drops", "17"), AlarmRecord(false, "charge_rises", "83")
    )
    private val expectedLogs = listOf(
        LogRecord(-1, null, 1_700_000_000_000, null, null),
        LogRecord(200, 47, 1_700_000_120_000, 317, 4110, -125_000, 2_350_000)
    )
    private val expectedPredictor = PredictorStoredState(
        Predictor.KEY_AVERAGE.zip(listOf(731f, 127f, 193f, 251f)).toMap(), Predictor.STATE_VERSION
    )
    private val existingAlarms = listOf(AlarmRecord(false, "temp_rises", "421"))
    private val existingLogs = listOf(LogRecord(12, 81, 1_600_000_000_000, 290, 3990))
    private val existingPredictor = PredictorStoredState(
        mapOf(Predictor.KEY_AVERAGE[1] to 999f), Predictor.STATE_VERSION
    )

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(target.cacheDir, "general-backup-${System.nanoTime()}").apply {
            check(mkdirs())
        }
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences("${directory.name}-$name", mode)

            override fun getDatabasePath(name: String) = File(directory, name)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)

            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?,
                errorHandler: android.database.DatabaseErrorHandler?
            ) = SQLiteDatabase.openOrCreateDatabase(
                getDatabasePath(name).path, factory, errorHandler
            )
        }
        preferences =
            context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
    }

    @After
    fun tearDown() {
        for (name in listOf(SettingsContract.SETTINGS_FILE, Predictor.STORE_NAME)) {
            assertTrue(
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            )
        }
        directory.deleteRecursively()
    }

    @Test
    fun complete_export_and_restore_recovers_actual_stored_data_after_reopening() {
        val archive = exportedArchive()
        assertEquals(allData, GeneralBackup.getAvailableData(archive))
        assertEquals(
            setOf("metadata.json", "settings.json", "alarms.json", "device-specific.json"),
            archive.fileContents.keys
        )
        assertFalse(GeneralBackup.containsNewerSchema(archive))
        seedExistingData()

        GeneralBackup.restore(context, preferences, archive, allData, LogImportMode.REPLACE)

        assertExpectedData()
        assertEquals(
            1_700_000_125_000L, preferences.getLong(SettingsContract.KEY_LAST_LOG_EXPORT_TIME, -1)
        )
        assertTrue(preferences.getBoolean("test-local-only", false))
    }

    @Test
    fun restoring_each_single_category_preserves_every_unselected_category() {
        val archive = exportedArchive()
        for (selected in GeneralBackupDataType.entries) {
            seedExistingData()

            GeneralBackup.restore(
                context, preferences, archive, setOf(selected), LogImportMode.REPLACE
            )

            assertEquals(
                "Portable settings changed while restoring $selected",
                selected != GeneralBackupDataType.SETTINGS,
                preferences.getBoolean(SettingsContract.KEY_ENABLE_LOGGING, false)
            )
            assertEquals(
                if (selected == GeneralBackupDataType.SETTINGS) "fahrenheit" else "celsius",
                preferences.getString(SettingsContract.KEY_TEMPERATURE_UNIT, null)
            )
            assertEquals(
                if (selected == GeneralBackupDataType.SETTINGS) 83 else 91,
                preferences.getInt(SettingsContract.KEY_CUSTOM_CHARGING_TARGET, -1)
            )
            assertEquals(
                "Alarms changed while restoring $selected",
                if (selected == GeneralBackupDataType.ALARMS) expectedAlarms else existingAlarms,
                alarms()
            )
            assertEquals(
                "Logs changed while restoring $selected",
                if (selected == GeneralBackupDataType.LOGS) expectedLogs else existingLogs,
                logs()
            )
            assertEquals(
                "Predictor changed while restoring $selected",
                if (selected == GeneralBackupDataType.PREDICTOR_DATA) expectedPredictor else existingPredictor,
                predictor()
            )
        }
    }

    @Test
    fun invalid_selected_payloads_are_rejected_before_any_category_is_changed() {
        val archive = exportedArchive()
        val invalidPayloads = listOf(
            "settings.json" to """{"version":4,"settings":{"enable_logging":"yes"}}""",
            "alarms.json" to """{"version":2,"alarms":[{"enabled":true,"type":"unsupported_type","threshold":"17"}]}""",
            "device-specific.json" to """{"version":2,"logs":[{"status":"bad","time":1}],"predictor":{"stateVersion":2}}""",
            "device-specific.json" to """{"version":2,"logs":[],"predictor":{"stateVersion":999}}"""
        )
        for ((file, invalidData) in invalidPayloads) {
            seedExistingData()
            val before = storedState()
            val invalid = read(zip(archive.fileContents + (file to invalidData)))

            assertThrows("Expected validation failure in $file", Exception::class.java) {
                GeneralBackup.restore(context, preferences, invalid, allData, LogImportMode.REPLACE)
            }

            assertEquals("Validation mutated stored data for $file", before, storedState())
        }
    }

    @Test
    fun empty_or_unavailable_selection_is_rejected_without_mutations() {
        val archive = read(
            zip(
                mapOf(
                    "metadata.json" to metadata(listOf("settings.json")),
                    "settings.json" to """{"version":4,"settings":{}}"""
                )
            )
        )
        seedExistingData()
        val before = storedState()
        for (selection in listOf(emptySet(), setOf(GeneralBackupDataType.LOGS))) {
            assertThrows(IllegalArgumentException::class.java) {
                GeneralBackup.restore(
                    context, preferences, archive, selection, LogImportMode.REPLACE
                )
            }
            assertEquals(before, storedState())
        }
    }

    @Test
    fun malformed_unselected_settings_do_not_block_a_selected_alarm_restore() {
        val exported = exportedArchive()
        val archive = read(zip(exported.fileContents + ("settings.json" to "invalid JSON")))
        seedExistingData()

        GeneralBackup.restore(
            context,
            preferences,
            archive,
            setOf(GeneralBackupDataType.ALARMS),
            LogImportMode.REPLACE
        )

        assertEquals(expectedAlarms, alarms())
        assertEquals(existingLogs, logs())
        assertEquals(existingPredictor, predictor())
        assertTrue(preferences.getBoolean(SettingsContract.KEY_ENABLE_LOGGING, false))
    }

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
            duplicateEntryArchive(metadata, settings)
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
        val archive = exportedArchive()
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

    @Test
    fun settings_commit_failure_reports_no_completed_stages_and_leaves_later_data_untouched() {
        val archive = exportedArchive()
        seedExistingData()
        val before = storedState()

        val failure = assertThrows(GeneralBackupRestoreException::class.java) {
            GeneralBackup.restore(
                context, failingCommit(preferences), archive, allData, LogImportMode.REPLACE
            )
        }

        assertEquals(emptySet<GeneralBackupDataType>(), failure.completedData)
        assertEquals(setOf(GeneralBackupDataType.SETTINGS), failure.failedData)
        assertEquals(before, storedState())
    }

    @Test
    fun alarm_write_failure_keeps_completed_settings_and_does_not_attempt_later_stages() {
        val archive = exportedArchive()
        seedExistingData()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("alarms.db").path, null, SQLiteDatabase.OPEN_READWRITE
        ).use {
            it.execSQL("CREATE TRIGGER fail_restore BEFORE INSERT ON alarms BEGIN SELECT RAISE(ABORT, 'injected alarm failure'); END")
        }

        val failure = assertThrows(GeneralBackupRestoreException::class.java) {
            GeneralBackup.restore(context, preferences, archive, allData, LogImportMode.REPLACE)
        }

        assertEquals(setOf(GeneralBackupDataType.SETTINGS), failure.completedData)
        assertEquals(setOf(GeneralBackupDataType.ALARMS), failure.failedData)
        assertFalse(preferences.getBoolean(SettingsContract.KEY_ENABLE_LOGGING, true))
        assertEquals(existingAlarms, alarms())
        assertEquals(existingLogs, logs())
        assertEquals(existingPredictor, predictor())
    }

    @Test
    fun predictor_commit_failure_reports_all_prior_stages_and_retry_finishes_selected_data() {
        val archive = exportedArchive()
        seedExistingData()
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val stored = context.getSharedPreferences(name, mode)
                return if (name == Predictor.STORE_NAME) failingCommit(stored) else stored
            }
        }

        val failure = assertThrows(GeneralBackupRestoreException::class.java) {
            GeneralBackup.restore(
                failingContext, preferences, archive, allData, LogImportMode.REPLACE
            )
        }

        assertEquals(allData - GeneralBackupDataType.PREDICTOR_DATA, failure.completedData)
        assertEquals(setOf(GeneralBackupDataType.PREDICTOR_DATA), failure.failedData)
        assertEquals(expectedAlarms, alarms())
        assertEquals(expectedLogs, logs())
        assertEquals(existingPredictor, predictor())
        GeneralBackup.restore(
            context,
            preferences,
            archive,
            setOf(GeneralBackupDataType.PREDICTOR_DATA),
            LogImportMode.REPLACE
        )
        assertExpectedData()
    }

    @Test
    fun log_metadata_commit_failure_reports_partial_log_stage_without_attempting_predictor() {
        val archive = exportedArchive()
        seedExistingData()
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val stored = context.getSharedPreferences(name, mode)
                return if (name == SettingsContract.SETTINGS_FILE) failingCommit(stored) else stored
            }
        }

        val failure = assertThrows(GeneralBackupRestoreException::class.java) {
            GeneralBackup.restore(
                failingContext, preferences, archive, allData, LogImportMode.REPLACE
            )
        }

        assertEquals(
            setOf(GeneralBackupDataType.SETTINGS, GeneralBackupDataType.ALARMS),
            failure.completedData
        )
        assertEquals(setOf(GeneralBackupDataType.LOGS), failure.failedData)
        assertEquals(expectedAlarms, alarms())
        assertEquals(expectedLogs, logs())
        assertEquals(existingPredictor, predictor())
        assertEquals(42L, preferences.getLong(SettingsContract.KEY_LAST_LOG_EXPORT_TIME, -1))
    }

    private fun exportedArchive(): GeneralBackupArchive {
        assertTrue(
            preferences.edit().clear().putBoolean(SettingsContract.KEY_ENABLE_LOGGING, false)
                .putString(SettingsContract.KEY_TEMPERATURE_UNIT, "fahrenheit")
                .putInt(SettingsContract.KEY_CUSTOM_CHARGING_TARGET, 83)
                .putLong(SettingsContract.KEY_LAST_LOG_EXPORT_TIME, 1_700_000_125_000L).commit()
        )
        writeAlarms(expectedAlarms)
        writeLogs(expectedLogs)
        writePredictor(expectedPredictor)
        val destination = Uri.fromFile(File(directory, "general.zip"))
        // The app can export a live snapshot; capture it from the real, isolated preference store.
        GeneralBackup.exportToUri(context, destination, preferences, predictor())
        return GeneralBackup.readFromUri(context, destination)
    }

    private fun seedExistingData() {
        assertTrue(
            preferences.edit().clear().putBoolean(SettingsContract.KEY_ENABLE_LOGGING, true)
                .putString(SettingsContract.KEY_TEMPERATURE_UNIT, "celsius")
                .putInt(SettingsContract.KEY_CUSTOM_CHARGING_TARGET, 91)
                .putLong(SettingsContract.KEY_LAST_LOG_EXPORT_TIME, 42)
                .putBoolean("test-local-only", true).commit()
        )
        writeAlarms(existingAlarms)
        writeLogs(existingLogs)
        writePredictor(existingPredictor)
    }

    private fun assertExpectedData() {
        assertFalse(preferences.getBoolean(SettingsContract.KEY_ENABLE_LOGGING, true))
        assertEquals(
            "fahrenheit", preferences.getString(SettingsContract.KEY_TEMPERATURE_UNIT, null)
        )
        assertEquals(83, preferences.getInt(SettingsContract.KEY_CUSTOM_CHARGING_TARGET, -1))
        assertEquals(expectedAlarms, alarms())
        assertEquals(expectedLogs, logs())
        assertEquals(expectedPredictor, predictor())
    }

    private fun writeAlarms(records: List<AlarmRecord>) {
        val database = AlarmDatabase(context)
        try {
            assertTrue(database.replaceAllAlarms(records))
        } finally {
            database.close()
        }
    }

    private fun alarms(): List<AlarmRecord> {
        val database = AlarmDatabase(context)
        return try {
            database.getAllAlarmRecords()
        } finally {
            database.close()
        }
    }

    private fun writeLogs(records: List<LogRecord>) {
        val database = LogDatabase(context)
        try {
            database.replaceAllLogs(records)
        } finally {
            database.close()
        }
    }

    private fun logs(): List<LogRecord> {
        val database = LogDatabase(context)
        return try {
            database.getAllLogRecords()
        } finally {
            database.close()
        }
    }

    private fun writePredictor(state: PredictorStoredState) {
        val editor =
            context.getSharedPreferences(Predictor.STORE_NAME, Context.MODE_PRIVATE).edit().clear()
        for ((key, value) in state.averages) editor.putFloat(key, value)
        editor.putInt(Predictor.KEY_STATE_VERSION, requireNotNull(state.version))
        assertTrue(editor.commit())
    }

    private fun predictor(): PredictorStoredState = Predictor.readStoredState(
        context.getSharedPreferences(Predictor.STORE_NAME, Context.MODE_PRIVATE).all
    )

    private fun storedState(): List<Any> =
        listOf(preferences.all.toMap(), alarms(), logs(), predictor())

    private fun failingCommit(stored: SharedPreferences): SharedPreferences =
        object : SharedPreferences by stored {
            override fun edit(): SharedPreferences.Editor {
                val editor = stored.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun clear(): SharedPreferences.Editor = apply { editor.clear() }
                    override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                        apply {
                            editor.putLong(key, value)
                        }

                    override fun commit() = false
                }
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

    private fun duplicateEntryArchive(metadata: String, settings: String): ByteArray {
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
