/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.diagnostics

import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import codes.swistak.batterymonitor.advancedstats.AdvancedBatteryStatsCollector
import codes.swistak.batterymonitor.diagnostics.DiagnosticsDurationFormatter
import codes.swistak.batterymonitor.monitoring.BatteryCurrent
import codes.swistak.batterymonitor.monitoring.BatteryMeasurementTime
import codes.swistak.batterymonitor.monitoring.batteryvoltage.BatteryVoltageValidator
import codes.swistak.batterymonitor.monitoring.oldestMeasurementTime
import java.util.Locale

internal data class DiagnosticRow(
    val label: Int,
    val rawKey: String,
    val value: String?,
    val rawValue: String?,
    val unit: String,
    val source: String,
    val observedAtMillis: Long,
    val accessMethod: String,
    val valueLabel: Int = 0,
    val observedElapsedMillis: Long? = null
)

internal data class DiagnosticGroup(val title: Int, val rows: List<DiagnosticRow>)

internal fun diagnosticGroups(
    snapshot: AdvancedBatterySnapshot,
    multiplier: Int,
    fahrenheit: Boolean = false,
    locale: Locale = Locale.getDefault()
): List<DiagnosticGroup> =
    normalizedDiagnosticGroups(snapshot, multiplier, fahrenheit, locale) + rawDiagnosticGroups(
        snapshot
    )

