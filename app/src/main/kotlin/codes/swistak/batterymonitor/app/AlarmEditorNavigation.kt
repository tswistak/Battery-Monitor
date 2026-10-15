/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.app

import android.content.Intent
import android.os.Bundle
import codes.swistak.batterymonitor.alarms.AlarmEditActivity
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import codes.swistak.batterymonitor.ui.navigation.SectionOwner

internal enum class AlarmEditorDestination { DISMISS, SECTION, UP, BACK, EDIT_ALARM, INTENT }

internal data class AlarmEditorNavigation(
    val destination: AlarmEditorDestination,
    val section: SectionOwner? = null,
    val alarmId: Int? = null,
    val intentExtras: Bundle? = null
) {
    fun save() = Bundle().apply {
        putString("destination", destination.name)
        section?.let { putString("section", it.route) }
        alarmId?.let { putInt("alarm_id", it) }
        intentExtras?.let { putBundle("intent_extras", it) }
    }

    companion object {
        fun restore(saved: Bundle?): AlarmEditorNavigation? {
            saved ?: return null
            val destination = AlarmEditorDestination.entries.firstOrNull {
                it.name == saved.getString("destination")
            } ?: return null
            val section =
                SectionOwner.entries.firstOrNull { it.route == saved.getString("section") }
            val extras = saved.getBundle("intent_extras")
            if (destination == AlarmEditorDestination.SECTION && section == null) return null
            if (destination == AlarmEditorDestination.INTENT && extras == null) return null
            return AlarmEditorNavigation(
                destination,
                section,
                saved.getInt("alarm_id").takeIf { saved.containsKey("alarm_id") },
                extras
            )
        }

        fun route(intent: Intent) = AlarmEditorNavigation(
            AlarmEditorDestination.INTENT, intentExtras = Bundle().apply {
                for (key in listOf(
                    BatteryInfoActivity.EXTRA_SECTION, BatteryInfoActivity.EXTRA_DETAIL
                )) {
                    if (intent.hasExtra(key)) putString(key, intent.getStringExtra(key))
                }
                for (key in listOf(
                    BatteryInfoActivity.EXTRA_HISTORY_HOURS, AlarmEditActivity.EXTRA_ALARM_ID
                )) {
                    if (intent.hasExtra(key)) putInt(key, intent.getIntExtra(key, -1))
                }
                if (intent.hasExtra(BatteryInfoService.EXTRA_EDIT_ALARMS)) {
                    putBoolean(BatteryInfoService.EXTRA_EDIT_ALARMS, true)
                }
            })
    }
}
