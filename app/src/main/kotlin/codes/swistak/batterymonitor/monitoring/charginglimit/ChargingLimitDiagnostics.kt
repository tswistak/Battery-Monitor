/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring.charginglimit

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.annotation.RequiresApi
import codes.swistak.batterymonitor.common.PrivilegedShellExecutor
import codes.swistak.batterymonitor.privileged.PrivilegedAccess
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

internal enum class ChargingDiagnosticCondition(val reportLabel: String) {
    OFF("Protection off / unrestricted"), FIXED_70("Fixed limit 70%"), FIXED_80("Fixed limit 80%"), FIXED_90(
        "Fixed limit 90%"
    ),
    MAXIMUM_100("Maximum / 100%"), ADAPTIVE("Adaptive / smart charging"), SCHEDULED("Scheduled charging"), OTHER(
        "Other OEM mode"
    )
}

internal data class ChargingDiagnosticSnapshot(
    val condition: ChargingDiagnosticCondition,
    val capturedAtEpochMillis: Long,
    val device: Map<String, String>,
    val settings: Map<String, String>,
    val properties: Map<String, String>,
    val systemPackages: Map<String, String>,
    val powerSupplyNodes: Map<String, String>,
    val batteryState: Map<String, String>,
    val adapterStates: Map<String, String>
)

