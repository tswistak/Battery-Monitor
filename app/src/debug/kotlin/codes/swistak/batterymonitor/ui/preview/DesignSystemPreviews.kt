package codes.swistak.batterymonitor.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import codes.swistak.batterymonitor.ui.components.BatteryCellHero
import codes.swistak.batterymonitor.ui.components.CapabilityNotice
import codes.swistak.batterymonitor.ui.components.MetricDisplay
import codes.swistak.batterymonitor.ui.components.MetricGrid
import codes.swistak.batterymonitor.ui.components.NumericInputError
import codes.swistak.batterymonitor.ui.components.NumericValueEditor
import codes.swistak.batterymonitor.ui.components.SettingRow
import codes.swistak.batterymonitor.ui.navigation.MenuSection
import codes.swistak.batterymonitor.ui.navigation.SideMenuContent
import codes.swistak.batterymonitor.ui.theme.BatterySpacing
import codes.swistak.batterymonitor.ui.theme.BatteryTheme
import codes.swistak.batterymonitor.ui.theme.Brightness
import codes.swistak.batterymonitor.ui.theme.ColorSource

private val batterySections = listOf(
    MenuSection("current", "Current state"),
    MenuSection("history", "History"),
    MenuSection("alarms", "Alarms")
)
private val toolSections = listOf(
    MenuSection("diagnostics", "Diagnostics"),
    MenuSection("widgets", "Widgets"),
    MenuSection("settings", "Settings"),
    MenuSection("help", "Help & about")
)

@Composable
private fun PreviewCatalog() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(BatterySpacing.content),
            verticalArrangement = Arrangement.spacedBy(BatterySpacing.normal)
        ) {
            BatteryCellHero(
                "Battery",
                72,
                "Discharging",
                "3,020 mAh remaining",
                "Battery 72 percent. Discharging. 3,020 milliamp hours remaining."
            )
            MetricGrid(
                listOf(
                    MetricDisplay("Temperature", "31.6", "°C"),
                    MetricDisplay("Voltage", "4.08", "V"),
                    MetricDisplay("Battery current", "−420", "mA"),
                    MetricDisplay("Android health", "Good")
                )
            )
            CapabilityNotice(
                "Permission needed",
                "Current data can still be read from Android while advanced diagnostics are unavailable."
            )
            SettingRow("Live updates", "Show the status bar chip", onClick = {}, checked = true)
            NumericValueEditor(
                label = "Charging target",
                unit = "%",
                initialValue = 80,
                min = 1,
                max = 100,
                step = 1,
                saveLabel = "Save",
                cancelLabel = "Cancel",
                errorMessage = { error ->
                    when (error) {
                        NumericInputError.Empty -> "Enter a value"
                        NumericInputError.Invalid -> "Enter a whole number"
                        NumericInputError.OutOfRange -> "Use a value from 1 to 100"
                        NumericInputError.WrongStep -> "Use steps of 1"
                    }
                },
                onSave = {},
                onCancel = {})
        }
    }
}

@Preview(name = "Material You · 1x", showBackground = true)
@Composable
private fun DynamicPreview() = BatteryTheme { PreviewCatalog() }

@Preview(name = "Battery Blue · 1x", showBackground = true)
@Composable
private fun LightPreview() =
    BatteryTheme(ColorSource.BatteryBlue, Brightness.Light) { PreviewCatalog() }

@Preview(name = "Dark · 1x", showBackground = true)
@Composable
private fun DarkPreview() =
    BatteryTheme(ColorSource.BatteryBlue, Brightness.Dark) { PreviewCatalog() }

@Preview(name = "True black · 1x", showBackground = true)
@Composable
private fun BlackPreview() =
    BatteryTheme(ColorSource.BatteryBlue, Brightness.TrueBlack) { PreviewCatalog() }

@Preview(name = "Material You · 2x", fontScale = 2f, showBackground = true)
@Composable
private fun DynamicLargePreview() = BatteryTheme { PreviewCatalog() }

