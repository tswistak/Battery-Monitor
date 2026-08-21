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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingTargetsTest {
    @Test
    fun `direct setting value avoids privileged access`() {
        var calls = 0
        val direct = value("1")

        val result = readSettingWithPrivilegedFallback(
            direct, true, SettingNamespace.GLOBAL, "protect_battery"
        ) {
            calls++
            "0"
        }

        assertEquals(direct, result)
        assertEquals(0, calls)
    }

    @Test
    fun `privileged reader distinguishes absent key from failed command`() {
        val absent = readSettingWithPrivilegedFallback(
            absent(), true, SettingNamespace.GLOBAL, "protect_battery"
        ) { "null" }
        val failed = readSettingWithPrivilegedFallback(
            absent(), true, SettingNamespace.GLOBAL, "protect_battery"
        ) { null }

        assertEquals(ReadResult.Absent(ReadSource.PRIVILEGED_SHELL), absent)
        assertEquals(
            ReadResult.Failed(
                ReadFailureReason.COMMAND_FAILED, ReadSource.PRIVILEGED_SHELL
            ), failed
        )
    }

    @Test
    fun `disabled privileged access preserves direct read result`() {
        var calls = 0
        val direct = absent()

        val result = readSettingWithPrivilegedFallback(
            direct, false, SettingNamespace.SECURE, "charge_optimization_mode"
        ) {
            calls++
            "1"
        }

        assertEquals(direct, result)
        assertEquals(0, calls)
    }

    @Test
    fun `Lineage privileged query output distinguishes value absence and invalid output`() {
        val value = parsePrivilegedSettingOutput(
            "Row: 0 value=80", SettingNamespace.LINEAGE_SYSTEM
        )
        val absent = parsePrivilegedSettingOutput(
            "No result found.", SettingNamespace.LINEAGE_SYSTEM
        )
        val invalid = parsePrivilegedSettingOutput(
            "unexpected", SettingNamespace.LINEAGE_SYSTEM
        )

        assertEquals(ReadResult.Value("80", ReadSource.PRIVILEGED_SHELL), value)
        assertEquals(ReadResult.Absent(ReadSource.PRIVILEGED_SHELL), absent)
        assertEquals(
            ReadResult.Failed(
                ReadFailureReason.INVALID_OUTPUT, ReadSource.PRIVILEGED_SHELL
            ), invalid
        )
    }

    @Test
    fun `Lineage detects fixed limit and scheduled modes independently of version`() {
        val fixed = detect(
            lineageProfile("20.0-20231021-UNOFFICIAL-oneplus2"),
            LineageOsChargingLimitAdapter,
            settings = lineageSettings(enabled = "1", mode = "3", limit = "80")
        )
        val scheduled = detect(
            lineageProfile("99.0-future"),
            LineageOsChargingLimitAdapter,
            settings = lineageSettings(enabled = "1", mode = "2", limit = "80")
        )

        assertFixed(80, fixed)
        assertNoFixedLimit(NoFixedLimitKind.SCHEDULED, scheduled)
    }

    @Test
    fun `Lineage disabled mode does not report a fixed limit`() {
        val disabled = detect(
            lineageProfile("20.0"),
            LineageOsChargingLimitAdapter,
            settings = lineageSettings(enabled = "0", mode = "3", limit = "80")
        )
        assertNoFixedLimit(NoFixedLimitKind.DISABLED, disabled)
    }

    @Test
    fun `Lineage system feature works on an unknown version and requires its provider`() {
        val supported = DeviceProfile(
            "Example",
            "Future",
            "future",
            99,
            hasLineageOsFeature = true,
            hasLineageSettingsProvider = true
        )
        val missingProvider = supported.copy(hasLineageSettingsProvider = false)

        assertFixed(
            90, detect(
                supported, LineageOsChargingLimitAdapter, settings = lineageSettings("1", "3", "90")
            )
        )
        assertUnavailable(
            ChargingLimitUnavailableReason.SETTING_ABSENT,
            detect(missingProvider, LineageOsChargingLimitAdapter)
        )
        assertTrue(
            LineageOsChargingLimitAdapter.supports(
                DeviceProfile(
                    "Example", "Derivative", "future", 99, hasLineageSettingsProvider = true
                )
            )
        )
    }

    @Test
    fun `Graphene detection precedes Pixel and supports future Android versions`() {
        val profile = DeviceProfile(
            "Google", "Future Pixel", "future", 99, hasGrapheneOsSystemPackage = true
        )

        val state = ChargingLimitDetector(
            profile,
            settings(SettingNamespace.GLOBAL, "battery_charge_limit", value("1")),
            noBattery(),
            noPowerSupply(),
            listOf(GrapheneOsChargingLimitAdapter, PixelChargingLimitAdapter)
        ).readState()

        assertFixed(80, state)
        assertEquals(GrapheneOsChargingLimitAdapter, DEFAULT_CHARGING_LIMIT_ADAPTERS.first())
    }

    @Test
    fun `Graphene absent setting means its default disabled state`() {
        val profile = DeviceProfile(
            "Google", "Pixel", "pixel", 35, hasGrapheneOsSystemPackage = true
        )
        assertNoFixedLimit(
            NoFixedLimitKind.DISABLED, detect(profile, GrapheneOsChargingLimitAdapter)
        )
    }

    @Test
    fun `Pixel adapter support is independent of model and Android version`() {
        assertTrue(
            PixelChargingLimitAdapter.supports(
                DeviceProfile("Google", "Pixel 6", "oriole", 31)
            )
        )
        assertTrue(
            PixelChargingLimitAdapter.supports(
                DeviceProfile("Google", "Future Pixel", "future", 99)
            )
        )
        assertFalse(PixelChargingLimitAdapter.supports(genericProfile()))
    }

    @Test
    fun `Pixel plugged long life state is fixed eighty with hardware evidence`() {
        val state = detect(
            pixelProfile(),
            PixelChargingLimitAdapter,
            battery = battery(plugged = true, chargingState = 4)
        )

        assertFixed(80, state)
        assertEquals(
            ChargingLimitEvidenceKind.HARDWARE_STATE,
            (state as ChargingLimitState.Fixed).evidence.kind
        )
    }

    @Test
    fun `Pixel ignores stale long life state while unplugged`() {
        val state = detect(
            pixelProfile(),
            PixelChargingLimitAdapter,
            battery = battery(plugged = false, chargingState = 4)
        )

        assertUnavailable(ChargingLimitUnavailableReason.SETTING_ABSENT, state)
    }

    @Test
    fun `Pixel setting detects fixed and adaptive modes`() {
        val fixed = detect(
            pixelProfile(),
            PixelChargingLimitAdapter,
            settings = settings(SettingNamespace.SECURE, "charge_optimization_mode", value("1"))
        )
        val adaptive = detect(
            pixelProfile(), PixelChargingLimitAdapter, settings = settings(
                SettingNamespace.SECURE,
                "charge_optimization_mode",
                value("0"),
                SettingNamespace.SECURE,
                "adaptive_charging_enabled",
                value("1")
            )
        )

        assertFixed(80, fixed)
        assertNoFixedLimit(NoFixedLimitKind.ADAPTIVE, adaptive)
    }

    @Test
    fun `Samsung uses a valid live threshold independently of One UI version`() {
        val state = detect(
            samsungProfile(), SamsungChargingLimitAdapter, settings = settings(
                SettingNamespace.GLOBAL,
                "protect_battery",
                value("1"),
                SettingNamespace.GLOBAL,
                "battery_protection_threshold",
                value("75")
            )
        )

        assertFixed(75, state)
    }

    @Test
    fun `Samsung does not guess when the live threshold is absent or fails`() {
        val absent = detect(
            samsungProfile(), SamsungChargingLimitAdapter, settings = settings(
                SettingNamespace.GLOBAL,
                "protect_battery",
                value("1"),
                SettingNamespace.GLOBAL,
                "battery_protection_threshold",
                absent(ReadSource.PRIVILEGED_SHELL)
            )
        )
        val failed = detect(
            samsungProfile(), SamsungChargingLimitAdapter, settings = settings(
                SettingNamespace.GLOBAL,
                "protect_battery",
                value("1"),
                SettingNamespace.GLOBAL,
                "battery_protection_threshold",
                failed()
            )
        )

        assertUnavailable(ChargingLimitUnavailableReason.SETTING_ABSENT, absent)
        assertUnavailable(ChargingLimitUnavailableReason.READ_FAILED, failed)
    }

    @Test
    fun `Samsung mode three is no fixed limit rather than disabled`() {
        val state = detect(
            samsungProfile(),
            SamsungChargingLimitAdapter,
            settings = settings(SettingNamespace.GLOBAL, "protect_battery", value("3"))
        )

        assertNoFixedLimit(NoFixedLimitKind.PAUSE_AT_FULL, state)
    }

    @Test
    fun `Xiaomi detection uses setting signal independently of device and OS version`() {
        val setting = settings(
            SettingNamespace.SECURE, "security_pc_secure_protect_mode_key", value("2")
        )
        val current = detect(
            xiaomiProfile("tanzanite", 35), XiaomiChargingLimitAdapter, settings = setting
        )
        val future = detect(
            xiaomiProfile("future", 99), XiaomiChargingLimitAdapter, settings = setting
        )

        assertFixed(80, current)
        assertFixed(80, future)
    }

    @Test
    fun `Xiaomi absent and failed reads are not treated as adaptive defaults`() {
        val absent = detect(
            xiaomiProfile(), XiaomiChargingLimitAdapter
        )
        val failed = detect(
            xiaomiProfile(), XiaomiChargingLimitAdapter, settings = settings(
                SettingNamespace.SECURE, "security_pc_secure_protect_mode_key", failed()
            )
        )

        assertUnavailable(ChargingLimitUnavailableReason.SETTING_ABSENT, absent)
        assertUnavailable(ChargingLimitUnavailableReason.READ_FAILED, failed)
    }

    @Test
    fun `Oplus detection uses setting signals independently of ROM version`() {
        val regular = detect(
            oplusProfile(), OplusChargingLimitAdapter, settings = oplusSettings("1", "0")
        )
        val adaptive = detect(
            oplusProfile(), OplusChargingLimitAdapter, settings = oplusSettings("0", "1")
        )
        val conflict = detect(
            oplusProfile(), OplusChargingLimitAdapter, settings = oplusSettings("1", "1")
        )
        val future = detect(
            oplusProfile(sdkInt = 37), OplusChargingLimitAdapter, settings = oplusSettings("1", "0")
        )

        assertFixed(80, regular)
        assertFixed(80, future)
        assertNoFixedLimit(NoFixedLimitKind.ADAPTIVE, adaptive)
        assertUnavailable(ChargingLimitUnavailableReason.CONFLICTING_SIGNALS, conflict)
    }

    @Test
    fun `detector rejects invalid adapter percentage instead of clamping`() {
        val invalidAdapter = object : ChargingLimitAdapter {
            override val id = "invalid"
            override fun supports(profile: DeviceProfile) = true
            override fun readState(
                profile: DeviceProfile,
                settings: SettingReader,
                battery: BatteryChargingStateReader,
                powerSupply: PowerSupplyReader
            ): ChargingLimitState = ChargingLimitState.Fixed(
                150, evidence = ChargingLimitEvidence(
                    ChargingLimitEvidenceKind.CONFIG_SETTING, emptySet()
                )
            )
        }

        val state = detect(genericProfile(), invalidAdapter)

        assertUnavailable(ChargingLimitUnavailableReason.INVALID_PERCENT, state)
    }

    @Test
    fun `detector rejects suspicious OEM percentage below sixty`() {
        val state = detect(genericProfile(), fixedAdapter("suspicious", 59))
        assertUnavailable(ChargingLimitUnavailableReason.INVALID_PERCENT, state)
    }

    @Test
    fun `standard end threshold only corroborates an existing configured limit`() {
        val state = detect(
            genericProfile(), fixedAdapter("configured", 80), powerSupply = powerSupply(
                "battery/charge_control_end_threshold" to value("80", ReadSource.APP_FILE)
            )
        )

        assertFixed(80, state)
        assertEquals(
            ChargingLimitEvidenceKind.SETTING_AND_HARDWARE,
            (state as ChargingLimitState.Fixed).evidence.kind
        )
    }

    @Test
    fun `Sony only uses lrc maximum while lrc is enabled`() {
        val profile = DeviceProfile("Sony", "Xperia", "xperia", 99)
        val disabled = detect(
            profile,
            SonyChargingLimitAdapter,
            powerSupply = powerSupply("battery/lrc_enable" to value("0", ReadSource.APP_FILE))
        )
        val fixed = detect(
            profile, SonyChargingLimitAdapter, powerSupply = powerSupply(
                "battery/lrc_enable" to value("1", ReadSource.APP_FILE),
                "battery/lrc_socmax" to value("90", ReadSource.APP_FILE)
            )
        )

        assertNoFixedLimit(NoFixedLimitKind.DISABLED, disabled)
        assertFixed(90, fixed)
    }

    @Test
    fun `resolver rejects invalid fixed percentage instead of clamping it`() {
        val state = ChargingLimitState.Fixed(
            150, evidence = ChargingLimitEvidence(
                ChargingLimitEvidenceKind.CONFIG_SETTING, emptySet()
            )
        )

        val target = resolveDetectedChargingTarget(state)

        assertEquals(100, target.percent)
        assertEquals(TargetSource.DEFAULT, target.source)
    }

    @Test
    fun `detector honors adapter order for future custom ROM overrides`() {
        val romAdapter = fixedAdapter("rom", 90)
        val oemAdapter = fixedAdapter("oem", 80)
        val state = ChargingLimitDetector(
            genericProfile(),
            FakeSettingReader(),
            noBattery(),
            noPowerSupply(),
            listOf(romAdapter, oemAdapter)
        ).readState()

        assertFixed(90, state)
    }

    private fun detect(
        profile: DeviceProfile,
        adapter: ChargingLimitAdapter,
        settings: SettingReader = FakeSettingReader(),
        battery: BatteryChargingStateReader = noBattery(),
        powerSupply: PowerSupplyReader = noPowerSupply()
    ): ChargingLimitState = ChargingLimitDetector(
        profile, settings, battery, powerSupply, listOf(adapter)
    ).readState()

    private fun pixelProfile(): DeviceProfile = DeviceProfile(
        manufacturer = "Google", model = "Pixel 8", device = "shiba", sdkInt = 35
    )

    private fun lineageProfile(version: String): DeviceProfile = DeviceProfile(
        manufacturer = "OnePlus",
        model = "ONE A2003",
        device = "OnePlus2",
        sdkInt = 33,
        properties = mapOf(
            ChargingLimitPropertyKeys.LINEAGE_VERSION to value(version, ReadSource.APP_SHELL)
        )
    )

    private fun samsungProfile(): DeviceProfile = DeviceProfile(
        manufacturer = "Samsung", model = "Galaxy", device = "galaxy", sdkInt = 35
    )

    private fun xiaomiProfile(device: String = "xiaomi", sdkInt: Int = 35): DeviceProfile =
        DeviceProfile(
            manufacturer = "Xiaomi", model = "Xiaomi", device = device, sdkInt = sdkInt
        )

    private fun oplusProfile(sdkInt: Int = 35): DeviceProfile = DeviceProfile(
        manufacturer = "OnePlus", model = "OnePlus", device = "oneplus", sdkInt = sdkInt
    )

    private fun genericProfile(): DeviceProfile = DeviceProfile(
        manufacturer = "Example", model = "Example", device = "example", sdkInt = 35
    )

    private fun settings(vararg entries: Any): SettingReader {
        val values = mutableMapOf<Pair<SettingNamespace, String>, ReadResult<String>>()
        var index = 0
        while (index < entries.size) {
            val namespace = entries[index] as SettingNamespace
            val key = entries[index + 1] as String

            @Suppress("UNCHECKED_CAST") val result = entries[index + 2] as ReadResult<String>
            values[namespace to key] = result
            index += 3
        }
        return FakeSettingReader(values)
    }

    private fun oplusSettings(regular: String, smart: String): SettingReader = settings(
        SettingNamespace.SYSTEM,
        "regular_charge_protection_switch_state",
        value(regular),
        SettingNamespace.SYSTEM,
        "smart_charge_protection_switch_state",
        value(smart)
    )

    private fun lineageSettings(enabled: String, mode: String, limit: String): SettingReader =
        settings(
            SettingNamespace.LINEAGE_SYSTEM,
            "charging_control_enabled",
            value(enabled),
            SettingNamespace.LINEAGE_SYSTEM,
            "charging_control_mode",
            value(mode),
            SettingNamespace.LINEAGE_SYSTEM,
            "charging_control_charging_limit",
            value(limit)
        )

    private fun battery(
        plugged: Boolean, chargingState: Int
    ): BatteryChargingStateReader = BatteryChargingStateReader {
        ReadResult.Value(
            BatteryChargingSnapshot(plugged, chargingState), ReadSource.SYSTEM_API
        )
    }

    private fun noBattery(): BatteryChargingStateReader = BatteryChargingStateReader {
        ReadResult.Failed(ReadFailureReason.NO_DATA, ReadSource.SYSTEM_API)
    }

    private fun noPowerSupply(): PowerSupplyReader = PowerSupplyReader {
        ReadResult.Absent(ReadSource.APP_FILE)
    }

    private fun powerSupply(vararg entries: Pair<String, ReadResult<String>>): PowerSupplyReader {
        val values = entries.toMap()
        return PowerSupplyReader { path -> values[path] ?: ReadResult.Absent(ReadSource.APP_FILE) }
    }

    private fun fixedAdapter(id: String, percent: Int): ChargingLimitAdapter =
        object : ChargingLimitAdapter {
            override val id = id
            override fun supports(profile: DeviceProfile) = true
            override fun readState(
                profile: DeviceProfile,
                settings: SettingReader,
                battery: BatteryChargingStateReader,
                powerSupply: PowerSupplyReader
            ): ChargingLimitState = ChargingLimitState.Fixed(
                percent, evidence = ChargingLimitEvidence(
                    ChargingLimitEvidenceKind.CONFIG_SETTING, emptySet()
                )
            )
        }

    private fun value(
        value: String, source: ReadSource = ReadSource.CONTENT_RESOLVER
    ): ReadResult.Value<String> = ReadResult.Value(value, source)

    private fun absent(
        source: ReadSource = ReadSource.CONTENT_RESOLVER
    ): ReadResult.Absent = ReadResult.Absent(source)

    private fun failed(): ReadResult.Failed = ReadResult.Failed(
        ReadFailureReason.COMMAND_FAILED, ReadSource.PRIVILEGED_SHELL
    )

    private fun assertFixed(percent: Int, state: ChargingLimitState) {
        assertTrue("Expected Fixed but was $state", state is ChargingLimitState.Fixed)
        assertEquals(percent, (state as ChargingLimitState.Fixed).effectivePercent)
    }

    private fun assertNoFixedLimit(kind: NoFixedLimitKind, state: ChargingLimitState) {
        assertTrue("Expected NoFixedLimit but was $state", state is ChargingLimitState.NoFixedLimit)
        assertEquals(kind, (state as ChargingLimitState.NoFixedLimit).kind)
    }

    private fun assertUnavailable(
        reason: ChargingLimitUnavailableReason, state: ChargingLimitState
    ) {
        assertTrue("Expected Unavailable but was $state", state is ChargingLimitState.Unavailable)
        assertEquals(reason, (state as ChargingLimitState.Unavailable).reason)
    }

    private class FakeSettingReader(
        private val values: Map<Pair<SettingNamespace, String>, ReadResult<String>> = emptyMap()
    ) : SettingReader {
        override fun read(namespace: SettingNamespace, key: String): ReadResult<String> =
            values[namespace to key] ?: ReadResult.Absent(ReadSource.CONTENT_RESOLVER)
    }
}
