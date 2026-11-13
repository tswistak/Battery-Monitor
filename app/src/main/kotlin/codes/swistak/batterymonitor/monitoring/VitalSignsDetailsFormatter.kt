/*
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.monitoring

import android.content.res.Resources
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.settings.SettingsContract

internal object VitalSignsDetailsFormatter {
    fun collapsedLine(entries: List<Pair<String, String>>): String {
        return entries.joinToString(" / ") { it.second }
    }

    fun detailedText(
        res: Resources,
        prediction: String,
        statusDuration: String,
        entries: List<Pair<String, String>>,
        health: Int
    ): String {
        fun labeled(label: String, value: String) =
            res.getString(R.string.pref_expanded_notification_format, label, value)

        return buildList {
            add(labeled(res.getString(R.string.time_remaining), prediction))
            add(statusDuration)

            val labelsByKey = res.getStringArray(R.array.vital_signs_content_values)
                .zip(res.getStringArray(R.array.vital_signs_content_entries)).toMap()
            for ((key, value) in entries) {
                when (key) {
                    SettingsContract.VITAL_SIGN_STATUS_DURATION -> Unit
                    SettingsContract.VITAL_SIGN_HEALTH -> add(
                        labeled(
                            res.getString(R.string.notification_battery_health),
                            res.getStringArray(R.array.notification_battery_health_values)[health]
                        )
                    )

                    else -> add(labeled(labelsByKey[key] ?: key, value))
                }
            }
        }.joinToString("\n")
    }
}