internal fun normalizedDiagnosticGroups(
    snapshot: AdvancedBatterySnapshot,
    multiplier: Int,
    fahrenheit: Boolean = false,
    locale: Locale = Locale.getDefault()
): List<DiagnosticGroup> {
    val android = snapshot.accessMethod == "Android"
    val access = diagnosticAccessName(snapshot)
    fun row(
        label: Int,
        key: String,
        raw: Any?,
        unit: String = "",
        value: String? = raw?.toString(),
        source: String? = null
    ): DiagnosticRow {
        val selectedSource = if (raw == null) "" else source ?: snapshot.fieldSources[key]
        ?: if (android) "BatteryManager / $key" else "dumpsys battery / $key"
        val time =
            snapshot.sourceReadTimes[selectedSource]?.takeIf { raw != null && it.wallMillis > 0 }
        return DiagnosticRow(
            label,
            key,
            value,
            raw?.toString(),
            unit,
            selectedSource,
            time?.wallMillis ?: 0,
            access,
            observedElapsedMillis = time?.elapsedMillis
        )
    }

    fun charge(raw: Long?) = raw?.let { String.format(locale, "%.1f mAh", it / 1000.0) }

    fun current(raw: Long?) = raw?.let {
        BatteryCurrent.formatMilliAmps(
            BatteryCurrent.scaleMicroAmps(it, multiplier), locale
        ) + " mA"
    }

    val health = snapshot.serviceLabels.indexOf("health").takeIf { it >= 0 }
        ?.let { snapshot.serviceValues.getOrNull(it)?.toIntOrNull() }
    val service = snapshot.serviceLabels.zip(snapshot.serviceValues).toMap()
    val temperature = service["temperature"]?.trim()?.toIntOrNull()
    val serviceSource = if (android) "ACTION_BATTERY_CHANGED" else "dumpsys battery"
    fun voltageReading(raw: String?, source: String, sysfs: Boolean = false): DiagnosticRow? {
        val millivolts = if (sysfs) BatteryVoltageValidator.normalizeSysfsVoltage(raw)
        else raw?.trim()?.toIntOrNull()?.takeIf(BatteryVoltageValidator::isValidBroadcastMillivolts)
        if (millivolts == null) return null
        val unit = if (sysfs && (raw?.trim()?.toLongOrNull()
                ?: 0) > BatteryVoltageValidator.MAX_PLAUSIBLE_MILLIVOLTS
        ) "µV" else "mV"
        return row(
            R.string.current_voltage,
            "voltage",
            raw,
            unit,
            String.format(locale, "%.3f V", millivolts / 1000.0),
            source
        )
    }

    val sysfs = snapshot.sysfsLabels.zip(snapshot.sysfsValues).toMap()
    val voltage = voltageReading(service["voltage"], "$serviceSource / voltage")
        ?: AdvancedBatteryStatsCollector.batterySupplyNames(sysfs).firstNotNullOfOrNull { supply ->
            voltageReading(
                sysfs["$supply/voltage_now"],
                "/sys/class/power_supply/$supply/voltage_now",
                sysfs = true
            )
        } ?: row(R.string.current_voltage, "voltage", null, "mV")
    val power = BatteryCurrent.powerWatts(
        diagnosticVoltageMillivolts(voltage),
        snapshot.currentNowUa?.let { BatteryCurrent.scaleMicroAmps(it, multiplier) })
    val currentNow = row(
        R.string.advanced_field_current_now,
        "current_now",
        snapshot.currentNowUa,
        "µA × $multiplier",
        current(snapshot.currentNowUa)
    )
    val powerTime = oldestMeasurementTime(voltage.measurementTime(), currentNow.measurementTime())
    val healthTime = oldestMeasurementTime(
        snapshot.fieldSources["charge_full"]?.let(snapshot.sourceReadTimes::get),
        snapshot.fieldSources["charge_full_design"]?.let(snapshot.sourceReadTimes::get)
    )
    return listOf(
        DiagnosticGroup(
            R.string.advanced_section_counters, listOf(
                row(
                    R.string.advanced_field_charge_counter,
                    "charge_counter",
                    snapshot.chargeCounterUah,
                    "µAh",
                    charge(snapshot.chargeCounterUah)
                ),
                currentNow,
                row(
                    R.string.advanced_field_current_average,
                    "current_average",
                    snapshot.currentAverageUa,
                    "µA × $multiplier",
                    current(snapshot.currentAverageUa)
                ),
                row(
                    R.string.advanced_field_energy_counter,
                    "energy_counter",
                    snapshot.energyCounterNwh,
                    "nWh",
                    snapshot.energyCounterNwh?.let {
                        String.format(
                            locale, "%.1f mWh", it / 1_000_000.0
                        )
                    }),
                row(R.string.advanced_field_cycle_count, "cycle_count", snapshot.cycleCount),
                voltage,
                row(
                    R.string.current_temperature,
                    "temperature",
                    temperature,
                    "0.1°C",
                    temperature?.let {
                        String.format(
                            locale,
                            if (fahrenheit) "%.1f°F" else "%.1f°C",
                            if (fahrenheit) it * 0.18 + 32 else it / 10.0
                        )
                    },
                    "$serviceSource / temperature"
                ),
                row(
                    R.string.battery_power,
                    "battery_power",
                    power,
                    "W",
                    power?.let { String.format(locale, "%.2f W", it) },
                    "${voltage.source} × ${snapshot.fieldSources["current_now"] ?: if (android) "BatteryManager / current_now" else "dumpsys battery / current_now"}"
                ).copy(
                    observedAtMillis = powerTime?.wallMillis ?: 0,
                    observedElapsedMillis = powerTime?.elapsedMillis
                )
            )
        ), DiagnosticGroup(
            R.string.advanced_section_capacity, listOf(
                row(
                    R.string.current_android_health,
                    "health",
                    health,
                    "Android status",
                    health?.let {
                        codes.swistak.batterymonitor.common.DisplayStrings.healths.getOrNull(
                            it
                        )
                    },
                    if (android) "ACTION_BATTERY_CHANGED / health" else "dumpsys battery / health"
                ), row(
                    R.string.advanced_field_reported_capacity,
                    "capacity",
                    snapshot.reportedCapacityPercent,
                    "%",
                    snapshot.reportedCapacityPercent?.let { "$it%" }), row(
                    R.string.advanced_field_state_of_health,
                    "state_of_health",
                    snapshot.stateOfHealthPercent,
                    "%",
                    snapshot.stateOfHealthPercent?.let { "$it%" }), row(
                    R.string.advanced_field_full_charge_capacity,
                    "charge_full",
                    snapshot.fullChargeUah,
                    "µAh",
                    charge(snapshot.fullChargeUah)
                ), row(
                    R.string.advanced_field_design_capacity,
                    "charge_full_design",
                    snapshot.designChargeUah,
                    "µAh",
                    charge(snapshot.designChargeUah)
                ), row(
                    R.string.advanced_field_estimated_health,
                    "estimated_health",
                    capacityHealth(snapshot),
                    "%",
                    capacityHealth(snapshot)?.let {
                        String.format(
                            locale, "%.1f%%", it
                        )
                    },
                    "${snapshot.fieldSources["charge_full"]} / ${snapshot.fieldSources["charge_full_design"]}"
                ).copy(
                    observedAtMillis = healthTime?.wallMillis ?: 0,
                    observedElapsedMillis = healthTime?.elapsedMillis
                )
            )
        ), DiagnosticGroup(
            R.string.advanced_section_charging, listOf(
                row(
                    R.string.advanced_field_charge_time_remaining,
                    "charge_time_remaining",
                    snapshot.chargeTimeRemainingMs,
                    "ms",
                    snapshot.chargeTimeRemainingMs?.takeIf { it >= 0 }
                        ?.let { DiagnosticsDurationFormatter.format(locale, it) }), row(
                    R.string.advanced_field_max_charging_current,
                    "max_charging_current",
                    snapshot.maxChargingCurrentUa,
                    "µA",
                    snapshot.maxChargingCurrentUa?.let {
                        BatteryCurrent.formatMilliAmps(
                            BatteryCurrent.scaleMicroAmps(it, 1), locale
                        ) + " mA"
                    }), row(
                    R.string.advanced_field_max_charging_voltage,
                    "max_charging_voltage",
                    snapshot.maxChargingVoltageUv,
                    "µV",
                    snapshot.maxChargingVoltageUv?.let {
                        String.format(
                            locale, "%.2f V", it / 1_000_000.0
                        )
                    }), row(
                    R.string.advanced_field_charging_policy,
                    "charging_policy",
                    snapshot.chargingPolicy,
                    value = null
                ).copy(valueLabel = chargingPolicyLabel(snapshot)), row(
                    R.string.advanced_field_charging_state,
                    "charging_state",
                    snapshot.chargingState,
                    value = null
                ).copy(valueLabel = chargingStateLabel(snapshot)), row(
                    R.string.advanced_field_capacity_level,
                    "capacity_level",
                    snapshot.capacityLevel,
                    value = null
                ).copy(valueLabel = capacityLevelLabel(snapshot.capacityLevel))
            )
        )
    )
}

