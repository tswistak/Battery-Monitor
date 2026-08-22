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
import androidx.core.content.edit
import codes.swistak.batterymonitor.common.PrivilegedShellExecutor
import codes.swistak.batterymonitor.privileged.PrivilegedAccess
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


internal sealed class ChargingDiagnosticCondition(val reportLabel: String) {
    data object Off : ChargingDiagnosticCondition("Protection off / unrestricted")

    data class Fixed(val percent: Int) : ChargingDiagnosticCondition("Fixed limit $percent%") {
        init {
            require(percent in MIN_FIXED_PERCENT..MAX_FIXED_PERCENT)
        }
    }

    data object Adaptive : ChargingDiagnosticCondition("Adaptive / smart charging")
    data object Scheduled : ChargingDiagnosticCondition("Scheduled charging")
    data object Other : ChargingDiagnosticCondition("Other OEM mode")

    fun toStorageValue(): String = when (this) {
        Off -> "OFF"
        is Fixed -> "FIXED:$percent"
        Adaptive -> "ADAPTIVE"
        Scheduled -> "SCHEDULED"
        Other -> "OTHER"
    }

    companion object {
        const val MIN_FIXED_PERCENT = 60
        const val MAX_FIXED_PERCENT = 100

        fun fromStorageValue(value: String): ChargingDiagnosticCondition = when {
            value == "OFF" -> Off
            value == "ADAPTIVE" -> Adaptive
            value == "SCHEDULED" -> Scheduled
            value == "OTHER" -> Other
            value == "MAXIMUM_100" -> Fixed(100)
            value.startsWith("FIXED:") -> parseFixed(value.substringAfter(':'))
            value.startsWith("FIXED_") -> parseFixed(value.substringAfter('_'))
            else -> Other
        }

        private fun parseFixed(percent: String): ChargingDiagnosticCondition =
            percent.toIntOrNull()?.takeIf { it in MIN_FIXED_PERCENT..MAX_FIXED_PERCENT }
                ?.let(::Fixed) ?: Other
    }
}

internal data class ChargingDiagnosticSnapshot(
    val condition: ChargingDiagnosticCondition,
    val capturedAtEpochMillis: Long,
    val plugged: Boolean?,
    val device: Map<String, String>,
    val discoveryAccess: Map<String, String>,
    val sectionStatus: Map<String, String>,
    val settings: Map<String, String>,
    val properties: Map<String, String>,
    val systemPackages: Map<String, String>,
    val powerSupplyNodes: Map<String, String>,
    val batteryState: Map<String, String>,
    val adapterStates: Map<String, String>
) {
    fun hasLimitedUnprivilegedDiscovery(): Boolean {
        if (discoveryAccess["privileged_requested"] != "false") return false
        val restrictedSections = listOf(
            sectionStatus["settings/global"],
            sectionStatus["settings/secure"],
            sectionStatus["settings/system"],
            sectionStatus["power_supply/local_discovery"]
        ).count { status ->
            status?.startsWith("FAILED") == true || status?.startsWith("UNREADABLE") == true
        }
        return restrictedSections >= 2
    }
}

