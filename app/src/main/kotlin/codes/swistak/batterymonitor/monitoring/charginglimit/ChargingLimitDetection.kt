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

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.SystemClock
import codes.swistak.batterymonitor.privileged.PrivilegedAccess

internal enum class ReadSource {
    CONTENT_RESOLVER, APP_FILE, PRIVILEGED_SHELL, SYSTEM_API
}

internal enum class ReadFailureReason {
    ACCESS_DENIED, COMMAND_FAILED, INVALID_OUTPUT, NO_DATA, EXCEPTION
}

internal sealed interface ReadResult<out T> {
    data class Value<T>(val value: T, val source: ReadSource) : ReadResult<T>
    data class Absent(val source: ReadSource) : ReadResult<Nothing>
    data class Failed(
        val reason: ReadFailureReason, val source: ReadSource? = null
    ) : ReadResult<Nothing>
}

internal enum class ChargingLimitEvidenceKind {
    CONFIG_SETTING, HARDWARE_STATE, SETTING_AND_HARDWARE
}

internal data class ChargingLimitEvidence(
    val kind: ChargingLimitEvidenceKind, val sources: Set<ReadSource>
)

internal enum class NoFixedLimitKind {
    DISABLED, ADAPTIVE, SCHEDULED, PAUSE_AT_FULL
}

internal enum class ChargingLimitUnavailableReason {
    UNSUPPORTED_DEVICE, SETTING_ABSENT, READ_FAILED, UNKNOWN_VALUE, CONFLICTING_SIGNALS, INVALID_PERCENT
}

internal sealed interface ChargingLimitState {
    data class Fixed(
        val configuredPercent: Int,
        val effectivePercent: Int = configuredPercent,
        val evidence: ChargingLimitEvidence
    ) : ChargingLimitState {
        val isValid: Boolean
            get() = configuredPercent in MIN_PLAUSIBLE_OEM_PERCENT..100 && effectivePercent in MIN_PLAUSIBLE_OEM_PERCENT..100
    }

    data class NoFixedLimit(
        val kind: NoFixedLimitKind, val evidence: ChargingLimitEvidence
    ) : ChargingLimitState

    data class Unavailable(
        val reason: ChargingLimitUnavailableReason,
        val adapterId: String? = null,
        val detail: String? = null
    ) : ChargingLimitState
}

internal data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val device: String,
    val sdkInt: Int,
    val hasLineageSettingsProvider: Boolean = false,
    val hasGrapheneOsSystemPackage: Boolean = false
)

internal data class BatteryChargingSnapshot(
    val plugged: Boolean, val chargingState: Int
)

internal enum class SettingNamespace(val commandName: String) {
    SECURE("secure"), GLOBAL("global"), SYSTEM("system"), LINEAGE_SYSTEM("lineage_system")
}

internal fun interface SettingReader {
    fun read(namespace: SettingNamespace, key: String): ReadResult<String>
}

internal fun interface BatteryChargingStateReader {
    fun read(): ReadResult<BatteryChargingSnapshot>
}

internal fun interface PowerSupplyReader {
    fun read(relativePath: String): ReadResult<String>
}

internal interface ChargingLimitAdapter {
    val id: String
    fun supports(profile: DeviceProfile): Boolean
    fun readState(
        profile: DeviceProfile,
        settings: SettingReader,
        battery: BatteryChargingStateReader,
        powerSupply: PowerSupplyReader
    ): ChargingLimitState
}

internal fun chargingLimitEvidence(
    kind: ChargingLimitEvidenceKind, vararg reads: ReadResult<*>
): ChargingLimitEvidence = ChargingLimitEvidence(
    kind, reads.mapNotNullTo(linkedSetOf()) { read ->
        when (read) {
            is ReadResult.Value -> read.source
            is ReadResult.Absent -> read.source
            is ReadResult.Failed -> read.source
        }
    })

internal fun unavailableSettingRead(
    adapterId: String, read: ReadResult<*>
): ChargingLimitState.Unavailable = when (read) {
    is ReadResult.Absent -> ChargingLimitState.Unavailable(
        ChargingLimitUnavailableReason.SETTING_ABSENT, adapterId
    )

    is ReadResult.Failed -> ChargingLimitState.Unavailable(
        ChargingLimitUnavailableReason.READ_FAILED, adapterId, read.reason.name
    )

    is ReadResult.Value -> ChargingLimitState.Unavailable(
        ChargingLimitUnavailableReason.UNKNOWN_VALUE, adapterId
    )
}

