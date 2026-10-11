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
package codes.swistak.batterymonitor.monitoring

import android.annotation.SuppressLint
import android.content.Context
import android.util.AtomicFile
import android.util.Log
import codes.swistak.batterymonitor.settings.SettingsContract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile

internal object MonitoringDesiredState {
    private const val LOG_TAG = "MonitoringDesiredState"
    private const val FILE_NAME = "monitoring_desired_state"
    private const val MAGIC = 0x424D4453
    private const val VERSION = 1
    private const val ENCODED_SIZE = 9

    fun read(context: Context): Boolean = try {
        locked(context) { readFile(it) } ?: readLegacy(context)
    } catch (error: Exception) {
        Log.w(LOG_TAG, "Unable to read monitoring state", error)
        readLegacy(context)
    }

    fun initialize(context: Context): Boolean =
        update(context, desired = true, initializeOnly = true)

    fun write(context: Context, desired: Boolean, mirrorPreferences: Boolean = true): Boolean =
        update(context, desired, mirrorPreferences = mirrorPreferences)

    private fun update(
        context: Context,
        desired: Boolean,
        initializeOnly: Boolean = false,
        mirrorPreferences: Boolean = true
    ): Boolean = try {
        locked(context) { file ->
            val existing = if (initializeOnly) readFile(file) else null
            val value = existing ?: desired
            if (existing == null) {
                val stream = file.startWrite()
                try {
                    stream.write(encode(value))
                    stream.fd.sync()
                    file.finishWrite(stream)
                    if (readFile(file) != value) throw IOException("Monitoring state was not committed")
                } catch (error: Exception) {
                    file.failWrite(stream)
                    throw error
                }
            }
            if (mirrorPreferences) {
                try {
                    @SuppressLint("UseKtx") val committed = context.getSharedPreferences(
                        SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE
                    ).edit().putBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, value)
                        .putBoolean(SettingsContract.KEY_MIGRATED_SERVICE_DESIRED, true).commit()
                    if (!committed) Log.w(
                        LOG_TAG, "Monitoring state saved; legacy preference mirror failed"
                    )
                } catch (error: Exception) {
                    Log.w(LOG_TAG, "Monitoring state saved; legacy preference mirror failed", error)
                }
            }
            true
        }
    } catch (error: Exception) {
        Log.e(LOG_TAG, "Unable to save monitoring state", error)
        false
    }

    @Synchronized
    private fun <T> locked(context: Context, operation: (AtomicFile) -> T): T =
        RandomAccessFile(File(context.noBackupFilesDir, "$FILE_NAME.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                operation(AtomicFile(File(context.noBackupFilesDir, FILE_NAME)))
            }
        }

    private fun readFile(file: AtomicFile): Boolean? = try {
        file.openRead().use { stream ->
            val bytes = ByteArray(ENCODED_SIZE)
            DataInputStream(stream).readFully(bytes)
            if (stream.read() == -1) decode(bytes) else null
        }
    } catch (_: FileNotFoundException) {
        null
    } catch (error: Exception) {
        Log.w(LOG_TAG, "Ignoring invalid monitoring state", error)
        null
    }

    private fun readLegacy(context: Context): Boolean {
        val main = context.getSharedPreferences(SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE)
        return legacyDesired(
            migrated = main.getBoolean(SettingsContract.KEY_MIGRATED_SERVICE_DESIRED, false),
            mainDesired = main.getBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, false),
            autostart = context.getSharedPreferences(
                SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
            ).getString(SettingsContract.KEY_AUTOSTART, "auto"),
            serviceDesired = context.getSharedPreferences(
                SettingsContract.SP_SERVICE_FILE, Context.MODE_PRIVATE
            ).getBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, false)
        )
    }

    internal fun legacyDesired(
        migrated: Boolean, mainDesired: Boolean, autostart: String?, serviceDesired: Boolean
    ): Boolean = if (migrated) mainDesired else autostart == "always" || serviceDesired

    internal fun encode(desired: Boolean): ByteArray = ByteArrayOutputStream(ENCODED_SIZE).also {
        DataOutputStream(it).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeBoolean(desired)
        }
    }.toByteArray()

    internal fun decode(bytes: ByteArray): Boolean? {
        if (bytes.size != ENCODED_SIZE) return null
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
            when (input.readUnsignedByte()) {
                0 -> false
                1 -> true
                else -> null
            }
        }
    }
}
