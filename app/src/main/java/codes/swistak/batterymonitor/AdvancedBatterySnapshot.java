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

import android.os.Bundle;

import java.util.ArrayList;

class AdvancedBatterySnapshot {
    static final String ACCESS_ROOT = "root";
    static final String ACCESS_SHIZUKU = "shizuku";

    private static final String KEY_ACCESS_METHOD = "access_method";
    private static final String KEY_REMOTE_UID = "remote_uid";
    private static final String KEY_SHIZUKU_VERSION = "shizuku_version";
    private static final String KEY_CHARGE_COUNTER_UAH = "charge_counter_uah";
    private static final String KEY_CURRENT_NOW_UA = "current_now_ua";
    private static final String KEY_CURRENT_AVERAGE_UA = "current_average_ua";
    private static final String KEY_ENERGY_COUNTER_NWH = "energy_counter_nwh";
    private static final String KEY_CYCLE_COUNT = "cycle_count";
    private static final String KEY_FULL_CHARGE_UAH = "full_charge_uah";
    private static final String KEY_DESIGN_CHARGE_UAH = "design_charge_uah";
    private static final String KEY_MAX_CHARGING_CURRENT_UA = "max_charging_current_ua";
    private static final String KEY_MAX_CHARGING_VOLTAGE_UV = "max_charging_voltage_uv";
    private static final String KEY_CHARGING_POLICY = "charging_policy";
    private static final String KEY_CHARGING_STATE = "charging_state";
    private static final String KEY_CAPACITY_LEVEL = "capacity_level";
    private static final String KEY_REPORTED_CAPACITY_PERCENT = "reported_capacity_percent";
    private static final String KEY_STATE_OF_HEALTH_PERCENT = "state_of_health_percent";
    private static final String KEY_CHARGE_TIME_REMAINING_MS = "charge_time_remaining_ms";
    private static final String KEY_SERVICE_LABELS = "service_labels";
    private static final String KEY_SERVICE_VALUES = "service_values";
    private static final String KEY_SYSFS_LABELS = "sysfs_labels";
    private static final String KEY_SYSFS_VALUES = "sysfs_values";
    private static final String KEY_METADATA_LABELS = "metadata_labels";
    private static final String KEY_METADATA_VALUES = "metadata_values";

    String accessMethod;
    int remoteUid = -1;
    int shizukuVersion = -1;
    Long chargeCounterUah;
    Long currentNowUa;
    Long currentAverageUa;
    Long energyCounterNwh;
    Long cycleCount;
    Long fullChargeUah;
    Long designChargeUah;
    Long maxChargingCurrentUa;
    Long maxChargingVoltageUv;
    String chargingPolicy;
    String chargingState;
    String capacityLevel;
    Integer reportedCapacityPercent;
    Integer stateOfHealthPercent;
    Long chargeTimeRemainingMs;
    ArrayList<String> serviceLabels = new ArrayList<>();
    ArrayList<String> serviceValues = new ArrayList<>();
    ArrayList<String> sysfsLabels = new ArrayList<>();
    ArrayList<String> sysfsValues = new ArrayList<>();
    ArrayList<String> metadataLabels = new ArrayList<>();
    ArrayList<String> metadataValues = new ArrayList<>();

    boolean hasStats() {
        return chargeCounterUah != null ||
                currentNowUa != null ||
                currentAverageUa != null ||
                energyCounterNwh != null ||
                cycleCount != null ||
                fullChargeUah != null ||
                designChargeUah != null ||
                maxChargingCurrentUa != null ||
                maxChargingVoltageUv != null ||
                chargingPolicy != null ||
                chargingState != null ||
                capacityLevel != null ||
                reportedCapacityPercent != null ||
                stateOfHealthPercent != null ||
                chargeTimeRemainingMs != null ||
                !serviceLabels.isEmpty() ||
                !sysfsLabels.isEmpty() ||
                !metadataLabels.isEmpty();
    }

    boolean hasPrivilegedStats() {
        return chargeCounterUah != null ||
                currentNowUa != null ||
                currentAverageUa != null ||
                energyCounterNwh != null ||
                cycleCount != null ||
                fullChargeUah != null ||
                designChargeUah != null ||
                maxChargingCurrentUa != null ||
                maxChargingVoltageUv != null ||
                chargingPolicy != null ||
                chargingState != null ||
                capacityLevel != null ||
                !serviceLabels.isEmpty() ||
                !sysfsLabels.isEmpty() ||
                !metadataLabels.isEmpty();
    }