@Preview(name = "Battery Blue · 2x", fontScale = 2f, showBackground = true)
@Composable
private fun LightLargePreview() =
    BatteryTheme(ColorSource.BatteryBlue, Brightness.Light) { PreviewCatalog() }

@Preview(name = "Dark · 2x", fontScale = 2f, showBackground = true)
@Composable
private fun DarkLargePreview() =
    BatteryTheme(ColorSource.BatteryBlue, Brightness.Dark) { PreviewCatalog() }

@Preview(name = "True black · 2x", fontScale = 2f, showBackground = true)
@Composable
private fun BlackLargePreview() =
    BatteryTheme(ColorSource.BatteryBlue, Brightness.TrueBlack) { PreviewCatalog() }

@Composable
private fun MenuPreviewContent() = SideMenuContent(
    appName = "Battery Monitor",
    closeLabel = "Close",
    batteryGroupLabel = "Battery",
    toolsGroupLabel = "Tools & app",
    batterySections = batterySections,
    toolSections = toolSections,
    selectedId = "history",
    onSelect = {},
    onClose = {})

@Preview(name = "Compact modal menu", widthDp = 390, heightDp = 700)
@Composable
private fun ModalMenuPreview() = BatteryTheme(ColorSource.BatteryBlue, Brightness.Light) {
    ModalNavigationDrawer(
        drawerState = rememberDrawerState(DrawerValue.Open),
        drawerContent = { ModalDrawerSheet(Modifier.width(360.dp)) { MenuPreviewContent() } }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Text("History", Modifier.padding(BatterySpacing.content))
        }
    }
}

@Preview(name = "Persistent menu", widthDp = 900, heightDp = 700)
@Composable
private fun PersistentMenuPreview() = BatteryTheme(ColorSource.BatteryBlue, Brightness.Light) {
    androidx.compose.foundation.layout.Row {
        PermanentDrawerSheet(Modifier.width(280.dp)) { MenuPreviewContent() }
        Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.background) {
            Text("History", Modifier.padding(BatterySpacing.content))
        }
    }
}

@Preview(name = "Polish content", locale = "pl", widthDp = 360)
@Composable
private fun PolishPreview() = BatteryTheme(ColorSource.BatteryBlue, Brightness.Light) {
    BatteryCellHero(
        "Bateria",
        72,
        "Rozładowywanie",
        "Pozostały ładunek 3 020 mAh",
        "Bateria 72 procent. Rozładowywanie."
    )
}

@Preview(name = "Long German · 2x", locale = "de", fontScale = 2f, widthDp = 360)
@Composable
private fun GermanLargePreview() = BatteryTheme(ColorSource.BatteryBlue, Brightness.Light) {
    Surface(color = MaterialTheme.colorScheme.background) {
        SettingRow(
            "Benachrichtigungseinstellungen",
            "Statusleistenanzeige bei aktivierter Überwachung",
            onClick = {},
            checked = true
        )
    }
}

@Preview(name = "RTL menu", locale = "ar", widthDp = 360, heightDp = 700)
@Composable
private fun RtlMenuPreview() = BatteryTheme(ColorSource.BatteryBlue, Brightness.Light) {
    ModalNavigationDrawer(
        drawerState = rememberDrawerState(DrawerValue.Open), drawerContent = {
            ModalDrawerSheet(Modifier.width(320.dp)) {
                SideMenuContent(
                    appName = "مراقب البطارية",
                    closeLabel = "إغلاق",
                    batteryGroupLabel = "البطارية",
                    toolsGroupLabel = "الأدوات والتطبيق",
                    batterySections = listOf(
                        MenuSection("current", "الحالة الحالية"),
                        MenuSection("history", "السجل"),
                        MenuSection("alarms", "التنبيهات")
                    ),
                    toolSections = listOf(
                        MenuSection("diagnostics", "التشخيص"),
                        MenuSection("widgets", "الأدوات"),
                        MenuSection("settings", "الإعدادات"),
                        MenuSection("help", "المساعدة")
                    ),
                    selectedId = "history",
                    onSelect = {},
                    onClose = {})
            }
        }) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {} }
}
