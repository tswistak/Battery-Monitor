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

internal object VitalSignsDetailsFormatter {
    fun collapsedLine(entries: List<Pair<String, String>>): String {
        return entries.joinToString(" / ") { it.second }
    }

    fun detailedText(entries: List<Pair<String, String>>): String {
        return entries.joinToString("\n") { (label, value) -> "$label: $value" }
    }
}
