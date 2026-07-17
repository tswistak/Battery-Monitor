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

package codes.swistak.batterymonitor;

import android.content.Context;
import android.os.BatteryManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class AdvancedBatteryStatsCollector {
    private static final long COMMAND_TIMEOUT_SECONDS = 10L;
    private static final Pattern LONG_PATTERN = Pattern.compile("(-?\\d+)");
    private static final int BATTERY_PROPERTY_MANUFACTURING_DATE = 7;
    private static final int BATTERY_PROPERTY_FIRST_USAGE_DATE = 8;
    private static final int BATTERY_PROPERTY_CHARGING_POLICY = 9;
    private static final int BATTERY_PROPERTY_STATE_OF_HEALTH = 10;
    private static final int BATTERY_PROPERTY_SERIAL_NUMBER = 11;
    private static final int BATTERY_PROPERTY_PART_STATUS = 12;
    private static final int BATTERY_PROPERTY_MANUFACTURER = 13;
    private static final int BATTERY_PROPERTY_MODEL_NAME = 14;
    private static final int BATTERY_PROPERTY_VOLTAGE_MIN_DESIGN = 15;
    private static final String SYSFS_ROOT = "/sys/class/power_supply";
    private static final String[] SYSFS_CYCLE_COUNT_PATHS = {
            SYSFS_ROOT + "/battery/cycle_count",
            SYSFS_ROOT + "/bms/cycle_count"
    };
    private static final String[] SYSFS_FULL_CHARGE_PATHS = {
            SYSFS_ROOT + "/battery/charge_full",
            SYSFS_ROOT + "/bms/charge_full"
    };
    private static final String[] SYSFS_DESIGN_CHARGE_PATHS = {
            SYSFS_ROOT + "/battery/charge_full_design",
            SYSFS_ROOT + "/bms/charge_full_design"
    };
    private static final String[] SYSFS_FALLBACK_DIRS = {
            SYSFS_ROOT + "/battery",
            SYSFS_ROOT + "/bms",
            SYSFS_ROOT + "/usb",
            SYSFS_ROOT + "/main",
            SYSFS_ROOT + "/wireless"
    };
    private static final Set<String> CURATED_DUMP_KEYS = new LinkedHashSet<>(Arrays.asList(
            "max charging current", "max charging voltage", "charge counter",
            "current now", "current average",
            "charging policy", "charging state", "capacity level"
    ));
    private static final Set<String> SYSFS_IGNORED_NAMES = new LinkedHashSet<>(Arrays.asList(
            "device", "subsystem", "power", "uevent", "type"
    ));

    interface CommandExecutor {
        String run(String command);
    }

    static final class RootExecutor implements CommandExecutor {
        @Override
        public String run(String command) {
            return runCommand(new String[]{"su", "-c", command});
        }
    }

    static final class PrivilegedShellExecutor implements CommandExecutor {
        @Override
        public String run(String command) {
            return runCommand(new String[]{"sh", "-c", command});
        }
    }

    static AdvancedBatterySnapshot collect(CommandExecutor executor, String accessMethod, int remoteUid, Context context, boolean allowPrivilegedBatteryApi) {
        AdvancedBatterySnapshot snapshot = new AdvancedBatterySnapshot();
        snapshot.accessMethod = accessMethod;
        snapshot.remoteUid = remoteUid;

        Map<String, String> batteryDump = parseDump(executor.run("dumpsys battery"));

        snapshot.chargeCounterUah = firstLong(readProperty(executor, "charge_counter"), batteryDump.get("Charge counter"));
        snapshot.currentNowUa = firstLong(readProperty(executor, "current_now"));
        snapshot.currentAverageUa = firstLong(readProperty(executor, "current_average"));
        snapshot.energyCounterNwh = firstLong(readProperty(executor, "energy_counter"), readProperty(executor, "counter"));
        snapshot.cycleCount = firstLong(readSysfs(executor, SYSFS_CYCLE_COUNT_PATHS));
        snapshot.fullChargeUah = firstLong(readSysfs(executor, SYSFS_FULL_CHARGE_PATHS));
        snapshot.designChargeUah = firstLong(readSysfs(executor, SYSFS_DESIGN_CHARGE_PATHS));
        snapshot.maxChargingCurrentUa = firstLong(batteryDump.get("Max charging current"));
        snapshot.maxChargingVoltageUv = firstLong(batteryDump.get("Max charging voltage"));
        snapshot.chargingPolicy = cleanString(batteryDump.get("Charging policy"));
        snapshot.chargingState = cleanString(batteryDump.get("Charging state"));
        snapshot.capacityLevel = cleanString(batteryDump.get("capacity level"));
        collectBatteryManagerFields(snapshot, context, allowPrivilegedBatteryApi);
        collectServiceFields(snapshot, batteryDump);
        collectSysfsFields(snapshot, executor);

        return snapshot;
    }

    private static String readProperty(CommandExecutor executor, String propertyName) {
        return executor.run("cmd battery get " + propertyName + " 2>/dev/null");
    }

    private static String readSysfs(CommandExecutor executor, String[] paths) {
        for (String path : paths) {
            String value = executor.run("cat " + path + " 2>/dev/null");
            if (value != null)
                return value;
        }

        return null;
    }

    private static Map<String, String> parseDump(String dump) {
        Map<String, String> values = new LinkedHashMap<>();
        if (dump == null)
            return values;

        String[] lines = dump.split("\\r?\\n");
        for (String s : lines) {
            String line = s.trim();
            int separator = line.indexOf(':');
            if (separator <= 0)
                continue;

            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (!key.isEmpty() && !value.isEmpty())
                values.put(key, value);
        }

        return values;
    }

    private static void collectBatteryManagerFields(AdvancedBatterySnapshot snapshot, Context context, boolean allowPrivilegedBatteryApi) {
        if (context == null)
            return;

        BatteryManager batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (batteryManager == null)
            return;

        snapshot.reportedCapacityPercent = readIntProperty(batteryManager, BatteryManager.BATTERY_PROPERTY_CAPACITY);
        snapshot.stateOfHealthPercent = readIntProperty(batteryManager, BATTERY_PROPERTY_STATE_OF_HEALTH);
        snapshot.chargeTimeRemainingMs = readChargeTimeRemaining(batteryManager);

        if (!allowPrivilegedBatteryApi)
            return;

        String chargingPolicyFromApi = formatChargingPolicy(readIntProperty(batteryManager, BATTERY_PROPERTY_CHARGING_POLICY));
        if (snapshot.chargingPolicy == null)
            snapshot.chargingPolicy = chargingPolicyFromApi;

        addLabeledValue(snapshot.metadataLabels, snapshot.metadataValues,
                localize(context, R.string.advanced_field_manufacturing_date, "Manufacturing date"),
                formatEpochSeconds(readLongProperty(batteryManager, BATTERY_PROPERTY_MANUFACTURING_DATE)));
        addLabeledValue(snapshot.metadataLabels, snapshot.metadataValues,
                localize(context, R.string.advanced_field_first_use_date, "First use date"),
                formatEpochSeconds(readLongProperty(batteryManager, BATTERY_PROPERTY_FIRST_USAGE_DATE)));
        addLabeledValue(snapshot.metadataLabels, snapshot.metadataValues,
                localize(context, R.string.advanced_field_battery_serial_number, "Battery serial number"),
                readStringProperty(batteryManager, BATTERY_PROPERTY_SERIAL_NUMBER));
        addLabeledValue(snapshot.metadataLabels, snapshot.metadataValues,
                localize(context, R.string.advanced_field_battery_part_status, "Battery part status"),
                formatPartStatus(readIntProperty(batteryManager, BATTERY_PROPERTY_PART_STATUS)));
        addLabeledValue(snapshot.metadataLabels, snapshot.metadataValues,
                localize(context, R.string.advanced_field_battery_part_manufacturer, "Battery part manufacturer"),
                readStringProperty(batteryManager, BATTERY_PROPERTY_MANUFACTURER));
        addLabeledValue(snapshot.metadataLabels, snapshot.metadataValues,
                localize(context, R.string.advanced_field_battery_model_name, "Battery model name"),
                readStringProperty(batteryManager, BATTERY_PROPERTY_MODEL_NAME));
        addLabeledValue(snapshot.metadataLabels, snapshot.metadataValues,
                localize(context, R.string.advanced_field_minimum_design_voltage, "Minimum design voltage"),
                formatMicroVolts(readLongProperty(batteryManager, BATTERY_PROPERTY_VOLTAGE_MIN_DESIGN)));
    }

    private static void collectServiceFields(AdvancedBatterySnapshot snapshot, Map<String, String> batteryDump) {
        for (Map.Entry<String, String> entry : batteryDump.entrySet()) {
            String key = entry.getKey();
            String lowerKey = key.toLowerCase(Locale.US);
            if (CURATED_DUMP_KEYS.contains(lowerKey))
                continue;

            addLabeledValue(snapshot.serviceLabels, snapshot.serviceValues, key, cleanString(entry.getValue()));
        }
    }

    private static void collectSysfsFields(AdvancedBatterySnapshot snapshot, CommandExecutor executor) {
        Set<String> seenLabels = new LinkedHashSet<>();
        List<String> dirs = discoverSysfsDirs(executor);

        for (String dir : dirs) {
            List<String> entries = splitLines(executor.run("ls " + dir + " 2>/dev/null"));
            String prefix = dir.substring(dir.lastIndexOf('/') + 1) + "/";

            for (int j = 0; j < entries.size(); j++) {
                String entry = entries.get(j).trim();
                if (!isValidSysfsEntry(entry) || SYSFS_IGNORED_NAMES.contains(entry))
                    continue;

                String label = prefix + entry;
                if (seenLabels.contains(label))
                    continue;

                String value = cleanSysfsValue(executor.run("cat " + dir + "/" + entry + " 2>/dev/null"));
                if (value == null)
                    continue;

                seenLabels.add(label);
                addLabeledValue(snapshot.sysfsLabels, snapshot.sysfsValues, label, value);
            }
        }
    }

    private static boolean isValidSysfsEntry(String entry) {
        if (entry == null || entry.isEmpty() || entry.indexOf('/') >= 0)
            return false;

        for (int i = 0; i < entry.length(); i++) {
            char ch = entry.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == '.'))
                return false;
        }

        return true;
    }

    private static List<String> splitLines(String value) {
        return value == null ? Collections.emptyList() : Arrays.asList(value.split("\\r?\\n"));
    }

    private static List<String> discoverSysfsDirs(CommandExecutor executor) {
        List<String> entries = splitLines(executor.run("ls " + SYSFS_ROOT + " 2>/dev/null"));
        LinkedHashSet<String> dirs = new LinkedHashSet<>();

        for (String s : entries) {
            String entry = s.trim();
            if (!isValidSysfsEntry(entry))
                continue;

            dirs.add(SYSFS_ROOT + "/" + entry);
        }

        if (!dirs.isEmpty())
            return new ArrayList<>(dirs);

        return Arrays.asList(SYSFS_FALLBACK_DIRS);
    }

    private static void addLabeledValue(ArrayList<String> labels, ArrayList<String> values, String label, String value) {
        if (label == null || value == null)
            return;

        labels.add(label);
        values.add(value);
    }

    private static String localize(Context context, int stringId, String fallback) {
        if (context == null)
            return fallback;

        try {
            return context.getString(stringId);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Integer readIntProperty(BatteryManager batteryManager, int id) {
        try {
            int value = batteryManager.getIntProperty(id);
            return value != Integer.MIN_VALUE ? value : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static Long readLongProperty(BatteryManager batteryManager, int id) {
        try {
            long value = batteryManager.getLongProperty(id);
            return value != Long.MIN_VALUE ? value : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String readStringProperty(BatteryManager batteryManager, int id) {
        try {
            Method method = BatteryManager.class.getMethod("getStringProperty", int.class);
            Object value = method.invoke(batteryManager, id);
            return value instanceof String ? cleanString((String) value) : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static Long readChargeTimeRemaining(BatteryManager batteryManager) {
        try {
            Method method = BatteryManager.class.getMethod("computeChargeTimeRemaining");
            Object value = method.invoke(batteryManager);
            if (!(value instanceof Long))
                return null;

            long remaining = (Long) value;
            return remaining >= 0 ? remaining : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static Long firstLong(String... values) {
        for (String value : values) {
            Long parsed = parseLong(value);
            if (parsed != null)
                return parsed;
        }

        return null;
    }

    private static Long parseLong(String value) {
        if (value == null)
            return null;

        Matcher matcher = LONG_PATTERN.matcher(value.replace(",", ""));
        if (!matcher.find())
            return null;

        try {
            String match = matcher.group(1);
            if (match == null)
                return null;

            return Long.valueOf(match);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String cleanString(String value) {
        if (value == null)
            return null;

        String cleaned = value.trim();
        return !cleaned.isEmpty() ? cleaned : null;
    }

    private static String cleanSysfsValue(String value) {
        if (value == null)
            return null;

        String cleaned = value.replace('\u0000', ' ').replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty() || cleaned.length() > 120)
            return null;

        return cleaned;
    }

    private static String formatEpochSeconds(Long value) {
        if (value == null || value <= 0)
            return null;

        return DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                .format(new Date(value * 1000L));
    }

    private static String formatPartStatus(Integer value) {
        if (value == null)
            return null;

        return switch (value) {
            case 0 -> "unsupported";
            case 1 -> "original";
            case 2 -> "replaced";
            default -> String.valueOf(value);
        };
    }

    private static String formatChargingPolicy(Integer value) {
        if (value == null)
            return null;

        return switch (value) {
            case 1 -> "default";
            case 2 -> "adaptive_aon";
            case 3 -> "adaptive_ac";
            case 4 -> "adaptive_longlife";
            case 5 -> "force_full_charge";
            default -> String.valueOf(value);
        };
    }

    private static String formatMicroVolts(Long value) {
        if (value == null)
            return null;

        return String.format(Locale.getDefault(), "%.2f V", value / 1000000.0d);
    }

    private static String runCommand(String[] command) {
        Process process = null;

        try {
            process = Runtime.getRuntime().exec(command);
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }

            if (process.exitValue() != 0)
                return null;

            String output = readFully(process.getInputStream()).trim();
            return !output.isEmpty() ? output : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null)
                process.destroy();
        }
    }

    private static String readFully(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int bytesRead;

        while ((bytesRead = inputStream.read(buffer)) != -1)
            outputStream.write(buffer, 0, bytesRead);

        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }
}
