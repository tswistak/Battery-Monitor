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
import codes.swistak.batterymonitor.monitoring.BatteryCurrent
import java.util.Locale

internal data class DiagnosticRow(
    val label: Int,
    val rawKey: String,
    val value: String?,
    val rawValue: String?,
    val unit: String,
    val source: String,
    val observedAtMillis: Long,
    val accessMethod: String
)

internal data class DiagnosticGroup(val title: Int, val rows: List<DiagnosticRow>)

internal fun diagnosticGroups(
    snapshot: AdvancedBatterySnapshot, multiplier: Int
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
    ) = DiagnosticRow(
        label,
        key,
        value,
        raw?.toString(),
        unit,
        if (raw == null) "" else source ?: snapshot.fieldSources[key]
        ?: if (android) "BatteryManager / $key" else "dumpsys battery / $key",
        snapshot.capturedAtMillis,
        access
    )

    fun charge(raw: Long?) =
        raw?.let { String.format(Locale.getDefault(), "%.1f mAh", it / 1000.0) }

    fun current(raw: Long?) = raw?.let {
        BatteryCurrent.formatMilliAmps(
            BatteryCurrent.scaleMicroAmps(
                it, multiplier
            )
        ) + " mA"
    }

    val batteryManager = "BatteryManager"
    val health = snapshot.serviceLabels.indexOf("health").takeIf { it >= 0 }
        ?.let { snapshot.serviceValues.getOrNull(it)?.toIntOrNull() }
    return listOf(
        DiagnosticGroup(
            R.string.advanced_section_counters, listOf(
                row(
                    R.string.advanced_field_charge_counter,
                    "charge_counter",
                    snapshot.chargeCounterUah,
                    "µAh",
                    charge(snapshot.chargeCounterUah)
                ), row(
                    R.string.advanced_field_current_now,
                    "current_now",
                    snapshot.currentNowUa,
                    "µA × $multiplier",
                    current(snapshot.currentNowUa)
                ), row(
                    R.string.advanced_field_current_average,
                    "current_average",
                    snapshot.currentAverageUa,
                    "µA × $multiplier",
                    current(snapshot.currentAverageUa)
                ), row(
                    R.string.advanced_field_energy_counter,
                    "energy_counter",
                    snapshot.energyCounterNwh,
                    "nWh",
                    snapshot.energyCounterNwh?.let {
                        String.format(
                            Locale.getDefault(), "%.1f mWh", it / 1_000_000.0
                        )
                    }), row(R.string.advanced_field_cycle_count, "cycle_count", snapshot.cycleCount)
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
                            Locale.getDefault(), "%.1f%%", it
                        )
                    },
                    "${snapshot.fieldSources["charge_full"]} / ${snapshot.fieldSources["charge_full_design"]}"
                )
            )
        ), DiagnosticGroup(
            R.string.advanced_section_charging, listOf(
                row(
                    R.string.advanced_field_charge_time_remaining,
                    "charge_time_remaining",
                    snapshot.chargeTimeRemainingMs,
                    "ms"
                ), row(
                    R.string.advanced_field_max_charging_current,
                    "max_charging_current",
                    snapshot.maxChargingCurrentUa,
                    "µA",
                    snapshot.maxChargingCurrentUa?.let {
                        BatteryCurrent.formatMilliAmps(BatteryCurrent.scaleMicroAmps(it, 1)) + " mA"
                    }), row(
                    R.string.advanced_field_max_charging_voltage,
                    "max_charging_voltage",
                    snapshot.maxChargingVoltageUv,
                    "µV",
                    snapshot.maxChargingVoltageUv?.let {
                        String.format(
                            Locale.getDefault(), "%.2f V", it / 1_000_000.0
                        )
                    }), row(
                    R.string.advanced_field_charging_policy,
                    "charging_policy",
                    snapshot.chargingPolicy
                ), row(
                    R.string.advanced_field_charging_state,
                    "charging_state",
                    snapshot.chargingState,
                    value = if (snapshot.fieldSources["charging_state"] == "ACTION_BATTERY_CHANGED / status") snapshot.chargingState?.toIntOrNull()
                        ?.let {
                            codes.swistak.batterymonitor.common.DisplayStrings.statuses.getOrNull(it)
                        }
                    else snapshot.chargingState), row(
                    R.string.advanced_field_capacity_level, "capacity_level", snapshot.capacityLevel
                ))), DiagnosticGroup(
            R.string.advanced_section_service, rawRows(
                snapshot.serviceLabels,
                snapshot.serviceValues,
                if (android) "ACTION_BATTERY_CHANGED" else "dumpsys battery",
                snapshot.capturedAtMillis,
                access
            )
        ), DiagnosticGroup(
            R.string.advanced_section_sysfs, rawRows(
                snapshot.sysfsLabels,
                snapshot.sysfsValues,
                "/sys/class/power_supply",
                snapshot.capturedAtMillis,
                access
            )
        ), DiagnosticGroup(
            R.string.advanced_section_metadata, rawRows(
                snapshot.metadataLabels,
                snapshot.metadataValues,
                batteryManager,
                snapshot.capturedAtMillis,
                access
            )
        ))
}

