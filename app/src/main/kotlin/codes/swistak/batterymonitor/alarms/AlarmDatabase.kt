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
package codes.swistak.batterymonitor.alarms

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Settings

internal class AlarmDatabase(context: Context?) {
    companion object {
        private const val DATABASE_NAME = "alarms.db"
        private const val DATABASE_VERSION = 5
        private const val ALARM_TABLE_NAME = "alarms"

        const val KEY_ID: String = "_id"
        const val KEY_ENABLED: String = "enabled"
        const val KEY_TYPE: String = "type"
        const val KEY_THRESHOLD: String = "threshold"
        const val KEY_RINGTONE: String = "ringtone"

        const val KEY_VIBRATE: String = "vibrate"
        const val KEY_LIGHTS: String = "lights"

        val SUPPORTED_TYPES: Set<String> = ALARM_TYPES.mapTo(linkedSetOf()) { it.type }
    }

    private val mSQLOpenHelper: SQLOpenHelper = SQLOpenHelper(context)
    private var rdb: SQLiteDatabase? = null
    private var wdb: SQLiteDatabase? = null

    init {
        openDBs()
    }

    private fun openDBs() {
        if (rdb == null || !rdb!!.isOpen) {
            rdb = try {
                mSQLOpenHelper.readableDatabase
            } catch (_: SQLiteException) {
                null
            }
        }

        if (wdb == null || !wdb!!.isOpen) {
            try {
                wdb = mSQLOpenHelper.writableDatabase
            } catch (_: SQLiteException) {
                rdb = null
            }
        }
    }

    fun close() {
        if (rdb != null) rdb!!.close()
        if (wdb != null) wdb!!.close()
    }

    fun getAllAlarms(reversed: Boolean): Cursor? {
        var order = "DESC"
        if (reversed) order = "ASC"

        openDBs()

        return try {
            rdb!!.rawQuery(
                "SELECT * FROM $ALARM_TABLE_NAME ORDER BY $KEY_ID $order", null
            )
        } catch (_: Exception) {
            null
        }
    }

    fun getAllAlarmRecords(): List<AlarmRecord> {
        val cursor = getAllAlarms(true) ?: error("Could not read alarms")
        cursor.use {
            val records = mutableListOf<AlarmRecord>()
            while (it.moveToNext()) {
                val type = it.getString(it.getColumnIndexOrThrow(KEY_TYPE)) ?: continue
                records += AlarmRecord(
                    enabled = it.getInt(it.getColumnIndexOrThrow(KEY_ENABLED)) == 1,
                    type = type,
                    threshold = it.getString(it.getColumnIndexOrThrow(KEY_THRESHOLD)) ?: ""
                )
            }
            return records
        }
    }

    fun getAlarmRules(): List<AlarmRule> {
        openDBs()
        return readAlarmRules(checkNotNull(rdb) { "Could not read alarms" })
    }

    private fun readAlarmRules(database: SQLiteDatabase): List<AlarmRule> =
        database.rawQuery("SELECT * FROM $ALARM_TABLE_NAME ORDER BY $KEY_ID ASC", null).use {
            buildList {
                while (it.moveToNext()) add(
                    AlarmRule(
                        id = it.getInt(it.getColumnIndexOrThrow(KEY_ID)),
                        enabled = it.getInt(it.getColumnIndexOrThrow(KEY_ENABLED)) == 1,
                        type = it.getString(it.getColumnIndexOrThrow(KEY_TYPE)) ?: "",
                        threshold = it.getString(it.getColumnIndexOrThrow(KEY_THRESHOLD)) ?: ""
                    )
                )
            }
        }

    fun saveThreshold(type: String, threshold: String, expected: List<AlarmRule>): Int =
        transaction { database, current ->
            val definition = ALARM_TYPES.single { it.type == type }
            require(definition.bounds != null && threshold.toIntOrNull() in definition.bounds)
            if (expected.isNotEmpty()) {
                require(expected.all { it.type == type && validStoredAlarm(it) })
                requireAlarmMembers(current, expected)
            }
            thresholdCollision(current, type, threshold, expected.map(AlarmRule::id))?.let {
                throw AlarmCollisionException(it)
            }
            if (expected.isEmpty()) {
                addAlarm(true, type, threshold).also { check(it >= 0) }
            } else {
                updateMembers(
                    database, expected, ContentValues().apply { put(KEY_THRESHOLD, threshold) })
                expected.minOf(AlarmRule::id)
            }
        }

    fun setLogicalEnabled(type: String, expected: List<AlarmRule>, enabled: Boolean) {
        transaction { database, current ->
            if (expected.isEmpty()) {
                require(ALARM_TYPES.any { it.type == type && it.bounds == null } && enabled)
                if (current.any { it.type == type }) throw AlarmConflictException()
                check(addAlarm(true, type, "") >= 0)
            } else {
                require(expected.all { it.type == type })
                requireAlarmMembers(current, expected)
                updateMembers(
                    database,
                    expected,
                    ContentValues().apply { put(KEY_ENABLED, if (enabled) 1 else 0) })
            }
        }
    }

    fun deleteLogicalAlarm(expected: List<AlarmRule>) {
        transaction { database, current ->
            requireAlarmMembers(current, expected)
            check(
                database.delete(
                    ALARM_TABLE_NAME, memberSelection(expected), null
                ) == expected.size
            )
        }
    }

