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

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.devicebackup.BackupImportInputStream
import codes.swistak.batterymonitor.devicebackup.DeviceDataBackup
import codes.swistak.batterymonitor.devicebackup.GeneralBackup
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class LogExportRecoveryException(message: String, cause: Exception) :
    IllegalArgumentException(message, cause)

internal fun exportRecoveryKey(uri: Uri): String {
    val identity = when (uri.scheme) {
        "file" -> File(requireNotNull(uri.path)).canonicalPath
        "content" -> try {
            "${uri.authority}:${DocumentsContract.getDocumentId(uri)}"
        } catch (_: IllegalArgumentException) {
            uri.normalizeScheme().toString()
        }

        else -> uri.normalizeScheme().toString()
    }
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal object JsonLogAppend {
    const val MAX_BYTES = GeneralBackup.MAX_ENTRY_BYTES

    fun append(context: Context, uri: Uri, records: Sequence<LogRecord>) {
        val directory = File(context.filesDir, "export-recovery").apply {
            check(isDirectory || mkdirs()) { "Could not create export recovery directory" }
        }
        val key = exportRecoveryKey(uri)
        val original = File(directory, "$key.original")
        val journal = File(directory, "$key.uri")
        val reading = File(directory, "$key.read")
        val prepared = File(directory, "$key.prepared")

        fun replace(source: File) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            } ?: error("Could not replace JSON history")
        }

        fun recoveryFailure(failure: Exception): Nothing {
            Log.e("LogExport", "Original JSON retained at $original for $uri", failure)
            throw LogExportRecoveryException(
                context.getString(R.string.log_export_recovery_required, original.name), failure
            )
        }

        if (original.exists()) {
            try {
                replace(original)
                check(original.delete()) { "Could not finish JSON history recovery" }
                journal.delete()
            } catch (failure: Exception) {
                recoveryFailure(failure)
            }
        }
        try {
            val replacement = try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Could not read JSON history")
                input.use { source ->
                    FileOutputStream(reading).use { output ->
                        BackupImportInputStream(source, MAX_BYTES).use { it.copyTo(output) }
                        output.fd.sync()
                    }
                }
                FileOutputStream(prepared).use { output ->
                    DeviceDataBackup.prepareLogsAppend(reading, output, records, MAX_BYTES).also {
                        if (it) output.fd.sync()
                    }
                }
            } catch (failure: Exception) {
                Log.w("LogExport", "Rejected JSON append destination $uri", failure)
                throw IllegalArgumentException(
                    context.getString(R.string.log_json_append_invalid), failure
                )
            }
            if (!replacement) return
            FileOutputStream(journal).use { output ->
                output.write(uri.toString().toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            check(reading.renameTo(original)) { "Could not preserve JSON history" }
            try {
                replace(prepared)
            } catch (failure: Exception) {
                try {
                    replace(original)
                    check(original.delete()) { "Could not finish JSON history recovery" }
                    journal.delete()
                } catch (restoreFailure: Exception) {
                    failure.addSuppressed(restoreFailure)
                    recoveryFailure(failure)
                }
                throw failure
            }
            if (!original.delete()) recoveryFailure(
                IllegalStateException("Could not finish JSON history replacement")
            )
            journal.delete()
        } finally {
            reading.delete()
            prepared.delete()
        }
    }
}
