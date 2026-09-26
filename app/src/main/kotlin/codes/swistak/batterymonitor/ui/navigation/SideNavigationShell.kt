package codes.swistak.batterymonitor.ui.navigation

import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.separatingVerticalHingeBounds
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.ui.theme.BatteryTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SideNavigationShell(
    selected: SectionOwner,
    detailTitle: String? = null,
    onSelect: (SectionOwner) -> Unit,
    onUp: () -> Unit = {},
    onLegacyActions: ((View) -> Unit)? = null,
    content: @Composable (Modifier) -> Unit
) {
    BatteryTheme {
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val menuFocus = remember { FocusRequester() }
        val headingFocus = remember { FocusRequester() }
        val panelFocus = remember { FocusRequester() }
        var wasOpen by remember { mutableStateOf(false) }
        var focusHeadingOnClose by remember { mutableStateOf(false) }
        val destinations = SectionRegistry.destinations
        val batterySections = destinations.filter { it.group == SectionGroup.BATTERY }
            .map { MenuSection(it.owner.route, stringResource(it.label)) }
        val toolSections = destinations.filter { it.group == SectionGroup.TOOLS }
            .map { MenuSection(it.owner.route, stringResource(it.label)) }
        val label = stringResource(destinations.first { it.owner == selected }.label)
        val density = LocalDensity.current
        val hinge =
            currentWindowAdaptiveInfoV2().windowPosture.separatingVerticalHingeBounds.firstOrNull()

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            val persistent = maxWidth >= 840.dp && maxHeight >= 480.dp && hinge == null
            val contentWidth = hinge?.let { with(density) { it.left.toDp() } } ?: maxWidth
            val navigationLabel = stringResource(
                when {
                    detailTitle != null -> R.string.nav_up
                    persistent -> R.string.nav_focus_menu
                    else -> R.string.nav_open_menu
                }
            )
            LaunchedEffect(persistent) {
                if (persistent) drawerState.close()
            }
            LaunchedEffect(drawerState.currentValue) {
                if (drawerState.isOpen) wasOpen = true
                else if (wasOpen) {
                    if (focusHeadingOnClose) headingFocus.requestFocus()
                    else menuFocus.requestFocus()
                    wasOpen = false
                    focusHeadingOnClose = false
                }
            }
            BackHandler(drawerState.isOpen && !persistent) {
                scope.launch { drawerState.close() }
            }
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = !persistent && detailTitle == null,
                drawerContent = {
                    if (!persistent) ModalDrawerSheet(
                        modifier = Modifier
                            .widthIn(max = 360.dp)
                            .fillMaxHeight(),
                        drawerShape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp),
                        windowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        SideMenuContent(
                            appName = stringResource(R.string.app_full_name),
                            closeLabel = stringResource(R.string.nav_close_menu),
                            batteryGroupLabel = stringResource(R.string.nav_battery_group),
                            toolsGroupLabel = stringResource(R.string.nav_tools_group),
                            batterySections = batterySections,
                            toolSections = toolSections,
                            selectedId = selected.route,
                            onSelect = { route ->
                                val owner = SectionRegistry.owner(route)
                                scope.launch {
                                    focusHeadingOnClose = owner != selected
                                    if (owner != selected) onSelect(owner)
                                    drawerState.close()
                                }
                            },
                            onClose = { scope.launch { drawerState.close() } })
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent {
                        if (it.key == Key.Escape && it.type == KeyEventType.KeyUp && drawerState.isOpen) {
                            scope.launch { drawerState.close() }
                            true
                        } else false
                    }) {
                Row(Modifier.fillMaxSize()) {
                    if (persistent) PermanentDrawerSheet(
                        modifier = Modifier
                            .width(280.dp)
                            .fillMaxHeight(),
                        windowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        SideMenuContent(
                            appName = stringResource(R.string.app_full_name),
                            closeLabel = stringResource(R.string.nav_close_menu),
                            batteryGroupLabel = stringResource(R.string.nav_battery_group),
                            toolsGroupLabel = stringResource(R.string.nav_tools_group),
                            batterySections = batterySections,
                            toolSections = toolSections,
                            selectedId = selected.route,
                            onSelect = { route -> onSelect(SectionRegistry.owner(route)) },
                            onClose = { menuFocus.requestFocus() },
                            showCloseButton = false,
                            firstItemFocusRequester = panelFocus
                        )
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        TopAppBar(title = {
                            Text(
                                detailTitle ?: label,
                                modifier = Modifier
                                    .focusRequester(headingFocus)
                                    .focusable()
                            )
                        }, navigationIcon = {
                            IconButton(onClick = {
                                if (detailTitle != null) onUp()
                                else if (persistent) panelFocus.requestFocus()
                                else scope.launch { drawerState.open() }
                            }, modifier = Modifier
                                .focusRequester(menuFocus)
                                .semantics {
                                    contentDescription = navigationLabel
                                }) {
                                Text(if (detailTitle == null) "☰" else "‹")
                            }
                        }, actions = {
                            if (onLegacyActions != null) AndroidView(
                                factory = { context ->
                                    TextView(context).apply {
                                        text = "⋮"
                                        textSize = 24f
                                        gravity = Gravity.CENTER
                                        isClickable = true
                                        isFocusable = true
                                        contentDescription = context.getString(R.string.nav_actions)
                                        setOnClickListener { onLegacyActions(this) }
                                    }
                                }, modifier = Modifier.size(48.dp)
                            )
                        })
                        Box(
                            Modifier
                                .fillMaxSize()
                                .widthIn(max = 600.dp)
                                .widthIn(max = contentWidth), contentAlignment = Alignment.TopStart
                        ) {
                            content(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}
