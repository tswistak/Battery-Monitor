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
import codes.swistak.batterymonitor.settings.backup.SettingsBackup
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal enum class GeneralBackupDataType {
    SETTINGS, ALARMS, LOGS, PREDICTOR_DATA
}

internal data class GeneralBackupArchive(
    val schemaVersion: Int, val declaredFiles: Set<String>, val fileContents: Map<String, String>
)

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
    private const val MAX_ENTRY_BYTES = 100 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 150 * 1024 * 1024

    fun exportToUri(
        context: Context, uri: Uri, settingsPreferences: SharedPreferences
    ) {
        val settings = SettingsBackup.exportToJson(settingsPreferences).toString()
        val alarms = AlarmDatabase(context).let { database ->
            try {
                AlarmBackup.exportToJson(database).toString()
            } finally {
                database.close()
            }
        }
        val deviceSpecific = DeviceDataBackup.exportToJson(
            context, setOf(DeviceDataType.LOGS, DeviceDataType.PREDICTOR_DATA)
        ).toString()
        val metadata = JSONObject().put(KEY_VERSION, SCHEMA_VERSION)
            .put(KEY_FILES, JSONArray(Version1GeneralBackupSchema.expectedFiles)).toString()

        val pfd = context.contentResolver.openFileDescriptor(uri, "w") ?: return
        pfd.use {
            ZipOutputStream(FileOutputStream(it.fileDescriptor)).use { output ->
                output.writeTextEntry(METADATA_FILE, metadata)
                output.writeTextEntry(Version1GeneralBackupSchema.SETTINGS_FILE, settings)
                output.writeTextEntry(Version1GeneralBackupSchema.ALARMS_FILE, alarms)
                output.writeTextEntry(
                    Version1GeneralBackupSchema.DEVICE_SPECIFIC_FILE, deviceSpecific
                )
            }
        }
    }

    fun readFromUri(context: Context, uri: Uri): GeneralBackupArchive? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        pfd.use {
            FileInputStream(it.fileDescriptor).use { input ->
                return readArchive(input)
            }
        }
    }

    internal fun readArchive(input: InputStream): GeneralBackupArchive {
        val entries = linkedMapOf<String, String>()
        var totalBytes = 0
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                require(!entry.isDirectory) { "Unexpected directory in general backup" }
                require(entry.name !in entries) { "Duplicate general backup entry" }
                require('/' !in entry.name && '\\' !in entry.name) {
                    "Invalid general backup entry name"
                }
                val bytes = zip.readEntryBytes(MAX_ENTRY_BYTES)
                totalBytes += bytes.size
                require(totalBytes <= MAX_TOTAL_BYTES) { "General backup is too large" }
                entries[entry.name] = bytes.toString(StandardCharsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val metadata = JSONObject(
            entries[METADATA_FILE] ?: throw IllegalArgumentException("Missing backup metadata")
        )
        val schemaVersion = metadata.optInt(KEY_VERSION, 0)
        require(schemaVersion >= Version1GeneralBackupSchema.VERSION) {
            "Unsupported general backup schema"
        }
        val filesJson = metadata.getJSONArray(KEY_FILES)
        val declaredFiles = buildSet {
            for (index in 0 until filesJson.length()) {
                val fileName = filesJson.getString(index)
                require(add(fileName)) { "Duplicate file in general backup schema" }
            }
        }
        val knownFiles = Version1GeneralBackupSchema.expectedFiles.toSet()
        for (fileName in declaredFiles.intersect(knownFiles)) {
            require(entries.containsKey(fileName)) { "Missing declared general backup file" }
        }
        require(declaredFiles.any { it in knownFiles }) {
            "General backup contains no supported files"
        }
        return GeneralBackupArchive(schemaVersion, declaredFiles, entries)
    }

    fun getAvailableData(archive: GeneralBackupArchive): Set<GeneralBackupDataType> = buildSet {
        if (Version1GeneralBackupSchema.SETTINGS_FILE in archive.declaredFiles) {
            add(GeneralBackupDataType.SETTINGS)
        }
        if (Version1GeneralBackupSchema.ALARMS_FILE in archive.declaredFiles) {
            add(GeneralBackupDataType.ALARMS)
        }
        if (Version1GeneralBackupSchema.DEVICE_SPECIFIC_FILE in archive.declaredFiles) {
            val deviceData = DeviceDataBackup.getAvailableData(
                archive.fileContents.getValue(Version1GeneralBackupSchema.DEVICE_SPECIFIC_FILE)
            )
            if (DeviceDataType.LOGS in deviceData) add(GeneralBackupDataType.LOGS)
            if (DeviceDataType.PREDICTOR_DATA in deviceData) {
                add(GeneralBackupDataType.PREDICTOR_DATA)
            }
        }
    }

    fun containsNewerSchema(archive: GeneralBackupArchive): Boolean {
        if (archive.schemaVersion > SCHEMA_VERSION) return true
        if (Version1GeneralBackupSchema.SETTINGS_FILE in archive.declaredFiles && SettingsBackup.getSchemaVersion(
                archive.fileContents.getValue(Version1GeneralBackupSchema.SETTINGS_FILE)
            ) > SettingsBackup.SCHEMA_VERSION
        ) return true
        if (Version1GeneralBackupSchema.ALARMS_FILE in archive.declaredFiles && AlarmBackup.getSchemaVersion(
                archive.fileContents.getValue(Version1GeneralBackupSchema.ALARMS_FILE)
            ) > AlarmBackup.SCHEMA_VERSION
        ) return true
        if (Version1GeneralBackupSchema.DEVICE_SPECIFIC_FILE in archive.declaredFiles && DeviceDataBackup.getSchemaVersion(
                archive.fileContents.getValue(Version1GeneralBackupSchema.DEVICE_SPECIFIC_FILE)
            ) > DeviceDataBackup.SCHEMA_VERSION
        ) return true
        return false
    }

    @SuppressLint("UseKtx")
    fun restore(
        context: Context,
        settingsPreferences: SharedPreferences,
        archive: GeneralBackupArchive,
        selectedData: Set<GeneralBackupDataType>,
        logImportMode: LogImportMode
    ) {
        require(selectedData.isNotEmpty()) { "No general backup data selected" }
        require(selectedData.all { it in getAvailableData(archive) }) {
            "Selected data is not available in the general backup"
        }

        if (GeneralBackupDataType.SETTINGS in selectedData) {
            val editor = settingsPreferences.edit()
            SettingsBackup.importFromJson(
                editor, archive.fileContents.getValue(Version1GeneralBackupSchema.SETTINGS_FILE)
            )
            check(editor.commit()) { "Could not restore settings" }
        }
        if (GeneralBackupDataType.ALARMS in selectedData) {
            val database = AlarmDatabase(context)
            try {
                AlarmBackup.importFromJson(
                    database, archive.fileContents.getValue(Version1GeneralBackupSchema.ALARMS_FILE)
                )
            } finally {
                database.close()
            }
        }

        val deviceSelection = buildSet {
            if (GeneralBackupDataType.LOGS in selectedData) add(DeviceDataType.LOGS)
            if (GeneralBackupDataType.PREDICTOR_DATA in selectedData) {
                add(DeviceDataType.PREDICTOR_DATA)
            }
        }
        if (deviceSelection.isNotEmpty()) {
            DeviceDataBackup.importFromJson(
                context,
                archive.fileContents.getValue(Version1GeneralBackupSchema.DEVICE_SPECIFIC_FILE),
                deviceSelection,
                logImportMode
            )
        }
    }

    private fun ZipOutputStream.writeTextEntry(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun ZipInputStream.readEntryBytes(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = read(buffer)
        while (count >= 0) {
            if (count > 0) {
                require(output.size() + count <= limit) { "General backup entry is too large" }
                output.write(buffer, 0, count)
            }
            count = read(buffer)
        }
        return output.toByteArray()
    }
}
