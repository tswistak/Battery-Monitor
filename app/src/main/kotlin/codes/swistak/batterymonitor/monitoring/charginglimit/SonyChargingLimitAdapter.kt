/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring.charginglimit

internal object SonyChargingLimitAdapter : ChargingLimitAdapter {
    override val id: String = "sony_lrc"

    private val NODE_FAMILIES = listOf("battery", "battery_ext")

    override fun supports(profile: DeviceProfile): Boolean =
        profile.manufacturer.equals("Sony", ignoreCase = true)

    override fun readState(
        profile: DeviceProfile,
        settings: SettingReader,
        battery: BatteryChargingStateReader,
        powerSupply: PowerSupplyReader
    ): ChargingLimitState {
        val familyReads = NODE_FAMILIES.map { it to powerSupply.read("$it/lrc_enable") }
        val activeFamily = familyReads.firstOrNull {
            (it.second as? ReadResult.Value)?.value == "1"
        } ?: familyReads.firstOrNull { it.second is ReadResult.Value }
        ?: return unavailableSettingRead(id, familyReads.first().second)
        val enabledRead = activeFamily.second as ReadResult.Value
        return when (enabledRead.value) {
            "0" -> ChargingLimitState.NoFixedLimit(
                NoFixedLimitKind.DISABLED,
                chargingLimitEvidence(ChargingLimitEvidenceKind.HARDWARE_STATE, enabledRead)
            )

            "1" -> readEnabled(activeFamily.first, enabledRead, powerSupply)
            else -> ChargingLimitState.Unavailable(
                ChargingLimitUnavailableReason.UNKNOWN_VALUE,
                id,
                "${activeFamily.first}/lrc_enable=${enabledRead.value}"
            )
        }
    }

    private fun readEnabled(
        family: String, enabled: ReadResult.Value<String>, powerSupply: PowerSupplyReader
    ): ChargingLimitState {
        val maximum = powerSupply.read("$family/lrc_socmax")
        val percent = (maximum as? ReadResult.Value)?.value?.toIntOrNull()
            ?: return unavailableSettingRead(id, maximum)
        return ChargingLimitState.Fixed(
            configuredPercent = percent, evidence = chargingLimitEvidence(
                ChargingLimitEvidenceKind.HARDWARE_STATE, enabled, maximum
            )
        )
    }
}