internal fun rawDiagnosticGroups(snapshot: AdvancedBatterySnapshot): List<DiagnosticGroup> {
    val access = diagnosticAccessName(snapshot)
    return listOf(
        DiagnosticGroup(
            R.string.advanced_section_service, rawRows(
                snapshot.serviceLabels,
                snapshot.serviceValues,
                if (snapshot.accessMethod == "Android") "ACTION_BATTERY_CHANGED" else "dumpsys battery",
                snapshot,
                access
            )
        ), DiagnosticGroup(
            R.string.advanced_section_sysfs, rawRows(
                snapshot.sysfsLabels,
                snapshot.sysfsValues,
                "/sys/class/power_supply",
                snapshot,
                access
            )
        ), DiagnosticGroup(
            R.string.advanced_section_metadata, rawRows(
                snapshot.metadataLabels, snapshot.metadataValues, "BatteryManager", snapshot, access
            )
        )
    )
}

internal fun mergedRawDiagnosticGroups(snapshots: List<AdvancedBatterySnapshot>): List<DiagnosticGroup> =
    snapshots.flatMap(::rawDiagnosticGroups).groupBy { it.title }.map { (title, groups) ->
        DiagnosticGroup(
            title,
            groups.flatMap { it.rows }.groupBy { it.rawKey }.values.map { readings ->
                checkNotNull(latestDiagnosticReading(readings))
            })
    }

