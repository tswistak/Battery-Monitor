/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import codes.swistak.batterymonitor.ui.navigation.MenuSection
import codes.swistak.batterymonitor.ui.navigation.SectionOwner
import codes.swistak.batterymonitor.ui.navigation.SideMenuContent
import codes.swistak.batterymonitor.ui.navigation.SideNavigationShell
import codes.swistak.batterymonitor.ui.theme.BatteryTheme

private val batterySections = listOf(
    MenuSection("current", "Current state"),
    MenuSection("history", "History"),
    MenuSection("alarms", "Alarms")
)
private val toolSections = listOf(
    MenuSection("diagnostics", "Diagnostics"),
    MenuSection("settings", "Settings"),
    MenuSection("help", "Help & about")
)

@Composable
private fun NavigationPreviewContent(showClose: Boolean) {
    SideMenuContent(
        appName = "Battery Monitor",
        closeLabel = "Close menu",
        batteryGroupLabel = "Battery",
        toolsGroupLabel = "Tools and app",
        batterySections = batterySections,
        toolSections = toolSections,
        selectedId = "history",
        onSelect = {},
        onClose = {},
        showCloseButton = showClose,
        modifier = Modifier.fillMaxSize()
    )
}

@Preview(name = "Modal menu, short window", widthDp = 360, heightDp = 420)
@Composable
private fun ModalMenuPreview() {
    BatteryTheme { ModalDrawerSheet { NavigationPreviewContent(true) } }
}

@Preview(name = "Permanent side panel", widthDp = 280, heightDp = 800)
@Composable
private fun PermanentPanelPreview() {
    BatteryTheme { PermanentDrawerSheet { NavigationPreviewContent(false) } }
}

@Preview(name = "Compact menu closed", widthDp = 360, heightDp = 640)
@Composable
private fun ClosedMenuPreview() {
    SideNavigationShell(SectionOwner.CURRENT, onSelect = {}) { Text("Current state") }
}
