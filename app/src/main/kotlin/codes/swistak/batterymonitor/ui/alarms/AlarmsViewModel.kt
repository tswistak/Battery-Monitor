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
import codes.swistak.batterymonitor.alarms.ALARM_TYPES
import codes.swistak.batterymonitor.alarms.AlarmCollisionException
import codes.swistak.batterymonitor.alarms.AlarmConflictException
import codes.swistak.batterymonitor.alarms.AlarmDatabase
import codes.swistak.batterymonitor.alarms.AlarmProjection
import codes.swistak.batterymonitor.alarms.AlarmRule
import codes.swistak.batterymonitor.alarms.LogicalAlarm
import codes.swistak.batterymonitor.alarms.projectAlarms
import codes.swistak.batterymonitor.alarms.requireAlarmMembers
import codes.swistak.batterymonitor.alarms.thresholdCollision
import codes.swistak.batterymonitor.alarms.validStoredAlarm
import codes.swistak.batterymonitor.monitoring.BackgroundServiceWatchdog
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.temperatureUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal data class AlarmChannelInfo(
    val available: Boolean, val blocked: Boolean, val sound: Boolean, val vibration: Boolean
)

internal data class AlarmsState(
    val rules: List<AlarmRule> = emptyList(),
    val projection: AlarmProjection = projectAlarms(emptyList()),
    val channels: Map<String, AlarmChannelInfo> = emptyMap(),
    val notificationsBlocked: Boolean = false,
    val groupBlocked: Boolean = false,
    val monitoringDesired: Boolean = true,
    val hasSnapshot: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val readError: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val draft: AlarmDraft? = null,
    val draftConflict: Boolean = false,
    val collisionId: Int? = null,
    val revealType: String? = null,
    val revealId: Int? = null
) {
    val canMutate: Boolean get() = hasSnapshot && !loading && readError == null && !busy
    val enabledCount: Int? get() = if (hasSnapshot) projection.enabledCount else null
}

