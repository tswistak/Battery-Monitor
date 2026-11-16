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
import android.content.Intent
import android.provider.Settings
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
    private const val VERSION = 2
    private const val ENCODED_SIZE = 13
    const val ACTION_CHANGED = "codes.swistak.batterymonitor.MONITORING_DESIRED_CHANGED"

    internal data class Session(val desired: Boolean, val bootCount: Int)

    fun read(context: Context): Boolean = resolve(context)

    fun afterSystemEvent(context: Context, action: String?): Boolean = resolve(context, action)

    private fun resolve(context: Context, action: String? = null): Boolean {
        return try {
            val bootCount = bootCount(context)
            var changed = false
            val desired = locked(context) { file ->
                val previous = readFile(file)
                if (previous != null && previous.bootCount == bootCount && (bootCount >= 0 || action != Intent.ACTION_BOOT_COMPLETED)) return@locked previous.desired

                val autostart = readAutostart(context)
                val wasDesired = previous?.desired ?: readLegacy(context, autostart)
                val effective = when {
                    previous != null || action == Intent.ACTION_BOOT_COMPLETED -> shouldStartMonitoringAfterSystemEvent(
                        Intent.ACTION_BOOT_COMPLETED, autostart, wasDesired
                    )

                    action == Intent.ACTION_MY_PACKAGE_REPLACED -> shouldStartMonitoringAfterSystemEvent(
                        action, autostart, wasDesired
                    )

                    else -> return@locked autostart != "never" && wasDesired
                }
                store(file, Session(effective, bootCount))
                changed = true
                effective
            }
            if (changed) notifyChanged(context)
            desired
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Unable to reconcile monitoring state", error)
            false
        }
    }

    fun initialize(context: Context): Boolean = update(context, desired = null)

    fun write(context: Context, desired: Boolean): Boolean = update(context, desired)

    private fun update(context: Context, desired: Boolean?): Boolean = try {
        val bootCount = bootCount(context)
        var changed = false
        locked(context) { file ->
            val existing = readFile(file)
            if (desired == null && existing == null) {
                val service = context.getSharedPreferences(
                    SettingsContract.SP_SERVICE_FILE, Context.MODE_PRIVATE
                )
                val main = context.getSharedPreferences(
                    SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE
                )
                if (service.contains(BatteryInfoService.KEY_SERVICE_DESIRED) || main.getBoolean(
                        SettingsContract.KEY_MIGRATED_SERVICE_DESIRED, false
                    )
                ) {
                    mirror(context, readLegacy(context, readAutostart(context)))
                    return@locked
                }
            }
            val value = desired ?: existing?.let {
                if (it.bootCount == bootCount) it.desired else {
                    val autostart = readAutostart(context)
                    shouldStartMonitoringAfterSystemEvent(
                        Intent.ACTION_BOOT_COMPLETED, autostart, it.desired
                    )
                }
            } ?: (readAutostart(context) != "never")
            val session = Session(value, bootCount)
            if (existing != session) {
                store(file, session)
                changed = true
            }
            mirror(context, value)
        }
        if (changed) notifyChanged(context)
        true
    } catch (error: Exception) {
        Log.e(LOG_TAG, "Unable to save monitoring state", error)
        false
    }

    private fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun store(file: AtomicFile, session: Session) {
        val stream = file.startWrite()
        try {
            stream.write(encode(session))
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        if (readFile(file) != session) throw IOException("Monitoring state was not committed")
    }

    @SuppressLint("UseKtx")
    private fun mirror(context: Context, desired: Boolean) {
        try {
            val committed = context.getSharedPreferences(
                SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE
            ).edit().putBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, desired)
                .putBoolean(SettingsContract.KEY_MIGRATED_SERVICE_DESIRED, true).commit()
            if (!committed) Log.w(
                LOG_TAG, "Monitoring state saved; legacy preference mirror failed"
            )
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Monitoring state saved; legacy preference mirror failed", error)
        }
    }

    private fun notifyChanged(context: Context) {
        try {
            context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "Unable to notify monitoring state consumers", error)
        }
    }

    @Synchronized
    private fun <T> locked(context: Context, operation: (AtomicFile) -> T): T =
        RandomAccessFile(File(context.noBackupFilesDir, "$FILE_NAME.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                operation(AtomicFile(File(context.noBackupFilesDir, FILE_NAME)))
            }
        }

    private fun readFile(file: AtomicFile): Session? = try {
        file.openRead().use { stream ->
            val bytes = ByteArray(ENCODED_SIZE + 1)
            var size = 0
            while (size < bytes.size) {
                val read = stream.read(bytes, size, bytes.size - size)
                if (read == -1) break
                size += read
            }
            decode(bytes.copyOf(size))
        }
    } catch (_: FileNotFoundException) {
        null
    } catch (error: Exception) {
        Log.w(LOG_TAG, "Ignoring invalid monitoring state", error)
        null
    }

    private fun readAutostart(context: Context): String? =
        context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
            .getString(SettingsContract.KEY_AUTOSTART, "auto")

    private fun readLegacy(context: Context, autostart: String?): Boolean {
        val main = context.getSharedPreferences(SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE)
        return legacyDesired(
            migrated = main.getBoolean(SettingsContract.KEY_MIGRATED_SERVICE_DESIRED, false),
            mainDesired = main.getBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, false),
            autostart = autostart,
            serviceDesired = context.getSharedPreferences(
                SettingsContract.SP_SERVICE_FILE, Context.MODE_PRIVATE
            ).getBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, false)
        )
    }

    internal fun legacyDesired(
        migrated: Boolean, mainDesired: Boolean, autostart: String?, serviceDesired: Boolean
    ): Boolean = if (migrated) mainDesired else autostart == "always" || serviceDesired

    internal fun encode(session: Session): ByteArray = ByteArrayOutputStream(ENCODED_SIZE).also {
        DataOutputStream(it).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeBoolean(session.desired)
            output.writeInt(session.bootCount)
        }
    }.toByteArray()

    internal fun decode(bytes: ByteArray): Session? {
        if (bytes.size != ENCODED_SIZE) return null
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
            val desired = when (input.readUnsignedByte()) {
                0 -> false
                1 -> true
                else -> return null
            }
            Session(desired, input.readInt())
        }
    }
}
