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
package codes.swistak.batterymonitor.ui.alarms

import android.app.Application
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.alarms.AlarmDatabase
import codes.swistak.batterymonitor.alarms.AlarmRule
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AlarmChannelInfo(
    val blocked: Boolean, val sound: Boolean, val vibration: Boolean
)

internal data class AlarmsState(
    val rules: List<AlarmRule> = emptyList(),
    val channels: Map<String, AlarmChannelInfo> = emptyMap(),
    val notificationsBlocked: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val draft: AlarmDraft? = null
)

internal fun alarmDeliveryLabel(channel: AlarmChannelInfo?): Int = when {
    channel == null || channel.blocked -> R.string.notifications_disabled
    channel.sound && channel.vibration -> R.string.alarms_delivery_sound_vibration
    channel.sound -> R.string.alarms_delivery_sound
    channel.vibration -> R.string.alarms_delivery_vibration
    else -> R.string.alarms_delivery_notification
}

internal class AlarmsViewModel(
    application: Application, private val savedState: SavedStateHandle
) : AndroidViewModel(application) {
    private var originalDraft = restoreDraft(savedState.get<Bundle>("alarm_original"))
    private val mutableState = MutableStateFlow(
        AlarmsState(draft = restoreDraft(savedState.get<Bundle>("alarm_draft")))
    )
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    val hasUnsavedChanges: Boolean
        get() = state.value.draft != null && state.value.draft != originalDraft

    fun refresh() {
        if (state.value.busy) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { readState() } }
            result.fold(onSuccess = { fresh ->
                mutableState.update {
                    it.copy(
                        rules = fresh.rules,
                        channels = fresh.channels,
                        notificationsBlocked = fresh.notificationsBlocked,
                        loading = false,
                        error = null
                    )
                }
            }, onFailure = {
                mutableState.update { it.copy(loading = false, error = databaseError()) }
            })
        }
    }

    fun beginDraft(id: Int?) {
        if (state.value.busy) return
        val existing = id?.let { alarmId -> state.value.rules.find { it.id == alarmId } }
        if (id != null && existing == null) {
            refreshJob?.cancel()
            mutableState.update { it.copy(busy = true, error = null) }
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { readState() } }
                val fresh = result.getOrNull()
                val rule = fresh?.rules?.find { it.id == id }
                if (rule == null) {
                    mutableState.update {
                        it.copy(
                            busy = false, loading = false, error = databaseError()
                        )
                    }
                } else {
                    mutableState.value = fresh.copy(busy = false)
                    setInitialDraft(AlarmDraft(rule.id, rule.enabled, rule.type, rule.threshold))
                }
            }
            return
        }
        val draft =
            existing?.let { AlarmDraft(it.id, it.enabled, it.type, it.threshold) } ?: AlarmDraft()
        setInitialDraft(draft)
    }

    private fun setInitialDraft(draft: AlarmDraft) {
        originalDraft = draft
        savedState["alarm_original"] = draft.toBundle()
        changeDraft(draft)
    }

    fun changeDraft(draft: AlarmDraft) {
        if (state.value.busy) return
        savedState["alarm_draft"] = draft.toBundle()
        mutableState.update { it.copy(draft = draft, error = null) }
    }

    fun discardDraft() {
        savedState.remove<Bundle>("alarm_draft")
        savedState.remove<Bundle>("alarm_original")
        originalDraft = null
        mutableState.update { it.copy(draft = null, error = null) }
    }

    fun saveDraft(onSaved: () -> Unit) {
        val draft = state.value.draft ?: return
        if (!validAlarmDraft(draft)) {
            mutableState.update {
                it.copy(error = getApplication<Application>().getString(R.string.alarms_invalid_threshold))
            }
            return
        }
        mutate(onSuccess = { discardDraft(); onSaved() }) { database ->
            database.saveAlarm(draft.id, draft.enabled, draft.type, draft.threshold) >= 0
        }
    }

    fun setEnabled(id: Int, enabled: Boolean, onChanged: () -> Unit) =
        mutate(onSuccess = onChanged) { database ->
            database.setEnabled(id, enabled) == 1
        }

    fun deleteDraft(onDeleted: () -> Unit) {
        val id = state.value.draft?.id ?: return
        mutate(onSuccess = { discardDraft(); onDeleted() }) { it.deleteAlarm(id) }
    }

    private fun mutate(
        onSuccess: () -> Unit = {}, operation: (AlarmDatabase) -> Boolean
    ) {
        if (state.value.busy) return
        refreshJob?.cancel()
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    withDatabase { check(operation(it)) }
                }
            }
            result.fold(onSuccess = {
                mutableState.update { it.copy(busy = false) }
                onSuccess()
                refresh()
            }, onFailure = {
                mutableState.update { it.copy(busy = false, error = databaseError()) }
            })
        }
    }

    private fun readState(): AlarmsState {
        val application = getApplication<Application>()
        val manager = application.getSystemService(NotificationManager::class.java)
        val groupBlocked =
            Build.VERSION.SDK_INT >= 28 && manager.getNotificationChannelGroup(BatteryInfoService.CHAN_GROUP_ID_ALARMS)?.isBlocked == true
        return AlarmsState(
            rules = withDatabase { it.getAlarmRules() },
            channels = AlarmDatabase.SUPPORTED_TYPES.associateWith { type ->
                val channel = manager.getNotificationChannel(type)
                AlarmChannelInfo(
                    blocked = channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE,
                    sound = channel?.sound != null && channel.importance >= NotificationManager.IMPORTANCE_DEFAULT,
                    vibration = channel?.shouldVibrate() == true
                )
            },
            notificationsBlocked = !manager.areNotificationsEnabled() || groupBlocked,
            loading = false
        )
    }

    private fun <T> withDatabase(operation: (AlarmDatabase) -> T): T {
        val database = AlarmDatabase(getApplication<Application>())
        return try {
            operation(database)
        } finally {
            database.close()
        }
    }

    private fun databaseError(): String =
        getApplication<Application>().getString(R.string.alarms_database_error)
}

private fun AlarmDraft.toBundle() = Bundle().apply {
    id?.let { putInt("id", it) }
    putBoolean("enabled", enabled)
    putString("type", type)
    putString("threshold", threshold)
    putString("threshold_input", thresholdInput)
}

private fun restoreDraft(bundle: Bundle?): AlarmDraft? = bundle?.let {
    AlarmDraft(
        id = if (it.containsKey("id")) it.getInt("id") else null,
        enabled = it.getBoolean("enabled"),
        type = it.getString("type") ?: "fully_charged",
        threshold = it.getString("threshold") ?: "",
        thresholdInput = it.getString("threshold_input")
    )
}
