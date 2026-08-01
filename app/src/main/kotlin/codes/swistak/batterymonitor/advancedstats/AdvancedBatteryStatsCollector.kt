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
import android.os.BatteryManager
import codes.swistak.batterymonitor.R
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

internal object AdvancedBatteryStatsCollector {
    private const val COMMAND_TIMEOUT_SECONDS = 10L
    private const val MAX_COMMAND_OUTPUT_BYTES = 256 * 1024
    private val LONG_PATTERN: Pattern = Pattern.compile("(-?\\d+)")
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
    private val CURATED_DUMP_KEYS: MutableSet<String> = LinkedHashSet(
        mutableListOf(
            "max charging current",
            "max charging voltage",
            "charge counter",
            "current now",
            "current average",
            "charging policy",
            "charging state",
            "capacity level"
        )
    )
    private val SYSFS_IGNORED_NAMES: MutableSet<String> = LinkedHashSet(
        mutableListOf(
            "device", "subsystem", "power", "uevent", "type"
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

        val batteryDump = parseDump(executor.run("dumpsys battery"))

        snapshot.chargeCounterUah =
            firstLong(readProperty(executor, "charge_counter"), batteryDump["Charge counter"])
        snapshot.currentNowUa = firstLong(readProperty(executor, "current_now"))
        snapshot.currentAverageUa = firstLong(readProperty(executor, "current_average"))
        snapshot.energyCounterNwh =
            firstLong(readProperty(executor, "energy_counter"), readProperty(executor, "counter"))
        snapshot.cycleCount = firstLong(readSysfs(executor, SYSFS_CYCLE_COUNT_PATHS))
        snapshot.fullChargeUah = firstLong(readSysfs(executor, SYSFS_FULL_CHARGE_PATHS))
        snapshot.designChargeUah = firstLong(readSysfs(executor, SYSFS_DESIGN_CHARGE_PATHS))
        snapshot.maxChargingCurrentUa = firstLong(batteryDump["Max charging current"])
        snapshot.maxChargingVoltageUv = firstLong(batteryDump["Max charging voltage"])
        snapshot.chargingPolicy = cleanString(batteryDump["Charging policy"])
        snapshot.chargingState = cleanString(batteryDump["Charging state"])
        snapshot.capacityLevel = cleanString(batteryDump["capacity level"])
        collectBatteryManagerFields(snapshot, context, allowPrivilegedBatteryApi)
        collectServiceFields(snapshot, batteryDump)
        collectSysfsFields(snapshot, executor)

        return snapshot
    }

    private fun readProperty(executor: CommandExecutor, propertyName: String?): String? {
        return executor.run("cmd battery get $propertyName 2>/dev/null")
    }

    private fun readSysfs(executor: CommandExecutor, paths: Array<String>): String? {
        for (path in paths) {
            val value = executor.run("cat $path 2>/dev/null")
            if (value != null) return value
        }

        return null
    }

    private fun parseDump(dump: String?): MutableMap<String, String> {
        val values: MutableMap<String, String> = LinkedHashMap()
        if (dump == null) return values

        val lines = dump.split("\\r?\\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (s in lines) {
            val line = s.trim()
            val separator = line.indexOf(':')
            if (separator <= 0) continue

            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (!key.isEmpty() && !value.isEmpty()) values.put(key, value)
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

        if (!allowPrivilegedBatteryApi) return

        val chargingPolicyFromApi =
            formatChargingPolicy(readIntProperty(batteryManager, BATTERY_PROPERTY_CHARGING_POLICY))
        if (snapshot.chargingPolicy == null) snapshot.chargingPolicy = chargingPolicyFromApi

        addLabeledValue(
            snapshot.metadataLabels,
            snapshot.metadataValues,
            localize(context, R.string.advanced_field_manufacturing_date, "Manufacturing date"),
            formatEpochSeconds(
                readLongProperty(
                    batteryManager, BATTERY_PROPERTY_MANUFACTURING_DATE
                )
            )
        )
        addLabeledValue(
            snapshot.metadataLabels,
            snapshot.metadataValues,
            localize(context, R.string.advanced_field_first_use_date, "First use date"),
            formatEpochSeconds(readLongProperty(batteryManager, BATTERY_PROPERTY_FIRST_USAGE_DATE))
        )
        addLabeledValue(
            snapshot.metadataLabels, snapshot.metadataValues, localize(
                context, R.string.advanced_field_battery_serial_number, "Battery serial number"
            ), readStringProperty(batteryManager, BATTERY_PROPERTY_SERIAL_NUMBER)
        )
        addLabeledValue(
            snapshot.metadataLabels,
            snapshot.metadataValues,
            localize(context, R.string.advanced_field_battery_part_status, "Battery part status"),
            formatPartStatus(readIntProperty(batteryManager, BATTERY_PROPERTY_PART_STATUS))
        )
        addLabeledValue(
            snapshot.metadataLabels, snapshot.metadataValues, localize(
                context,
                R.string.advanced_field_battery_part_manufacturer,
                "Battery part manufacturer"
            ), readStringProperty(batteryManager, BATTERY_PROPERTY_MANUFACTURER)
        )
        addLabeledValue(
            snapshot.metadataLabels,
            snapshot.metadataValues,
            localize(context, R.string.advanced_field_battery_model_name, "Battery model name"),
            readStringProperty(batteryManager, BATTERY_PROPERTY_MODEL_NAME)
        )
        addLabeledValue(
            snapshot.metadataLabels,
            snapshot.metadataValues,
            localize(
                context, R.string.advanced_field_minimum_design_voltage, "Minimum design voltage"
            ),
            formatMicroVolts(readLongProperty(batteryManager, BATTERY_PROPERTY_VOLTAGE_MIN_DESIGN))
        )
    }

    private fun collectServiceFields(
        snapshot: AdvancedBatterySnapshot, batteryDump: MutableMap<String, String>
    ) {
        for (entry in batteryDump.entries) {
            val key: String = entry.key!!
            val lowerKey = key.lowercase()
            if (CURATED_DUMP_KEYS.contains(lowerKey)) continue

            addLabeledValue(
                snapshot.serviceLabels, snapshot.serviceValues, key, cleanString(entry.value)
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

                val value = cleanSysfsValue(executor.run("cat $dir/$entry 2>/dev/null")) ?: continue

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

        if (!dirs.isEmpty()) return ArrayList(dirs)

        return mutableListOf(*SYSFS_FALLBACK_DIRS)
    }

    private fun addLabeledValue(
        labels: ArrayList<String>, values: ArrayList<String>, label: String?, value: String?
    ) {
        if (label == null || value == null) return

        labels.add(label)
        values.add(value)
    }

    private fun localize(context: Context?, stringId: Int, fallback: String?): String? {
        if (context == null) return fallback

        try {
            return context.getString(stringId)
        } catch (e: Exception) {
            return fallback
        }
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
            return if (value is String) cleanString(value) else null
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

    private fun firstLong(vararg values: String?): Long? {
        for (value in values) {
            val parsed = parseLong(value)
            if (parsed != null) return parsed
        }

        return null
    }

    private fun parseLong(value: String?): Long? {
        if (value == null) return null

        val matcher = LONG_PATTERN.matcher(value.replace(",", ""))
        if (!matcher.find()) return null

        try {
            val match = matcher.group(1) ?: return null

            return match.toLong()
        } catch (e: NumberFormatException) {
            return null
        }
    }

    private fun cleanString(value: String?): String? {
        if (value == null) return null

        val cleaned = value.trim()
        return if (!cleaned.isEmpty()) cleaned else null
    }

    private fun cleanSysfsValue(value: String?): String? {
        if (value == null) return null

        val cleaned = value.replace('\u0000', ' ').replace("\\s+".toRegex(), " ").trim()
        if (cleaned.isEmpty() || cleaned.length > 120) return null

        return cleaned
    }

    private fun formatEpochSeconds(value: Long?): String? {
        if (value == null || value <= 0) return null

        return DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            .format(Date(value * 1000L))
    }

    private fun formatPartStatus(value: Int?): String? {
        if (value == null) return null

        return when (value) {
            0 -> "unsupported"
            1 -> "original"
            2 -> "replaced"
            else -> value.toString()
        }
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

    private fun formatMicroVolts(value: Long?): String? {
        if (value == null) return null

        return String.format(Locale.getDefault(), "%.2f V", value / 1000000.0)
    }

    private fun runCommand(command: Array<String>): String? {
        var process: Process? = null

        try {
            process = ProcessBuilder(*command).redirectErrorStream(true).start()
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }

            if (process.exitValue() != 0) return null

            var output = readFully(process.getInputStream()) ?: return null

            output = output.trim()
            return if (!output.isEmpty()) output else null
        } catch (e: Exception) {
            return null
        } finally {
            process?.destroy()
        }
    }

    @Throws(Exception::class)
    private fun readFully(inputStream: InputStream): String? {
        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var bytesRead: Int

        while ((inputStream.read(buffer).also { bytesRead = it }) != -1) {
            if (outputStream.size() + bytesRead > MAX_COMMAND_OUTPUT_BYTES) return null

            outputStream.write(buffer, 0, bytesRead)
        }

        return String(outputStream.toByteArray(), StandardCharsets.UTF_8)
    }

    internal interface CommandExecutor {
        fun run(command: String): String?
    }

    internal class RootExecutor : CommandExecutor {
        override fun run(command: String): String? {
            return runCommand(arrayOf("su", "-c", command))
        }
    }

    internal class PrivilegedShellExecutor : CommandExecutor {
        override fun run(command: String): String? {
            return runCommand(arrayOf("sh", "-c", command))
        }
    }
}
