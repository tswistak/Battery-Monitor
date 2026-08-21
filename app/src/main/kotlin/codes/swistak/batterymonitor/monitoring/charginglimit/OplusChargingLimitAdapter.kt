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

internal object OplusChargingLimitAdapter : ChargingLimitAdapter {
    override val id: String = "oplus"

    private const val REGULAR_KEY = "regular_charge_protection_switch_state"
    private const val SMART_KEY = "smart_charge_protection_switch_state"
    private val MANUFACTURERS = setOf("oneplus", "oppo", "realme")

    override fun supports(profile: DeviceProfile): Boolean =
        profile.manufacturer.lowercase() in MANUFACTURERS

    override fun readState(
        profile: DeviceProfile,
        settings: SettingReader,
        battery: BatteryChargingStateReader,
        powerSupply: PowerSupplyReader
    ): ChargingLimitState {
        val regularRead = settings.read(SettingNamespace.SYSTEM, REGULAR_KEY)
        val smartRead = settings.read(SettingNamespace.SYSTEM, SMART_KEY)
        if (regularRead is ReadResult.Failed) return unavailableSettingRead(id, regularRead)
        if (smartRead is ReadResult.Failed) return unavailableSettingRead(id, smartRead)

        val regular = (regularRead as? ReadResult.Value)?.value
        val smart = (smartRead as? ReadResult.Value)?.value
        if (regular !in setOf(null, "0", "1")) return unknownValue(regular.orEmpty())
        if (smart !in setOf(null, "0", "1")) return unknownValue(smart.orEmpty())

        val evidence = chargingLimitEvidence(
            ChargingLimitEvidenceKind.CONFIG_SETTING, regularRead, smartRead
        )
        return when {
            regular == "1" && smart == "1" -> ChargingLimitState.Unavailable(
                ChargingLimitUnavailableReason.CONFLICTING_SIGNALS, id
            )

            regular == "1" -> ChargingLimitState.Fixed(80, evidence = evidence)
            smart == "1" -> ChargingLimitState.NoFixedLimit(
                NoFixedLimitKind.ADAPTIVE, evidence
            )

            regular != null || smart != null -> ChargingLimitState.NoFixedLimit(
                NoFixedLimitKind.DISABLED, evidence
            )

            else -> ChargingLimitState.Unavailable(
                ChargingLimitUnavailableReason.SETTING_ABSENT, id
            )
        }
    }

    private fun unknownValue(value: String): ChargingLimitState.Unavailable =
        ChargingLimitState.Unavailable(
            ChargingLimitUnavailableReason.UNKNOWN_VALUE, id, value
        )
}
