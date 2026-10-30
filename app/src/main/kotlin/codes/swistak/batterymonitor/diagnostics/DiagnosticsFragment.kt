/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.diagnostics

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteFullException
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.ResultReceiver
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.currentStateAsState
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.app.PersistentFragment
import codes.swistak.batterymonitor.common.NotificationSettingsNavigator
import codes.swistak.batterymonitor.common.RootExecutor
import codes.swistak.batterymonitor.common.hasCause
import codes.swistak.batterymonitor.common.showToast
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogResult
import codes.swistak.batterymonitor.monitoring.BackgroundServiceWatchdog
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import codes.swistak.batterymonitor.monitoring.MonitoringHealthStore
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingDiagnosticCondition
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingDiagnosticReport
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingDiagnosticStore
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingLimitDiagnostics
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.ui.components.NumericValueDialog
import codes.swistak.batterymonitor.ui.components.applicationDialogsAllowed
import codes.swistak.batterymonitor.ui.diagnostics.DiagnosticReportDialog
import codes.swistak.batterymonitor.ui.diagnostics.MonitorAction
import codes.swistak.batterymonitor.ui.diagnostics.MonitorOperationScreen
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import rikka.shizuku.Shizuku
import java.text.DateFormat
import java.util.Date

class DiagnosticsFragment : Fragment(), SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        internal val OVERVIEW_KEYS = listOf(
            "diagnostics_service",
            "diagnostics_heartbeat",
            "diagnostics_database",
            "diagnostics_notifications",
            "diagnostics_live_updates",
            "diagnostics_battery_optimization"
        )

        private const val HEALTHY_HEARTBEAT_AGE_MS = 5L * 60L * 1000L
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 7128

        private const val SERVICE_RESPONSE_TIMEOUT_MS = 2500L
        private const val SERVICE_RESTART_DELAY_MS = 500L
        private const val LOW_STORAGE_THRESHOLD_BYTES = 10L * 1024L * 1024L

        private const val KEY_NOTIFICATIONS = "diagnostics_notifications"
        private const val KEY_LIVE_UPDATES = "diagnostics_live_updates"
        private const val KEY_ROOT = "diagnostics_root"
        private const val KEY_SHIZUKU = "diagnostics_shizuku"

        private const val KEY_SERVICE = "diagnostics_service"
        private const val KEY_HEARTBEAT = "diagnostics_heartbeat"
        private const val KEY_DATABASE = "diagnostics_database"
        private const val KEY_BATTERY_OPTIMIZATION = "diagnostics_battery_optimization"
        private const val KEY_VENDOR_SETTINGS = "diagnostics_vendor_settings"
        private const val KEY_DONT_KILL_MY_APP = "diagnostics_dont_kill_my_app"
        private const val KEY_EXPORT = "diagnostics_export"
        private const val KEY_CLEAR = "diagnostics_clear"
        private const val KEY_CHARGING_CAPTURE = "charging_diagnostics_capture"
        private const val KEY_CHARGING_REPORT = "charging_diagnostics_report"
        private const val KEY_CHARGING_CLEAR = "charging_diagnostics_clear"
        private const val KEY_DIALOG = "diagnostics.dialog"
        private const val KEY_CONDITION = "diagnostics.condition"
        private const val KEY_PENDING_HINT = "charging_diagnostics_privileged_hint_pending"
    }

    private lateinit var settingsPreferences: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rootAvailable: Boolean? = null
    private var checkingRoot = false

    private var serviceCheckGeneration = 0
    private var databaseCheckGeneration = 0
    private var latestServiceResponseElapsedTime = 0L
    private var latestDatabaseResponseElapsedTime = 0L
    private var serviceCheckFailed = false
    private var databaseCheckFailed = false

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) refresh()
        }
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val shizukuDeadListener = Shizuku.OnBinderDeadListener { refresh() }

    private lateinit var dialogModel: DiagnosticsDialogViewModel
    private val dialogState get() = dialogModel.state
    private val capturing: Boolean get() = dialogState.capturing

    @Volatile
    private var rootGeneration = 0

    private val refreshTick = object : Runnable {
        override fun run() {
            if (isResumed) {
                refresh()
                mainHandler.postDelayed(this, 5_000)
            }
        }
    }
    private lateinit var actions: Map<String, MonitorAction>
    internal val monitorActions: Map<String, MonitorAction> get() = actions
    private val actionsOnly: Boolean get() = arguments?.getBoolean("actionsOnly") == true
    private var highlightedAction by mutableStateOf<String?>(null)
    internal fun highlightAction(key: String?) {
        highlightedAction = key
    }

    private fun action(key: String): MonitorAction? = actions[key]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsPreferences = requireContext().getSharedPreferences(
            SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
        )
        dialogModel = ViewModelProvider(this)[DiagnosticsDialogViewModel::class.java]
        if (!dialogModel.initialized) {
            dialogState.restore(
                savedInstanceState?.getString(KEY_DIALOG),
                savedInstanceState?.getInt(KEY_CONDITION, -1)
            )
            dialogModel.initialized = true
        }
        actions = linkedMapOf(
            "diagnostics_notifications" to MonitorAction(R.string.diagnostics_notifications),
            "diagnostics_live_updates" to MonitorAction(R.string.live_updates_notif_chan_name),
            "diagnostics_root" to MonitorAction(R.string.diagnostics_root),
            "diagnostics_shizuku" to MonitorAction(R.string.diagnostics_shizuku),
            "diagnostics_service" to MonitorAction(R.string.diagnostics_service),
            "diagnostics_heartbeat" to MonitorAction(R.string.diag_heartbeat, enabled = false),
            "diagnostics_database" to MonitorAction(R.string.diagnostics_database),
            "diagnostics_battery_optimization" to MonitorAction(R.string.diagnostics_battery_optimization),
            "diagnostics_vendor_settings" to MonitorAction(
                R.string.diagnostics_vendor_settings,
                getString(R.string.diagnostics_vendor_settings_summary)
            ),
            "diagnostics_dont_kill_my_app" to MonitorAction(
                R.string.diagnostics_dont_kill_my_app,
                getString(R.string.diagnostics_dont_kill_my_app_summary)
            ),
            "debug_logging" to MonitorAction(R.string.diagnostics_debug_logs),
            "diagnostics_export" to MonitorAction(
                R.string.diagnostics_export, getString(R.string.diagnostics_export_summary)
            ),
            "diagnostics_clear" to MonitorAction(
                R.string.diagnostics_clear, getString(R.string.diagnostics_clear_summary)
            ),
            "hint" to MonitorAction(
                R.string.charging_diagnostics_instructions_hint, enabled = false
            ),
            "charging_diagnostics_capture" to MonitorAction(
                R.string.charging_diagnostics_capture,
                getString(R.string.charging_diagnostics_capture_summary)
            ),
            "charging_diagnostics_report" to MonitorAction(
                R.string.charging_diagnostics_report,
                getString(R.string.charging_diagnostics_report_summary)
            ),
            "charging_diagnostics_clear" to MonitorAction(
                R.string.charging_diagnostics_clear,
                getString(R.string.charging_diagnostics_clear_summary)
            ),
            "monitor_stop" to MonitorAction(R.string.diag_stop_monitor),
            "monitor_start" to MonitorAction(R.string.diag_start_monitor),
        )
        bindActions()
        action(SettingsContract.KEY_DEBUG_LOGGING)?.onClick = {
            settingsPreferences.edit {
                putBoolean(
                    SettingsContract.KEY_DEBUG_LOGGING,
                    !settingsPreferences.getBoolean(SettingsContract.KEY_DEBUG_LOGGING, false)
                )
            }
        }
        action("monitor_stop")?.onClick = {
            dialogState.open(DiagnosticsDialog.STOP_MONITORING)
        }
        action("monitor_start")?.onClick = {
            PersistentFragment.getInstance(parentFragmentManager).startMonitoring()
            refresh()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        if (actionsOnly) return null
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppBatteryTheme {
                    val groups = listOf(
                        R.string.diagnostics_permissions to listOf(
                            "diagnostics_notifications",
                            "diagnostics_live_updates",
                            "diagnostics_root",
                            "diagnostics_shizuku"
                        ), R.string.diagnostics_monitoring to listOf(
                            "diagnostics_service",
                            "diagnostics_database",
                            "diagnostics_battery_optimization",
                            "diagnostics_vendor_settings",
                            "diagnostics_dont_kill_my_app",
                            "monitor_start",
                            "monitor_stop"
                        ), R.string.diagnostics_device_and_logs to listOf(
                            "debug_logging", "diagnostics_export", "diagnostics_clear"
                        ), R.string.charging_diagnostics_title to listOf(
                            "hint",
                            "charging_diagnostics_capture",
                            "charging_diagnostics_report",
                            "charging_diagnostics_clear"
                        )
                    ).filter { (title, _) ->
                        (title == R.string.charging_diagnostics_title) == (arguments?.getBoolean(
                            "charging"
                        ) == true)
                    }
                    MonitorOperationScreen(groups, actions, highlightedAction)
                    Dialogs()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        settingsPreferences.registerOnSharedPreferenceChangeListener(this)
        if (!actionsOnly) {
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
            Shizuku.addBinderDeadListener(shizukuDeadListener)
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }
        refresh()
        if (settingsPreferences.getBoolean(KEY_PENDING_HINT, false)) {
            dialogState.offerPrivilegedHint()
        } else {
            dialogState.clearPrivilegedHint()
        }
        mainHandler.postDelayed(refreshTick, 5_000)
    }

    override fun onPause() {
        serviceCheckGeneration++
        databaseCheckGeneration++
        rootGeneration++
        checkingRoot = false
        mainHandler.removeCallbacksAndMessages(null)
        settingsPreferences.unregisterOnSharedPreferenceChangeListener(this)
        if (!actionsOnly) {
            Shizuku.removeBinderReceivedListener(shizukuBinderListener)
            Shizuku.removeBinderDeadListener(shizukuDeadListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DIALOG, dialogState.dialog?.name)
        outState.putInt(KEY_CONDITION, dialogState.selectedCondition ?: -1)
    }

    @Composable
    internal fun Dialogs() {
        val state = dialogState
        val savedDialogs = rememberSaveableStateHolder()
        val dialogsAllowed = applicationDialogsAllowed()
        val lifecycleState by lifecycle.currentStateAsState()
        val dialog = state.dialog
        LaunchedEffect(state.capturing, lifecycleState) {
            if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) refresh()
        }
        LaunchedEffect(dialog) {
            for (previous in DiagnosticsDialog.entries) {
                if (previous != dialog) savedDialogs.removeState(previous.name)
            }
        }
        if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED) || dialog == null || !dialogsAllowed) return
        savedDialogs.SaveableStateProvider(dialog.name) { PresentDialog(dialog) }
    }

    @Composable
    private fun PresentDialog(dialog: DiagnosticsDialog) {
        val state = dialogState
        when (dialog) {
            DiagnosticsDialog.STOP_MONITORING -> AlertDialog(
                onDismissRequest = state::dismiss,
                title = { Text(stringResource(R.string.diag_stop_monitor)) },
                text = { Text(stringResource(R.string.diag_stop_monitor_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        state.dismiss()
                        PersistentFragment.getInstance(parentFragmentManager).stopMonitoring()
                        refresh()
                    }) { Text(stringResource(R.string.diag_stop_monitor)) }
                },
                dismissButton = {
                    TextButton(onClick = state::dismiss) { Text(stringResource(R.string.cancel)) }
                })

            DiagnosticsDialog.CHARGING_CONDITION -> AlertDialog(
                onDismissRequest = state::dismiss,
                title = { Text(stringResource(R.string.charging_diagnostics_choose_state)) },
                text = {
                    val labels = listOf(
                        R.string.charging_diagnostics_condition_off,
                        R.string.charging_diagnostics_condition_fixed,
                        R.string.charging_diagnostics_condition_adaptive,
                        R.string.charging_diagnostics_condition_scheduled,
                        R.string.charging_diagnostics_condition_other
                    )
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .selectableGroup()
                    ) {
                        labels.forEachIndexed { index, label ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(
                                        selected = state.selectedCondition == index,
                                        role = Role.RadioButton,
                                        onClick = { state.selectedCondition = index })
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = state.selectedCondition == index, onClick = null
                                )
                                Text(stringResource(label), Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = state.selectedCondition != null, onClick = {
                            state.confirmCondition()?.let(::captureChargingSnapshot)
                        }) { Text(stringResource(R.string.okay)) }
                },
                dismissButton = {
                    TextButton(onClick = state::dismiss) { Text(stringResource(R.string.cancel)) }
                })

            DiagnosticsDialog.FIXED_CHARGING_LIMIT -> NumericValueDialog(
                label = stringResource(R.string.charging_diagnostics_condition_fixed),
                unit = stringResource(R.string.percent_symbol),
                initialValue = DEFAULT_FIXED_CHARGING_LIMIT_PERCENT,
                min = ChargingDiagnosticCondition.MIN_FIXED_PERCENT,
                max = ChargingDiagnosticCondition.MAX_FIXED_PERCENT,
                step = 1,
                saveLabel = stringResource(R.string.okay),
                cancelLabel = stringResource(R.string.cancel),
                errorMessage = {
                    getString(
                        R.string.alarms_numeric_range,
                        ChargingDiagnosticCondition.MIN_FIXED_PERCENT,
                        ChargingDiagnosticCondition.MAX_FIXED_PERCENT
                    )
                },
                onSave = {
                    state.dismiss()
                    captureChargingSnapshot(ChargingDiagnosticCondition.Fixed(it))
                },
                onCancel = state::dismiss,
                fieldLabel = stringResource(R.string.settings_numeric_value)
            )

            DiagnosticsDialog.PRIVILEGED_HINT -> AlertDialog(
                onDismissRequest = ::dismissPrivilegedHint,
                title = { Text(stringResource(R.string.charging_diagnostics_title)) },
                text = {
                    Text(
                        stringResource(R.string.charging_diagnostics_privileged_hint),
                        Modifier.verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = {
                    TextButton(onClick = ::dismissPrivilegedHint) {
                        Text(stringResource(R.string.okay))
                    }
                })

            DiagnosticsDialog.MONITOR_REPORT, DiagnosticsDialog.CHARGING_REPORT -> {
                val appContext = requireContext().applicationContext
                val root = rootAvailable
                val shizuku = shizukuReportStatus()
                val charging = dialog == DiagnosticsDialog.CHARGING_REPORT
                DiagnosticReportDialog(
                    create = { include ->
                        if (charging) ChargingDiagnosticReport.create(
                            appContext, ChargingDiagnosticStore.read(appContext), include
                        ) else DiagnosticsReport.create(appContext, root, shizuku, include)
                    },
                    optionLabel = if (charging) R.string.diag_include_apps else R.string.diag_include_debug,
                    titleResource = if (charging) R.string.charging_diagnostics_report else R.string.diagnostics_export,
                    onDismiss = state::dismiss
                )
            }
        }
    }

    private fun dismissPrivilegedHint() {
        settingsPreferences.edit { putBoolean(KEY_PENDING_HINT, false) }
        dialogState.dismiss()
    }

    override fun onDestroyView() {
        serviceCheckGeneration++
        databaseCheckGeneration++
        super.onDestroyView()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key != SettingsContract.KEY_DEBUG_LOGGING) return
        DebugLogCollector.sync(
            requireContext(), sharedPreferences.getBoolean(key, false)
        )
        refreshDebugLoggingSummary()
    }

    private fun bindActions() {
        action(KEY_NOTIFICATIONS)?.onClick = {
            val context = requireContext()
            if (!NotificationSettingsNavigator.openNotifications(context)) {
                context.showToast(R.string.diag_value_not_available)
            }
        }
        action(KEY_LIVE_UPDATES)?.onClick = {
            val context = requireContext()
            if (!NotificationSettingsNavigator.openLiveUpdates(context)) {
                context.showToast(R.string.diag_value_not_available)
            }
        }
        action(KEY_ROOT)?.onClick = {
            checkRootAccess()
        }
        action(KEY_SHIZUKU)?.onClick = {
            requestOrOpenShizuku()
        }
        action(KEY_SERVICE)?.onClick = {
            requestMonitoringServiceUpdate()
        }
        action(KEY_DATABASE)?.onClick = {
            retryDatabaseLogging()
        }
        action(KEY_BATTERY_OPTIMIZATION)?.onClick = {
            val context = requireContext()
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                context.showToast(R.string.diagnostics_unrestricted)
            } else if (!openBatteryOptimizationSettings()) {
                context.showToast(R.string.diag_value_not_available)
            }
        }
        val family = BackgroundSettingsNavigator.vendorFamily()
        action(KEY_VENDOR_SETTINGS)?.apply {
            isVisible = family != null
            onClick = {
                val context = requireContext()
                if (family != null && !BackgroundSettingsNavigator.openVendorSettings(
                        context, family
                    )
                ) {
                    context.showToast(R.string.diag_value_not_available)
                }
            }
        }
        action(KEY_DONT_KILL_MY_APP)?.onClick = {
            BackgroundSettingsNavigator.openDontKillMyApp(requireContext(), family)
        }
        action(KEY_EXPORT)?.onClick = { dialogState.open(DiagnosticsDialog.MONITOR_REPORT) }
        action(KEY_CLEAR)?.onClick = {
            val cleared = DebugLogCollector.clear(requireContext())
            requireContext().showToast(
                if (cleared) R.string.diagnostics_logs_cleared else R.string.diagnostics_logs_clear_failed,
                Toast.LENGTH_SHORT
            )
        }
        action(KEY_CHARGING_CAPTURE)?.onClick = {
            showChargingConditionPicker()
        }
        action(KEY_CHARGING_REPORT)?.onClick = {
            showChargingReportActions()
        }
        action(KEY_CHARGING_CLEAR)?.onClick = {
            ChargingDiagnosticStore.clear(requireContext())
            refreshChargingDiagnosticsSummary()
            requireContext().showToast(R.string.charging_diagnostics_cleared)
        }
    }

    private fun refresh() {
        if (!isAdded) return
        val context = requireContext()
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        action(KEY_NOTIFICATIONS)?.summary = permissionSummary(
            notificationManager.areNotificationsEnabled()
        )

        action(KEY_LIVE_UPDATES)?.apply {
            isVisible = BatteryInfoService.supportsLiveUpdates()
            summary = permissionSummary(BatteryInfoService.isLiveUpdateEnabledInSystem(context))
        }

        if (!actionsOnly) {
            action(KEY_ROOT)?.summary = when {
                checkingRoot -> getString(R.string.diagnostics_checking)
                rootAvailable == true -> getString(R.string.yes)
                rootAvailable == false -> getString(R.string.diagnostics_root_unavailable)
                else -> getString(R.string.diagnostics_tap_to_check)
            }
            action(KEY_SHIZUKU)?.summary = shizukuDisplayStatus()
        }
        refreshMonitoringStatus()

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        action(KEY_BATTERY_OPTIMIZATION)?.summary =
            if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) getString(R.string.diagnostics_unrestricted) else getString(
                R.string.diagnostics_restricted_tap_to_fix
            )

        refreshDebugLoggingSummary()
        action(KEY_CHARGING_CAPTURE)?.isEnabled = !capturing
        if (!actionsOnly) {
            refreshChargingDiagnosticsSummary()
        }
    }

    private fun showChargingConditionPicker() {
        if (!capturing) dialogState.open(DiagnosticsDialog.CHARGING_CONDITION)
    }

    private fun captureChargingSnapshot(condition: ChargingDiagnosticCondition) {
        val state = dialogState
        if (!state.beginCapture()) return
        val preference = action(KEY_CHARGING_CAPTURE)
        preference?.isEnabled = false
        preference?.summary = getString(R.string.charging_diagnostics_capturing)
        val context = requireContext().applicationContext
        val privilegedEnabled = settingsPreferences.getBoolean(
            SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false
        )
        Thread {
            val result = runCatching {
                val snapshot =
                    ChargingLimitDiagnostics(context, { privilegedEnabled }).capture(condition)
                ChargingDiagnosticStore.append(context, snapshot)
                snapshot to ChargingDiagnosticStore.read(context).size
            }.getOrNull()
            val snapshot = result?.first
            val snapshotCount = result?.second ?: 0
            val needsHint =
                snapshotCount == 1 && snapshot?.hasLimitedUnprivilegedDiscovery() == true
            if (needsHint) settingsPreferences.edit { putBoolean(KEY_PENDING_HINT, true) }
            // Completion belongs to the retained model, even while its fragment is paused or recreated.
            Handler(Looper.getMainLooper()).post {
                state.finishCapture(needsHint)
                if (!isResumed || !isAdded) return@post
                preference?.isEnabled = true
                refreshChargingDiagnosticsSummary()
                requireContext().showToast(
                    if (snapshot != null) R.string.charging_diagnostics_captured
                    else R.string.charging_diagnostics_capture_failed
                )
            }
        }.apply { name = "charging-limit-diagnostics" }.start()
    }

    private fun refreshChargingDiagnosticsSummary() {
        val count = ChargingDiagnosticStore.read(requireContext()).size
        action(KEY_CHARGING_REPORT)?.apply {
            isEnabled = count >= 2
            summary = if (count == 0) {
                getString(R.string.charging_diagnostics_report_summary)
            } else {
                resources.getQuantityString(
                    R.plurals.charging_diagnostics_snapshot_count, count, count
                )
            }
        }
        action(KEY_CHARGING_CLEAR)?.isEnabled = count > 0
        action(KEY_CHARGING_CAPTURE)?.summary =
            getString(if (capturing) R.string.charging_diagnostics_capturing else R.string.charging_diagnostics_capture_summary)
    }

    private fun showChargingReportActions() {
        if (ChargingDiagnosticStore.read(requireContext()).size < 2) requireContext().showToast(R.string.charging_diagnostics_need_two)
        else dialogState.open(DiagnosticsDialog.CHARGING_REPORT)
    }

    private fun refreshMonitoringStatus() {
        val context = requireContext()
        val healthState = MonitoringHealthStore.read(context)
        val now = SystemClock.elapsedRealtime()
        val heartbeat = maxOf(
            healthState.serviceHeartbeatElapsedTime, latestServiceResponseElapsedTime
        )
        val databaseHeartbeat = maxOf(
            healthState.databaseHeartbeatElapsedTime, latestDatabaseResponseElapsedTime
        )
        val serviceDesired = BackgroundServiceWatchdog.isServiceDesired(context)
        action(KEY_SERVICE)?.apply {
            isEnabled = serviceDesired
            summary = statusWithAge(
                serviceDesired && !serviceCheckFailed && isFresh(now, heartbeat),
                heartbeat,
                now,
                if (serviceDesired) R.string.diagnostics_not_running_tap_to_start else R.string.currently_disabled
            )
        }
        action(KEY_HEARTBEAT)?.summary = when {
            heartbeat !in 1..now -> getString(R.string.diag_value_not_available)
            !isFresh(now, heartbeat) -> getString(R.string.current_stale)
            else -> DateFormat.getTimeInstance(DateFormat.SHORT)
                .format(Date(System.currentTimeMillis() - (now - heartbeat)))
        }

        action("monitor_start")?.isVisible = !serviceDesired
        action("monitor_stop")?.isVisible = serviceDesired
        val loggingEnabled = settingsPreferences.getBoolean(
            SettingsContract.KEY_ENABLE_LOGGING, true
        )
        action(KEY_DATABASE)?.apply {
            isEnabled = loggingEnabled
            summary = statusWithAge(
                loggingEnabled && !databaseCheckFailed && isFresh(now, databaseHeartbeat),
                databaseHeartbeat,
                now,
                if (loggingEnabled) R.string.diagnostics_no_recent_database_access else R.string.currently_disabled
            )
        }
    }

    private fun statusWithAge(
        healthy: Boolean, timestamp: Long, now: Long, unhealthyText: Int
    ): String {
        if (!healthy) return getString(unhealthyText)
        val age = DiagnosticsDurationFormatter.format(requireContext(), now - timestamp)
        return getString(R.string.diagnostics_working_last_seen, age)
    }

    private fun isFresh(now: Long, timestamp: Long): Boolean =
        timestamp in 1..now && now - timestamp < HEALTHY_HEARTBEAT_AGE_MS

    private fun permissionSummary(granted: Boolean): String = if (granted) {
        getString(R.string.yes)
    } else {
        getString(R.string.diagnostics_permission_missing_tap_to_fix)
    }

    private fun shizukuDisplayStatus(): String {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return getString(R.string.shizuku_not_running)
        }
        return if (runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)) getString(R.string.yes) else getString(R.string.diagnostics_shizuku_permission_missing)
    }

    private fun shizukuReportStatus(): DiagnosticsReport.ShizukuStatus {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return DiagnosticsReport.ShizukuStatus.NOT_RUNNING
        }
        return if (runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)) DiagnosticsReport.ShizukuStatus.PERMISSION_GRANTED
        else DiagnosticsReport.ShizukuStatus.PERMISSION_MISSING
    }

    private fun checkRootAccess() {
        if (checkingRoot) return
        val generation = ++rootGeneration
        checkingRoot = true
        refresh()
        Thread {
            val available = RootExecutor.requestAccess(allowed = {
                generation == rootGeneration && isResumed
            })
            mainHandler.post {
                if (generation != rootGeneration || !isResumed) return@post
                checkingRoot = false
                rootAvailable = available
                refresh()
            }
        }.apply { name = "diagnostics-root-check" }.start()
    }

    private fun requestOrOpenShizuku() {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            if (runCatching {
                    Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)) {
                if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(
                        false
                    )
                ) {
                    openShizuku()
                } else {
                    runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE) }
                }
            }
            return
        }

        openShizuku()
    }

    private fun openShizuku() {
        val launchIntent = requireContext().packageManager.getLaunchIntentForPackage(
            "moe.shizuku.privileged.api"
        )
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            runCatching {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW, "https://shizuku.rikka.app/guide/setup/".toUri()
                    )
                )
            }
        }
    }

    private fun requestMonitoringServiceUpdate() {
        val generation = ++serviceCheckGeneration
        serviceCheckFailed = false
        action(KEY_SERVICE)?.summary = getString(R.string.diagnostics_checking)
        requestMonitoringServiceResponse(generation, restarting = false)
    }

    private fun requestMonitoringServiceResponse(generation: Int, restarting: Boolean) {
        val context = requireContext().applicationContext
        var responseReceived = false
        val receiver = object : ResultReceiver(mainHandler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (generation != serviceCheckGeneration || !isAdded || responseReceived) return
                responseReceived = true
                if (resultCode != BatteryInfoService.DIAGNOSTICS_RESULT_OK) {
                    handleMissingServiceResponse(generation, restarting)
                    return
                }
                latestServiceResponseElapsedTime = resultData?.getLong(
                    BatteryInfoService.DIAGNOSTICS_RESULT_TIMESTAMP
                )?.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
                serviceCheckFailed = false
                refreshMonitoringStatus()
                requireContext().showToast(R.string.diagnostics_service_responded)
            }
        }
        val result = BatteryInfoService.requestDiagnosticsCheck(context, receiver)
        if (!result.isRequestAccepted()) {
            handleMissingServiceResponse(generation, restarting)
            return
        }

        mainHandler.postDelayed({
            if (generation == serviceCheckGeneration && !responseReceived && isAdded) {
                responseReceived = true
                handleMissingServiceResponse(generation, restarting)
            }
        }, SERVICE_RESPONSE_TIMEOUT_MS)
    }

    private fun handleMissingServiceResponse(generation: Int, restarting: Boolean) {
        if (generation != serviceCheckGeneration || !isAdded) return
        if (restarting) {
            serviceCheckFailed = true
            refreshMonitoringStatus()
            requireContext().showToast(R.string.diagnostics_service_restart_failed)
            return
        }

        requireContext().showToast(R.string.diagnostics_service_restarting)
        val context = requireContext().applicationContext
        val restartGeneration = ++serviceCheckGeneration
        context.stopService(Intent(context, BatteryInfoService::class.java))
        mainHandler.postDelayed({
            if (restartGeneration == serviceCheckGeneration && isAdded) {
                requestMonitoringServiceResponse(restartGeneration, restarting = true)
            }
        }, SERVICE_RESTART_DELAY_MS)
    }

    // This is a best-effort diagnosis after a failed write, based on immediately free space.
    @SuppressLint("UsableSpace")
    private fun retryDatabaseLogging() {
        val context = requireContext()
        if (!settingsPreferences.getBoolean(SettingsContract.KEY_ENABLE_LOGGING, true)) {
            context.showToast(R.string.currently_disabled)
            return
        }

        val generation = ++databaseCheckGeneration
        databaseCheckFailed = false
        action(KEY_DATABASE)?.summary = getString(R.string.diagnostics_checking)
        val appContext = context.applicationContext
        Thread {
            val checkResult = runCatching {
                val database = LogDatabase(appContext)
                try {
                    database.checkHealth()
                } finally {
                    database.close()
                }
            }.getOrElse { LogResult.Failed(it) }
            mainHandler.post {
                if (generation != databaseCheckGeneration || !isAdded) return@post
                when (checkResult) {
                    LogResult.Inserted, LogResult.Duplicate -> {
                        latestDatabaseResponseElapsedTime = SystemClock.elapsedRealtime()
                        databaseCheckFailed = false
                        refreshMonitoringStatus()
                        requireContext().showToast(R.string.diagnostics_database_check_succeeded)
                    }

                    is LogResult.Failed -> {
                        databaseCheckFailed = true
                        refreshMonitoringStatus()
                        val storageProblem =
                            checkResult.error.hasCause<SQLiteFullException>() || appContext.filesDir.usableSpace < LOW_STORAGE_THRESHOLD_BYTES
                        requireContext().showToast(
                            if (storageProblem) {
                                R.string.diagnostics_database_failed_storage
                            } else {
                                R.string.diagnostics_database_failed
                            }
                        )
                    }
                }
            }
        }.apply { name = "diagnostics-database-check" }.start()
    }

    private fun BatteryInfoService.ServiceStartResult.isRequestAccepted(): Boolean =
        this == BatteryInfoService.ServiceStartResult.START_REQUESTED || this == BatteryInfoService.ServiceStartResult.FALLBACK_REQUESTED

    private fun openBatteryOptimizationSettings(): Boolean =
        BackgroundSettingsNavigator.openBatteryOptimization(requireContext())

    private fun refreshDebugLoggingSummary() {
        action(SettingsContract.KEY_DEBUG_LOGGING)?.apply {
            summary = getString(R.string.diagnostics_debug_logs_warning)
            checked = settingsPreferences.getBoolean(SettingsContract.KEY_DEBUG_LOGGING, false)
        }
    }

}
