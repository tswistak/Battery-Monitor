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
package codes.swistak.batterymonitor.advancedstats

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import codes.swistak.batterymonitor.common.CommandExecutor
import codes.swistak.batterymonitor.monitoring.BatteryCurrent
import java.io.File

internal object AdvancedBatteryStatsCollector {
    private const val BATTERY_PROPERTY_MANUFACTURING_DATE = 7
    private const val BATTERY_PROPERTY_FIRST_USAGE_DATE = 8
    private const val BATTERY_PROPERTY_CHARGING_POLICY = 9
    private const val BATTERY_PROPERTY_STATE_OF_HEALTH = 10
    private const val BATTERY_PROPERTY_SERIAL_NUMBER = 11
    private const val BATTERY_PROPERTY_PART_STATUS = 12
    private const val BATTERY_PROPERTY_MANUFACTURER = 13
    private const val BATTERY_PROPERTY_MODEL_NAME = 14
    private const val BATTERY_PROPERTY_VOLTAGE_MIN_DESIGN = 15
    private const val SYSFS_ROOT = "/sys/class/power_supply"
    private val SYSFS_FALLBACK_DIRS = arrayOf(
        "$SYSFS_ROOT/battery",
        "$SYSFS_ROOT/bms",
        "$SYSFS_ROOT/usb",
        "$SYSFS_ROOT/main",
        "$SYSFS_ROOT/wireless"
    )
    private val SYSFS_IGNORED_NAMES: MutableSet<String> = LinkedHashSet(
        mutableListOf(
            "device", "subsystem", "power"
        )
    )

    private val MONITORING_SERVICE_FIELDS = setOf(
        "Charge counter",
        "voltage",
        "temperature",
        "health",
        "status",
        "level",
        "scale",
        "plugged",
        "AC powered",
        "USB powered",
        "Wireless powered",
        "Dock powered"
    )
    private val MONITORING_SYSFS_FIELDS = listOf(
        "current_now",
        "current_avg",
        "charge_counter",
        "voltage_now",
        "temp",
        "health",
        "status",
        "capacity"
    )
    private const val MONITORING_FILE_MARKER = "__BATTERY_MONITOR_FILE__"

    fun collectMonitoring(
        executor: CommandExecutor, context: Context? = null
    ): AdvancedBatterySnapshot {
        val snapshot = AdvancedBatterySnapshot()
        collectBatteryManagerFields(snapshot, context, false)
        val batteryDump =
            parseDump(executor.runRaw("dumpsys battery")).filterKeys { it in MONITORING_SERVICE_FIELDS }
                .toMutableMap()
        collectServiceFields(snapshot, batteryDump)

        val supplies = discoverSysfsDirs(executor).map { it.substringAfterLast('/') }
        val metadata = readMonitoringFiles(executor, supplies.flatMap { supply ->
            listOf("$supply/type", "$supply/uevent")
        })
        val classified = metadata.toMutableMap().apply {
            supplies.forEach { put("$it/", "") }
        }
        val batterySupplies = batterySupplyNames(classified)
        val fields =
            metadata.filterKeys { it.substringBefore('/') in batterySupplies } + readMonitoringFiles(
                executor,
                batterySupplies.flatMap { supply ->
                    MONITORING_SYSFS_FIELDS.map { "$supply/$it" }
                })
        snapshot.sysfsLabels.addAll(fields.keys)
        snapshot.sysfsValues.addAll(fields.values)
        projectCollectedFields(snapshot, batteryDump)

        if (snapshot.currentNowUa == null) snapshot.currentNowUa =
            commandProperty(snapshot, executor, "current_now", BatteryCurrent::isValidMicroAmps)
        if (snapshot.currentAverageUa == null) snapshot.currentAverageUa =
            commandProperty(snapshot, executor, "current_average", BatteryCurrent::isValidMicroAmps)
        if (snapshot.chargeCounterUah == null) snapshot.chargeCounterUah =
            commandProperty(snapshot, executor, "charge_counter") { it >= 0 } ?: parseLong(
                batteryDump["Charge counter"]
            )?.takeIf { it >= 0 }?.also {
                snapshot.fieldSources["charge_counter"] = "dumpsys battery / Charge counter"
            }
        snapshot.capturedAtMillis = System.currentTimeMillis()
        return snapshot
    }

