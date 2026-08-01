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

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import codes.swistak.batterymonitor.monitoring.BatteryInfo

internal class LogDatabase(context: Context?) {
    companion object {
        private const val DATABASE_NAME = "logs.db"
        private const val DATABASE_VERSION = 4
        private const val LOG_TABLE_NAME = "logs"

        private const val KEY_ID = "_id"
        const val KEY_STATUS_CODE: String = "status"
        const val KEY_CHARGE: String = "charge"
        const val KEY_TIME: String = "time"
        const val KEY_TEMPERATURE: String = "temperature"
        const val KEY_VOLTAGE: String = "voltage"

        val STATUS_BOOT_COMPLETED: Int = -1

        // Values for status_age
        const val STATUS_NEW: Int = 0
        const val STATUS_OLD: Int = 1

        /* My cursor adapter was getting a bit complicated since it could only see one datum at a time, and
       how I want to present the data depends on several interrelated factors.  Storing all three of
       these items together simplifies things. */
        private fun encodeStatus(status: Int, plugged: Int, statusAge: Int): Int {
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
            }
        }

        if (wdb == null || !wdb!!.isOpen) {
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

    fun getAllLogs(reversed: Boolean): Cursor? {
        var order = "DESC"
        if (reversed) order = "ASC"

        openDBs()

        return try {
            rdb!!.rawQuery(
                "SELECT * FROM $LOG_TABLE_NAME ORDER BY $KEY_TIME $order", null
            )
        } catch (e: Exception) {
            null
        }
    }

    fun logStatus(info: BatteryInfo, time: Long, statusAge: Int) {
        var duplicate = false

        openDBs()

        try {
            val lastLog = rdb!!.rawQuery(
                "SELECT * FROM $LOG_TABLE_NAME ORDER BY $KEY_TIME DESC LIMIT 1", null
            )

            if (lastLog.moveToFirst()) {
                val statusCode = lastLog.getInt(lastLog.getColumnIndexOrThrow(KEY_STATUS_CODE))
                val lastCharge = lastLog.getInt(lastLog.getColumnIndexOrThrow(KEY_CHARGE))
                val a: IntArray = decodeStatus(statusCode)
                val lastStatus = a[0]
                val lastPlugged = a[1]

                if (info.percent == lastCharge && info.status == lastStatus && info.plugged == lastPlugged) duplicate =
                    true
            }

            if (!duplicate) wdb!!.execSQL(
                ("INSERT INTO $LOG_TABLE_NAME VALUES (NULL, ${
                    encodeStatus(
                        info.status, info.plugged, statusAge
                    )
                } ,${info.percent} ,${time} ,${info.temperature} ,${info.voltage})")
            )

            lastLog.close()
        } catch (e: Exception) {
        }
    }

    fun logBoot() {
        openDBs()

        try {
            wdb!!.execSQL(
                "INSERT INTO $LOG_TABLE_NAME VALUES (NULL, $STATUS_BOOT_COMPLETED,NULL,${System.currentTimeMillis()},NULL,NULL)"
            )
        } catch (e: Exception) {
        }
    }

    fun prune(maxHours: Int) {
        val currentTM = System.currentTimeMillis()
        val oldestLog = currentTM - (maxHours.toLong() * 60 * 60 * 1000)

        openDBs()

        try {
            wdb!!.execSQL("DELETE FROM $LOG_TABLE_NAME WHERE $KEY_TIME < $oldestLog")
        } catch (e: Exception) {
        }
    }

    fun clearAllLogs() {
        mSQLOpenHelper.reset()
    }

    private class SQLOpenHelper(context: Context?) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $LOG_TABLE_NAME ($KEY_ID INTEGER PRIMARY KEY,$KEY_STATUS_CODE INTEGER,$KEY_CHARGE INTEGER,$KEY_TIME INTEGER,$KEY_TEMPERATURE INTEGER,$KEY_VOLTAGE INTEGER);"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion == 3 && newVersion == 4) {
                db.execSQL("ALTER TABLE $LOG_TABLE_NAME ADD COLUMN $KEY_TEMPERATURE INTEGER;")
                db.execSQL("ALTER TABLE $LOG_TABLE_NAME ADD COLUMN $KEY_VOLTAGE INTEGER;")
            } else {
                db.execSQL("DROP TABLE IF EXISTS $LOG_TABLE_NAME")
                onCreate(db)
            }
        }

        fun reset() {
            val db = writableDatabase
            db.execSQL("DROP TABLE IF EXISTS $LOG_TABLE_NAME")
            onCreate(db)
        }
    }
}
