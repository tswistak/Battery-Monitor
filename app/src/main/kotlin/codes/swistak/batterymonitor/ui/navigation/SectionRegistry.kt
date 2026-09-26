/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.navigation

import androidx.annotation.StringRes
import codes.swistak.batterymonitor.R

internal data class SectionDestination(
    val owner: SectionOwner, @param:StringRes val label: Int, val group: SectionGroup
)

internal enum class SectionGroup { BATTERY, TOOLS }

internal object SectionRegistry {
    val destinations = listOf(
        SectionDestination(SectionOwner.CURRENT, R.string.nav_current, SectionGroup.BATTERY),
        SectionDestination(SectionOwner.HISTORY, R.string.nav_history, SectionGroup.BATTERY),
        SectionDestination(SectionOwner.ALARMS, R.string.nav_alarms, SectionGroup.BATTERY),
        SectionDestination(SectionOwner.DIAGNOSTICS, R.string.nav_diagnostics, SectionGroup.TOOLS),
        SectionDestination(SectionOwner.SETTINGS, R.string.nav_settings, SectionGroup.TOOLS),
        SectionDestination(SectionOwner.HELP, R.string.nav_help, SectionGroup.TOOLS)
    )

    fun owner(route: String?): SectionOwner =
        destinations.firstOrNull { it.owner.route == route }?.owner ?: SectionOwner.CURRENT
}
