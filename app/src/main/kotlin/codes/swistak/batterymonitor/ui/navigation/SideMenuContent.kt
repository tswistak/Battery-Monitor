package codes.swistak.batterymonitor.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import codes.swistak.batterymonitor.ui.theme.BatterySpacing

data class MenuSection(val id: String, val label: String)

@Composable
fun SideMenuContent(
    appName: String,
    closeLabel: String,
    batteryGroupLabel: String,
    toolsGroupLabel: String,
    batterySections: List<MenuSection>,
    toolSections: List<MenuSection>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(BatterySpacing.md)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                appName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            TextButton(
                onClick = onClose, modifier = Modifier.heightIn(min = BatterySpacing.touch)
            ) {
                Text(closeLabel)
            }
        }
        Text(
            batteryGroupLabel,
            modifier = Modifier.padding(
                start = BatterySpacing.normal, top = BatterySpacing.lg, bottom = BatterySpacing.sm
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        batterySections.forEach { section ->
            NavigationDrawerItem(
                label = { Text(section.label) },
                selected = selectedId == section.id,
                onClick = { onSelect(section.id) },
                modifier = Modifier.heightIn(min = BatterySpacing.touch)
            )
        }
        HorizontalDivider(
            Modifier.padding(vertical = BatterySpacing.normal),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Text(
            toolsGroupLabel,
            modifier = Modifier.padding(start = BatterySpacing.normal, bottom = BatterySpacing.sm),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        toolSections.forEach { section ->
            NavigationDrawerItem(
                label = { Text(section.label) },
                selected = selectedId == section.id,
                onClick = { onSelect(section.id) },
                modifier = Modifier.heightIn(min = BatterySpacing.touch)
            )
        }
    }
}
