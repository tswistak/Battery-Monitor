/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring.charginglimit

internal object GrapheneOsChargingLimitAdapter : ChargingLimitAdapter {
    override val id: String = "grapheneos"

    private const val LIMIT_KEY = "battery_charge_limit"

    override fun supports(profile: DeviceProfile): Boolean = profile.hasGrapheneOsSystemPackage

    override fun readState(
        profile: DeviceProfile,
        settings: SettingReader,
        battery: BatteryChargingStateReader,
        powerSupply: PowerSupplyReader
    ): ChargingLimitState {
        return when (val limit = settings.read(SettingNamespace.GLOBAL, LIMIT_KEY)) {
            is ReadResult.Value -> when (limit.value) {
                "1" -> ChargingLimitState.Fixed(
                    80, evidence = chargingLimitEvidence(
                        ChargingLimitEvidenceKind.CONFIG_SETTING, limit
                    )
                )

                "0" -> disabled(limit)
                else -> ChargingLimitState.Unavailable(
                    ChargingLimitUnavailableReason.UNKNOWN_VALUE, id, limit.value
                )
            }

            is ReadResult.Absent -> disabled(limit)
            is ReadResult.Failed -> unavailableSettingRead(id, limit)
        }
    }

    private fun disabled(read: ReadResult<*>): ChargingLimitState.NoFixedLimit =
        ChargingLimitState.NoFixedLimit(
            NoFixedLimitKind.DISABLED,
            chargingLimitEvidence(ChargingLimitEvidenceKind.CONFIG_SETTING, read)
        )
}
