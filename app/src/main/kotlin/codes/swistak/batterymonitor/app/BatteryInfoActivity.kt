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
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.alarms.AlarmsFragment
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.diagnostics.DiagnosticsFragment
import codes.swistak.batterymonitor.logs.LogViewFragment
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsFragment
import codes.swistak.batterymonitor.settings.SettingsHelpActivity
import codes.swistak.batterymonitor.ui.current.CurrentStateRoute
import codes.swistak.batterymonitor.ui.diagnostics.DiagnosticsRoute
import codes.swistak.batterymonitor.ui.diagnostics.DiagnosticsViewModel
import codes.swistak.batterymonitor.ui.diagnostics.MonitorAction
import codes.swistak.batterymonitor.ui.help.LegacyHelpFragment
import codes.swistak.batterymonitor.ui.history.HistoryActionsMenu
import codes.swistak.batterymonitor.ui.history.HistoryRoute
import codes.swistak.batterymonitor.ui.history.HistoryViewModel
import codes.swistak.batterymonitor.ui.navigation.SectionNavigator
import codes.swistak.batterymonitor.ui.navigation.SectionOwner
import codes.swistak.batterymonitor.ui.navigation.SectionRegistry
import codes.swistak.batterymonitor.ui.navigation.SideNavigationShell

class BatteryInfoActivity : AppCompatActivity() {
    companion object {
        const val PR_LVF_WRITE_STORAGE = 1
        const val EXTRA_DETAIL = "codes.swistak.batterymonitor.EXTRA_DETAIL"

        const val EXTRA_SECTION = "codes.swistak.batterymonitor.EXTRA_SECTION"
    }

    private lateinit var navigator: SectionNavigator
    private var selected by mutableStateOf(SectionOwner.CURRENT)
    private var containerReady = false
    private var shownDetail: String? = null
    private var detail by mutableStateOf<String?>(null)
    private lateinit var diagnostics: DiagnosticsViewModel
    private var monitorActions by mutableStateOf<Map<String, MonitorAction>>(emptyMap())

