/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.navigation

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.separatingVerticalHingeBounds
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SideNavigationShell(
    selected: SectionOwner,
    externalNavigationRequest: Int = 0,
    detailTitle: String? = null,
    onSelect: (SectionOwner) -> Unit,
    onUp: () -> Unit = {},
    onSettings: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit
) {
    AppBatteryTheme {
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val menuFocus = remember { FocusRequester() }
        val headingFocus = remember { FocusRequester() }
        val panelFocus = remember { FocusRequester() }
        val inputModeManager = LocalInputModeManager.current
        val keyboardNavigation = inputModeManager.inputMode == InputMode.Keyboard
        val view = LocalView.current
        DisposableEffect(view, drawerState, inputModeManager) {
            val listener = ViewCompat.OnUnhandledKeyEventListenerCompat { _, event ->
                if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && drawerState.isOpen) {
                    inputModeManager.requestInputMode(InputMode.Keyboard)
                    if (event.action == KeyEvent.ACTION_UP) scope.launch { drawerState.close() }
                    true
                } else false
            }
            ViewCompat.addOnUnhandledKeyEventListener(view, listener)
            onDispose { ViewCompat.removeOnUnhandledKeyEventListener(view, listener) }
        }
        var wasOpen by remember { mutableStateOf(false) }
        var focusHeadingOnClose by remember { mutableStateOf(false) }
        val currentSelect by rememberUpdatedState(onSelect)
        val pendingNavigation = remember(scope, drawerState) {
            PendingDrawerNavigation(scope, { drawerState.close() }) { owner ->
                currentSelect(owner)
            }
        }
        DisposableEffect(pendingNavigation) { onDispose { pendingNavigation.cancel() } }
        LaunchedEffect(externalNavigationRequest) {
            if (externalNavigationRequest > 0) {
                pendingNavigation.cancel()
                drawerState.close()
            }
        }
        val destinations = SectionRegistry.destinations
        val batterySections = destinations.filter { it.group == SectionGroup.BATTERY }
            .map { MenuSection(it.owner.route, stringResource(it.label)) }
        val toolSections = destinations.filter { it.group == SectionGroup.TOOLS }
            .map { MenuSection(it.owner.route, stringResource(it.label)) }
        val label = stringResource(destinations.first { it.owner == selected }.label)
        val settingsLabel = stringResource(R.string.nav_settings)
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val safeLeft = with(density) {
            WindowInsets.safeDrawing.getLeft(density, layoutDirection).toDp()
        }
        val hinge =
            currentWindowAdaptiveInfoV2().windowPosture.separatingVerticalHingeBounds.firstOrNull()

        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
            ) {
                val persistent =
                    maxWidth >= 840.dp * density.fontScale && maxHeight >= 480.dp && hinge == null
                val contentWidth = hinge?.let {
                    with(density) {
                        startPaneWidth(
                            maxWidth,
                            it.left.toDp() - safeLeft,
                            it.right.toDp() - safeLeft,
                            layoutDirection
                        )
                    }
                } ?: maxWidth
                val modalOpen = !persistent && drawerState.isOpen
                val navigationLabel = stringResource(
                    when {
                        detailTitle != null -> R.string.nav_up
                        persistent -> R.string.nav_focus_menu
                        else -> R.string.nav_open_menu
                    }
                )
                LaunchedEffect(persistent) {
                    if (persistent) {
                        pendingNavigation.cancel()
                        drawerState.close()
                    }
                }
                LaunchedEffect(drawerState.currentValue, keyboardNavigation) {
                    if (drawerState.isOpen) {
                        wasOpen = true
                        if (keyboardNavigation) panelFocus.requestFocus()
                    } else if (wasOpen) {
                        if (keyboardNavigation) {
                            if (focusHeadingOnClose) headingFocus.requestFocus()
                            else menuFocus.requestFocus()
                        }
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
                                .widthIn(
                                    max = minOf(
                                        360.dp, contentWidth
                                    )
                                )
                                .focusProperties {
                                    onEnter = {
                                        if (!drawerState.isOpen) cancelFocusChange()
                                        else if (keyboardNavigation) panelFocus.requestFocus()
                                    }
                                    onExit = {
                                        if (drawerState.isOpen) cancelFocusChange()
                                    }
                                }
                                .focusGroup()
                                .semantics {
                                    if (!drawerState.isOpen) hideFromAccessibility()
                                }
                                .fillMaxHeight(),
                            drawerShape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp),
                            windowInsets = WindowInsets(0, 0, 0, 0)) {
                            SideMenuContent(
                                appName = stringResource(R.string.app_full_name),
                                closeLabel = stringResource(R.string.nav_close_menu),
                                batteryGroupLabel = stringResource(R.string.nav_battery_group),
                                toolsGroupLabel = stringResource(R.string.nav_tools_group),
                                batterySections = batterySections,
                                toolSections = toolSections,
                                selectedId = selected.route,
                                firstItemFocusRequester = panelFocus,
                                onSelect = { route ->
                                    val owner = SectionRegistry.owner(route)
                                    focusHeadingOnClose = owner != selected
                                    pendingNavigation.select(owner)
                                },
                                onClose = { scope.launch { drawerState.close() } })
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent {
                            if (it.key == Key.Escape && drawerState.isOpen) {
                                inputModeManager.requestInputMode(InputMode.Keyboard)
                                if (it.type == KeyEventType.KeyUp) scope.launch { drawerState.close() }
                                true
                            } else false
                        }) {
                    Row(Modifier
                        .fillMaxSize()
                        .focusProperties {
                            onEnter = { if (modalOpen) cancelFocusChange() }
                        }
                        .focusGroup()
                        .semantics { if (modalOpen) hideFromAccessibility() }) {
                        if (persistent) PermanentDrawerSheet(
                            modifier = Modifier
                                .width(280.dp)
                                .fillMaxHeight(),
                            drawerShape = RectangleShape,
                            drawerContainerColor = MaterialTheme.colorScheme.surface,
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
                                onClose = { if (keyboardNavigation) menuFocus.requestFocus() },
                                showCloseButton = false,
                                firstItemFocusRequester = panelFocus
                            )
                        }
                        if (persistent) Spacer(
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                        Column(
                            Modifier
                                .then(
                                    if (hinge != null) Modifier.width(contentWidth) else Modifier.weight(
                                        1f
                                    )
                                )
                                .fillMaxHeight()
                        ) {
                            TopAppBar(
                                windowInsets = WindowInsets(0, 0, 0, 0),
                                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.background
                                ),
                                title = {
                                    Text(
                                        detailTitle ?: label,
                                        modifier = Modifier
                                            .focusRequester(headingFocus)
                                            .focusable()
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = {
                                        if (detailTitle != null) onUp()
                                        else if (persistent) {
                                            if (keyboardNavigation) panelFocus.requestFocus()
                                        } else scope.launch { drawerState.open() }
                                    }, modifier = Modifier
                                        .focusRequester(menuFocus)
                                        .semantics {
                                            contentDescription = navigationLabel
                                        }) {
                                        Icon(
                                            painterResource(if (detailTitle == null) R.drawable.ui_menu else R.drawable.ui_back),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                },
                                actions = {
                                    if (onSettings != null) IconButton(onClick = onSettings) {
                                        Icon(
                                            painterResource(R.drawable.ui_settings),
                                            contentDescription = settingsLabel,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    actions()
                                })
                            val maximumContentWidth = when (selected) {
                                SectionOwner.CURRENT -> 1120.dp
                                SectionOwner.HISTORY -> 1120.dp
                                else -> 600.dp
                            }
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Box(
                                    Modifier
                                        .widthIn(max = minOf(maximumContentWidth, contentWidth))
                                        .fillMaxSize()
                                ) {
                                    content(Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun startPaneWidth(
    availableWidth: Dp, hingeLeft: Dp, hingeRight: Dp, direction: LayoutDirection
): Dp = (if (direction == LayoutDirection.Rtl) availableWidth - hingeRight else hingeLeft).coerceIn(
    0.dp, availableWidth
)