internal fun diagnosticAccessName(snapshot: AdvancedBatterySnapshot): String =
    when (snapshot.accessMethod) {
        AdvancedBatterySnapshot.ACCESS_SHIZUKU -> "Shizuku"
        AdvancedBatterySnapshot.ACCESS_ROOT -> "Root"
        else -> snapshot.accessMethod ?: "Android"
    }

internal fun diagnosticSourceName(access: String): String =
    access.split(" · ").map { if (it == "App UID") "Android" else it }.distinct()
        .joinToString(" · ")

private fun diagnosticVoltageMillivolts(row: DiagnosticRow): Int? {
    val raw = row.rawValue?.trim()?.toLongOrNull() ?: return null
    val millivolts = when (row.unit) {
        "µV" -> raw / 1000
        "mV" -> raw
        else -> return null
    }
    if (millivolts !in BatteryVoltageValidator.MIN_PLAUSIBLE_MILLIVOLTS.toLong()..BatteryVoltageValidator.MAX_PLAUSIBLE_MILLIVOLTS.toLong()) return null
    return millivolts.toInt()
}

private fun DiagnosticRow.measurementTime(): BatteryMeasurementTime? =
    observedAtMillis.takeIf { it > 0 }?.let { BatteryMeasurementTime(it, observedElapsedMillis) }

internal fun latestDiagnosticReading(rows: List<DiagnosticRow>): DiagnosticRow? {
    val monotonic =
        rows.filter { it.observedAtMillis > 0 && it.observedElapsedMillis?.let { elapsed -> elapsed >= 0 } == true }
    return if (monotonic.isNotEmpty()) monotonic.maxByOrNull { it.observedElapsedMillis!! }
    else rows.maxByOrNull { it.observedAtMillis }
}

internal fun mergedDiagnosticGroups(
    snapshots: List<AdvancedBatterySnapshot>,
    multiplier: Int,
    fahrenheit: Boolean = false,
    locale: Locale = Locale.getDefault()
): List<DiagnosticGroup> {
    val groups = snapshots.map { normalizedDiagnosticGroups(it, multiplier, fahrenheit, locale) }
    val chargeCounterApi = snapshots.filter {
        it.accessMethod in listOf(
            "Android", "App UID"
        ) && "BATTERY_PROPERTY_CHARGE_COUNTER" in it.metadataLabels
    }.maxByOrNull { it.capturedAtMillis }?.let {
        it.metadataValues.getOrNull(it.metadataLabels.indexOf("BATTERY_PROPERTY_CHARGE_COUNTER"))
            ?.trim()?.toLongOrNull()
    }

    fun unavailableChargeDefault(row: DiagnosticRow): Boolean = chargeCounterApi in listOf(
        Int.MIN_VALUE.toLong(), Long.MIN_VALUE
    ) && row.rawKey == "charge_counter" && row.rawValue == "0" && row.source in listOf(
        "ACTION_BATTERY_CHANGED / charge_counter", "dumpsys battery / Charge counter"
    )

    val merged = groups.firstOrNull()?.mapIndexed { index, group ->
        group.copy(rows = group.rows.map { row ->
            val readings =
                groups.mapNotNull { it[index].rows.firstOrNull { candidate -> candidate.rawKey == row.rawKey } }
            latestDiagnosticReading(readings.filter {
                (it.value != null || it.valueLabel != 0) && !unavailableChargeDefault(
                    it
                )
            }) ?: if (unavailableChargeDefault(row)) row.copy(
                value = null, rawValue = null, source = ""
            ) else row
        })
    }.orEmpty()
    return merged.mapIndexed { index, group ->
        group.copy(rows = group.rows.map { row ->
            if (row.rawKey == "battery_power") {
                val voltage = group.rows.first { it.rawKey == "voltage" }
                val current = group.rows.first { it.rawKey == "current_now" }
                val power = BatteryCurrent.powerWatts(
                    diagnosticVoltageMillivolts(voltage),
                    current.rawValue?.toLongOrNull()
                        ?.let { BatteryCurrent.scaleMicroAmps(it, multiplier) })
                val time =
                    oldestMeasurementTime(voltage.measurementTime(), current.measurementTime())
                return@map row.copy(
                    value = power?.let { String.format(locale, "%.2f W", it) },
                    rawValue = power?.toString(),
                    source = if (power == null) "" else "${voltage.source} × ${current.source}",
                    accessMethod = listOf(voltage.accessMethod, current.accessMethod).distinct()
                        .joinToString(" · "),
                    observedAtMillis = time?.wallMillis ?: 0,
                    observedElapsedMillis = time?.elapsedMillis
                )
            }
            if (row.rawKey != "estimated_health") return@map row
            val inputs = listOf("charge_full", "charge_full_design")
            // A derived reading must describe the measurements actually displayed together.
            groups.map { it[index] }.firstOrNull { candidate ->
                inputs.all { key ->
                    candidate.rows.first { it.rawKey == key } == group.rows.first { it.rawKey == key }
                }
            }?.rows?.first { it.rawKey == row.rawKey } ?: row.copy(
                value = null, rawValue = null, source = ""
            )
        })
    }
}

