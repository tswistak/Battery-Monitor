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
    private val SYSFS_CYCLE_COUNT_PATHS = arrayOf(
        "$SYSFS_ROOT/battery/cycle_count", "$SYSFS_ROOT/bms/cycle_count"
    )
    private val SYSFS_FULL_CHARGE_PATHS = arrayOf(
        "$SYSFS_ROOT/battery/charge_full", "$SYSFS_ROOT/bms/charge_full"
    )
    private val SYSFS_DESIGN_CHARGE_PATHS = arrayOf(
        "$SYSFS_ROOT/battery/charge_full_design", "$SYSFS_ROOT/bms/charge_full_design"
    )
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
        fun property(key: String): Long? {
            val commands = if (key == "current_now" || key == "current_average") {
                listOf("cmd battery get -f $key", "cmd battery get $key")
            } else listOf("cmd battery get $key")
            for (command in commands) {
                val raw = executor.runRaw("$command 2>/dev/null")
                parseLong(raw)?.let {
                    addLabeledValue(snapshot.metadataLabels, snapshot.metadataValues, command, raw)
                    snapshot.fieldSources[key] = command
                    return it
                }
            }
            return null
        }

        fun sysfs(paths: Array<String>, key: String): Long? {
            for (path in paths) {
                parseLong(executor.run("cat $path 2>/dev/null"))?.let {
                    snapshot.fieldSources[key] = path
                    return it
                }
            }
            return null
        }
        snapshot.chargeCounterUah =
            property("charge_counter") ?: parseLong(batteryDump["Charge counter"])?.also {
                snapshot.fieldSources["charge_counter"] = "dumpsys battery / Charge counter"
            }
        snapshot.currentNowUa = property("current_now") ?: sysfs(
            arrayOf("$SYSFS_ROOT/battery/current_now", "$SYSFS_ROOT/bms/current_now"), "current_now"
        )
        snapshot.currentAverageUa = property("current_average") ?: sysfs(
            arrayOf("$SYSFS_ROOT/battery/current_avg", "$SYSFS_ROOT/bms/current_avg"),
            "current_average"
        )
        snapshot.energyCounterNwh = property("energy_counter")
        snapshot.cycleCount = sysfs(SYSFS_CYCLE_COUNT_PATHS, "cycle_count")
        snapshot.fullChargeUah = sysfs(SYSFS_FULL_CHARGE_PATHS, "charge_full")
        snapshot.designChargeUah = sysfs(SYSFS_DESIGN_CHARGE_PATHS, "charge_full_design")
        snapshot.maxChargingCurrentUa = parseLong(batteryDump["Max charging current"])
        snapshot.maxChargingVoltageUv = parseLong(batteryDump["Max charging voltage"])
        snapshot.chargingPolicy = cleanString(batteryDump["Charging policy"])
        snapshot.chargingState = cleanString(batteryDump["Charging state"])
        snapshot.capacityLevel = cleanString(batteryDump["capacity level"])
        listOf(
            "max_charging_current" to "Max charging current",
            "max_charging_voltage" to "Max charging voltage",
            "charging_policy" to "Charging policy",
            "charging_state" to "Charging state",
            "capacity_level" to "capacity level"
        ).forEach { (field, key) ->
            if (batteryDump[key] != null) snapshot.fieldSources[field] = "dumpsys battery / $key"
        }
        collectBatteryManagerFields(snapshot, context, allowPrivilegedBatteryApi)
        collectServiceFields(snapshot, batteryDump)
        collectSysfsFields(snapshot, executor)

        snapshot.capturedAtMillis = System.currentTimeMillis()
        return snapshot
    }

    fun collectBasic(context: Context): AdvancedBatterySnapshot {
        val snapshot = AdvancedBatterySnapshot().apply { accessMethod = "Android" }
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        manager?.let {
            snapshot.chargeCounterUah =
                readIntProperty(it, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.toLong()
            snapshot.currentNowUa =
                readIntProperty(it, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.toLong()
            snapshot.currentAverageUa =
                readIntProperty(it, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)?.toLong()
            snapshot.energyCounterNwh =
                readLongProperty(it, BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        }
        collectBatteryManagerFields(snapshot, context, false)
        listOf(
            "BATTERY_PROPERTY_CHARGE_COUNTER" to snapshot.chargeCounterUah,
            "BATTERY_PROPERTY_CURRENT_NOW" to snapshot.currentNowUa,
            "BATTERY_PROPERTY_CURRENT_AVERAGE" to snapshot.currentAverageUa,
            "BATTERY_PROPERTY_ENERGY_COUNTER" to snapshot.energyCounterNwh,
            "BATTERY_PROPERTY_CAPACITY" to snapshot.reportedCapacityPercent,
            "computeChargeTimeRemaining" to snapshot.chargeTimeRemainingMs
        ).forEach { (key, value) ->
            addLabeledValue(
                snapshot.metadataLabels, snapshot.metadataValues, key, value?.toString()
            )
        }
        listOf(
            "charge_counter" to "BATTERY_PROPERTY_CHARGE_COUNTER",
            "current_now" to "BATTERY_PROPERTY_CURRENT_NOW",
            "current_average" to "BATTERY_PROPERTY_CURRENT_AVERAGE",
            "energy_counter" to "BATTERY_PROPERTY_ENERGY_COUNTER"
        ).forEach { (field, property) ->
            if (snapshot.metadataLabels.contains(property)) snapshot.fieldSources[field] =
                "BatteryManager / $property"
        }
        fun currentFromFile(average: Boolean, key: String): Long? {
            val file = BatteryCurrent.findCurrentFile(File(SYSFS_ROOT), average) ?: return null
            return runCatching {
                file.bufferedReader().use { it.readLine()?.trim()?.toLongOrNull() }
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
        if (snapshot.cycleCount != null) snapshot.fieldSources["cycle_count"] =
            "ACTION_BATTERY_CHANGED / cycle_count"
        snapshot.capturedAtMillis = System.currentTimeMillis()
        return snapshot
    }

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
        fields["status"]?.toIntOrNull()?.takeIf { it in 1..5 }?.let {
            snapshot.chargingState = it.toString()
            snapshot.fieldSources["charging_state"] = "ACTION_BATTERY_CHANGED / status"
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

        snapshot.reportedCapacityPercent =
            readIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CAPACITY)
        snapshot.stateOfHealthPercent =
            readIntProperty(batteryManager, BATTERY_PROPERTY_STATE_OF_HEALTH)
        snapshot.chargeTimeRemainingMs = readChargeTimeRemaining(batteryManager)
        if (snapshot.reportedCapacityPercent != null) snapshot.fieldSources["capacity"] =
            "BatteryManager / BATTERY_PROPERTY_CAPACITY"
        if (snapshot.stateOfHealthPercent != null) snapshot.fieldSources["state_of_health"] =
            "BatteryManager / BATTERY_PROPERTY_STATE_OF_HEALTH"
        if (snapshot.chargeTimeRemainingMs != null) snapshot.fieldSources["charge_time_remaining"] =
            "BatteryManager / computeChargeTimeRemaining"

        if (!allowPrivilegedBatteryApi) return
        addLabeledValue(
            snapshot.metadataLabels,
            snapshot.metadataValues,
            "BATTERY_PROPERTY_CHARGING_POLICY",
            readIntProperty(batteryManager, BATTERY_PROPERTY_CHARGING_POLICY)?.toString()
        )
        addLabeledValue(
            snapshot.metadataLabels,
            snapshot.metadataValues,
            "BATTERY_PROPERTY_STATE_OF_HEALTH",
            snapshot.stateOfHealthPercent?.toString()
        )

        val chargingPolicyFromApi =
            formatChargingPolicy(readIntProperty(batteryManager, BATTERY_PROPERTY_CHARGING_POLICY))
        if (snapshot.chargingPolicy == null && chargingPolicyFromApi != null) {
            snapshot.chargingPolicy = chargingPolicyFromApi
            snapshot.fieldSources["charging_policy"] =
                "BatteryManager / BATTERY_PROPERTY_CHARGING_POLICY"
        }

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

    private fun readChargeTimeRemaining(batteryManager: BatteryManager?): Long? {
        try {
            val method = BatteryManager::class.java.getMethod("computeChargeTimeRemaining")
            val value = method.invoke(batteryManager)
            if (value !is Long) return null

            return if (value >= 0) value else null
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