internal fun alarmDeliveryLabel(channel: AlarmChannelInfo?): Int = when {
    channel == null || !channel.available -> R.string.alarms_channel_unavailable
    channel.blocked -> R.string.alarm_chan_disabled
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
        mutableState.update { it.copy(loading = true) }
        refreshJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { readState() } }
            result.fold(onSuccess = { fresh ->
                mutableState.update { old ->
                    fresh.copy(
                        draft = old.draft,
                        error = old.error,
                        notice = old.notice,
                        collisionId = old.collisionId,
                        revealType = old.revealType,
                        revealId = old.revealId,
                        draftConflict = old.draft?.members?.takeIf { it.isNotEmpty() }?.let {
                            runCatching { requireAlarmMembers(fresh.rules, it) }.isFailure
                        } == true)
                }
                savedState.remove<Int>("alarm_pending_id")?.let(::openLegacyAlarm)
            }, onFailure = {
                mutableState.update {
                    it.copy(
                        loading = false, readError = text(R.string.alarms_database_error)
                    )
                }
            })
        }
    }

    fun openLegacyAlarm(id: Int) {
        if (!state.value.canMutate) {
            savedState["alarm_pending_id"] = id
            if (!state.value.busy) refresh()
            return
        }
        val rule = state.value.rules.find { it.id == id }
        if (rule == null || !validStoredAlarm(rule)) {
            mutableState.update { it.copy(notice = text(R.string.alarms_missing_rule)) }
            return
        }
        mutableState.update { it.copy(revealType = rule.type, revealId = id) }
        if (ALARM_TYPES.single { it.type == rule.type }.bounds != null) beginDraft(id)
    }

    fun beginThreshold(type: String, fahrenheit: Boolean, locale: Locale) {
        if (!state.value.canMutate || ALARM_TYPES.none { it.type == type && it.bounds != null }) return
        setInitialDraft(
            AlarmDraft(
                type = type,
                thresholdInput = "",
                initialInput = "",
                inputLocale = locale.toLanguageTag(),
                inputFahrenheit = fahrenheit
            )
        )
    }

    fun beginDraft(id: Int, fahrenheit: Boolean? = null, locale: Locale? = null) {
        if (!state.value.canMutate) return
        val group =
            state.value.projection.types.flatMap { it.alarms }.find { id in it.memberIds } ?: return
        if (ALARM_TYPES.single { it.type == group.type }.bounds == null) return
        val actualLocale =
            locale ?: getApplication<Application>().resources.configuration.locales[0]
        val actualFahrenheit = fahrenheit ?: getApplication<Application>().getSharedPreferences(
            SettingsContract.SETTINGS_FILE, Application.MODE_PRIVATE
        ).temperatureUnit(text(R.string.default_temperature_unit)).convertToFahrenheit
        val draft = AlarmDraft(
            id = group.id,
            enabled = group.enabled,
            type = group.type,
            threshold = group.members.first().threshold,
            members = group.members,
            inputLocale = actualLocale.toLanguageTag(),
            inputFahrenheit = actualFahrenheit
        )
        val input = alarmThresholdInput(draft, actualFahrenheit, actualLocale)
        setInitialDraft(draft.copy(thresholdInput = input, initialInput = input))
    }

    private fun setInitialDraft(draft: AlarmDraft) {
        originalDraft = draft
        savedState["alarm_original"] = draft.toBundle()
        savedState["alarm_draft"] = draft.toBundle()
        mutableState.update {
            it.copy(
                draft = draft, error = null, collisionId = null, draftConflict = false
            )
        }
    }

    fun changeDraftInput(input: String) {
        if (!state.value.canMutate) return
        val draft = state.value.draft ?: return
        val updated = editThresholdInput(draft, input)
        savedState["alarm_draft"] = updated.toBundle()
        val collision = if (validAlarmDraft(updated)) thresholdCollision(
            state.value.rules, updated.type, updated.threshold, updated.members.map(AlarmRule::id)
        ) else null
        mutableState.update { it.copy(draft = updated, error = null, collisionId = collision?.id) }
    }

    fun discardDraft() {
        savedState.remove<Bundle>("alarm_draft")
        savedState.remove<Bundle>("alarm_original")
        originalDraft = null
        mutableState.update {
            it.copy(
                draft = null, error = null, collisionId = null, draftConflict = false
            )
        }
    }

    fun editExistingThreshold() {
        val id = state.value.collisionId ?: return
        val old = state.value.draft ?: return
        beginDraft(id, old.inputFahrenheit, Locale.forLanguageTag(requireNotNull(old.inputLocale)))
    }

    fun saveDraft(onSaved: () -> Unit) {
        val draft = state.value.draft ?: return
        if (!validAlarmDraft(draft) || state.value.collisionId != null || state.value.draftConflict) return
        mutate(onSuccess = {
            discardDraft()
            onSaved()
        }) {
            it.saveThreshold(draft.type, draft.threshold, draft.members)
        }
    }

    fun setEnabled(type: String, alarm: LogicalAlarm?, enabled: Boolean, onChanged: () -> Unit) =
        mutate(onSuccess = onChanged) {
            it.setLogicalEnabled(
                type, alarm?.members.orEmpty(), enabled
            )
        }

    fun deleteDraft(onDeleted: () -> Unit) {
        val draft = state.value.draft ?: return
        if (draft.members.isEmpty() || state.value.draftConflict) return
        mutate(onSuccess = {
            discardDraft()
            onDeleted()
        }) {
            it.deleteLogicalAlarm(draft.members)
        }
    }

    fun disableInvalid(rule: AlarmRule, onChanged: () -> Unit) =
        mutate(onSuccess = onChanged) { it.setLogicalEnabled(rule.type, listOf(rule), false) }

    fun deleteInvalid(rule: AlarmRule, onChanged: () -> Unit) =
        mutate(onSuccess = onChanged) { it.deleteLogicalAlarm(listOf(rule)) }

    fun clearReveal() = mutableState.update { it.copy(revealType = null, revealId = null) }
    fun clearNotice() = mutableState.update { it.copy(notice = null) }
    fun reportSettingsFailure() =
        mutableState.update { it.copy(notice = text(R.string.alarms_settings_unavailable)) }

    private fun mutate(onSuccess: () -> Unit, operation: (AlarmDatabase) -> Unit) {
        if (!state.value.canMutate) return
        refreshJob?.cancel()
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { withDatabase(operation) } }
            result.fold(onSuccess = {
                mutableState.update { it.copy(busy = false, notice = text(R.string.alarms_saved)) }
                runCatching(onSuccess).onFailure {
                    mutableState.update { it.copy(notice = text(R.string.alarms_saved_reload_failed)) }
                }
                refresh()
            }, onFailure = { failure ->
                mutableState.update { old ->
                    old.copy(
                        busy = false,
                        error = if (failure is AlarmCollisionException) null else text(
                            if (failure is AlarmConflictException) R.string.alarms_conflict else R.string.alarms_database_error
                        ),
                        draftConflict = old.draftConflict || failure is AlarmConflictException,
                        collisionId = (failure as? AlarmCollisionException)?.existing?.id
                            ?: old.collisionId
                    )
                }
                if (failure is AlarmConflictException || failure is AlarmCollisionException) refresh()
            })
        }
    }

    private fun readState(): AlarmsState {
        val manager =
            getApplication<Application>().getSystemService(NotificationManager::class.java)
        val rules = withDatabase { it.getAlarmRules() }
        return AlarmsState(
            rules = rules,
            projection = projectAlarms(rules),
            hasSnapshot = true,
            monitoringDesired = BackgroundServiceWatchdog.isServiceDesired(getApplication()),
            channels = AlarmDatabase.SUPPORTED_TYPES.associateWith { type ->
                val channel = manager.getNotificationChannel(type)
                AlarmChannelInfo(
                    available = channel != null,
                    blocked = channel?.importance == NotificationManager.IMPORTANCE_NONE,
                    sound = channel?.sound != null && channel.importance >= NotificationManager.IMPORTANCE_DEFAULT,
                    vibration = channel?.shouldVibrate() == true
                )
            },
            notificationsBlocked = !manager.areNotificationsEnabled(),
            groupBlocked = Build.VERSION.SDK_INT >= 28 && manager.getNotificationChannelGroup(
                BatteryInfoService.CHAN_GROUP_ID_ALARMS
            )?.isBlocked == true,
            loading = false
        )
    }

    private fun <T> withDatabase(operation: (AlarmDatabase) -> T): T {
        val database = AlarmDatabase(getApplication())
        return try {
            operation(database)
        } finally {
            database.close()
        }
    }

    private fun text(resource: Int): String = getApplication<Application>().getString(resource)
}

