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
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import android.util.AtomicFile
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.alarms.AlarmDatabase
import codes.swistak.batterymonitor.alarms.AlarmRecord
import codes.swistak.batterymonitor.alarms.backup.AlarmBackup
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.app.PersistentFragment
import codes.swistak.batterymonitor.common.NotificationSettingsNavigator
import codes.swistak.batterymonitor.common.RootExecutor
import codes.swistak.batterymonitor.common.showToast
import codes.swistak.batterymonitor.devicebackup.BackupImportInputStream
import codes.swistak.batterymonitor.devicebackup.CsvLogImporter
import codes.swistak.batterymonitor.devicebackup.DeviceDataBackup
import codes.swistak.batterymonitor.devicebackup.DeviceDataType
import codes.swistak.batterymonitor.devicebackup.GeneralBackup
import codes.swistak.batterymonitor.devicebackup.GeneralBackupArchive
import codes.swistak.batterymonitor.devicebackup.GeneralBackupDataType
import codes.swistak.batterymonitor.devicebackup.GeneralBackupRestoreException
import codes.swistak.batterymonitor.devicebackup.LogImportMode
import codes.swistak.batterymonitor.devicebackup.PreparedDeviceDataBackup
import codes.swistak.batterymonitor.diagnostics.DebugLogCollector
import codes.swistak.batterymonitor.logs.AutoLogExportFrequency
import codes.swistak.batterymonitor.logs.AutoLogExportMode
import codes.swistak.batterymonitor.logs.AutoLogExportScheduler
import codes.swistak.batterymonitor.logs.AutoLogExportSetupAction
import codes.swistak.batterymonitor.logs.LogExport
import codes.swistak.batterymonitor.logs.LogExportFormat
import codes.swistak.batterymonitor.logs.LogExportRecoveryException
import codes.swistak.batterymonitor.logs.autoLogExportSetupAction
import codes.swistak.batterymonitor.monitoring.BackgroundServiceWatchdog
import codes.swistak.batterymonitor.monitoring.BatteryCurrent
import codes.swistak.batterymonitor.monitoring.BatteryCurrentMultiplierDetector
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import codes.swistak.batterymonitor.monitoring.PredictorStoredState
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingLimitContentProvider
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingTargetResolver
import codes.swistak.batterymonitor.monitoring.charginglimit.ResolvedTarget
import codes.swistak.batterymonitor.monitoring.charginglimit.TargetSource
import codes.swistak.batterymonitor.monitoring.notifications.NotificationChannels
import codes.swistak.batterymonitor.privileged.PrivilegedAccess
import codes.swistak.batterymonitor.settings.backup.SettingsBackup
import codes.swistak.batterymonitor.ui.settings.NotificationAvailability
import codes.swistak.batterymonitor.ui.settings.SettingCatalog
import codes.swistak.batterymonitor.ui.settings.SettingDescriptor
import codes.swistak.batterymonitor.ui.settings.SettingsDialog
import codes.swistak.batterymonitor.ui.settings.SettingsDialogs
import codes.swistak.batterymonitor.ui.settings.SettingsOrderItem
import codes.swistak.batterymonitor.ui.settings.SettingsScreen
import codes.swistak.batterymonitor.ui.theme.AppBatteryTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnBinderReceivedListener
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit


internal fun displayPathForDocumentId(documentId: String): String =
    documentId.removePrefix("primary:")

// GeneralBackup allows 150 MiB of entry contents; reserve 10 MiB for ZIP headers and metadata.
internal const val GENERAL_BACKUP_DIALOG_CACHE_LIMIT_BYTES = 160L * 1024 * 1024

internal fun copyGeneralBackupDialogSource(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long = GENERAL_BACKUP_DIALOG_CACHE_LIMIT_BYTES
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    var count = input.read(buffer)
    while (count != -1) {
        total += count
        require(total <= maxBytes) { "General backup is too large" }
        output.write(buffer, 0, count)
        count = input.read(buffer)
    }
}

class SettingsFragment : Fragment(), OnSharedPreferenceChangeListener {