internal fun deviceDiagnosticGroups(
    snapshots: List<AdvancedBatterySnapshot>,
    charging: Boolean,
    locale: Locale = Locale.getDefault()
): List<DiagnosticGroup> {
    val groups = snapshots.flatMap { snapshot ->
        val access = diagnosticAccessName(snapshot)
        val serviceSource =
            if (snapshot.accessMethod == "Android") "ACTION_BATTERY_CHANGED" else "dumpsys battery"
        val fields = snapshot.sysfsLabels.zip(snapshot.sysfsValues).toMap()
        val batterySupplies = AdvancedBatteryStatsCollector.batterySupplyNames(
            fields
        )
        val service = if (charging) rawRows(
            snapshot.serviceLabels, snapshot.serviceValues, serviceSource, snapshot, access
        ).mapNotNull { row ->
            if (row.rawKey != "ChargeFastCharger") return@mapNotNull null
            val enabled = when (row.rawValue?.trim()?.lowercase(Locale.ROOT)) {
                "true" -> R.string.yes
                "false" -> R.string.diag_no
                else -> return@mapNotNull null
            }
            row.copy(label = R.string.diag_fast_charging, value = null, valueLabel = enabled)
        } else emptyList()
        val sysfs = rawRows(
            snapshot.sysfsLabels, snapshot.sysfsValues, "/sys/class/power_supply", snapshot, access
        ).groupBy { it.rawKey.substringBefore('/') }.mapNotNull { (node, rows) ->
            val battery = node in batterySupplies
            if (!charging && !battery) return@mapNotNull null
            val title = if (battery) {
                if (node == "bms") R.string.diag_fuel_gauge else R.string.nav_battery_group
            } else when (fields["$node/type"]?.trim()?.lowercase(Locale.ROOT)) {
                "mains" -> R.string.diag_mains_supply
                "wireless" -> R.string.diag_wireless_supply
                "usb", "usb_dcp", "usb_cdp", "usb_aca", "usb_c", "usb_pd", "usb_pd_drp", "usb_hvdcp", "usb_hvdcp_3", "usb_float" -> R.string.diag_usb_supply
                else -> return@mapNotNull null
            }
            val readings = rows.mapNotNull { row ->
                val key = row.rawKey.substringAfter('/')
                val text = row.rawValue?.trim().orEmpty()
                fun number(label: Int, unit: String, divisor: Double): DiagnosticRow? {
                    val value = text.toLongOrNull()?.takeIf { it >= 0 } ?: return null
                    return row.copy(
                        label = label, unit = unit, value = if (unit == "µV") String.format(
                            locale, "%.3f V", value / divisor
                        )
                        else BatteryCurrent.formatMilliAmps(value / divisor, locale) + " mA"
                    )
                }

                fun enumeration(label: Int, valueLabel: Int): DiagnosticRow? =
                    if (valueLabel == 0) null else row.copy(
                        label = label, value = null, valueLabel = valueLabel
                    )
                // Vendor charge_now and charge_now_raw can describe a charger or a learning accumulator.
                // Keep fields with unverified semantics and units in Raw.
                if (!charging) when (key) {
                    "health" -> enumeration(R.string.diag_supply_health, supplyHealthLabel(text))
                    "technology" -> text.takeIf {
                        it in setOf(
                            "NiMH", "Li-ion", "Li-poly", "LiFe", "NiCd", "LiMn"
                        )
                    }?.let { row.copy(label = R.string.diag_battery_technology, value = it) }

                    "voltage_ocv" -> number(R.string.diag_open_circuit_voltage, "µV", 1_000_000.0)
                    "voltage_min_design" -> number(
                        R.string.diag_min_design_voltage, "µV", 1_000_000.0
                    )

                    "voltage_max_design" -> number(
                        R.string.diag_max_design_voltage, "µV", 1_000_000.0
                    )

                    else -> null
                } else when {
                    key == "charge_type" -> enumeration(
                        R.string.diag_charge_type, chargeTypeLabel(text)
                    )

                    key in setOf("charging_enabled", "battery_charging_enabled") -> enumeration(
                        if (key == "charging_enabled") R.string.diag_charging_input_enabled else R.string.diag_battery_charging_enabled,
                        when (text) {
                            "1" -> R.string.yes
                            "0" -> R.string.diag_no
                            else -> 0
                        }
                    )

                    key == "input_current_limit" -> number(
                        R.string.diag_input_current_limit, "µA", 1000.0
                    )

                    key == "input_current_max" -> number(
                        R.string.diag_settled_input_current, "µA", 1000.0
                    )

                    key == "input_voltage_limit" -> number(
                        R.string.diag_input_voltage_limit, "µV", 1_000_000.0
                    )

                    battery -> null
                    key == "health" -> enumeration(
                        R.string.diag_supply_health, supplyHealthLabel(text)
                    )

                    key == "online" -> enumeration(
                        R.string.diag_power_supply_state, when (text) {
                            "0" -> R.string.diag_supply_offline
                            "1" -> R.string.diag_supply_fixed
                            "2" -> R.string.diag_supply_programmable
                            else -> 0
                        }
                    )

                    key == "current_now" -> number(R.string.diag_input_current, "µA", 1000.0)
                    key == "current_max" -> number(
                        R.string.advanced_field_max_charging_current, "µA", 1000.0
                    )

                    key == "voltage_now" -> number(
                        R.string.diag_input_voltage, "µV", 1_000_000.0
                    )?.takeIf { text.toLong() > 0 }

                    key == "voltage_max" -> number(
                        R.string.advanced_field_max_charging_voltage, "µV", 1_000_000.0
                    )

                    else -> null
                }
            }
            DiagnosticGroup(title, readings).takeIf { readings.isNotEmpty() }
        }
        sysfs + if (service.isEmpty()) emptyList() else listOf(
            DiagnosticGroup(
                R.string.diag_device_readings, service
            )
        )
    }
    return groups.groupBy { it.title }.map { (title, readings) ->
        DiagnosticGroup(
            title,
            readings.flatMap { it.rows }.groupBy { it.rawKey }.values.map { candidates ->
                checkNotNull(latestDiagnosticReading(candidates))
            })
    }
}

