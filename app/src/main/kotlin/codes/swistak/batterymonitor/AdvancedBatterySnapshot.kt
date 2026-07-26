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
package codes.swistak.batterymonitor

import android.os.Bundle

internal class AdvancedBatterySnapshot {
    companion object {
        const val ACCESS_ROOT: String = "root"
        const val ACCESS_SHIZUKU: String = "shizuku"

        private const val KEY_ACCESS_METHOD = "access_method"
        private const val KEY_REMOTE_UID = "remote_uid"
        private const val KEY_SHIZUKU_VERSION = "shizuku_version"
        private const val KEY_CHARGE_COUNTER_UAH = "charge_counter_uah"
        private const val KEY_CURRENT_NOW_UA = "current_now_ua"
        private const val KEY_CURRENT_AVERAGE_UA = "current_average_ua"
        private const val KEY_ENERGY_COUNTER_NWH = "energy_counter_nwh"
        private const val KEY_CYCLE_COUNT = "cycle_count"
        private const val KEY_FULL_CHARGE_UAH = "full_charge_uah"
        private const val KEY_DESIGN_CHARGE_UAH = "design_charge_uah"
        private const val KEY_MAX_CHARGING_CURRENT_UA = "max_charging_current_ua"
        private const val KEY_MAX_CHARGING_VOLTAGE_UV = "max_charging_voltage_uv"
        private const val KEY_CHARGING_POLICY = "charging_policy"
        private const val KEY_CHARGING_STATE = "charging_state"
        private const val KEY_CAPACITY_LEVEL = "capacity_level"
        private const val KEY_REPORTED_CAPACITY_PERCENT = "reported_capacity_percent"
        private const val KEY_STATE_OF_HEALTH_PERCENT = "state_of_health_percent"
        private const val KEY_CHARGE_TIME_REMAINING_MS = "charge_time_remaining_ms"
        private const val KEY_SERVICE_LABELS = "service_labels"
        private const val KEY_SERVICE_VALUES = "service_values"
        private const val KEY_SYSFS_LABELS = "sysfs_labels"
        private const val KEY_SYSFS_VALUES = "sysfs_values"
        private const val KEY_METADATA_LABELS = "metadata_labels"
        private const val KEY_METADATA_VALUES = "metadata_values"

        fun fromBundle(bundle: Bundle): AdvancedBatterySnapshot {
            val snapshot = AdvancedBatterySnapshot()

            snapshot.accessMethod = bundle.getString(KEY_ACCESS_METHOD)
            snapshot.remoteUid = bundle.getInt(KEY_REMOTE_UID, -1)
            snapshot.shizukuVersion = bundle.getInt(KEY_SHIZUKU_VERSION, -1)
            snapshot.chargeCounterUah = getLong(bundle, KEY_CHARGE_COUNTER_UAH)
            snapshot.currentNowUa = getLong(bundle, KEY_CURRENT_NOW_UA)
            snapshot.currentAverageUa = getLong(bundle, KEY_CURRENT_AVERAGE_UA)
            snapshot.energyCounterNwh = getLong(bundle, KEY_ENERGY_COUNTER_NWH)
            snapshot.cycleCount = getLong(bundle, KEY_CYCLE_COUNT)
            snapshot.fullChargeUah = getLong(bundle, KEY_FULL_CHARGE_UAH)
            snapshot.designChargeUah = getLong(bundle, KEY_DESIGN_CHARGE_UAH)
            snapshot.maxChargingCurrentUa = getLong(bundle, KEY_MAX_CHARGING_CURRENT_UA)
            snapshot.maxChargingVoltageUv = getLong(bundle, KEY_MAX_CHARGING_VOLTAGE_UV)
            snapshot.chargingPolicy = bundle.getString(KEY_CHARGING_POLICY)
            snapshot.chargingState = bundle.getString(KEY_CHARGING_STATE)
            snapshot.capacityLevel = bundle.getString(KEY_CAPACITY_LEVEL)
            snapshot.reportedCapacityPercent =
                if (bundle.containsKey(KEY_REPORTED_CAPACITY_PERCENT)) bundle.getInt(
                    KEY_REPORTED_CAPACITY_PERCENT
                )
                else null
            snapshot.stateOfHealthPercent =
                if (bundle.containsKey(KEY_STATE_OF_HEALTH_PERCENT)) bundle.getInt(
                    KEY_STATE_OF_HEALTH_PERCENT
                )
                else null
            snapshot.chargeTimeRemainingMs = getLong(bundle, KEY_CHARGE_TIME_REMAINING_MS)
            val serviceLabels = bundle.getStringArrayList(KEY_SERVICE_LABELS)
            val serviceValues = bundle.getStringArrayList(KEY_SERVICE_VALUES)
            val sysfsLabels = bundle.getStringArrayList(KEY_SYSFS_LABELS)
            val sysfsValues = bundle.getStringArrayList(KEY_SYSFS_VALUES)
            val metadataLabels = bundle.getStringArrayList(KEY_METADATA_LABELS)
            val metadataValues = bundle.getStringArrayList(KEY_METADATA_VALUES)
            if (serviceLabels != null) snapshot.serviceLabels = serviceLabels
            if (serviceValues != null) snapshot.serviceValues = serviceValues
            if (sysfsLabels != null) snapshot.sysfsLabels = sysfsLabels
            if (sysfsValues != null) snapshot.sysfsValues = sysfsValues
            if (metadataLabels != null) snapshot.metadataLabels = metadataLabels
            if (metadataValues != null) snapshot.metadataValues = metadataValues

            return snapshot
        }

        private fun putLong(bundle: Bundle, key: String?, value: Long?) {
            if (value != null) bundle.putLong(key, value)
        }

        private fun getLong(bundle: Bundle, key: String?): Long? {
            return if (bundle.containsKey(key)) bundle.getLong(key) else null
        }
    }

