/*
    Copyright (c) 2009-2020 Darshan Computing, LLC
    Modified in 2026 by Tomasz Świstak <tomasz@swistak.codes> for the Battery Monitor fork.
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.alarms.AlarmEditActivity
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.common.NotificationSettingsNavigator
import codes.swistak.batterymonitor.diagnostics.DiagnosticsFragment
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringAvailability
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsFragment
import codes.swistak.batterymonitor.settings.temperatureUnit
import codes.swistak.batterymonitor.ui.alarms.AlarmsScreen
import codes.swistak.batterymonitor.ui.alarms.AlarmsViewModel
import codes.swistak.batterymonitor.ui.alarms.ThresholdDialog
import codes.swistak.batterymonitor.ui.current.CurrentMeasurement
import codes.swistak.batterymonitor.ui.current.CurrentStateRoute
import codes.swistak.batterymonitor.ui.current.CurrentStateViewModel
import codes.swistak.batterymonitor.ui.diagnostics.DiagnosticsRoute
import codes.swistak.batterymonitor.ui.diagnostics.DiagnosticsViewModel
import codes.swistak.batterymonitor.ui.diagnostics.MonitorAction
import codes.swistak.batterymonitor.ui.help.LegacyHelpFragment
import codes.swistak.batterymonitor.ui.history.FullscreenChartActivity
import codes.swistak.batterymonitor.ui.history.HistoryActionsMenu
import codes.swistak.batterymonitor.ui.history.HistoryRoute
import codes.swistak.batterymonitor.ui.history.HistoryViewModel
import codes.swistak.batterymonitor.ui.navigation.SectionNavigator
import codes.swistak.batterymonitor.ui.navigation.SectionOwner
import codes.swistak.batterymonitor.ui.navigation.SectionRegistry
import codes.swistak.batterymonitor.ui.navigation.SideNavigationShell
import codes.swistak.batterymonitor.ui.settings.SettingsCategory

class BatteryInfoActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_DETAIL = "codes.swistak.batterymonitor.EXTRA_DETAIL"

        const val EXTRA_SECTION = "codes.swistak.batterymonitor.EXTRA_SECTION"
        const val EXTRA_HISTORY_HOURS = "codes.swistak.batterymonitor.EXTRA_HISTORY_HOURS"
    }

    private lateinit var navigator: SectionNavigator
    private var selected by mutableStateOf(SectionOwner.CURRENT)
    private var containerReady = false
    private var shownDetail: String? = null
    private var detail by mutableStateOf<String?>(null)
    private var currentMeasurement by mutableStateOf<CurrentMeasurement?>(null)
    private var externalNavigationRequest by mutableIntStateOf(0)

    private lateinit var diagnostics: DiagnosticsViewModel
    private lateinit var current: CurrentStateViewModel
    private var monitorActions by mutableStateOf<Map<String, MonitorAction>>(emptyMap())

    private var shown: SectionOwner? = null
    private lateinit var history: HistoryViewModel

    private lateinit var alarms: AlarmsViewModel
    private var pendingAlarmNavigation by mutableStateOf<(() -> Unit)?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.bi_compose_theme)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val persistent = PersistentFragment.getInstance(supportFragmentManager)
        DisplayStrings.setResources(resources)
        val currentSettings = getSharedPreferences(SettingsContract.SETTINGS_FILE, MODE_PRIVATE)
        diagnostics = ViewModelProvider(this)[DiagnosticsViewModel::class.java]
        current = ViewModelProvider(this)[CurrentStateViewModel::class.java]
        history = ViewModelProvider(this)[HistoryViewModel::class.java]
        alarms = ViewModelProvider(this)[AlarmsViewModel::class.java]
        history.restore(savedInstanceState?.getBundle("history_state"))
        currentMeasurement =
            CurrentMeasurement.fromRoute(savedInstanceState?.getString("current_measurement"))
        for (tag in listOf(
            "section:current", "section:history", "section:diagnostics", "section:alarms"
        )) {
            supportFragmentManager.findFragmentByTag(tag)?.let { legacy ->
                supportFragmentManager.commitNow { remove(legacy) }
            }
        }


        navigator = SectionNavigator.restore(savedInstanceState)
        selected = navigator.selected
        detail = navigator.detail
        if (selected == SectionOwner.ALARMS && detail == "alarm-edit") {
            navigator.select(SectionOwner.ALARMS)
            detail = null
        }
        if (savedInstanceState == null && (intent.hasExtra(EXTRA_SECTION) || intent.hasExtra(
                BatteryInfoService.EXTRA_CURRENT_INFO
            ) || intent.hasExtra(BatteryInfoService.EXTRA_EDIT_ALARMS))
        ) routeIntent(intent)

        setContentView(ComposeView(this).apply {
            setContent {
                val sections = rememberSaveableStateHolder()
                var historyAction by rememberSaveable { mutableStateOf<String?>(null) }
                val historyState by history.state.collectAsStateWithLifecycle()
                val alarmState by alarms.state.collectAsStateWithLifecycle()
                val monitoringState by persistent.monitoring.state.collectAsStateWithLifecycle()
                var alarmSettingsVersion by remember { mutableIntStateOf(0) }
                DisposableEffect(currentSettings) {
                    val listener =
                        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                            alarmSettingsVersion++
                        }
                    currentSettings.registerOnSharedPreferenceChangeListener(listener)
                    onDispose { currentSettings.unregisterOnSharedPreferenceChangeListener(listener) }
                }
                val temperatureUnit = remember(alarmSettingsVersion) {
                    currentSettings.temperatureUnit(getString(R.string.default_temperature_unit))
                }
                val chargingTarget = when {
                    currentSettings.getString(
                        SettingsContract.KEY_CHARGING_TARGET_MODE,
                        SettingsContract.CHARGING_TARGET_MODE_AUTOMATIC
                    ) == SettingsContract.CHARGING_TARGET_MODE_CUSTOM -> currentSettings.getInt(
                        SettingsContract.KEY_CUSTOM_CHARGING_TARGET,
                        SettingsContract.DEFAULT_CUSTOM_CHARGING_TARGET
                    ).coerceIn(1, 100)

                    monitoringState.snapshot?.plugged != 0 -> monitoringState.snapshot?.configuredPrediction?.targetPercent?.takeIf { it in 1..100 }

                    else -> null
                }
                val dischargingTarget = currentSettings.getInt(
                    SettingsContract.KEY_DISCHARGING_TARGET,
                    SettingsContract.DEFAULT_DISCHARGING_TARGET
                ).coerceIn(0, 99)
                LaunchedEffect(selected) { if (selected == SectionOwner.ALARMS) alarms.refresh() }
                val alarmLocale = LocalConfiguration.current.locales[0]
                SideNavigationShell(
                    selected = selected,
                    externalNavigationRequest = externalNavigationRequest,
                    detailTitle = detail?.let {
                        getString(
                            when {
                                selected == SectionOwner.ALARMS -> R.string.alarm_settings_subtitle
                                selected == SectionOwner.SETTINGS -> SettingsCategory.fromRoute(
                                    it.substringAfter(
                                        "settings:"
                                    ).substringBefore(':')
                                )?.title ?: R.string.settings_activity_subtitle

                                selected == SectionOwner.HELP -> R.string.menu_help
                                it.substringBefore(':') == "charging-tools" -> R.string.charging_diagnostics_title
                                else -> R.string.diag_monitor_operation
                            }
                        )
                    },
                    onUp = ::navigateUp,
                    onSelect = ::selectSection,
                    onSettings = if (selected == SectionOwner.CURRENT) {
                        { selectSection(SectionOwner.SETTINGS) }
                    } else null,
                    onLegacyActions = null,
                    actions = {
                        if (selected in setOf(
                                SectionOwner.DIAGNOSTICS, SectionOwner.ALARMS, SectionOwner.SETTINGS
                            )
                        ) androidx.compose.material3.IconButton(
                            onClick = {
                                val topic = when (selected) {
                                    SectionOwner.ALARMS -> if (detail == null) SettingsContract.KEY_ALARMS_SETTINGS else SettingsContract.KEY_ALARM_EDIT_SETTINGS
                                    SectionOwner.DIAGNOSTICS -> if (detail == null) SettingsContract.KEY_ADVANCED_INFO_HELP else SettingsContract.KEY_DIAGNOSTICS_SETTINGS
                                    else -> when (SettingsCategory.fromRoute(
                                        detail?.substringAfter(
                                            "settings:"
                                        )?.substringBefore(':')
                                    )) {
                                        SettingsCategory.General -> SettingsContract.KEY_OTHER_SETTINGS
                                        SettingsCategory.Current -> SettingsContract.KEY_CURRENT_STATE_SETTINGS
                                        SettingsCategory.Time -> SettingsContract.KEY_TIME_ESTIMATES_SETTINGS
                                        SettingsCategory.Notification -> SettingsContract.KEY_NOTIFICATION_SETTINGS
                                        SettingsCategory.Advanced -> SettingsContract.KEY_ADVANCED_SETTINGS
                                        SettingsCategory.Backup -> SettingsContract.KEY_BACKUP_RESTORE_SETTINGS
                                        else -> "settings"
                                    }
                                }
                                openHelp(topic)
                            }) {
                            androidx.compose.material3.Icon(
                                androidx.compose.ui.res.painterResource(R.drawable.ui_info),
                                getString(R.string.menu_help)
                            )
                        }
                        if (selected == SectionOwner.HISTORY) HistoryActionsMenu(!historyState.busy) {
                            if (it == "settings") selectSection(SectionOwner.SETTINGS)
                            else historyAction = it
                        }
                    }) { modifier ->
                    Box(modifier) {
                        AndroidView(
                            factory = {
                                layoutInflater.inflate(R.layout.compose_section_container, null)
                                    .apply {
                                        post {
                                            containerReady = true
                                            showSection(selected)
                                        }
                                    }
                            }, modifier = Modifier.fillMaxSize()
                        )
                        if (selected == SectionOwner.CURRENT) sections.SaveableStateProvider("current") {
                            CurrentStateRoute(
                                viewModel = current,
                                monitoring = persistent.monitoring.state,
                                settings = currentSettings,
                                onSection = ::selectSection,
                                onBatteryUsage = ::openBatteryUsage,
                                onMonitor = { openDiagnosticDetail("monitor") },
                                requestedMeasurement = currentMeasurement,
                                onMeasurementHandled = { currentMeasurement = null },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        if (selected == SectionOwner.DIAGNOSTICS && detail == null) {
                            sections.SaveableStateProvider("diagnostics") {
                                DiagnosticsRoute(
                                    diagnostics,
                                    { openDiagnosticDetail("monitor") },
                                    { openDiagnosticDetail("charging-tools") },
                                    persistent.monitoring.state,
                                    monitorActions
                                )
                            }
                        }
                        if (selected == SectionOwner.HISTORY) sections.SaveableStateProvider("history") {
                            HistoryRoute(
                                history,
                                persistent.monitoring.state,
                                requestedAction = historyAction,
                                onActionHandled = { historyAction = null },
                                onOpenFullscreen = { metric, range, label ->
                                    history.retainExactRange()
                                    startActivity(
                                        FullscreenChartActivity.intent(
                                            this@BatteryInfoActivity, metric, range, label
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        if (selected == SectionOwner.ALARMS) {
                            sections.SaveableStateProvider("alarms:list") {
                                AlarmsScreen(
                                    state = alarmState,
                                    convertFahrenheit = temperatureUnit.convertToFahrenheit,
                                    chargingTarget = chargingTarget,
                                    dischargingTarget = dischargingTarget,
                                    monitoringAvailable = monitoringState.availability == MonitoringAvailability.LIVE,
                                    onEdit = {
                                        alarms.beginDraft(
                                            it, temperatureUnit.convertToFahrenheit, alarmLocale
                                        )
                                    },
                                    onAdd = {
                                        alarms.beginThreshold(
                                            it, temperatureUnit.convertToFahrenheit, alarmLocale
                                        )
                                    },
                                    onEnabled = { type, rule, enabled ->
                                        alarms.setEnabled(
                                            type, rule, enabled, persistent::reloadAlarmRules
                                        )
                                    },
                                    onChannelSettings = { type ->
                                        if (!NotificationSettingsNavigator.openNotificationChannel(
                                                this@BatteryInfoActivity, type
                                            )
                                        ) {
                                            alarms.reportSettingsFailure()
                                        }
                                    },
                                    onNotificationSettings = {
                                        if (!NotificationSettingsNavigator.openNotifications(this@BatteryInfoActivity)) {
                                            alarms.reportSettingsFailure()
                                        }
                                    },
                                    onTargets = {
                                        openSettingsCategory(
                                            SettingsCategory.Time.route,
                                            SettingsContract.KEY_CHARGING_TARGET_MODE
                                        )
                                    },
                                    onRetry = alarms::refresh,
                                    onDisableInvalid = {
                                        alarms.disableInvalid(
                                            it, persistent::reloadAlarmRules
                                        )
                                    },
                                    onDeleteInvalid = {
                                        alarms.deleteInvalid(
                                            it, persistent::reloadAlarmRules
                                        )
                                    },
                                    onNoticeHandled = alarms::clearNotice,
                                    onRevealHandled = alarms::clearReveal
                                )
                            }
                            ThresholdDialog(
                                state = alarmState,
                                onInput = alarms::changeDraftInput,
                                onSave = { alarms.saveDraft(persistent::reloadAlarmRules) },
                                onDelete = { alarms.deleteDraft(persistent::reloadAlarmRules) },
                                onDismiss = { leaveAlarmEditor {} },
                                onEditExisting = alarms::editExistingThreshold,
                                onRetry = alarms::refresh
                            )
                        }
                        if (pendingAlarmNavigation != null) AlertDialog(
                            modifier = Modifier.imePadding(),
                            properties = DialogProperties(decorFitsSystemWindows = false),
                            onDismissRequest = { pendingAlarmNavigation = null },
                            text = {
                                Text(
                                    getString(R.string.alarms_discard_confirmation),
                                    Modifier.verticalScroll(rememberScrollState())
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val navigation = pendingAlarmNavigation
                                    pendingAlarmNavigation = null
                                    alarms.discardDraft()
                                    navigation?.invoke()
                                }) { Text(getString(R.string.alarms_discard)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingAlarmNavigation = null }) {
                                    Text(
                                        getString(R.string.cancel)
                                    )
                                }
                            })
                    }
                }
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (selected) {
                    SectionOwner.HELP if detail != null -> handleNavigationBack(this)
                    SectionOwner.ALARMS if alarms.state.value.draft != null -> leaveAlarmEditor {}
                    else -> leaveAlarmEditor { handleNavigationBack(this) }
                }
            }
        })
    }

    private fun handleNavigationBack(callback: OnBackPressedCallback) {
        if (navigator.back()) {
            selected = navigator.selected
            detail = navigator.detail
            showSection(selected)
        } else {
            callback.isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            callback.isEnabled = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    override fun onPostResume() {
        super.onPostResume()
        if (containerReady) showSection(selected)
        if (::alarms.isInitialized && selected == SectionOwner.ALARMS) alarms.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("history_state", history.save())
        outState.putString("current_measurement", currentMeasurement?.route)
        navigator.save(outState)
        super.onSaveInstanceState(outState)
    }

    internal fun selectSection(owner: SectionOwner) {
        leaveAlarmEditor { performSelectSection(owner) }
    }

    private fun performSelectSection(owner: SectionOwner) {
        navigator.select(owner)
        detail = navigator.detail
        selected = navigator.selected
        showSection(selected)
    }

    fun openSettingsCategory(category: String?, highlight: String? = null) {
        if (category == null) {
            performSelectSection(SectionOwner.SETTINGS)
            return
        }
        navigator.openDetail(
            SectionOwner.SETTINGS,
            "settings:$category" + (highlight?.let { ":$it" } ?: ""))
        selected = navigator.selected
        detail = navigator.detail
        showSection(selected)
    }

    private fun openHelp(topic: String) {
        navigator.openDetail(SectionOwner.HELP, "help:$topic")
        selected = navigator.selected
        detail = navigator.detail
        showSection(selected)
    }

    internal fun openDiagnosticDetail(route: String, highlight: String? = null) {
        navigator.openDetail(SectionOwner.DIAGNOSTICS, route + (highlight?.let { ":$it" } ?: ""))
        selected = navigator.selected
        detail = navigator.detail
        showSection(selected)
    }

    private fun navigateUp() {
        if (selected == SectionOwner.HELP && detail != null) performNavigateUp()
        else leaveAlarmEditor { performNavigateUp() }
    }

    private fun leaveAlarmEditor(navigation: () -> Unit) {
        if (alarms.state.value.draft != null) {
            if (alarms.state.value.busy) return
            if (alarms.hasUnsavedChanges) {
                pendingAlarmNavigation = navigation
                return
            }
            alarms.discardDraft()
        }
        navigation()
    }

    private fun openAlarmEditor(id: Int?) {
        leaveAlarmEditor {
            performSelectSection(SectionOwner.ALARMS)
            alarms.openLegacyAlarm(id ?: -1)
        }
    }

    private fun performNavigateUp() {
        navigator.back()
        selected = navigator.selected
        detail = navigator.detail
        showSection(selected)
    }

    private fun routeIntent(intent: Intent) {
        leaveAlarmEditor { performRouteIntent(intent) }
    }

    private fun performRouteIntent(intent: Intent) {
        externalNavigationRequest++
        val owner = when {
            intent.hasExtra(BatteryInfoService.EXTRA_EDIT_ALARMS) -> SectionOwner.ALARMS
            intent.hasExtra(EXTRA_SECTION) -> SectionRegistry.owner(
                intent.getStringExtra(
                    EXTRA_SECTION
                )
            )

            else -> SectionOwner.CURRENT
        }
        val requestedDetail = intent.getStringExtra(EXTRA_DETAIL)
        currentMeasurement = requestedDetail?.takeIf {
            owner == SectionOwner.CURRENT && it.startsWith("measurement:")
        }?.removePrefix("measurement:")?.let(CurrentMeasurement::fromRoute)
        if (owner == SectionOwner.HISTORY && intent.hasExtra(EXTRA_HISTORY_HOURS)) {
            history.openWidgetRange(intent.getIntExtra(EXTRA_HISTORY_HOURS, -1))
        }
        if (owner == SectionOwner.HELP && requestedDetail?.startsWith("help:") == true) {
            openHelp(requestedDetail.removePrefix("help:"))
            return
        }
        if (owner == SectionOwner.SETTINGS && requestedDetail?.startsWith("settings:") == true) {
            val route = requestedDetail.removePrefix("settings:")
            openSettingsCategory(
                route.substringBefore(':'), route.substringAfter(':', "").ifEmpty { null })
            return
        }
        performSelectSection(owner)
        if (owner == SectionOwner.DIAGNOSTICS) intent.getStringExtra(EXTRA_DETAIL)
            ?.takeIf { it.substringBefore(':') in setOf("monitor", "charging-tools") }?.let {
                openDiagnosticDetail(
                    it.substringBefore(':'), it.substringAfter(':', "").ifEmpty { null })
            }
        if (owner == SectionOwner.ALARMS && intent.getStringExtra(EXTRA_DETAIL) == "alarm-edit") {
            openAlarmEditor(
                intent.getIntExtra(AlarmEditActivity.EXTRA_ALARM_ID, -1).takeIf { it >= 0 })
        }
    }

    private fun showSection(owner: SectionOwner) {
        if (!containerReady || supportFragmentManager.isStateSaved || (shown == owner && shownDetail == detail)) return
        val manager = supportFragmentManager
        val actionsOnly = owner == SectionOwner.DIAGNOSTICS && detail == null
        val targetTag = when (owner) {
            SectionOwner.DIAGNOSTICS -> "section:diagnostics:${
                detail?.substringBefore(
                    ':'
                ) ?: "actions"
            }"

            SectionOwner.HELP -> "section:help:${detail ?: "root"}"
            else -> "section:${owner.route}"
        }
        val target = if (owner in setOf(
                SectionOwner.CURRENT, SectionOwner.HISTORY, SectionOwner.ALARMS
            )
        ) null
        else manager.findFragmentByTag(targetTag)
            ?: if (owner == SectionOwner.DIAGNOSTICS) DiagnosticsFragment().apply {
                arguments = Bundle().apply {
                    putBoolean("charging", detail?.substringBefore(':') == "charging-tools")
                    putBoolean("actionsOnly", actionsOnly)
                }
            } else newFragment(owner)
        manager.commitNow {
            setReorderingAllowed(true)
            for (fragment in manager.fragments) {
                if (fragment.tag?.startsWith("section:") == true && fragment !== target) {
                    hide(fragment)
                    setMaxLifecycle(fragment, Lifecycle.State.CREATED)
                }
            }
            if (target != null) {
                if (target.isAdded) {
                    show(target)
                    setMaxLifecycle(target, Lifecycle.State.RESUMED)
                } else if (actionsOnly) add(target, targetTag)
                else add(R.id.section_container, target, targetTag)
            }
        }
        if (target is DiagnosticsFragment) target.highlightAction(
            detail?.substringAfter(':', "")?.ifEmpty { null })
        if (actionsOnly) {
            val actions = (target as DiagnosticsFragment).monitorActions
            monitorActions =
                DiagnosticsFragment.OVERVIEW_KEYS.associateWith { actions.getValue(it) }
        }
        if (target is SettingsFragment) {
            val route = detail?.removePrefix("settings:")
            target.showCategory(
                route?.substringBefore(':'), route?.substringAfter(':', "")?.ifEmpty { null })
        }
        shown = owner
        shownDetail = detail
    }

    private fun newFragment(owner: SectionOwner): Fragment = when (owner) {
        SectionOwner.CURRENT -> error("Current State is rendered by Compose")
        SectionOwner.HISTORY -> error("History is rendered by Compose")
        SectionOwner.ALARMS -> error("Alarms is rendered by Compose")
        SectionOwner.DIAGNOSTICS -> error("Diagnostics is rendered by Compose")
        SectionOwner.SETTINGS -> SettingsFragment()
        SectionOwner.HELP -> LegacyHelpFragment().apply {
            arguments = Bundle().apply { putString("topic", detail?.removePrefix("help:")) }
        }
    }

    private fun openBatteryUsage() {
        try {
            startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        } catch (_: Exception) {
            android.widget.Toast.makeText(
                this, R.string.current_usage_unavailable, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

}