    companion object {
        const val ARG_CATEGORY = "category"
        const val ARG_HIGHLIGHT = "highlight"

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

        private val RESET_SERVICE = setOf(
            SettingsContract.KEY_COLOR_SOURCE,
            SettingsContract.KEY_BRIGHTNESS,
            SettingsContract.KEY_DISMISS_LOW_BATTERY_ON_RECOVERY,
            SettingsContract.KEY_TEMPERATURE_UNIT,
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
            SettingsContract.KEY_PREDICTION_TYPE,
            SettingsContract.KEY_CHARGING_TARGET_MODE,
            SettingsContract.KEY_CUSTOM_CHARGING_TARGET,
            SettingsContract.KEY_DISCHARGING_TARGET,
            SettingsContract.KEY_LONG_DURATION_FORMAT
        )

        private fun reloadService(
            context: Context,
            settings: SharedPreferences,
            messenger: Messenger?,
            predictor: Boolean = false
        ) {
            if (!BackgroundServiceWatchdog.isServiceDesired(context)) {
                codes.swistak.batterymonitor.widgets.WidgetSettingsReceiver.synchronize(
                    context, settings
                )
                return
            }
            val outgoing = Message.obtain().apply {
                what = when {
                    predictor -> BatteryInfoService.RemoteConnection.SERVICE_RELOAD_DEVICE_DATA
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
            } catch (_: Exception) {
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

    private lateinit var mSharedPreferences: SharedPreferences
    private lateinit var notificationAvailability: NotificationAvailability
    private var settingsRows by mutableStateOf(emptyList<SettingDescriptor>())
    private var chargingTargetSummary: String? = null

    private var selectedCategory by mutableStateOf<String?>(null)
    private var highlightedKey by mutableStateOf<String?>(null)
    private var activeDialog by mutableStateOf<SettingsDialog?>(null)
    private var dialogRequest: Bundle? = null
    private var pendingBackupDialog: Bundle? = null
    private var dialog: SettingsDialog?
        get() = activeDialog
        set(value) {
            activeDialog = value
            dialogRequest = null
        }

    private lateinit var operationModel: SettingsOperationViewModel
    private val busy: Boolean get() = operationModel.busy
    private var serviceBound = false
    private var pendingDocumentRequest = 0
    private val documentRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleDocumentResult(result.resultCode, result.data)
    }

    private lateinit var applicationContext: Context
    private var timeEstimateJob: Job? = null
    private var batteryCurrentMultiplierDetectionRunning = false
    private var applyingDetectedBatteryCurrentMultiplier = false
    private var pendingPrivilegedShizukuBinderListener: OnBinderReceivedListener? = null

    @Volatile
    private var privilegedAccessRequestInProgress = false

    @Volatile
    private var privilegedAccessRequestGeneration = 0L

    private val privilegedShizukuPermissionListener =
        OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE || !privilegedAccessRequestInProgress) return@OnRequestPermissionResultListener

            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                val generation = privilegedAccessRequestGeneration
                mainHandler.post {
                    if (generation == privilegedAccessRequestGeneration) completePrivilegedAccessEnable()
                }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applicationContext = requireContext().applicationContext
        operationModel = ViewModelProvider(this)[SettingsOperationViewModel::class.java]
        pendingDocumentRequest = savedInstanceState?.getInt("document_request") ?: 0
        selectedCategory =
            savedInstanceState?.getString(ARG_CATEGORY) ?: arguments?.getString(ARG_CATEGORY)
                    ?: selectedCategory
        highlightedKey =
            savedInstanceState?.getString(ARG_HIGHLIGHT) ?: arguments?.getString(ARG_HIGHLIGHT)
        mSharedPreferences = requireContext().getSharedPreferences(
            SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
        )
        prepareBatteryCurrentMultiplierDetection()
        notificationAvailability = readNotificationAvailability()
        BatteryCurrent.setContext(requireContext())
        PrivilegedAccess.initialize(requireContext())
        PrivilegedAccess.setEnabled(
            mSharedPreferences.getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false)
        )
        PrivilegedAccess.setReadyListener {
            mainHandler.post {
                if (isAdded) {
                    setupTimeEstimatePreferences()
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(privilegedShizukuPermissionListener)
        pendingDeviceDataExport =
            savedInstanceState?.getStringArray(STATE_DEVICE_DATA_EXPORT)?.mapNotNull { name ->
                runCatching { DeviceDataType.valueOf(name) }.getOrNull()
            }?.toSet().orEmpty()
        setPreferences()
        pendingBackupDialog = savedInstanceState?.getBundle("settings_backup_dialog")
        savedInstanceState?.getBundle("settings_dialog")?.let { request ->
            if (pendingDocumentRequest == AUTO_LOG_EXPORT_DIRECTORY_REQUEST && request.getString("action") == "auto_export") dialogRequest =
                request else restoreDialog(request)
        }
        if (dialog == null && !busy && operationModel.outcome == null) {
            pendingBackupDialog?.let(::readBackupImport)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AppBatteryTheme {
                SettingsScreen(
                    descriptors = settingsRows,
                    initialCategory = selectedCategory,
                    highlightKey = highlightedKey,
                    onActivate = ::activatePreference,
                    onChange = ::changeSetting,
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
                SettingsDialogs(
                    dialog, busy, ::dismissSettingsDialog, ::closeSettingsDialogForConfirmation
                )
            }
        }
    }

    private fun openDiagnostics(route: String, highlight: String?) {
        (activity as? BatteryInfoActivity)?.openDiagnosticDetail(route, highlight)
    }

    private fun activatePreference(key: String) {
        if (busy || pendingDocumentRequest != 0) return
        val row = settingsRows.firstOrNull { it.key == key && it.unavailable == null } ?: return
        val choice = row.control as? SettingsControl.Choice
        when {
            choice != null -> {
                val values = resources.getStringArray(choice.entryValues).toList()
                dialog = SettingsDialog.Choices(
                    title = choice.dialogTitle?.let(::getString) ?: row.title,
                    options = resources.getStringArray(choice.entries).toList(),
                    selected = setOf(
                        if (key == SettingsContract.KEY_CHIP_SWITCHING_INTERVAL) {
                            chipSwitchingIntervalChoiceIndex(row.value as? String, values)
                        } else values.indexOf(row.value as? String)
                    ),
                    multiple = false,
                    onConfirm = { selected -> changeSetting(key, values[selected.single()]) })
                rememberPreferenceDialog(key)
            }

            key == SettingsContract.KEY_ENABLE_NOTIFS_B -> enableNotifsButtonClick()
            key == SettingsContract.KEY_LIVE_UPDATE_SYSTEM_SETTINGS -> {
                val context = requireContext()
                val opened = when {
                    !notificationAvailability.appEnabled -> NotificationSettingsNavigator.openNotifications(
                        context
                    )

                    isLiveUpdateChannelBlocked() -> NotificationSettingsNavigator.openNotificationChannel(
                        context, NotificationChannels.CHAN_ID_LIVE_UPDATE
                    )

                    else -> NotificationSettingsNavigator.openLiveUpdates(context)
                }
                if (!opened) context.showToast(R.string.diag_value_not_available)
            }

            key == SettingsContract.KEY_CHANGE_APP_LANGUAGE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launchChangeAppLanguageIntent()
                }
            }

            else -> activateAction(key)
        }
    }

    private fun changeSetting(key: String, value: Any) {
        if (busy || settingsRows.none { it.key == key && it.unavailable == null }) return
        when (key) {
            SettingsContract.KEY_USE_PRIVILEGED_ACCESS -> {
                if (value == true) {
                    requestPrivilegedAccessEnable()
                    return
                }
                cancelPrivilegedAccessRequest()
            }

            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER -> {
                if (value == BATTERY_CURRENT_MULTIPLIER_AUTODETECT_VALUE) {
                    requestBatteryCurrentMultiplierDetection()
                    return
                }
            }

            SettingsContract.KEY_CHIP_SWITCHING_INTERVAL -> {
                if (value == "custom") {
                    showCustomChipSwitchingIntervalDialog()
                    return
                }
            }
        }
        mSharedPreferences.edit {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
                is Int -> putInt(key, value)
            }
            if (key == SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER) {
                remove(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING)
            }
        }
        refreshSettingsRows()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val request = dialogRequest ?: (dialog as? SettingsDialog.Message)?.let {
            Bundle().apply {
                putString("action", "message")
                putString("message", it.message)
            }
        }
        outState.putBundle("settings_dialog", request)
        outState.putBundle("settings_backup_dialog", pendingBackupDialog)
        outState.putInt("document_request", pendingDocumentRequest)
        outState.putString(ARG_CATEGORY, selectedCategory)
        outState.putString(ARG_HIGHLIGHT, highlightedKey)
        outState.putStringArray(
            STATE_DEVICE_DATA_EXPORT, pendingDeviceDataExport.map { it.name }.toTypedArray()
        )
        super.onSaveInstanceState(outState)
    }

    private fun rememberPreferenceDialog(key: String) {
        dialogRequest = Bundle().apply {
            putString("action", "preference")
            putString("key", key)
        }
    }

    private fun restoreDialog(request: Bundle) {
        if (busy || pendingDocumentRequest != 0) return
        when (request.getString("action")) {
            "preference" -> request.getString("key")?.let(::activatePreference)
            "interval" -> showCustomChipSwitchingIntervalDialog()

            "auto_export" -> showAutoLogExportDialog(
                request.getString("directory")?.let(Uri::parse),
                request.getInt("permission_flags")
                    .takeIf { request.containsKey("permission_flags") },
                request
            )

            "message" -> request.getString("message")?.let { dialog = SettingsDialog.Message(it) }
            "backup_import" -> readBackupImport(request)
            "device_export" -> showDeviceDataExportDialog()
        }
    }


    private fun dismissSettingsDialog() {
        dialog = null
        clearBackupDialog()
    }

    private fun closeSettingsDialogForConfirmation() {
        // The confirmation may transfer its cache to another dialog or to the restore worker.
        dialog = null
    }

    private fun rememberBackupDialog(
        request: Bundle?, stage: String = "preview", selected: List<String> = emptyList()
    ) {
        if (request == null || dialog == null) return
        pendingBackupDialog = Bundle(request).apply {
            putString("stage", stage)
            putStringArrayList("selected", ArrayList(selected))
        }
        dialogRequest = pendingBackupDialog
    }

    private fun deleteBackupDialogCache(request: Bundle) {
        val name = request.getString("cache") ?: return
        if (File(name).name == name && name.startsWith("settings-import-")) {
            val source = File(applicationContext.cacheDir, name)
            AtomicFile(source).delete()
            DeviceDataBackup.preparedLogsFile(source).delete()
        }
    }

    private fun clearBackupDialog(deleteCache: Boolean = true) {
        if (deleteCache) pendingBackupDialog?.let(::deleteBackupDialogCache)
        pendingBackupDialog = null
        if (dialogRequest?.getString("action") == "backup_import") dialogRequest = null
    }


    private fun readBackupImport(request: Bundle, source: Uri? = null) {
        val requestCode = request.getInt("request")
        val (action, error) = when (requestCode) {
            IMPORT_REQUEST -> SettingsOperationAction.ReadSettings to R.string.invalid_settings_file
            IMPORT_ALARMS_REQUEST -> SettingsOperationAction.ReadAlarms to R.string.invalid_alarms_file
            IMPORT_DEVICE_DATA_REQUEST -> SettingsOperationAction.ReadDeviceData to R.string.invalid_device_data_file
            IMPORT_LOGS_CSV_REQUEST -> SettingsOperationAction.ReadCsvLogs to R.string.invalid_csv_logs_file
            IMPORT_GENERAL_BACKUP_REQUEST -> SettingsOperationAction.ReadGeneralBackup to R.string.invalid_general_backup_file
            else -> return
        }
        pendingBackupDialog = request
        fileOperation(action, error) { context ->
            val name = requireNotNull(request.getString("cache"))
            require(File(name).name == name && name.startsWith("settings-import-"))
            val cache = AtomicFile(File(context.cacheDir, name))
            if (source != null) {
                val copySource = {
                    val input = context.contentResolver.openInputStream(source)
                        ?: throw IOException("Could not open backup source")
                    input.use {
                        val output = cache.startWrite()
                        try {
                            if (requestCode == IMPORT_GENERAL_BACKUP_REQUEST) {
                                copyGeneralBackupDialogSource(it, output)
                            } else {
                                BackupImportInputStream(it).copyTo(output)
                            }
                            cache.finishWrite(output)
                        } catch (failure: Exception) {
                            cache.failWrite(output)
                            throw failure
                        }
                    }
                }
                if (requestCode == IMPORT_LOGS_CSV_REQUEST) {
                    LogExport.withReadableCsv(context, source, copySource)
                } else copySource()
            }
            val cachedUri = Uri.fromFile(cache.baseFile)
            val preparedLogs = DeviceDataBackup.preparedLogsFile(cache.baseFile)
            when (requestCode) {
                IMPORT_REQUEST -> SettingsBackup.parsedSettings(
                    JSONObject(SettingsBackup.readFromUri(context, cachedUri))
                )

                IMPORT_ALARMS_REQUEST -> {
                    val root = JSONObject(AlarmBackup.readFromUri(context, cachedUri))
                    AlarmBackupImportPreview(
                        root.optInt("version", 0), AlarmBackup.validatedRecords(root)
                    )
                }

                IMPORT_DEVICE_DATA_REQUEST -> DeviceDataBackup.readFromUri(
                    context, cachedUri, preparedLogs
                )

                IMPORT_LOGS_CSV_REQUEST -> {
                    cache.openRead().close()
                    cachedUri
                }

                else -> {
                    val archive = GeneralBackup.readFromUri(context, cachedUri, preparedLogs)
                    GeneralBackupImportPreview(
                        archive,
                        GeneralBackup.getAvailableData(archive),
                        GeneralBackup.containsNewerSchema(archive)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        val currentAvailability = readNotificationAvailability()
        if (notificationAvailability != currentAvailability) resetService()
        notificationAvailability = currentAvailability

        setPreferences()
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

        if (pendingPrivilegedShizukuBinderListener != null) cancelPrivilegedAccessRequest()
        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        timeEstimateJob?.cancel()
        timeEstimateJob = null
        if (serviceBound) {
            requireContext().unbindService(serviceConnection)
            serviceBound = false
            serviceMessenger = null
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) cancelPrivilegedAccessRequest()
    }

    override fun onDestroy() {
        cancelPrivilegedAccessRequest()
        Shizuku.removeRequestPermissionResultListener(privilegedShizukuPermissionListener)
        PrivilegedAccess.setReadyListener(null)
        super.onDestroy()
    }

    private fun cancelPrivilegedAccessRequest() {
        privilegedAccessRequestInProgress = false
        privilegedAccessRequestGeneration++
        pendingPrivilegedShizukuBinderListener?.let(Shizuku::removeBinderReceivedListener)
        pendingPrivilegedShizukuBinderListener = null
    }

    private fun resetService() {
        reloadService(applicationContext, mSharedPreferences, serviceMessenger)
    }

    private fun setPreferences(afterRestore: Boolean = false) {
        notificationAvailability = readNotificationAvailability()
        BatteryCurrent.setMultiplier(
            mSharedPreferences.getString(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "1")
                ?.toIntOrNull() ?: 1
        )
        syncPrivilegedAccessPreference()
        setupTimeEstimatePreferences()
        if (afterRestore) {
            prepareBatteryCurrentMultiplierDetection()
            capAutoLogExportFrequencyToRetention()
            AutoLogExportScheduler.ensureScheduled(applicationContext)
        }
        maybeDetectBatteryCurrentMultiplier()
        refreshSettingsRows()
    }

    private fun refreshSettingsRows() {
        settingsRows = SettingCatalog.entries(
            resources,
            mSharedPreferences.all,
            Build.VERSION.SDK_INT,
            notificationAvailability,
            chargingTargetSummary,
            autoLogExportSummary()
        )
    }

    private fun activateAction(key: String) {
        when (key) {
            SettingsContract.KEY_RESET_DEFAULT_SETTINGS -> {
                dialog = SettingsDialog.Confirm(
                    message = getString(R.string.settings_reset_question),
                    title = getString(R.string.settings_reset_title),
                    confirmLabel = R.string.settings_reset_confirm,
                    onConfirm = ::resetSettingsToDefaults
                )
                rememberPreferenceDialog(key)
            }

            SettingsContract.KEY_EXPORT_SETTINGS -> {
                val ts = SimpleDateFormat(
                    "yyyy-MM-dd-HHmmss-SSS", Locale.getDefault()
                ).format(Date())
                val exportIntent =
                    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "battery_monitor_settings_$ts.json")
                launchDocument(exportIntent, EXPORT_REQUEST)
            }

            SettingsContract.KEY_IMPORT_SETTINGS -> {
                val importIntent =
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                launchDocument(importIntent, IMPORT_REQUEST)
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
            }

            SettingsContract.KEY_IMPORT_ALARMS -> {
                val importIntent =
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                launchDocument(importIntent, IMPORT_ALARMS_REQUEST)
            }

            SettingsContract.KEY_EXPORT_DEVICE_DATA -> {
                showDeviceDataExportDialog()
            }

            SettingsContract.KEY_IMPORT_DEVICE_DATA -> {
                val importIntent =
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                launchDocument(importIntent, IMPORT_DEVICE_DATA_REQUEST)
            }

            SettingsContract.KEY_IMPORT_LOGS_CSV -> {
                openCsvLogFilePicker()
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
            }

            SettingsContract.KEY_IMPORT_GENERAL_BACKUP -> {
                val importIntent =
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/zip")
                launchDocument(importIntent, IMPORT_GENERAL_BACKUP_REQUEST)
            }

            SettingsContract.KEY_AUTO_LOG_EXPORT -> {
                if (isAutoLogExportConfigured()) {
                    showAutoLogExportDialog(currentAutoLogExportDirectory())
                } else {
                    openAutoLogExportDirectoryPicker()
                }
            }

            SettingsContract.KEY_VITAL_SIGNS_CONTENT -> {
                showVitalSignsDialog()
            }

            SettingsContract.KEY_CHIP_CONTENT -> {
                showChipContentDialog()
            }

        }
    }


    private fun showVitalSignsDialog() = showOrderedContentDialog(
        SettingsContract.KEY_VITAL_SIGNS_CONTENT,
        SettingsContract.KEY_VITAL_SIGNS_ORDER,
        SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT,
        R.array.vital_signs_content_values,
        R.array.vital_signs_content_chooser_entries,
        VitalSignsOrder.parse(
            mSharedPreferences.getString(
                SettingsContract.KEY_VITAL_SIGNS_ORDER, null
            )
        ),
        allowEmpty = true,
        serialize = VitalSignsOrder::serialize
    )

    private fun showChipContentDialog() = showOrderedContentDialog(
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
            title = getString(SettingsDefinitions.byKey.getValue(contentKey).title),
            message = SettingsDefinitions.byKey.getValue(contentKey).summary?.let(::getString),
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
                resetService()
                refreshSettingsRows()
            })
        rememberPreferenceDialog(contentKey)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (!isAdded) return
        if (key == null) {
            refreshSettingsRows()
            return
        }
        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        try {
            when (key) {
                SettingsContract.KEY_MAX_LOG_AGE -> capAutoLogExportFrequencyToRetention()
                SettingsContract.KEY_ENABLE_LOGGING -> {
                    if (mSharedPreferences.getBoolean(SettingsContract.KEY_ENABLE_LOGGING, true)) {
                        AutoLogExportScheduler.ensureScheduled(requireContext())
                    } else {
                        AutoLogExportScheduler.cancel(requireContext())
                    }
                }

                SettingsContract.KEY_CHARGING_TARGET_MODE, SettingsContract.KEY_CUSTOM_CHARGING_TARGET, SettingsContract.KEY_DISCHARGING_TARGET -> setupTimeEstimatePreferences()

                SettingsContract.KEY_USE_PRIVILEGED_ACCESS -> {
                    syncPrivilegedAccessPreference()
                    setupTimeEstimatePreferences()
                }
            }
            if (key in RESET_SERVICE && !(key == SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER && applyingDetectedBatteryCurrentMultiplier)) resetService()
        } finally {
            if (isResumed && !busy) mSharedPreferences.registerOnSharedPreferenceChangeListener(this)
        }
        refreshSettingsRows()
    }

    private fun requestPrivilegedAccessEnable() {
        if (privilegedAccessRequestInProgress) {
            if (pendingPrivilegedShizukuBinderListener != null && !runCatching(Shizuku::pingBinder).getOrDefault(
                    false
                )
            ) {
                Toast.makeText(requireContext(), R.string.shizuku_not_running, Toast.LENGTH_LONG)
                    .show()
            }
            return
        }
        privilegedAccessRequestInProgress = true
        val generation = ++privilegedAccessRequestGeneration
        val allowed = {
            privilegedAccessRequestInProgress && generation == privilegedAccessRequestGeneration && isAdded
        }

        Thread {
            val rootGranted = RootExecutor.requestAccess(allowed, onGranted = {
                // Retain this verified session when the explicit request becomes monitoring access.
                PrivilegedAccess.setEnabled(true)
                mSharedPreferences.edit {
                    putBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, true)
                }
            })
            mainHandler.post {
                if (!allowed()) return@post
                if (rootGranted) {
                    completePrivilegedAccessEnable()
                } else {
                    requestPrivilegedShizukuPermission()
                }
            }
        }.apply { name = "settings-root-access" }.start()
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
                        if (pendingPrivilegedShizukuBinderListener !== this) return@post
                        Shizuku.removeBinderReceivedListener(this)
                        pendingPrivilegedShizukuBinderListener = null
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
        PrivilegedAccess.setEnabled(
            mSharedPreferences.getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false)
        )
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

    private fun requestBatteryCurrentMultiplierDetection() {
        mSharedPreferences.edit {
            putBoolean(
                SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING, true
            )
        }
        maybeDetectBatteryCurrentMultiplier(showFailureMessage = true)
    }

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
                    refreshSettingsRows()
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

    private fun showCustomChipSwitchingIntervalDialog() {
        val key = SettingsContract.KEY_CHIP_SWITCHING_INTERVAL
        val row = settingsRows.firstOrNull { it.key == key && it.unavailable == null } ?: return
        dialog = SettingsDialog.Number(
            title = row.title,
            message = getString(R.string.pref_chip_switching_interval_custom_message),
            initial = (row.value as? String)?.toIntOrNull()?.coerceIn(1, 3600) ?: 5,
            min = 1,
            max = 3600,
            error = getString(R.string.pref_chip_switching_interval_error),
            onConfirm = { seconds -> changeSetting(key, seconds.toString()) })
        dialogRequest = Bundle().apply { putString("action", "interval") }
    }

    private fun setupTimeEstimatePreferences() {
        val preferences = mSharedPreferences
        val customMode = preferences.getString(
            SettingsContract.KEY_CHARGING_TARGET_MODE,
            SettingsContract.CHARGING_TARGET_MODE_AUTOMATIC
        ) == SettingsContract.CHARGING_TARGET_MODE_CUSTOM
        chargingTargetSummary = null
        timeEstimateJob?.cancel()
        val context = applicationContext
        timeEstimateJob = lifecycleScope.launch {
            val target = withContext(Dispatchers.IO) {
                runCatching {
                    ChargingTargetResolver(preferences) {
                        ChargingLimitContentProvider.read(context)
                    }.resolveChargingTarget()
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
            chargingTargetSummary = summary
            refreshSettingsRows()
        }
    }

    private fun launchDocument(intent: Intent, requestCode: Int) {
        if (pendingDocumentRequest != 0 || busy) return
        runCatching {
            pendingDocumentRequest = requestCode
            documentRequest.launch(intent)
        }.onFailure {
            pendingDocumentRequest = 0
            dialog = SettingsDialog.Message(getString(R.string.diag_value_not_available))
        }
    }

    private fun fileOperation(
        action: SettingsOperationAction, error: Int, operation: (Context) -> Any?
    ) {
        operationModel.start(action, error, operation)
    }

    private fun handleOperationOutcome(outcome: SettingsOperationOutcome) {
        val backupRequest = pendingBackupDialog
        try {
            outcome.result.fold(onSuccess = { value ->
                when (outcome.action) {
                    SettingsOperationAction.ReadSettings -> {
                        @Suppress("UNCHECKED_CAST") val preview =
                            value as Pair<Int, Map<String, Any>>
                        if (preview.first > SettingsBackup.SCHEMA_VERSION) {
                            dialog =
                                SettingsDialog.Confirm(getString(R.string.settings_file_version_warning)) {
                                    doImport(preview)
                                }
                            rememberBackupDialog(backupRequest)
                        } else doImport(preview)
                    }

                    SettingsOperationAction.ReadAlarms -> {
                        val preview = value as AlarmBackupImportPreview
                        if (preview.version > AlarmBackup.SCHEMA_VERSION) {
                            dialog =
                                SettingsDialog.Confirm(getString(R.string.settings_file_version_warning)) {
                                    doAlarmImport(preview.alarms)
                                }
                            rememberBackupDialog(backupRequest)
                        } else doAlarmImport(preview.alarms)
                    }

                    SettingsOperationAction.ReadDeviceData -> showDeviceDataImportDialog(value as PreparedDeviceDataBackup)
                    SettingsOperationAction.ReadCsvLogs -> showCsvLogImportModeDialog(value as Uri)
                    SettingsOperationAction.ReadGeneralBackup -> showGeneralBackupImportDialog(value as GeneralBackupImportPreview)
                    else -> {
                        if (outcome.action == SettingsOperationAction.ImportSettings || outcome.action == SettingsOperationAction.ImportGeneralBackup || outcome.action == SettingsOperationAction.ResetSettings) setPreferences(
                            afterRestore = true
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
                            SettingsOperationAction.ImportDeviceData -> R.string.device_data_imported_dialog
                            SettingsOperationAction.ImportCsvLogs -> R.string.csv_logs_imported_dialog
                            SettingsOperationAction.ExportGeneralBackup -> R.string.general_backup_exported
                            else -> R.string.general_backup_imported
                        }
                        dialog = SettingsDialog.Message(getString(message))
                    }
                }
            }, onFailure = { failure ->
                clearBackupDialog()
                if (failure is kotlinx.coroutines.CancellationException) return@fold
                if (outcome.action == SettingsOperationAction.ResetSettings) setPreferences(
                    afterRestore = true
                )
                dialog = SettingsDialog.Message(backupFailureMessage(outcome.errorMessage, failure))
            })
        } catch (failure: Exception) {
            clearBackupDialog()
            dialog = SettingsDialog.Message(backupFailureMessage(outcome.errorMessage, failure))
        } finally {
            if (isResumed && !busy) mSharedPreferences.registerOnSharedPreferenceChangeListener(this)
        }
    }

    private fun backupFailureMessage(error: Int, failure: Throwable): String {
        if (failure is LogExportRecoveryException) return failure.message ?: getString(error)
        if (failure !is GeneralBackupRestoreException) {
            val storageFailure =
                error != R.string.settings_reset_failed && (failure is SecurityException || failure is android.database.sqlite.SQLiteException || failure is IOException && failure !is java.util.zip.ZipException)
            return getString(if (storageFailure) R.string.history_operation_failed else error)
        }
        setPreferences(afterRestore = true)
        val completed = failure.completedData.joinToString(", ") {
            generalBackupItemLabel(it)
        }
        val failed = failure.failedData.joinToString(", ") {
            generalBackupItemLabel(it)
        }
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

    private fun handleDocumentResult(resultCode: Int, data: Intent?) {
        val requestCode = pendingDocumentRequest
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

            IMPORT_REQUEST, IMPORT_ALARMS_REQUEST, IMPORT_DEVICE_DATA_REQUEST, IMPORT_LOGS_CSV_REQUEST, IMPORT_GENERAL_BACKUP_REQUEST -> {
                clearBackupDialog()
                readBackupImport(Bundle().apply {
                    putString("action", "backup_import")
                    putInt("request", requestCode)
                    putString("cache", "settings-import-${UUID.randomUUID()}.backup")
                    putString("stage", "preview")
                }, uri)
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

            EXPORT_DEVICE_DATA_REQUEST -> {
                val selectedData = pendingDeviceDataExport
                val predictor =
                    if (DeviceDataType.PREDICTOR_DATA in selectedData) predictorExportRequest() else null
                fileOperation(
                    SettingsOperationAction.ExportDeviceData, R.string.history_operation_failed
                ) { context ->
                    val snapshot = predictor?.get(6, TimeUnit.SECONDS)
                    DeviceDataBackup.exportToUri(context, uri, selectedData, snapshot)
                }
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

        }
    }


    private fun autoLogExportSummary(): String? {
        if (!mSharedPreferences.getBoolean(SettingsContract.KEY_ENABLE_LOGGING, true)) {
            return getString(R.string.currently_disabled)
        }
        if (!isAutoLogExportConfigured()) return null

        val frequencyOptions = autoLogExportFrequencyOptions()
        val frequencyValues = frequencyOptions.map { it.preferenceValue }.toTypedArray()
        val modeValues = resources.getStringArray(R.array.auto_log_export_mode_values)
        val formatValues = resources.getStringArray(R.array.auto_log_export_format_values)
        return getString(
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
            ), getString(
                R.string.pref_auto_log_export_path, directoryLabel(currentAutoLogExportDirectory())
            )
        )
    }


    private fun openAutoLogExportDirectoryPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        launchDocument(intent, AUTO_LOG_EXPORT_DIRECTORY_REQUEST)
    }

    private fun handleAutoLogExportDirectoryResult(resultCode: Int, data: Intent?) {
        val draft = dialogRequest?.takeIf { it.getString("action") == "auto_export" }
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            if (draft != null) {
                showAutoLogExportDialog(
                    draft.getString("directory")?.let(Uri::parse),
                    draft.getInt("permission_flags")
                        .takeIf { draft.containsKey("permission_flags") },
                    draft
                )
            } else if (isAutoLogExportConfigured()) {
                showAutoLogExportDialog(currentAutoLogExportDirectory())
            }
            return
        }

        val flags =
            data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        showAutoLogExportDialog(uri, flags, draft)
    }

    private fun showAutoLogExportDialog(
        directory: Uri?, permissionFlags: Int? = null, draft: Bundle? = null
    ) {
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
            title = getString(R.string.pref_auto_log_export_title),
            directoryLabel = directoryLabel(directory),
            frequencyLabels = frequencies.map { it.label },
            frequencyEnabled = frequencies.map { it.preferenceValue in enabled },
            frequencyIndex = draft?.getInt("frequency")?.takeIf { draft.containsKey("frequency") }
                ?: valueIndex(
                    frequencyValues, AutoLogExportFrequency.cappedForRetention(
                        currentAutoLogExportFrequency(), maxLogAgeHours()
                    ).preferenceValue
                ),
            modeLabels = resources.getStringArray(R.array.auto_log_export_mode_entries).toList(),
            modeIndex = draft?.getInt("mode")?.takeIf { draft.containsKey("mode") } ?: valueIndex(
                modes, mSharedPreferences.getString(
                    SettingsContract.KEY_AUTO_LOG_EXPORT_MODE,
                    AutoLogExportMode.NEW_FILE.preferenceValue
                )
            ),
            formatLabels = resources.getStringArray(R.array.auto_log_export_format_entries)
                .toList(),
            formatIndex = draft?.getInt("format")?.takeIf { draft.containsKey("format") }
                ?: valueIndex(
                    formats, mSharedPreferences.getString(
                        SettingsContract.KEY_AUTO_LOG_EXPORT_FORMAT,
                        LogExportFormat.CSV.preferenceValue
                    )
                ),
            configured = wasConfigured,
            onConfirm = { frequency, mode, format ->
                runCatching {
                    val oldDirectory = currentAutoLogExportDirectory()
                    permissionFlags?.let {
                        requireContext().contentResolver.takePersistableUriPermission(
                            directory,
                            it and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
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
                    refreshSettingsRows()
                    when (autoLogExportSetupAction(wasConfigured)) {
                        AutoLogExportSetupAction.START_INITIAL_EXPORT -> AutoLogExportScheduler.startInitialExport(
                            requireContext()
                        )

                        AutoLogExportSetupAction.RESCHEDULE -> AutoLogExportScheduler.reschedule(
                            requireContext()
                        )
                    }
                }.onFailure {
                    dialog = SettingsDialog.Message(getString(R.string.diag_value_not_available))
                }
            },
            onChooseDirectory = { frequency, mode, format ->
                dialogRequest = Bundle(dialogRequest ?: Bundle()).apply {
                    putInt("frequency", frequency)
                    putInt("mode", mode)
                    putInt("format", format)
                }
                openAutoLogExportDirectoryPicker()
            },
            onDisable = ::disableAutoLogExport
        )
        dialogRequest = Bundle().apply {
            putString("action", "auto_export")
            putString("directory", directory.toString())
            permissionFlags?.let { putInt("permission_flags", it) }
            draft?.let {
                for (key in listOf("frequency", "mode", "format")) {
                    if (it.containsKey(key)) putInt(key, it.getInt(key))
                }
            }
        }
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
        refreshSettingsRows()
        AutoLogExportScheduler.cancel(requireContext())
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
        refreshSettingsRows()
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
        dialogRequest = Bundle().apply { putString("action", "device_export") }
    }

    private fun openCsvLogFilePicker() {
        val importIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/*")
        launchDocument(importIntent, IMPORT_LOGS_CSV_REQUEST)
    }

    private fun showCsvLogImportModeDialog(uri: Uri) {
        val request = pendingBackupDialog
        showLogImportModeDialog(
            getString(R.string.csv_logs_import_warning) + "\n\n" + getString(R.string.log_import_mode_message)
        ) { mode ->
            clearBackupDialog(deleteCache = false)
            fileOperation(
                SettingsOperationAction.ImportCsvLogs, R.string.invalid_csv_logs_file
            ) { context ->
                try {
                    CsvLogImporter.withCsvReader(context, uri) { reader ->
                        CsvLogImporter.importFromReader(context, reader, mode)
                    }
                } finally {
                    request?.let(::deleteBackupDialogCache)
                }
            }
        }
        rememberBackupDialog(request, "log_mode")
    }

    private fun showGeneralBackupImportDialog(preview: GeneralBackupImportPreview) {
        runCatching {
            val archive = preview.archive
            val available = preview.available
            val request = pendingBackupDialog
            if (request?.getString("stage") == "log_mode") {
                val selected = request.getStringArrayList("selected").orEmpty()
                    .mapTo(linkedSetOf()) { GeneralBackupDataType.valueOf(it) }
                require(selected.isNotEmpty() && available.containsAll(selected))
                showLogImportModeDialog(getString(R.string.log_import_mode_message)) { mode ->
                    doGeneralBackupImport(archive, selected, mode)
                }
                rememberBackupDialog(request, "log_mode", selected.map { it.name })
                return
            }
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
                supportingText = if (types.any {
                        it == GeneralBackupDataType.LOGS || it == GeneralBackupDataType.PREDICTOR_DATA
                    }) getString(R.string.general_backup_device_data_warning) else null,
                onConfirm = { selected ->
                    val selectedData = selected.mapTo(linkedSetOf()) { types[it] }
                    if (GeneralBackupDataType.LOGS in selectedData) {
                        showLogImportModeDialog(getString(R.string.log_import_mode_message)) { mode ->
                            doGeneralBackupImport(archive, selectedData, mode)
                        }
                        rememberBackupDialog(request, "log_mode", selectedData.map { it.name })
                    } else doGeneralBackupImport(archive, selectedData, LogImportMode.REPLACE)
                })
            rememberBackupDialog(request)
        }.onFailure {
            clearBackupDialog()
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
        return label
    }

    private fun doGeneralBackupImport(
        archive: GeneralBackupArchive,
        selectedData: Set<GeneralBackupDataType>,
        logImportMode: LogImportMode
    ) {
        val request = pendingBackupDialog
        clearBackupDialog(deleteCache = false)
        val settings = mSharedPreferences
        val messenger = serviceMessenger
        settings.unregisterOnSharedPreferenceChangeListener(this)
        fileOperation(
            SettingsOperationAction.ImportGeneralBackup, R.string.invalid_general_backup_file
        ) { context ->
            try {
                GeneralBackup.restore(context, settings, archive, selectedData, logImportMode)
                reloadRestoredData(context, settings, messenger, selectedData)
            } catch (failure: GeneralBackupRestoreException) {
                reloadRestoredData(
                    context, settings, messenger, failure.completedData + failure.failedData
                )
                throw failure
            } finally {
                request?.let(::deleteBackupDialogCache)
            }
        }
    }

    private fun showDeviceDataImportDialog(preview: PreparedDeviceDataBackup) {
        try {
            val availableData = preview.available
            val request = pendingBackupDialog
            if (request?.getString("stage") == "log_mode") {
                val selected = request.getStringArrayList("selected").orEmpty()
                    .mapTo(linkedSetOf()) { DeviceDataType.valueOf(it) }
                require(selected.isNotEmpty() && availableData.containsAll(selected))
                showLogImportModeDialog(getString(R.string.log_import_mode_message)) { mode ->
                    doDeviceDataImport(preview, selected, mode)
                }
                rememberBackupDialog(request, "log_mode", selected.map { it.name })
                return
            }
            if (availableData.isEmpty()) {
                clearBackupDialog()
                dialog = SettingsDialog.Message(getString(R.string.device_data_file_empty))
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
                        doDeviceDataImport(preview, selectedData, logImportMode)
                    }
                    rememberBackupDialog(request, "log_mode", selectedData.map { it.name })
                } else {
                    doDeviceDataImport(preview, selectedData, LogImportMode.REPLACE)
                }
            }
            rememberBackupDialog(request)
        } catch (_: Exception) {
            clearBackupDialog()
            dialog = SettingsDialog.Message(getString(R.string.invalid_device_data_file))
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
        prepared: PreparedDeviceDataBackup,
        selectedData: Set<DeviceDataType>,
        logImportMode: LogImportMode
    ) {
        val request = pendingBackupDialog
        clearBackupDialog(deleteCache = false)
        val settings = mSharedPreferences
        val messenger = serviceMessenger
        fileOperation(
            SettingsOperationAction.ImportDeviceData, R.string.invalid_device_data_file
        ) { context ->
            try {
                DeviceDataBackup.restore(context, prepared, selectedData, logImportMode)
            } catch (failure: GeneralBackupRestoreException) {
                reloadRestoredData(
                    context, settings, messenger, failure.completedData + failure.failedData
                )
                throw failure
            } finally {
                request?.let(::deleteBackupDialogCache)
            }
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
                // This context only reads defaults in the system locale; it does not change the app locale.
                @SuppressLint("AppBundleLocaleChanges") val defaultsContext =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val manager = context.getSystemService(LocaleManager::class.java)
                        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                            setLocales(manager.systemLocales)
                        })
                    } else context
                // Keep commit's Boolean result so failed resets are reported to the user.
                @SuppressLint("UseKtx") val editor = settings.edit()
                SettingsReset.reset(editor)
                SettingsDefinitions.defaultValues(defaultsContext::getString)
                    .forEach { (key, value) ->
                        when (value) {
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            is String -> editor.putString(key, value)
                            else -> error("Unsupported default value for $key")
                        }
                    }
                editor.putBoolean(
                    SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING, true
                )
                check(editor.commit()) { "Could not reset settings" }
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

    private fun doImport(parsed: Pair<Int, Map<String, Any>>) {
        clearBackupDialog()
        val settings = mSharedPreferences
        val messenger = serviceMessenger
        settings.unregisterOnSharedPreferenceChangeListener(this)
        fileOperation(
            SettingsOperationAction.ImportSettings, R.string.invalid_settings_file
        ) { context ->
            // Keep commit's Boolean result so failed imports are reported to the user.
            @SuppressLint("UseKtx") val editor = settings.edit()
            SettingsBackup.restore(editor, parsed)
            if (!editor.commit()) throw IOException("Could not save imported settings")
            reloadService(context, settings, messenger)
        }
    }

    private fun doAlarmImport(alarms: List<AlarmRecord>) {
        clearBackupDialog()
        val settings = mSharedPreferences
        val messenger = serviceMessenger
        fileOperation(
            SettingsOperationAction.ImportAlarms, R.string.invalid_alarms_file
        ) { context ->
            val database = AlarmDatabase(context)
            try {
                AlarmBackup.restore(database, alarms)
            } finally {
                database.close()
            }
            reloadService(context, settings, messenger)
        }
    }


    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun launchChangeAppLanguageIntent(): Boolean {
        try {
            val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS)
            intent.setData(Uri.fromParts("package", requireContext().packageName, null))
            startActivity(intent)
            return true
        } catch (_: Exception) {
        }
        return false
    }

    private fun enableNotifsButtonClick() {
        val context = requireContext()
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = NotificationChannels.CHAN_ID_MAIN
        val opened =
            if (!notificationAvailability.appEnabled || manager.getNotificationChannel(channelId) == null) {
                NotificationSettingsNavigator.openNotifications(context)
            } else {
                NotificationSettingsNavigator.openNotificationChannel(context, channelId)
            }
        if (!opened) context.showToast(R.string.diag_value_not_available)
    }

    private fun isLiveUpdateChannelBlocked(): Boolean =
        requireContext().getSystemService(NotificationManager::class.java)
            .getNotificationChannel(NotificationChannels.CHAN_ID_LIVE_UPDATE)?.importance == NotificationManager.IMPORTANCE_NONE

    private fun readNotificationAvailability(): NotificationAvailability {
        val context = requireContext()
        val manager = context.getSystemService(NotificationManager::class.java)
        return NotificationAvailability(
            appEnabled = manager.areNotificationsEnabled(),
            mainEnabled = manager.getNotificationChannel(NotificationChannels.CHAN_ID_MAIN)?.importance?.let { it > NotificationManager.IMPORTANCE_NONE } == true,
            liveEnabled = BatteryInfoService.supportsLiveUpdates() && BatteryInfoService.isLiveUpdateEnabledInSystem(
                context
            ) && !isLiveUpdateChannelBlocked())
    }

}

internal fun chipSwitchingIntervalChoiceIndex(
    storedSeconds: String?, entryValues: List<String>
): Int {
    val seconds = storedSeconds?.toIntOrNull()?.coerceIn(1, 3600) ?: 5
    return entryValues.indexOf(seconds.toString()).takeIf { it >= 0 }
        ?: entryValues.indexOf("custom")
}
