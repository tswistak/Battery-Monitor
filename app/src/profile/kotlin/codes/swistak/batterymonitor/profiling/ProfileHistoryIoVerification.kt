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
package codes.swistak.batterymonitor.profiling

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import codes.swistak.batterymonitor.devicebackup.DeviceDataBackup
import codes.swistak.batterymonitor.devicebackup.DeviceDataType
import codes.swistak.batterymonitor.devicebackup.LogImportMode
import codes.swistak.batterymonitor.logs.HistoryExportRequest
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogExportFormat
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.logs.exportHistory
import codes.swistak.batterymonitor.logs.historyQuery
import codes.swistak.batterymonitor.monitoring.MonitoringDesiredState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal object ProfileHistoryIoVerification {
    fun run(context: Context, intent: Intent): Bundle {
        check(context.packageName == "codes.swistak.batterymonitor.profile")
        check(intent.getBooleanExtra("synthetic", false))
        check(context.getSharedPreferences("profile_fixture", 0).getLong("anchorMillis", 0) > 0)
        val output = ownedExport(context, intent)
        check(!output.exists()) { "Refusing to overwrite an existing export" }
        val owner = File(
            context.cacheDir, "profile-history-io-${SystemClock.elapsedRealtimeNanos()}"
        ).apply { check(!exists() && mkdirs()) }
        val isolated = RestoreContext(context, owner)
        val report = JSONObject().put("complete", false).put("appPid", Process.myPid())
            .put("exportPath", output.path).put("ownedRestoreDirectory", owner.path).put(
                "scope",
                "real profile history exportHistory(JSON) and DeviceDataBackup.importFromJson(REPLACE) into empty isolated database; excludes SAF UI"
            )
        try {
            val through = System.currentTimeMillis()
            val source = LogDatabase(context)
            val records = try {
                source.readHistory(
                    historyQuery(
                        HistoryRangeState(
                            0, through + 1
                        )
                    )
                ) { rows -> rows.map { it.record }.toList() }
            } finally {
                source.close()
            }
            check(records.isNotEmpty())
            runBlocking {
                exportHistory(
                    context,
                    Uri.fromFile(output),
                    HistoryExportRequest(null, through, LogExportFormat.JSON)
                )
            }
            val json = output.readText()
            val decoded = DeviceDataBackup.readLogsFromJson(json)
            check(decoded == records) { "Export did not preserve all seven record fields and duplicate rows" }
            val restore = LogDatabase(isolated)
            try {
                check(restore.getAllLogRecords().isEmpty())
            } finally {
                restore.close()
            }
            DeviceDataBackup.importFromJson(
                isolated, json, setOf(DeviceDataType.LOGS), LogImportMode.REPLACE
            )
            val restored = LogDatabase(isolated)
            val result = try {
                restored.getAllLogRecords()
            } finally {
                restored.close()
            }
            check(result == decoded) { "Production restore changed exported record values or duplicates" }
            report.put("sourceCount", records.size).put("restoredCount", result.size)
                .put("sourceRecordsSha256", digest(records))
                .put("restoredRecordsSha256", digest(result))
                .put("exportFileSha256", sha256(output.readBytes()))
                .put("exportBytes", output.length()).put("throughMillis", through)
                .put("nullableCounts", JSONObject().apply {
                    put("charge", records.count { it.charge == null })
                    put("temperature", records.count { it.temperature == null })
                    put("voltage", records.count { it.voltage == null })
                    put("current", records.count { it.currentMicroAmps == null })
                    put(
                        "remainingCharge",
                        records.count { it.remainingChargeMicroampHours == null })
                }).put("complete", true)
        } catch (error: Exception) {
            report.put("failure", error.stackTraceToString())
        } finally {
            val cleaned = report.optBoolean("complete") && owner.deleteRecursively()
            report.put("ownedRestoreDirectoryRemoved", cleaned)
            val preferencesCleaned =
                isolated.preferenceNames.all { name -> context.deleteSharedPreferences(name) }
            report.put("ownedPreferencesRemoved", preferencesCleaned)
            if (!preferencesCleaned) report.put("complete", false)
                .put("failure", "Owned preference cleanup failed")
            if (report.optBoolean("complete") && !cleaned) report.put("complete", false)
                .put("failure", "Owned restore cleanup failed")
        }
        return Bundle().apply {
            putBoolean("complete", report.getBoolean("complete"))
            putString("reportJson", report.toString())
            putString("outputPath", output.path)
        }
    }

    fun restore(context: Context, intent: Intent): Bundle {
        check(context.packageName == "codes.swistak.batterymonitor.profile")
        check(intent.getBooleanExtra("synthetic", false))
        check(context.getSharedPreferences("profile_fixture", 0).getLong("anchorMillis", 0) > 0)
        check(!MonitoringDesiredState.read(context)) { "Owner must disable real monitoring before restore" }
        val input = ownedExport(context, intent)
        check(input.isFile && input.length() in 1L..16_777_216L)
        val expected = checkNotNull(intent.getStringExtra("expectedExportSha256"))
        require(expected.matches(Regex("[a-f0-9]{64}")))
        val bytes = input.readBytes()
        val actual = sha256(bytes)
        check(actual == expected) { "Owned export checksum mismatch" }
        val json = bytes.toString(Charsets.UTF_8)
        val records = DeviceDataBackup.readLogsFromJson(json)
        check(records.isNotEmpty())
        val before = LogDatabase(context)
        try {
            check(before.getAllLogRecords().isEmpty()) { "Actual profile history must be empty" }
        } finally {
            before.close()
        }
        val schema = SQLiteDatabase.openDatabase(
            context.getDatabasePath("logs.db").path, null, SQLiteDatabase.OPEN_READONLY
        ).use { it.version }
        check(schema == 7)
        check(!MonitoringDesiredState.read(context))
        DeviceDataBackup.importFromJson(
            context, json, setOf(DeviceDataType.LOGS), LogImportMode.REPLACE
        )
        val database = LogDatabase(context)
        val restored = try {
            database.getAllLogRecords()
        } finally {
            database.close()
        }
        check(restored == records) { "Actual profile restore changed exported values or duplicate multiplicity" }
        val report = JSONObject().put("complete", true).put("appPid", Process.myPid())
            .put("package", context.packageName).put("beforeCount", 0)
            .put("afterCount", restored.size).put("schemaVersion", schema)
            .put("exportPath", input.path).put("exportFileSha256", actual)
            .put("sourceRecordsSha256", digest(records))
            .put("restoredRecordsSha256", digest(restored))
            .put("monitoringDesired", MonitoringDesiredState.read(context)).put(
                "scope",
                "production DeviceDataBackup JSON REPLACE into actual initially empty profile logs.db; excludes SAF picker UI"
            )
        check(!report.getBoolean("monitoringDesired"))
        return Bundle().apply {
            putBoolean("complete", true)
            putString("reportJson", report.toString())
        }
    }

    private fun ownedExport(context: Context, intent: Intent): File {
        val name = checkNotNull(intent.getStringExtra("exportFilename"))
        require(name.matches(Regex("final-history-[A-Za-z0-9_-]{1,64}\\.json")))
        val directory = File(
            context.filesDir, "profiling/final-verification"
        ).apply { check(isDirectory || mkdirs()) }
        return File(
            directory, name
        ).also { check(it.canonicalFile.parentFile == directory.canonicalFile) }
    }

    private class RestoreContext(target: Context, private val directory: File) :
        ContextWrapper(target) {
        val preferenceNames = linkedSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File {
            require(name == "logs.db")
            return File(directory, name)
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

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val owned = "${directory.name}_$name"
            preferenceNames += owned
            return baseContext.getSharedPreferences(owned, mode)
        }

        override fun sendBroadcast(intent: Intent) = Unit
    }

    private fun digest(records: List<LogRecord>): String =
        sha256(records.joinToString("\n", postfix = "\n") {
            listOf(
                it.status,
                it.charge,
                it.time,
                it.temperature,
                it.voltage,
                it.currentMicroAmps,
                it.remainingChargeMicroampHours
            ).joinToString("\t") { value -> value?.toString() ?: "NULL" }
        }.toByteArray(Charsets.UTF_8))

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
