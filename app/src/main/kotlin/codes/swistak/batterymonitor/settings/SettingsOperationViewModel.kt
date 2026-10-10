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
package codes.swistak.batterymonitor.settings

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import codes.swistak.batterymonitor.devicebackup.DeviceDataType
import codes.swistak.batterymonitor.devicebackup.GeneralBackupArchive
import codes.swistak.batterymonitor.devicebackup.GeneralBackupDataType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SettingsOperationAction {
    ExportSettings, ReadSettings, ImportSettings, ExportAlarms, ReadAlarms, ImportAlarms, ExportDeviceData, ReadDeviceData, ImportDeviceData, ReadCsvLogs, ImportCsvLogs, ExportGeneralBackup, ReadGeneralBackup, ImportGeneralBackup, ResetSettings
}

internal data class JsonBackupImportPreview(val json: String, val version: Int)

internal data class DeviceBackupImportPreview(
    val json: String, val available: Set<DeviceDataType>, val version: Int
)

internal data class GeneralBackupImportPreview(
    val archive: GeneralBackupArchive,
    val available: Set<GeneralBackupDataType>,
    val newerSchema: Boolean
)

internal data class SettingsOperationOutcome(
    val action: SettingsOperationAction, val errorMessage: Int, val result: Result<Any?>
)

internal class SettingsOperationViewModel(application: Application) :
    AndroidViewModel(application) {
    var busy by mutableStateOf(false)
        private set
    var outcome by mutableStateOf<SettingsOperationOutcome?>(null)
        private set

    fun start(action: SettingsOperationAction, errorMessage: Int, operation: (Context) -> Any?) {
        if (busy || outcome != null) return
        busy = true
        val context = getApplication<Application>().applicationContext
        viewModelScope.launch {
            withContext(NonCancellable) {
                val result = withContext(Dispatchers.IO) { runCatching { operation(context) } }
                outcome = SettingsOperationOutcome(action, errorMessage, result)
                busy = false
            }
        }
    }

    fun takeOutcome(): SettingsOperationOutcome? = outcome.also { outcome = null }
}