internal fun diagnosticAccessName(snapshot: AdvancedBatterySnapshot): String =
    when (snapshot.accessMethod) {
        AdvancedBatterySnapshot.ACCESS_SHIZUKU -> "Shizuku"
        AdvancedBatterySnapshot.ACCESS_ROOT -> "Root"
        else -> snapshot.accessMethod ?: "Android"
    }

internal fun mergedDiagnosticGroups(
    snapshots: List<AdvancedBatterySnapshot>, multiplier: Int
): List<DiagnosticGroup> {
    val groups = snapshots.map { diagnosticGroups(it, multiplier).take(3) }
    return groups.firstOrNull()?.mapIndexed { index, group ->
        group.copy(rows = group.rows.map { row ->
            val readings =
                groups.mapNotNull { it[index].rows.firstOrNull { candidate -> candidate.rawKey == row.rawKey } }
            readings.filter { it.value != null }.maxByOrNull { it.observedAtMillis } ?: row
        })
    }.orEmpty()
}

internal fun diagnosticGroupSources(group: DiagnosticGroup): String =
    group.rows.filter { it.value != null }.map { row ->
        val source = when {
            row.source.startsWith("cmd battery ") -> "cmd battery"
            row.source.startsWith("/sys/class/power_supply/") -> "/sys/class/power_supply"
            else -> row.source.substringBefore(" / ")
        }
        "$source (${row.accessMethod})"
    }.distinct().joinToString(" · ")

internal fun capacityHealth(snapshot: AdvancedBatterySnapshot): Double? {
    val fullSource = snapshot.fieldSources["charge_full"] ?: return null
    val designSource = snapshot.fieldSources["charge_full_design"] ?: return null
    if (fullSource.substringBeforeLast('/') != designSource.substringBeforeLast('/')) return null
    val full = snapshot.fullChargeUah?.takeIf { it > 0 } ?: return null
    val design = snapshot.designChargeUah?.takeIf { it > 0 } ?: return null
    return full * 100.0 / design
}

internal fun filterRawGroups(groups: List<DiagnosticGroup>, query: String): List<DiagnosticGroup> =
    groups.map { group ->
        group.copy(rows = group.rows.filter { it.rawKey.contains(query.trim(), ignoreCase = true) })
    }.filter { it.rows.isNotEmpty() }

private fun rawRows(
    keys: List<String>, values: List<String>, source: String, at: Long, access: String
) = keys.zip(values).map { (key, value) ->
    DiagnosticRow(
        0,
        key,
        value,
        value,
        "",
        if (key.startsWith("cmd battery ")) key else "$source / $key",
        at,
        access
    )
}

// A conservative default: arbitrary device values and identifiers stay out of reports.
internal fun diagnosticReport(
    groups: List<DiagnosticGroup>, label: (Int) -> String, includeRaw: Boolean
): String = buildString {
    groups.forEachIndexed { index, group ->
        appendLine(label(group.title))
        group.rows.forEach { row ->
            if (includeRaw || (index < 3 && row.rawValue?.toDoubleOrNull() != null)) {
                appendLine("${row.rawKey}: ${row.value ?: "<unsupported>"}")
                appendLine("  source=${row.source}; access=${row.accessMethod}; observed_at=${row.observedAtMillis}; raw=${row.rawValue}; unit=${row.unit}")
            }
        }
    }
}
