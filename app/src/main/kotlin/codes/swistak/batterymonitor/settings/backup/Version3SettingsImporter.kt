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
package codes.swistak.batterymonitor.settings.backup

import android.content.SharedPreferences
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.VitalSignsOrder

internal object Version3SettingsImporter : SettingsImporter {
    const val VERSION = 3

    const val KEY_VITAL_SIGN_HEALTH = "vital_signs_health"
    const val KEY_VITAL_SIGN_TEMPERATURE = "vital_signs_temperature"
    const val KEY_VITAL_SIGN_VOLTAGE = "vital_signs_voltage"
    const val KEY_VITAL_SIGN_CURRENT = "vital_signs_current"
    const val KEY_VITAL_SIGN_CHARGE = "vital_signs_charge"
    const val KEY_VITAL_SIGN_STATUS_DURATION = "vital_signs_status_duration"

    const val KEY_VITAL_SIGN_HEALTH_ORDER = "vital_signs_health_order"
    const val KEY_VITAL_SIGN_TEMPERATURE_ORDER = "vital_signs_temperature_order"
    const val KEY_VITAL_SIGN_VOLTAGE_ORDER = "vital_signs_voltage_order"
    const val KEY_VITAL_SIGN_CURRENT_ORDER = "vital_signs_current_order"
    const val KEY_VITAL_SIGN_CHARGE_ORDER = "vital_signs_charge_order"
    const val KEY_VITAL_SIGN_STATUS_DURATION_ORDER = "vital_signs_status_duration_order"

    val vitalSignsContentByBackupKey: Map<String, String> = linkedMapOf(
        KEY_VITAL_SIGN_HEALTH to SettingsContract.VITAL_SIGN_HEALTH,
        KEY_VITAL_SIGN_TEMPERATURE to SettingsContract.VITAL_SIGN_TEMPERATURE,
        KEY_VITAL_SIGN_VOLTAGE to SettingsContract.VITAL_SIGN_VOLTAGE,
        KEY_VITAL_SIGN_CURRENT to SettingsContract.VITAL_SIGN_CURRENT,
        KEY_VITAL_SIGN_CHARGE to SettingsContract.VITAL_SIGN_CHARGE,
        KEY_VITAL_SIGN_STATUS_DURATION to SettingsContract.VITAL_SIGN_STATUS_DURATION
    )

    val vitalSignsOrderByBackupKey: Map<String, String> = linkedMapOf(
        KEY_VITAL_SIGN_HEALTH_ORDER to SettingsContract.VITAL_SIGN_HEALTH,
        KEY_VITAL_SIGN_TEMPERATURE_ORDER to SettingsContract.VITAL_SIGN_TEMPERATURE,
        KEY_VITAL_SIGN_VOLTAGE_ORDER to SettingsContract.VITAL_SIGN_VOLTAGE,
        KEY_VITAL_SIGN_CURRENT_ORDER to SettingsContract.VITAL_SIGN_CURRENT,
        KEY_VITAL_SIGN_CHARGE_ORDER to SettingsContract.VITAL_SIGN_CHARGE,
        KEY_VITAL_SIGN_STATUS_DURATION_ORDER to SettingsContract.VITAL_SIGN_STATUS_DURATION
    )

    private val vitalSignsBackupKeys =
        vitalSignsContentByBackupKey.keys + vitalSignsOrderByBackupKey.keys

    override val schema: Map<String, Class<*>> = buildMap {
        putAll(Version2SettingsImporter.schema)
        remove(SettingsContract.KEY_DISPLAY_CURRENT_IN_NOTIFICATION)
        remove(SettingsContract.KEY_STATUS_DURATION_IN_VITAL_SIGNS)
        put(SettingsContract.KEY_SHOW_REMAINING_CHARGE, Boolean::class.java)
        for (key in vitalSignsContentByBackupKey.keys) put(key, Boolean::class.java)
        for (key in vitalSignsOrderByBackupKey.keys) put(key, Int::class.java)
    }

    override fun restore(
        editor: SharedPreferences.Editor, settings: Map<String, Any>
    ) {
        for ((key, value) in settings) {
            if (key !in vitalSignsBackupKeys) editor.putSetting(key, value)
        }

        editor.putStringSet(
            SettingsContract.KEY_VITAL_SIGNS_CONTENT, vitalSignsContentFromBackup(settings)
        )
        editor.putString(
            SettingsContract.KEY_VITAL_SIGNS_ORDER,
            VitalSignsOrder.serialize(vitalSignsOrderFromBackup(settings))
        )

        if (settings.containsKey(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER)) {
            editor.remove(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING)
        }
    }

    internal fun vitalSignsContentFromBackup(settings: Map<String, Any>): Set<String> =
        vitalSignsContentByBackupKey.mapNotNullTo(linkedSetOf()) { (backupKey, contentValue) ->
            val defaultValue = contentValue in SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT
            if (settings[backupKey] as? Boolean ?: defaultValue) contentValue else null
        }

    internal fun vitalSignsOrderFromBackup(settings: Map<String, Any>): List<String> {
        val defaultPositions = SettingsContract.ALL_VITAL_SIGNS_CONTENT.withIndex()
            .associate { (index, value) -> value to index }
        val positionedValues = vitalSignsOrderByBackupKey.map { (backupKey, contentValue) ->
            val position = settings[backupKey] as? Int ?: defaultPositions.getValue(contentValue)
            require(position in SettingsContract.ALL_VITAL_SIGNS_CONTENT.indices) {
                "Invalid Vital Signs position for '$backupKey'"
            }
            contentValue to position
        }
        require(positionedValues.map { it.second }.distinct().size == positionedValues.size) {
            "Duplicate Vital Signs positions"
        }
        return VitalSignsOrder.normalize(positionedValues.sortedBy { it.second }.map { it.first })
    }
}
