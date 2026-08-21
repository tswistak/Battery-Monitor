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

internal object LineageOsChargingLimitAdapter : ChargingLimitAdapter {
    override val id: String = "lineageos"

    private const val ENABLED_KEY = "charging_control_enabled"
    private const val MODE_KEY = "charging_control_mode"
    private const val LIMIT_KEY = "charging_control_charging_limit"
    private const val LIMIT_MODE = "3"
    private val SCHEDULED_MODES = setOf("1", "2")

    override fun supports(profile: DeviceProfile): Boolean =
        profile.hasLineageOsFeature || profile.hasLineageSettingsProvider || profile.property(
            ChargingLimitPropertyKeys.LINEAGE_VERSION
        ) is ReadResult.Value

    override fun readState(
        profile: DeviceProfile,
        settings: SettingReader,
        battery: BatteryChargingStateReader,
        powerSupply: PowerSupplyReader
    ): ChargingLimitState {
        if (!profile.hasLineageSettingsProvider && profile.hasLineageOsFeature) {
            return ChargingLimitState.Unavailable(
                ChargingLimitUnavailableReason.SETTING_ABSENT, id, "provider"
            )
        }
        val enabledRead = settings.read(SettingNamespace.LINEAGE_SYSTEM, ENABLED_KEY)
        val enabled = readValue(enabledRead) ?: return unavailableSettingRead(id, enabledRead)
        if (enabled !in setOf("0", "1")) return unknownValue(ENABLED_KEY, enabled)
        if (enabled == "0") {
            return ChargingLimitState.NoFixedLimit(
                NoFixedLimitKind.DISABLED,
                chargingLimitEvidence(ChargingLimitEvidenceKind.CONFIG_SETTING, enabledRead)
            )
        }

        val modeRead = settings.read(SettingNamespace.LINEAGE_SYSTEM, MODE_KEY)
        val mode = readValue(modeRead) ?: return unavailableSettingRead(id, modeRead)
        if (mode in SCHEDULED_MODES) {
            return ChargingLimitState.NoFixedLimit(
                NoFixedLimitKind.SCHEDULED, chargingLimitEvidence(
                    ChargingLimitEvidenceKind.CONFIG_SETTING, enabledRead, modeRead
                )
            )
        }
        if (mode != LIMIT_MODE) return unknownValue(MODE_KEY, mode)

        val limitRead = settings.read(SettingNamespace.LINEAGE_SYSTEM, LIMIT_KEY)
        val limitValue = readValue(limitRead) ?: return unavailableSettingRead(id, limitRead)
        val limit = limitValue.toIntOrNull() ?: return unknownValue(LIMIT_KEY, limitValue)
        return ChargingLimitState.Fixed(
            configuredPercent = limit, evidence = chargingLimitEvidence(
                ChargingLimitEvidenceKind.CONFIG_SETTING, enabledRead, modeRead, limitRead
            )
        )
    }

    private fun readValue(read: ReadResult<String>): String? = (read as? ReadResult.Value)?.value

    private fun unknownValue(key: String, value: String): ChargingLimitState.Unavailable =
        ChargingLimitState.Unavailable(
            ChargingLimitUnavailableReason.UNKNOWN_VALUE, id, "$key=$value"
        )
}