internal class ChargingLimitDiagnostics(
    context: Context,
    private val privilegedAccessEnabled: () -> Boolean,
    private val privilegedCommand: (String) -> String? = PrivilegedAccess::run
) {
    private val appContext = context.applicationContext
    private val appShell = PrivilegedShellExecutor()
    private val settingReader = AndroidSettingReader(
        appContext, privilegedAccessEnabled, privilegedCommand
    )
    private val batteryReader = AndroidBatteryChargingStateReader(appContext)
    private val powerSupplyReader = AndroidPowerSupplyReader(
        privilegedAccessEnabled, privilegedCommand
    )

    fun capture(condition: ChargingDiagnosticCondition): ChargingDiagnosticSnapshot {
        val profile = AndroidDeviceProfile.current(appContext)
        return ChargingDiagnosticSnapshot(
            condition = condition,
            capturedAtEpochMillis = System.currentTimeMillis(),
            device = linkedMapOf(
                "manufacturer" to Build.MANUFACTURER.orEmpty(),
                "model" to Build.MODEL.orEmpty(),
                "device" to Build.DEVICE.orEmpty(),
                "android_release" to Build.VERSION.RELEASE.orEmpty(),
                "api" to Build.VERSION.SDK_INT.toString()
            ),
            settings = collectSettings(),
            properties = collectProperties(),
            systemPackages = collectSystemPackages(),
            powerSupplyNodes = collectPowerSupplyNodes(),
            batteryState = collectBatteryState(),
            adapterStates = DEFAULT_CHARGING_LIMIT_ADAPTERS.associate { adapter ->
                adapter.id to if (!adapter.supports(profile)) {
                    "not matched"
                } else {
                    ChargingLimitDetector(
                        profile, settingReader, batteryReader, powerSupplyReader, listOf(adapter)
                    ).readState().toDiagnosticText()
                }
            })
    }

    private fun collectSettings(): Map<String, String> {
        val result = sortedMapOf<String, String>()
        SettingNamespace.entries.filter { it != SettingNamespace.LINEAGE_SYSTEM }.forEach { ns ->
            val output = runDiscoveryCommand("settings list ${ns.commandName}") ?: return@forEach
            output.lineSequence().forEach { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@forEach
                val key = line.substring(0, separator).trim()
                if (!DISCOVERY_NAME.containsMatchIn(key) || SENSITIVE_NAME.containsMatchIn(key)) {
                    return@forEach
                }
                result["${ns.commandName}/$key"] = sanitizeValue(
                    line.substring(separator + 1).trim()
                )
            }
        }

        LINEAGE_KEYS.forEach { key ->
            val read = settingReader.read(SettingNamespace.LINEAGE_SYSTEM, key)
            if (read !is ReadResult.Absent) {
                result["lineage_system/$key"] = read.toDiagnosticValue()
            }
        }
        return result
    }

    private fun collectProperties(): Map<String, String> = PROPERTY_KEYS.associateWith { key ->
        val output = runDiscoveryCommand("getprop $key")
        if (output == null || output.isBlank()) "<absent>" else sanitizeKnownProperty(output.trim())
    }

    private fun collectSystemPackages(): Map<String, String> {
        val output = runDiscoveryCommand("pm list packages -s") ?: return emptyMap()
        return output.lineSequence().map { it.removePrefix("package:").trim() }
            .filter { SAFE_PACKAGE_NAME.matches(it) && DISCOVERY_PACKAGE.containsMatchIn(it) }
            .distinct().sorted().associateWith { "present" }
    }

    private fun collectPowerSupplyNodes(): Map<String, String> {
        val relativePaths = linkedSetOf<String>()
        File(POWER_SUPPLY_ROOT).listFiles()?.forEach { supply ->
            supply.listFiles()?.forEach { node ->
                if (DISCOVERY_NAME.containsMatchIn(node.name) && !SENSITIVE_NAME.containsMatchIn(
                        node.name
                    )
                ) {
                    relativePaths += "${supply.name}/${node.name}"
                }
            }
        }
        if (privilegedAccessEnabled()) {
            privilegedCommand(POWER_SUPPLY_FIND_COMMAND)?.lineSequence()?.forEach { absolute ->
                val match = SAFE_POWER_SUPPLY_PATH.matchEntire(absolute.trim()) ?: return@forEach
                if (DISCOVERY_NAME.containsMatchIn(match.groupValues[2]) && !SENSITIVE_NAME.containsMatchIn(
                        match.groupValues[2]
                    )
                ) {
                    relativePaths += "${match.groupValues[1]}/${match.groupValues[2]}"
                }
            }
        }
        val selectedPaths = relativePaths.sorted().take(MAX_POWER_SUPPLY_NODES)
        val privilegedValues = if (privilegedAccessEnabled()) {
            readPowerSupplyNodesInBulk(selectedPaths)
        } else {
            emptyMap()
        }
        return selectedPaths.associateWith { relative ->
            privilegedValues[relative]?.let {
                "${sanitizeValue(it)} [${ReadSource.PRIVILEGED_SHELL.name}]"
            } ?: powerSupplyReader.read(relative).toDiagnosticValue()
        }
    }

    private fun readPowerSupplyNodesInBulk(relativePaths: List<String>): Map<String, String> {
        if (relativePaths.isEmpty()) return emptyMap()
        val absolutePaths = relativePaths.map { "$POWER_SUPPLY_ROOT/$it" }
        val command = buildString {
            append("for f in ")
            append(absolutePaths.joinToString(" "))
            append("; do printf '%s=' \"\$f\"; head -c 512 \"\$f\"; printf '\\n'; done")
        }
        val output = privilegedCommand(command) ?: return emptyMap()
        val absoluteToRelative = absolutePaths.zip(relativePaths).toMap()
        return output.lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val relative =
                absoluteToRelative[line.substring(0, separator)] ?: return@mapNotNull null
            relative to line.substring(separator + 1).trim()
        }.toMap()
    }

    private fun collectBatteryState(): Map<String, String> {
        val battery = appContext.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return mapOf("status" to "<unavailable>")
        val result = linkedMapOf(
            "level" to battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).toString(),
            "scale" to battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1).toString(),
            "plugged" to battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0).toString(),
            "status" to battery.getIntExtra(BatteryManager.EXTRA_STATUS, 0).toString()
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            result["charging_status"] = readChargingStatus(battery).toString()
        }
        return result
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun readChargingStatus(battery: Intent): Int =
        battery.getIntExtra(BatteryManager.EXTRA_CHARGING_STATUS, 0)

    private fun runDiscoveryCommand(command: String): String? =
        if (privilegedAccessEnabled()) privilegedCommand(command) ?: appShell.run(command)
        else appShell.run(command)

    private fun ReadResult<String>.toDiagnosticValue(): String = when (this) {
        is ReadResult.Value -> "${sanitizeValue(value)} [${source.name}]"
        is ReadResult.Absent -> "<absent> [${source.name}]"
        is ReadResult.Failed -> "<failed:${reason.name}> [${source?.name ?: "unknown"}]"
    }

    private fun ChargingLimitState.toDiagnosticText(): String = when (this) {
        is ChargingLimitState.Fixed -> "fixed(configured=$configuredPercent,effective=$effectivePercent,evidence=${evidence.kind},sources=${evidence.sources.sortedBy { it.name }})"

        is ChargingLimitState.NoFixedLimit -> "no_fixed_limit(kind=$kind,evidence=${evidence.kind},sources=${evidence.sources.sortedBy { it.name }})"

        is ChargingLimitState.Unavailable -> "unavailable(reason=$reason,detail=${detail?.let(::sanitizeValue) ?: "none"})"
    }

    companion object {
        private const val POWER_SUPPLY_ROOT = "/sys/class/power_supply"
        private const val POWER_SUPPLY_FIND_COMMAND =
            "find -L /sys/class/power_supply -mindepth 2 -maxdepth 2 -type f"
        private const val MAX_POWER_SUPPLY_NODES = 200
        private val DISCOVERY_NAME = Regex(
            "battery|batt|charg|protect|health|limit|threshold|smart|adaptive|care|lrc|soc|max|min",
            RegexOption.IGNORE_CASE
        )
        private val SENSITIVE_NAME = Regex(
            "serial|imei|imsi|address|uuid|identifier|account|email|owner|subscriber|phone|user|token|secret|password|credential|login",
            RegexOption.IGNORE_CASE
        )
        private val SAFE_POWER_SUPPLY_PATH = Regex(
            "/sys/class/power_supply/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)"
        )
        private val SAFE_PACKAGE_NAME = Regex("[A-Za-z0-9_.]+")
        private val DISCOVERY_PACKAGE = Regex(
            "battery|batt|charg|protect|health|care|motorola\\.actions|motorola\\.aiservices",
            RegexOption.IGNORE_CASE
        )
        private val SAFE_EXPORTED_VALUE = Regex(
            "-?[0-9]+(?:\\.[0-9]+)?|true|false|on|off|enabled|disabled|null|adaptive|smart|auto|manual|scheduled",
            RegexOption.IGNORE_CASE
        )
        private val LINEAGE_KEYS = listOf(
            "charging_control_enabled", "charging_control_mode", "charging_control_charging_limit"
        )
        private val PROPERTY_KEYS = listOf(
            "ro.lineage.version",
            "ro.build.version.oneui",
            "ro.mi.os.version.name",
            "ro.build.version.oplusrom",
            "ro.build.version.emui",
            "ro.build.version.magic",
            "ro.nothing.version.id",
            "ro.build.version.asus",
            "ro.build.version.motorola"
        )

        internal fun sanitizeValue(value: String): String {
            val normalized = value.trim().take(512)
            if (SAFE_EXPORTED_VALUE.matches(normalized)) return normalized
            return "<redacted>"
        }

        private fun sanitizeKnownProperty(value: String): String =
            Regex("(?i)(?:v)?[0-9]+(?:\\.[0-9]+)+|[0-9]+").find(value)?.value
                ?: "<present:redacted>"
    }
}