    private fun readMonitoringFiles(
        executor: CommandExecutor, relativePaths: List<String>
    ): Map<String, String> {
        if (relativePaths.isEmpty()) return emptyMap()
        val paths = relativePaths.map { "$SYSFS_ROOT/$it" }
        val command = """
            for battery_file in ${paths.joinToString(" ") { "'$it'" }}; do
                [ -r "${'$'}battery_file" ] || continue
                printf '\n$MONITORING_FILE_MARKER%s\n' "${'$'}battery_file"
                cat "${'$'}battery_file" 2>/dev/null
                printf '\n'
            done
        """.trimIndent()
        val output = executor.runRaw(command) ?: return emptyMap()
        val fields = linkedMapOf<String, String>()
        var path: String? = null
        val value = StringBuilder()
        fun addField() {
            val key = path?.takeIf { it in paths }?.removePrefix("$SYSFS_ROOT/") ?: return
            value.toString().trim().takeIf(String::isNotEmpty)?.let { fields[key] = it }
        }
        output.lineSequence().forEach { line ->
            if (line.startsWith(MONITORING_FILE_MARKER)) {
                addField()
                path = line.removePrefix(MONITORING_FILE_MARKER)
                value.setLength(0)
            } else if (path != null) value.appendLine(line)
        }
        addField()
        return fields
    }

    private fun commandProperty(
        snapshot: AdvancedBatterySnapshot,
        executor: CommandExecutor,
        key: String,
        valid: (Long) -> Boolean = { true }
    ): Long? {
        val commands = if (key == "current_now" || key == "current_average") {
            listOf("cmd battery get -f $key", "cmd battery get $key")
        } else listOf("cmd battery get $key")
        for (command in commands) {
            val raw = executor.runRaw("$command 2>/dev/null")
            parseLong(raw)?.let {
                addLabeledValue(snapshot.metadataLabels, snapshot.metadataValues, command, raw)
                if (valid(it)) {
                    snapshot.fieldSources[key] = command
                    return it
                }
            }
        }
        return null
    }

    fun collect(
        executor: CommandExecutor,
        accessMethod: String?,
        remoteUid: Int,
        context: Context?,
        allowPrivilegedBatteryApi: Boolean
    ): AdvancedBatterySnapshot {
        val snapshot = AdvancedBatterySnapshot()
        snapshot.accessMethod = accessMethod
        snapshot.remoteUid = remoteUid
        val rawDump = executor.runRaw("dumpsys battery")
        val batteryDump = parseDump(rawDump)
        fun property(key: String, valid: (Long) -> Boolean = { true }): Long? =
            commandProperty(snapshot, executor, key, valid)

        snapshot.chargeCounterUah = property("charge_counter") { it >= 0 }
            ?: parseLong(batteryDump["Charge counter"])?.takeIf { it >= 0 }?.also {
                snapshot.fieldSources["charge_counter"] = "dumpsys battery / Charge counter"
            }
        snapshot.currentNowUa = property("current_now", BatteryCurrent::isValidMicroAmps)
        snapshot.currentAverageUa = property("current_average", BatteryCurrent::isValidMicroAmps)
        snapshot.energyCounterNwh = property("energy_counter") { it >= 0 }
        snapshot.maxChargingCurrentUa =
            parseLong(batteryDump["Max charging current"])?.takeIf { it >= 0 }
        snapshot.maxChargingVoltageUv =
            parseLong(batteryDump["Max charging voltage"])?.takeIf { it >= 0 }
        snapshot.chargingPolicy =
            cleanString(batteryDump["Charging policy"])?.takeUnless { it == "-1" }
        snapshot.chargingState =
            cleanString(batteryDump["Charging state"])?.takeUnless { it == "-1" }
        snapshot.capacityLevel =
            cleanString(batteryDump["capacity level"])?.takeUnless { it == "-1" }
        listOf(
            Triple("max_charging_current", "Max charging current", snapshot.maxChargingCurrentUa),
            Triple("max_charging_voltage", "Max charging voltage", snapshot.maxChargingVoltageUv),
            Triple("charging_policy", "Charging policy", snapshot.chargingPolicy),
            Triple("charging_state", "Charging state", snapshot.chargingState),
            Triple("capacity_level", "capacity level", snapshot.capacityLevel)
        ).forEach { (field, key, value) ->
            if (value != null) snapshot.fieldSources[field] = "dumpsys battery / $key"
        }
        collectBatteryManagerFields(snapshot, context, allowPrivilegedBatteryApi)
        collectServiceFields(snapshot, batteryDump)
        collectSysfsFields(snapshot, executor)
        projectCollectedFields(snapshot, batteryDump)

        snapshot.capturedAtMillis = System.currentTimeMillis()
        return snapshot
    }

