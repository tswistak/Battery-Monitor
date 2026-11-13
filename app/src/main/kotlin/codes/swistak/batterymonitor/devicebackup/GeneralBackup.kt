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
package codes.swistak.batterymonitor.devicebackup

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import codes.swistak.batterymonitor.alarms.AlarmDatabase
import codes.swistak.batterymonitor.alarms.backup.AlarmBackup
import codes.swistak.batterymonitor.monitoring.PredictorStoredState
import codes.swistak.batterymonitor.settings.backup.SettingsBackup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal enum class GeneralBackupDataType {
    SETTINGS, ALARMS, LOGS, PREDICTOR_DATA
}

internal data class GeneralBackupArchive(
    val schemaVersion: Int,
    val settings: Result<JSONObject>?,
    val alarms: Result<JSONObject>?,
    val device: PreparedDeviceDataBackup?
)

internal class GeneralBackupRestoreException(
    val completedData: Set<GeneralBackupDataType>,
    val failedData: Set<GeneralBackupDataType>,
    cause: Exception
) : IOException("Could not completely restore the backup", cause)

internal object Version1GeneralBackupSchema {
    const val VERSION = 1
    const val SETTINGS_FILE = "settings.json"
    const val ALARMS_FILE = "alarms.json"
    const val DEVICE_SPECIFIC_FILE = "device-specific.json"
    val expectedFiles: List<String> = listOf(
        SETTINGS_FILE, ALARMS_FILE, DEVICE_SPECIFIC_FILE
    )
}

internal object GeneralBackup {
    const val SCHEMA_VERSION: Int = Version1GeneralBackupSchema.VERSION
    const val METADATA_FILE = "metadata.json"

    private const val KEY_VERSION = "version"
    private const val KEY_FILES = "files"
    internal const val MAX_ENTRY_BYTES = 100 * 1024 * 1024
    internal const val MAX_TOTAL_BYTES = 150 * 1024 * 1024

    internal const val MAX_ENTRIES = 32
    internal const val MAX_ENTRY_NAME_BYTES = 128
    internal const val MAX_METADATA_BYTES = 16 * 1024
    internal const val MAX_ARCHIVE_BYTES = MAX_TOTAL_BYTES + 10 * 1024 * 1024

    fun exportToUri(
        context: Context,
        uri: Uri,
        settingsPreferences: SharedPreferences,
        predictorState: PredictorStoredState? = null
    ) {
        val settings = SettingsBackup.exportToJson(settingsPreferences).toString()
        val alarms = AlarmDatabase(context).let { database ->
            try {
                AlarmBackup.exportToJson(database).toString()
            } finally {
                database.close()
            }
        }
        val metadata = JSONObject().put(KEY_VERSION, SCHEMA_VERSION)
            .put(KEY_FILES, JSONArray(Version1GeneralBackupSchema.expectedFiles)).toString()

        writeStagedBackupExport(context, { uri }) { staged ->
            ZipOutputStream(staged).use { output ->
                var remaining = MAX_TOTAL_BYTES
                fun writeEntry(name: String, write: (OutputStream) -> Unit) {
                    output.putNextEntry(ZipEntry(name))
                    val bounded =
                        BackupExportOutputStream(output, minOf(remaining, entryLimit(name)))
                    write(bounded)
                    remaining -= bounded.bytesWritten
                    output.closeEntry()
                }
                writeEntry(METADATA_FILE) { it.write(metadata.toByteArray(StandardCharsets.UTF_8)) }
                writeEntry(Version1GeneralBackupSchema.SETTINGS_FILE) {
                    it.write(settings.toByteArray(StandardCharsets.UTF_8))
                }
                writeEntry(Version1GeneralBackupSchema.ALARMS_FILE) {
                    it.write(alarms.toByteArray(StandardCharsets.UTF_8))
                }
                writeEntry(Version1GeneralBackupSchema.DEVICE_SPECIFIC_FILE) {
                    DeviceDataBackup.writeBackup(
                        context,
                        it,
                        setOf(DeviceDataType.LOGS, DeviceDataType.PREDICTOR_DATA),
                        predictorState
                    )
                }
            }
        }
    }

    fun readFromUri(context: Context, uri: Uri, logsFile: File): GeneralBackupArchive {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open backup source")
        return input.use { readArchive(it, logsFile) }
    }