internal fun formattedDiagnosticValue(
    row: DiagnosticRow, label: (Int) -> String
): String? = formattedDiagnosticValue(row, label, Locale.getDefault())

internal fun formattedDiagnosticValue(
    row: DiagnosticRow, label: (Int) -> String, locale: Locale
): String? {
    if (row.valueLabel != 0) return label(row.valueLabel)
    if (row.label == R.string.advanced_field_charge_time_remaining) {
        val milliseconds = row.rawValue?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        row.value?.takeIf { it != row.rawValue }?.let { return it }
        return DiagnosticsDurationFormatter.format(locale, milliseconds)
    }
    return row.value
}

private fun supplyHealthLabel(value: String?): Int = when (value?.trim()?.lowercase(Locale.ROOT)) {
    "unknown" -> R.string.health_unknown
    "good" -> R.string.health_good
    "overheat" -> R.string.health_overheat
    "dead" -> R.string.health_dead
    "over voltage", "overvoltage" -> R.string.health_overvoltage
    "unspecified failure" -> R.string.health_failure
    "cold" -> R.string.health_cold
    else -> 0
}

private fun chargeTypeLabel(value: String): Int = when (value.lowercase(Locale.ROOT)) {
    "unknown" -> R.string.status_unknown
    "none" -> R.string.status_not_charging
    "trickle" -> R.string.diag_charge_trickle
    "fast" -> R.string.diag_charge_fast
    "standard" -> R.string.diag_charge_standard
    "adaptive" -> R.string.charging_diagnostics_condition_adaptive
    "custom" -> R.string.charging_target_mode_custom
    "long life" -> R.string.diag_charge_long_life
    "bypass" -> R.string.diag_charge_bypass
    else -> 0
}

