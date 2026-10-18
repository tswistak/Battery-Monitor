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

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import codes.swistak.batterymonitor.logs.HistoryQuery
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ProfileHistoryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                check(context.packageName == "codes.swistak.batterymonitor.profile")
                check(intent.action == "${context.packageName}.HISTORY_INSERT")
                check(
                    intent.getBooleanExtra(
                        "synthetic", false
                    )
                ) { "Explicit synthetic fixture required" }
                check(
                    context.getSharedPreferences("profile_fixture", 0)
                        .getLong("anchorMillis", 0) > 0
                )
                val processName = File("/proc/self/cmdline").readText().substringBefore('\u0000')
                check(processName == "${context.packageName}:BIS")
                @Suppress("DEPRECATION") val service = checkNotNull(
                    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(
                        100
                    ).firstOrNull { it.service.className == BatteryInfoService::class.java.name }) {
                    "Real BatteryInfoService must already run"
                }
                check(service.pid == Process.myPid()) { "BatteryInfoService must run in the writer process" }
                val file = context.getDatabasePath("logs.db")
                check(file.isFile)
                val schema =
                    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                        .use { it.version }
                check(schema == 7) { "Expected already-migrated schema 7, got $schema" }
                val times = checkNotNull(intent.getLongArrayExtra("times"))
                require(times.size in 1..8 && times.distinct().size == times.size)
                val now = System.currentTimeMillis()
                require(times.all { it in 1_577_836_800_000L..now })
                val percent = intent.getIntExtra("percent", -1).also { require(it in 0..100) }
                val status = intent.getIntExtra("status", -1).also { require(it in 0..9) }
                val plugged = intent.getIntExtra("plugged", -1).also { require(it in 0..9) }
                val voltage =
                    intent.getIntExtra("voltage", 4000).also { require(it in 500..20_000) }
                val temperature =
                    intent.getIntExtra("temperature", 350).also { require(it in -500..1500) }
                val columns =
                    "_id,status,charge,time,temperature,voltage,current,remaining_charge_uah"
                val database = LogDatabase(context)
                val result = try {
                    val beforeRevision = database.historyChanges(null, null).revision
                    val beforeId = database.readHistory(
                        HistoryQuery(
                            "SELECT $columns FROM logs ORDER BY _id DESC LIMIT 1", emptyArray()
                        )
                    ) { it.firstOrNull()?.id ?: 0L }
                    val start = SystemClock.elapsedRealtimeNanos()
                    database.addLogs(times.map { time ->
                        LogRecord(
                            LogDatabase.encodeStatus(status, plugged, LogDatabase.STATUS_SAMPLE),
                            percent,
                            time,
                            temperature,
                            voltage,
                            -350_000L,
                            percent * 30_000L
                        )
                    })
                    val duration = SystemClock.elapsedRealtimeNanos() - start
                    val inserted =
                        database.readHistory(
                            HistoryQuery(
                                "SELECT $columns FROM logs WHERE _id > ? AND time IN (${
                                    times.joinToString(
                                        ","
                                    ) { "?" }
                                }) ORDER BY _id", arrayOf(
                                    beforeId.toString(), *times.map(Long::toString).toTypedArray()
                                ))) { records -> records.toList() }
                    check(inserted.size == times.size && inserted.all {
                        it.record.charge == percent && it.record.status == LogDatabase.encodeStatus(
                            status, plugged, LogDatabase.STATUS_SAMPLE
                        )
                    }) { "Committed fixture rows could not be identified exactly" }
                    val revision = database.historyChanges(beforeRevision, null).revision
                    check(revision > beforeRevision)
                    JSONObject().put(
                        "scope",
                        "synthetic profile rows; real :BIS LogDatabase.addLogs transaction and broadcast"
                    ).put("pid", Process.myPid()).put("uid", Process.myUid())
                        .put("processName", processName).put("servicePid", service.pid)
                        .put("serviceComponent", service.service.flattenToString())
                        .put("databasePath", file.path).put("schemaVersion", schema)
                        .put("beforeRevision", beforeRevision).put("revision", revision)
                        .put("writeDurationNanos", duration)
                        .put("records", JSONArray(inserted.map { row ->
                            val record = row.record
                            JSONObject().put("id", row.id).put("time", record.time)
                                .put("status", record.status).put("percent", record.charge)
                                .put("temperature", record.temperature)
                                .put("voltage", record.voltage)
                                .put("currentMicroAmps", record.currentMicroAmps).put(
                                    "remainingChargeMicroampHours",
                                    record.remainingChargeMicroampHours
                                )
                        }))
                } finally {
                    database.close()
                }
                pending.resultCode = Activity.RESULT_OK
                pending.setResultExtras(Bundle().apply {
                    putString(
                        "reportJson", result.toString()
                    )
                })
            } catch (error: Exception) {
                Log.e("ProfileHistory", "BIS fixture failed", error)
                pending.resultCode = Activity.RESULT_CANCELED
                pending.setResultExtras(Bundle().apply {
                    putString("error", error.javaClass.simpleName + ": " + error.message)
                    putInt("pid", Process.myPid())
                })
            } finally {
                pending.finish()
            }
        }.apply {
            name = "profile-bis-history"
            start()
        }
    }
}