internal class ChargingLimitDetector(
    private val profile: DeviceProfile,
    private val settings: SettingReader,
    private val battery: BatteryChargingStateReader,
    private val powerSupply: PowerSupplyReader,
    private val adapters: List<ChargingLimitAdapter>
) {
    fun readState(): ChargingLimitState {
        val adapter =
            adapters.firstOrNull { it.supports(profile) } ?: return ChargingLimitState.Unavailable(
                ChargingLimitUnavailableReason.UNSUPPORTED_DEVICE
            )
        return try {
            adapter.readState(profile, settings, battery, powerSupply)
                .corroboratedByStandardThreshold(powerSupply).validated(adapter.id)
        } catch (error: Throwable) {
            ChargingLimitState.Unavailable(
                ChargingLimitUnavailableReason.READ_FAILED, adapter.id, error.javaClass.simpleName
            )
        }
    }

    private fun ChargingLimitState.corroboratedByStandardThreshold(
        powerSupply: PowerSupplyReader
    ): ChargingLimitState {
        if (this !is ChargingLimitState.Fixed || evidence.kind != ChargingLimitEvidenceKind.CONFIG_SETTING) return this
        val corroboratingRead = STANDARD_THRESHOLD_CANDIDATES.asSequence().map(powerSupply::read)
            .filterIsInstance<ReadResult.Value<String>>()
            .firstOrNull { it.value.toIntOrNull() == effectivePercent } ?: return this
        return copy(
            evidence = ChargingLimitEvidence(
                ChargingLimitEvidenceKind.SETTING_AND_HARDWARE,
                evidence.sources + corroboratingRead.source
            )
        )
    }

    private fun ChargingLimitState.validated(adapterId: String): ChargingLimitState {
        return if (this is ChargingLimitState.Fixed && !isValid) {
            ChargingLimitState.Unavailable(
                ChargingLimitUnavailableReason.INVALID_PERCENT,
                adapterId,
                "$configuredPercent/$effectivePercent"
            )
        } else {
            this
        }
    }
}

internal class DeviceChargingLimitProvider(
    context: Context,
    private val privilegedAccessEnabled: () -> Boolean = { false },
    privilegedCommand: (String) -> String? = PrivilegedAccess::run,
    private val profile: DeviceProfile = AndroidDeviceProfile.current(context),
    private val adapters: List<ChargingLimitAdapter> = DEFAULT_CHARGING_LIMIT_ADAPTERS,
    private val cacheTtlMillis: Long = CHARGING_LIMIT_CACHE_MS,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime
) : ChargingLimitProvider {
    private val packageManager = context.applicationContext.packageManager
    private val settings = AndroidSettingReader(context, privilegedAccessEnabled, privilegedCommand)
    private val battery = AndroidBatteryChargingStateReader(context)
    private val powerSupply = AndroidPowerSupplyReader(privilegedAccessEnabled, privilegedCommand)

    private var cached: CachedState? = null

    @Synchronized
    override fun readState(): ChargingLimitState {
        val now = elapsedRealtime()
        val access = currentAccess()
        val owner = DeviceProfileSignals.lineageProvider(packageManager)
        cached?.takeIf {
            it.access == access && it.lineageOwner == owner && now - it.readAtMillis < cacheTtlMillis
        }?.let { return it.state }

        val state = ChargingLimitDetector(
            profile.copy(hasLineageSettingsProvider = owner != null),
            settings,
            battery,
            powerSupply,
            adapters
        ).readState()
        if (currentAccess() != access || DeviceProfileSignals.lineageProvider(packageManager) != owner) {
            return ChargingLimitState.Unavailable(ChargingLimitUnavailableReason.READ_FAILED)
        }
        cached = CachedState(state, elapsedRealtime(), access, owner)
        return state
    }

    private fun currentAccess() = ChargingLimitAccess(
        privilegedAccessEnabled(),
        PrivilegedAccess.accessRevision,
        PrivilegedAccess.accessChanges.value
    )

    private data class CachedState(
        val state: ChargingLimitState,
        val readAtMillis: Long,
        val access: ChargingLimitAccess,
        val lineageOwner: LineageProviderIdentity?
    )
}

internal const val MIN_PLAUSIBLE_OEM_PERCENT = 60

private val STANDARD_THRESHOLD_CANDIDATES = listOf(
    "battery/charge_control_end_threshold", "battery_ext/charge_control_end_threshold"
)

internal val DEFAULT_CHARGING_LIMIT_ADAPTERS: List<ChargingLimitAdapter> = listOf(
    GrapheneOsChargingLimitAdapter,
    LineageOsChargingLimitAdapter,
    PixelChargingLimitAdapter,
    SamsungChargingLimitAdapter,
    XiaomiChargingLimitAdapter,
    OplusChargingLimitAdapter,
    SonyChargingLimitAdapter
)

internal object DeviceProfileSignals {
    const val LINEAGE_PROVIDER = "lineagesettings"
    const val LINEAGE_PROVIDER_PACKAGE = "org.lineageos.lineagesettings"

    val GRAPHENE_PACKAGES = listOf("app.grapheneos.setupwizard", "app.grapheneos.info")

    fun hasGrapheneSystemPackage(context: Context): Boolean = GRAPHENE_PACKAGES.any { name ->
        runCatching {
            val flags = context.packageManager.getApplicationInfo(name, 0).flags
            flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrDefault(false)
    }

    fun lineageProvider(packageManager: PackageManager): LineageProviderIdentity? = runCatching {
        val provider = packageManager.resolveContentProvider(LINEAGE_PROVIDER, 0)
        trustedLineageProvider(provider) {
            packageManager.checkSignatures(
                "android", LINEAGE_PROVIDER_PACKAGE
            ) == PackageManager.SIGNATURE_MATCH
        }
    }.getOrNull()
}

internal data class LineageProviderIdentity(val uid: Int, val sourceDir: String?)

internal fun trustedLineageProvider(
    provider: ProviderInfo?, platformSigned: () -> Boolean
): LineageProviderIdentity? {
    if (provider?.packageName != DeviceProfileSignals.LINEAGE_PROVIDER_PACKAGE) return null
    val app = provider.applicationInfo ?: return null
    if (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 || !platformSigned()) return null
    return LineageProviderIdentity(app.uid, app.sourceDir)
}
