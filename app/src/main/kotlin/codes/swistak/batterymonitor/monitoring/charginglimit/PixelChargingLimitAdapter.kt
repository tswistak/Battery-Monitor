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

internal object PixelChargingLimitAdapter : ChargingLimitAdapter {
    override val id: String = "pixel"

    private const val MODE_KEY = "charge_optimization_mode"
    private const val ADAPTIVE_KEY = "adaptive_charging_enabled"
    private const val CHARGING_STATE_LONG_LIFE = 4
    private const val CHARGING_STATE_ADAPTIVE = 5

    override fun supports(profile: DeviceProfile): Boolean =
        profile.manufacturer.equals("Google", ignoreCase = true) && !profile.isGoogleEmulator()

    private fun DeviceProfile.isGoogleEmulator(): Boolean = model.startsWith(
        "sdk_gphone",
        ignoreCase = true
    ) || model.startsWith(
        "Android SDK built for",
        ignoreCase = true
    ) || device.startsWith("generic", ignoreCase = true) || device.startsWith(
        "emu",
        ignoreCase = true
    )

    override fun readState(
        profile: DeviceProfile,
        settings: SettingReader,
        battery: BatteryChargingStateReader,
        powerSupply: PowerSupplyReader
    ): ChargingLimitState {
        val batteryRead = battery.read()
        val mode = settings.read(SettingNamespace.SECURE, MODE_KEY)
        val adaptive = settings.read(SettingNamespace.SECURE, ADAPTIVE_KEY)
        val hardwareState =
            (batteryRead as? ReadResult.Value)?.value?.takeIf(BatteryChargingSnapshot::plugged)?.chargingState

        if (hardwareState == CHARGING_STATE_LONG_LIFE) {
            if (mode is ReadResult.Value && mode.value == "0") {
                return unavailableConflict()
            }
            return ChargingLimitState.Fixed(
                configuredPercent = 80, evidence = chargingLimitEvidence(
                    if (mode is ReadResult.Value && mode.value == "1") {
                        ChargingLimitEvidenceKind.SETTING_AND_HARDWARE
                    } else {
                        ChargingLimitEvidenceKind.HARDWARE_STATE
                    }, batteryRead, mode
                )
            )
        }

        if (hardwareState == CHARGING_STATE_ADAPTIVE) {
            if (mode is ReadResult.Value && mode.value == "1") {
                return unavailableConflict()
            }
            return ChargingLimitState.NoFixedLimit(
                NoFixedLimitKind.ADAPTIVE, chargingLimitEvidence(
                    if (adaptive is ReadResult.Value && adaptive.value == "1") {
                        ChargingLimitEvidenceKind.SETTING_AND_HARDWARE
                    } else {
                        ChargingLimitEvidenceKind.HARDWARE_STATE
                    }, batteryRead, adaptive
                )
            )
        }

        return when (mode) {
            is ReadResult.Value -> when (mode.value) {
                "1" -> ChargingLimitState.Fixed(
                    80, evidence = chargingLimitEvidence(
                        ChargingLimitEvidenceKind.CONFIG_SETTING, mode
                    )
                )

                "0" -> readNonFixedMode(mode, adaptive)
                else -> unknownValue(mode.value)
            }

            else -> unavailableSettingRead(id, mode)
        }
    }

    private fun readNonFixedMode(
        mode: ReadResult.Value<String>, adaptive: ReadResult<String>
    ): ChargingLimitState {
        return when (adaptive) {
            is ReadResult.Value -> when (adaptive.value) {
                "1" -> ChargingLimitState.NoFixedLimit(
                    NoFixedLimitKind.ADAPTIVE, chargingLimitEvidence(
                        ChargingLimitEvidenceKind.CONFIG_SETTING, mode, adaptive
                    )
                )

                "0" -> disabled(mode, adaptive)
                else -> unknownValue(adaptive.value)
            }

            is ReadResult.Absent -> disabled(mode, adaptive)
            is ReadResult.Failed -> unavailableSettingRead(id, adaptive)
        }
    }

    private fun disabled(vararg reads: ReadResult<*>): ChargingLimitState.NoFixedLimit =
        ChargingLimitState.NoFixedLimit(
            NoFixedLimitKind.DISABLED,
            chargingLimitEvidence(ChargingLimitEvidenceKind.CONFIG_SETTING, *reads)
        )

    private fun unavailableConflict(): ChargingLimitState.Unavailable =
        ChargingLimitState.Unavailable(
            ChargingLimitUnavailableReason.CONFLICTING_SIGNALS, id
        )

    private fun unknownValue(value: String): ChargingLimitState.Unavailable =
        ChargingLimitState.Unavailable(
            ChargingLimitUnavailableReason.UNKNOWN_VALUE, id, value
        )
}
