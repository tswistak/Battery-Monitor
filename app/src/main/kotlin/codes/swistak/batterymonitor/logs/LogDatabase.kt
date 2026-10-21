/*
    Copyright (c) 2009-2020 Darshan Computing, LLC
    Modified in 2026 by Tomasz Świstak <tomasz@swistak.codes> for the Battery Monitor fork.
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

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.core.database.sqlite.transaction
import codes.swistak.batterymonitor.monitoring.batteryvoltage.BatteryVoltageValidator


internal data class LogRecord(
    val status: Int,
    val charge: Int?,
    val time: Long,
    val temperature: Int?,
    val voltage: Int?,
    val currentMicroAmps: Long? = null,
    val remainingChargeMicroampHours: Long? = null
)

internal sealed interface LogResult {
    data object Inserted : LogResult
    data object Duplicate : LogResult
    data class Failed(val error: Throwable) : LogResult
}

internal class LogDatabase(context: Context?) {
    companion object {
        private const val LOG_TAG = "LogDatabase"

        private const val DATABASE_NAME = "logs.db"
        private const val DATABASE_VERSION = 7
        internal const val HISTORY_CHANGE_LIMIT = 128
        private const val HISTORY_CHANGES_TABLE = "history_changes"

        private const val LOG_TABLE_NAME = "logs"

        private const val KEY_ID = "_id"
        const val KEY_STATUS_CODE: String = "status"
        const val KEY_CHARGE: String = "charge"
        const val KEY_TIME: String = "time"
        const val KEY_TEMPERATURE: String = "temperature"
        const val KEY_VOLTAGE: String = "voltage"
        const val KEY_CURRENT: String = "current"
        const val KEY_REMAINING_CHARGE: String = "remaining_charge_uah"

        const val ACTION_HISTORY_CHANGED = "codes.swistak.batterymonitor.HISTORY_CHANGED"

        const val STATUS_BOOT_COMPLETED: Int = -1

        // Values for status_age
        const val STATUS_NEW: Int = 0
        const val STATUS_OLD: Int = 1
        const val STATUS_SAMPLE: Int = 2

        /* My cursor adapter was getting a bit complicated since it could only see one datum at a time, and
       how I want to present the data depends on several interrelated factors.  Storing all three of
       these items together simplifies things. */
        internal fun encodeStatus(status: Int, plugged: Int, statusAge: Int): Int {
            return status + (plugged * 10) + (statusAge * 100)
        }

        /* Returns [status, plugged, status_age] */
        fun decodeStatus(statusCode: Int): IntArray {
            var statusCode = statusCode
            if (statusCode < 0) return intArrayOf(statusCode, 0, 0)

            val a = IntArray(3)

            a[2] = statusCode / 100
            statusCode -= a[2] * 100
            a[1] = statusCode / 10
            statusCode -= a[1] * 10
            a[0] = statusCode

            return a
        }
    }

    private val appContext = context?.applicationContext

    fun historyChanges(since: Long?, range: HistoryRangeState?): HistoryChanges {
        openDBs()
        return checkNotNull(rdb).rawQuery(
            "SELECT MIN(revision), MAX(revision), MAX(CASE WHEN revision > ? AND first_time <= ? AND last_time >= ? THEN 1 ELSE 0 END) FROM $HISTORY_CHANGES_TABLE",
            arrayOf(
                (since ?: -1).toString(),
                (range?.exportThrough ?: Long.MAX_VALUE).toString(),
                (range?.start ?: Long.MIN_VALUE).toString()
            )
        ).use { cursor ->
            cursor.moveToFirst()
            val first = if (cursor.isNull(0)) 0L else cursor.getLong(0)
            val latest = if (cursor.isNull(1)) 0L else cursor.getLong(1)
            // A cursor older than the bounded journal must refresh rather than miss a committed change.
            HistoryChanges(
                latest,
                since == null || since > latest || since < first - 1 || cursor.getInt(2) != 0
            )
        }
    }

    private fun SQLiteDatabase.recordHistoryChange(first: Long, last: Long) {
        val values = ContentValues().apply {
            put("first_time", first)
            put("last_time", last)
        }
        val revision = insertOrThrow(HISTORY_CHANGES_TABLE, null, values)
        delete(
            HISTORY_CHANGES_TABLE,
            "revision <= ?",
            arrayOf((revision - HISTORY_CHANGE_LIMIT).toString())
        )
    }

    private fun SQLiteDatabase.affectedTimes(
        where: String? = null, args: Array<String>? = null
    ): LongRange? {
        val predicate = " WHERE time IS NOT NULL" + where?.let { " AND ($it)" }.orEmpty()
        return rawQuery(
            "SELECT (SELECT time FROM logs$predicate ORDER BY time ASC, _id ASC LIMIT 1), (SELECT time FROM logs$predicate ORDER BY time DESC, _id DESC LIMIT 1)",
            args?.let { it + it }).use { cursor ->
            cursor.moveToFirst()
            if (cursor.isNull(0)) null else cursor.getLong(0)..cursor.getLong(1)
        }
    }

    private fun historyChanged() {
        try {
            appContext?.let { it.sendBroadcast(Intent(ACTION_HISTORY_CHANGED).setPackage(it.packageName)) }
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "Unable to notify history consumers", error)
        }
    }

    private val mSQLOpenHelper: SQLOpenHelper = SQLOpenHelper(context)
    private var rdb: SQLiteDatabase? = null
    private var wdb: SQLiteDatabase? = null

    init {
        openDBs()
    }

    private fun openDBs() {
        if (rdb == null || !rdb!!.isOpen) {
            try {
                rdb = mSQLOpenHelper.readableDatabase
            } catch (e: SQLiteException) {
                rdb = null
                Log.e(LOG_TAG, "Unable to open the log database for reading", e)
            }
        }

        if (wdb == null || !wdb!!.isOpen) {
            try {
                wdb = mSQLOpenHelper.writableDatabase
            } catch (e: SQLiteException) {
                wdb = null
                Log.e(LOG_TAG, "Unable to open the log database for writing", e)
            }
        }
    }

    private fun closeDatabaseHandles() {
        val readableDatabase = rdb
        val writableDatabase = wdb
        rdb = null
        wdb = null

        try {
            readableDatabase?.close()
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "Unable to close the readable log database handle", e)
        }
        if (writableDatabase !== readableDatabase) {
            try {
                writableDatabase?.close()
            } catch (e: RuntimeException) {
                Log.w(LOG_TAG, "Unable to close the writable log database handle", e)
            }
        }
    }

    private fun requireDatabases(): Pair<SQLiteDatabase, SQLiteDatabase> {
        openDBs()
        val readableDatabase =
            rdb ?: throw SQLiteException("Log database is unavailable for reading")
        val writableDatabase =
            wdb ?: throw SQLiteException("Log database is unavailable for writing")
        return readableDatabase to writableDatabase
    }

    private inline fun writeWithRetry(
        operationName: String, operation: (SQLiteDatabase, SQLiteDatabase) -> LogResult
    ): LogResult {
        var firstError: SQLiteException? = null
        repeat(2) { attempt ->
            try {
                val (readableDatabase, writableDatabase) = requireDatabases()
                return operation(readableDatabase, writableDatabase)
            } catch (e: SQLiteException) {
                if (attempt == 0) {
                    firstError = e
                    Log.w(
                        LOG_TAG,
                        "$operationName failed; reopening the database and retrying once",
                        e
                    )
                    closeDatabaseHandles()
                } else {
                    firstError?.let { e.addSuppressed(it) }
                    Log.e(LOG_TAG, "$operationName failed after retry", e)
                    return LogResult.Failed(e)
                }
            } catch (e: RuntimeException) {
                Log.e(LOG_TAG, "$operationName failed", e)
                return LogResult.Failed(e)
            }
        }
        error("Unreachable")
    }

    fun close() {
        closeDatabaseHandles()
    }

    fun getAllLogRecords(): List<LogRecord> {
        return getLogRecordsInRange()
    }

    fun <T> readHistory(query: HistoryQuery, block: (Sequence<HistoryRecord>) -> T): T {
        openDBs()
        val database = checkNotNull(rdb) { "Log database is unavailable" }
        return database.rawQuery(query.sql, query.args).use { cursor ->
            block(sequence {
                while (cursor.moveToNext()) {
                    yield(
                        HistoryRecord(
                            cursor.getLong(0), LogRecord(
                                status = cursor.getInt(1),
                                charge = cursor.getNullableInt(2),
                                time = cursor.getLong(3),
                                temperature = cursor.getNullableInt(4),
                                voltage = cursor.getNullableInt(5)
                                    ?.takeIf(BatteryVoltageValidator::isValidBroadcastMillivolts),
                                currentMicroAmps = cursor.getNullableLong(6),
                                remainingChargeMicroampHours = cursor.getNullableLong(7)
                            )
                        )
                    )
                }
            })
        }
    }

    fun retainedHistoryRange(): HistoryRangeState? {
        openDBs()
        val database = checkNotNull(rdb) { "Log database is unavailable" }
        return database.rawQuery(
            "SELECT (SELECT time FROM logs WHERE time >= 0 ORDER BY time ASC, _id ASC LIMIT 1), " + "(SELECT time FROM logs WHERE time >= 0 ORDER BY time DESC, _id DESC LIMIT 1)",
            null
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else {
                val first = cursor.getLong(0).coerceAtMost(Long.MAX_VALUE - 1)
                val last = cursor.getLong(1)
                val end = historyExclusiveEnd(last)
                HistoryRangeState(first, end)
            }
        }
    }

    fun deleteHistory(range: HistoryRangeState?) {
        openDBs()
        val database = checkNotNull(wdb) { "Log database is unavailable" }
        val deleted = database.transaction {
            val where = range?.let { "time >= ? AND time ${it.endOperator} ?" } ?: "1"
            val args = range?.let { arrayOf(it.start.toString(), it.end.toString()) }
            val times = affectedTimes(where, args)
            delete(LOG_TABLE_NAME, where, args).also { deleted ->
                if (deleted > 0) recordHistoryChange(
                    times?.first ?: Long.MIN_VALUE, times?.last ?: Long.MAX_VALUE
                )
            }
        }
        if (deleted > 0) historyChanged()
    }

    fun historyDuration(entry: HistoryRecord): Long? {
        val record = entry.record
        val status = decodeStatus(record.status)
        if (status[2] == STATUS_SAMPLE) return null
        val previous = when {
            status[0] == 5 || (status[0] == 0 && status[2] == 0) -> 2
            status[0] == 2 && status[2] == 0 -> 0
            else -> return null
        }
        openDBs()
        return checkNotNull(rdb).rawQuery(
            "SELECT time FROM logs WHERE (time < ? OR (time = ? AND _id < ?)) AND status >= 0 AND status < 100 AND status % 10 = ? ORDER BY time DESC, _id DESC LIMIT 1",
            arrayOf(
                record.time.toString(),
                record.time.toString(),
                entry.id.toString(),
                previous.toString()
            )
        ).use { if (it.moveToFirst()) record.time - it.getLong(0) else null }
    }


    fun getLogRecordsInRange(
        afterExclusive: Long? = null, throughInclusive: Long? = null
    ): List<LogRecord> {
        openDBs()

        val whereParts = mutableListOf<String>()
        val whereArgs = mutableListOf<String>()
        afterExclusive?.let {
            whereParts += "$KEY_TIME > ?"
            whereArgs += it.toString()
        }
        throughInclusive?.let {
            whereParts += "$KEY_TIME <= ?"
            whereArgs += it.toString()
        }
        val where = if (whereParts.isEmpty()) "" else " WHERE ${whereParts.joinToString(" AND ")}"
        val cursor = rdb?.rawQuery(
            "SELECT $KEY_STATUS_CODE, $KEY_CHARGE, $KEY_TIME, $KEY_TEMPERATURE, $KEY_VOLTAGE, $KEY_CURRENT, $KEY_REMAINING_CHARGE FROM $LOG_TABLE_NAME$where ORDER BY $KEY_TIME ASC",
            whereArgs.takeIf { it.isNotEmpty() }?.toTypedArray()
        ) ?: return emptyList()
        cursor.use {
            val statusColumn = it.getColumnIndexOrThrow(KEY_STATUS_CODE)
            val chargeColumn = it.getColumnIndexOrThrow(KEY_CHARGE)
            val timeColumn = it.getColumnIndexOrThrow(KEY_TIME)
            val temperatureColumn = it.getColumnIndexOrThrow(KEY_TEMPERATURE)
            val voltageColumn = it.getColumnIndexOrThrow(KEY_VOLTAGE)
            val currentColumn = it.getColumnIndexOrThrow(KEY_CURRENT)
            val remainingChargeColumn = it.getColumnIndexOrThrow(KEY_REMAINING_CHARGE)
            return buildList {
                while (it.moveToNext()) {
                    add(
                        LogRecord(
                            status = it.getInt(statusColumn),
                            charge = it.getNullableInt(chargeColumn),
                            time = it.getLong(timeColumn),
                            temperature = it.getNullableInt(temperatureColumn),
                            voltage = it.getNullableInt(voltageColumn)
                                ?.takeIf(BatteryVoltageValidator::isValidBroadcastMillivolts),
                            currentMicroAmps = it.getNullableLong(currentColumn),
                            remainingChargeMicroampHours = it.getNullableLong(remainingChargeColumn)
                        )
                    )
                }
            }
        }
    }

    fun replaceAllLogs(records: List<LogRecord>) = writeLogs(records, replaceExisting = true)

    fun addLogs(records: List<LogRecord>) = writeLogs(records, replaceExisting = false)

    private fun writeLogs(records: List<LogRecord>, replaceExisting: Boolean) {
        openDBs()
        val database = checkNotNull(wdb) { "Log database is unavailable" }
        val changed = database.transaction {
            val previousTimes = if (replaceExisting) affectedTimes() else null
            val deleted = if (replaceExisting) database.delete(LOG_TABLE_NAME, "1", null) else 0
            for ((status, charge, time, temperature, voltage, currentMicroAmps, remainingChargeMicroampHours) in records) {
                val values = ContentValues().apply {
                    put(KEY_STATUS_CODE, status)
                    putNullable(KEY_CHARGE, charge)
                    put(KEY_TIME, time)
                    putNullable(KEY_TEMPERATURE, temperature)
                    putNullable(KEY_VOLTAGE, voltage)
                    putNullable(KEY_CURRENT, currentMicroAmps)
                    putNullable(KEY_REMAINING_CHARGE, remainingChargeMicroampHours)
                }
                check(database.insertOrThrow(LOG_TABLE_NAME, null, values) >= 0) {
                    "Could not restore log entry"
                }
            }
            val changed = deleted > 0 || records.isNotEmpty()
            if (changed) {
                val first =
                    listOfNotNull(previousTimes?.first, records.minOfOrNull { it.time }).minOrNull()
                        ?: Long.MIN_VALUE
                val last =
                    listOfNotNull(previousTimes?.last, records.maxOfOrNull { it.time }).maxOrNull()
                        ?: Long.MAX_VALUE
                recordHistoryChange(first, last)
            }
            changed
        }
        if (changed) historyChanged()
    }

    private fun Cursor.getNullableInt(columnIndex: Int): Int? =
        if (isNull(columnIndex)) null else getInt(columnIndex)

    private fun ContentValues.putNullable(key: String, value: Int?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun Cursor.getNullableLong(columnIndex: Int): Long? =
        if (isNull(columnIndex)) null else getLong(columnIndex)

    private fun ContentValues.putNullable(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    fun logObservation(
        observation: LogRecord,
        elapsed: Long,
        sampler: BatteryHistorySampler,
        loggingEnabled: Boolean,
        newStatus: Boolean = false
    ): LogResult? {
        if (!loggingEnabled) return null
        val result = writeWithRetry("Logging battery observation") { _, database ->
            database.transaction {
                val previous = rawQuery(
                    "SELECT status, charge FROM logs ORDER BY _id DESC LIMIT 1", null
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else LogRecord(
                        cursor.getInt(0), cursor.getNullableInt(1), 0, null, null
                    )
                }
                val age = sampler.statusAge(previous, observation, elapsed, newStatus)
                if (age == null) LogResult.Duplicate else {
                    val decoded = decodeStatus(observation.status)
                    val values = ContentValues().apply {
                        put(KEY_STATUS_CODE, encodeStatus(decoded[0], decoded[1], age))
                        putNullable(KEY_CHARGE, observation.charge)
                        put(KEY_TIME, observation.time)
                        putNullable(KEY_TEMPERATURE, observation.temperature)
                        putNullable(KEY_VOLTAGE, observation.voltage)
                        putNullable(KEY_CURRENT, observation.currentMicroAmps)
                        putNullable(KEY_REMAINING_CHARGE, observation.remainingChargeMicroampHours)
                    }
                    insertOrThrow(LOG_TABLE_NAME, null, values)
                    recordHistoryChange(observation.time, observation.time)
                    LogResult.Inserted
                }
            }
        }
        if (result == LogResult.Inserted) {
            sampler.inserted(elapsed)
            historyChanged()
        }
        return result
    }

    fun logBoot(): LogResult {
        val result = writeWithRetry("Logging boot completion") { _, writableDatabase ->
            val values = ContentValues().apply {
                put(KEY_STATUS_CODE, STATUS_BOOT_COMPLETED)
                putNull(KEY_CHARGE)
                put(KEY_TIME, System.currentTimeMillis())
                putNull(KEY_TEMPERATURE)
                putNull(KEY_VOLTAGE)
                putNull(KEY_CURRENT)
                putNull(KEY_REMAINING_CHARGE)
            }
            writableDatabase.transaction {
                insertOrThrow(LOG_TABLE_NAME, null, values)
                val time = values.getAsLong(KEY_TIME)
                recordHistoryChange(time, time)
            }
            LogResult.Inserted
        }
        if (result == LogResult.Inserted) historyChanged()
        return result
    }

    fun checkHealth(): LogResult =
        writeWithRetry("Checking log database health") { readableDatabase, writableDatabase ->
            readableDatabase.rawQuery("SELECT 1 FROM $LOG_TABLE_NAME LIMIT 1", null).use {
                it.moveToFirst()
            }

            writableDatabase.beginTransaction()
            try {
                writableDatabase.execSQL(
                    "CREATE TABLE IF NOT EXISTS diagnostics_write_probe (value INTEGER NOT NULL)"
                )
                writableDatabase.execSQL(
                    "INSERT INTO diagnostics_write_probe (value) VALUES (1)"
                )
            } finally {
                if (writableDatabase.inTransaction()) writableDatabase.endTransaction()
            }
            LogResult.Inserted
        }

    fun prune(maxHours: Int) {
        val currentTM = System.currentTimeMillis()
        val oldestLog = currentTM - (maxHours.toLong() * 60 * 60 * 1000)

        openDBs()

        try {
            val deleted = checkNotNull(wdb).transaction {
                val where = "$KEY_TIME < ?"
                val args = arrayOf(oldestLog.toString())
                val times = affectedTimes(where, args)
                delete(LOG_TABLE_NAME, where, args).also { deleted ->
                    if (deleted > 0) recordHistoryChange(
                        times?.first ?: Long.MIN_VALUE, times?.last ?: Long.MAX_VALUE
                    )
                }
            }
            if (deleted > 0) historyChanged()
        } catch (_: Exception) {
        }
    }

    fun hasLogsOlderThan(maxHours: Int): Boolean {
        val oldestAllowedLog = System.currentTimeMillis() - (maxHours.toLong() * 60 * 60 * 1000)
        openDBs()
        return try {
            rdb?.rawQuery(
                "SELECT 1 FROM $LOG_TABLE_NAME WHERE $KEY_TIME < ? LIMIT 1",
                arrayOf(oldestAllowedLog.toString())
            )?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private class SQLOpenHelper(context: Context?) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            if (!db.isReadOnly) db.execSQL("CREATE INDEX IF NOT EXISTS logs_time_id ON logs(time, _id)")
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $LOG_TABLE_NAME ($KEY_ID INTEGER PRIMARY KEY,$KEY_STATUS_CODE INTEGER,$KEY_CHARGE INTEGER,$KEY_TIME INTEGER,$KEY_TEMPERATURE INTEGER,$KEY_VOLTAGE INTEGER,\"$KEY_CURRENT\" INTEGER,$KEY_REMAINING_CHARGE INTEGER);"
            )
            createHistoryChanges(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 3) {
                db.execSQL("DROP TABLE IF EXISTS $LOG_TABLE_NAME")
                onCreate(db)
                return
            }
            if (oldVersion < 4) {
                db.execSQL("ALTER TABLE $LOG_TABLE_NAME ADD COLUMN $KEY_TEMPERATURE INTEGER;")
                db.execSQL("ALTER TABLE $LOG_TABLE_NAME ADD COLUMN $KEY_VOLTAGE INTEGER;")
            }
            if (oldVersion < 5) {
                db.execSQL("ALTER TABLE $LOG_TABLE_NAME ADD COLUMN \"$KEY_CURRENT\" INTEGER;")
            }
            if (oldVersion < 6) {
                db.execSQL("ALTER TABLE $LOG_TABLE_NAME ADD COLUMN $KEY_REMAINING_CHARGE INTEGER;")
            }
            if (oldVersion < 7) createHistoryChanges(db)
        }

        private fun createHistoryChanges(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $HISTORY_CHANGES_TABLE (revision INTEGER PRIMARY KEY, first_time INTEGER NOT NULL, last_time INTEGER NOT NULL)"
            )
        }

    }
}
