/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
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

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsSnapshot

internal class AutoLogExportProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val appContext = requireNotNull(context)
        return when (method) {
            WATERMARKS -> Bundle().apply {
                val preferences = appContext.getSharedPreferences(
                    SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
                )
                for (key in watermarkKeys) if (preferences.contains(key)) {
                    putLong(key, preferences.getLong(key, 0))
                }
            }

            else -> try {
                if (method == EXPORT) {
                    AutoLogExporter.exportInMainProcess(
                        appContext, extras?.getBoolean(CREATE_WHEN_EMPTY) == true
                    )
                } else {
                    LogExport.withWatermarkLock(appContext) {
                        when (method) {
                            "scheduler.ensure" -> AutoLogExportScheduler.ensureScheduledInMainProcess(
                                appContext
                            )

                            "scheduler.reschedule" -> AutoLogExportScheduler.rescheduleInMainProcess(
                                appContext
                            )

                            "scheduler.cancel" -> AutoLogExportScheduler.cancelInMainProcess(
                                appContext
                            )

                            "scheduler.after" -> AutoLogExportScheduler.scheduleAfterRunInMainProcess(
                                appContext
                            )

                            else -> throw IllegalArgumentException("Unknown automatic export operation")
                        }
                    }
                }
                Bundle().apply { putBoolean(SUCCESS, true) }
            } catch (failure: Exception) {
                Bundle().apply {
                    putString(ERROR, failure.message ?: failure.javaClass.simpleName)
                    putBoolean(RECOVERY, failure is LogExportRecoveryException)
                }
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor = throw UnsupportedOperationException()

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException()

    companion object {
        private const val EXPORT = "export"
        private const val WATERMARKS = "watermarks"
        private const val CREATE_WHEN_EMPTY = "createWhenEmpty"
        private const val SUCCESS = "success"
        private const val ERROR = "error"
        private const val RECOVERY = "recovery"
        private val watermarkKeys = listOf(
            SettingsContract.KEY_LAST_LOG_EXPORT_TIME,
            SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME
        )

        private fun uri(context: Context): Uri =
            "content://${context.packageName}.log-export".toUri()

        fun export(context: Context, createFileWhenEmpty: Boolean) = request(
            context, EXPORT, Bundle().apply { putBoolean(CREATE_WHEN_EMPTY, createFileWhenEmpty) })

        fun schedule(context: Context, action: String) = request(context, "scheduler.$action", null)

        private fun request(context: Context, method: String, extras: Bundle?) {
            val result = checkNotNull(
                context.contentResolver.call(
                    uri(context), method, null, extras
                )
            ) { "Automatic export did not return a result" }
            if (!result.getBoolean(SUCCESS)) {
                val failure =
                    IllegalStateException(result.getString(ERROR) ?: "Automatic export failed")
                if (result.getBoolean(RECOVERY)) throw LogExportRecoveryException(
                    requireNotNull(failure.message), failure
                )
                throw failure
            }
        }

        fun applyMonitoringSnapshot(
            context: Context, preferences: SharedPreferences, snapshot: Bundle
        ): Boolean = runCatching {
            LogExport.withWatermarkLock(context) {
                val latest = checkNotNull(
                    context.contentResolver.call(
                        uri(context), WATERMARKS, null, null
                    )
                ) { "Could not read export watermarks" }
                val merged = Bundle(snapshot)
                for (key in watermarkKeys) merged.remove(key)
                merged.putAll(latest)
                SettingsSnapshot.apply(preferences, merged)
            }
        }.getOrDefault(false)
    }
}
