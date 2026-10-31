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
package codes.swistak.batterymonitor.profiling

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.core.database.sqlite.transaction
import codes.swistak.batterymonitor.logs.BatteryHistorySampler
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.logs.LogResult
import codes.swistak.batterymonitor.logs.historyQuery
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal object ProfileJournalVerification {
    private const val PROFILE_PACKAGE = "codes.swistak.batterymonitor.profile"
    private const val BUDGET_MILLIS = 50_000L
    private const val SEED_COUNT = 21_600
    private const val PERIODIC_COUNT = 128
    private const val IMPORT_COUNT = 5_000
    private const val LOG_COLUMNS =
        "_id,status,charge,time,temperature,voltage,current,remaining_charge_uah"
    private const val LOG_DDL =
        "CREATE TABLE logs (_id INTEGER PRIMARY KEY,status INTEGER,charge INTEGER,time INTEGER,temperature INTEGER,voltage INTEGER,current INTEGER,remaining_charge_uah INTEGER)"
    private const val JOURNAL_DDL =
        "CREATE TABLE history_changes (revision INTEGER PRIMARY KEY, first_time INTEGER NOT NULL, last_time INTEGER NOT NULL)"
    private const val INSERT_LOG =
        "INSERT INTO logs(status,charge,time,temperature,voltage,current,remaining_charge_uah) VALUES (?,?,?,?,?,?,?)"
    private const val INSERT_CHANGE =
        "INSERT INTO history_changes(first_time,last_time) VALUES (?,?)"
    private const val PRUNE_CHANGES = "DELETE FROM history_changes WHERE revision <= ?"
    private const val AFFECTED_TIMES =
        "SELECT (SELECT time FROM logs WHERE time IS NOT NULL ORDER BY time ASC, _id ASC LIMIT 1), (SELECT time FROM logs WHERE time IS NOT NULL ORDER BY time DESC, _id DESC LIMIT 1)"

    fun run(context: Context, intent: Intent): Bundle {
        require(context.packageName == PROFILE_PACKAGE)
        val mode = intent.getStringExtra("journalMode") ?: "correctness"
        require(mode in setOf("correctness", "overhead"))
        val pattern = intent.getStringExtra("journalPattern")
        val initialJournalRows = intent.getIntExtra("journalInitialRows", -1)
        if (mode == "overhead") {
            require(pattern in setOf("single", "periodic128", "bulkAdd5000", "bulkReplace5000"))
            require(initialJournalRows in setOf(0, 128))
        } else require(pattern == null && !intent.hasExtra("journalInitialRows"))
        val started = SystemClock.elapsedRealtime()
        val report = JSONObject().apply {
            put("format", "profile-journal-verification-v2")
            put("complete", false)
            put("mode", mode)
            put("correctnessExecuted", mode == "correctness")
            put("measurementExecuted", mode == "overhead")
            put("historicalSqlExperimentExecuted", mode == "overhead")
            put("productionPerformanceValidated", false)
            put("selectedPattern", pattern ?: JSONObject.NULL)
            put(
                "selectedSchema7InitialJournalRows",
                if (mode == "overhead") initialJournalRows else JSONObject.NULL
            )
            put("appPid", Process.myPid())
            put("appUid", Process.myUid())
            put("packageName", context.packageName)
            put("budgetMillis", BUDGET_MILLIS)
            put(
                "correctnessScope",
                "Real current LogDatabase methods on new private test databases; concurrent handles share this process, not main/BIS process-boundary evidence"
            )
            put(
                "measurementScope",
                "Optional manual historical synthetic SQL experiment: schema 6 versus schema 7; does not measure current production LogDatabase methods, APK or UI performance"
            )
            put("backupSerializationExercised", false)
            put("hardwareOrDeviceClockChanged", false)
        }
        val owner = File(context.cacheDir, "profile-journal-${SystemClock.elapsedRealtimeNanos()}")
        check(!owner.exists() && owner.mkdirs())
        report.put("ownedTemporaryDirectory", owner.absolutePath)
        fun budget() {
            check(!Thread.currentThread().isInterrupted) { "Journal verifier interrupted" }
            check(SystemClock.elapsedRealtime() - started < BUDGET_MILLIS) { "Journal verifier budget exhausted" }
        }
        try {
            if (mode == "correctness") {
                val correctness = JSONArray()
                report.put("correctness", correctness)
                verifyContract(context, owner, correctness, ::budget)
            } else {
                val writeCost = JSONObject().put("passed", false)
                report.put("writeCost", writeCost)
                measureWriteCost(
                    owner, writeCost, checkNotNull(pattern), initialJournalRows, ::budget
                )
            }
            report.put("complete", true)
        } catch (error: Exception) {
            report.put("failure", error.javaClass.simpleName + ": " + error.message)
            report.put("failureStack", error.stackTraceToString())
        } finally {
            report.put("elapsedMillis", SystemClock.elapsedRealtime() - started)
            // A failed run keeps only its newly created test directory for diagnosis.
            val cleaned = report.optBoolean("complete") && owner.deleteRecursively()
            report.put("ownedTemporaryDirectoryRemoved", cleaned)
            if (report.optBoolean("complete") && !cleaned) {
                report.put("complete", false)
                report.put("failure", "Could not remove the owned journal test directory")
            }
        }
        val outputDirectory = File(context.filesDir, "profiling/final-verification")
        check(outputDirectory.isDirectory || outputDirectory.mkdirs())
        val output = File(outputDirectory, "journal-${SystemClock.elapsedRealtimeNanos()}.json")
        check(!output.exists())
        output.writeText(report.toString(2))
        return Bundle().apply {
            putBoolean("complete", report.optBoolean("complete"))
            putString("reportJson", report.toString())
            putString("outputPath", output.absolutePath)
        }
    }

    private class IsolatedContext(target: Context, directory: File) : ContextWrapper(target) {
        val broadcasts = AtomicInteger()
        val file = File(directory, "logs.db")

        init {
            check(directory.mkdirs())
        }

        override fun getApplicationContext(): Context = this
        override fun sendBroadcast(intent: Intent) {
            broadcasts.incrementAndGet()
        }

        override fun getDatabasePath(name: String): File {
            require(name == "logs.db")
            return file
        }

        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
        ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?
        ): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
    }

    private fun verifyContract(target: Context, owner: File, cases: JSONArray, budget: () -> Unit) {
        fun case(name: String, action: (LogDatabase, IsolatedContext, JSONObject) -> Unit) {
            budget()
            val item = JSONObject().put("name", name).put("passed", false)
            cases.put(item)
            val context = IsolatedContext(target, File(owner, name))
            val database = LogDatabase(context)
            try {
                action(database, context, item)
                item.put("finalDatabase", inspect(context.file))
                item.put("passed", true)
            } catch (error: Exception) {
                item.put("failure", error.toString())
                throw error
            } finally {
                database.close()
            }
        }

        val observation = LogRecord(203, 50, 1_000, 300, 4_000, -100_000, 2_500_000)
        case("small_pending_and_range_boundaries") { database, _, item ->
            check(!database.historyChanges(0, null).affectsRange)
            check(database.historyChanges(null, null).affectsRange)
            database.addLogs(listOf(observation))
            val before = database.historyChanges(null, null).revision
            val closed = HistoryRangeState(1_000, 2_000)
            database.addLogs(listOf(observation.copy(time = 2_000)))
            val outside = database.historyChanges(before, closed)
            check(outside.revision > before && !outside.affectsRange)
            database.addLogs(listOf(observation.copy(time = 1_999)))
            check(database.historyChanges(outside.revision, closed).affectsRange)
            val acknowledged = database.historyChanges(null, null).revision
            check(!database.historyChanges(acknowledged, closed).affectsRange)
            database.addLogs(listOf(observation.copy(time = 1_000, currentMicroAmps = null)))
            check(database.historyChanges(acknowledged, closed).affectsRange)
            check(database.getAllLogRecords().size == 4)
            item.put("outsideRevisionAcknowledged", outside.revision)
            item.put("inclusiveStartExclusiveEnd", true)
        }
        case("pending_127_128_129_and_overflow") { database, context, item ->
            database.addLogs(listOf(observation))
            val consumer = database.historyChanges(null, null).revision
            val closed = HistoryRangeState(0, 2_000)
            for (index in 1..129) {
                budget()
                database.addLogs(listOf(observation.copy(time = 10_000L + index)))
                if (index in setOf(1, 127, 128, 129)) {
                    val change = database.historyChanges(consumer, closed)
                    check(change.affectsRange == (index == 129))
                    item.put("pending$index", JSONObject().apply {
                        put("revision", change.revision)
                        put("affectsRange", change.affectsRange)
                        put("journal", journal(context.file))
                    })
                }
            }
            val latest = database.historyChanges(null, null).revision
            check(journal(context.file).getInt("count") == 128)
            check(!database.historyChanges(latest, closed).affectsRange)
            check(database.historyChanges(latest + 1, closed).affectsRange)
            check(database.getAllLogRecords().size == 130)
        }
        case("bulk_add_replace_delete_clear_and_noops") { database, context, item ->
            val original = listOf(
                observation, observation.copy(
                    time = 2_000,
                    charge = null,
                    temperature = null,
                    currentMicroAmps = null,
                    remainingChargeMicroampHours = null
                )
            )
            database.addLogs(original)
            val beforeAdd = database.historyChanges(null, null).revision
            val originalDigest = digestRows(context.file, "_id <= 2")
            val imported =
                (0 until IMPORT_COUNT).map { index -> observation.copy(time = 10_000L + index) }
            database.addLogs(imported)
            check(database.historyChanges(null, null).revision == beforeAdd + 1)
            check(!database.historyChanges(beforeAdd, HistoryRangeState(0, 3_000)).affectsRange)
            check(
                database.historyChanges(
                    beforeAdd, HistoryRangeState(10_000, 10_001)
                ).affectsRange
            )
            check(digestRows(context.file, "_id <= 2") == originalDigest)
            check(database.getAllLogRecords() == original + imported)
            val beforeReplace = database.historyChanges(null, null).revision
            val restored = listOf(
                observation.copy(time = 30_000),
                observation.copy(time = 31_000, voltage = null),
                observation.copy(
                    time = 32_000,
                    charge = null,
                    temperature = null,
                    currentMicroAmps = null,
                    remainingChargeMicroampHours = null
                )
            )
            database.replaceAllLogs(restored)
            check(database.getAllLogRecords() == restored)
            check(database.historyChanges(null, null).revision == beforeReplace + 1)
            check(
                database.historyChanges(
                    beforeReplace, HistoryRangeState(1_000, 1_001)
                ).affectsRange
            )
            check(
                database.historyChanges(
                    beforeReplace, HistoryRangeState(31_000, 31_001)
                ).affectsRange
            )
            check(
                !database.historyChanges(
                    beforeReplace, HistoryRangeState(40_000, 41_000)
                ).affectsRange
            )
            val beforeDelete = database.historyChanges(null, null).revision
            database.deleteHistory(HistoryRangeState(31_000, 31_001))
            check(database.getAllLogRecords() == listOf(restored[0], restored[2]))
            check(database.historyChanges(null, null).revision == beforeDelete + 1)
            check(
                database.historyChanges(
                    beforeDelete, HistoryRangeState(31_000, 31_001)
                ).affectsRange
            )
            val beforeClear = database.historyChanges(null, null).revision
            database.deleteHistory(null)
            check(database.getAllLogRecords().isEmpty())
            val afterClear = database.historyChanges(null, null).revision
            check(afterClear == beforeClear + 1)
            check(
                database.historyChanges(
                    beforeClear, HistoryRangeState(30_000, 30_001)
                ).affectsRange
            )
            val notifications = context.broadcasts.get()
            database.deleteHistory(null)
            database.addLogs(emptyList())
            database.replaceAllLogs(emptyList())
            check(database.historyChanges(null, null).revision == afterClear)
            check(context.broadcasts.get() == notifications)
            item.put("importRows", IMPORT_COUNT)
            item.put("originalRowsDigestUnchangedByAdd", originalDigest)
            item.put("bulkRevisionIncrement", 1)
            item.put("oldAndNewReplacementIntervalsCovered", true)
            item.put("nullableFieldsPreserved", true)
            item.put("noOpMutationDidNotNotify", true)
        }
        case("journal_failure_rolls_back_all_state") { database, context, item ->
            database.addLogs(listOf(observation))
            val before = inspect(context.file)
            val revision = database.historyChanges(null, null).revision
            val notifications = context.broadcasts.get()
            SQLiteDatabase.openDatabase(context.file.path, null, SQLiteDatabase.OPEN_READWRITE)
                .use { handle ->
                    handle.execSQL("CREATE TRIGGER fail_revision BEFORE INSERT ON history_changes BEGIN SELECT RAISE(ABORT, 'expected journal verification failure'); END")
                }
            val sampler = BatteryHistorySampler()
            check(
                database.logObservation(
                    observation.copy(time = 121_000), 120_000, sampler, true
                ) is LogResult.Failed
            )
            check(database.historyChanges(null, null).revision == revision)
            check(inspect(context.file).getString("rowsSha256") == before.getString("rowsSha256"))
            check(sampler.lastRecordedElapsed == null && context.broadcasts.get() == notifications)
            SQLiteDatabase.openDatabase(context.file.path, null, SQLiteDatabase.OPEN_READWRITE)
                .use { it.execSQL("DROP TRIGGER fail_revision") }
            check(
                database.logObservation(
                    observation.copy(time = 121_000), 120_000, sampler, true
                ) == LogResult.Inserted
            )
            check(database.historyChanges(null, null).revision == revision + 1)
            check(sampler.lastRecordedElapsed == 120_000L && context.broadcasts.get() == notifications + 1)
            check(database.getAllLogRecords().size == 2)
            item.put("beforeFailure", before)
            item.put("rowsRevisionSamplerBroadcastRolledBack", true)
            item.put("sameSamplerRetryCommittedOnce", true)
        }
        case("concurrent_snapshot_then_commit") { database, context, item ->
            val records = (0 until 1_000).map { observation.copy(time = it.toLong()) }
            database.addLogs(records)
            val before = database.historyChanges(null, null).revision
            val originalDigest = digestRows(context.file, "_id <= 1000")
            val writerReady = CountDownLatch(1)
            val startWrite = CountDownLatch(1)
            val attemptingWrite = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val writer = Thread {
                var other: LogDatabase? = null
                try {
                    other = LogDatabase(context)
                    writerReady.countDown()
                    check(startWrite.await(5, TimeUnit.SECONDS))
                    attemptingWrite.countDown()
                    other.addLogs(listOf(observation.copy(time = 1_001)))
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    writerReady.countDown()
                    attemptingWrite.countDown()
                    other?.close()
                }
            }.apply {
                isDaemon = true
                name = "profile-journal-writer"
                start()
            }
            try {
                check(writerReady.await(5, TimeUnit.SECONDS))
                failure.get()?.let {
                    throw IllegalStateException(
                        "Writer could not open the isolated database", it
                    )
                }
                val count =
                    database.readHistory(historyQuery(HistoryRangeState(0, 2_000))) { rows ->
                        rows.onEach {
                            startWrite.countDown()
                            check(attemptingWrite.await(5, TimeUnit.SECONDS))
                        }.count()
                    }
                writer.join(8_000)
                check(!writer.isAlive) { "Concurrent writer did not finish after the snapshot closed" }
                failure.get()?.let { throw IllegalStateException("Concurrent writer failed", it) }
                check(count == 1_000)
                check(database.getAllLogRecords().size == 1_001)
                check(database.historyChanges(before, HistoryRangeState(0, 2_000)).affectsRange)
                check(digestRows(context.file, "_id <= 1000") == originalDigest)
                item.put("readSnapshotRows", count)
                item.put("committedRowsAfterSnapshot", 1_001)
                item.put("writerPid", Process.myPid())
                item.put("sameProcessSeparateHandles", true)
                item.put("originalRowsDigestUnchanged", originalDigest)
            } finally {
                startWrite.countDown()
                if (writer.isAlive) {
                    writer.interrupt()
                    writer.join(1_000)
                }
            }
        }
    }

    private data class Sample(
        val status: Int,
        val charge: Int?,
        val time: Long,
        val temperature: Int?,
        val voltage: Int?,
        val current: Long?,
        val remaining: Long?
    ) {
        fun values(): List<Long?> = listOf(
            status.toLong(),
            charge?.toLong(),
            time,
            temperature?.toLong(),
            voltage?.toLong(),
            current,
            remaining
        )
    }

    // Historical SQL comparison, invoked only by the explicit overhead mode; verifyContract uses LogDatabase.
    private fun measureWriteCost(
        owner: File,
        result: JSONObject,
        pattern: String,
        initialJournalRows: Int,
        budget: () -> Unit
    ) {
        result.put("experiment", "historical-synthetic-sql-schema6-vs-schema7")
        val directory = File(owner, "write-cost").apply { check(mkdirs()) }
        val anchor = 1_792_200_000_000L
        val seed = (0 until SEED_COUNT).map { index ->
            Sample(
                if (index / 720 % 2 == 0) 203 else 212,
                20 + index / 30 % 75,
                anchor - (SEED_COUNT - index) * 120_000L,
                if (index % 11 == 0) null else 280 + index % 80,
                if (index % 13 == 0) null else 3_700 + index % 300,
                if (index % 7 == 0) null else if (index / 720 % 2 == 0) -100_000L - index else 200_000L + index,
                if (index % 17 == 0) null else 1_000_000L + index
            )
        }
        val original = File(directory, "schema6-template.db")
        SQLiteDatabase.openOrCreateDatabase(original, null).use { database ->
            database.execSQL(LOG_DDL)
            database.execSQL("CREATE INDEX logs_time_id ON logs(time, _id)")
            database.version = 6
            database.compileStatement(INSERT_LOG).use { statement ->
                database.beginTransaction()
                try {
                    seed.forEach {
                        bindAndInsert(
                            statement, it
                        )
                    }
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }
        val seedDigest = digestRows(original)
        val templates = mutableMapOf<Pair<Int, Int>, File>()
        templates[6 to 0] = original
        for (initial in listOf(initialJournalRows)) {
            val file = File(directory, "schema7-template-$initial.db")
            original.copyTo(file)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
                .use { database ->
                    database.execSQL(JOURNAL_DDL)
                    database.version = 7
                    database.compileStatement(INSERT_CHANGE).use { insert ->
                        database.beginTransaction()
                        try {
                            repeat(initial) { index ->
                                insert.bindLong(1, anchor - index)
                                insert.bindLong(2, anchor - index)
                                insert.executeInsert()
                            }
                            database.setTransactionSuccessful()
                        } finally {
                            database.endTransaction()
                        }
                    }
                }
            check(digestRows(file) == seedDigest)
            templates[7 to initial] = file
        }
        val runs = JSONArray()
        val baselineConfiguration =
            SQLiteDatabase.openDatabase(original.path, null, SQLiteDatabase.OPEN_READWRITE)
                .use { configuration(it) }
        val summary = JSONArray()
        result.put("runs", runs)
        result.put("summary", summary)
        budget()
        val durations = mutableMapOf(6 to mutableListOf<Long>(), 7 to mutableListOf<Long>())
        val order = listOf(6, 7, 7, 6)
        for ((block, schema) in order.withIndex()) {
            val repetitions = 1
            repeat(repetitions) { trial ->
                budget()
                val file = File(directory, "run-$initialJournalRows-$pattern-$block-$trial.db")
                val template =
                    checkNotNull(templates[schema to if (schema == 6) 0 else initialJournalRows])
                template.copyTo(file)
                val sampleCount = when (pattern) {
                    "single" -> 1
                    "periodic128" -> PERIODIC_COUNT
                    else -> IMPORT_COUNT
                }
                val samples = (0 until sampleCount).map { index ->
                    Sample(
                        203,
                        50,
                        anchor + index * 120_000L,
                        if (index % 11 == 0) null else 300,
                        4_000,
                        -100_000,
                        if (index % 17 == 0) null else 2_500_000
                    )
                }
                val values = mutableListOf<Long>()
                val rawDurations = JSONArray()
                val run = JSONObject().apply {
                    put("pattern", pattern)
                    put("pairedSchema7InitialJournalRows", initialJournalRows)
                    put("schema", schema)
                    put("abbaBlock", block + 1)
                    put("trial", trial + 1)
                    put(
                        "actualInitialJournalRows",
                        if (schema == 7) initialJournalRows else JSONObject.NULL
                    )
                    put("rowInsertions", sampleCount)
                    put("verified", false)
                    put("transactionDurationNanos", rawDurations)
                }
                runs.put(run)
                var writerConfiguration = JSONObject()
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
                    .use { database ->
                        writerConfiguration = configuration(database)
                        check(writerConfiguration.toString() == baselineConfiguration.toString())
                        database.compileStatement(INSERT_LOG).use { insert ->
                            val change =
                                if (schema == 7) database.compileStatement(INSERT_CHANGE) else null
                            val prune =
                                if (schema == 7) database.compileStatement(PRUNE_CHANGES) else null
                            try {
                                fun measure(rows: List<Sample>) {
                                    val duration = timedTransaction(
                                        database, insert, change, prune, rows, pattern
                                    )
                                    values += duration
                                    rawDurations.put(duration)
                                }
                                if (pattern == "periodic128") samples.forEach { sample ->
                                    budget()
                                    measure(listOf(sample))
                                } else measure(samples)
                            } finally {
                                change?.close()
                                prune?.close()
                            }
                        }
                    }
                val replaces = pattern == "bulkReplace5000"
                val expectedRows = if (replaces) samples.size else SEED_COUNT + samples.size
                val after = inspect(file)
                check(after.getInt("rowCount") == expectedRows)
                if (!replaces) check(digestRows(file, "_id <= $SEED_COUNT") == seedDigest)
                val firstId = if (replaces) 1L else SEED_COUNT + 1L
                check(
                    digestRows(
                        file, if (replaces) null else "_id > $SEED_COUNT"
                    ) == expectedDigest(samples, firstId)
                )
                val transactions = if (pattern == "periodic128") PERIODIC_COUNT else 1
                if (schema == 7) check(
                    journal(file).getInt("count") == minOf(
                        128, initialJournalRows + transactions
                    )
                )
                else check(!after.getBoolean("hasJournalTable"))
                durations.getValue(schema).addAll(values)
                run.apply {
                    put("transactions", transactions)
                    put("after", after)
                    put("writerConfiguration", writerConfiguration)
                    put("oldRowsPreserved", !replaces)
                    put("expectedInsertedRowsSha256", expectedDigest(samples, firstId))
                    put("verified", true)
                }
                check(file.delete())
                File(file.path + "-journal").takeIf { it.exists() }?.let { check(it.delete()) }
            }
        }
        val old = durations.getValue(6)
        val current = durations.getValue(7)
        val oldMedian = median(old)
        val newMedian = median(current)
        summary.put(JSONObject().apply {
            put("pattern", pattern)
            put("pairedSchema7InitialJournalRows", initialJournalRows)
            put("schema6Samples", old.size)
            put("schema7Samples", current.size)
            put("schema6MedianNanos", oldMedian)
            put("schema7MedianNanos", newMedian)
            put("schema6P95Nanos", percentile(old, .95))
            put("schema7P95Nanos", percentile(current, .95))
            put("medianIncrementNanos", newMedian - oldMedian)
            put(
                "medianIncrementPerInsertedRowNanos",
                (newMedian - oldMedian) / if (pattern.startsWith("bulk")) IMPORT_COUNT else 1
            )
        })
        result.apply {
            put("passed", true)
            put("seedRows", SEED_COUNT)
            put("seedRowsSha256", seedDigest)
            put("historicalDefinition", JSONObject().apply {
                put("archive", "baseline/source-before.tar.gz")
                put(
                    "member",
                    "source-before/app/src/main/kotlin/codes/swistak/batterymonitor/logs/LogDatabase.kt"
                )
                put("sha256", "72ab6d6c53a7b753677293b1fd2136259a0df7dd7827ab5b9cdb7937c6540403")
            })
            put("seedAnchorMillis", anchor)
            put("abbaOrder", JSONArray(listOf(6, 7, 7, 6)))
            put("trialsPerBlock", 1)
            put("configuration", baselineConfiguration)
            put("runs", runs)
            put("summary", summary)
            put(
                "percentileDefinition",
                "Median averages the central pair for even samples; p95 uses nearest-rank. Each schema has only two independent ABBA blocks; small single/import sample counts limit inference."
            )
            put(
                "sql", JSONObject().apply {
                    put("logs", LOG_DDL)
                    put("journal", JOURNAL_DDL)
                    put("insertLog", INSERT_LOG)
                    put("insertJournal", INSERT_CHANGE)
                    put("pruneJournal", PRUNE_CHANGES)
                    put("replaceAffectedTimes", AFFECTED_TIMES)
                })
            put(
                "includedInTiming",
                "BEGIN/COMMIT, identical logs SQL, prior status/charge SELECT for single/periodic, and schema7 journal INSERT/prune. Batch schema7 also computes incoming min/max; REPLACE additionally queries old min/max before common DELETE WHERE 1."
            )
            put(
                "excludedFromTiming",
                "Dataset creation, database copies, opening/migration, statement preparation, integrity/digest/configuration verification, JSON serialization, broadcasts and UI"
            )
            put(
                "limits", JSONArray(
                    listOf(
                        "SQL-path control uses schema6 DDL and matched current log SQL, not execution of the preserved original APK methods.",
                        "Common DELETE WHERE 1 deliberately isolates journal costs; historical original REPLACE used an unqualified DELETE.",
                        "Initially empty/128-entry journal describes metadata occupancy, not a cold filesystem/SQLite cache.",
                        "Prepared statements omit first-use compilation; scheduler and fsync noise remain. Small ABBA sample counts, especially imports, do not establish a causal application performance result.",
                        "Negative measured increments are retained as noise/order observations, not an optimization claim.",
                        "Synthetic periodic timestamps do not change real logging frequency or the device clock."
                    )
                )
            )
        }
    }

    private fun timedTransaction(
        database: SQLiteDatabase,
        insert: SQLiteStatement,
        change: SQLiteStatement?,
        prune: SQLiteStatement?,
        samples: List<Sample>,
        pattern: String
    ): Long {
        val started = SystemClock.elapsedRealtimeNanos()
        database.transaction {
            try {
                if (pattern == "single" || pattern == "periodic128") rawQuery(
                    "SELECT status, charge FROM logs ORDER BY _id DESC LIMIT 1", null
                ).use { check(it.moveToFirst()) }
                val previous = if (change != null && pattern == "bulkReplace5000") rawQuery(
                    AFFECTED_TIMES, null
                ).use { cursor ->
                    cursor.moveToFirst()
                    if (cursor.isNull(0)) null else cursor.getLong(0)..cursor.getLong(1)
                } else null
                if (pattern == "bulkReplace5000") execSQL("DELETE FROM logs WHERE 1")
                samples.forEach { bindAndInsert(insert, it) }
                if (change != null) {
                    val first: Long
                    val last: Long
                    if (pattern.startsWith("bulk")) {
                        first = listOfNotNull(
                            previous?.first, samples.minOfOrNull { it.time }).minOrNull()
                            ?: Long.MIN_VALUE
                        last = listOfNotNull(
                            previous?.last, samples.maxOfOrNull { it.time }).maxOrNull()
                            ?: Long.MAX_VALUE
                    } else {
                        first = samples.single().time
                        last = first
                    }
                    change.bindLong(1, first)
                    change.bindLong(2, last)
                    val revision = change.executeInsert()
                    checkNotNull(prune).bindLong(1, revision - LogDatabase.HISTORY_CHANGE_LIMIT)
                    prune.executeUpdateDelete()
                }
            } finally {
            }
        }
        return SystemClock.elapsedRealtimeNanos() - started
    }

    private fun bindAndInsert(statement: SQLiteStatement, sample: Sample) {
        sample.values().forEachIndexed { index, value ->
            if (value == null) statement.bindNull(index + 1) else statement.bindLong(
                index + 1, value
            )
        }
        check(statement.executeInsert() >= 0)
    }

    private fun configuration(database: SQLiteDatabase): JSONObject = JSONObject().apply {
        for (name in listOf(
            "journal_mode", "synchronous", "page_size", "cache_size"
        )) database.rawQuery("PRAGMA $name", null).use { cursor ->
            cursor.moveToFirst()
            put(name, cursor.getString(0))
        }
        database.rawQuery("SELECT sqlite_version()", null).use { cursor ->
            cursor.moveToFirst()
            put("sqliteVersion", cursor.getString(0))
        }
    }

    private fun inspect(file: File): JSONObject =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            JSONObject().apply {
                put("schemaVersion", database.version)
                database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == "ok")
                    put("quickCheck", "ok")
                }
                database.rawQuery(
                    "SELECT COUNT(*), SUM(charge IS NULL), SUM(temperature IS NULL), SUM(voltage IS NULL), SUM(current IS NULL), SUM(remaining_charge_uah IS NULL) FROM logs",
                    null
                ).use { cursor ->
                    cursor.moveToFirst()
                    put("rowCount", cursor.getLong(0))
                    put("nullableFieldCounts", JSONArray((1..5).map { cursor.getLong(it) }))
                }
                put("rowsSha256", digestRows(file))
                database.rawQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='history_changes'",
                    null
                ).use { cursor ->
                    cursor.moveToFirst()
                    put("hasJournalTable", cursor.getInt(0) == 1)
                }
                if (getBoolean("hasJournalTable")) put("journal", journal(file))
            }
        }

    private fun journal(file: File): JSONObject =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery(
                "SELECT COUNT(*), MIN(revision), MAX(revision) FROM history_changes", null
            ).use { cursor ->
                cursor.moveToFirst()
                JSONObject().apply {
                    put("count", cursor.getInt(0))
                    put(
                        "firstRevision",
                        if (cursor.isNull(1)) JSONObject.NULL else cursor.getLong(1)
                    )
                    put(
                        "lastRevision", if (cursor.isNull(2)) JSONObject.NULL else cursor.getLong(2)
                    )
                }
            }
        }

    private fun digestRows(file: File, where: String? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT $LOG_COLUMNS FROM logs" + where?.let { " WHERE $it" }
                .orEmpty() + " ORDER BY _id", null).use { cursor ->
                while (cursor.moveToNext()) updateDigest(
                    digest, (0 until 8).map { if (cursor.isNull(it)) null else cursor.getLong(it) })
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun expectedDigest(samples: List<Sample>, firstId: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        samples.forEachIndexed { index, sample ->
            updateDigest(
                digest, listOf(firstId + index) + sample.values()
            )
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateDigest(digest: MessageDigest, row: List<Long?>) {
        digest.update((row.joinToString("\t") { it?.toString() ?: "NULL" } + "\n").toByteArray(
            Charsets.UTF_8
        ))
    }

    private fun percentile(values: List<Long>, quantile: Double): Long {
        val sorted = values.sorted()
        return sorted[(kotlin.math.ceil(quantile * sorted.size).toInt() - 1).coerceIn(
            0, sorted.lastIndex
        )]
    }

    private fun median(values: List<Long>): Double {
        val sorted = values.sorted()
        return (sorted[(sorted.size - 1) / 2].toDouble() + sorted[sorted.size / 2]) / 2
    }
}