    Bundle toBundle() {
        Bundle bundle = new Bundle();

        if (accessMethod != null)
            bundle.putString(KEY_ACCESS_METHOD, accessMethod);
        bundle.putInt(KEY_REMOTE_UID, remoteUid);
        bundle.putInt(KEY_SHIZUKU_VERSION, shizukuVersion);
        putLong(bundle, KEY_CHARGE_COUNTER_UAH, chargeCounterUah);
        putLong(bundle, KEY_CURRENT_NOW_UA, currentNowUa);
        putLong(bundle, KEY_CURRENT_AVERAGE_UA, currentAverageUa);
        putLong(bundle, KEY_ENERGY_COUNTER_NWH, energyCounterNwh);
        putLong(bundle, KEY_CYCLE_COUNT, cycleCount);
        putLong(bundle, KEY_FULL_CHARGE_UAH, fullChargeUah);
        putLong(bundle, KEY_DESIGN_CHARGE_UAH, designChargeUah);
        putLong(bundle, KEY_MAX_CHARGING_CURRENT_UA, maxChargingCurrentUa);
        putLong(bundle, KEY_MAX_CHARGING_VOLTAGE_UV, maxChargingVoltageUv);
        if (chargingPolicy != null)
            bundle.putString(KEY_CHARGING_POLICY, chargingPolicy);
        if (chargingState != null)
            bundle.putString(KEY_CHARGING_STATE, chargingState);
        if (capacityLevel != null)
            bundle.putString(KEY_CAPACITY_LEVEL, capacityLevel);
        if (reportedCapacityPercent != null)
            bundle.putInt(KEY_REPORTED_CAPACITY_PERCENT, reportedCapacityPercent);
        if (stateOfHealthPercent != null)
            bundle.putInt(KEY_STATE_OF_HEALTH_PERCENT, stateOfHealthPercent);
        putLong(bundle, KEY_CHARGE_TIME_REMAINING_MS, chargeTimeRemainingMs);
        bundle.putStringArrayList(KEY_SERVICE_LABELS, serviceLabels);
        bundle.putStringArrayList(KEY_SERVICE_VALUES, serviceValues);
        bundle.putStringArrayList(KEY_SYSFS_LABELS, sysfsLabels);
        bundle.putStringArrayList(KEY_SYSFS_VALUES, sysfsValues);
        bundle.putStringArrayList(KEY_METADATA_LABELS, metadataLabels);
        bundle.putStringArrayList(KEY_METADATA_VALUES, metadataValues);

        return bundle;
    }

    static AdvancedBatterySnapshot fromBundle(Bundle bundle) {
        AdvancedBatterySnapshot snapshot = new AdvancedBatterySnapshot();

        snapshot.accessMethod = bundle.getString(KEY_ACCESS_METHOD);
        snapshot.remoteUid = bundle.getInt(KEY_REMOTE_UID, -1);
        snapshot.shizukuVersion = bundle.getInt(KEY_SHIZUKU_VERSION, -1);
        snapshot.chargeCounterUah = getLong(bundle, KEY_CHARGE_COUNTER_UAH);
        snapshot.currentNowUa = getLong(bundle, KEY_CURRENT_NOW_UA);
        snapshot.currentAverageUa = getLong(bundle, KEY_CURRENT_AVERAGE_UA);
        snapshot.energyCounterNwh = getLong(bundle, KEY_ENERGY_COUNTER_NWH);
        snapshot.cycleCount = getLong(bundle, KEY_CYCLE_COUNT);
        snapshot.fullChargeUah = getLong(bundle, KEY_FULL_CHARGE_UAH);
        snapshot.designChargeUah = getLong(bundle, KEY_DESIGN_CHARGE_UAH);
        snapshot.maxChargingCurrentUa = getLong(bundle, KEY_MAX_CHARGING_CURRENT_UA);
        snapshot.maxChargingVoltageUv = getLong(bundle, KEY_MAX_CHARGING_VOLTAGE_UV);
        snapshot.chargingPolicy = bundle.getString(KEY_CHARGING_POLICY);
        snapshot.chargingState = bundle.getString(KEY_CHARGING_STATE);
        snapshot.capacityLevel = bundle.getString(KEY_CAPACITY_LEVEL);
        snapshot.reportedCapacityPercent = bundle.containsKey(KEY_REPORTED_CAPACITY_PERCENT)
                ? bundle.getInt(KEY_REPORTED_CAPACITY_PERCENT)
                : null;
        snapshot.stateOfHealthPercent = bundle.containsKey(KEY_STATE_OF_HEALTH_PERCENT)
                ? bundle.getInt(KEY_STATE_OF_HEALTH_PERCENT)
                : null;
        snapshot.chargeTimeRemainingMs = getLong(bundle, KEY_CHARGE_TIME_REMAINING_MS);
        ArrayList<String> serviceLabels = bundle.getStringArrayList(KEY_SERVICE_LABELS);
        ArrayList<String> serviceValues = bundle.getStringArrayList(KEY_SERVICE_VALUES);
        ArrayList<String> sysfsLabels = bundle.getStringArrayList(KEY_SYSFS_LABELS);
        ArrayList<String> sysfsValues = bundle.getStringArrayList(KEY_SYSFS_VALUES);
        ArrayList<String> metadataLabels = bundle.getStringArrayList(KEY_METADATA_LABELS);
        ArrayList<String> metadataValues = bundle.getStringArrayList(KEY_METADATA_VALUES);
        if (serviceLabels != null)
            snapshot.serviceLabels = serviceLabels;
        if (serviceValues != null)
            snapshot.serviceValues = serviceValues;
        if (sysfsLabels != null)
            snapshot.sysfsLabels = sysfsLabels;
        if (sysfsValues != null)
            snapshot.sysfsValues = sysfsValues;
        if (metadataLabels != null)
            snapshot.metadataLabels = metadataLabels;
        if (metadataValues != null)
            snapshot.metadataValues = metadataValues;

        return snapshot;
    }

    private static void putLong(Bundle bundle, String key, Long value) {
        if (value != null)
            bundle.putLong(key, value);
    }

    private static Long getLong(Bundle bundle, String key) {
        return bundle.containsKey(key) ? bundle.getLong(key) : null;
    }
}