    private fun memberSelection(members: List<AlarmRule>): String =
        "$KEY_ID IN (${members.joinToString(",") { it.id.toString() }})"

    private fun updateMembers(
        database: SQLiteDatabase, members: List<AlarmRule>, values: ContentValues
    ) {
        check(
            database.update(
                ALARM_TABLE_NAME, values, memberSelection(members), null
            ) == members.size
        )
    }

    private fun <T> transaction(operation: (SQLiteDatabase, List<AlarmRule>) -> T): T {
        openDBs()
        val database = checkNotNull(wdb) { "Could not write alarms" }
        database.beginTransaction()
        try {
            val result = operation(database, readAlarmRules(database))
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }

    fun replaceAllAlarms(records: List<AlarmRecord>): Boolean {
        openDBs()
        val database = wdb ?: return false

        return try {
            database.beginTransaction()
            database.delete(ALARM_TABLE_NAME, null, null)
            for ((enabled, type, threshold) in records) {
                val values = ContentValues().apply {
                    put(KEY_ENABLED, if (enabled) 1 else 0)
                    put(KEY_TYPE, type)
                    put(KEY_THRESHOLD, threshold)
                    put(KEY_RINGTONE, Settings.System.DEFAULT_NOTIFICATION_URI.toString())
                    put(KEY_VIBRATE, 0)
                    put(KEY_LIGHTS, 1)
                }
                check(database.insert(ALARM_TABLE_NAME, null, values) >= 0) {
                    "Could not restore alarm"
                }
            }
            database.setTransactionSuccessful()
            true
        } catch (_: Exception) {
            false
        } finally {
            if (database.inTransaction()) database.endTransaction()
        }
    }

    fun anyActiveAlarms(): Boolean {
        openDBs()

        try {
            val c = rdb!!.rawQuery(
                "SELECT * FROM $ALARM_TABLE_NAME WHERE ENABLED=1 LIMIT 1", null
            )
            val b = (c.count > 0)
            c.close()
            return b
        } catch (_: Exception) {
            return false
        }
    }

    fun activeAlarmFull(): Cursor? {
        openDBs()

        try {
            val c = rdb!!.rawQuery(
                "SELECT * FROM $ALARM_TABLE_NAME WHERE $KEY_TYPE='fully_charged' AND ENABLED=1 LIMIT 1",
                null
            )

            if (c.count == 0) {
                c.close()
                return null
            }

            c.moveToFirst()
            return c
        } catch (_: Exception) {
            return null
        }
    }

    fun activeAlarmChargingLimitMet(): Cursor? {
        openDBs()

        try {
            val c = rdb!!.rawQuery(
                "SELECT * FROM $ALARM_TABLE_NAME WHERE $KEY_TYPE='charging_limit_met' AND ENABLED=1 LIMIT 1",
                null
            )

            if (c.count == 0) {
                c.close()
                return null
            }

            c.moveToFirst()
            return c
        } catch (_: Exception) {
            return null
        }
    }

    fun activeAlarmDischargingLimitMet(): Cursor? {
        openDBs()

        try {
            val c = rdb!!.rawQuery(
                "SELECT * FROM $ALARM_TABLE_NAME WHERE $KEY_TYPE='discharging_limit_met' AND ENABLED=1 LIMIT 1",
                null
            )

            if (c.count == 0) {
                c.close()
                return null
            }

            c.moveToFirst()
            return c
        } catch (_: Exception) {
            return null
        }
    }

    // Custom threshold crossings are evaluated by ThresholdAlarmEvaluator.

    fun activeAlarmFailure(): Cursor? {
        openDBs()

        try {
            val c = rdb!!.rawQuery(
                "SELECT * FROM $ALARM_TABLE_NAME WHERE $KEY_TYPE='health_failure' AND ENABLED=1 LIMIT 1",
                null
            )

            if (c.count == 0) {
                c.close()
                return null
            }

            c.moveToFirst()
            return c
        } catch (_: Exception) {
            return null
        }
    }

    @JvmOverloads
    fun addAlarm(
        enabled: Boolean = true,
        type: String? = "fully_charged",
        threshold: String? = "",
        ringtone: String? = Settings.System.DEFAULT_NOTIFICATION_URI.toString(),
        vibrate: Boolean = false,
        lights: Boolean = true
    ): Int {
        openDBs()

        try {
            val cv = ContentValues()
            cv.put(KEY_ENABLED, if (enabled) 1 else 0)
            cv.put(KEY_TYPE, type)
            cv.put(KEY_THRESHOLD, threshold)
            cv.put(KEY_RINGTONE, ringtone)
            cv.put(KEY_VIBRATE, if (vibrate) 1 else 0)
            cv.put(KEY_LIGHTS, if (lights) 1 else 0)
            return wdb!!.insert(ALARM_TABLE_NAME, null, cv).toInt()
        } catch (_: Exception) {
            return -1
        }
    }

    private class SQLOpenHelper(context: Context?) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        // shortcut: v5 STRING columns retain numeric affinity, change only with a preserving migration.
        @SuppressLint("SQLiteString")
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $ALARM_TABLE_NAME ($KEY_ID INTEGER PRIMARY KEY,$KEY_ENABLED INTEGER,$KEY_TYPE STRING,$KEY_THRESHOLD STRING,$KEY_RINGTONE STRING,$KEY_VIBRATE INTEGER,$KEY_LIGHTS INTEGER);"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $ALARM_TABLE_NAME")
            onCreate(db)
        }
    }
}