    internal fun readArchive(
        input: InputStream,
        logsFile: File,
        maxEntryBytes: Int = MAX_ENTRY_BYTES,
        maxTotalBytes: Int = MAX_TOTAL_BYTES
    ): GeneralBackupArchive {
        val entries = mutableSetOf<String>()
        var headerBytes = 0
        var totalBytes = 0
        var metadata: JSONObject? = null
        var settings: Result<JSONObject>? = null
        var alarms: Result<JSONObject>? = null
        var device: PreparedDeviceDataBackup? = null
        try {
            val source = BackupZipInputStream(input)
            ZipInputStream(source).use { zip ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(entries.size < MAX_ENTRIES) { "General backup has too many entries" }
                    require(!entry.isDirectory) { "Unexpected directory in general backup" }
                    require(entries.add(entry.name)) { "Duplicate general backup entry" }
                    val nameBytes = entry.name.toByteArray(StandardCharsets.UTF_8).size
                    require(
                        nameBytes in 1..MAX_ENTRY_NAME_BYTES && '/' !in entry.name && '\\' !in entry.name && '\u0000' !in entry.name
                    ) {
                        "Invalid general backup entry name"
                    }
                    headerBytes += nameBytes + (entry.extra?.size ?: 0)
                    require(headerBytes <= MAX_METADATA_BYTES) { "General backup metadata is too large" }

                    val limit = minOf(entryLimit(entry.name), maxEntryBytes)
                    var entryBytes = 0
                    val entryInput = object : InputStream() {
                        override fun read(): Int = zip.read().also { if (it != -1) count(1) }

                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                            zip.read(bytes, offset, length).also { if (it > 0) count(it) }

                        private fun count(size: Int) {
                            entryBytes += size
                            totalBytes += size
                            require(entryBytes <= limit) { "General backup entry is too large" }
                            require(totalBytes <= maxTotalBytes) { "General backup is too large" }
                        }
                    }
                    when (entry.name) {
                        METADATA_FILE -> metadata = entryInput.readJson().getOrThrow()
                        Version1GeneralBackupSchema.SETTINGS_FILE -> settings =
                            entryInput.readJson()

                        Version1GeneralBackupSchema.ALARMS_FILE -> alarms = entryInput.readJson()
                        Version1GeneralBackupSchema.DEVICE_SPECIFIC_FILE -> device =
                            DeviceDataBackup.read(entryInput, logsFile)

                        else -> Unit
                    }
                    require(entryBytes <= limit) { "General backup entry is too large" }
                    require(totalBytes <= maxTotalBytes) { "General backup is too large" }
                    while (entryInput.read(buffer) != -1) {
                        // Count the bytes of any unconsumed or unknown entry.
                    }
                    zip.closeEntry()
                }
                source.validateDirectory(entries)
            }
            val manifest = requireNotNull(metadata) { "Missing backup metadata" }
            val schemaVersion = manifest.opt(KEY_VERSION) as? Int ?: 0
            require(schemaVersion >= Version1GeneralBackupSchema.VERSION) {
                "Unsupported general backup schema"
            }
            val filesJson = manifest.getJSONArray(KEY_FILES)
            require(filesJson.length() <= MAX_ENTRIES) { "General backup declares too many files" }
            val declared = buildSet {
                for (index in 0 until filesJson.length()) {
                    val fileName = filesJson.getString(index)
                    require(fileName.toByteArray(StandardCharsets.UTF_8).size in 1..MAX_ENTRY_NAME_BYTES) {
                        "Invalid declared file name"
                    }
                    require(add(fileName)) { "Duplicate file in general backup schema" }
                }
            }.intersect(Version1GeneralBackupSchema.expectedFiles.toSet())
            require(declared.isNotEmpty()) { "General backup contains no supported files" }
            require(entries.containsAll(declared)) { "Missing declared general backup file" }
            if (Version1GeneralBackupSchema.DEVICE_SPECIFIC_FILE !in declared) logsFile.delete()
            return GeneralBackupArchive(
                schemaVersion,
                settings.takeIf { Version1GeneralBackupSchema.SETTINGS_FILE in declared },
                alarms.takeIf { Version1GeneralBackupSchema.ALARMS_FILE in declared },
                device.takeIf { Version1GeneralBackupSchema.DEVICE_SPECIFIC_FILE in declared })
        } catch (failure: Exception) {
            logsFile.delete()
            throw failure
        }
    }

    private fun InputStream.readJson(): Result<JSONObject> = try {
        Result.success(readBackupJson(this))
    } catch (failure: ZipException) {
        throw failure
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private fun entryLimit(name: String): Int = when (name) {
        METADATA_FILE -> MAX_METADATA_BYTES
        Version1GeneralBackupSchema.SETTINGS_FILE -> SettingsBackup.MAX_IMPORT_BYTES
        Version1GeneralBackupSchema.ALARMS_FILE -> AlarmBackup.MAX_IMPORT_BYTES
        else -> MAX_ENTRY_BYTES
    }

    fun getAvailableData(archive: GeneralBackupArchive): Set<GeneralBackupDataType> = buildSet {
        if (archive.settings != null) add(GeneralBackupDataType.SETTINGS)
        if (archive.alarms != null) add(GeneralBackupDataType.ALARMS)
        val device = archive.device?.available.orEmpty()
        if (DeviceDataType.LOGS in device) add(GeneralBackupDataType.LOGS)
        if (DeviceDataType.PREDICTOR_DATA in device) add(GeneralBackupDataType.PREDICTOR_DATA)
    }

    // A failed optional member is reported when it is selected for restoration.
    fun containsNewerSchema(archive: GeneralBackupArchive): Boolean =
        archive.schemaVersion > SCHEMA_VERSION || (archive.settings?.getOrNull()
            ?.optInt(KEY_VERSION, 0)
            ?: 0) > SettingsBackup.SCHEMA_VERSION || (archive.alarms?.getOrNull()
            ?.optInt(KEY_VERSION, 0) ?: 0) > AlarmBackup.SCHEMA_VERSION || (archive.device?.version
            ?: 0) > DeviceDataBackup.SCHEMA_VERSION

    // KTX edit discards the commit result required before marking settings restored.
    @SuppressLint("UseKtx")
    fun restore(
        context: Context,
        settingsPreferences: SharedPreferences,
        archive: GeneralBackupArchive,
        selectedData: Set<GeneralBackupDataType>,
        logImportMode: LogImportMode
    ) {
        require(selectedData.isNotEmpty() && getAvailableData(archive).containsAll(selectedData)) {
            "Selected data is not available in the general backup"
        }
        val settings = if (GeneralBackupDataType.SETTINGS in selectedData) {
            SettingsBackup.parsedSettings(requireNotNull(archive.settings).getOrThrow())
        } else null
        val alarms = if (GeneralBackupDataType.ALARMS in selectedData) {
            AlarmBackup.validatedRecords(requireNotNull(archive.alarms).getOrThrow())
        } else null
        val deviceSelection = deviceSelection(selectedData)
        val device = if (deviceSelection.isNotEmpty()) {
            requireNotNull(archive.device).also { DeviceDataBackup.validate(it, deviceSelection) }
        } else null
        val completed = linkedSetOf<GeneralBackupDataType>()
        var attempted = emptySet<GeneralBackupDataType>()
        try {
            if (settings != null) {
                attempted = setOf(GeneralBackupDataType.SETTINGS)
                val editor = settingsPreferences.edit()
                SettingsBackup.restore(editor, settings)
                check(editor.commit()) { "Could not restore settings" }
                completed += attempted
            }
            if (alarms != null) {
                attempted = setOf(GeneralBackupDataType.ALARMS)
                val database = AlarmDatabase(context)
                try {
                    AlarmBackup.restore(database, alarms)
                } finally {
                    database.close()
                }
                completed += attempted
            }
            if (device != null) {
                attempted = selectedData.intersect(
                    setOf(GeneralBackupDataType.LOGS, GeneralBackupDataType.PREDICTOR_DATA)
                )
                DeviceDataBackup.restore(context, device, deviceSelection, logImportMode)
                completed += attempted
            }
        } catch (error: GeneralBackupRestoreException) {
            throw GeneralBackupRestoreException(
                completed + error.completedData, error.failedData, error
            )
        } catch (error: Exception) {
            throw GeneralBackupRestoreException(completed.toSet(), attempted, error)
        }
    }

    private fun deviceSelection(selectedData: Set<GeneralBackupDataType>): Set<DeviceDataType> =
        buildSet {
            if (GeneralBackupDataType.LOGS in selectedData) add(DeviceDataType.LOGS)
            if (GeneralBackupDataType.PREDICTOR_DATA in selectedData) add(DeviceDataType.PREDICTOR_DATA)
        }

}
