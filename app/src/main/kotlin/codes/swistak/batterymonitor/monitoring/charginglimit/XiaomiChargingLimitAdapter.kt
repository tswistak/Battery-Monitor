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

internal object XiaomiChargingLimitAdapter : ChargingLimitAdapter {
    override val id: String = "xiaomi"

    private const val MODE_KEY = "security_pc_secure_protect_mode_key"

    override fun supports(profile: DeviceProfile): Boolean =
        profile.manufacturer.equals("Xiaomi", ignoreCase = true)

    override fun readState(
        profile: DeviceProfile,
        settings: SettingReader,
        battery: BatteryChargingStateReader,
        powerSupply: PowerSupplyReader
    ): ChargingLimitState {
        val mode = settings.read(SettingNamespace.SECURE, MODE_KEY)
        return when (mode) {
            is ReadResult.Value -> when (mode.value) {
                "0" -> noFixedLimit(NoFixedLimitKind.DISABLED, mode)
                "1" -> noFixedLimit(NoFixedLimitKind.ADAPTIVE, mode)
                "2" -> ChargingLimitState.Fixed(
                    80, evidence = chargingLimitEvidence(
                        ChargingLimitEvidenceKind.CONFIG_SETTING, mode
                    )
                )

                else -> unknownValue(mode.value)
            }

            else -> unavailableSettingRead(id, mode)
        }
    }

    private fun noFixedLimit(
        kind: NoFixedLimitKind, read: ReadResult<*>
    ): ChargingLimitState.NoFixedLimit = ChargingLimitState.NoFixedLimit(
        kind, chargingLimitEvidence(ChargingLimitEvidenceKind.CONFIG_SETTING, read)
    )

    private fun unknownValue(value: String): ChargingLimitState.Unavailable =
        ChargingLimitState.Unavailable(
            ChargingLimitUnavailableReason.UNKNOWN_VALUE, id, value
        )
}