private fun chargingPolicyLabel(snapshot: AdvancedBatterySnapshot): Int {
    val value = snapshot.chargingPolicy?.trim()?.lowercase(Locale.ROOT)
    if (snapshot.fieldSources["charging_policy"] == "dumpsys battery / Charging policy") {
        return when (value) {
            "0" -> R.string.status_unknown
            "1" -> R.string.diag_policy_default
            // HAL versions use different codes for protection modes; do not infer a fixed limit.
            "2", "4" -> R.string.diag_charge_long_life
            "3" -> R.string.charging_diagnostics_condition_adaptive
            else -> 0
        }
    }
    return when (value) {
        "default" -> R.string.diag_policy_default
        "adaptive_aon" -> R.string.diag_policy_fixed_limit
        "adaptive_ac" -> R.string.diag_policy_adaptive_ac
        "adaptive_longlife" -> R.string.diag_charge_long_life
        else -> 0
    }
}

private fun chargingStateLabel(snapshot: AdvancedBatterySnapshot): Int {
    val value = snapshot.chargingState?.trim()?.lowercase(Locale.ROOT)
    if (snapshot.fieldSources["charging_state"] in setOf(
            "ACTION_BATTERY_CHANGED / status", "dumpsys battery / status"
        )
    ) {
        return when (value) {
            "1" -> R.string.status_unknown
            "2" -> R.string.status_charging
            "3" -> R.string.status_discharging
            "4" -> R.string.status_not_charging
            "5" -> R.string.status_fully_charged
            else -> 0
        }
    }
    if (snapshot.fieldSources["charging_state"] in setOf(
            "dumpsys battery / Charging state",
            "ACTION_BATTERY_CHANGED / android.os.extra.CHARGING_STATUS"
        )
    ) {
        return when (value) {
            "0" -> R.string.status_unknown
            "1" -> R.string.normal
            "2" -> R.string.diag_charging_too_cold
            "3" -> R.string.diag_charging_too_hot
            "4" -> R.string.diag_charge_long_life
            "5" -> R.string.charging_diagnostics_condition_adaptive
            else -> 0
        }
    }
    return when (value) {
        "unknown" -> R.string.status_unknown
        "charging" -> R.string.status_charging
        "discharging" -> R.string.status_discharging
        "not charging" -> R.string.status_not_charging
        "full" -> R.string.status_fully_charged
        else -> 0
    }
}