    internal fun batterySupplyNames(fields: Map<String, String>): List<String> =
        fields.keys.map { it.substringBefore('/') }.distinct().mapNotNull { supply ->
            val type = fields["$supply/type"]?.trim() ?: fields["$supply/uevent"]?.lineSequence()
                ?.firstOrNull { it.startsWith("POWER_SUPPLY_TYPE=") }?.substringAfter('=')?.trim()
            BatteryCurrent.batterySupplyRank(supply, type)?.let { supply to it }
        }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase(java.util.Locale.ROOT) })
            .map { it.first }

    internal fun projectCollectedFields(
        snapshot: AdvancedBatterySnapshot, batteryDump: Map<String, String>
    ) {
        if (snapshot.reportedCapacityPercent == null) {
            val level = parseLong(batteryDump["level"])
            val scale = parseLong(batteryDump["scale"])
            if (level != null && scale != null && scale > 0 && level in 0..scale) {
                snapshot.reportedCapacityPercent = (level.toDouble() * 100 / scale).toInt()
                snapshot.fieldSources["capacity"] = "dumpsys battery / level / scale"
            }
        }
        if (snapshot.chargingState == null) {
            parseLong(batteryDump["status"])?.takeIf { it in 1..5 }?.let {
                snapshot.chargingState = it.toString()
                snapshot.fieldSources["charging_state"] = "dumpsys battery / status"
            }
        }

        val fields = snapshot.sysfsLabels.zip(snapshot.sysfsValues).toMap()
        val supplies = batterySupplyNames(fields)
        fun number(field: String, key: String = field, valid: (Long) -> Boolean = { true }): Long? {
            for (supply in supplies) {
                val path = "$supply/$key"
                parseLong(fields[path])?.takeIf(valid)?.let {
                    snapshot.fieldSources[field] = "$SYSFS_ROOT/$path"
                    return it
                }
            }
            return null
        }

        fun text(field: String, key: String): String? {
            for (supply in supplies) {
                val path = "$supply/$key"
                cleanString(fields[path])?.let {
                    snapshot.fieldSources[field] = "$SYSFS_ROOT/$path"
                    return it
                }
            }
            return null
        }
        if (snapshot.chargeCounterUah == null) snapshot.chargeCounterUah =
            number("charge_counter") { it >= 0 }
        if (snapshot.currentNowUa == null) snapshot.currentNowUa =
            number("current_now", valid = BatteryCurrent::isValidMicroAmps)
        if (snapshot.currentAverageUa == null) snapshot.currentAverageUa =
            number("current_average", "current_avg", BatteryCurrent::isValidMicroAmps)
        // Linux energy_now is µWh; the BatteryManager snapshot uses nWh.
        if (snapshot.energyCounterNwh == null) snapshot.energyCounterNwh = number(
            "energy_counter", "energy_now"
        ) { it >= 0 && it <= Long.MAX_VALUE / 1000 }?.let { it * 1000 }
        if (snapshot.reportedCapacityPercent == null) snapshot.reportedCapacityPercent =
            number("capacity") { it in 0..100 }?.toInt()
        if (snapshot.stateOfHealthPercent == null) snapshot.stateOfHealthPercent =
            number("state_of_health") { it in 0..100 }?.toInt()
        // Linux time_to_full_now is seconds; the snapshot uses milliseconds.
        if (snapshot.chargeTimeRemainingMs == null) snapshot.chargeTimeRemainingMs = number(
            "charge_time_remaining", "time_to_full_now"
        ) { it >= 0 && it <= Long.MAX_VALUE / 1000 }?.let { it * 1000 }
        if (snapshot.chargingState == null) snapshot.chargingState =
            text("charging_state", "status")
        if (snapshot.capacityLevel == null) snapshot.capacityLevel =
            text("capacity_level", "capacity_level")
        if (snapshot.cycleCount == null) snapshot.cycleCount = number("cycle_count") { it > 0 }
        if (snapshot.fullChargeUah == null) snapshot.fullChargeUah =
            number("charge_full") { it > 0 }
        if (snapshot.designChargeUah == null) snapshot.designChargeUah =
            number("charge_full_design") { it > 0 }

        val fullSource = snapshot.fieldSources["charge_full"]
        val designSource = snapshot.fieldSources["charge_full_design"]
        val mismatchedSysfsPair =
            fullSource?.startsWith("$SYSFS_ROOT/") == true && designSource?.startsWith("$SYSFS_ROOT/") == true && fullSource.substringBeforeLast(
                '/'
            ) != designSource.substringBeforeLast('/')
        val pairSupply = supplies.firstOrNull {
            (parseLong(fields["$it/charge_full"])
                ?: 0) > 0 && (parseLong(fields["$it/charge_full_design"]) ?: 0) > 0
        }
        if (pairSupply != null && mismatchedSysfsPair) {
            snapshot.fullChargeUah = parseLong(fields["$pairSupply/charge_full"])
            snapshot.designChargeUah = parseLong(fields["$pairSupply/charge_full_design"])
            snapshot.fieldSources["charge_full"] = "$SYSFS_ROOT/$pairSupply/charge_full"
            snapshot.fieldSources["charge_full_design"] =
                "$SYSFS_ROOT/$pairSupply/charge_full_design"
        }
    }

    fun collectBasic(context: Context): AdvancedBatterySnapshot {
        val snapshot = AdvancedBatterySnapshot().apply { accessMethod = "Android" }
        collectBatteryManagerFields(snapshot, context, false)
        fun currentFromFile(average: Boolean, key: String): Long? {
            val file = BatteryCurrent.findCurrentFile(File(SYSFS_ROOT), average) ?: return null
            return runCatching {
                file.bufferedReader().use {
                    it.readLine()?.trim()?.toLongOrNull()?.takeIf(BatteryCurrent::isValidMicroAmps)
                }
            }.getOrNull()?.also { snapshot.fieldSources[key] = file.path }
        }
        if (snapshot.currentNowUa == null) snapshot.currentNowUa =
            currentFromFile(false, "current_now")
        if (snapshot.currentAverageUa == null) snapshot.currentAverageUa =
            currentFromFile(true, "current_average")
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        battery?.extras?.let { extras ->
            extras.keySet().sorted().forEach { key ->
                @Suppress("DEPRECATION") val value = extras.get(key)
                if (value is Number || value is Boolean || value is String) {
                    addLabeledValue(
                        snapshot.serviceLabels, snapshot.serviceValues, key, value.toString()
                    )
                }
            }
        }
        projectBatteryIntentFields(
            snapshot, snapshot.serviceLabels.zip(snapshot.serviceValues).toMap()
        )
        snapshot.cycleCount = AndroidCycleCount.read(battery)
        if (Build.VERSION.SDK_INT >= 34 && snapshot.cycleCount != null) snapshot.fieldSources["cycle_count"] =
            "ACTION_BATTERY_CHANGED / ${BatteryManager.EXTRA_CYCLE_COUNT}"
        snapshot.capturedAtMillis = System.currentTimeMillis()
        return snapshot
    }

    @SuppressLint("InlinedApi")
    internal fun projectBatteryIntentFields(
        snapshot: AdvancedBatterySnapshot, fields: Map<String, String>
    ) {
        if (snapshot.chargeCounterUah == null) {
            parseLong(fields["charge_counter"])?.takeIf { it >= 0 }?.let {
                snapshot.chargeCounterUah = it
                snapshot.fieldSources["charge_counter"] = "ACTION_BATTERY_CHANGED / charge_counter"
            }
        }
        if (snapshot.reportedCapacityPercent == null) {
            val level = fields["level"]?.toIntOrNull()?.toLong()
            val scale = fields["scale"]?.toIntOrNull()?.toLong()
            if (level != null && scale != null && scale > 0 && level in 0..scale) {
                snapshot.reportedCapacityPercent = (level * 100 / scale).toInt()
                snapshot.fieldSources["capacity"] = "ACTION_BATTERY_CHANGED / level / scale"
            }
        }
        parseLong(fields["max_charging_current"])?.takeIf { it >= 0 }?.let {
            snapshot.maxChargingCurrentUa = it
            snapshot.fieldSources["max_charging_current"] =
                "ACTION_BATTERY_CHANGED / max_charging_current"
        }
        parseLong(fields["max_charging_voltage"])?.takeIf { it >= 0 }?.let {
            snapshot.maxChargingVoltageUv = it
            snapshot.fieldSources["max_charging_voltage"] =
                "ACTION_BATTERY_CHANGED / max_charging_voltage"
        }
        fields[BatteryManager.EXTRA_CAPACITY_LEVEL]?.toIntOrNull()?.takeIf { it in 0..5 }?.let {
            snapshot.capacityLevel = it.toString()
            snapshot.fieldSources["capacity_level"] =
                "ACTION_BATTERY_CHANGED / ${BatteryManager.EXTRA_CAPACITY_LEVEL}"
        }
        val hardwareStatus =
            fields[BatteryManager.EXTRA_CHARGING_STATUS]?.toIntOrNull()?.takeIf { it in 0..5 }
        if (hardwareStatus != null) {
            snapshot.chargingState = hardwareStatus.toString()
            snapshot.fieldSources["charging_state"] =
                "ACTION_BATTERY_CHANGED / ${BatteryManager.EXTRA_CHARGING_STATUS}"
        } else if (snapshot.chargingState == null || snapshot.fieldSources["charging_state"] == "ACTION_BATTERY_CHANGED / status") {
            fields["status"]?.toIntOrNull()?.takeIf { it in 1..5 }?.let {
                snapshot.chargingState = it.toString()
                snapshot.fieldSources["charging_state"] = "ACTION_BATTERY_CHANGED / status"
            }
        }
    }

    fun collectAppRaw(context: Context, cancelled: () -> Boolean): AdvancedBatterySnapshot {
        val shell = codes.swistak.batterymonitor.common.PrivilegedShellExecutor()
        return collect(object : CommandExecutor {
            override fun run(command: String): String? =
                if (cancelled()) null else shell.run(command)

            override fun runRaw(command: String): String? =
                if (cancelled()) null else shell.runRaw(command)
        }, "App UID", android.os.Process.myUid(), context, true)
    }

    private fun parseDump(dump: String?): MutableMap<String, String> {
        val values: MutableMap<String, String> = LinkedHashMap()
        // dumpsys may exit successfully with an error instead of a battery-service dump.
        if (dump == null || dump.lineSequence()
                .none { it.trim() == "Current Battery Service state:" }
        ) return values

        val lines = dump.split("\\r?\\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (s in lines) {
            val line = s.trim()
            val separator = line.indexOf(':')
            if (separator <= 0) continue

            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) values.put(key, value)
        }

        return values
    }

    private fun collectBatteryManagerFields(
        snapshot: AdvancedBatterySnapshot, context: Context?, allowPrivilegedBatteryApi: Boolean
    ) {
        if (context == null) return

        val batteryManager =
            context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager? ?: return

        collectBatteryManagerReadings(
            snapshot, batteryManager::getIntProperty, batteryManager::getLongProperty, {
                if (Build.VERSION.SDK_INT >= 28) batteryManager.computeChargeTimeRemaining() else null
            }, allowPrivilegedBatteryApi
        )

        if (!allowPrivilegedBatteryApi) return

        addLabeledValue(
            snapshot.metadataLabels,
            snapshot.metadataValues,
            "BATTERY_PROPERTY_MANUFACTURING_DATE",
            readLongProperty(batteryManager, BATTERY_PROPERTY_MANUFACTURING_DATE)?.toString()
        )
        addLabeledValue(
            snapshot.metadataLabels,
            snapshot.metadataValues,
            "BATTERY_PROPERTY_FIRST_USAGE_DATE",
            readLongProperty(batteryManager, BATTERY_PROPERTY_FIRST_USAGE_DATE)?.toString()
        )
        addLabeledValue(
            snapshot.metadataLabels,
            snapshot.metadataValues,
            "BATTERY_PROPERTY_SERIAL_NUMBER",
            readStringProperty(batteryManager, BATTERY_PROPERTY_SERIAL_NUMBER)
        )
        addLabeledValue(
            snapshot.metadataLabels,
            snapshot.metadataValues,
            "BATTERY_PROPERTY_PART_STATUS",
            readIntProperty(batteryManager, BATTERY_PROPERTY_PART_STATUS)?.toString()
        )
        addLabeledValue(
            snapshot.metadataLabels,
            snapshot.metadataValues,
            "BATTERY_PROPERTY_MANUFACTURER",
            readStringProperty(batteryManager, BATTERY_PROPERTY_MANUFACTURER)
        )
        addLabeledValue(
            snapshot.metadataLabels,
            snapshot.metadataValues,
            "BATTERY_PROPERTY_MODEL_NAME",
            readStringProperty(batteryManager, BATTERY_PROPERTY_MODEL_NAME)
        )
        addLabeledValue(
            snapshot.metadataLabels,
            snapshot.metadataValues,
            "BATTERY_PROPERTY_VOLTAGE_MIN_DESIGN",
            readLongProperty(batteryManager, BATTERY_PROPERTY_VOLTAGE_MIN_DESIGN)?.toString()
        )
    }

    internal fun collectBatteryManagerReadings(
        snapshot: AdvancedBatterySnapshot,
        getIntProperty: (Int) -> Int,
        getLongProperty: (Int) -> Long,
        getChargeTimeRemaining: () -> Long? = { null },
        collectChargingPolicy: Boolean = false
    ) {
        val properties = linkedMapOf(
            "BATTERY_PROPERTY_CHARGE_COUNTER" to runCatching {
                getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toLong()
            }.getOrNull(), "BATTERY_PROPERTY_CURRENT_NOW" to runCatching {
                getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong()
            }.getOrNull(), "BATTERY_PROPERTY_CURRENT_AVERAGE" to runCatching {
                getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE).toLong()
            }.getOrNull(), "BATTERY_PROPERTY_ENERGY_COUNTER" to runCatching {
                getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
            }.getOrNull(), "BATTERY_PROPERTY_CAPACITY" to runCatching {
                getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toLong()
            }.getOrNull(), "BATTERY_PROPERTY_STATE_OF_HEALTH" to runCatching {
                getIntProperty(BATTERY_PROPERTY_STATE_OF_HEALTH).toLong()
            }.getOrNull(), "computeChargeTimeRemaining" to runCatching {
                getChargeTimeRemaining()
            }.getOrNull()
        )
        if (collectChargingPolicy) properties["BATTERY_PROPERTY_CHARGING_POLICY"] = runCatching {
            getIntProperty(BATTERY_PROPERTY_CHARGING_POLICY).toLong()
        }.getOrNull()
        properties.forEach { (key, value) ->
            addLabeledValue(
                snapshot.metadataLabels, snapshot.metadataValues, key, value?.toString()
            )
        }
        fun value(field: String, property: String, valid: (Long) -> Boolean): Long? =
            properties[property]?.takeIf(valid)?.also {
                snapshot.fieldSources[field] = "BatteryManager / $property"
            }
        if (snapshot.chargeCounterUah == null) snapshot.chargeCounterUah =
            value("charge_counter", "BATTERY_PROPERTY_CHARGE_COUNTER") { it >= 0 }
        if (snapshot.currentNowUa == null) snapshot.currentNowUa =
            value("current_now", "BATTERY_PROPERTY_CURRENT_NOW", BatteryCurrent::isValidMicroAmps)
        if (snapshot.currentAverageUa == null) snapshot.currentAverageUa = value(
            "current_average", "BATTERY_PROPERTY_CURRENT_AVERAGE", BatteryCurrent::isValidMicroAmps
        )
        if (snapshot.energyCounterNwh == null) snapshot.energyCounterNwh =
            value("energy_counter", "BATTERY_PROPERTY_ENERGY_COUNTER") { it >= 0 }
        if (snapshot.reportedCapacityPercent == null) snapshot.reportedCapacityPercent =
            value("capacity", "BATTERY_PROPERTY_CAPACITY") { it in 0..100 }?.toInt()
        if (snapshot.stateOfHealthPercent == null) snapshot.stateOfHealthPercent =
            value("state_of_health", "BATTERY_PROPERTY_STATE_OF_HEALTH") { it in 0..100 }?.toInt()
        if (snapshot.chargeTimeRemainingMs == null) snapshot.chargeTimeRemainingMs =
            value("charge_time_remaining", "computeChargeTimeRemaining") { it >= 0 }
        if (snapshot.chargingPolicy == null) snapshot.chargingPolicy =
            value("charging_policy", "BATTERY_PROPERTY_CHARGING_POLICY") { it >= 0 }?.toInt()
                ?.let(::formatChargingPolicy)
    }


    private fun collectServiceFields(
        snapshot: AdvancedBatterySnapshot, batteryDump: MutableMap<String, String>
    ) {
        batteryDump.forEach { (key, value) ->
            addLabeledValue(
                snapshot.serviceLabels, snapshot.serviceValues, key, value
            )
        }
    }

    private fun collectSysfsFields(snapshot: AdvancedBatterySnapshot, executor: CommandExecutor) {
        val seenLabels: MutableSet<String> = LinkedHashSet()
        val dirs = discoverSysfsDirs(executor)

        for (dir in dirs) {
            val entries = splitLines(executor.run("ls $dir 2>/dev/null"))
            val prefix = dir.substring(dir.lastIndexOf('/') + 1) + "/"

            for (j in entries.indices) {
                val entry = entries[j].trim()
                if (!isValidSysfsEntry(entry) || SYSFS_IGNORED_NAMES.contains(entry)) continue

                val label = prefix + entry
                if (seenLabels.contains(label)) continue

                val value = executor.runRaw("cat $dir/$entry 2>/dev/null") ?: continue

                seenLabels.add(label)
                addLabeledValue(snapshot.sysfsLabels, snapshot.sysfsValues, label, value)
            }
        }
    }


    private fun isValidSysfsEntry(entry: String?): Boolean {
        if (entry.isNullOrEmpty() || entry.indexOf('/') >= 0) return false

        for (i in entry.indices) {
            val ch = entry[i]
            if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == '.')) return false
        }

        return true
    }

    private fun splitLines(value: String?): MutableList<String> {
        return (if (value == null) mutableListOf() else mutableListOf(
            *value.split(
                "\\r?\\n".toRegex()
            ).dropLastWhile { it.isEmpty() }.toTypedArray()
        ))
    }

    private fun discoverSysfsDirs(executor: CommandExecutor): MutableList<String> {
        val entries = splitLines(executor.run("ls $SYSFS_ROOT 2>/dev/null"))
        val dirs = LinkedHashSet<String>()

        for (s in entries) {
            val entry = s.trim()
            if (!isValidSysfsEntry(entry)) continue

            dirs.add("$SYSFS_ROOT/$entry")
        }

        if (dirs.isNotEmpty()) return ArrayList(dirs)

        return mutableListOf(*SYSFS_FALLBACK_DIRS)
    }

    private fun addLabeledValue(
        labels: ArrayList<String>, values: ArrayList<String>, label: String?, value: String?
    ) {
        if (label == null || value == null) return

        labels.add(label)
        values.add(value)
    }

    private fun readIntProperty(batteryManager: BatteryManager, id: Int): Int? {
        try {
            val value = batteryManager.getIntProperty(id)
            return if (value != Int.MIN_VALUE) value else null
        } catch (e: Throwable) {
            return null
        }
    }

    private fun readLongProperty(batteryManager: BatteryManager, id: Int): Long? {
        try {
            val value = batteryManager.getLongProperty(id)
            return if (value != Long.MIN_VALUE) value else null
        } catch (e: Throwable) {
            return null
        }
    }

    private fun readStringProperty(batteryManager: BatteryManager?, id: Int): String? {
        try {
            val method = BatteryManager::class.java.getMethod(
                "getStringProperty", Int::class.javaPrimitiveType
            )
            val value = method.invoke(batteryManager, id)
            return value as? String
        } catch (e: Throwable) {
            return null
        }
    }

    internal fun parseLong(value: String?): Long? = value?.trim()?.toLongOrNull()

    private fun cleanString(value: String?): String? {
        if (value == null) return null

        val cleaned = value.trim()
        return cleaned.ifEmpty { null }
    }

    private fun formatChargingPolicy(value: Int?): String? {
        if (value == null) return null

        return when (value) {
            1 -> "default"
            2 -> "adaptive_aon"
            3 -> "adaptive_ac"
            4 -> "adaptive_longlife"
            5 -> "force_full_charge"
            else -> value.toString()
        }
    }

}

internal object AndroidCycleCount {
    fun read(intent: Intent?): Long? = if (Build.VERSION.SDK_INT >= 34) {
        value(
            intent?.hasExtra(BatteryManager.EXTRA_CYCLE_COUNT) == true,
            intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) ?: -1
        )
    } else null

    internal fun value(present: Boolean, count: Int): Long? =
        count.takeIf { present && it >= 0 }?.toLong()
}
