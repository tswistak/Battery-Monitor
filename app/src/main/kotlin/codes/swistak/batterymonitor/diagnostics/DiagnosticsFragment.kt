/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.diagnostics

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteFullException
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.ResultReceiver
import android.os.SystemClock
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import codes.swistak.batterymonitor.R
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
import codes.swistak.batterymonitor.privileged.PrivilegedAccess
import codes.swistak.batterymonitor.settings.SettingsActivity
import codes.swistak.batterymonitor.settings.SettingsContract
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        private const val EXPORT_DIAGNOSTICS_REQUEST = 128
        private const val EXPORT_CHARGING_DIAGNOSTICS_REQUEST = 129

        private const val ROOT_CHECK_COMMAND = "id"
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
        private const val KEY_DATABASE = "diagnostics_database"
        private const val KEY_BATTERY_OPTIMIZATION = "diagnostics_battery_optimization"
        private const val KEY_VENDOR_SETTINGS = "diagnostics_vendor_settings"
        private const val KEY_DONT_KILL_MY_APP = "diagnostics_dont_kill_my_app"
        private const val KEY_EXPORT = "diagnostics_export"
        private const val KEY_CLEAR = "diagnostics_clear"
        private const val KEY_CHARGING_CAPTURE = "charging_diagnostics_capture"
        private const val KEY_CHARGING_REPORT = "charging_diagnostics_report"
        private const val KEY_CHARGING_CLEAR = "charging_diagnostics_clear"
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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = SettingsContract.SETTINGS_FILE
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        settingsPreferences = requireNotNull(preferenceManager.sharedPreferences)
        PrivilegedAccess.initialize(requireContext())
        PrivilegedAccess.setEnabled(
            settingsPreferences.getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false)
        )
        setPreferencesFromResource(R.xml.diagnostics_pref_screen, rootKey)
        bindActions()
    }

    override fun onResume() {
        super.onResume()
        settingsPreferences.registerOnSharedPreferenceChangeListener(this)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        refresh()
    }

    override fun onPause() {
        settingsPreferences.unregisterOnSharedPreferenceChangeListener(this)
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onPause()
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
        findPreference<Preference>(KEY_NOTIFICATIONS)?.setOnPreferenceClickListener {
            val context = requireContext()
            if (!NotificationSettingsNavigator.openNotifications(context)) {
                context.showToast(R.string.advanced_value_not_available)
            }
            true
        }
        findPreference<Preference>(KEY_LIVE_UPDATES)?.setOnPreferenceClickListener {
            val context = requireContext()
            if (!NotificationSettingsNavigator.openLiveUpdates(context)) {
                context.showToast(R.string.advanced_value_not_available)
            }
            true
        }
        findPreference<Preference>(KEY_ROOT)?.setOnPreferenceClickListener {
            checkRootAccess()
            true
        }
        findPreference<Preference>(KEY_SHIZUKU)?.setOnPreferenceClickListener {
            requestOrOpenShizuku()
            true
        }
        findPreference<Preference>(KEY_SERVICE)?.setOnPreferenceClickListener {
            requestMonitoringServiceUpdate()
            true
        }
        findPreference<Preference>(KEY_DATABASE)?.setOnPreferenceClickListener {
            retryDatabaseLogging()
            true
        }
        findPreference<Preference>(KEY_BATTERY_OPTIMIZATION)?.setOnPreferenceClickListener {
            val context = requireContext()
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                context.showToast(R.string.diagnostics_unrestricted)
            } else if (!openBatteryOptimizationSettings()) {
                context.showToast(R.string.advanced_value_not_available)
            }
            true
        }
        val family = BackgroundSettingsNavigator.vendorFamily()
        findPreference<Preference>(KEY_VENDOR_SETTINGS)?.apply {
            isVisible = family != null
            setOnPreferenceClickListener {
                val context = requireContext()
                if (family != null && !BackgroundSettingsNavigator.openVendorSettings(
                        context, family
                    )
                ) {
                    context.showToast(R.string.advanced_value_not_available)
                }
                true
            }
        }
        findPreference<Preference>(KEY_DONT_KILL_MY_APP)?.setOnPreferenceClickListener {
            BackgroundSettingsNavigator.openDontKillMyApp(requireContext(), family)
            true
        }
        findPreference<Preference>(KEY_EXPORT)?.setOnPreferenceClickListener {
            val timestamp = SimpleDateFormat(
                "yyyy-MM-dd-HHmmss", Locale.getDefault()
            ).format(Date())
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, "battery_monitor_diagnostics_$timestamp.txt"),
                EXPORT_DIAGNOSTICS_REQUEST
            )
            true
        }
        findPreference<Preference>(KEY_CLEAR)?.setOnPreferenceClickListener {
            val cleared = DebugLogCollector.clear(requireContext())
            requireContext().showToast(
                if (cleared) R.string.diagnostics_logs_cleared else R.string.diagnostics_logs_clear_failed,
                Toast.LENGTH_SHORT
            )
            true
        }
        findPreference<Preference>(KEY_CHARGING_CAPTURE)?.setOnPreferenceClickListener {
            showChargingConditionPicker()
            true
        }
        findPreference<Preference>(KEY_CHARGING_REPORT)?.setOnPreferenceClickListener {
            showChargingReportActions()
            true
        }
        findPreference<Preference>(KEY_CHARGING_CLEAR)?.setOnPreferenceClickListener {
            ChargingDiagnosticStore.clear(requireContext())
            refreshChargingDiagnosticsSummary()
            requireContext().showToast(R.string.charging_diagnostics_cleared)
            true
        }
    }

    private fun refresh() {
        if (!isAdded) return
        val context = requireContext()
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        findPreference<Preference>(KEY_NOTIFICATIONS)?.summary = permissionSummary(
            notificationManager.areNotificationsEnabled()
        )

        findPreference<Preference>(KEY_LIVE_UPDATES)?.apply {
            isVisible = BatteryInfoService.supportsLiveUpdates()
            summary = permissionSummary(BatteryInfoService.isLiveUpdateEnabledInSystem(context))
        }

        findPreference<Preference>(KEY_ROOT)?.summary = when {
            checkingRoot -> getString(R.string.diagnostics_checking)
            rootAvailable == true -> getString(R.string.yes)
            rootAvailable == false -> getString(R.string.diagnostics_root_unavailable)
            else -> getString(R.string.diagnostics_tap_to_check)
        }
        findPreference<Preference>(KEY_SHIZUKU)?.summary = shizukuDisplayStatus()
        refreshMonitoringStatus()

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        findPreference<Preference>(KEY_BATTERY_OPTIMIZATION)?.summary =
            if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) getString(R.string.diagnostics_unrestricted) else getString(
                R.string.diagnostics_restricted_tap_to_fix
            )

        refreshDebugLoggingSummary()
        refreshChargingDiagnosticsSummary()
    }

    private fun showChargingConditionPicker() {
        val labels = intArrayOf(
            R.string.charging_diagnostics_condition_off,
            R.string.charging_diagnostics_condition_fixed,
            R.string.charging_diagnostics_condition_adaptive,
            R.string.charging_diagnostics_condition_scheduled,
            R.string.charging_diagnostics_condition_other
        ).map(::getString).toTypedArray()
        AlertDialog.Builder(requireContext()).setTitle(R.string.charging_diagnostics_choose_state)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> captureChargingSnapshot(ChargingDiagnosticCondition.Off)
                    1 -> showFixedChargingLimitPicker()
                    2 -> captureChargingSnapshot(ChargingDiagnosticCondition.Adaptive)
                    3 -> captureChargingSnapshot(ChargingDiagnosticCondition.Scheduled)
                    4 -> captureChargingSnapshot(ChargingDiagnosticCondition.Other)
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showFixedChargingLimitPicker() {
        val context = requireContext()
        val horizontalPadding = (24 * resources.displayMetrics.density).toInt()
        val picker = NumberPicker(context).apply {
            minValue = ChargingDiagnosticCondition.MIN_FIXED_PERCENT
            maxValue = ChargingDiagnosticCondition.MAX_FIXED_PERCENT
            value = 80
            wrapSelectorWheel = false
        }
        val container = FrameLayout(context).apply {
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(
                picker, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(context).setTitle(R.string.charging_diagnostics_condition_fixed)
            .setView(container).setPositiveButton(android.R.string.ok) { _, _ ->
                captureChargingSnapshot(ChargingDiagnosticCondition.Fixed(picker.value))
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun captureChargingSnapshot(condition: ChargingDiagnosticCondition) {
        val preference = findPreference<Preference>(KEY_CHARGING_CAPTURE)
        preference?.isEnabled = false
        preference?.summary = getString(R.string.charging_diagnostics_capturing)
        val context = requireContext().applicationContext
        val privilegedEnabled = settingsPreferences.getBoolean(
            SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false
        )
        Thread {
            val snapshot = runCatching {
                ChargingLimitDiagnostics(context, { privilegedEnabled }).capture(condition)
            }.getOrNull()
            val snapshotCount = if (snapshot != null) {
                ChargingDiagnosticStore.append(context, snapshot)
                ChargingDiagnosticStore.read(context).size
            } else {
                0
            }
            mainHandler.post {
                if (!isAdded) return@post
                preference?.isEnabled = true
                refreshChargingDiagnosticsSummary()
                requireContext().showToast(
                    if (snapshot != null) R.string.charging_diagnostics_captured
                    else R.string.charging_diagnostics_capture_failed
                )
                if (snapshotCount == 1 && snapshot?.hasLimitedUnprivilegedDiscovery() == true) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.charging_diagnostics_title)
                        .setMessage(R.string.charging_diagnostics_privileged_hint)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
        }.apply { name = "charging-limit-diagnostics" }.start()
    }

    private fun refreshChargingDiagnosticsSummary() {
        val count = ChargingDiagnosticStore.read(requireContext()).size
        findPreference<Preference>(KEY_CHARGING_REPORT)?.apply {
            isEnabled = count >= 2
            summary = if (count == 0) {
                getString(R.string.charging_diagnostics_report_summary)
            } else {
                resources.getQuantityString(
                    R.plurals.charging_diagnostics_snapshot_count, count, count
                )
            }
        }
        findPreference<Preference>(KEY_CHARGING_CLEAR)?.isEnabled = count > 0
        findPreference<Preference>(KEY_CHARGING_CAPTURE)?.summary =
            getString(R.string.charging_diagnostics_capture_summary)
    }

    private fun showChargingReportActions() {
        val snapshots = ChargingDiagnosticStore.read(requireContext())
        if (snapshots.size < 2) {
            requireContext().showToast(R.string.charging_diagnostics_need_two)
            return
        }
        val report = ChargingDiagnosticReport.create(requireContext(), snapshots)
        val context = requireContext()
        val padding = (16 * resources.displayMetrics.density).toInt()
        val reportView = TextView(context).apply {
            text = report
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(padding, padding, padding, padding)
        }
        val scrollView = ScrollView(context).apply { addView(reportView) }
        AlertDialog.Builder(requireContext()).setTitle(R.string.charging_diagnostics_report)
            .setView(scrollView).setPositiveButton(R.string.charging_diagnostics_copy) { _, _ ->
                copyChargingReport(report)
            }.setNeutralButton(R.string.charging_diagnostics_save) { _, _ ->
                saveChargingReport()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun copyChargingReport(report: String) {
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OEM charging-limit diagnostics", report))
        requireContext().showToast(R.string.charging_diagnostics_copied)
    }

    private fun saveChargingReport() {
        val timestamp = SimpleDateFormat(
            "yyyy-MM-dd-HHmmss", Locale.getDefault()
        ).format(Date())
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(
                    Intent.EXTRA_TITLE, "battery_monitor_charging_diagnostics_$timestamp.txt"
                ), EXPORT_CHARGING_DIAGNOSTICS_REQUEST
        )
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
        findPreference<Preference>(KEY_SERVICE)?.apply {
            isEnabled = serviceDesired
            summary = statusWithAge(
                serviceDesired && !serviceCheckFailed && isFresh(now, heartbeat),
                heartbeat,
                now,
                if (serviceDesired) R.string.diagnostics_not_running_tap_to_start else R.string.currently_disabled
            )
        }

        val loggingEnabled = settingsPreferences.getBoolean(
            SettingsContract.KEY_ENABLE_LOGGING, true
        )
        findPreference<Preference>(KEY_DATABASE)?.summary = statusWithAge(
            loggingEnabled && !databaseCheckFailed && isFresh(now, databaseHeartbeat),
            databaseHeartbeat,
            now,
            if (loggingEnabled) R.string.diagnostics_no_recent_database_access else R.string.currently_disabled
        )
    }

    private fun statusWithAge(
        healthy: Boolean, timestamp: Long, now: Long, unhealthyText: Int
    ): CharSequence {
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
        checkingRoot = true
        refresh()
        Thread {
            val available = RootExecutor().run(ROOT_CHECK_COMMAND)?.contains("uid=0") == true
            mainHandler.post {
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
        findPreference<Preference>(KEY_SERVICE)?.summary = getString(R.string.diagnostics_checking)
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

    private fun retryDatabaseLogging() {
        val context = requireContext()
        if (!settingsPreferences.getBoolean(SettingsContract.KEY_ENABLE_LOGGING, true)) {
            context.showToast(R.string.currently_disabled)
            startActivity(
                Intent(context, SettingsActivity::class.java).putExtra(
                    SettingsContract.EXTRA_SCREEN, SettingsContract.KEY_OTHER_SETTINGS
                )
            )
            return
        }

        val generation = ++databaseCheckGeneration
        databaseCheckFailed = false
        findPreference<Preference>(KEY_DATABASE)?.summary = getString(R.string.diagnostics_checking)
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

    @SuppressLint("BatteryLife")
    private fun openBatteryOptimizationSettings(): Boolean {
        val context = requireContext()
        val appUri = "package:${context.packageName}".toUri()
        val intents = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)
        )
        return intents.any { intent -> runCatching { startActivity(intent) }.isSuccess }
    }

    private fun refreshDebugLoggingSummary() {
        findPreference<SwitchPreferenceCompat>(SettingsContract.KEY_DEBUG_LOGGING)?.summary =
            getString(R.string.diagnostics_debug_logs_warning)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != Activity.RESULT_OK) return

        if (requestCode == EXPORT_CHARGING_DIAGNOSTICS_REQUEST) {
            val context = requireContext().applicationContext
            Thread {
                val success = runCatching {
                    val report = ChargingDiagnosticReport.create(
                        context, ChargingDiagnosticStore.read(context)
                    )
                    requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter()
                        .use { it.write(report) }
                }.isSuccess
                mainHandler.post {
                    if (!isAdded) return@post
                    requireContext().showToast(
                        if (success) R.string.diagnostics_exported
                        else R.string.diagnostics_export_failed, Toast.LENGTH_SHORT
                    )
                }
            }.apply { name = "charging-diagnostics-export" }.start()
            return
        }

        if (requestCode != EXPORT_DIAGNOSTICS_REQUEST) return

        Thread {
            val success = runCatching {
                DiagnosticsReport.write(
                    requireContext().applicationContext, uri, rootAvailable, shizukuReportStatus()
                )
            }.isSuccess
            mainHandler.post {
                if (!isAdded) return@post
                requireContext().showToast(
                    if (success) R.string.diagnostics_exported else R.string.diagnostics_export_failed,
                    Toast.LENGTH_SHORT
                )
            }
        }.apply { name = "diagnostics-export" }.start()
    }
}