private fun capacityLevelLabel(value: String?): Int = when (value?.trim()?.lowercase(Locale.ROOT)) {
    "0", "unknown" -> R.string.status_unknown
    "1", "critical" -> R.string.diag_capacity_critical
    "2", "low" -> R.string.diag_capacity_low
    "3", "normal" -> R.string.normal
    "4", "high" -> R.string.diag_capacity_high
    "5", "full" -> R.string.status_fully_charged
    else -> 0
}

internal fun diagnosticGroupSources(group: DiagnosticGroup): String = group.rows.filter {
    (it.value != null || it.valueLabel != 0) && it.label != R.string.battery_power && it.label != R.string.advanced_field_estimated_health
}.map { row ->
    val source = when {
        row.source.startsWith("cmd battery ") -> "cmd battery"
        row.source.startsWith("/sys/class/power_supply/") -> "/sys/class/power_supply"
        else -> row.source.substringBefore(" / ")
    }
    "$source (${diagnosticSourceName(row.accessMethod)})"
}.distinct().joinToString(" · ")

internal fun capacityHealth(snapshot: AdvancedBatterySnapshot): Double? {
    val fullSource = snapshot.fieldSources["charge_full"] ?: return null
    val designSource = snapshot.fieldSources["charge_full_design"] ?: return null
    if (fullSource.substringBeforeLast('/') != designSource.substringBeforeLast('/')) return null
    val full = snapshot.fullChargeUah?.takeIf { it > 0 } ?: return null
    val design = snapshot.designChargeUah?.takeIf { it > 0 } ?: return null
    if (full > design) return null
    return full * 100.0 / design
}

internal fun inconsistentCapacities(group: DiagnosticGroup): Boolean =
    group.rows.firstOrNull { it.rawKey == "charge_full" }?.rawValue?.toLongOrNull()?.let { full ->
        group.rows.firstOrNull { it.rawKey == "charge_full_design" }?.rawValue?.toLongOrNull()
            ?.takeIf { it > 0 }?.let { full > it }
    } == true

internal fun filterRawGroups(groups: List<DiagnosticGroup>, query: String): List<DiagnosticGroup> {
    val trimmed = query.trim()
    return groups.map { group ->
        group.copy(rows = group.rows.filter { it.rawKey.contains(trimmed, ignoreCase = true) })
    }.filter { it.rows.isNotEmpty() }
}

private fun rawRows(
    keys: List<String>,
    values: List<String>,
    source: String,
    snapshot: AdvancedBatterySnapshot,
    access: String
) = keys.zip(values).map { (key, value) ->
    val canonicalSource = when {
        key.startsWith("cmd battery ") -> key.substringBefore(" 2>")
        source == "/sys/class/power_supply" -> "$source/$key"
        else -> "$source / $key"
    }
    val time = snapshot.sourceReadTimes[canonicalSource]?.takeIf { it.wallMillis > 0 }
    DiagnosticRow(
        0,
        key,
        value,
        value,
        "",
        if (key.startsWith("cmd battery ")) key else "$source / $key",
        time?.wallMillis ?: 0,
        access,
        observedElapsedMillis = time?.elapsedMillis
    )
}

// A conservative default: arbitrary device values and identifiers stay out of reports.
internal fun diagnosticReport(
    groups: List<DiagnosticGroup>,
    label: (Int) -> String,
    includeRaw: Boolean,
    locale: Locale = Locale.getDefault()
): String = buildString {
    groups.forEachIndexed { index, group ->
        appendLine(label(group.title))
        group.rows.forEach { row ->
            val predictor = row.label != 0 && group.title == R.string.device_data_predictor
            if (includeRaw || predictor || (index < 3 && row.label != 0 && row.rawValue?.toDoubleOrNull() != null)) {
                appendLine(
                    "${row.rawKey}: ${
                        formattedDiagnosticValue(
                            row, label, locale
                        ) ?: "<unsupported>"
                    }"
                )
                appendLine("  source=${row.source}; access=${diagnosticSourceName(row.accessMethod)}; observed_at=${row.observedAtMillis}; raw=${row.rawValue}; unit=${row.unit}")
            }
        }
    }
}