    private var shown: SectionOwner? = null
    private lateinit var history: HistoryViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.bi_compose_theme)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val persistent = PersistentFragment.getInstance(supportFragmentManager)
        DisplayStrings.setResources(resources)
        val currentSettings = getSharedPreferences(SettingsContract.SETTINGS_FILE, MODE_PRIVATE)
        diagnostics = ViewModelProvider(this)[DiagnosticsViewModel::class.java]
        history = ViewModelProvider(this)[HistoryViewModel::class.java]
        history.restore(savedInstanceState?.getBundle("history_state"))
        for (tag in listOf("section:current", "section:history", "section:diagnostics")) {
            supportFragmentManager.findFragmentByTag(tag)?.let { legacy ->
                supportFragmentManager.commitNow { remove(legacy) }
            }
        }


        navigator = SectionNavigator.restore(savedInstanceState)
        selected = navigator.selected
        detail = navigator.detail
        if (savedInstanceState == null && (intent.hasExtra(EXTRA_SECTION) || intent.hasExtra(
                BatteryInfoService.EXTRA_CURRENT_INFO
            ) || intent.hasExtra(BatteryInfoService.EXTRA_EDIT_ALARMS))
        ) routeIntent(intent)

        setContentView(ComposeView(this).apply {
            setContent {
                val sections = rememberSaveableStateHolder()
                var historyAction by rememberSaveable { mutableStateOf<String?>(null) }
                val historyState by history.state.collectAsStateWithLifecycle()
                SideNavigationShell(
                    selected = selected,
                    detailTitle = detail?.let { getString(if (it == "charging-tools") R.string.charging_diagnostics_title else R.string.diag_monitor_operation) },
                    onUp = ::navigateUp,
                    onSelect = ::selectSection,
                    onSettings = if (selected == SectionOwner.CURRENT) {
                        { selectSection(SectionOwner.SETTINGS) }
                    } else null,
                    onLegacyActions = if (selected in setOf(
                            SectionOwner.HELP,
                            SectionOwner.CURRENT,
                            SectionOwner.HISTORY,
                            SectionOwner.DIAGNOSTICS
                        )
                    ) {
                        null
                    } else ::showLegacyActions,
                    actions = {
                        if (selected == SectionOwner.DIAGNOSTICS) androidx.compose.material3.IconButton(
                            onClick = {
                                startActivity(
                                    Intent(
                                        this@BatteryInfoActivity, SettingsHelpActivity::class.java
                                    ).putExtra(
                                        SettingsContract.EXTRA_SCREEN,
                                        if (detail == null) SettingsContract.KEY_ADVANCED_INFO_HELP else SettingsContract.KEY_DIAGNOSTICS_SETTINGS
                                    )
                                )
                            }) {
                            androidx.compose.material3.Icon(
                                androidx.compose.ui.res.painterResource(
                                    R.drawable.ui_info
                                ), getString(R.string.nav_help)
                            )
                        }
                        if (selected == SectionOwner.HISTORY) HistoryActionsMenu(!historyState.busy) {
                            if (it == "settings") selectSection(SectionOwner.SETTINGS)
                            else historyAction = it
                        }
                    }) { modifier ->
                    Box(modifier) {
                        AndroidView(
                            factory = { context ->
                                FrameLayout(context).apply {
                                    id = R.id.section_container
                                    post {
                                        containerReady = true
                                        showSection(selected)
                                    }
                                }
                            }, modifier = Modifier.fillMaxSize()
                        )
                        if (selected == SectionOwner.CURRENT) {
                            CurrentStateRoute(
                                monitoring = persistent.monitoring.state,
                                settings = currentSettings,
                                onSection = ::selectSection,
                                onBatteryUsage = ::openBatteryUsage,
                                onMonitor = { openDiagnosticDetail("monitor") },
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
                        if (selected == SectionOwner.HISTORY) {
                            HistoryRoute(
                                history,
                                persistent.monitoring.state,
                                requestedAction = historyAction,
                                onActionHandled = { historyAction = null },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navigator.back()) {
                    selected = navigator.selected
                    detail = navigator.detail
                    showSection(selected)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    override fun onPostResume() {
        super.onPostResume()
        if (containerReady) showSection(selected)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("history_state", history.save())
        navigator.save(outState)
        super.onSaveInstanceState(outState)
    }

    internal fun selectSection(owner: SectionOwner) {
        navigator.select(owner)
        detail = navigator.detail
        selected = navigator.selected
        showSection(selected)
    }

    private fun openDiagnosticDetail(route: String) {
        navigator.openDetail(SectionOwner.DIAGNOSTICS, route)
        selected = navigator.selected
        detail = navigator.detail
        showSection(selected)
    }

    private fun navigateUp() {
        navigator.back()
        selected = navigator.selected
        detail = navigator.detail
        showSection(selected)
    }

    private fun routeIntent(intent: Intent) {
        val owner = when {
            intent.hasExtra(BatteryInfoService.EXTRA_EDIT_ALARMS) -> SectionOwner.ALARMS
            intent.hasExtra(EXTRA_SECTION) -> SectionRegistry.owner(
                intent.getStringExtra(
                    EXTRA_SECTION
                )
            )

            else -> SectionOwner.CURRENT
        }
        selectSection(owner)
        if (owner == SectionOwner.DIAGNOSTICS) intent.getStringExtra(EXTRA_DETAIL)
            ?.takeIf { it in setOf("monitor", "charging-tools") }?.let(::openDiagnosticDetail)
    }

    private fun showSection(owner: SectionOwner) {
        if (!containerReady || supportFragmentManager.isStateSaved || (shown == owner && shownDetail == detail)) return
        val manager = supportFragmentManager
        val actionsOnly = owner == SectionOwner.DIAGNOSTICS && detail == null
        val targetTag =
            if (owner == SectionOwner.DIAGNOSTICS) "section:diagnostics:${detail ?: "actions"}" else "section:${owner.route}"
        val target = if (owner in setOf(SectionOwner.CURRENT, SectionOwner.HISTORY)) null
        else manager.findFragmentByTag(targetTag)
            ?: if (owner == SectionOwner.DIAGNOSTICS) DiagnosticsFragment().apply {
                arguments = Bundle().apply {
                    putBoolean("charging", detail == "charging-tools")
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
                    show(target); setMaxLifecycle(target, Lifecycle.State.RESUMED)
                } else if (actionsOnly) add(target, targetTag)
                else add(R.id.section_container, target, targetTag)
            }
        }
        if (actionsOnly) {
            val actions = (target as DiagnosticsFragment).monitorActions
            monitorActions =
                DiagnosticsFragment.OVERVIEW_KEYS.associateWith { actions.getValue(it) }
        }
        shown = owner
        shownDetail = detail
    }

    private fun newFragment(owner: SectionOwner): Fragment = when (owner) {
        SectionOwner.CURRENT -> error("Current State is rendered by Compose")
        SectionOwner.HISTORY -> error("History is rendered by Compose")
        SectionOwner.ALARMS -> AlarmsFragment()
        SectionOwner.DIAGNOSTICS -> error("Diagnostics is rendered by Compose")
        SectionOwner.SETTINGS -> SettingsFragment().apply { setScreen(R.xml.main_pref_screen) }
        SectionOwner.HELP -> LegacyHelpFragment()
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

    @Suppress("DEPRECATION")
    private fun showLegacyActions(anchor: View) {
        val fragment =
            supportFragmentManager.findFragmentByTag("section:${selected.route}") ?: return
        val popup = PopupMenu(this, anchor)
        if (selected == SectionOwner.SETTINGS) {
            menuInflater.inflate(R.menu.settings, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.menu_help) {
                    startActivity(Intent(this, SettingsHelpActivity::class.java))
                    true
                } else false
            }
        } else {
            fragment.onCreateOptionsMenu(popup.menu, menuInflater)
            fragment.onPrepareOptionsMenu(popup.menu)
            popup.setOnMenuItemClickListener { fragment.onOptionsItemSelected(it) }
        }
        popup.show()
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PR_LVF_WRITE_STORAGE) {
            (supportFragmentManager.findFragmentByTag("section:history") as? LogViewFragment)?.onRequestPermissionsResult(
                requestCode, permissions, grantResults
            )
        }
    }
}
