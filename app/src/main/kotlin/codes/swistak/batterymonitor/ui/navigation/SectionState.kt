/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.navigation

internal enum class SectionOwner(val route: String) {
    CURRENT("current"), HISTORY("history"), ALARMS("alarms"), DIAGNOSTICS("diagnostics"), SETTINGS(
        "settings"
    ),
    HELP("help")
}

internal data class SectionState(
    val selectedTab: String? = null,
    val rangeStartMillis: Long? = null,
    val rangeEndMillis: Long? = null,
    val filters: Set<String> = emptySet(),
    val selectedItemId: String? = null,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0
)
