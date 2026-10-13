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
package codes.swistak.batterymonitor.alarms

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import codes.swistak.batterymonitor.BatteryMonitorTestRunner
import codes.swistak.batterymonitor.alarms.backup.AlarmBackup
import codes.swistak.batterymonitor.alarms.backup.Version2AlarmImporter
import codes.swistak.batterymonitor.ui.alarms.AlarmDraft
import codes.swistak.batterymonitor.ui.alarms.AlarmsState
import codes.swistak.batterymonitor.ui.alarms.AlarmsViewModel
import codes.swistak.batterymonitor.ui.alarms.editThresholdInput
import codes.swistak.batterymonitor.ui.alarms.restoreDraft
import codes.swistak.batterymonitor.ui.alarms.toBundle
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class AlarmDatabaseInstrumentedTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var database: AlarmDatabase

    @Before
    fun setUp() {
        val testContext = BatteryMonitorTestRunner.instrumentation.targetContext
        directory =
            File(testContext.cacheDir, "grouped-alarms-${System.nanoTime()}").apply { mkdirs() }
        context = object : ContextWrapper(testContext) {
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
        database = AlarmDatabase(context)
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    private fun rows() = database.getAlarmRules()
    private fun group(type: String, threshold: Int? = null) =
        projectAlarms(rows()).types.single { it.definition.type == type }.alarms.single { threshold == null || it.threshold == threshold }

    private fun sql(statement: String) {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("alarms.db").path, null, SQLiteDatabase.OPEN_READWRITE
        ).use { it.execSQL(statement) }
    }

    @Test
    fun reads_never_create_events_and_first_on_creates_only_one_persistent_record() {
        assertTrue(rows().isEmpty())
        assertEquals(8, projectAlarms(rows()).types.size)
        assertTrue(rows().isEmpty())
        database.setLogicalEnabled("fully_charged", emptyList(), true)
        val original = group("fully_charged")
        database.setLogicalEnabled(original.type, original.members, false)
        val disabled = group("fully_charged")
        database.setLogicalEnabled(disabled.type, disabled.members, true)
        assertEquals(original.memberIds, group("fully_charged").memberIds)
        assertEquals(1, rows().size)
    }

    @Test
    fun singleton_and_threshold_switches_update_every_legacy_copy_atomically() {
        for (type in listOf("fully_charged", "charge_drops")) {
            database.addAlarm(false, type, if (type == "charge_drops") "020" else "legacy")
            database.addAlarm(true, type, if (type == "charge_drops") "20" else "")
            val original = group(type)
            assertTrue(original.enabled)
            database.setLogicalEnabled(type, original.members, false)
            assertTrue(rows().filter { it.type == type }.none { it.enabled })
            database.setLogicalEnabled(type, group(type).members, true)
            assertTrue(rows().filter { it.type == type }.all { it.enabled })
            assertEquals(original.memberIds, group(type).memberIds)
        }
    }

    @Test
    fun editing_preserves_ids_individual_flags_and_legacy_columns_and_deletion_removes_every_copy() {
        database.addAlarm(
            false, "charge_drops", "020", "content://legacy/a", vibrate = true, lights = false
        )
        database.addAlarm(
            true, "charge_drops", "20", "content://legacy/b", vibrate = false, lights = true
        )
        database.addAlarm(true, "charge_drops", "15")
        database.addAlarm(false, "charge_rises", "19")
        val original = group("charge_drops", 20)
        val untouched = rows().filter { it.id !in original.memberIds }
        database.saveThreshold("charge_drops", "19", original.members)
        val edited = group("charge_drops", 19)
        assertEquals(original.memberIds, edited.memberIds)
        assertEquals(listOf(false, true), edited.members.map { it.enabled })
        database.getAllAlarms(true)!!.use { cursor ->
            cursor.moveToFirst()
            assertEquals(
                "content://legacy/a", cursor.getString(cursor.getColumnIndexOrThrow("ringtone"))
            )
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("vibrate")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("lights")))
        }
        database.deleteLogicalAlarm(edited.members)
        assertEquals(untouched, rows())
    }

    @Test
    fun canonical_collisions_are_checked_in_the_transaction_for_both_new_and_edited_thresholds() {
        database.saveThreshold("charge_drops", "20", emptyList())
        val first = rows()
        assertTrue(first.single().enabled)
        assertThrows(AlarmCollisionException::class.java) {
            database.saveThreshold(
                "charge_drops", "020", emptyList()
            )
        }
        database.saveThreshold("charge_drops", "15", emptyList())
        assertThrows(AlarmCollisionException::class.java) {
            database.saveThreshold(
                "charge_drops", "20", group("charge_drops", 15).members
            )
        }
        database.saveThreshold("charge_rises", "20", emptyList())
        assertEquals(3, rows().size)
    }

    @Test
    fun partial_SQL_failure_rolls_back_every_member_and_retry_does_not_duplicate_records() {
        database.addAlarm(true, "charge_drops", "20")
        database.addAlarm(false, "charge_drops", "020")
        val before = rows()
        val original = group("charge_drops")
        sql("CREATE TRIGGER fail_second BEFORE UPDATE ON alarms WHEN OLD._id=${before.last().id} BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        assertThrows(Exception::class.java) {
            database.setLogicalEnabled(
                "charge_drops", original.members, false
            )
        }
        assertEquals(before, rows())
        assertThrows(Exception::class.java) {
            database.saveThreshold(
                "charge_drops", "19", original.members
            )
        }
        assertEquals(before, rows())
        sql("DROP TRIGGER fail_second")
        database.saveThreshold("charge_drops", "19", original.members)
        assertEquals(before.map { it.copy(threshold = "19") }, rows())
    }

    @Test
    fun stale_drafts_cannot_recreate_deleted_or_imported_members() {
        database.saveThreshold("charge_drops", "20", emptyList())
        val original = group("charge_drops")
        database.deleteLogicalAlarm(original.members)
        assertThrows(AlarmConflictException::class.java) {
            database.saveThreshold(
                "charge_drops", "19", original.members
            )
        }
        assertTrue(rows().isEmpty())
        database.addAlarm(true, "charge_drops", "20")
        val beforeImport = group("charge_drops")
        assertTrue(
            database.replaceAllAlarms(
                listOf(
                    AlarmRecord(false, "charge_drops", "20"),
                    AlarmRecord(true, "charge_drops", "020")
                )
            )
        )
        val imported = rows()
        assertThrows(AlarmConflictException::class.java) {
            database.setLogicalEnabled(
                "charge_drops", beforeImport.members, false
            )
        }
        assertEquals(imported, rows())
        assertEquals(1, projectAlarms(imported).enabledCount)
    }

    @Test
    fun reopening_a_version_five_database_preserves_all_rows_ids_flags_and_legacy_columns() {
        for (type in AlarmDatabase.SUPPORTED_TYPES) database.addAlarm(
            type != "fully_charged", type, when (type) {
                "charge_rises", "charge_drops" -> "023"
                "temp_rises", "temp_drops" -> "451"
                else -> ""
            }, "content://saved/tone", vibrate = true, lights = false
        )
        database.addAlarm(false, "temp_rises", "451")
        val before = rows()
        database.close()
        database = AlarmDatabase(context)
        assertEquals(before, rows())
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("alarms.db").path, null, SQLiteDatabase.OPEN_READONLY
        ).use {
            assertEquals(5, it.version)
            it.rawQuery(
                "SELECT ringtone,vibrate,lights FROM alarms WHERE _id=?",
                arrayOf(before.first().id.toString())
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("content://saved/tone", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
            }
        }
    }

    @Test
    fun invalid_records_are_retained_until_explicitly_disabled_or_deleted() {
        database.addAlarm(true, "charge_drops", "not a number")
        database.addAlarm(true, "future_type", "12")
        val before = rows()
        assertEquals(before, projectAlarms(before).invalid)
        database.setLogicalEnabled(before.first().type, listOf(before.first()), false)
        assertEquals(before.first().copy(enabled = false), rows().first())
        database.deleteLogicalAlarm(listOf(rows().first()))
        assertEquals(listOf(before.last()), rows())
    }

    @Test
    fun draft_parcel_round_trip_retains_raw_text_members_flags_locale_and_unit() {
        val draft = AlarmDraft(
            id = 4,
            type = "temp_drops",
            threshold = "",
            thresholdInput = "-",
            members = listOf(
                AlarmRule(4, false, "temp_drops", "451"), AlarmRule(7, true, "temp_drops", "0451")
            ),
            originalThreshold = "451",
            initialInput = "113,2",
            inputLocale = "pl",
            inputFahrenheit = true
        )
        val parcel = android.os.Parcel.obtain()
        val bundle = draft.toBundle()
        bundle.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val restored = Bundle.CREATOR.createFromParcel(parcel)
        parcel.recycle()
        assertEquals(draft, restoreDraft(restored))
        val handle = SavedStateHandle(mapOf("alarm_draft" to restored, "alarm_original" to bundle))
        assertEquals(draft, restoreDraft(handle["alarm_draft"]))
        assertEquals("451", editThresholdInput(draft, "113,2").threshold)
    }

    // Actual SAF export from the published 1.7.3 APK, retained under captures/docs/device-review.
    private val legacyBackup =
        """{"version":2,"alarms":[{"enabled":true,"type":"fully_charged","threshold":""},{"enabled":false,"type":"charging_limit_met","threshold":""},{"enabled":true,"type":"discharging_limit_met","threshold":""},{"enabled":false,"type":"charge_drops","threshold":"17"},{"enabled":true,"type":"charge_rises","threshold":"83"},{"enabled":false,"type":"temp_drops","threshold":"94"},{"enabled":true,"type":"temp_rises","threshold":"378"},{"enabled":false,"type":"health_failure","threshold":""},{"enabled":false,"type":"charge_rises","threshold":"61"}]}"""

    @Test
    fun actual_1_7_3_backup_imports_all_eight_types_and_exports_the_same_portable_records() {
        assertEquals(9, AlarmBackup.importFromJson(database, legacyBackup))
        val imported = database.getAllAlarmRecords()
        assertEquals(8, imported.map { it.type }.distinct().size)
        assertEquals(
            listOf(true, false, true, false, true, false, true, false, false),
            imported.map { it.enabled })
        assertEquals(
            listOf("", "", "", "17", "83", "94", "378", "", "61"), imported.map { it.threshold })
        val exported = AlarmBackup.exportToJson(database)
        assertEquals(2, exported.getInt("version"))
        val portable = exported.getJSONArray("alarms")
        val oldReader = (0 until portable.length()).map { index ->
            val record = portable.getJSONObject(index)
            assertEquals(setOf("enabled", "type", "threshold"), record.keys().asSequence().toSet())
            Version2AlarmImporter.restore(
                mapOf(
                    "enabled" to record.getBoolean("enabled"),
                    "type" to record.getString("type"),
                    "threshold" to record.getString("threshold")
                )
            )
        }
        assertEquals(imported, oldReader)
        assertEquals(9, AlarmBackup.importFromJson(database, exported.toString()))
        assertEquals(imported, database.getAllAlarmRecords())
        assertEquals(
            9, AlarmBackup.importFromJson(
                database, JSONObject(legacyBackup).put("version", 1).toString()
            )
        )
        assertEquals(imported, database.getAllAlarmRecords())
    }

    @Test
    fun backup_duplicates_remain_portable_and_their_logical_switch_has_no_hidden_active_copy() {
        val backup = JSONObject(legacyBackup)
        backup.getJSONArray("alarms").put(
            JSONObject().put("enabled", true).put("type", "charge_drops").put("threshold", "017")
        )
        backup.getJSONArray("alarms").put(
            JSONObject().put("enabled", false).put("type", "fully_charged").put("threshold", "")
        )
        assertEquals(11, AlarmBackup.importFromJson(database, backup.toString()))
        assertEquals(5, projectAlarms(rows()).enabledCount)
        assertEquals(2, group("charge_drops", 17).members.size)
        val ids = rows().map { it.id }
        database.setLogicalEnabled("charge_drops", group("charge_drops", 17).members, false)
        assertTrue(rows().filter { it.type == "charge_drops" }.none { it.enabled })
        assertEquals(ids, rows().map { it.id })
        val exported = AlarmBackup.exportToJson(database).toString()
        assertEquals(11, AlarmBackup.importFromJson(database, exported))
        assertTrue(rows().filter { it.type == "charge_drops" }.none { it.enabled })
        assertEquals(2, group("fully_charged").members.size)
        assertEquals(4, projectAlarms(rows()).enabledCount)
    }

    @Test
    fun bad_backup_and_failed_replacement_leave_the_existing_database_intact() {
        AlarmBackup.importFromJson(database, legacyBackup)
        val original = rows()
        for (bad in listOf(
            "not json", "{}", "{\"version\":2,\"alarms\":[{\"type\":\"unknown\"}]}"
        )) {
            assertThrows(Exception::class.java) { AlarmBackup.importFromJson(database, bad) }
            assertEquals(original, rows())
        }
        sql("CREATE TRIGGER fail_import BEFORE INSERT ON alarms WHEN NEW.type='temp_rises' BEGIN SELECT RAISE(ABORT, 'injected import failure'); END")
        assertThrows(Exception::class.java) { AlarmBackup.importFromJson(database, legacyBackup) }
        assertEquals(original, rows())
        sql("DROP TRIGGER fail_import")
        assertEquals(0, AlarmBackup.importFromJson(database, "{\"version\":2,\"alarms\":[]}"))
        assertTrue(rows().isEmpty())
    }

    private fun model(handle: SavedStateHandle = SavedStateHandle()): AlarmsViewModel {
        val app = object : Application() { init {
            attachBaseContext(context)
        }
        }
        return AlarmsViewModel(app, handle)
    }

    private fun main(action: () -> Unit) =
        BatteryMonitorTestRunner.instrumentation.runOnMainSync(action)

    private fun idle(
        model: AlarmsViewModel, condition: (AlarmsState) -> Boolean = { !it.loading && !it.busy }
    ) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition(model.state.value) && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(
            "Timed out waiting for alarm state: ${model.state.value}", condition(model.state.value)
        )
    }

    @Test
    fun view_model_reloads_once_per_commit_and_never_saves_while_typing_or_refreshing() {
        val model = model()
        val reloads = AtomicInteger()
        main { model.refresh() }
        idle(model)
        main {
            model.beginThreshold(
                "charge_drops", false, Locale.US
            )
            model.changeDraftInput("17")
        }
        assertTrue(rows().isEmpty())
        assertEquals(0, reloads.get())
        main {
            model.saveDraft { reloads.incrementAndGet() }
            model.saveDraft { reloads.incrementAndGet() }
        }
        idle(model)
        assertEquals(1, reloads.get())
        assertTrue(rows().single().enabled)
        val group = group("charge_drops")
        main { model.setEnabled(group.type, group, false) { reloads.incrementAndGet() } }
        idle(model)
        main {
            model.beginDraft(
                group.id, false, Locale.US
            )
            model.changeDraftInput("19")
            model.saveDraft { reloads.incrementAndGet() }
        }
        idle(model)
        assertEquals(3, reloads.get())
        assertFalse(rows().single().enabled)
        assertEquals(group.id, rows().single().id)
        assertEquals("19", rows().single().threshold)
        main { model.refresh() }
        idle(model)
        assertEquals(3, reloads.get())
    }

    @Test
    fun view_model_keeps_failed_draft_and_retry_commits_once_without_success_on_failure() {
        val model = model()
        val reloads = AtomicInteger()
        main { model.refresh() }
        idle(model)
        main {
            model.beginThreshold(
                "temp_drops", false, Locale.forLanguageTag("pl")
            )
            model.changeDraftInput("-4,2")
        }
        sql("CREATE TRIGGER fail_save BEFORE INSERT ON alarms BEGIN SELECT RAISE(ABORT, 'injected save failure'); END")
        main { model.saveDraft { reloads.incrementAndGet() } }
        idle(model)
        assertEquals("-4,2", model.state.value.draft!!.thresholdInput)
        assertNotNull(model.state.value.error)
        assertNull(model.state.value.notice)
        assertEquals(0, reloads.get())
        assertTrue(rows().isEmpty())
        sql("DROP TRIGGER fail_save")
        main { model.saveDraft { reloads.incrementAndGet() } }
        idle(model)
        assertEquals(1, reloads.get())
        assertNull(model.state.value.draft)
        assertEquals("-42", rows().single().threshold)
    }

    @Test
    fun first_read_error_is_unknown_and_stale_snapshot_blocks_mutations_until_retry() {
        database.close()
        val path = context.getDatabasePath("alarms.db")
        val saved = File(directory, "saved.db")
        assertTrue(path.renameTo(saved))
        assertTrue(path.mkdir())
        val model = model()
        main { model.refresh() }
        idle(model)
        assertNull(model.state.value.enabledCount)
        assertFalse(model.state.value.canMutate)
        assertNotNull(model.state.value.readError)
        assertTrue(path.delete())
        assertTrue(saved.renameTo(path))
        database = AlarmDatabase(context)
        database.saveThreshold("charge_drops", "17", emptyList())
        main { model.refresh() }
        idle(model)
        assertEquals(1, model.state.value.enabledCount)
        database.close()
        assertTrue(path.renameTo(saved))
        assertTrue(path.mkdir())
        main { model.refresh() }
        idle(model)
        assertEquals(1, model.state.value.enabledCount)
        assertFalse(model.state.value.canMutate)
        main {
            model.setEnabled(
                "fully_charged", null, true
            ) { fail("Reload on rejected mutation") }
        }
        assertTrue(path.delete())
        assertTrue(saved.renameTo(path))
        database = AlarmDatabase(context)
        assertEquals(1, rows().size)
        main { model.refresh() }
        idle(model)
        assertTrue(model.state.value.canMutate)
    }

}