internal fun AlarmDraft.toBundle() = Bundle().apply {
    id?.let { putInt("id", it) }
    putBoolean("enabled", enabled)
    putString("type", type)
    putString("threshold", threshold)
    putString("threshold_input", thresholdInput)
    putString("original_threshold", originalThreshold)
    putString("initial_input", initialInput)
    putString("input_locale", inputLocale)
    putBoolean("input_fahrenheit", inputFahrenheit)
    putIntArray("member_ids", members.map { it.id }.toIntArray())
    putBooleanArray("member_enabled", members.map { it.enabled }.toBooleanArray())
    putStringArray("member_thresholds", members.map { it.threshold }.toTypedArray())
}

internal fun restoreDraft(bundle: Bundle?): AlarmDraft? = bundle?.let {
    val type = it.getString("type") ?: return null
    val locale = it.getString("input_locale") ?: return null
    val ids = it.getIntArray("member_ids") ?: intArrayOf()
    val flags = it.getBooleanArray("member_enabled") ?: booleanArrayOf()
    val thresholds = it.getStringArray("member_thresholds") ?: emptyArray()
    if (ids.size != flags.size || ids.size != thresholds.size) return null
    AlarmDraft(
        id = if (it.containsKey("id")) it.getInt("id") else null,
        enabled = it.getBoolean("enabled"),
        type = type,
        threshold = it.getString("threshold") ?: "",
        thresholdInput = it.getString("threshold_input"),
        members = ids.mapIndexed { index, id ->
            AlarmRule(
                id, flags[index], type, thresholds[index]
            )
        },
        originalThreshold = it.getString("original_threshold") ?: "",
        initialInput = it.getString("initial_input"),
        inputLocale = locale,
        inputFahrenheit = it.getBoolean("input_fahrenheit")
    )
}
