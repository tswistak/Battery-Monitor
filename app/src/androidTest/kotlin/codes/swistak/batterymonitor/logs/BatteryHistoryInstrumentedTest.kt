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
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import codes.swistak.batterymonitor.BatteryMonitorTestRunner
import codes.swistak.batterymonitor.devicebackup.CsvLogImporter
import codes.swistak.batterymonitor.devicebackup.DeviceDataBackup
import codes.swistak.batterymonitor.devicebackup.DeviceDataType
import codes.swistak.batterymonitor.devicebackup.LogImportMode
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.ui.history.HistoryViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class BatteryHistoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var database: LogDatabase
    private val range = HistoryRangeState(0, Long.MAX_VALUE)
    private val observation = LogRecord(0, 50, 1_000, 300, 4000, -100_000, 2_500_000)

    @Before
    fun setUp() {
        val target = BatteryMonitorTestRunner.instrumentation.targetContext
        directory = File(target.cacheDir, "battery-history-${System.nanoTime()}").apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
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
        database = LogDatabase(context)
    }

    @After
    fun tearDown() {
        database.close()
        for (name in listOf(SettingsContract.SETTINGS_FILE, SettingsContract.SP_MAIN_FILE)) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        directory.deleteRecursively()
    }

    private fun rows(filters: Set<String>? = null, revealed: Long? = null) =
        database.readHistory(historyQuery(range, filters, revealedId = revealed)) { it.toList() }

    private fun log(
        record: LogRecord, elapsed: Long, sampler: BatteryHistorySampler, enabled: Boolean = true
    ) = database.logObservation(record, elapsed, sampler, enabled)

    private fun sql(statement: String) = SQLiteDatabase.openDatabase(
        context.getDatabasePath("logs.db").path, null, SQLiteDatabase.OPEN_READWRITE
    ).use { it.execSQL(statement) }

    @Test
    fun database_records_real_transitions_and_samples_but_not_fluctuations_or_disabled_logging() {
        val sampler = BatteryHistorySampler()
        assertEquals(LogResult.Inserted, log(observation, 0, sampler))
        repeat(100) { index ->
            assertEquals(
                LogResult.Duplicate,
                log(observation.copy(currentMicroAmps = index.toLong()), index + 1L, sampler)
            )
        }
        assertEquals(LogResult.Inserted, log(observation.copy(time = 121_000), 120_000, sampler))
        assertEquals(listOf(0, 200), rows().map { it.record.status })
        for ((index, status) in listOf(12, 0, 22, 0).withIndex()) {
            assertEquals(
                LogResult.Inserted, log(
                    observation.copy(status = status, time = 122_000L + index),
                    121_000L + index,
                    sampler
                )
            )
        }
        val before = rows()
        assertNull(log(observation.copy(charge = 49), 1_000_000, sampler, enabled = false))
        assertEquals(before, rows())
        assertEquals(
            LogResult.Inserted,
            log(observation.copy(charge = 49, time = 1_001_000), 1_000_001, sampler)
        )
        assertEquals(100, rows().last().record.status)
    }

    @Test
    fun insert_failure_recovery_keeps_the_clock_and_event_until_commit_and_reopens_handles() {
        val sampler = BatteryHistorySampler()
        log(observation, 0, sampler)
        sql("CREATE TRIGGER fail_sample BEFORE INSERT ON logs BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(log(observation, 120_000, sampler) is LogResult.Failed)
        assertEquals(0L, sampler.lastRecordedElapsed)
        assertEquals(1, rows().size)
        sql("DROP TRIGGER fail_sample")
        database.close()
        assertEquals(LogResult.Inserted, log(observation.copy(time = 122_000), 120_001, sampler))
        assertEquals(120_001L, sampler.lastRecordedElapsed)
        assertEquals(2, rows().size)
    }

    @Test
    fun SQL_filters_match_Kotlin_and_samples_can_be_revealed_by_ID_and_paginated() {
        val codes = listOf(-1) + (0..2).flatMap { age ->
            (0..4).flatMap { plugged -> (0..9).map { LogDatabase.encodeStatus(it, plugged, age) } }
        }
        database.addLogs(codes.map { observation.copy(status = it) })
        for (filter in historyFilterKeys) {
            assertEquals(
                codes.filter { historyFilterKey(it) == filter },
                rows(setOf(filter)).map { it.record.status })
        }
        val all = rows()
        val sample = all.first { it.record.status == 200 }
        assertEquals(all, rows(historyFilterKeys.toSet()))
        val samples = all.filter { it.record.status in 200..299 }
        assertEquals(samples, rows(setOf("battery_measurement")))
        val eventFilters = historyFilterKeys.toSet() - "battery_measurement"
        assertEquals(all - samples.toSet(), rows(eventFilters))
        val mixedFilters = setOf("battery_measurement", "unplugged")
        assertEquals(
            all.filter { historyFilterKey(it.record.status) in mixedFilters }, rows(mixedFilters)
        )
        assertEquals(listOf(sample), rows(emptySet(), sample.id))
        assertEquals(
            rows(setOf("boot_completed")) + sample, rows(setOf("boot_completed"), sample.id)
        )
        for (ascending in listOf(true, false)) {
            val page = database.readHistory(
                historyQuery(
                    range,
                    historyFilterKeys.toSet(),
                    ascending,
                    sample.key,
                    129,
                    includeAnchor = true,
                    revealedId = sample.id
                )
            ) { it.toList() }
            assertEquals(sample, page.first())
            assertEquals(page.size, page.map { it.id }.distinct().size)
            val revealed = database.readHistory(
                historyQuery(
                    range,
                    setOf("boot_completed"),
                    ascending,
                    sample.key,
                    129,
                    includeAnchor = true,
                    revealedId = sample.id
                )
            ) { it.toList() }
            assertEquals(sample, revealed.first())
            assertEquals(1, revealed.count { it.record.status in 200..299 })
        }
        for (record in samples) assertNull(database.historyDuration(record))
        val chart = historyChart(range, all.asSequence())
        assertEquals(5L, chart.events.filter { it.code == 2 }.sumOf { it.count })
        assertEquals(5L, chart.events.filter { it.code == 0 }.sumOf { it.count })
        assertEquals(1L, chart.events.filter { it.code == -1 }.sumOf { it.count })
    }

    @Test
    fun measurement_filter_upgrade_preserves_all_and_narrow_legacy_choices_in_both_preference_files() {
        val measurement = "battery_measurement"
        val legacy = historyFilterKeys.toSet() - measurement
        val preferences =
            context.getSharedPreferences(SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE)
        val settings =
            context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
        val app = object : Application() { init {
            attachBaseContext(context)
        }
        }
        for (alreadyMigrated in listOf(false, true)) {
            for (selected in listOf(legacy, setOf("unplugged"))) {
                preferences.edit().clear().commit()
                settings.edit().clear().commit()
                val source = if (alreadyMigrated) preferences else settings
                source.edit().apply { legacy.forEach { putBoolean(it, it in selected) } }.commit()
                if (alreadyMigrated) preferences.edit()
                    .putBoolean("log_filters_migrated_to_sp_main", true).commit()
                lateinit var model: HistoryViewModel
                BatteryMonitorTestRunner.instrumentation.runOnMainSync {
                    model = HistoryViewModel(app)
                }
                val expected = if (selected == legacy) historyFilterKeys.toSet() else selected
                assertEquals(expected, model.state.value.filters)
                assertEquals(
                    selected == legacy, preferences.getBoolean(measurement, selected != legacy)
                )
                BatteryMonitorTestRunner.instrumentation.runOnMainSync {
                    model = HistoryViewModel(app)
                }
                assertEquals(expected, model.state.value.filters)
            }
        }
    }

    @Test
    fun explicitly_toggling_measurement_filter_survives_model_recreation() {
        val measurement = "battery_measurement"
        val legacy = historyFilterKeys.toSet() - measurement
        val app = object : Application() { init {
            attachBaseContext(context)
        }
        }
        lateinit var model: HistoryViewModel
        fun main(action: () -> Unit) =
            BatteryMonitorTestRunner.instrumentation.runOnMainSync(action)
        main { model = HistoryViewModel(app) }
        for (selected in listOf(legacy, setOf("unplugged", measurement))) {
            main { model.filters(selected) }
            val deadline = System.nanoTime() + 10_000_000_000
            while (model.state.value.loading && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse(model.state.value.loading)
            assertFalse(model.state.value.error)
            main { model = HistoryViewModel(app) }
            assertEquals(selected, model.state.value.filters)
        }
    }

    @Test
    fun imports_with_future_times_do_not_hide_subsequent_events_or_suppress_periodic_sampling() {
        database.addLogs(listOf(observation.copy(status = 12, time = Long.MAX_VALUE - 1)))
        val sampler = BatteryHistorySampler()
        assertEquals(LogResult.Inserted, log(observation, 0, sampler))
        assertEquals(LogResult.Duplicate, log(observation, 1, sampler))
        assertEquals(LogResult.Inserted, log(observation.copy(time = 121_000), 120_000, sampler))
        assertEquals(3, rows().size)
    }

    @Test
    fun JSON_and_actual_CSV_preserve_samples_and_null_measurements_alongside_legacy_records() {
        val records = listOf(
            observation, observation.copy(
                status = 215,
                charge = null,
                temperature = null,
                voltage = null,
                currentMicroAmps = null,
                remainingChargeMicroampHours = null
            )
        )
        val json = DeviceDataBackup.exportLogsToJson(context, records).toString()
        assertEquals(records, DeviceDataBackup.readLogsFromJson(json))
        val csv = ByteArrayOutputStream().also { LogExport.writeCsv(context, it, records, true) }
            .toString("UTF-8")
        val restored = CsvLogImporter.parseRecords(csv, { 0 }, { _, _ -> 1000 })
        assertEquals(records, restored)
        val legacy = """{"version":1,"logs":[{"status":100,"charge":50,"time":1000}]}"""
        assertEquals(
            DeviceDataBackup.readLogsFromJson(legacy), listOf(LogRecord(100, 50, 1000, null, null))
        )
        database.replaceAllLogs(DeviceDataBackup.readLogsFromJson(json))
        assertEquals(records, database.getAllLogRecords())
    }

    @Test
    fun device_backup_and_JSON_log_exports_share_the_format_and_restore_sample_semantics() {
        val records = listOf(
            LogRecord(-1, null, 999, null, null),
            observation.copy(status = 12),
            observation.copy(status = 112, time = 1001),
            observation.copy(status = 212, time = 1002),
            LogRecord(245, null, 1003, null, null)
        )
        val selected = setOf(DeviceDataType.LOGS)
        val preferences =
            context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
        preferences.edit().putLong(SettingsContract.KEY_LAST_LOG_EXPORT_TIME, 1234).commit()
        database.addLogs(records)
        val backup = DeviceDataBackup.exportToJson(context, selected)
        val logExport = DeviceDataBackup.exportLogsToJson(context, records)
        assertEquals(2, backup.getInt("version"))
        assertEquals(logExport.toString(), backup.toString())
        DeviceDataBackup.validateJson(backup.toString(), selected)
        assertEquals(records, DeviceDataBackup.readLogsFromJson(backup.toString()))

        database.replaceAllLogs(emptyList())
        preferences.edit().remove(SettingsContract.KEY_LAST_LOG_EXPORT_TIME).commit()
        DeviceDataBackup.importFromJson(context, backup.toString(), selected, LogImportMode.REPLACE)
        assertEquals(records, database.getAllLogRecords())
        assertEquals(1234L, preferences.getLong(SettingsContract.KEY_LAST_LOG_EXPORT_TIME, -1))

        database.replaceAllLogs(records.dropLast(1))
        val sampleExport =
            DeviceDataBackup.exportLogsToJson(context, records.takeLast(1)).toString()
        DeviceDataBackup.importFromJson(context, sampleExport, selected, LogImportMode.ADD)
        assertEquals(records, database.getAllLogRecords())

        for (version in listOf(1, 2)) {
            val legacy = """{"version":$version,"logs":[{"status":112,"charge":50,"time":1000}]}"""
            DeviceDataBackup.importFromJson(context, legacy, selected, LogImportMode.REPLACE)
            assertEquals(listOf(LogRecord(112, 50, 1000, null, null)), database.getAllLogRecords())
        }
    }

    @Test
    fun required_export_failure_retains_expired_samples_and_watermarks() {
        val record = observation.copy(status = 200)
        database.addLogs(listOf(record))
        val preferences =
            context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
        preferences.edit().putBoolean(SettingsContract.KEY_ENABLE_LOGGING, true)
            .putString(SettingsContract.KEY_AUTO_LOG_EXPORT_FREQUENCY, "24").putString(
                SettingsContract.KEY_AUTO_LOG_EXPORT_DIRECTORY, "content://missing/tree/test"
            ).putLong(SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME, 0).commit()
        maintainHistory(
            672,
            database::hasLogsOlderThan,
            { AutoLogExporter.exportBeforeAutomaticLogPruning(context) },
            database::prune
        )
        assertEquals(listOf(record), database.getAllLogRecords())
        assertEquals(0L, preferences.getLong(SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME, -1))
        assertThrows(Exception::class.java) { runBlocking { LogsRepository(context).delete(null) } }
        assertEquals(listOf(record), database.getAllLogRecords())
        preferences.edit().putString(SettingsContract.KEY_AUTO_LOG_EXPORT_FREQUENCY, "off").commit()
        maintainHistory(
            672,
            database::hasLogsOlderThan,
            { AutoLogExporter.exportBeforeAutomaticLogPruning(context) },
            database::prune
        )
        assertTrue(rows().isEmpty())
    }

    @Test
    fun older_schema_upgrades_without_losing_rows_and_new_observation_fields_stay_nullable() {
        database.close()
        context.getDatabasePath("logs.db").delete()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("logs.db"), null).use {
            it.execSQL("CREATE TABLE logs (_id INTEGER PRIMARY KEY,status INTEGER,charge INTEGER,time INTEGER,temperature INTEGER,voltage INTEGER,current INTEGER)")
            it.execSQL("INSERT INTO logs VALUES (42,100,50,1000,NULL,4000,NULL)")
            it.version = 5
        }
        database = LogDatabase(context)
        val old = rows().single()
        assertEquals(42L, old.id)
        assertEquals(LogRecord(100, 50, 1000, null, 4000), old.record)
        assertEquals(
            LogResult.Inserted,
            log(old.record.copy(time = 121_000), 120_000, BatteryHistorySampler(0))
        )
        assertEquals(200, rows().last().record.status)
    }

    @Test
    fun two_pane_default_history_paginates_samples_and_selection_reveals_filtered_observations() {
        database.addLogs(listOf(observation) + (1..300).map {
            observation.copy(status = 200, time = 1000L + it * 120_000)
        })
        val app = object : Application() { init {
            attachBaseContext(context)
        }
        }
        lateinit var model: HistoryViewModel
        fun main(action: () -> Unit) =
            BatteryMonitorTestRunner.instrumentation.runOnMainSync(action)

        fun idle() {
            val deadline = System.nanoTime() + 10_000_000_000
            while (model.state.value.loading && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse(model.state.value.loading)
            assertFalse(model.state.value.error)
        }
        main {
            model = HistoryViewModel(app)
            model.range(HistoryRangeState(0, 40_000_000), "test")
            model.twoPane(true)
        }
        idle()
        val firstPage = model.state.value.page
        assertEquals(128, firstPage.size)
        assertTrue(firstPage.all { it.record.status == 200 })
        assertTrue(model.state.value.hasNext)
        assertFalse(model.state.value.hasPrevious)
        val visited = firstPage.map { it.id }.toMutableList()
        main { model.next(previous = false) }
        idle()
        assertEquals(128, model.state.value.page.size)
        visited += model.state.value.page.map { it.id }
        main { model.next(previous = false) }
        idle()
        assertEquals(45, model.state.value.page.size)
        assertFalse(model.state.value.hasNext)
        assertTrue(model.state.value.hasPrevious)
        visited += model.state.value.page.map { it.id }
        assertEquals(rows().asReversed().map { it.id }, visited)
        assertEquals(301, visited.distinct().size)
        main { model.next(previous = true) }
        idle()
        main { model.next(previous = true) }
        idle()
        assertEquals(firstPage, model.state.value.page)

        val chart = model.state.value.chart!!
        assertEquals(1L, chart.events.single().count)
        val sample = chart.series.getValue(HistoryMetric.LEVEL).points.first {
            it.key.id > 1 && firstPage.none { row -> row.id == it.key.id }
        }
        main { model.selectPoint(sample.key.id) }
        idle()
        assertTrue(model.state.value.page.any { it.id == sample.key.id && it.record.status == 200 })
        assertEquals(sample.key.id, model.state.value.selectedPointId)

        main { model.filters(setOf("unplugged")) }
        idle()
        assertEquals(listOf(0), model.state.value.page.map { it.record.status })
        main { model.selectPoint(sample.key.id) }
        idle()
        assertEquals(1, model.state.value.page.count { it.record.status == 200 })
        assertEquals(sample.key.id, model.state.value.selectedPointId)
    }

    @Test
    fun CSV_appends_preserve_existing_rows_headers_and_sample_semantics_for_every_supported_format() {
        for (columns in listOf(7, 8, 10)) {
            val header = listOf(
                "Date",
                "Time",
                "Status",
                "Charge",
                "C",
                "F",
                "V",
                "Current (µA)",
                "Remaining charge (µAh)",
                "Power (W)"
            ).take(columns)
            val oldRow =
                listOf("date", "time", "Discharging", "50", "30", "86", "4.0", "", "", "").take(
                    columns
                )
            val file = File(directory, "append-$columns.csv")
            file.writeText(header.joinToString(",") + "\r\n" + oldRow.joinToString(","))
            val sample = observation.copy(status = 200, time = 121_000)
            LogExport.appendCsv(context, android.net.Uri.fromFile(file), sequenceOf(sample))
            val rows = CsvLogImporter.parseCsv(file.readText())
            assertEquals(3, rows.size)
            assertTrue(LogExport.hasMeasurementCsvHeader(rows.first()))
            val records = CsvLogImporter.parseRecords(file.readText(), { 100 }, { _, _ -> 121_000 })
            assertEquals(100, records.first().status)
            assertEquals(sample, records.last())
        }
    }

    @Test
    fun default_retention_volume_keeps_chart_memory_bounded_and_records_SQLite_latency() {
        val now = System.currentTimeMillis()
        database.addLogs((0..<20_160).map { index ->
            observation.copy(
                status = if (index == 0) 0 else 200, time = now - (20_160 - index) * 120_000L
            )
        })
        val sampler = BatteryHistorySampler(0)
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        repeat(20) { index ->
            assertEquals(
                LogResult.Inserted,
                log(observation.copy(time = now + index), (index + 1) * 120_000L, sampler)
            )
        }
        val writeNanos = android.os.SystemClock.elapsedRealtimeNanos() - started
        val chartStarted = android.os.SystemClock.elapsedRealtimeNanos()
        val chart = database.readHistory(historyQuery(range)) { historyChart(range, it) }
        val chartNanos = android.os.SystemClock.elapsedRealtimeNanos() - chartStarted
        assertEquals(20_180L, chart.count)
        assertTrue(chart.series.values.all { it.points.size <= 500 })
        val page = database.readHistory(
            historyQuery(
                range, historyFilterKeys.toSet(), ascending = false, limit = 129
            )
        ) { it.toList() }
        assertEquals(129, page.size)
        assertTrue(page.all { it.record.status == 200 })
        assertEquals(1L, chart.events.single().count)
        android.util.Log.i(
            "BatteryHistoryTest",
            "SQLite observation mean=${writeNanos / 20 / 1_000_000.0}ms; chart=${chartNanos / 1_000_000.0}ms"
        )
    }

}