internal object ChargingDiagnosticStore {
    private const val STORE_FILE = "charging_limit_diagnostics"
    private const val KEY_SNAPSHOTS = "snapshots"
    private const val MAX_SNAPSHOTS = 12

    fun read(context: Context): List<ChargingDiagnosticSnapshot> {
        val raw = context.getSharedPreferences(STORE_FILE, Context.MODE_PRIVATE)
            .getString(KEY_SNAPSHOTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) add(array.getJSONObject(index).toSnapshot())
            }
        }.getOrDefault(emptyList())
    }

    fun append(context: Context, snapshot: ChargingDiagnosticSnapshot) {
        val updated = (read(context) + snapshot).takeLast(MAX_SNAPSHOTS)
        val array = JSONArray()
        updated.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(STORE_FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_SNAPSHOTS, array.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(STORE_FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun ChargingDiagnosticSnapshot.toJson(): JSONObject = JSONObject().apply {
        put("condition", condition.name)
        put("schema", 1)
        put("captured_at", capturedAtEpochMillis)
        put("device", device.toJson())
        put("settings", settings.toJson())
        put("properties", properties.toJson())
        put("system_packages", systemPackages.toJson())
        put("power_supply", powerSupplyNodes.toJson())
        put("battery", batteryState.toJson())
        put("adapters", adapterStates.toJson())
    }

    private fun JSONObject.toSnapshot() = ChargingDiagnosticSnapshot(
        condition = ChargingDiagnosticCondition.valueOf(getString("condition")),
        capturedAtEpochMillis = getLong("captured_at"),
        device = getJSONObject("device").toMap(),
        settings = getJSONObject("settings").toMap(),
        properties = getJSONObject("properties").toMap(),
        systemPackages = getJSONObject("system_packages").toMap(),
        powerSupplyNodes = getJSONObject("power_supply").toMap(),
        batteryState = getJSONObject("battery").toMap(),
        adapterStates = getJSONObject("adapters").toMap()
    )

    private fun Map<String, String>.toJson() = JSONObject(this)

    private fun JSONObject.toMap(): Map<String, String> =
        keys().asSequence().associateWith { getString(it) }
}

internal object ChargingDiagnosticReport {
    fun create(context: Context, snapshots: List<ChargingDiagnosticSnapshot>): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return buildString {
            appendLine("Battery Monitor - OEM charging-limit diagnostics")
            appendLine("Generated: ${Instant.now()}")
            appendLine("App version: ${packageInfo.versionName}")
            snapshots.forEachIndexed { index, snapshot ->
                appendLine()
                appendLine("Snapshot ${index + 1}: ${snapshot.condition.reportLabel}")
                appendLine("Captured: ${Instant.ofEpochMilli(snapshot.capturedAtEpochMillis)}")
                appendSection("Device", snapshot.device)
                appendSection("Detected adapters", snapshot.adapterStates)
                appendSection("Battery state", snapshot.batteryState)
                appendSection("Filtered settings", snapshot.settings)
                appendSection("Allowlisted properties", snapshot.properties)
                appendSection("Filtered system packages", snapshot.systemPackages)
                appendSection("Filtered power_supply nodes", snapshot.powerSupplyNodes)
                if (index > 0) appendDiff(snapshots[index - 1], snapshot)
            }
        }
    }

    private fun StringBuilder.appendSection(title: String, values: Map<String, String>) {
        appendLine("$title:")
        if (values.isEmpty()) appendLine("  <none readable>")
        else values.toSortedMap().forEach { (key, value) -> appendLine("  $key=$value") }
    }

    private fun StringBuilder.appendDiff(
        previous: ChargingDiagnosticSnapshot, current: ChargingDiagnosticSnapshot
    ) {
        appendLine("Changes from previous snapshot:")
        val sections = linkedMapOf(
            "settings" to (previous.settings to current.settings),
            "properties" to (previous.properties to current.properties),
            "system_packages" to (previous.systemPackages to current.systemPackages),
            "power_supply" to (previous.powerSupplyNodes to current.powerSupplyNodes),
            "battery" to (previous.batteryState to current.batteryState),
            "adapters" to (previous.adapterStates to current.adapterStates)
        )
        var changeFound = false
        sections.forEach { (section, maps) ->
            (maps.first.keys + maps.second.keys).toSortedSet().forEach { key ->
                val before = maps.first[key]
                val after = maps.second[key]
                if (before != after) {
                    changeFound = true
                    appendLine("  $section/$key: ${before ?: "<absent>"} -> ${after ?: "<absent>"}")
                }
            }
        }
        if (!changeFound) appendLine("  <no changes in exported fields>")
    }
}
