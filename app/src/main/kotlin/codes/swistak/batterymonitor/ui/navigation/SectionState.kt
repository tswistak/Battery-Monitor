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
