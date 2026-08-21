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

internal object SamsungChargingLimitAdapter : ChargingLimitAdapter {
    override val id: String = "samsung"

    private const val PROTECTION_KEY = "protect_battery"
    private const val THRESHOLD_KEY = "battery_protection_threshold"

    override fun supports(profile: DeviceProfile): Boolean =
        profile.manufacturer.equals("Samsung", ignoreCase = true)

    override fun readState(
        profile: DeviceProfile,
        settings: SettingReader,
        battery: BatteryChargingStateReader,
        powerSupply: PowerSupplyReader
    ): ChargingLimitState {
        val protect = settings.read(SettingNamespace.GLOBAL, PROTECTION_KEY)
        if (protect !is ReadResult.Value) return unavailableSettingRead(id, protect)

        return when (protect.value) {
            "0" -> noFixedLimit(NoFixedLimitKind.DISABLED, protect)
            "3" -> noFixedLimit(NoFixedLimitKind.PAUSE_AT_FULL, protect)
            "1" -> readEnabledState(settings, protect)
            else -> unknownValue(protect.value)
        }
    }

    private fun readEnabledState(
        settings: SettingReader, protect: ReadResult.Value<String>
    ): ChargingLimitState {
        return when (val threshold = settings.read(SettingNamespace.GLOBAL, THRESHOLD_KEY)) {
            is ReadResult.Value -> {
                val percent = threshold.value.toIntOrNull() ?: return unknownValue(threshold.value)
                fixed(percent, protect, threshold)
            }

            else -> unavailableSettingRead(id, threshold)
        }
    }

    private fun fixed(
        percent: Int, vararg reads: ReadResult<*>
    ): ChargingLimitState.Fixed = ChargingLimitState.Fixed(
        percent, evidence = chargingLimitEvidence(
            ChargingLimitEvidenceKind.CONFIG_SETTING, *reads
        )
    )

    private fun noFixedLimit(
        kind: NoFixedLimitKind, vararg reads: ReadResult<*>
    ): ChargingLimitState.NoFixedLimit = ChargingLimitState.NoFixedLimit(
        kind, chargingLimitEvidence(ChargingLimitEvidenceKind.CONFIG_SETTING, *reads)
    )

    private fun unknownValue(value: String): ChargingLimitState.Unavailable =
        ChargingLimitState.Unavailable(
            ChargingLimitUnavailableReason.UNKNOWN_VALUE, id, value
        )
}
