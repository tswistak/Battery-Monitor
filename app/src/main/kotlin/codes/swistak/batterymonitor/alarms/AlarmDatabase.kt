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
    }

    private val mSQLOpenHelper: SQLOpenHelper = SQLOpenHelper(context)
    private var rdb: SQLiteDatabase? = null
    private var wdb: SQLiteDatabase? = null

    init {
        openDBs()
    }

    private fun openDBs() {
        if (rdb == null || !rdb!!.isOpen()) {
            rdb = try {
                mSQLOpenHelper.readableDatabase
            } catch (e: SQLiteException) {
                null
            }
        }

        if (wdb == null || !wdb!!.isOpen()) {
            try {
                wdb = mSQLOpenHelper.writableDatabase
            } catch (e: SQLiteException) {
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

        try {
            return rdb!!.rawQuery(
                "SELECT * FROM $ALARM_TABLE_NAME ORDER BY $KEY_ID $order", null
            )
        } catch (e: Exception) {
            return null
        }
    }

    fun getAlarm(id: Int): Cursor? {
        openDBs()

        try {
            val c = rdb!!.rawQuery(
                "SELECT * FROM $ALARM_TABLE_NAME WHERE $KEY_ID=$id LIMIT 1",
                null
            )
            c.moveToFirst()
            return c
        } catch (e: Exception) {
            return null
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            return null
        }
    }

    fun activeAlarmChargeDrops(current: Int, previous: Int): Cursor? {
        openDBs()

        try {
            val c = rdb!!.rawQuery(
                "SELECT * FROM $ALARM_TABLE_NAME WHERE $KEY_TYPE='charge_drops' AND ENABLED=1 AND $KEY_THRESHOLD>$current AND $KEY_THRESHOLD<=$previous LIMIT 1",
                null
            )

            if (c.count == 0) {
                c.close()
                return null
            }

            c.moveToFirst()
            return c
        } catch (e: Exception) {
            return null
        }
    }

    fun activeAlarmChargeRises(current: Int, previous: Int): Cursor? {
        openDBs()

        try {
            val c = rdb!!.rawQuery(
                "SELECT * FROM $ALARM_TABLE_NAME WHERE $KEY_TYPE='charge_rises' AND ENABLED=1 AND $KEY_THRESHOLD<$current AND $KEY_THRESHOLD>=$previous LIMIT 1",
                null
            )

            if (c.count == 0) {
                c.close()
                return null
            }

            c.moveToFirst()
            return c
        } catch (e: Exception) {
            return null
        }
    }

    fun activeAlarmTempRises(current: Int, previous: Int): Cursor? {
        openDBs()

        try {
            val c = rdb!!.rawQuery(
                "SELECT * FROM $ALARM_TABLE_NAME WHERE $KEY_TYPE='temp_rises' AND ENABLED=1 AND $KEY_THRESHOLD<$current AND $KEY_THRESHOLD>=$previous LIMIT 1",
                null
            )

            if (c.count == 0) {
                c.close()
                return null
            }

            c.moveToFirst()
            return c
        } catch (e: Exception) {
            return null
        }
    }

    fun activeAlarmTempDrops(current: Int, previous: Int): Cursor? {
        openDBs()

        try {
            val c = rdb!!.rawQuery(
                "SELECT * FROM $ALARM_TABLE_NAME WHERE $KEY_TYPE='temp_drops' AND ENABLED=1 AND $KEY_THRESHOLD>$current AND $KEY_THRESHOLD<=$previous LIMIT 1",
                null
            )

            if (c.count == 0) {
                c.close()
                return null
            }

            c.moveToFirst()
            return c
        } catch (e: Exception) {
            return null
        }
    }

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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            return -1
        }
    }

    fun setEnabled(id: Int, enabled: Boolean): Int {
        val cv = ContentValues()
        cv.put(KEY_ENABLED, if (enabled) 1 else 0)

        openDBs()

        return try {
            wdb!!.update(ALARM_TABLE_NAME, cv, "$KEY_ID=$id", null)
        } catch (e: Exception) {
            -1
        }
    }

    fun setType(id: Int, type: String?): Int {
        val cv = ContentValues()
        cv.put(KEY_TYPE, type)

        openDBs()

        return try {
            wdb!!.update(ALARM_TABLE_NAME, cv, "$KEY_ID=$id", null)
        } catch (e: Exception) {
            -1
        }
    }

    fun setThreshold(id: Int, threshold: String?): Int {
        val cv = ContentValues()
        cv.put(KEY_THRESHOLD, threshold)

        openDBs()

        return try {
            wdb!!.update(ALARM_TABLE_NAME, cv, "$KEY_ID=$id", null)
        } catch (e: Exception) {
            -1
        }
    }

    fun deleteAlarm(id: Int) {
        openDBs()

        try {
            wdb!!.delete(ALARM_TABLE_NAME, KEY_ID + "=" + id, null)
        } catch (e: Exception) {
        }
    }

    private class SQLOpenHelper(context: Context?) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
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
