package codes.swistak.batterymonitor.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    showCloseButton: Boolean = true,
    firstItemFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 16.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .heightIn(min = 52.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .background(Color(0xFF03A9F4), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                NavigationGlyph("brand", Color.White, Modifier.size(19.dp))
            }
            Text(
                appName,
                Modifier.weight(1f),
                fontSize = 18.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (showCloseButton) {
                IconButton(onClick = onClose, modifier = Modifier.semantics {
                    contentDescription = closeLabel
                }) {
                    NavigationGlyph("close", colors.onSurfaceVariant)
                }
            }
        }
        MenuGroupHeading(batteryGroupLabel, Modifier.padding(top = 14.dp, bottom = 7.dp))
        batterySections.forEachIndexed { index, section ->
            MenuItem(
                section,
                selectedId == section.id,
                onSelect,
                if (index == 0 && firstItemFocusRequester != null) Modifier.focusRequester(
                    firstItemFocusRequester
                ) else Modifier
            )
        }
        HorizontalDivider(
            Modifier.padding(top = 16.dp, bottom = 12.dp), color = colors.outlineVariant
        )
        MenuGroupHeading(toolsGroupLabel, Modifier.padding(bottom = 7.dp))
        toolSections.forEach { section ->
            MenuItem(section, selectedId == section.id, onSelect)
        }
    }
}

@Composable
private fun MenuGroupHeading(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier.padding(horizontal = 16.dp),
        fontSize = 12.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun MenuItem(
    section: MenuSection,
    isSelected: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val foreground = if (isSelected) colors.primary else colors.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(if (isSelected) colors.primaryContainer else Color.Transparent, CircleShape)
            .clickable { onSelect(section.id) }
            .semantics { selected = isSelected }
            .heightIn(min = 49.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        NavigationGlyph(section.id, foreground)
        Spacer(Modifier.width(14.dp))
        Text(
            section.label,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