    var accessMethod: String? = null
    var remoteUid: Int = -1
    var shizukuVersion: Int = -1
    var chargeCounterUah: Long? = null
    var currentNowUa: Long? = null
    var currentAverageUa: Long? = null
    var energyCounterNwh: Long? = null
    var cycleCount: Long? = null
    var fullChargeUah: Long? = null
    var designChargeUah: Long? = null
    var maxChargingCurrentUa: Long? = null
    var maxChargingVoltageUv: Long? = null
    var chargingPolicy: String? = null
    var chargingState: String? = null
    var capacityLevel: String? = null
    var reportedCapacityPercent: Int? = null
    var stateOfHealthPercent: Int? = null
    var chargeTimeRemainingMs: Long? = null
    var serviceLabels = ArrayList<String>()
    var serviceValues = ArrayList<String>()
    var sysfsLabels = ArrayList<String>()
    var sysfsValues = ArrayList<String>()
    var metadataLabels = ArrayList<String>()
    var metadataValues = ArrayList<String>()

    fun hasStats(): Boolean {
        return chargeCounterUah != null || currentNowUa != null || currentAverageUa != null || energyCounterNwh != null || cycleCount != null || fullChargeUah != null || designChargeUah != null || maxChargingCurrentUa != null || maxChargingVoltageUv != null || chargingPolicy != null || chargingState != null || capacityLevel != null || reportedCapacityPercent != null || stateOfHealthPercent != null || chargeTimeRemainingMs != null || !serviceLabels!!.isEmpty() || !sysfsLabels!!.isEmpty() || !metadataLabels!!.isEmpty()
    }

    fun hasPrivilegedStats(): Boolean {
        return chargeCounterUah != null || currentNowUa != null || currentAverageUa != null || energyCounterNwh != null || cycleCount != null || fullChargeUah != null || designChargeUah != null || maxChargingCurrentUa != null || maxChargingVoltageUv != null || chargingPolicy != null || chargingState != null || capacityLevel != null || !serviceLabels!!.isEmpty() || !sysfsLabels!!.isEmpty() || !metadataLabels!!.isEmpty()
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()

        if (accessMethod != null) bundle.putString(KEY_ACCESS_METHOD, accessMethod)
        bundle.putInt(KEY_REMOTE_UID, remoteUid)
        bundle.putInt(KEY_SHIZUKU_VERSION, shizukuVersion)
        putLong(bundle, KEY_CHARGE_COUNTER_UAH, chargeCounterUah)
        putLong(bundle, KEY_CURRENT_NOW_UA, currentNowUa)
        putLong(bundle, KEY_CURRENT_AVERAGE_UA, currentAverageUa)
        putLong(bundle, KEY_ENERGY_COUNTER_NWH, energyCounterNwh)
        putLong(bundle, KEY_CYCLE_COUNT, cycleCount)
        putLong(bundle, KEY_FULL_CHARGE_UAH, fullChargeUah)
        putLong(bundle, KEY_DESIGN_CHARGE_UAH, designChargeUah)
        putLong(bundle, KEY_MAX_CHARGING_CURRENT_UA, maxChargingCurrentUa)
        putLong(bundle, KEY_MAX_CHARGING_VOLTAGE_UV, maxChargingVoltageUv)
        if (chargingPolicy != null) bundle.putString(KEY_CHARGING_POLICY, chargingPolicy)
        if (chargingState != null) bundle.putString(KEY_CHARGING_STATE, chargingState)
        if (capacityLevel != null) bundle.putString(KEY_CAPACITY_LEVEL, capacityLevel)
        if (reportedCapacityPercent != null) bundle.putInt(
            KEY_REPORTED_CAPACITY_PERCENT, reportedCapacityPercent!!
        )
        if (stateOfHealthPercent != null) bundle.putInt(
            KEY_STATE_OF_HEALTH_PERCENT, stateOfHealthPercent!!
        )
        putLong(bundle, KEY_CHARGE_TIME_REMAINING_MS, chargeTimeRemainingMs)
        bundle.putStringArrayList(KEY_SERVICE_LABELS, serviceLabels)
        bundle.putStringArrayList(KEY_SERVICE_VALUES, serviceValues)
        bundle.putStringArrayList(KEY_SYSFS_LABELS, sysfsLabels)
        bundle.putStringArrayList(KEY_SYSFS_VALUES, sysfsValues)
        bundle.putStringArrayList(KEY_METADATA_LABELS, metadataLabels)
        bundle.putStringArrayList(KEY_METADATA_VALUES, metadataValues)

        return bundle
    }
}
