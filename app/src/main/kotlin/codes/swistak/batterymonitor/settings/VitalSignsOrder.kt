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
package codes.swistak.batterymonitor.settings

internal object VitalSignsOrder {
    private const val SEPARATOR = ","

    fun parse(serializedOrder: String?): List<String> {
        return normalize(serializedOrder?.split(SEPARATOR))
    }

    fun serialize(order: Iterable<String>): String {
        return normalize(order).joinToString(SEPARATOR)
    }

    fun normalize(order: Iterable<String>?): List<String> {
        val allowedValues = SettingsContract.ALL_VITAL_SIGNS_CONTENT.toSet()
        val normalized = linkedSetOf<String>()
        order?.forEach { value ->
            if (value in allowedValues) normalized += value
        }
        normalized += SettingsContract.ALL_VITAL_SIGNS_CONTENT
        return normalized.toList()
    }
}
