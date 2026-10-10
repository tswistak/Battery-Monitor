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
package codes.swistak.batterymonitor.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.app.LocaleManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.alarms.AlarmDatabase
import codes.swistak.batterymonitor.alarms.backup.AlarmBackup
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.app.PersistentFragment
import codes.swistak.batterymonitor.common.NotificationSettingsNavigator
import codes.swistak.batterymonitor.common.RootExecutor
import codes.swistak.batterymonitor.common.showToast
import codes.swistak.batterymonitor.devicebackup.CsvLogImporter
import codes.swistak.batterymonitor.devicebackup.DeviceDataBackup
import codes.swistak.batterymonitor.devicebackup.DeviceDataType
import codes.swistak.batterymonitor.devicebackup.GeneralBackup
import codes.swistak.batterymonitor.devicebackup.GeneralBackupArchive
import codes.swistak.batterymonitor.devicebackup.GeneralBackupDataType
import codes.swistak.batterymonitor.devicebackup.GeneralBackupRestoreException
import codes.swistak.batterymonitor.devicebackup.LogImportMode
import codes.swistak.batterymonitor.diagnostics.DebugLogCollector
import codes.swistak.batterymonitor.logs.AutoLogExportFrequency
import codes.swistak.batterymonitor.logs.AutoLogExportMode
import codes.swistak.batterymonitor.logs.AutoLogExportScheduler
import codes.swistak.batterymonitor.logs.AutoLogExportSetupAction
import codes.swistak.batterymonitor.logs.LogExportFormat
import codes.swistak.batterymonitor.logs.autoLogExportSetupAction
import codes.swistak.batterymonitor.monitoring.BackgroundServiceWatchdog
import codes.swistak.batterymonitor.monitoring.BatteryCurrent
import codes.swistak.batterymonitor.monitoring.BatteryCurrentMultiplierDetector
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import codes.swistak.batterymonitor.monitoring.PredictorStoredState
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingTargetResolver
import codes.swistak.batterymonitor.monitoring.charginglimit.DeviceChargingLimitProvider
import codes.swistak.batterymonitor.monitoring.charginglimit.ResolvedTarget
import codes.swistak.batterymonitor.monitoring.charginglimit.TargetSource
import codes.swistak.batterymonitor.privileged.PrivilegedAccess
import codes.swistak.batterymonitor.settings.backup.SettingsBackup
import codes.swistak.batterymonitor.ui.settings.SettingsDialog
import codes.swistak.batterymonitor.ui.settings.SettingsDialogs
import codes.swistak.batterymonitor.ui.settings.SettingsOrderItem
import codes.swistak.batterymonitor.ui.settings.SettingsScreen
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnBinderReceivedListener
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit


internal fun displayPathForDocumentId(documentId: String): String =
    documentId.removePrefix("primary:")

