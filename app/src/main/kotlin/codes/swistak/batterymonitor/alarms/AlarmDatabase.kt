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
        private const val DATABASE_VERSION = 6
        private const val ALARM_TABLE_NAME = "alarms"
        private const val SETTINGS_TABLE_NAME = "alarm_sound_settings"
        private const val KEY_ALARM_ID = "alarm_id"
        private const val KEY_PLAYBACK = "playback"
        private const val KEY_VOLUME_CATEGORY = "volume_category"
        private const val KEY_CHANNEL_ID = "channel_id"
        private const val SHARED_ALARM_ID = 0

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

    fun getSnapshot(): AlarmSnapshot = withTransaction { database ->
        readSnapshot(database, readAlarmRules(database))
    }

    private fun readSnapshot(database: SQLiteDatabase, rules: List<AlarmRule>): AlarmSnapshot {
        val shared = mutableMapOf<String, AlarmConfiguration>()
        val custom = mutableMapOf<Int, AlarmConfiguration>()
        database.query(
            SETTINGS_TABLE_NAME,
            arrayOf(KEY_TYPE, KEY_ALARM_ID, KEY_PLAYBACK, KEY_VOLUME_CATEGORY, KEY_CHANNEL_ID),
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val alarmId = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_ALARM_ID))
                val configuration = readConfiguration(cursor)
                if (alarmId == SHARED_ALARM_ID) {
                    shared[cursor.getString(cursor.getColumnIndexOrThrow(KEY_TYPE))] = configuration
                } else {
                    custom[alarmId] = configuration
                }
            }
        }
        return AlarmSnapshot(rules, shared, custom)
    }

    fun saveSharedSettings(
        type: String, expected: AlarmConfiguration?, configuration: AlarmConfiguration
    ) = withTransaction { database ->
        require(type in SUPPORTED_TYPES)
        if (readSettings(
                database, type, SHARED_ALARM_ID
            ) != expected
        ) throw AlarmConflictException()
        writeSettings(
            database,
            type,
            SHARED_ALARM_ID,
            configuration.takeUnless { it == AlarmConfiguration() })
    }

    fun saveCustomSettings(
        alarm: LogicalAlarm, expected: AlarmConfiguration?, configuration: AlarmConfiguration?
    ) = transaction { database, current ->
        require(alarm.thresholdKey != null)
        requireAlarmMembers(current, alarm.members)
        if (readSettings(database, alarm.type, alarm.id) != expected) throw AlarmConflictException()
        writeSettings(database, alarm.type, alarm.id, configuration)
    }

    private fun readSettings(
        database: SQLiteDatabase, type: String, alarmId: Int
    ): AlarmConfiguration? = database.query(
        SETTINGS_TABLE_NAME,
        arrayOf(KEY_PLAYBACK, KEY_VOLUME_CATEGORY, KEY_CHANNEL_ID),
        "$KEY_TYPE = ? AND $KEY_ALARM_ID = ?",
        arrayOf(type, alarmId.toString()),
        null,
        null,
        null
    ).use { cursor ->
        if (cursor.moveToFirst()) readConfiguration(cursor) else null
    }

    private fun readConfiguration(cursor: Cursor): AlarmConfiguration {
        val playback = cursor.getString(cursor.getColumnIndexOrThrow(KEY_PLAYBACK))
        val volumeCategory = cursor.getString(cursor.getColumnIndexOrThrow(KEY_VOLUME_CATEGORY))
        return AlarmConfiguration(
            AlarmSoundSettings(AlarmPlayback.entries.firstOrNull { it.name == playback }
                ?: AlarmPlayback.ONCE,
                AlarmVolumeCategory.entries.firstOrNull { it.name == volumeCategory }
                    ?: AlarmVolumeCategory.NOTIFICATIONS),
            cursor.getString(cursor.getColumnIndexOrThrow(KEY_CHANNEL_ID)))
    }

    private fun writeSettings(
        database: SQLiteDatabase, type: String, alarmId: Int, configuration: AlarmConfiguration?
    ) {
        if (configuration == null) {
            database.delete(
                SETTINGS_TABLE_NAME,
                "$KEY_TYPE = ? AND $KEY_ALARM_ID = ?",
                arrayOf(type, alarmId.toString())
            )
            return
        }
        val values = ContentValues().apply {
            put(KEY_TYPE, type)
            put(KEY_ALARM_ID, alarmId)
            put(KEY_PLAYBACK, configuration.sound.playback.name)
            put(KEY_VOLUME_CATEGORY, configuration.sound.volumeCategory.name)
            put(KEY_CHANNEL_ID, configuration.channelId)
        }
        database.replaceOrThrow(SETTINGS_TABLE_NAME, null, values)
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
            val alarm = LogicalAlarm(expected)
            if (alarm.thresholdKey != null) writeSettings(database, alarm.type, alarm.id, null)
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

    private fun <T> transaction(operation: (SQLiteDatabase, List<AlarmRule>) -> T): T =
        withTransaction { database -> operation(database, readAlarmRules(database)) }

    private fun <T> withTransaction(operation: (SQLiteDatabase) -> T): T {
        openDBs()
        val database = checkNotNull(wdb) { "Could not write alarms" }
        database.beginTransactionNonExclusive()
        try {
            val result = operation(database)
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }

    fun replaceAllAlarms(records: List<AlarmRecord>): Boolean =
        replaceAllAlarms(records, emptyMap(), emptyMap())

    fun replaceAllAlarms(
        records: List<AlarmRecord>,
        shared: Map<String, AlarmSoundSettings> = emptyMap(),
        custom: Map<AlarmThresholdKey, AlarmSoundSettings> = emptyMap(),
        channelIds: AlarmRestoreChannelIds = AlarmRestoreChannelIds(),
        expected: AlarmSnapshot? = null
    ): Boolean = try {
        transaction { database, rules ->
            val previous = readSnapshot(database, rules)
            if (expected != null && previous != expected) throw AlarmConflictException()
            require(shared.keys.all { it in SUPPORTED_TYPES })
            database.delete(SETTINGS_TABLE_NAME, null, null)
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
                database.insertOrThrow(ALARM_TABLE_NAME, null, values)
            }

            for (type in SUPPORTED_TYPES) {
                val settings = shared[type] ?: AlarmSoundSettings()
                val retained = previous.shared[type]?.takeIf {
                    it.sound.volumeCategory == settings.volumeCategory
                }?.channelId
                val configuration =
                    AlarmConfiguration(settings, channelIds.shared[type] ?: retained)
                if (configuration != AlarmConfiguration()) {
                    writeSettings(database, type, SHARED_ALARM_ID, configuration)
                }
            }

            if (custom.isNotEmpty()) {
                val previousIds = logicalThresholdIds(rules)
                val currentIds = logicalThresholdIds(readAlarmRules(database))
                for ((key, settings) in custom) {
                    val id = requireNotNull(currentIds[key])
                    val retained = previousIds[key]?.let(previous.custom::get)?.takeIf {
                        it.sound.volumeCategory == settings.volumeCategory
                    }?.channelId
                    writeSettings(
                        database,
                        key.type,
                        id,
                        AlarmConfiguration(settings, channelIds.custom[key] ?: retained)
                    )
                }
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun logicalThresholdIds(rules: List<AlarmRule>): Map<AlarmThresholdKey, Int> =
        projectAlarms(rules).types.flatMap(AlarmTypeRows::alarms).mapNotNull { alarm ->
            alarm.thresholdKey?.let { it to alarm.id }
        }.toMap()

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
        override fun onCreate(db: SQLiteDatabase) {
            createAlarmTable(db)
            db.execSQL(
                """CREATE TABLE $SETTINGS_TABLE_NAME (
                    $KEY_TYPE TEXT NOT NULL,
                    $KEY_ALARM_ID INTEGER NOT NULL,
                    $KEY_PLAYBACK TEXT NOT NULL,
                    $KEY_VOLUME_CATEGORY TEXT NOT NULL,
                    $KEY_CHANNEL_ID TEXT,
                    PRIMARY KEY ($KEY_TYPE, $KEY_ALARM_ID)
                )""".trimIndent()
            )
        }

        // Preserve numeric affinity for legacy thresholds and never reuse an alarm ID.
        @SuppressLint("SQLiteString")
        private fun createAlarmTable(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE $ALARM_TABLE_NAME (
                    $KEY_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $KEY_ENABLED INTEGER,
                    $KEY_TYPE STRING,
                    $KEY_THRESHOLD STRING,
                    $KEY_RINGTONE STRING,
                    $KEY_VIBRATE INTEGER,
                    $KEY_LIGHTS INTEGER
                )""".trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            val legacyTable = "legacy_alarms"
            db.execSQL("ALTER TABLE $ALARM_TABLE_NAME RENAME TO $legacyTable")
            onCreate(db)
            val columns = listOf(
                KEY_ID, KEY_ENABLED, KEY_TYPE, KEY_THRESHOLD, KEY_RINGTONE, KEY_VIBRATE, KEY_LIGHTS
            )
            val legacyColumns = db.rawQuery("SELECT * FROM $legacyTable LIMIT 0", null).use {
                it.columnNames.toSet()
            }
            val legacyDefaults =
                mapOf(KEY_RINGTONE to "NULL", KEY_VIBRATE to "0", KEY_LIGHTS to "1")
            val source =
                columns.joinToString { if (it in legacyColumns) it else legacyDefaults.getValue(it) }
            db.execSQL(
                "INSERT INTO $ALARM_TABLE_NAME (${columns.joinToString()}) SELECT $source FROM $legacyTable"
            )
            db.execSQL("DROP TABLE $legacyTable")
        }
    }
}
