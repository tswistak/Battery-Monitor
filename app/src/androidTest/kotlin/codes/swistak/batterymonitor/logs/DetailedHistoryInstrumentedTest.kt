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

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DetailedHistoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var database: LogDatabase
    private val notifications = mutableListOf<Intent>()
    private var notificationFailure = false
    private val observation = LogRecord(200, 50, 1_000, 300, 4000, -100_000, 2_500_000)

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory =
            File(target.cacheDir, "detailed-history-${System.nanoTime()}").apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun sendBroadcast(intent: Intent) {
                if (notificationFailure) throw SecurityException("notification rejected")
                notifications += intent
            }

            override fun getDatabasePath(name: String) = File(directory, name)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)

            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ) = SQLiteDatabase.openOrCreateDatabase(
                getDatabasePath(name).path, factory, errorHandler
            )
        }
        database = LogDatabase(context)
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun viewport_queries_use_inclusive_start_exclusive_end_and_stable_duplicate_order(): Unit =
        runBlocking {
            database.addLogs(
                listOf(
                    observation.copy(time = 200, charge = 100),
                    observation.copy(time = 100, charge = 20),
                    observation.copy(time = 100, charge = 30),
                    observation.copy(time = 99, charge = 0),
                    observation.copy(time = 1_000, charge = 0)
                )
            )
            val model = LogsRepository(context).detailedChart(
                HistoryRangeState(0, 1_000), HistoryMetric.LEVEL, HistoryRangeState(100, 200), 800
            )
            assertEquals(2L, model.count)
            assertEquals(20.0, model.min!!, 0.0)
            assertEquals(30.0, model.max!!, 0.0)
            assertEquals(listOf(4L, 2L, 3L, 1L), model.points.map { it.key.id })
            assertEquals(listOf(99L, 100L, 100L, 200L), model.points.map { it.key.time })
            val exact = LogsRepository(context).detailedChart(
                HistoryRangeState(100, 200), HistoryMetric.LEVEL, pixelWidth = 800
            )
            assertEquals(listOf(2L, 3L), exact.points.map { it.key.id })
        }

    @Test
    fun durable_changes_survive_missed_notifications_and_same_identity_replacement() {
        database.addLogs(listOf(observation))
        val original =
            database.readHistory(historyQuery(HistoryRangeState(0, 10_000))) { it.single() }
        val revision = database.historyChanges(null, null).revision
        notificationFailure = true
        val otherConnection = LogDatabase(context)
        try {
            otherConnection.addLogs(listOf(observation.copy(time = 2_000)))
            otherConnection.addLogs(listOf(observation.copy(time = 3_000)))
        } finally {
            otherConnection.close()
        }
        assertTrue(database.historyChanges(revision, HistoryRangeState(0, 4_000)).affectsRange)
        assertFalse(database.historyChanges(revision, HistoryRangeState(100, 500)).affectsRange)
        val beforeReplacement = database.historyChanges(null, null).revision
        database.replaceAllLogs(listOf(observation.copy(charge = 49)))
        val replacement =
            database.readHistory(historyQuery(HistoryRangeState(0, 10_000))) { it.single() }
        assertEquals(original.key, replacement.key)
        assertNotEquals(original.record.charge, replacement.record.charge)
        assertTrue(
            database.historyChanges(
                beforeReplacement, HistoryRangeState(1_000, 1_001)
            ).affectsRange
        )
        assertFalse(
            database.historyChanges(
                beforeReplacement, HistoryRangeState(0, 1_000)
            ).affectsRange
        )
        val beforeDelete = database.historyChanges(null, null).revision
        database.deleteHistory(HistoryRangeState(1_000, 1_001))
        assertTrue(
            database.historyChanges(
                beforeDelete, HistoryRangeState(1_000, 1_001)
            ).affectsRange
        )
        val afterDelete = database.historyChanges(null, null).revision
        database.deleteHistory(null)
        database.addLogs(emptyList())
        assertEquals(afterDelete, database.historyChanges(null, null).revision)
    }

    @Test
    fun bounded_changes_exclude_closed_ranges_until_the_retained_revision_window_is_exceeded() {
        database.addLogs(listOf(observation))
        val revision = database.historyChanges(null, null).revision
        val closed = HistoryRangeState(0, 2_000)
        repeat(LogDatabase.HISTORY_CHANGE_LIMIT) { index ->
            database.addLogs(listOf(observation.copy(time = 10_000L + index)))
        }
        assertFalse(database.historyChanges(revision, closed).affectsRange)
        database.addLogs(listOf(observation.copy(time = 20_000)))
        assertTrue(database.historyChanges(revision, closed).affectsRange)
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("logs.db").path, null, SQLiteDatabase.OPEN_READONLY
        ).use { handle ->
            handle.rawQuery("SELECT COUNT(*) FROM history_changes", null).use { cursor ->
                cursor.moveToFirst()
                assertEquals(LogDatabase.HISTORY_CHANGE_LIMIT, cursor.getInt(0))
            }
        }
        val beforeBoundary = database.historyChanges(null, null).revision
        database.addLogs(listOf(observation.copy(time = Long.MAX_VALUE)))
        assertTrue(
            database.historyChanges(
                beforeBoundary, HistoryRangeState(1, Long.MAX_VALUE)
            ).affectsRange
        )
    }

    @Test
    fun a_failed_revision_write_rolls_back_the_observation_and_does_not_advance_the_sampler() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("logs.db").path, null, SQLiteDatabase.OPEN_READWRITE
        ).use { handle ->
            handle.execSQL(
                "CREATE TRIGGER fail_revision BEFORE INSERT ON history_changes " + "BEGIN SELECT RAISE(ABORT, 'revision failure'); END"
            )
        }
        val sampler = BatteryHistorySampler()
        assertTrue(database.logObservation(observation, 1_000, sampler, true) is LogResult.Failed)
        assertEquals(0L, database.historyChanges(null, null).revision)
        assertTrue(database.getAllLogRecords().isEmpty())
        assertTrue(notifications.isEmpty())
        assertNull(sampler.lastRecordedElapsed)
    }

    @Test
    fun commits_after_a_read_snapshot_remain_visible_to_the_next_freshness_check() {
        database.addLogs((0..<10_000).map { observation.copy(time = it.toLong()) })
        val before = database.historyChanges(null, null).revision
        val start = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                start.await()
                val other = LogDatabase(context)
                try {
                    other.addLogs(listOf(observation.copy(time = 10_001)))
                } finally {
                    other.close()
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        writer.start()
        val rows = database.readHistory(historyQuery(HistoryRangeState(0, 20_000))) { records ->
            records.onEach { start.countDown() }.count()
        }
        writer.join(10_000)
        assertFalse(writer.isAlive)
        failure.get()?.let { throw AssertionError("Concurrent history write failed", it) }
        assertEquals(10_000, rows)
        assertTrue(database.historyChanges(before, HistoryRangeState(0, 20_000)).affectsRange)
        assertEquals(10_001, database.getAllLogRecords().size)
    }

    @Test
    fun realistic_retention_overview_is_bounded_and_zoom_loads_every_real_reading(): Unit =
        runBlocking {
            val count = 20_160
            database.addLogs((0..<count).map { index ->
                observation.copy(time = 1_000 + index * HISTORY_SAMPLE_INTERVAL_MS)
            })
            val range = HistoryRangeState(0, 28 * 86_400_000L + 1_000)
            val repository = LogsRepository(context)
            val start = android.os.SystemClock.elapsedRealtimeNanos()
            val wide = repository.detailedChart(range, HistoryMetric.CURRENT, pixelWidth = 300)
            val overviewNanos = android.os.SystemClock.elapsedRealtimeNanos() - start
            val viewport = HistoryRangeState(1_200_001_000, 1_200_901_000)
            val zoomStart = android.os.SystemClock.elapsedRealtimeNanos()
            val narrow = repository.detailedChart(range, HistoryMetric.CURRENT, viewport, 300)
            val zoomNanos = android.os.SystemClock.elapsedRealtimeNanos() - zoomStart
            val expected =
                database.readHistory(historyQuery(viewport)) { it.map { row -> row.key }.toList() }
            assertFalse(wide.raw)
            assertEquals(count.toLong(), wide.count)
            assertTrue(wide.points.size <= 1_700)
            assertTrue(narrow.raw)
            assertEquals(expected, narrow.points.filter {
                it.key.time in viewport.start..<viewport.end
            }.map { it.key })
            assertEquals(8L, narrow.count)
            assertTrue(narrow.points.any { point -> wide.points.none { it.key == point.key } })
            android.util.Log.i(
                "DetailedHistoryTest",
                "SQLite overview=${overviewNanos / 1_000_000.0}ms; " + "15-minute zoom=${zoomNanos / 1_000_000.0}ms; displayed=${wide.points.size}/$count"
            )
        }

    @Test
    fun database_validation_and_guard_records_preserve_gaps_at_viewport_edges(): Unit =
        runBlocking {
            database.addLogs(
                listOf(
                    observation.copy(time = 90, voltage = null),
                    observation.copy(time = 100),
                    observation.copy(time = 110, status = -1),
                    observation.copy(time = 120, voltage = -1),
                    observation.copy(time = 130),
                    observation.copy(time = 200, voltage = null)
                )
            )
            val range = HistoryRangeState(0, 300)
            val repository = LogsRepository(context)
            val model = repository.detailedChart(
                range, HistoryMetric.VOLTAGE, HistoryRangeState(100, 200), 800
            )
            assertEquals(4L, model.count)
            assertEquals(listOf(null, 4.0, null, null, 4.0, null), model.points.map { it.value })
            assertNotEquals(model.points[1].segment, model.points[4].segment)
            assertEquals(-1, model.events.single().code)
            val bounds = repository.detailedBounds(range, HistoryMetric.VOLTAGE)
            assertEquals(6L, bounds.count)
            assertEquals(4.0, bounds.min!!, 0.0)
            assertEquals(4.0, bounds.max!!, 0.0)
        }

    @Test
    fun periodic_status_rows_are_loaded_once_ordered_and_remain_connected_in_both_series(): Unit =
        runBlocking {
            val legacy = listOf(
                observation.copy(time = 10, status = 100),
                observation.copy(time = 1_000_000, status = 100)
            )
            val samples = (0..<600).map { index ->
                observation.copy(time = 10_000_000 + index * 1_000_000L, status = 200 + index % 100)
            }
            database.addLogs(legacy + samples)
            val repository = LogsRepository(context)
            val range = repository.historyRange()!!
            val source = database.readHistory(historyQuery(range)) { it.toList() }
            assertEquals(602, source.size)
            assertEquals(600, source.count { LogDatabase.decodeStatus(it.record.status)[2] == 2 })
            assertEquals(source.sortedWith(compareBy({ it.key.time }, { it.key.id })), source)
            val charts = repository.detailedCharts(
                range, listOf(HistoryMetric.LEVEL, HistoryMetric.CURRENT), pixelWidth = 800
            )
            val compact = repository.chart(range)
            for ((_, _, _, count, points, events, _, _, raw) in charts.values) {
                assertTrue(raw)
                assertEquals(source.size.toLong(), count)
                assertEquals(source.map { it.key }, points.map { it.key })
                assertEquals(1, points.map { it.segment }.distinct().size)
                assertTrue(events.isEmpty())
            }
            val reduced = repository.detailedCharts(
                range, listOf(HistoryMetric.LEVEL, HistoryMetric.CURRENT), pixelWidth = 3
            )
            for ((_, _, metric, count, points, events, min, max, raw) in reduced.values) {
                assertFalse(raw)
                assertEquals(602L, count)
                assertEquals(1, points.map { it.segment }.distinct().size)
                assertTrue(events.isEmpty())
                assertEquals(compact.series.getValue(metric).min, min)
                assertEquals(compact.series.getValue(metric).max, max)
            }
            val filters = repository.page(range, setOf("battery_measurement"), true, null)
            assertEquals(129, filters.size)
            assertTrue(filters.all { it.record.status in 200..299 })
            assertEquals(source.drop(2).take(129), filters)
            android.util.Log.i(
                "DetailedHistoryTest",
                "SQLite continuity rows=${source.size}; samples=600; raw=${charts.values.first().count}; " + "reduced=${reduced.values.first().count}; segments=1; confirmed interruptions=0"
            )
        }

    @Test
    fun all_history_bounds_follow_import_restore_delete_and_prune_without_loading_rows(): Unit =
        runBlocking {
            val repository = LogsRepository(context)
            assertNull(repository.historyRange())
            database.addLogs(listOf(observation.copy(time = 100)))
            assertEquals(HistoryRangeState(100, 101), repository.historyRange())
            database.addLogs(
                listOf(
                    observation.copy(time = 100),
                    observation.copy(time = 500),
                    observation.copy(time = 2, status = 100)
                )
            )
            assertEquals(HistoryRangeState(2, 501), repository.historyRange())
            database.deleteHistory(HistoryRangeState(0, 100))
            assertEquals(HistoryRangeState(100, 501), repository.historyRange())
            database.replaceAllLogs(listOf(observation.copy(time = 1_000)))
            assertEquals(HistoryRangeState(1_000, 1_001), repository.historyRange())
            val now = System.currentTimeMillis()
            database.replaceAllLogs(
                listOf(
                    observation.copy(time = now - 10_800_000), observation.copy(time = now)
                )
            )
            database.prune(1)
            assertEquals(HistoryRangeState(now, now + 1), repository.historyRange())
            database.deleteHistory(null)
            assertNull(repository.historyRange())
            database.addLogs(listOf(observation.copy(time = Long.MAX_VALUE)))
            val saturated = repository.historyRange()!!
            assertEquals(HistoryRangeState(Long.MAX_VALUE - 1, Long.MAX_VALUE), saturated)
            assertEquals(
                1L, repository.detailedChart(saturated, HistoryMetric.LEVEL, pixelWidth = 800).count
            )
            assertEquals(1, repository.page(saturated, null, true, null).size)
            database.deleteHistory(saturated)
            assertNull(repository.historyRange())
        }

    @Test
    fun all_history_keeps_event_pagination_filters_and_precise_selection(): Unit = runBlocking {
        database.addLogs((0..<400).map { index ->
            observation.copy(time = 100 + index / 2L, status = if (index % 2 == 0) 200 else 22)
        })
        val repository = LogsRepository(context)
        val range = repository.historyRange()!!
        val filters = setOf("plugged_in", "battery_measurement")
        val first = repository.page(range, filters, true, null)
        val next = repository.page(range, filters, true, first.last().key)
        assertEquals(129, first.size)
        assertEquals(129, next.size)
        assertEquals(258, (first + next).map { it.key }.distinct().size)
        assertTrue(first.last().key.time <= next.first().key.time)
        assertEquals(129, repository.page(range, setOf("plugged_in"), false, null).size)
        assertTrue(
            repository.page(range, setOf("plugged_in"), false, null).all { it.record.status == 22 })
        val selected = next[37]
        assertEquals(selected, repository.detailedSelection(selected.key))
        assertNull(repository.detailedSelection(selected.key.copy(time = selected.key.time + 1)))
        database.deleteHistory(HistoryRangeState(selected.key.time, selected.key.time + 1))
        assertNull(repository.detailedSelection(selected.key))
    }

    @Test
    fun only_successful_actual_mutations_notify_history_consumers(): Unit = runBlocking {
        assertTrue(notifications.isEmpty())
        val repository = LogsRepository(context)
        repository.historyRange()
        database.checkHealth()
        database.addLogs(emptyList())
        database.deleteHistory(null)
        assertTrue(notifications.isEmpty())
        val sampler = BatteryHistorySampler()
        assertEquals(LogResult.Inserted, database.logObservation(observation, 1_000, sampler, true))
        assertEquals(1, notifications.size)
        assertEquals(
            LogResult.Duplicate, database.logObservation(observation, 1_001, sampler, true)
        )
        assertEquals(1, notifications.size)
        assertEquals(
            LogResult.Inserted, database.logObservation(
                observation.copy(time = 121_000), 121_000, sampler, true
            )
        )
        assertEquals(2, notifications.size)
        database.prune(1)
        assertEquals(3, notifications.size)
        database.prune(1)
        assertEquals(3, notifications.size)
        assertEquals(LogResult.Inserted, database.logBoot())
        assertEquals(4, notifications.size)
        database.replaceAllLogs(listOf(observation))
        assertEquals(5, notifications.size)
        database.addLogs(listOf(observation.copy(time = 2_000)))
        assertEquals(6, notifications.size)
        database.deleteHistory(null)
        assertEquals(7, notifications.size)
        assertTrue(notifications.all {
            it.action == LogDatabase.ACTION_HISTORY_CHANGED && it.`package` == context.packageName
        })
    }

    @Test
    fun notification_failure_does_not_turn_a_persisted_observation_into_a_logging_failure(): Unit =
        runBlocking {
            notificationFailure = true
            val sampler = BatteryHistorySampler()
            assertEquals(
                LogResult.Inserted, database.logObservation(observation, 1_000, sampler, true)
            )
            assertEquals(1_000L, sampler.lastRecordedElapsed)
            assertEquals(
                1L, LogsRepository(context).detailedChart(
                    HistoryRangeState(0, 2_000), HistoryMetric.LEVEL, pixelWidth = 800
                ).count
            )
        }


    @Test
    fun visible_range_queries_use_the_existing_time_and_id_index() {
        database.addLogs(listOf(observation))
        val query = historyQuery(HistoryRangeState(100, 200))
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("logs.db").path, null, SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            db.rawQuery("EXPLAIN QUERY PLAN ${query.sql}", query.args).use { cursor ->
                val plans = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(3))
                }
                assertTrue(plans.any { it.contains("logs_time_id") })
                assertFalse(plans.any { it.contains("TEMP B-TREE") })
            }
        }
    }
}