internal class ChargingLimitDiagnostics(
    context: Context,
    private val privilegedAccessEnabled: () -> Boolean,
    private val privilegedCommand: (String) -> PrivilegedAccess.CommandResult? = PrivilegedAccess::runWithBackend
) {
    private data class DiscoveryCommandRead(
        val output: String?, val source: String, val status: String, val succeeded: Boolean
    )

    private data class ShellCommandRead(val output: String?, val exitCode: Int)

    private sealed interface BulkNodeRead {
        data class Value(val value: String, val source: String) : BulkNodeRead
        data class Failed(val source: String) : BulkNodeRead
    }

    private val appContext = context.applicationContext
    private val appShell = PrivilegedShellExecutor()
    private val redactor = DiagnosticValueRedactor(appContext)
    private val stringPrivilegedCommand: (String) -> String? = { privilegedCommand(it)?.output }
    private val settingReader = AndroidSettingReader(
        appContext, privilegedAccessEnabled, stringPrivilegedCommand
    )
    private val batteryReader = AndroidBatteryChargingStateReader(appContext)
    private val powerSupplyReader = AndroidPowerSupplyReader(
        privilegedAccessEnabled, stringPrivilegedCommand
    )

    fun capture(condition: ChargingDiagnosticCondition): ChargingDiagnosticSnapshot {
        val profile = AndroidDeviceProfile.current(appContext)
        val sectionStatus = sortedMapOf<String, String>()
        val batteryState = collectBatteryState()
        return ChargingDiagnosticSnapshot(
            condition = condition,
            capturedAtEpochMillis = System.currentTimeMillis(),
            plugged = batteryState["plugged"]?.toIntOrNull()?.let { it != 0 },
            device = linkedMapOf(
                "manufacturer" to Build.MANUFACTURER.orEmpty(),
                "brand" to Build.BRAND.orEmpty(),
                "model" to Build.MODEL.orEmpty(),
                "device" to Build.DEVICE.orEmpty(),
                "product" to Build.PRODUCT.orEmpty(),
                "android_release" to Build.VERSION.RELEASE.orEmpty(),
                "api" to Build.VERSION.SDK_INT.toString()
            ),
            discoveryAccess = collectDiscoveryAccess(),
            sectionStatus = sectionStatus,
            settings = collectSettings(sectionStatus),
            properties = collectProperties(sectionStatus),
            systemPackages = collectSystemPackages(sectionStatus),
            powerSupplyNodes = collectPowerSupplyNodes(sectionStatus),
            batteryState = batteryState,
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

    private fun collectDiscoveryAccess(): Map<String, String> {
        val requested = privilegedAccessEnabled()
        val test = if (requested) privilegedCommand("id") else null
        return linkedMapOf(
            "privileged_requested" to requested.toString(),
            "privileged_backend" to (test?.backend?.name ?: "none"),
            "privileged_command_test" to when {
                !requested -> "NOT_REQUESTED"
                test != null -> "OK"
                else -> "FAILED"
            }
        )
    }

    private fun collectSettings(sectionStatus: MutableMap<String, String>): Map<String, String> {
        val result = sortedMapOf<String, String>()
        SettingNamespace.entries.filter { it != SettingNamespace.LINEAGE_SYSTEM }.forEach { ns ->
            val read = runDiscoveryCommand("settings list ${ns.commandName}")
            sectionStatus["settings/${ns.commandName}"] = read.status
            read.output?.lineSequence()?.forEach { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@forEach
                val key = line.substring(0, separator).trim()
                if (!SETTINGS_DISCOVERY_NAME.containsMatchIn(key) || isSensitiveName(key)) {
                    return@forEach
                }
                result["${ns.commandName}/$key"] =
                    "${redactor.sanitize(line.substring(separator + 1))} [${read.source}]"
            }
        }

        val lineageStatuses = linkedSetOf<String>()
        LINEAGE_KEYS.forEach { key ->
            val read = settingReader.read(SettingNamespace.LINEAGE_SYSTEM, key)
            lineageStatuses += read.statusText()
            if (read !is ReadResult.Absent) {
                result["lineage_system/$key"] = read.toDiagnosticValue()
            }
        }
        sectionStatus["settings/lineage_system"] = lineageStatuses.joinToString()
        return result
    }

    private fun collectProperties(sectionStatus: MutableMap<String, String>): Map<String, String> =
        PROPERTY_KEYS.associateWith { key ->
            val read = runDiscoveryCommand("getprop $key")
            sectionStatus["properties/$key"] = read.status
            if (!read.succeeded) {
                "<failed:COMMAND_FAILED> [${read.source}]"
            } else if (read.output.isNullOrBlank()) {
                "<absent> [${read.source}]"
            } else {
                "${sanitizeKnownProperty(read.output.trim())} [${read.source}]"
            }
        }

    private fun collectSystemPackages(
        sectionStatus: MutableMap<String, String>
    ): Map<String, String> {
        val read = runDiscoveryCommand("pm list packages -s")
        sectionStatus["system_packages"] = read.status
        return read.output?.lineSequence()?.map { it.removePrefix("package:").trim() }
            ?.filter { SAFE_PACKAGE_NAME.matches(it) && DISCOVERY_PACKAGE.containsMatchIn(it) }
            ?.distinct()?.sorted()?.associateWith { "present [${read.source}]" }.orEmpty()
    }

    private fun collectPowerSupplyNodes(
        sectionStatus: MutableMap<String, String>
    ): Map<String, String> {
        val relativePaths = linkedSetOf<String>()
        val supplies = File(POWER_SUPPLY_ROOT).listFiles()
        sectionStatus["power_supply/local_discovery"] =
            if (supplies == null) "UNREADABLE [APP_FILE]" else "OK [APP_FILE]"
        supplies?.forEach { supply ->
            supply.listFiles()?.forEach { node ->
                if (POWER_SUPPLY_DISCOVERY_NAME.containsMatchIn(node.name) && !isSensitiveName(
                        node.name
                    )
                ) {
                    relativePaths += "${supply.name}/${node.name}"
                }
            }
        }

        if (privilegedAccessEnabled()) {
            val discovery = runPrivilegedCommand(POWER_SUPPLY_FIND_COMMAND)
            sectionStatus["power_supply/privileged_discovery"] = discovery.status
            discovery.output?.lineSequence()?.forEach { absolute ->
                val match = SAFE_POWER_SUPPLY_PATH.matchEntire(absolute.trim()) ?: return@forEach
                if (POWER_SUPPLY_DISCOVERY_NAME.containsMatchIn(match.groupValues[2]) && !isSensitiveName(
                        match.groupValues[2]
                    )
                ) {
                    relativePaths += "${match.groupValues[1]}/${match.groupValues[2]}"
                }
            }
        } else {
            sectionStatus["power_supply/privileged_discovery"] = "NOT_REQUESTED"
        }

        val selectedPaths = relativePaths.sorted().take(MAX_POWER_SUPPLY_NODES)
        val directReads = selectedPaths.associateWith(::readPowerSupplyNodeDirect)
        val pathsNeedingPrivilegedRead = directReads.filterValues { it !is ReadResult.Value }.keys
        val privilegedReads = if (privilegedAccessEnabled()) {
            readPowerSupplyNodesInBatches(pathsNeedingPrivilegedRead.toList(), sectionStatus)
        } else {
            emptyMap()
        }
        sectionStatus["power_supply/selection"] =
            "selected=${selectedPaths.size}, capped=${relativePaths.size > MAX_POWER_SUPPLY_NODES}"

        return selectedPaths.associateWith { relative ->
            val direct = directReads.getValue(relative)
            when {
                direct is ReadResult.Value -> direct.toDiagnosticValue()
                privilegedReads[relative] is BulkNodeRead.Value -> {
                    val value = privilegedReads.getValue(relative) as BulkNodeRead.Value
                    "${redactor.sanitize(value.value)} [${value.source}]"
                }

                privilegedReads[relative] is BulkNodeRead.Failed -> {
                    val failure = privilegedReads.getValue(relative) as BulkNodeRead.Failed
                    "<failed:COMMAND_FAILED> [${failure.source}]"
                }

                else -> direct.toDiagnosticValue()
            }
        }
    }

    private fun readPowerSupplyNodeDirect(relativePath: String): ReadResult<String> {
        val file = File(POWER_SUPPLY_ROOT, relativePath)
        return try {
            if (!file.exists()) ReadResult.Absent(ReadSource.APP_FILE)
            else ReadResult.Value(file.readText().trim(), ReadSource.APP_FILE)
        } catch (_: SecurityException) {
            ReadResult.Failed(ReadFailureReason.ACCESS_DENIED, ReadSource.APP_FILE)
        } catch (_: Throwable) {
            ReadResult.Failed(ReadFailureReason.EXCEPTION, ReadSource.APP_FILE)
        }
    }

    private fun readPowerSupplyNodesInBatches(
        relativePaths: List<String>, sectionStatus: MutableMap<String, String>
    ): Map<String, BulkNodeRead> {
        if (relativePaths.isEmpty()) {
            sectionStatus["power_supply/privileged_reads"] = "NOT_NEEDED"
            return emptyMap()
        }
        val result = mutableMapOf<String, BulkNodeRead>()
        relativePaths.chunked(POWER_SUPPLY_BATCH_SIZE).forEachIndexed { index, batch ->
            val absolutePaths = batch.map { "$POWER_SUPPLY_ROOT/$it" }
            val command = buildPowerSupplyBatchCommand(absolutePaths)
            val read = runPrivilegedCommand(command)
            sectionStatus["power_supply/read_batch_${index + 1}"] = read.status
            val absoluteToRelative = absolutePaths.zip(batch).toMap()
            read.output?.lineSequence()?.forEach { line ->
                val fields = line.split('\t', limit = 3)
                val relative = absoluteToRelative[fields.firstOrNull()] ?: return@forEach
                result[relative] = if (fields.getOrNull(1) == "OK" && fields.size == 3) {
                    BulkNodeRead.Value(fields[2], read.source)
                } else {
                    BulkNodeRead.Failed(read.source)
                }
            }
            batch.forEach { relative ->
                result.putIfAbsent(relative, BulkNodeRead.Failed(read.source))
            }
        }
        return result
    }

    private fun buildPowerSupplyBatchCommand(absolutePaths: List<String>): String = buildString {
        append("for f in ")
        append(absolutePaths.joinToString(" "))
        append("; do printf '%s\\t' \"\$f\"; ")
        append("if [ -r \"\$f\" ]; then value=\$(head -c 512 \"\$f\" 2>/dev/null); ")
        append("code=\$?; if [ \"\$code\" -eq 0 ]; then ")
        append("printf 'OK\\t%s\\n' \"\$value\"; else printf 'FAILED\\n'; fi; ")
        append("else printf 'FAILED\\n'; fi; done")
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

    private fun runDiscoveryCommand(command: String): DiscoveryCommandRead {
        var privilegedFailed = false
        if (privilegedAccessEnabled()) {
            val privileged = runPrivilegedCommandWithStatus(command)
            if (privileged != null && privileged.first.exitCode == 0) {
                return DiscoveryCommandRead(
                    privileged.first.output,
                    privileged.second.name,
                    "OK [${privileged.second.name}]",
                    true
                )
            }
            privilegedFailed = true
        }
        val appRead = runAppCommandWithStatus(command)
        val detail = if (privilegedFailed) "; privileged=FAILED" else ""
        return if (appRead != null && appRead.exitCode == 0) {
            DiscoveryCommandRead(
                appRead.output, "APP_SHELL", "OK [APP_SHELL$detail]", true
            )
        } else {
            DiscoveryCommandRead(
                null, "APP_SHELL", "FAILED [APP_SHELL$detail]", false
            )
        }
    }

    private fun runPrivilegedCommand(command: String): DiscoveryCommandRead {
        val privileged = runPrivilegedCommandWithStatus(command)
        return if (privileged == null || privileged.first.exitCode != 0) {
            DiscoveryCommandRead(
                null, "PRIVILEGED_SHELL", "FAILED [PRIVILEGED_SHELL]", false
            )
        } else {
            DiscoveryCommandRead(
                privileged.first.output,
                privileged.second.name,
                "OK [${privileged.second.name}]",
                true
            )
        }
    }

    private fun runAppCommandWithStatus(command: String): ShellCommandRead? =
        parseCommandWithStatus(appShell.run(wrapCommandWithExitStatus(command)))

    private fun runPrivilegedCommandWithStatus(
        command: String
    ): Pair<ShellCommandRead, PrivilegedAccess.Backend>? {
        val result = privilegedCommand(wrapCommandWithExitStatus(command)) ?: return null
        return parseCommandWithStatus(result.output)?.let { it to result.backend }
    }

    private fun parseCommandWithStatus(output: String?): ShellCommandRead? {
        if (output == null) return null
        val markerIndex = output.lastIndexOf(COMMAND_EXIT_MARKER)
        if (markerIndex < 0) return null
        val exitCode =
            output.substring(markerIndex + COMMAND_EXIT_MARKER.length).lineSequence().firstOrNull()
                ?.trim()?.toIntOrNull() ?: return null
        val commandOutput = output.substring(0, markerIndex).trim().takeIf(String::isNotEmpty)
        return ShellCommandRead(commandOutput, exitCode)
    }

    private fun wrapCommandWithExitStatus(command: String): String =
        "{ $command; }; code=\$?; printf '\\n${COMMAND_EXIT_MARKER}%s\\n' \"\$code\""

    private fun ReadResult<String>.statusText(): String = when (this) {
        is ReadResult.Value -> "OK [${source.name}]"
        is ReadResult.Absent -> "ABSENT [${source.name}]"
        is ReadResult.Failed -> "${reason.name} [${source?.name ?: "unknown"}]"
    }

    private fun ReadResult<String>.toDiagnosticValue(): String = when (this) {
        is ReadResult.Value -> "${redactor.sanitize(value)} [${source.name}]"
        is ReadResult.Absent -> "<absent> [${source.name}]"
        is ReadResult.Failed -> "<failed:${reason.name}> [${source?.name ?: "unknown"}]"
    }

    private fun ChargingLimitState.toDiagnosticText(): String = when (this) {
        is ChargingLimitState.Fixed -> "fixed(configured=$configuredPercent,effective=$effectivePercent,evidence=${evidence.kind},sources=${evidence.sources.sortedBy { it.name }})"

        is ChargingLimitState.NoFixedLimit -> "no_fixed_limit(kind=$kind,evidence=${evidence.kind},sources=${evidence.sources.sortedBy { it.name }})"

        is ChargingLimitState.Unavailable -> "unavailable(reason=$reason,detail=${
            detail?.let(
                redactor::sanitize
            ) ?: "none"
        })"
    }

    private fun isSensitiveName(name: String): Boolean = SENSITIVE_NAME.containsMatchIn(name)

    companion object {
        private const val POWER_SUPPLY_ROOT = "/sys/class/power_supply"
        private const val POWER_SUPPLY_FIND_COMMAND =
            "find -L /sys/class/power_supply -mindepth 2 -maxdepth 2 -type f"
        private const val MAX_POWER_SUPPLY_NODES = 200
        private const val POWER_SUPPLY_BATCH_SIZE = 25
        private const val COMMAND_EXIT_MARKER = "__BATTERY_MONITOR_EXIT_CODE__:"
        private val SETTINGS_DISCOVERY_NAME = Regex(
            "battery|batt|charg|protect|lrc", RegexOption.IGNORE_CASE
        )
        private val POWER_SUPPLY_DISCOVERY_NAME = Regex(
            "battery|batt|charg|protect|health|limit|threshold|smart|adaptive|care|lrc|soc|max|min",
            RegexOption.IGNORE_CASE
        )
        private val SENSITIVE_NAME = Regex(
            "(?:^|[^a-z0-9])(?:serial|serial_number|serialnumber|imei|imsi|android_id|battery_id|phone_number|subscriber_id|email|account_name|token|secret|password|credential)(?:$|[^a-z0-9])",
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

        private fun sanitizeKnownProperty(value: String): String =
            Regex("(?i)(?:v)?[0-9]+(?:\\.[0-9]+)+|[0-9]+").find(value)?.value
                ?: "<present:redacted>"
    }
}

internal class DiagnosticValueRedactor(context: Context) {
    private val key: ByteArray by lazy { loadOrCreateKey(context.applicationContext) }

    fun sanitize(value: String): String = sanitizeWithKey(value, key)

    companion object {
        internal fun sanitizeWithKey(value: String, key: ByteArray): String {
            val normalized = value.trim().replace('\n', ' ').replace('\r', ' ').take(512)
            if (SAFE_EXPORTED_VALUE.matches(normalized)) return normalized
            return "<redacted:${tokenFor(normalized, key)}>"
        }

        private const val STORE_FILE = "charging_limit_diagnostics_secrets"
        private const val KEY_HMAC = "redaction_hmac_key_v1"
        private const val KEY_SIZE_BYTES = 32
        private const val TOKEN_BYTES = 6
        private val SAFE_EXPORTED_VALUE = Regex(
            "-?[0-9]+(?:\\.[0-9]+)?|true|false|on|off|enabled|disabled|null|adaptive|smart|auto|manual|scheduled",
            RegexOption.IGNORE_CASE
        )

        internal fun tokenFor(value: String, key: ByteArray): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(value.toByteArray(Charsets.UTF_8)).take(TOKEN_BYTES)
                .joinToString("") { "%02X".format(it.toInt() and 0xff) }
        }

        private fun loadOrCreateKey(context: Context): ByteArray {
            val preferences = context.getSharedPreferences(STORE_FILE, Context.MODE_PRIVATE)
            preferences.getString(KEY_HMAC, null)?.let {
                return Base64.getDecoder().decode(it)
            }
            val generated = ByteArray(KEY_SIZE_BYTES).also(SecureRandom()::nextBytes)
            preferences.edit(commit = true) {
                putString(KEY_HMAC, Base64.getEncoder().encodeToString(generated))
            }
            return generated
        }
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
        context.getSharedPreferences(STORE_FILE, Context.MODE_PRIVATE).edit {
            putString(KEY_SNAPSHOTS, array.toString())
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(STORE_FILE, Context.MODE_PRIVATE).edit { clear() }
    }

    private fun ChargingDiagnosticSnapshot.toJson(): JSONObject = JSONObject().apply {
        put("condition", condition.toStorageValue())
        put("schema", 3)
        put("captured_at", capturedAtEpochMillis)
        plugged?.let { put("plugged", it) }
        put("device", device.toJson())
        put("discovery_access", discoveryAccess.toJson())
        put("section_status", sectionStatus.toJson())
        put("settings", settings.toJson())
        put("properties", properties.toJson())
        put("system_packages", systemPackages.toJson())
        put("power_supply", powerSupplyNodes.toJson())
        put("battery", batteryState.toJson())
        put("adapters", adapterStates.toJson())
    }

    private fun JSONObject.toSnapshot(): ChargingDiagnosticSnapshot {
        val batteryState = getJSONObject("battery").toMap()
        val plugged = if (has("plugged") && !isNull("plugged")) {
            getBoolean("plugged")
        } else {
            batteryState["plugged"]?.toIntOrNull()?.let { it != 0 }
        }
        return ChargingDiagnosticSnapshot(
            condition = ChargingDiagnosticCondition.fromStorageValue(getString("condition")),
            capturedAtEpochMillis = getLong("captured_at"),
            plugged = plugged,
            device = getJSONObject("device").toMap(),
            discoveryAccess = optJSONObject("discovery_access")?.toMap().orEmpty(),
            sectionStatus = optJSONObject("section_status")?.toMap().orEmpty(),
            settings = getJSONObject("settings").toMap(),
            properties = getJSONObject("properties").toMap(),
            systemPackages = getJSONObject("system_packages").toMap(),
            powerSupplyNodes = getJSONObject("power_supply").toMap(),
            batteryState = batteryState,
            adapterStates = getJSONObject("adapters").toMap()
        )
    }

    private fun Map<String, String>.toJson() = JSONObject(this)

    private fun JSONObject.toMap(): Map<String, String> =
        keys().asSequence().associateWith { getString(it) }
}

internal object ChargingDiagnosticReport {
    private data class CandidateSignal(
        val key: String,
        val classification: String,
        val values: List<Pair<ChargingDiagnosticCondition, String>>
    )

    fun create(context: Context, snapshots: List<ChargingDiagnosticSnapshot>): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return buildString {
            appendLine("Battery Monitor - OEM charging-limit diagnostics")
            appendLine("Generated: ${Instant.now()}")
            appendLine("App version: ${packageInfo.versionName}")
            append(createCandidateSection(snapshots))
            snapshots.forEachIndexed { index, snapshot ->
                appendLine()
                appendLine("Snapshot ${index + 1}: ${snapshot.condition.reportLabel}")
                appendLine("Captured: ${Instant.ofEpochMilli(snapshot.capturedAtEpochMillis)}")
                appendSection(
                    "Capture context",
                    mapOf("plugged" to (snapshot.plugged?.toString() ?: "<unknown>"))
                )
                appendSection("Device", snapshot.device)
                appendSection("Discovery access", snapshot.discoveryAccess)
                appendSection("Section status", snapshot.sectionStatus)
                appendSection("Detected adapters", snapshot.adapterStates)
                appendSection("Battery state (context only)", snapshot.batteryState)
                appendSection("Filtered settings", snapshot.settings)
                appendSection("Allowlisted properties", snapshot.properties)
                appendSection("Filtered system packages", snapshot.systemPackages)
                appendSection("Filtered power_supply nodes", snapshot.powerSupplyNodes)
                if (index > 0) appendDiff(snapshots[index - 1], snapshot)
            }
        }
    }

    internal fun createCandidateSection(
        snapshots: List<ChargingDiagnosticSnapshot>
    ): String = buildString { appendCandidateSignals(snapshots) }

    private fun StringBuilder.appendCandidateSignals(snapshots: List<ChargingDiagnosticSnapshot>) {
        val signalsBySnapshot = snapshots.map(::candidateSignals)
        val changingKeys = signalsBySnapshot.flatMapTo(sortedSetOf()) { it.keys }.filter { key ->
            signalsBySnapshot.map { it[key] ?: "<absent>" }.distinct().size > 1
        }
        val candidates = changingKeys.map { key ->
            val values = snapshots.indices.map { index ->
                snapshots[index].condition to (signalsBySnapshot[index][key] ?: "<absent>")
            }
            CandidateSignal(key, classifyCandidate(values), values)
        }
        val highConfidence =
            candidates.filter { it.classification in HIGH_CONFIDENCE_CLASSES }.sortedWith(
                compareBy({ CLASSIFICATION_PRIORITY.getValue(it.classification) }, { it.key })
            )
        val otherChanges =
            candidates.filterNot { it.classification in HIGH_CONFIDENCE_CLASSES }.sortedWith(
                compareBy({ CLASSIFICATION_PRIORITY.getValue(it.classification) }, { it.key })
            )

        appendLine()
        appendCandidateGroup("High-confidence candidate signals", highConfidence)
        appendLine()
        appendCandidateGroup("Other changing values", otherChanges)
    }

    private fun StringBuilder.appendCandidateGroup(
        title: String, candidates: List<CandidateSignal>
    ) {
        appendLine("$title:")
        if (candidates.isEmpty()) appendLine("  <none found>")
        candidates.forEach { candidate ->
            appendLine("  ${candidate.key} [${candidate.classification}]")
            candidate.values.forEach { (condition, value) ->
                appendLine("    ${condition.reportLabel} = $value")
            }
        }
    }

    private fun candidateSignals(snapshot: ChargingDiagnosticSnapshot): Map<String, String> =
        buildMap {
            snapshot.settings.forEach { (key, value) -> put("settings/$key", value) }
            snapshot.properties.forEach { (key, value) -> put("properties/$key", value) }
            snapshot.systemPackages.forEach { (key, value) -> put("system_packages/$key", value) }
            snapshot.powerSupplyNodes.forEach { (key, value) -> put("power_supply/$key", value) }
            snapshot.adapterStates.forEach { (key, value) -> put("adapters/$key", value) }
        }

    private fun classifyCandidate(
        values: List<Pair<ChargingDiagnosticCondition, String>>
    ): String {
        val fixedValues = values.mapNotNull { (condition, value) ->
            (condition as? ChargingDiagnosticCondition.Fixed)?.let { it.percent to coreValue(value) }
        }
        if (fixedValues.map { it.first }
                .distinct().size >= 2 && fixedValues.all { (percent, value) ->
                value.toIntOrNull() == percent
            }) {
            return "MATCHES_TARGET_PERCENT"
        }
        val normalizedValues = values.map { (condition, value) ->
            condition to coreValue(value).lowercase()
        }
        val fixedBooleanValues = normalizedValues.filter {
            it.first is ChargingDiagnosticCondition.Fixed
        }.map { it.second }
        val offBooleanValues = normalizedValues.filter {
            it.first is ChargingDiagnosticCondition.Off
        }.map { it.second }
        val fixedBooleans = fixedBooleanValues.mapNotNull { it.toBooleanSignal() }
        val offBooleans = offBooleanValues.mapNotNull { it.toBooleanSignal() }
        if (fixedBooleans.size == fixedBooleanValues.size && fixedBooleans.isNotEmpty() && fixedBooleans.all { it } && offBooleans.size == offBooleanValues.size && offBooleans.isNotEmpty() && offBooleans.none { it }) {
            return "BOOLEAN_ENABLE_SIGNAL"
        }

        val allNumeric = normalizedValues.map { it.second.toLongOrNull() }
        val fixedPercentages = normalizedValues.mapNotNull { (condition, _) ->
            (condition as? ChargingDiagnosticCondition.Fixed)?.percent
        }.distinct()
        val fixedModeValues = normalizedValues.mapNotNull { (condition, value) ->
            value.takeIf { condition is ChargingDiagnosticCondition.Fixed }
        }.distinct()
        val otherModeValues = normalizedValues.mapNotNull { (condition, value) ->
            value.takeUnless { condition is ChargingDiagnosticCondition.Fixed }
        }
        if (allNumeric.all { it != null } && allNumeric.filterNotNull()
                .all { it in -32L..32L } && normalizedValues.map { it.second }
                .distinct().size in 2..4 && fixedPercentages.size >= 2 && fixedModeValues.size == 1 && otherModeValues.any { it != fixedModeValues.single() }) {
            return "SMALL_ENUM_SIGNAL"
        }
        if (normalizedValues.all { it.second.startsWith("<redacted:") }) {
            return "REDACTED_VALUE_CHANGED"
        }
        if (allNumeric.all { it != null }) return "NUMERIC_VALUE_CHANGED"
        return "VALUE_CHANGED"
    }

    private fun coreValue(value: String): String = value.substringBefore(" [")

    private fun String.toBooleanSignal(): Boolean? = when (this) {
        "1", "true", "on", "enabled" -> true
        "0", "false", "off", "disabled" -> false
        else -> null
    }

    private fun StringBuilder.appendSection(title: String, values: Map<String, String>) {
        appendLine("$title:")
        if (values.isEmpty()) appendLine("  <none readable>")
        else values.toSortedMap().forEach { (key, value) -> appendLine("  $key=$value") }
    }

    private fun StringBuilder.appendDiff(
        previous: ChargingDiagnosticSnapshot, current: ChargingDiagnosticSnapshot
    ) {
        appendLine("Changes from previous snapshot (battery context excluded):")
        val sections = linkedMapOf(
            "settings" to (previous.settings to current.settings),
            "properties" to (previous.properties to current.properties),
            "system_packages" to (previous.systemPackages to current.systemPackages),
            "power_supply" to (previous.powerSupplyNodes to current.powerSupplyNodes),
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

    private val HIGH_CONFIDENCE_CLASSES = setOf(
        "MATCHES_TARGET_PERCENT", "BOOLEAN_ENABLE_SIGNAL", "SMALL_ENUM_SIGNAL"
    )
    private val CLASSIFICATION_PRIORITY = mapOf(
        "MATCHES_TARGET_PERCENT" to 0,
        "BOOLEAN_ENABLE_SIGNAL" to 1,
        "SMALL_ENUM_SIGNAL" to 2,
        "REDACTED_VALUE_CHANGED" to 3,
        "VALUE_CHANGED" to 4,
        "NUMERIC_VALUE_CHANGED" to 5
    )
}
