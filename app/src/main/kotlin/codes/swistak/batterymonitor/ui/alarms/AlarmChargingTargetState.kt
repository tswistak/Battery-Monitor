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

import android.content.SharedPreferences
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingLimitProvider
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingTargetResolver
import codes.swistak.batterymonitor.settings.SettingsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AlarmChargingTargetState(
    private val preferences: SharedPreferences,
    private val scope: CoroutineScope,
    private val createProvider: () -> ChargingLimitProvider,
    private val accessChanges: StateFlow<Long>,
    private val prepareAccess: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableTarget = MutableStateFlow<Int?>(null)
    val target = mutableTarget.asStateFlow()
    private val readMutex = Mutex()
    private var provider: ChargingLimitProvider? = null
    private var active = false
    private var preparingAccess = false
    private var generation = 0L
    private var accessVersion = accessChanges.value
    private var refreshJob: Job? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in setOf(
                SettingsContract.KEY_CHARGING_TARGET_MODE,
                SettingsContract.KEY_CUSTOM_CHARGING_TARGET,
                SettingsContract.KEY_USE_PRIVILEGED_ACCESS
            )
        ) {
            refreshAccess()
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        scope.launch {
            try {
                accessChanges.collect { version ->
                    if (!preparingAccess && version != accessVersion) refresh(invalidate = true)
                }
            } finally {
                preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            }
        }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) {
            refreshAccess()
        } else {
            generation++
            refreshJob?.cancel()
        }
    }

    private fun refreshAccess() {
        if (!active) return
        preparingAccess = true
        try {
            prepareAccess()
        } finally {
            preparingAccess = false
        }
        refresh(invalidate = true)
    }

    fun refresh(invalidate: Boolean = false) {
        if (!active || !invalidate && refreshJob?.isActive == true) return
        accessVersion = accessChanges.value
        val revision = accessVersion
        val request = ++generation
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val percent = withContext(ioDispatcher) {
                readMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (invalidate) provider = null
                    try {
                        ChargingTargetResolver(preferences) {
                            val currentProvider =
                                provider ?: createProvider().also { provider = it }
                            currentProvider.readState()
                        }.resolveChargingTarget().percent
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        100
                    }
                }
            }
            if (!active || request != generation) return@launch
            if (revision != accessChanges.value) {
                refresh(invalidate = true)
                return@launch
            }
            mutableTarget.value = percent
        }
    }
}
