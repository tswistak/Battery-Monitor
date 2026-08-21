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
package codes.swistak.batterymonitor.monitoring.charginglimit

import android.content.SharedPreferences
import codes.swistak.batterymonitor.settings.SettingsContract

internal fun interface ChargingLimitProvider {
    fun readState(): ChargingLimitState
}

internal enum class TargetSource {
    DEVICE, CUSTOM, DEFAULT
}

internal data class ResolvedTarget(
    val percent: Int, val source: TargetSource
)

internal class ChargingTargetResolver(
    private val preferences: SharedPreferences,
    private val chargingLimitProvider: ChargingLimitProvider
) {
    fun resolveChargingTarget(): ResolvedTarget {
        val mode = preferences.getString(
            SettingsContract.KEY_CHARGING_TARGET_MODE,
            SettingsContract.CHARGING_TARGET_MODE_AUTOMATIC
        )
        if (mode == SettingsContract.CHARGING_TARGET_MODE_CUSTOM) {
            return ResolvedTarget(
                preferences.getInt(
                    SettingsContract.KEY_CUSTOM_CHARGING_TARGET,
                    SettingsContract.DEFAULT_CUSTOM_CHARGING_TARGET
                ).coerceIn(1, 100), TargetSource.CUSTOM
            )
        }

        return resolveDetectedChargingTarget(chargingLimitProvider.readState())
    }

    fun resolveDischargingTarget(): ResolvedTarget = ResolvedTarget(
        preferences.getInt(
            SettingsContract.KEY_DISCHARGING_TARGET, SettingsContract.DEFAULT_DISCHARGING_TARGET
        ).coerceIn(0, 99), TargetSource.CUSTOM
    )
}

internal fun resolveDetectedChargingTarget(state: ChargingLimitState): ResolvedTarget =
    if (state is ChargingLimitState.Fixed && state.isValid) {
        ResolvedTarget(state.effectivePercent, TargetSource.DEVICE)
    } else {
        ResolvedTarget(100, TargetSource.DEFAULT)
    }
