/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import codes.swistak.batterymonitor.settings.SettingsContract
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

internal data class MonitoringHealthState(
    val serviceHeartbeatElapsedTime: Long, val databaseHeartbeatElapsedTime: Long
)

internal object MonitoringHealthStore {
    private const val LOG_TAG = "MonitoringHealthStore"
    private const val FILE_NAME = "monitoring_health_state"
    private const val FILE_MAGIC = 0x424D4853
    private const val FILE_VERSION = 1

    fun read(context: Context): MonitoringHealthState =
        readFile(context) ?: readLegacyPreferences(context)

    @Synchronized
    fun recordServiceHeartbeat(context: Context, elapsedTime: Long) {
        val current = read(context)
        write(
            context, current.copy(serviceHeartbeatElapsedTime = elapsedTime)
        )
    }

    @Synchronized
    fun recordDatabaseHeartbeat(context: Context, elapsedTime: Long) {
        val current = read(context)
        write(
            context, current.copy(databaseHeartbeatElapsedTime = elapsedTime)
        )
    }

    private fun readFile(context: Context): MonitoringHealthState? {
        val file = stateFile(context)
        if (!file.baseFile.isFile) return null
        return try {
            DataInputStream(file.openRead().buffered()).use { input ->
                if (input.readInt() != FILE_MAGIC || input.readInt() != FILE_VERSION) return null
                MonitoringHealthState(
                    serviceHeartbeatElapsedTime = input.readLong(),
                    databaseHeartbeatElapsedTime = input.readLong()
                )
            }
        } catch (error: IOException) {
            Log.w(LOG_TAG, "Unable to read monitoring health state", error)
            null
        }
    }

    private fun readLegacyPreferences(context: Context): MonitoringHealthState {
        val preferences = context.getSharedPreferences(
            SettingsContract.SP_SERVICE_FILE, Context.MODE_PRIVATE
        )
        return MonitoringHealthState(
            serviceHeartbeatElapsedTime = preferences.getLong(
                BackgroundServiceWatchdog.KEY_LAST_HEARTBEAT_ELAPSED_TIME, 0L
            ), databaseHeartbeatElapsedTime = preferences.getLong(
                BackgroundServiceWatchdog.KEY_LAST_SUCCESSFUL_LOG_CHECK_ELAPSED_TIME, 0L
            )
        )
    }

    private fun write(context: Context, state: MonitoringHealthState) {
        val file = stateFile(context)
        val output = try {
            file.startWrite()
        } catch (error: IOException) {
            Log.e(LOG_TAG, "Unable to start writing monitoring health state", error)
            return
        }
        try {
            DataOutputStream(output.buffered()).apply {
                writeInt(FILE_MAGIC)
                writeInt(FILE_VERSION)
                writeLong(state.serviceHeartbeatElapsedTime)
                writeLong(state.databaseHeartbeatElapsedTime)
                flush()
            }
            file.finishWrite(output)
        } catch (error: IOException) {
            file.failWrite(output)
            Log.e(LOG_TAG, "Unable to write monitoring health state", error)
        }
    }

    private fun stateFile(context: Context): AtomicFile = AtomicFile(
        File(context.noBackupFilesDir, FILE_NAME)
    )
}