class SettingsFragment : Fragment(), OnSharedPreferenceChangeListener,
    PreferenceManager.OnPreferenceTreeClickListener {

    companion object {
        const val ARG_CATEGORY = "category"
        const val ARG_HIGHLIGHT = "highlight"
        private val PREFERENCE_SCREENS = listOf(
            R.xml.other_pref_screen,
            R.xml.current_state_pref_screen,
            R.xml.time_estimates_pref_screen,
            R.xml.notification_pref_screen,
            R.xml.advanced_pref_screen,
            R.xml.backup_restore_pref_screen
        )

        private const val EXPORT_REQUEST = 1
        private const val IMPORT_REQUEST = 2
        private const val EXPORT_ALARMS_REQUEST = 3
        private const val IMPORT_ALARMS_REQUEST = 4
        private const val EXPORT_DEVICE_DATA_REQUEST = 5
        private const val IMPORT_DEVICE_DATA_REQUEST = 6
        private const val IMPORT_LOGS_CSV_REQUEST = 7
        private const val EXPORT_GENERAL_BACKUP_REQUEST = 8
        private const val IMPORT_GENERAL_BACKUP_REQUEST = 9
        private const val AUTO_LOG_EXPORT_DIRECTORY_REQUEST = 10

        private const val STATE_DEVICE_DATA_EXPORT = "state_device_data_export"

        private const val BATTERY_CURRENT_MULTIPLIER_AUTODETECT_VALUE = "auto"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 7001

        private val PARENTS = arrayOf<String?>(
            SettingsContract.KEY_ENABLE_LOGGING,
            SettingsContract.KEY_RED,
            SettingsContract.KEY_AMBER,
            SettingsContract.KEY_GREEN
        )
        private val DEPENDENTS = arrayOf<Array<String?>?>(
            arrayOf(SettingsContract.KEY_MAX_LOG_AGE),
            arrayOf(SettingsContract.KEY_RED_THRESH),
            arrayOf(SettingsContract.KEY_AMBER_THRESH),
            arrayOf(SettingsContract.KEY_GREEN_THRESH)
        )

        private val INVERSE_PARENTS = arrayOf<String?>()
        private val INVERSE_DEPENDENTS = arrayOf<String?>()

        private val LIST_PREFS = arrayOf<String?>(
            SettingsContract.KEY_AUTOSTART,
            SettingsContract.KEY_STATUS_DUR_EST,
            SettingsContract.KEY_RED_THRESH,
            SettingsContract.KEY_AMBER_THRESH,
            SettingsContract.KEY_GREEN_THRESH,
            SettingsContract.KEY_ICON_CONTENT,
            SettingsContract.KEY_LIVE_UPDATE_DISPLAY,
            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER,
            SettingsContract.KEY_MAX_LOG_AGE,
            SettingsContract.KEY_TOP_LINE,
            SettingsContract.KEY_BOTTOM_LINE,
            SettingsContract.KEY_TIME_REMAINING_VERBOSITY,
            SettingsContract.KEY_PREDICTION_TYPE,
            SettingsContract.KEY_TEMPERATURE_UNIT,
            SettingsContract.KEY_LONG_DURATION_FORMAT
        )

        private val RESET_SERVICE = arrayOf<String?>(
            SettingsContract.KEY_DISMISS_LOW_BATTERY_ON_RECOVERY,
            SettingsContract.KEY_TEMPERATURE_UNIT,
            SettingsContract.KEY_NOTIFY_STATUS_DURATION,
            SettingsContract.KEY_RED,
            SettingsContract.KEY_RED_THRESH,
            SettingsContract.KEY_AMBER,
            SettingsContract.KEY_AMBER_THRESH,
            SettingsContract.KEY_GREEN,
            SettingsContract.KEY_GREEN_THRESH,
            SettingsContract.KEY_INDICATE_CHARGING,
            SettingsContract.KEY_SHOW_ICON_UNIT,
            SettingsContract.KEY_ICON_CONTENT,
            SettingsContract.KEY_CHIP_CONTENT,
            SettingsContract.KEY_CHIP_CONTENT_ORDER,
            SettingsContract.KEY_CHIP_SWITCHING_INTERVAL,
            SettingsContract.KEY_CHIP_INDICATE_CHARGING,
            SettingsContract.KEY_LIVE_UPDATE_DISPLAY,
            SettingsContract.KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION,
            SettingsContract.KEY_TOP_LINE,
            SettingsContract.KEY_BOTTOM_LINE,
            SettingsContract.KEY_ENABLE_LOGGING,
            SettingsContract.KEY_CHANGE_APP_LANGUAGE,
            SettingsContract.KEY_MAX_LOG_AGE,
            SettingsContract.KEY_TIME_REMAINING_VERBOSITY,
            SettingsContract.KEY_VITAL_SIGNS_CONTENT,
            SettingsContract.KEY_VITAL_SIGNS_ORDER,
            SettingsContract.KEY_EXPANDED_NOTIFICATION_DETAILS,
            SettingsContract.KEY_EXPANDED_LIVE_UPDATE_DETAILS,
            SettingsContract.KEY_USE_PRIVILEGED_ACCESS,
            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER,
            SettingsContract.KEY_UI_COLOR,
            SettingsContract.KEY_PREDICTION_TYPE,
            SettingsContract.KEY_CHARGING_TARGET_MODE,
            SettingsContract.KEY_CUSTOM_CHARGING_TARGET,
            SettingsContract.KEY_DISCHARGING_TARGET,
            SettingsContract.KEY_LONG_DURATION_FORMAT
        )
        private val RESET_SERVICE_WITH_CANCEL_NOTIFICATION = arrayOf<String?>()

        @SuppressLint("ApplySharedPref", "UseKtx")
        private fun reloadService(
            context: Context,
            settings: SharedPreferences,
            messenger: Messenger?,
            predictor: Boolean = false,
            cancelFirst: Boolean = false
        ) {
            if (!BackgroundServiceWatchdog.isServiceDesired(context)) return
            settings.edit().commit()
            val outgoing = Message.obtain().apply {
                what = when {
                    predictor -> BatteryInfoService.RemoteConnection.SERVICE_RELOAD_DEVICE_DATA
                    cancelFirst -> BatteryInfoService.RemoteConnection.SERVICE_CANCEL_NOTIFICATION_AND_RELOAD_SETTINGS
                    else -> BatteryInfoService.RemoteConnection.SERVICE_RELOAD_SETTINGS
                }
                data = SettingsSnapshot.capture(settings).apply {
                    if (predictor) putBundle(
                        BatteryInfoService.EXTRA_PREDICTOR_SNAPSHOT,
                        DeviceDataBackup.predictorSnapshot(context)
                    )
                }
            }
            try {
                requireNotNull(messenger).send(outgoing)
            } catch (e: Exception) {
                BatteryInfoService.startForegroundServiceSafely(context, outgoing.data)
            }
        }

        private fun reloadRestoredData(
            context: Context,
            settings: SharedPreferences,
            messenger: Messenger?,
            changed: Set<GeneralBackupDataType>
        ) {
            when {
                GeneralBackupDataType.PREDICTOR_DATA in changed -> reloadService(
                    context, settings, messenger, predictor = true
                )

                GeneralBackupDataType.SETTINGS in changed || GeneralBackupDataType.ALARMS in changed -> reloadService(
                    context, settings, messenger
                )
            }
        }


    }

    private var serviceMessenger: Messenger? = null
    private val messenger = Messenger(MessageHandler(this))
    private val serviceConnection = BatteryInfoService.RemoteConnection(messenger)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var res: Resources
    private var mPreferenceScreen: PreferenceScreen? = null
    private lateinit var mSharedPreferences: SharedPreferences
    private var mNotificationManager: NotificationManager? = null
    private var mainChan: NotificationChannel? = null
    private var appNotifsEnabled = false
    private var mainNotifsEnabled = false
    private var systemPromotedEnabled = false

    private lateinit var preferenceManager: PreferenceManager
    private var modelVersion by mutableIntStateOf(0)
    private var selectedCategory by mutableStateOf<String?>(null)
    private var highlightedKey by mutableStateOf<String?>(null)
    private var dialog by mutableStateOf<SettingsDialog?>(null)
    private lateinit var operationModel: SettingsOperationViewModel
    private val busy: Boolean get() = operationModel.busy
    private var serviceBound = false
    private var pendingDocumentRequest = 0
    private lateinit var applicationContext: Context
    private var timeEstimateJob: Job? = null
    private var chargingLimitProvider: DeviceChargingLimitProvider? = null
    private var chargingLimitCapabilityVersion = 0
    private var chargingLimitProviderVersion = -1
    private var chargingLimitProviderPrivilegedEnabled: Boolean? = null
    private val chargingLimitProviderLock = Any()
    private var batteryCurrentMultiplierDetectionRunning = false
    private var applyingDetectedBatteryCurrentMultiplier = false
    private var pendingPrivilegedShizukuBinderListener: OnBinderReceivedListener? = null

    private var privilegedAccessRequestInProgress = false
    private val privilegedShizukuPermissionListener =
        OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE || !privilegedAccessRequestInProgress) return@OnRequestPermissionResultListener

            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                mainHandler.post(::completePrivilegedAccessEnable)
            } else {
                privilegedAccessRequestInProgress = false
            }
        }

    private var pendingDeviceDataExport: Set<DeviceDataType> = emptySet()

    private class MessageHandler(private val sa: SettingsFragment) :
        Handler(Looper.getMainLooper()) {
        override fun handleMessage(incoming: Message) {
            when (incoming.what) {
                BatteryInfoService.RemoteConnection.CLIENT_SERVICE_CONNECTED -> {
                    sa.serviceMessenger = incoming.replyTo
                    sa.resetService()
                }

                else -> super.handleMessage(incoming)
            }
        }
    }

    fun showCategory(category: String?, highlight: String? = null) {
        selectedCategory = category
        highlightedKey = highlight
    }

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        res = resources
        applicationContext = requireContext().applicationContext
        operationModel = ViewModelProvider(this)[SettingsOperationViewModel::class.java]
        pendingDocumentRequest = savedInstanceState?.getInt("document_request") ?: 0
        selectedCategory =
            savedInstanceState?.getString(ARG_CATEGORY) ?: arguments?.getString(ARG_CATEGORY)
                    ?: selectedCategory
        highlightedKey =
            savedInstanceState?.getString(ARG_HIGHLIGHT) ?: arguments?.getString(ARG_HIGHLIGHT)
        preferenceManager = PreferenceManager(requireContext()).apply {
            sharedPreferencesName = SettingsContract.SETTINGS_FILE
            sharedPreferencesMode = Context.MODE_PRIVATE
            onPreferenceTreeClickListener = this@SettingsFragment
        }
        mSharedPreferences = requireNotNull(preferenceManager.sharedPreferences)
        BatteryCurrent.setContext(requireContext())
        PrivilegedAccess.initialize(requireContext())
        PrivilegedAccess.setEnabled(
            mSharedPreferences.getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false)
        )
        PrivilegedAccess.setReadyListener {
            mainHandler.post {
                if (isAdded) {
                    chargingLimitCapabilityVersion++
                    setupTimeEstimatePreferences()
                    modelVersion++
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(privilegedShizukuPermissionListener)
        pendingDeviceDataExport =
            savedInstanceState?.getStringArray(STATE_DEVICE_DATA_EXPORT)?.mapNotNull { name ->
                runCatching { DeviceDataType.valueOf(name) }.getOrNull()
            }?.toSet().orEmpty()
        setPreferences()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AppBatteryTheme {
                SettingsScreen(
                    preferences = requireNotNull(mPreferenceScreen),
                    version = modelVersion,
                    initialCategory = selectedCategory,
                    highlightKey = highlightedKey,
                    onActivate = ::activatePreference,
                    onNavigateCategory = { category, highlight ->
                        (activity as? BatteryInfoActivity)?.openSettingsCategory(
                            category, highlight
                        )
                    },
                    onDiagnostics = ::openDiagnostics
                )
                LaunchedEffect(operationModel.outcome) {
                    if (isResumed) operationModel.takeOutcome()?.let(::handleOperationOutcome)
                }
                SettingsDialogs(dialog, busy) { dialog = null }
            }
        }
    }

    private fun openDiagnostics(route: String, highlight: String?) {
        (activity as? BatteryInfoActivity)?.openDiagnosticDetail(route, highlight)
    }

    private fun activatePreference(preference: Preference) {
        if (busy || pendingDocumentRequest != 0 || !preference.isEnabled) return
        if (preference is ListPreference) {
            dialog = SettingsDialog.Choices(
                title = (preference.dialogTitle ?: preference.title).toString(),
                message = preference.dialogMessage?.toString(),
                options = preference.entries.map(CharSequence::toString),
                selected = setOf(preference.findIndexOfValue(preference.value)),
                multiple = false,
                onConfirm = { selected ->
                    val value = preference.entryValues[selected.single()].toString()
                    if (preference.callChangeListener(value)) preference.value = value
                    modelVersion++
                })
        } else if (preference.key == SettingsContract.KEY_ENABLE_NOTIFS_B) {
            enableNotifsButtonClick()
        } else if (preference.key == "live_update_system_settings") {
            if (!NotificationSettingsNavigator.openLiveUpdates(requireContext())) {
                requireContext().showToast(R.string.advanced_value_not_available)
            }
        } else if (preference.key == SettingsContract.KEY_CHANGE_APP_LANGUAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launchChangeAppLanguageIntent()
            }
        } else {
            onPreferenceTreeClick(preference)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("document_request", pendingDocumentRequest)
        outState.putString(ARG_CATEGORY, selectedCategory)
        outState.putString(ARG_HIGHLIGHT, highlightedKey)
        outState.putStringArray(
            STATE_DEVICE_DATA_EXPORT, pendingDeviceDataExport.map { it.name }.toTypedArray()
        )
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()

        if (mNotificationManager == null) mNotificationManager = requireActivity().getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager?

        val currentAppNotifsEnabled = mNotificationManager!!.areNotificationsEnabled()
        val currentMainNotifsEnabled = getMainNotifsEnabled()
        val currentLiveUpdateEnabledInSystem: Boolean =
            BatteryInfoService.isLiveUpdateEnabledInSystem(requireContext())

        if (appNotifsEnabled != currentAppNotifsEnabled || mainNotifsEnabled != currentMainNotifsEnabled || systemPromotedEnabled != currentLiveUpdateEnabledInSystem) { // Doesn't seem worth checking which screen
            resetService()
        }

        setPreferences()
        syncPrivilegedAccessPreference()
        modelVersion++
        operationModel.takeOutcome()?.let(::handleOperationOutcome)
        if (!busy) mSharedPreferences.registerOnSharedPreferenceChangeListener(this)
        if (!serviceBound) {
            serviceBound = requireContext().bindService(
                Intent(requireContext(), BatteryInfoService::class.java), serviceConnection, 0
            )
        }
    }

    override fun onPause() {
        super.onPause()

        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        timeEstimateJob?.cancel()
        timeEstimateJob = null
        if (serviceBound) {
            requireContext().unbindService(serviceConnection)
            serviceBound = false
            serviceMessenger = null
        }
    }

    override fun onDestroy() {
        pendingPrivilegedShizukuBinderListener?.let(Shizuku::removeBinderReceivedListener)
        pendingPrivilegedShizukuBinderListener = null
        privilegedAccessRequestInProgress = false
        Shizuku.removeRequestPermissionResultListener(privilegedShizukuPermissionListener)
        PrivilegedAccess.setReadyListener(null)
        super.onDestroy()
    }

    private fun resetService(cancelFirst: Boolean = false) {
        reloadService(
            applicationContext, mSharedPreferences, serviceMessenger, cancelFirst = cancelFirst
        )
    }

    @SuppressLint("RestrictedApi")
    private fun setPreferences(reinflate: Boolean = false) {
        mNotificationManager = requireContext().getSystemService(NotificationManager::class.java)
        appNotifsEnabled = mNotificationManager!!.areNotificationsEnabled()
        mainNotifsEnabled = getMainNotifsEnabled()
        systemPromotedEnabled = BatteryInfoService.isLiveUpdateEnabledInSystem(requireContext())
        if (mPreferenceScreen == null || reinflate) {
            prepareBatteryCurrentMultiplierDetection()
            var screen: PreferenceScreen? = null
            PREFERENCE_SCREENS.forEach { xml ->
                screen = preferenceManager.inflateFromResource(requireContext(), xml, screen)
            }
            mPreferenceScreen = requireNotNull(screen)
            preferenceManager.setPreferences(screen)
        }
        syncPreferenceValues(requireNotNull(mPreferenceScreen))
        mPreferenceScreen?.findPreference<Preference>(SettingsContract.KEY_ENABLE_NOTIFS_B)?.apply {
            setTitle(R.string.pref_manage_main_channel)
            setSummary(
                if (!appNotifsEnabled) R.string.app_notifs_disabled_summary
                else if (!mainNotifsEnabled) R.string.main_notifs_disabled_summary
                else R.string.pref_manage_main_channel
            )
            isSelectable = true
        }
        BatteryCurrent.setMultiplier(
            mSharedPreferences.getString(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "1")
                ?.toIntOrNull() ?: 1
        )
        setupBatteryCurrentMultiplierPreference()
        setupChipSwitchingIntervalPreference()
        updateChipIntervalVisibility()
        syncPrivilegedAccessPreference()
        setupTimeEstimatePreferences()
        setupPrivilegedAccessPreference()
        if (reinflate) {
            capAutoLogExportFrequencyToRetention()
            AutoLogExportScheduler.ensureScheduled(applicationContext)
        }
        setupAutoLogExportPreference()
        PARENTS.indices.forEach(::setEnablednessOfDeps)
        INVERSE_PARENTS.indices.forEach(::setEnablednessOfInverseDeps)
        LIST_PREFS.filterNotNull().forEach(::updateListPrefSummary)
        setupLanguage()
        maybeDetectBatteryCurrentMultiplier()
        modelVersion++
    }

    private fun syncPreferenceValues(group: PreferenceGroup) {
        for (index in 0 until group.preferenceCount) {
            when (val preference = group.getPreference(index)) {
                is PreferenceGroup -> syncPreferenceValues(preference)
                is CheckBoxPreference -> preference.isChecked =
                    mSharedPreferences.getBoolean(preference.key, preference.isChecked)

                is ListPreference -> preference.value =
                    mSharedPreferences.getString(preference.key, preference.value)

                is SeekBarPreference -> preference.value =
                    mSharedPreferences.getInt(preference.key, preference.value)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            null -> {
                return false
            }

            "reset_default_settings" -> {
                dialog = SettingsDialog.Confirm(
                    message = getString(R.string.settings_reset_question),
                    title = getString(R.string.settings_reset_title),
                    confirmLabel = R.string.settings_reset_confirm,
                    onConfirm = ::resetSettingsToDefaults
                )
                return true
            }

            SettingsContract.KEY_EXPORT_SETTINGS -> {
                val ts = SimpleDateFormat(
                    "yyyy-MM-dd-HHmmss-SSS", Locale.getDefault()
                ).format(Date())
                val exportIntent =
                    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "battery_monitor_settings_" + ts + ".json")
                launchDocument(exportIntent, EXPORT_REQUEST)
                return true
            }

            SettingsContract.KEY_IMPORT_SETTINGS -> {
                val importIntent =
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                launchDocument(importIntent, IMPORT_REQUEST)
                return true
            }

            SettingsContract.KEY_EXPORT_ALARMS -> {
                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd-HHmmss-SSS", Locale.getDefault()
                ).format(Date())
                val exportIntent =
                    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "battery_monitor_alarms_$timestamp.json")
                launchDocument(exportIntent, EXPORT_ALARMS_REQUEST)
                return true
            }

            SettingsContract.KEY_IMPORT_ALARMS -> {
                val importIntent =
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                launchDocument(importIntent, IMPORT_ALARMS_REQUEST)
                return true
            }

            SettingsContract.KEY_EXPORT_DEVICE_DATA -> {
                showDeviceDataExportDialog()
                return true
            }

            SettingsContract.KEY_IMPORT_DEVICE_DATA -> {
                val importIntent =
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                launchDocument(importIntent, IMPORT_DEVICE_DATA_REQUEST)
                return true
            }

            SettingsContract.KEY_IMPORT_LOGS_CSV -> {
                openCsvLogFilePicker()
                return true
            }

            SettingsContract.KEY_EXPORT_GENERAL_BACKUP -> {
                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd-HHmmss-SSS", Locale.getDefault()
                ).format(Date())
                val exportIntent =
                    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/zip").putExtra(
                            Intent.EXTRA_TITLE, "battery_monitor_general_backup_$timestamp.zip"
                        )
                launchDocument(exportIntent, EXPORT_GENERAL_BACKUP_REQUEST)
                return true
            }

            SettingsContract.KEY_IMPORT_GENERAL_BACKUP -> {
                val importIntent =
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/zip")
                launchDocument(importIntent, IMPORT_GENERAL_BACKUP_REQUEST)
                return true
            }

            SettingsContract.KEY_AUTO_LOG_EXPORT -> {
                if (isAutoLogExportConfigured()) {
                    showAutoLogExportDialog(currentAutoLogExportDirectory())
                } else {
                    openAutoLogExportDirectoryPicker()
                }
                return true
            }

            SettingsContract.KEY_VITAL_SIGNS_CONTENT -> {
                showVitalSignsDialog(preference)
                return true
            }

            SettingsContract.KEY_CHIP_CONTENT -> {
                showChipContentDialog(preference)
                return true
            }

            else -> return false
        }
    }

    private fun showVitalSignsDialog(preference: Preference) = showOrderedContentDialog(
        preference,
        SettingsContract.KEY_VITAL_SIGNS_CONTENT,
        SettingsContract.KEY_VITAL_SIGNS_ORDER,
        SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT,
        R.array.vital_signs_content_values,
        R.array.vital_signs_content_entries,
        VitalSignsOrder.parse(
            mSharedPreferences.getString(
                SettingsContract.KEY_VITAL_SIGNS_ORDER, null
            )
        ),
        allowEmpty = true,
        serialize = VitalSignsOrder::serialize
    )

    private fun showChipContentDialog(preference: Preference) = showOrderedContentDialog(
        preference,
        SettingsContract.KEY_CHIP_CONTENT,
        SettingsContract.KEY_CHIP_CONTENT_ORDER,
        SettingsContract.DEFAULT_CHIP_CONTENT,
        R.array.chip_content_values,
        R.array.chip_content_entries,
        ChipContentOrder.parse(
            mSharedPreferences.getString(
                SettingsContract.KEY_CHIP_CONTENT_ORDER, null
            )
        ),
        allowEmpty = false,
        serialize = ChipContentOrder::serialize
    )

    private fun showOrderedContentDialog(
        preference: Preference,
        contentKey: String,
        orderKey: String,
        defaults: Set<String>,
        valuesResource: Int,
        labelsResource: Int,
        order: List<String>,
        allowEmpty: Boolean,
        serialize: (List<String>) -> String
    ) {
        val selected = mSharedPreferences.getStringSet(contentKey, defaults) ?: defaults
        val labels =
            resources.getStringArray(valuesResource).zip(resources.getStringArray(labelsResource))
                .toMap()
        dialog = SettingsDialog.Ordered(
            title = preference.title.toString(),
            message = preference.summary?.toString(),
            items = order.mapNotNull { value ->
                labels[value]?.let {
                    SettingsOrderItem(value, it, value in selected)
                }
            },
            allowEmpty = allowEmpty,
            onConfirm = { items ->
                mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
                try {
                    mSharedPreferences.edit {
                        putStringSet(
                            contentKey,
                            items.filter { it.selected }.mapTo(linkedSetOf()) { it.value })
                        putString(orderKey, serialize(items.map { it.value }))
                    }
                } finally {
                    if (isResumed) mSharedPreferences.registerOnSharedPreferenceChangeListener(this)
                }
                updateChipIntervalVisibility()
                resetService()
                modelVersion++
            })
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == null) return
        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        syncPreferenceValues(requireNotNull(mPreferenceScreen))

        if (key == SettingsContract.KEY_CHIP_CONTENT || key == SettingsContract.KEY_CHIP_CONTENT_ORDER) {
            updateChipIntervalVisibility()
        }

        if (key == SettingsContract.KEY_MAX_LOG_AGE) {
            capAutoLogExportFrequencyToRetention()
        }

        if (key == SettingsContract.KEY_ENABLE_LOGGING) {
            setupAutoLogExportPreference()
            if (mSharedPreferences.getBoolean(SettingsContract.KEY_ENABLE_LOGGING, true)) {
                AutoLogExportScheduler.ensureScheduled(requireContext())
            } else {
                AutoLogExportScheduler.cancel(requireContext())
            }
        }

        if (key == SettingsContract.KEY_LIVE_UPDATE_DISPLAY) {
            updateChipIntervalVisibility()
        }

        for (i in PARENTS.indices) {
            if (key == PARENTS[i]) {
                setEnablednessOfDeps(i)
                break
            }
        }

        for (i in INVERSE_PARENTS.indices) {
            if (key == INVERSE_PARENTS[i]) {
                setEnablednessOfInverseDeps(i)
                break
            }
        }

        for (i in LIST_PREFS.indices) {
            if (key == LIST_PREFS[i]) {
                updateListPrefSummary(LIST_PREFS[i]!!)
                break
            }
        }

        if (key == SettingsContract.KEY_CHARGING_TARGET_MODE || key == SettingsContract.KEY_CUSTOM_CHARGING_TARGET || key == SettingsContract.KEY_DISCHARGING_TARGET) {
            setupTimeEstimatePreferences()
        }

        if (key == SettingsContract.KEY_USE_PRIVILEGED_ACCESS) {
            val enabled = mSharedPreferences.getBoolean(
                SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false
            )
            PrivilegedAccess.setEnabled(enabled)
            chargingLimitCapabilityVersion++
            setupTimeEstimatePreferences()
        }

        if (key == SettingsContract.KEY_CHIP_SWITCHING_INTERVAL) {
            updateChipSwitchingIntervalSummary()
        }

        for (i in RESET_SERVICE.indices) {
            if (key == RESET_SERVICE[i]) {
                if (!(key == SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER && applyingDetectedBatteryCurrentMultiplier)) {
                    resetService()
                }
                break
            }
        }

        for (i in RESET_SERVICE_WITH_CANCEL_NOTIFICATION.indices) {
            if (key == RESET_SERVICE_WITH_CANCEL_NOTIFICATION[i]) {
                resetService(true)
                break
            }
        }

        mSharedPreferences.registerOnSharedPreferenceChangeListener(this)
        setupLanguage()
        modelVersion++
    }

    private fun setupPrivilegedAccessPreference() {
        val preference = mPreferenceScreen?.findPreference<CheckBoxPreference>(
            SettingsContract.KEY_USE_PRIVILEGED_ACCESS
        ) ?: return
        preference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
            val enable = value as? Boolean ?: return@OnPreferenceChangeListener false
            if (!enable) {
                privilegedAccessRequestInProgress = false
                pendingPrivilegedShizukuBinderListener?.let(
                    Shizuku::removeBinderReceivedListener
                )
                pendingPrivilegedShizukuBinderListener = null
                return@OnPreferenceChangeListener true
            }

            requestPrivilegedAccessEnable()
            false
        }
    }

    private fun requestPrivilegedAccessEnable() {
        if (privilegedAccessRequestInProgress) return
        privilegedAccessRequestInProgress = true

        Thread {
            val rootGranted = RootExecutor().run("id") != null
            mainHandler.post {
                if (!privilegedAccessRequestInProgress || !isAdded) return@post
                if (rootGranted) {
                    completePrivilegedAccessEnable()
                } else {
                    requestPrivilegedShizukuPermission()
                }
            }
        }.start()
    }

    private fun requestPrivilegedShizukuPermission() {
        if (!isAdded || activity == null) {
            privilegedAccessRequestInProgress = false
            return
        }

        if (!runCatching(Shizuku::pingBinder).getOrDefault(false)) {
            Toast.makeText(requireContext(), R.string.shizuku_not_running, Toast.LENGTH_LONG).show()
            if (pendingPrivilegedShizukuBinderListener != null) return
            val listener = object : OnBinderReceivedListener {
                override fun onBinderReceived() {
                    mainHandler.post {
                        Shizuku.removeBinderReceivedListener(this)
                        if (pendingPrivilegedShizukuBinderListener === this) {
                            pendingPrivilegedShizukuBinderListener = null
                        }
                        if (privilegedAccessRequestInProgress) {
                            requestPrivilegedShizukuPermission()
                        }
                    }
                }
            }
            pendingPrivilegedShizukuBinderListener = listener
            Shizuku.addBinderReceivedListenerSticky(listener)
            return
        }

        if (runCatching(Shizuku::isPreV11).getOrDefault(true)) {
            privilegedAccessRequestInProgress = false
            return
        }
        if (runCatching(Shizuku::checkSelfPermission).getOrDefault(
                PackageManager.PERMISSION_DENIED
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            completePrivilegedAccessEnable()
            return
        }
        if (runCatching(Shizuku::shouldShowRequestPermissionRationale).getOrDefault(true)) {
            Toast.makeText(
                requireContext(), R.string.shizuku_permission_denied_permanently, Toast.LENGTH_LONG
            ).show()
            privilegedAccessRequestInProgress = false
            return
        }

        runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE) }.onFailure {
            privilegedAccessRequestInProgress = false
        }
    }

    private fun completePrivilegedAccessEnable() {
        if (!privilegedAccessRequestInProgress || !isAdded) return
        privilegedAccessRequestInProgress = false
        pendingPrivilegedShizukuBinderListener?.let(Shizuku::removeBinderReceivedListener)
        pendingPrivilegedShizukuBinderListener = null
        mSharedPreferences.edit {
            putBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, true)
        }
        syncPrivilegedAccessPreference()
    }

    private fun syncPrivilegedAccessPreference() {
        val enabled = mSharedPreferences.getBoolean(
            SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false
        )
        mPreferenceScreen?.findPreference<CheckBoxPreference>(
            SettingsContract.KEY_USE_PRIVILEGED_ACCESS
        )?.isChecked = enabled
        PrivilegedAccess.setEnabled(enabled)
    }

    private fun setEnablednessOfDeps(index: Int) {
        for (i in DEPENDENTS[index]!!.indices) {
            val dependent =
                mPreferenceScreen!!.findPreference<Preference?>(DEPENDENTS[index]!![i]!!) ?: return

            dependent.isEnabled = mSharedPreferences.getBoolean(PARENTS[index], false)

            updateListPrefSummary(DEPENDENTS[index]!![i]!!)
        }
    }

    private fun prepareBatteryCurrentMultiplierDetection() {
        if (mSharedPreferences.contains(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER)) return
        if (mSharedPreferences.getBoolean(
                SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING, false
            )
        ) return

        mSharedPreferences.edit {
            putBoolean(
                SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING, true
            )
        }
    }

    private fun setupBatteryCurrentMultiplierPreference() {
        val preference = mPreferenceScreen!!.findPreference<ListPreference>(
            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER
        ) ?: return
        preference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                if (newValue == BATTERY_CURRENT_MULTIPLIER_AUTODETECT_VALUE) {
                    requestBatteryCurrentMultiplierDetection()
                    false
                } else {
                    mSharedPreferences.edit {
                        remove(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING)
                    }
                    true
                }
            }
    }

    private fun requestBatteryCurrentMultiplierDetection() {
        mSharedPreferences.edit {
            putBoolean(
                SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING, true
            )
        }
        maybeDetectBatteryCurrentMultiplier(showFailureMessage = true)
    }

    @Suppress("DEPRECATION")
    private fun maybeDetectBatteryCurrentMultiplier(showFailureMessage: Boolean = false) {
        if (batteryCurrentMultiplierDetectionRunning || !mSharedPreferences.getBoolean(
                SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING, false
            )
        ) return

        val batteryIntent = requireContext().registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        if (batteryIntent == null) {
            if (showFailureMessage) {
                Toast.makeText(
                    activity,
                    R.string.pref_battery_current_multiplier_detection_unavailable,
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        val batteryInfo = BatteryInfo().apply { load(batteryIntent) }
        batteryCurrentMultiplierDetectionRunning = true

        Thread {
            BatteryCurrent.readForMultiplierDetection(average = false) { rawCurrent ->
                val detectedMultiplier = rawCurrent?.let {
                    BatteryCurrentMultiplierDetector.detect(
                        milliAmpsAtMultiplierOne = it,
                        batteryStatus = batteryInfo.status,
                        batteryPercent = batteryInfo.percent
                    )
                }

                mainHandler.post {
                    batteryCurrentMultiplierDetectionRunning = false
                    if (!isAdded) return@post
                    if (detectedMultiplier == null) {
                        if (showFailureMessage) {
                            Toast.makeText(
                                activity,
                                R.string.pref_battery_current_multiplier_detection_unavailable,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        return@post
                    }
                    if (!mSharedPreferences.getBoolean(
                            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING, false
                        )
                    ) return@post

                    BatteryCurrent.setMultiplier(detectedMultiplier)
                    applyingDetectedBatteryCurrentMultiplier = true
                    try {
                        mSharedPreferences.edit {
                            putString(
                                SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER,
                                detectedMultiplier.toString()
                            )
                            remove(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING)
                        }
                    } finally {
                        applyingDetectedBatteryCurrentMultiplier = false
                    }
                    mPreferenceScreen?.findPreference<ListPreference>(
                        SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER
                    )?.value = detectedMultiplier.toString()
                    updateListPrefSummary(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER)
                    Toast.makeText(
                        activity, getString(
                            R.string.pref_battery_current_multiplier_detection_result,
                            detectedMultiplier
                        ), Toast.LENGTH_SHORT
                    ).show()
                    resetService()
                }
            }
        }.apply { name = "battery-current-multiplier-detection" }.start()
    }

    private fun showIntervalDialog(
        preference: ListPreference, fallback: String, message: Int, error: Int
    ) {
        dialog = SettingsDialog.Number(
            title = preference.title.toString(),
            message = getString(message),
            initial = mSharedPreferences.getString(preference.key, fallback)?.toIntOrNull()
                ?: fallback.toInt(),
            min = 1,
            max = 3600,
            error = getString(error),
            onConfirm = { seconds ->
                preference.value = seconds.toString()
                updateChipSwitchingIntervalSummary()
                modelVersion++
            })
    }

    private fun setupChipSwitchingIntervalPreference() {
        val preference = mPreferenceScreen!!.findPreference<ListPreference>(
            SettingsContract.KEY_CHIP_SWITCHING_INTERVAL
        ) ?: return
        updateChipSwitchingIntervalSummary()
        preference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                if (newValue == "custom") {
                    showCustomChipSwitchingIntervalDialog(preference)
                    false
                } else {
                    true
                }
            }
    }

    private fun updateChipSwitchingIntervalSummary() {
        val preference = mPreferenceScreen!!.findPreference<ListPreference>(
            SettingsContract.KEY_CHIP_SWITCHING_INTERVAL
        ) ?: return
        val seconds = mSharedPreferences.getString(
            SettingsContract.KEY_CHIP_SWITCHING_INTERVAL, "5"
        )?.toIntOrNull()?.coerceIn(1, 3600) ?: 5
        val entry = preference.entries.getOrNull(
            preference.findIndexOfValue(seconds.toString())
        )
        val value = entry ?: getString(
            R.string.pref_chip_switching_interval_custom_summary, seconds
        )
        preference.summary = getString(R.string.currently_set_to) + value
    }

    private fun showCustomChipSwitchingIntervalDialog(preference: ListPreference) {
        showIntervalDialog(
            preference,
            "5",
            R.string.pref_chip_switching_interval_custom_message,
            R.string.pref_chip_switching_interval_error
        )
    }

    private fun setEnablednessOfInverseDeps(index: Int) {
        val dependent =
            mPreferenceScreen!!.findPreference<Preference?>(INVERSE_DEPENDENTS[index]!!) ?: return

        dependent.isEnabled = !mSharedPreferences.getBoolean(INVERSE_PARENTS[index], false)

        updateListPrefSummary(INVERSE_DEPENDENTS[index]!!)
    }

    private fun updateChipIntervalVisibility() {
        val p =
            mPreferenceScreen!!.findPreference<Preference?>(SettingsContract.KEY_CHIP_SWITCHING_INTERVAL)
                ?: return

        val isSwitching = mSharedPreferences.getStringSet(
            SettingsContract.KEY_CHIP_CONTENT, SettingsContract.DEFAULT_CHIP_CONTENT
        )?.size?.let { it > 1 } == true
        val liveUpdatesDisabled = "never" == mSharedPreferences.getString(
            SettingsContract.KEY_LIVE_UPDATE_DISPLAY,
            res.getString(R.string.default_live_update_display_mode)
        )
        p.isVisible = isSwitching && !liveUpdatesDisabled
    }

    private fun updateListPrefSummary(key: String) {
        val pref: ListPreference?
        try {
            pref = mPreferenceScreen!!.findPreference<Preference?>(key) as ListPreference?
        } catch (e: ClassCastException) {
            return
        }

        if (pref == null) return

        if (pref.isEnabled) {
            pref.setSummary(res.getString(R.string.currently_set_to) + pref.getEntry())
        } else {
            pref.setSummary(res.getString(R.string.currently_disabled))
        }
    }

    private fun setupTimeEstimatePreferences() {
        val screen = mPreferenceScreen ?: return
        val modePreference = screen.findPreference<ListPreference>(
            SettingsContract.KEY_CHARGING_TARGET_MODE
        ) ?: return
        val customPreference = screen.findPreference<SeekBarPreference>(
            SettingsContract.KEY_CUSTOM_CHARGING_TARGET
        ) ?: return
        val dischargingPreference = screen.findPreference<SeekBarPreference>(
            SettingsContract.KEY_DISCHARGING_TARGET
        ) ?: return

        val customMode = mSharedPreferences.getString(
            SettingsContract.KEY_CHARGING_TARGET_MODE,
            SettingsContract.CHARGING_TARGET_MODE_AUTOMATIC
        ) == SettingsContract.CHARGING_TARGET_MODE_CUSTOM
        customPreference.isVisible = customMode
        customPreference.isEnabled = customMode

        val preferences = mSharedPreferences
        customPreference.summary = getString(
            R.string.pref_target_level_summary, preferences.getInt(
                SettingsContract.KEY_CUSTOM_CHARGING_TARGET,
                SettingsContract.DEFAULT_CUSTOM_CHARGING_TARGET
            ).coerceIn(1, 100)
        )
        dischargingPreference.summary = getString(
            R.string.pref_target_level_summary, preferences.getInt(
                SettingsContract.KEY_DISCHARGING_TARGET, SettingsContract.DEFAULT_DISCHARGING_TARGET
            ).coerceIn(0, 99)
        )
        timeEstimateJob?.cancel()
        val context = applicationContext
        val capabilityVersion = chargingLimitCapabilityVersion
        val privilegedEnabled =
            preferences.getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false)
        timeEstimateJob = lifecycleScope.launch {
            val target = withContext(Dispatchers.IO) {
                runCatching {
                    val provider = synchronized(chargingLimitProviderLock) {
                        if (chargingLimitProviderVersion != capabilityVersion || chargingLimitProviderPrivilegedEnabled != privilegedEnabled || chargingLimitProvider == null) {
                            chargingLimitProvider = DeviceChargingLimitProvider(
                                context, privilegedAccessEnabled = { privilegedEnabled })
                            chargingLimitProviderVersion = capabilityVersion
                            chargingLimitProviderPrivilegedEnabled = privilegedEnabled
                        }
                        requireNotNull(chargingLimitProvider)
                    }
                    ChargingTargetResolver(preferences, provider).resolveChargingTarget()
                }.getOrDefault(ResolvedTarget(100, TargetSource.DEFAULT))
            }
            if (!isAdded) return@launch
            val targetSummary = when (target.source) {
                TargetSource.DEVICE -> getString(
                    R.string.pref_charging_target_mode_summary_device, target.percent
                )

                TargetSource.CUSTOM -> getString(R.string.pref_charging_target_mode_summary_custom)
                TargetSource.DEFAULT -> getString(R.string.pref_charging_target_mode_summary_default)
            }
            val summary = if (customMode) targetSummary else getString(
                R.string.pref_charging_target_mode_summary_automatic_help, targetSummary
            )
            modePreference.summaryProvider = Preference.SummaryProvider<ListPreference> { summary }
            modelVersion++
        }
    }

    @Suppress("DEPRECATION")
    private fun launchDocument(intent: Intent, requestCode: Int) {
        if (pendingDocumentRequest != 0 || busy) return
        runCatching {
            pendingDocumentRequest = requestCode
            startActivityForResult(intent, requestCode)
        }.onFailure {
            pendingDocumentRequest = 0
            dialog = SettingsDialog.Message(getString(R.string.advanced_value_not_available))
        }
    }

    private fun fileOperation(
        action: SettingsOperationAction, error: Int, operation: (Context) -> Any?
    ) {
        operationModel.start(action, error, operation)
    }

    private fun handleOperationOutcome(outcome: SettingsOperationOutcome) {
        try {
            outcome.result.fold(onSuccess = { value ->
                when (outcome.action) {
                    SettingsOperationAction.ReadSettings -> {
                        val preview = value as JsonBackupImportPreview
                        if (preview.version > SettingsBackup.SCHEMA_VERSION) {
                            dialog =
                                SettingsDialog.Confirm(getString(R.string.settings_file_version_warning)) {
                                    doImport(preview.json)
                                }
                        } else doImport(preview.json)
                    }

                    SettingsOperationAction.ReadAlarms -> {
                        val preview = value as JsonBackupImportPreview
                        if (preview.version > AlarmBackup.SCHEMA_VERSION) {
                            dialog =
                                SettingsDialog.Confirm(getString(R.string.settings_file_version_warning)) {
                                    doAlarmImport(preview.json)
                                }
                        } else doAlarmImport(preview.json)
                    }

                    SettingsOperationAction.ReadDeviceData -> showDeviceDataImportDialog(value as DeviceBackupImportPreview)
                    SettingsOperationAction.ReadCsvLogs -> showCsvLogImportModeDialog(value as String)
                    SettingsOperationAction.ReadGeneralBackup -> showGeneralBackupImportDialog(value as GeneralBackupImportPreview)
                    else -> {
                        if (outcome.action == SettingsOperationAction.ImportSettings || outcome.action == SettingsOperationAction.ImportGeneralBackup || outcome.action == SettingsOperationAction.ResetSettings) setPreferences(
                            reinflate = true
                        )
                        if (outcome.action == SettingsOperationAction.ExportDeviceData) pendingDeviceDataExport =
                            emptySet()
                        val message = when (outcome.action) {
                            SettingsOperationAction.ResetSettings -> R.string.settings_reset_done
                            SettingsOperationAction.ExportSettings -> R.string.settings_exported
                            SettingsOperationAction.ImportSettings -> R.string.settings_imported
                            SettingsOperationAction.ExportAlarms -> R.string.alarms_exported
                            SettingsOperationAction.ImportAlarms -> R.string.alarms_imported
                            SettingsOperationAction.ExportDeviceData -> R.string.device_data_exported
                            SettingsOperationAction.ImportDeviceData -> R.string.device_data_imported
                            SettingsOperationAction.ImportCsvLogs -> R.string.csv_logs_imported
                            SettingsOperationAction.ExportGeneralBackup -> R.string.general_backup_exported
                            else -> R.string.general_backup_imported
                        }
                        dialog = SettingsDialog.Message(getString(message))
                    }
                }
            }, onFailure = { failure ->
                if (outcome.action == SettingsOperationAction.ResetSettings) setPreferences(
                    reinflate = true
                )
                dialog = SettingsDialog.Message(backupFailureMessage(outcome.errorMessage, failure))
            })
        } catch (failure: Exception) {
            dialog = SettingsDialog.Message(backupFailureMessage(outcome.errorMessage, failure))
        } finally {
            if (isResumed && !busy) mSharedPreferences.registerOnSharedPreferenceChangeListener(this)
        }
    }

    private fun backupFailureMessage(error: Int, failure: Throwable): String {
        if (failure !is GeneralBackupRestoreException) return getString(error)
        setPreferences(reinflate = true)
        val completed =
            failure.completedData.map { generalBackupItemLabel(it).substringBefore('\n') }
                .joinToString(", ")
        val failed = failure.failedData.map { generalBackupItemLabel(it).substringBefore('\n') }
            .joinToString(", ")
        return getString(
            R.string.settings_restore_partial,
            completed.ifEmpty { getString(R.string.settings_restore_none) },
            failed
        )
    }

    private fun predictorExportRequest(): CompletableFuture<PredictorStoredState>? {
        if (!BackgroundServiceWatchdog.isServiceDesired(applicationContext)) return null
        val persistent =
            parentFragmentManager.findFragmentByTag(PersistentFragment.FRAG_TAG) as? PersistentFragment
        return persistent?.predictorForBackup() ?: CompletableFuture<PredictorStoredState>().apply {
            completeExceptionally(IOException("Monitoring service is unavailable"))
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        pendingDocumentRequest = 0
        if (requestCode == AUTO_LOG_EXPORT_DIRECTORY_REQUEST) {
            handleAutoLogExportDirectoryResult(resultCode, data)
            return
        }
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val settings = mSharedPreferences
        when (requestCode) {
            EXPORT_REQUEST -> fileOperation(
                SettingsOperationAction.ExportSettings, R.string.history_operation_failed
            ) { context ->
                SettingsBackup.writeToUri(context, uri, SettingsBackup.exportToJson(settings))
            }

            IMPORT_REQUEST -> fileOperation(
                SettingsOperationAction.ReadSettings, R.string.invalid_settings_file
            ) { context ->
                val json = requireNotNull(SettingsBackup.readFromUri(context, uri))
                JsonBackupImportPreview(json, SettingsBackup.getSchemaVersion(json))
            }

            EXPORT_ALARMS_REQUEST -> fileOperation(
                SettingsOperationAction.ExportAlarms, R.string.history_operation_failed
            ) { context ->
                val database = AlarmDatabase(context)
                try {
                    AlarmBackup.writeToUri(context, uri, AlarmBackup.exportToJson(database))
                } finally {
                    database.close()
                }
            }

            IMPORT_ALARMS_REQUEST -> fileOperation(
                SettingsOperationAction.ReadAlarms, R.string.invalid_alarms_file
            ) { context ->
                val json = requireNotNull(AlarmBackup.readFromUri(context, uri))
                JsonBackupImportPreview(json, AlarmBackup.getSchemaVersion(json))
            }

            EXPORT_DEVICE_DATA_REQUEST -> {
                val selectedData = pendingDeviceDataExport
                val predictor =
                    if (DeviceDataType.PREDICTOR_DATA in selectedData) predictorExportRequest() else null
                fileOperation(
                    SettingsOperationAction.ExportDeviceData, R.string.history_operation_failed
                ) { context ->
                    val snapshot = predictor?.get(6, TimeUnit.SECONDS)
                    DeviceDataBackup.writeToUri(
                        context, uri, DeviceDataBackup.exportToJson(context, selectedData, snapshot)
                    )
                }
            }

            IMPORT_DEVICE_DATA_REQUEST -> fileOperation(
                SettingsOperationAction.ReadDeviceData, R.string.invalid_device_data_file
            ) { context ->
                val json = requireNotNull(DeviceDataBackup.readFromUri(context, uri))
                DeviceBackupImportPreview(
                    json,
                    DeviceDataBackup.getAvailableData(json),
                    DeviceDataBackup.getSchemaVersion(json)
                )
            }

            IMPORT_LOGS_CSV_REQUEST -> fileOperation(
                SettingsOperationAction.ReadCsvLogs, R.string.invalid_csv_logs_file
            ) { context ->
                requireNotNull(CsvLogImporter.readFromUri(context, uri))
            }

            EXPORT_GENERAL_BACKUP_REQUEST -> {
                val predictor = predictorExportRequest()
                fileOperation(
                    SettingsOperationAction.ExportGeneralBackup, R.string.history_operation_failed
                ) { context ->
                    GeneralBackup.exportToUri(
                        context, uri, settings, predictor?.get(6, TimeUnit.SECONDS)
                    )
                }
            }

            IMPORT_GENERAL_BACKUP_REQUEST -> fileOperation(
                SettingsOperationAction.ReadGeneralBackup, R.string.invalid_general_backup_file
            ) { context ->
                val archive = requireNotNull(GeneralBackup.readFromUri(context, uri))
                GeneralBackupImportPreview(
                    archive,
                    GeneralBackup.getAvailableData(archive),
                    GeneralBackup.containsNewerSchema(archive)
                )
            }
        }
    }

    private fun setupAutoLogExportPreference() {
        val preference = mPreferenceScreen?.findPreference<Preference>(
            SettingsContract.KEY_AUTO_LOG_EXPORT
        ) ?: return
        val loggingEnabled = mSharedPreferences.getBoolean(
            SettingsContract.KEY_ENABLE_LOGGING, true
        )
        preference.isEnabled = loggingEnabled
        if (!isAutoLogExportConfigured()) {
            preference.setTitle(R.string.pref_set_auto_log_export)
            preference.setSummary(
                if (loggingEnabled) R.string.pref_auto_log_export_not_set_summary
                else R.string.currently_disabled
            )
            return
        }

        val frequencyOptions = autoLogExportFrequencyOptions()
        val frequencyValues = frequencyOptions.map { it.preferenceValue }.toTypedArray()
        val modeValues = resources.getStringArray(R.array.auto_log_export_mode_values)
        val formatValues = resources.getStringArray(R.array.auto_log_export_format_values)
        preference.setTitle(R.string.pref_edit_auto_log_export)
        if (!loggingEnabled) {
            preference.setSummary(R.string.currently_disabled)
            return
        }
        preference.summary = getString(
            R.string.pref_auto_log_export_set_summary, labelForValue(
                frequencyValues,
                frequencyOptions.map { it.label }.toTypedArray(),
                currentAutoLogExportFrequency().preferenceValue
            ), labelForValue(
                modeValues,
                resources.getStringArray(R.array.auto_log_export_mode_entries),
                mSharedPreferences.getString(SettingsContract.KEY_AUTO_LOG_EXPORT_MODE, null)
            ), labelForValue(
                formatValues,
                resources.getStringArray(R.array.auto_log_export_format_entries),
                mSharedPreferences.getString(SettingsContract.KEY_AUTO_LOG_EXPORT_FORMAT, null)
            ), directoryLabel(currentAutoLogExportDirectory())
        )
    }

    private fun openAutoLogExportDirectoryPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        launchDocument(intent, AUTO_LOG_EXPORT_DIRECTORY_REQUEST)
    }

    @SuppressLint("WrongConstant")
    private fun handleAutoLogExportDirectoryResult(resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            if (isAutoLogExportConfigured()) {
                showAutoLogExportDialog(currentAutoLogExportDirectory())
            }
            return
        }

        val flags =
            data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        showAutoLogExportDialog(uri, flags)
    }

    @SuppressLint("WrongConstant")
    private fun showAutoLogExportDialog(directory: Uri?, permissionFlags: Int? = null) {
        if (directory == null) {
            openAutoLogExportDirectoryPicker()
            return
        }
        val wasConfigured = isAutoLogExportConfigured()
        val frequencies = autoLogExportFrequencyOptions()
        val frequencyValues = frequencies.map { it.preferenceValue }.toTypedArray()
        val modes = resources.getStringArray(R.array.auto_log_export_mode_values)
        val formats = resources.getStringArray(R.array.auto_log_export_format_values)
        val enabled =
            AutoLogExportFrequency.enabledForRetention(maxLogAgeHours()).map { it.preferenceValue }
                .toSet()
        dialog = SettingsDialog.AutoExport(
            title = getString(if (wasConfigured) R.string.pref_edit_auto_log_export else R.string.pref_set_auto_log_export),
            directoryLabel = directoryLabel(directory),
            frequencyLabels = frequencies.map { it.label },
            frequencyEnabled = frequencies.map { it.preferenceValue in enabled },
            frequencyIndex = valueIndex(
                frequencyValues, AutoLogExportFrequency.cappedForRetention(
                    currentAutoLogExportFrequency(), maxLogAgeHours()
                ).preferenceValue
            ),
            modeLabels = resources.getStringArray(R.array.auto_log_export_mode_entries).toList(),
            modeIndex = valueIndex(
                modes, mSharedPreferences.getString(
                    SettingsContract.KEY_AUTO_LOG_EXPORT_MODE,
                    AutoLogExportMode.NEW_FILE.preferenceValue
                )
            ),
            formatLabels = resources.getStringArray(R.array.auto_log_export_format_entries)
                .toList(),
            formatIndex = valueIndex(
                formats, mSharedPreferences.getString(
                    SettingsContract.KEY_AUTO_LOG_EXPORT_FORMAT, LogExportFormat.CSV.preferenceValue
                )
            ),
            configured = wasConfigured,
            onConfirm = { frequency, mode, format ->
                runCatching {
                    val oldDirectory = currentAutoLogExportDirectory()
                    permissionFlags?.let {
                        requireContext().contentResolver.takePersistableUriPermission(
                            directory, it
                        )
                    }
                    mSharedPreferences.edit {
                        putString(
                            SettingsContract.KEY_AUTO_LOG_EXPORT_FREQUENCY,
                            frequencyValues[frequency]
                        )
                        putString(SettingsContract.KEY_AUTO_LOG_EXPORT_MODE, modes[mode])
                        putString(SettingsContract.KEY_AUTO_LOG_EXPORT_FORMAT, formats[format])
                        putString(
                            SettingsContract.KEY_AUTO_LOG_EXPORT_DIRECTORY, directory.toString()
                        )
                        if (oldDirectory != directory) remove(SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME)
                    }
                    releaseAutoLogExportDirectory(oldDirectory.takeIf { it != directory })
                    setupAutoLogExportPreference()
                    when (autoLogExportSetupAction(wasConfigured)) {
                        AutoLogExportSetupAction.START_INITIAL_EXPORT -> AutoLogExportScheduler.startInitialExport(
                            requireContext()
                        )

                        AutoLogExportSetupAction.RESCHEDULE -> AutoLogExportScheduler.reschedule(
                            requireContext()
                        )
                    }
                    modelVersion++
                }.onFailure {
                    dialog =
                        SettingsDialog.Message(getString(R.string.advanced_value_not_available))
                }
            },
            onChooseDirectory = ::openAutoLogExportDirectoryPicker,
            onDisable = ::disableAutoLogExport
        )
    }

    private fun disableAutoLogExport() {
        val directory = currentAutoLogExportDirectory()
        mSharedPreferences.edit {
            putString(
                SettingsContract.KEY_AUTO_LOG_EXPORT_FREQUENCY,
                AutoLogExportFrequency.OFF.preferenceValue
            )
            remove(SettingsContract.KEY_AUTO_LOG_EXPORT_DIRECTORY)
            remove(SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME)
        }
        releaseAutoLogExportDirectory(directory)
        setupAutoLogExportPreference()
        AutoLogExportScheduler.cancel(requireContext())
        modelVersion++
    }

    private data class AutoLogExportFrequencyOption(
        val label: String, val preferenceValue: String
    )

    private fun autoLogExportFrequencyOptions(): List<AutoLogExportFrequencyOption> {
        val labels = resources.getStringArray(R.array.auto_log_export_frequency_entries)
        val values = resources.getStringArray(R.array.auto_log_export_frequency_values)
        return values.indices.map { index ->
            AutoLogExportFrequencyOption(labels[index], values[index])
        }
    }

    private fun maxLogAgeHours(): Int = mSharedPreferences.getString(
        SettingsContract.KEY_MAX_LOG_AGE, getString(R.string.default_max_log_age)
    )?.toIntOrNull() ?: getString(R.string.default_max_log_age).toInt()

    private fun currentAutoLogExportFrequency(): AutoLogExportFrequency =
        AutoLogExportFrequency.fromPreference(
            mSharedPreferences.getString(
                SettingsContract.KEY_AUTO_LOG_EXPORT_FREQUENCY,
                AutoLogExportFrequency.ONE_DAY.preferenceValue
            )
        ).takeUnless { it == AutoLogExportFrequency.OFF } ?: AutoLogExportFrequency.ONE_DAY

    private fun capAutoLogExportFrequencyToRetention() {
        if (!isAutoLogExportConfigured()) return
        val current = currentAutoLogExportFrequency()
        val capped = AutoLogExportFrequency.cappedForRetention(current, maxLogAgeHours())
        if (capped == current) return
        mSharedPreferences.edit {
            putString(SettingsContract.KEY_AUTO_LOG_EXPORT_FREQUENCY, capped.preferenceValue)
        }
        setupAutoLogExportPreference()
        AutoLogExportScheduler.reschedule(requireContext())
    }

    private fun currentAutoLogExportDirectory(): Uri? = mSharedPreferences.getString(
        SettingsContract.KEY_AUTO_LOG_EXPORT_DIRECTORY, null
    )?.let(Uri::parse)

    private fun isAutoLogExportConfigured(): Boolean =
        currentAutoLogExportDirectory() != null && AutoLogExportFrequency.fromPreference(
            mSharedPreferences.getString(SettingsContract.KEY_AUTO_LOG_EXPORT_FREQUENCY, null)
        ) != AutoLogExportFrequency.OFF

    private fun releaseAutoLogExportDirectory(directory: Uri?) {
        directory ?: return
        runCatching {
            requireContext().contentResolver.releasePersistableUriPermission(
                directory,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun directoryLabel(directory: Uri?): String = directory?.let {
        runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull()?.let(
            ::displayPathForDocumentId
        ) ?: it.lastPathSegment.orEmpty()
    }.orEmpty()

    private fun valueIndex(values: Array<String>, value: String?): Int =
        values.indexOf(value).takeIf { it >= 0 } ?: 0

    private fun labelForValue(
        values: Array<String>, labels: Array<String>, value: String?
    ): String = labels.getOrElse(valueIndex(values, value)) { labels.firstOrNull().orEmpty() }

    private fun showDeviceDataExportDialog() {
        val dataTypes = DeviceDataType.entries.toTypedArray()
        showDeviceDataSelectionDialog(
            title = R.string.pref_export_device_data,
            dataTypes = dataTypes,
            warning = getString(R.string.device_data_backup_warning)
        ) { selectedData ->
            pendingDeviceDataExport = selectedData
            val timestamp = SimpleDateFormat(
                "yyyy-MM-dd-HHmmss-SSS", Locale.getDefault()
            ).format(Date())
            val exportIntent =
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(
                        Intent.EXTRA_TITLE, "battery_monitor_device_specific_$timestamp.json"
                    )
            launchDocument(exportIntent, EXPORT_DEVICE_DATA_REQUEST)
        }
    }

    private fun openCsvLogFilePicker() {
        val importIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/*")
        launchDocument(importIntent, IMPORT_LOGS_CSV_REQUEST)
    }

    private fun showCsvLogImportModeDialog(csv: String) {
        showLogImportModeDialog(
            getString(R.string.csv_logs_import_warning) + "\n\n" + getString(R.string.log_import_mode_message)
        ) { mode ->
            fileOperation(
                SettingsOperationAction.ImportCsvLogs, R.string.invalid_csv_logs_file
            ) { context ->
                CsvLogImporter.importFromCsv(context, csv, mode)
            }
        }
    }

    private fun showGeneralBackupImportDialog(preview: GeneralBackupImportPreview) {
        runCatching {
            val archive = preview.archive
            val available = preview.available
            val types = GeneralBackupDataType.entries.filter { it in available }
            val warning = buildString {
                append(getString(R.string.general_backup_restore_message))
                if (preview.newerSchema) {
                    append("\n\n").append(getString(R.string.settings_file_version_warning))
                }
            }
            dialog = SettingsDialog.Choices(
                title = getString(R.string.pref_import_general_backup),
                message = warning,
                options = types.map(::generalBackupItemLabel),
                selected = types.indices.toSet(),
                onConfirm = { selected ->
                    val selectedData = selected.mapTo(linkedSetOf()) { types[it] }
                    if (GeneralBackupDataType.LOGS in selectedData) {
                        showLogImportModeDialog(getString(R.string.log_import_mode_message)) { mode ->
                            doGeneralBackupImport(archive, selectedData, mode)
                        }
                    } else doGeneralBackupImport(archive, selectedData, LogImportMode.REPLACE)
                })
        }.onFailure {
            dialog = SettingsDialog.Message(getString(R.string.invalid_general_backup_file))
        }
    }

    private fun generalBackupItemLabel(type: GeneralBackupDataType): String {
        val label = getString(
            when (type) {
                GeneralBackupDataType.SETTINGS -> R.string.settings_activity_subtitle
                GeneralBackupDataType.ALARMS -> R.string.alarm_settings
                GeneralBackupDataType.LOGS -> R.string.device_data_logs
                GeneralBackupDataType.PREDICTOR_DATA -> R.string.device_data_predictor
            }
        )
        return if (type == GeneralBackupDataType.LOGS || type == GeneralBackupDataType.PREDICTOR_DATA) {
            label + "\n" + getString(R.string.device_data_backup_warning)
        } else {
            label
        }
    }

    private fun doGeneralBackupImport(
        archive: GeneralBackupArchive,
        selectedData: Set<GeneralBackupDataType>,
        logImportMode: LogImportMode
    ) {
        val settings = mSharedPreferences
        val messenger = serviceMessenger
        settings.unregisterOnSharedPreferenceChangeListener(this)
        fileOperation(
            SettingsOperationAction.ImportGeneralBackup, R.string.invalid_general_backup_file
        ) { context ->
            val changed = try {
                GeneralBackup.restore(context, settings, archive, selectedData, logImportMode)
                selectedData
            } catch (failure: GeneralBackupRestoreException) {
                reloadRestoredData(
                    context, settings, messenger, failure.completedData + failure.failedData
                )
                throw failure
            }
            reloadRestoredData(context, settings, messenger, changed)
        }
    }

    private fun showDeviceDataImportDialog(preview: DeviceBackupImportPreview) {
        try {
            val json = preview.json
            val availableData = preview.available
            if (availableData.isEmpty()) {
                Toast.makeText(activity, R.string.device_data_file_empty, Toast.LENGTH_SHORT).show()
                return
            }
            val dataTypes = DeviceDataType.entries.filter { it in availableData }.toTypedArray()
            val warning = if (preview.version > DeviceDataBackup.SCHEMA_VERSION) {
                getString(R.string.settings_file_version_warning) + "\n\n" + getString(R.string.device_data_backup_warning)
            } else {
                getString(R.string.device_data_backup_warning)
            }
            showDeviceDataSelectionDialog(
                title = R.string.pref_import_device_data, dataTypes = dataTypes, warning = warning
            ) { selectedData ->
                if (DeviceDataType.LOGS in selectedData) {
                    showLogImportModeDialog(getString(R.string.log_import_mode_message)) { logImportMode ->
                        doDeviceDataImport(json, selectedData, logImportMode)
                    }
                } else {
                    doDeviceDataImport(json, selectedData, LogImportMode.REPLACE)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.invalid_device_data_file, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeviceDataSelectionDialog(
        title: Int,
        dataTypes: Array<DeviceDataType>,
        warning: String,
        onConfirm: (Set<DeviceDataType>) -> Unit
    ) {
        dialog = SettingsDialog.Choices(
            title = getString(title),
            message = warning,
            options = dataTypes.map { type ->
                getString(
                    when (type) {
                        DeviceDataType.LOGS -> R.string.device_data_logs
                        DeviceDataType.PREDICTOR_DATA -> R.string.device_data_predictor
                    }
                )
            },
            selected = dataTypes.indices.toSet(),
            onConfirm = { selected -> onConfirm(selected.mapTo(linkedSetOf()) { dataTypes[it] }) })
    }

    private fun showLogImportModeDialog(message: String, onConfirm: (LogImportMode) -> Unit) {
        val modes = listOf(LogImportMode.ADD, LogImportMode.REPLACE)
        dialog = SettingsDialog.Choices(
            title = getString(R.string.log_import_mode_title),
            message = message,
            options = listOf(
                getString(R.string.log_import_add), getString(R.string.log_import_replace)
            ),
            selected = setOf(0),
            multiple = false,
            onConfirm = { selected -> onConfirm(modes[selected.single()]) })
    }

    private fun doDeviceDataImport(
        json: String, selectedData: Set<DeviceDataType>, logImportMode: LogImportMode
    ) {
        val settings = mSharedPreferences
        val messenger = serviceMessenger
        fileOperation(
            SettingsOperationAction.ImportDeviceData, R.string.invalid_device_data_file
        ) { context ->
            DeviceDataBackup.importFromJson(context, json, selectedData, logImportMode)
            if (DeviceDataType.PREDICTOR_DATA in selectedData) reloadService(
                context, settings, messenger, predictor = true
            )
        }
    }

    private fun resetSettingsToDefaults() {
        val settings = mSharedPreferences
        val messenger = serviceMessenger
        val oldDirectory = currentAutoLogExportDirectory()
        settings.unregisterOnSharedPreferenceChangeListener(this)
        fileOperation(
            SettingsOperationAction.ResetSettings, R.string.settings_reset_failed
        ) { context ->
            try {
                val defaultsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val manager = context.getSystemService(LocaleManager::class.java)
                    context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                        setLocales(manager.systemLocales)
                    })
                } else context
                val editor = settings.edit()
                SettingsReset.reset(editor)
                editor.putBoolean(
                    SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING, true
                )
                check(editor.commit()) { "Could not reset settings" }
                PREFERENCE_SCREENS.forEach { xml ->
                    PreferenceManager.setDefaultValues(
                        defaultsContext,
                        SettingsContract.SETTINGS_FILE,
                        Context.MODE_PRIVATE,
                        xml,
                        true
                    )
                }
                check(settings.edit().commit()) { "Could not save default settings" }
                DebugLogCollector.sync(context, false)
                PrivilegedAccess.setEnabled(false)
                AutoLogExportScheduler.cancel(context)
                oldDirectory?.let { directory ->
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            directory,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                }
                reloadService(context, settings, messenger)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.getSystemService(LocaleManager::class.java).applicationLocales =
                        LocaleList.getEmptyLocaleList()
                }
            } catch (failure: Exception) {
                BatteryCurrent.setMultiplier(
                    settings.getString(
                        SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "1"
                    )?.toIntOrNull() ?: 1
                )
                PrivilegedAccess.setEnabled(
                    settings.getBoolean(
                        SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false
                    )
                )
                runCatching {
                    DebugLogCollector.sync(
                        context, settings.getBoolean(SettingsContract.KEY_DEBUG_LOGGING, false)
                    )
                }
                runCatching { AutoLogExportScheduler.ensureScheduled(context) }
                runCatching { reloadService(context, settings, messenger) }
                throw failure
            }
        }
    }

    private fun doImport(json: String) {
        val settings = mSharedPreferences
        val messenger = serviceMessenger
        settings.unregisterOnSharedPreferenceChangeListener(this)
        fileOperation(
            SettingsOperationAction.ImportSettings, R.string.invalid_settings_file
        ) { context ->
            val editor = settings.edit()
            SettingsBackup.importFromJson(editor, json)
            check(editor.commit()) { "Could not save imported settings" }
            reloadService(context, settings, messenger)
        }
    }

    private fun doAlarmImport(json: String) {
        val settings = mSharedPreferences
        val messenger = serviceMessenger
        fileOperation(
            SettingsOperationAction.ImportAlarms, R.string.invalid_alarms_file
        ) { context ->
            val database = AlarmDatabase(context)
            try {
                AlarmBackup.importFromJson(database, json)
            } finally {
                database.close()
            }
            reloadService(context, settings, messenger)
        }
    }

    private fun setupLanguage() {
        val category: PreferenceCategory?
        val pref: Preference?
        try {
            category = mPreferenceScreen!!.findPreference(
                SettingsContract.KEY_CHANGE_APP_LANGUAGE_HOLDER
            )
            pref = mPreferenceScreen!!.findPreference(SettingsContract.KEY_CHANGE_APP_LANGUAGE)
        } catch (e: ClassCastException) {
            return
        }

        if (category == null || pref == null) return
        pref.setSummary(
            res.getString(R.string.currently_set_to) + " " + Locale.getDefault().displayLanguage
        )
        category.isVisible = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pref.isVisible = true
            pref.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { _: Preference? -> this.launchChangeAppLanguageIntent() }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun launchChangeAppLanguageIntent(): Boolean {
        try {
            val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS)
            intent.setData(Uri.fromParts("package", requireContext().packageName, null))
            startActivity(intent)
            return true
        } catch (ignored: Exception) {
        }
        return false
    }

    fun enableNotifsButtonClick() {
        val context = requireContext()
        val opened = if (!appNotifsEnabled || mainChan == null) {
            NotificationSettingsNavigator.openNotifications(context)
        } else {
            NotificationSettingsNavigator.openNotificationChannel(context, mainChan!!.id)
        }
        if (!opened) context.showToast(R.string.advanced_value_not_available)
    }

    private fun getMainNotifsEnabled(): Boolean {
        mainChan = mNotificationManager!!.getNotificationChannel(BatteryInfoService.CHAN_ID_MAIN)
        return mainChan != null && mainChan!!.importance > 0
    }
}
