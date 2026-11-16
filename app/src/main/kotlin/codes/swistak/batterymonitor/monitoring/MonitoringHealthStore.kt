/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring

import android.content.Context
import android.provider.Settings
import android.util.AtomicFile
import android.util.Log
import codes.swistak.batterymonitor.settings.SettingsContract
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile

internal data class MonitoringHealthState(
    val serviceHeartbeatElapsedTime: Long, val databaseHeartbeatElapsedTime: Long
)

internal object MonitoringHealthStore {
    private const val LOG_TAG = "MonitoringHealthStore"
    private const val FILE_NAME = "monitoring_health_state"
    private const val FILE_MAGIC = 0x424D4853
    private const val FILE_VERSION = 2
    private val empty = MonitoringHealthState(0L, 0L)

    fun read(context: Context): MonitoringHealthState = try {
        val boot = bootCount(context)
        locked(context) { file -> readFile(file, boot) } ?: readLegacyPreferences(context)
    } catch (error: IOException) {
        Log.w(LOG_TAG, "Unable to read monitoring health state", error)
        empty
    }

    fun record(context: Context, serviceElapsed: Long = 0L, databaseElapsed: Long = 0L) {
        try {
            val boot = bootCount(context)
            locked(context) { file ->
                val current = readFile(file, boot, allowLegacy = false) ?: empty
                val next = MonitoringHealthState(
                    serviceHeartbeatElapsedTime = maxOf(
                        current.serviceHeartbeatElapsedTime, serviceElapsed
                    ), databaseHeartbeatElapsedTime = maxOf(
                        current.databaseHeartbeatElapsedTime, databaseElapsed
                    )
                )
                if (next != current) write(file, boot, next)
            }
        } catch (error: IOException) {
            Log.e(LOG_TAG, "Unable to write monitoring health state", error)
        }
    }

    private fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun readFile(
        file: AtomicFile, boot: Int, allowLegacy: Boolean = true
    ): MonitoringHealthState? {
        return try {
            DataInputStream(file.openRead().buffered()).use { input ->
                if (input.readInt() != FILE_MAGIC) return empty
                when (input.readInt()) {
                    1 -> if (!allowLegacy) return empty
                    FILE_VERSION -> if (input.readInt() != boot) return empty
                    else -> return empty
                }
                MonitoringHealthState(
                    serviceHeartbeatElapsedTime = input.readLong(),
                    databaseHeartbeatElapsedTime = input.readLong()
                )
            }
        } catch (_: FileNotFoundException) {
            null
        } catch (error: IOException) {
            Log.w(LOG_TAG, "Unable to read monitoring health state", error)
            empty
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

    private fun write(file: AtomicFile, boot: Int, state: MonitoringHealthState) {
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
                writeInt(boot)
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

    @Synchronized
    private fun <T> locked(context: Context, operation: (AtomicFile) -> T): T =
        RandomAccessFile(File(context.noBackupFilesDir, "$FILE_NAME.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                operation(AtomicFile(File(context.noBackupFilesDir, FILE_NAME)))
            }
        }
}
