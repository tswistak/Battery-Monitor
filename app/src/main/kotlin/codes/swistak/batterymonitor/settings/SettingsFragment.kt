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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.alarms.AlarmDatabase
import codes.swistak.batterymonitor.alarms.backup.AlarmBackup
import codes.swistak.batterymonitor.common.RootExecutor
import codes.swistak.batterymonitor.devicebackup.CsvLogImporter
import codes.swistak.batterymonitor.devicebackup.DeviceDataBackup
import codes.swistak.batterymonitor.devicebackup.DeviceDataType
import codes.swistak.batterymonitor.devicebackup.GeneralBackup
import codes.swistak.batterymonitor.devicebackup.GeneralBackupArchive
import codes.swistak.batterymonitor.devicebackup.GeneralBackupDataType
import codes.swistak.batterymonitor.devicebackup.LogImportMode
import codes.swistak.batterymonitor.logs.AutoLogExportFrequency
import codes.swistak.batterymonitor.logs.AutoLogExportMode
import codes.swistak.batterymonitor.logs.AutoLogExportScheduler
import codes.swistak.batterymonitor.logs.AutoLogExportSetupAction
import codes.swistak.batterymonitor.logs.LogExportFormat
import codes.swistak.batterymonitor.logs.autoLogExportSetupAction
import codes.swistak.batterymonitor.monitoring.BatteryCurrent
import codes.swistak.batterymonitor.monitoring.BatteryCurrentMultiplierDetector
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import codes.swistak.batterymonitor.settings.backup.SettingsBackup
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnBinderReceivedListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : PreferenceFragmentCompat(), OnSharedPreferenceChangeListener {
    companion object {
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

        private val BATTERY_CURRENT_DEPENDENTS = arrayOf<String?>(
            SettingsContract.KEY_USE_PRIVILEGED_BATTERY_CURRENT,
            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER,
            SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL,
            SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT
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
            SettingsContract.KEY_PREDICTION_TYPE
        )

        private val RESET_SERVICE = arrayOf<String?>(
            SettingsContract.KEY_CONVERT_F,
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
            SettingsContract.KEY_ENABLE_BATTERY_CURRENT,
            SettingsContract.KEY_USE_PRIVILEGED_BATTERY_CURRENT,
            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER,
            SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT,
            SettingsContract.KEY_UI_COLOR,
            SettingsContract.KEY_PREDICTION_TYPE
        )

        private val RESET_SERVICE_WITH_CANCEL_NOTIFICATION = arrayOf<String?>()

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

    private var prefScreen = 0
    private var batteryCurrentMultiplierDetectionRunning = false
    private var applyingDetectedBatteryCurrentMultiplier = false
    private var pendingShizukuBinderListener: OnBinderReceivedListener? = null
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

    fun setScreen(screen: Int) {
        prefScreen = screen

        if (this::res.isInitialized) setPreferences()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        res = resources

        val pm = preferenceManager
        pm.setSharedPreferencesName(SettingsContract.SETTINGS_FILE)
        pm.setSharedPreferencesMode(Context.MODE_PRIVATE)
        mSharedPreferences = requireNotNull(pm.getSharedPreferences())

        pendingDeviceDataExport =
            savedInstanceState?.getStringArray(STATE_DEVICE_DATA_EXPORT)?.mapNotNull { name ->
                runCatching { DeviceDataType.valueOf(name) }.getOrNull()
            }?.toSet().orEmpty()

        if (prefScreen > 0) setPreferences()
    }

    override fun onSaveInstanceState(outState: Bundle) {
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
            setPreferences()
        }

        mSharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()

        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        pendingShizukuBinderListener?.let(Shizuku::removeBinderReceivedListener)
        pendingShizukuBinderListener = null
        super.onDestroy()
    }

    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun resetService(cancelFirst: Boolean = false) {
        mSharedPreferences.edit().commit()

        val outgoing = Message.obtain()
        outgoing.data = SettingsSnapshot.capture(mSharedPreferences)

        if (cancelFirst) outgoing.what =
            BatteryInfoService.RemoteConnection.SERVICE_CANCEL_NOTIFICATION_AND_RELOAD_SETTINGS
        else outgoing.what = BatteryInfoService.RemoteConnection.SERVICE_RELOAD_SETTINGS

        try {
            serviceMessenger!!.send(outgoing)
        } catch (e: Exception) {
            BatteryInfoService.startForegroundServiceSafely(requireContext(), outgoing.data)
        }
    }

    private fun setPreferences() {
        mNotificationManager =
            requireActivity().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?

        appNotifsEnabled = mNotificationManager!!.areNotificationsEnabled()
        mainNotifsEnabled = getMainNotifsEnabled()
        systemPromotedEnabled = BatteryInfoService.isLiveUpdateEnabledInSystem(requireContext())

        var prefRes = prefScreen

        if ((prefScreen == R.xml.status_bar_icon_pref_screen || prefScreen == R.xml.status_bar_chip_pref_screen || prefScreen == R.xml.notification_pref_screen) && (!appNotifsEnabled || !mainNotifsEnabled)) {
            prefRes = R.xml.main_notifs_disabled_pref_screen
        }

        if (prefRes == R.xml.current_state_pref_screen) {
            prepareBatteryCurrentMultiplierDetection()
        }

        setPreferencesFromResource(prefRes, null)
        mPreferenceScreen = preferenceScreen

        val liveUpdateSupported: Boolean = BatteryInfoService.supportsLiveUpdates()

        if (prefScreen == R.xml.main_pref_screen && !liveUpdateSupported) {
            val p =
                mPreferenceScreen!!.findPreference<Preference?>(SettingsContract.KEY_STATUS_BAR_CHIP_SETTINGS)
            if (p != null) mPreferenceScreen!!.removePreference(p)
        }

        if (prefRes == R.xml.main_notifs_disabled_pref_screen) {
            val prefB =
                mPreferenceScreen!!.findPreference<Preference?>(SettingsContract.KEY_ENABLE_NOTIFS_B)
            val prefS =
                mPreferenceScreen!!.findPreference<Preference?>(SettingsContract.KEY_ENABLE_NOTIFS_SUMMARY)

            if (!appNotifsEnabled) {
                prefS!!.setSummary(R.string.app_notifs_disabled_summary)
                prefB!!.setSummary(R.string.app_notifs_disabled_b)
            } else {
                prefS!!.setSummary(R.string.main_notifs_disabled_summary)
                prefB!!.setSummary(R.string.main_notifs_disabled_b)
            }
        } else if (prefScreen == R.xml.notification_pref_screen) {
            val prefB =
                mPreferenceScreen!!.findPreference<Preference?>(SettingsContract.KEY_ENABLE_NOTIFS_B)
            prefB!!.setSummary(R.string.pref_manage_main_channel)
        } else if (prefScreen == R.xml.status_bar_chip_pref_screen) {
            if (!liveUpdateSupported) {
                val chipCat = mPreferenceScreen!!.findPreference<Preference?>(
                    SettingsContract.KEY_CAT_STATUS_BAR_CHIP
                ) as PreferenceCategory?
                if (chipCat != null) {
                    chipCat.removeAll()
                    chipCat.layoutResource = R.layout.none
                }
            } else {
                setupChipSwitchingIntervalPreference()
                updateChipIntervalVisibility()
            }
        } else if (prefScreen == R.xml.current_state_pref_screen) {
            BatteryCurrent.setContext(requireContext())
            BatteryCurrent.setUsePrivilegedAccess(
                mSharedPreferences.getBoolean(
                    SettingsContract.KEY_USE_PRIVILEGED_BATTERY_CURRENT, false
                )
            )
            BatteryCurrent.setMultiplier(
                mSharedPreferences.getString(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "1")
                    ?.toIntOrNull() ?: 1
            )
            setupBatteryCurrentMultiplierPreference()
            setupBatteryCurrentRefreshIntervalPreference()
        } else if (prefScreen == R.xml.backup_restore_pref_screen) {
            setupAutoLogExportPreference()
        }

        for (i in PARENTS.indices) setEnablednessOfDeps(i)

        for (i in INVERSE_PARENTS.indices) setEnablednessOfInverseDeps(i)

        for (i in LIST_PREFS.indices) updateListPrefSummary(LIST_PREFS[i]!!)

        if (prefScreen == R.xml.current_state_pref_screen && !mSharedPreferences!!.getBoolean(
                SettingsContract.KEY_ENABLE_BATTERY_CURRENT, false
            )
        ) setEnablednessOfBatteryCurrentDeps(false)

        updateConvertFSummary()
        setupLanguage()

        val biServiceIntent = Intent(activity, BatteryInfoService::class.java)
        requireActivity().bindService(biServiceIntent, serviceConnection, 0)
    }

    @Suppress("DEPRECATION")
    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (val key = preference.key) {
            null -> {
                return false
            }

            SettingsContract.KEY_NOTIFICATION_SETTINGS, SettingsContract.KEY_STATUS_BAR_ICON_SETTINGS, SettingsContract.KEY_STATUS_BAR_CHIP_SETTINGS, SettingsContract.KEY_CURRENT_STATE_SETTINGS, SettingsContract.KEY_OTHER_SETTINGS, SettingsContract.KEY_BACKUP_RESTORE_SETTINGS -> {
                val comp = ComponentName(
                    requireActivity().packageName, SettingsActivity::class.java.getName()
                )
                startActivity(
                    Intent().setComponent(comp).putExtra(SettingsContract.EXTRA_SCREEN, key)
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
                startActivityForResult(exportIntent, EXPORT_REQUEST)
                return true
            }

            SettingsContract.KEY_IMPORT_SETTINGS -> {
                val importIntent =
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                startActivityForResult(importIntent, IMPORT_REQUEST)
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
                startActivityForResult(exportIntent, EXPORT_ALARMS_REQUEST)
                return true
            }

            SettingsContract.KEY_IMPORT_ALARMS -> {
                val importIntent =
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                startActivityForResult(importIntent, IMPORT_ALARMS_REQUEST)
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
                startActivityForResult(importIntent, IMPORT_DEVICE_DATA_REQUEST)
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
                startActivityForResult(exportIntent, EXPORT_GENERAL_BACKUP_REQUEST)
                return true
            }

            SettingsContract.KEY_IMPORT_GENERAL_BACKUP -> {
                val importIntent =
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/zip")
                startActivityForResult(importIntent, IMPORT_GENERAL_BACKUP_REQUEST)
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

            else -> return key == SettingsContract.KEY_PLUGIN_SETTINGS
        }
    }

    private data class VitalSignDialogItem(
        val value: String, val label: CharSequence, var isSelected: Boolean
    )

    private class VitalSignViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.vital_sign_checkbox)
        val dragHandle: ImageView = view.findViewById(R.id.vital_sign_drag_handle)
    }

    private class VitalSignsAdapter(
        private val items: MutableList<VitalSignDialogItem>
    ) : RecyclerView.Adapter<VitalSignViewHolder>() {
        var startDrag: ((RecyclerView.ViewHolder) -> Unit)? = null
        var selectionChanged: (() -> Unit)? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VitalSignViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                R.layout.vital_sign_dialog_item, parent, false
            )
            return VitalSignViewHolder(view)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VitalSignViewHolder, position: Int) {
            val item = items[position]
            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.text = item.label
            holder.checkBox.isChecked = item.isSelected
            holder.checkBox.setOnCheckedChangeListener { _, checked ->
                item.isSelected = checked
                selectionChanged?.invoke()
            }
            holder.dragHandle.contentDescription = holder.itemView.context.getString(
                R.string.pref_vital_signs_reorder_handle, item.label
            )
            holder.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    startDrag?.invoke(holder)
                    true
                } else {
                    false
                }
            }
        }

        override fun getItemCount(): Int = items.size

        fun move(fromPosition: Int, toPosition: Int) {
            if (fromPosition == toPosition) return
            val item = items.removeAt(fromPosition)
            items.add(toPosition, item)
            notifyItemMoved(fromPosition, toPosition)
        }
    }

    private fun showVitalSignsDialog(preference: Preference) {
        val context = context ?: return
        val selectedValues = mSharedPreferences.getStringSet(
            SettingsContract.KEY_VITAL_SIGNS_CONTENT, SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT
        ) ?: SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT
        val labelsByValue = resources.getStringArray(
            R.array.vital_signs_content_values
        ).zip(resources.getTextArray(R.array.vital_signs_content_entries)).toMap()
        val items = VitalSignsOrder.parse(
            mSharedPreferences.getString(SettingsContract.KEY_VITAL_SIGNS_ORDER, null)
        ).mapNotNullTo(mutableListOf()) { value ->
            labelsByValue[value]?.let { label ->
                VitalSignDialogItem(value, label, value in selectedValues)
            }
        }

        val rowPadding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics
        ).toInt()
        val adapter = VitalSignsAdapter(items)
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            setPadding(rowPadding, 0, rowPadding, 0)
            clipToPadding = false
        }
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) {
                    return false
                }
                adapter.move(fromPosition, toPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean = false
        }).apply { attachToRecyclerView(list) }
        adapter.startDrag = itemTouchHelper::startDrag

        AlertDialog.Builder(context).setTitle(preference.title).setMessage(preference.summary)
            .setView(list).setPositiveButton(R.string.okay) { _, _ ->
                mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
                try {
                    mSharedPreferences.edit {
                        putStringSet(
                            SettingsContract.KEY_VITAL_SIGNS_CONTENT,
                            items.filter { it.isSelected }.mapTo(linkedSetOf()) {
                                it.value
                            })
                        putString(
                            SettingsContract.KEY_VITAL_SIGNS_ORDER,
                            VitalSignsOrder.serialize(items.map { it.value })
                        )
                    }
                } finally {
                    mSharedPreferences.registerOnSharedPreferenceChangeListener(this)
                }
                resetService()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showChipContentDialog(preference: Preference) {
        val context = context ?: return
        val selectedValues = mSharedPreferences.getStringSet(
            SettingsContract.KEY_CHIP_CONTENT, SettingsContract.DEFAULT_CHIP_CONTENT
        ) ?: SettingsContract.DEFAULT_CHIP_CONTENT
        val labelsByValue = resources.getStringArray(
            R.array.chip_content_values
        ).zip(resources.getTextArray(R.array.chip_content_entries)).toMap()
        val items = ChipContentOrder.parse(
            mSharedPreferences.getString(SettingsContract.KEY_CHIP_CONTENT_ORDER, null)
        ).mapNotNullTo(mutableListOf()) { value ->
            labelsByValue[value]?.let { label ->
                VitalSignDialogItem(value, label, value in selectedValues)
            }
        }

        val rowPadding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics
        ).toInt()
        val adapter = VitalSignsAdapter(items)
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            setPadding(rowPadding, 0, rowPadding, 0)
            clipToPadding = false
        }
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) {
                    return false
                }
                adapter.move(fromPosition, toPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean = false
        }).apply { attachToRecyclerView(list) }
        adapter.startDrag = itemTouchHelper::startDrag

        val dialog =
            AlertDialog.Builder(context).setTitle(preference.title).setMessage(preference.summary)
                .setView(list).setPositiveButton(R.string.okay) { _, _ ->
                    mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
                    try {
                        mSharedPreferences.edit {
                            putStringSet(
                                SettingsContract.KEY_CHIP_CONTENT,
                                items.filter { it.isSelected }.mapTo(linkedSetOf()) { it.value })
                            putString(
                                SettingsContract.KEY_CHIP_CONTENT_ORDER,
                                ChipContentOrder.serialize(items.map { it.value })
                            )
                        }
                    } finally {
                        mSharedPreferences.registerOnSharedPreferenceChangeListener(this)
                    }
                    updateChipIntervalVisibility()
                    resetService()
                }.setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val updatePositiveButton = { positiveButton.isEnabled = items.any { it.isSelected } }
            adapter.selectionChanged = updatePositiveButton
            updatePositiveButton()
        }
        dialog.show()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == null) return
        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

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

        if (key == SettingsContract.KEY_CONVERT_F) {
            updateConvertFSummary()
        }

        if (key == SettingsContract.KEY_ENABLE_BATTERY_CURRENT) {
            val enabled = mSharedPreferences.getBoolean(
                SettingsContract.KEY_ENABLE_BATTERY_CURRENT, false
            )
            if (enabled) {
                setEnablednessOfBatteryCurrentDeps(true)
                maybeDetectBatteryCurrentMultiplier(showFailureMessage = true)
            }

            for (i in PARENTS.indices) setEnablednessOfDeps(i)

            if (!mSharedPreferences.getBoolean(
                    SettingsContract.KEY_ENABLE_BATTERY_CURRENT, false
                )
            ) setEnablednessOfBatteryCurrentDeps(false)
        }

        if (key == SettingsContract.KEY_ENABLE_ADVANCED_STATS && mSharedPreferences.getBoolean(
                SettingsContract.KEY_ENABLE_ADVANCED_STATS, false
            )
        ) maybeRequestShizukuPermission()

        if (key == SettingsContract.KEY_USE_PRIVILEGED_BATTERY_CURRENT) {
            val enabled = mSharedPreferences.getBoolean(
                SettingsContract.KEY_USE_PRIVILEGED_BATTERY_CURRENT, false
            )
            BatteryCurrent.setUsePrivilegedAccess(enabled)
            if (enabled) maybeRequestShizukuPermission()
        }

        if (key == SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL) {
            updateBatteryCurrentRefreshIntervalSummary()
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
    }

    private fun maybeRequestShizukuPermission() {
        Thread(Runnable {
            val rootAvailable = RootExecutor().run("id") != null
            if (rootAvailable) return@Runnable
            mainHandler.post { this.requestShizukuPermissionIfNeeded() }
        }).start()
    }

    private fun requestShizukuPermissionIfNeeded() {
        if (!isAdded || activity == null) return

        if (Shizuku.pingBinder()) {
            requestShizukuPermissionFromBinder()
            return
        }

        if (pendingShizukuBinderListener != null) return
        Toast.makeText(requireContext(), R.string.shizuku_not_running, Toast.LENGTH_LONG).show()
        val listener = object : OnBinderReceivedListener {
            override fun onBinderReceived() {
                mainHandler.post {
                    Shizuku.removeBinderReceivedListener(this)
                    if (pendingShizukuBinderListener === this) pendingShizukuBinderListener = null
                    requestShizukuPermissionFromBinder()
                }
            }
        }
        pendingShizukuBinderListener = listener
        Shizuku.addBinderReceivedListenerSticky(listener)
    }

    private fun requestShizukuPermissionFromBinder() {
        if (!isAdded || activity == null || Shizuku.isPreV11()) return

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            Toast.makeText(
                requireContext(), R.string.shizuku_permission_denied_permanently, Toast.LENGTH_LONG
            ).show()
            return
        }

        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
    }

    private fun updateConvertFSummary() {
        val pref = mPreferenceScreen!!.findPreference<Preference?>(SettingsContract.KEY_CONVERT_F)
            ?: return

        pref.setSummary(
            res.getString(R.string.currently_using) + " " + (if (mSharedPreferences.getBoolean(
                    SettingsContract.KEY_CONVERT_F,
                    res.getBoolean(R.bool.default_convert_to_fahrenheit)
                )
            ) res.getString(R.string.fahrenheit) else res.getString(R.string.celsius))
        )
    }

    private fun setEnablednessOfDeps(index: Int) {
        for (i in DEPENDENTS[index]!!.indices) {
            val dependent =
                mPreferenceScreen!!.findPreference<Preference?>(DEPENDENTS[index]!![i]!!) ?: return

            dependent.isEnabled = mSharedPreferences!!.getBoolean(PARENTS[index], false)

            updateListPrefSummary(DEPENDENTS[index]!![i]!!)
        }
    }

    private fun setEnablednessOfBatteryCurrentDeps(enabled: Boolean) {
        for (i in BATTERY_CURRENT_DEPENDENTS.indices) {
            val dependent =
                mPreferenceScreen!!.findPreference<Preference?>(BATTERY_CURRENT_DEPENDENTS[i]!!)
                    ?: return

            dependent.isEnabled = enabled
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
        val preferAverage = mSharedPreferences.getBoolean(
            SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT, false
        )
        batteryCurrentMultiplierDetectionRunning = true

        Thread {
            val rawCurrent = runCatching {
                if (preferAverage) {
                    BatteryCurrent.readForMultiplierDetection(average = true)
                        ?: BatteryCurrent.readForMultiplierDetection(average = false)
                } else {
                    BatteryCurrent.readForMultiplierDetection(average = false)
                }
            }.getOrNull()
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
                        SettingsContract.KEY_ENABLE_BATTERY_CURRENT, false
                    ) || !mSharedPreferences.getBoolean(
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
        }.apply { name = "battery-current-multiplier-detection" }.start()
    }

    private fun setupBatteryCurrentRefreshIntervalPreference() {
        val preference = mPreferenceScreen!!.findPreference<ListPreference>(
            SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL
        ) ?: return
        updateBatteryCurrentRefreshIntervalSummary()
        preference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                if (newValue == "custom") {
                    showCustomBatteryCurrentRefreshIntervalDialog(preference)
                    false
                } else {
                    true
                }
            }
    }

    private fun updateBatteryCurrentRefreshIntervalSummary() {
        val preference = mPreferenceScreen!!.findPreference<ListPreference>(
            SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL
        ) ?: return
        val seconds = mSharedPreferences.getString(
            SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL, "2"
        )?.toIntOrNull()?.coerceIn(1, 3600) ?: 2
        val entry = preference.entries.getOrNull(
            preference.findIndexOfValue(seconds.toString())
        )
        val value = entry ?: getString(
            R.string.pref_battery_current_refresh_interval_custom_summary, seconds
        )
        preference.summary = getString(R.string.currently_set_to) + value
    }

    private fun showCustomBatteryCurrentRefreshIntervalDialog(preference: ListPreference) {
        val context = context ?: return
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(
                mSharedPreferences.getString(
                    SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL, "2"
                )
            )
            selectAll()
        }
        val container = FrameLayout(context)
        val margin = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics
        ).toInt()
        container.addView(
            input, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = margin
                rightMargin = margin
            })

        val dialog = AlertDialog.Builder(context).setTitle(preference.title)
            .setMessage(R.string.pref_battery_current_refresh_interval_custom_message)
            .setView(container).setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val seconds = input.text.toString().toIntOrNull()
                if (seconds == null || seconds !in 1..3600) {
                    input.error = getString(
                        R.string.pref_battery_current_refresh_interval_error
                    )
                    return@setOnClickListener
                }
                mSharedPreferences.edit {
                    putString(
                        SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL, seconds.toString()
                    )
                }
                preference.value = seconds.toString()
                updateBatteryCurrentRefreshIntervalSummary()
                dialog.dismiss()
            }
        }
        dialog.show()
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
        val context = context ?: return
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(
                mSharedPreferences.getString(
                    SettingsContract.KEY_CHIP_SWITCHING_INTERVAL, "5"
                )
            )
            selectAll()
        }
        val container = FrameLayout(context)
        val margin = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics
        ).toInt()
        container.addView(
            input, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = margin
                rightMargin = margin
            })

        val dialog = AlertDialog.Builder(context).setTitle(preference.title)
            .setMessage(R.string.pref_chip_switching_interval_custom_message).setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val seconds = input.text.toString().toIntOrNull()
                if (seconds == null || seconds !in 1..3600) {
                    input.error = getString(R.string.pref_chip_switching_interval_error)
                    return@setOnClickListener
                }
                mSharedPreferences.edit {
                    putString(SettingsContract.KEY_CHIP_SWITCHING_INTERVAL, seconds.toString())
                }
                preference.value = seconds.toString()
                updateChipSwitchingIntervalSummary()
                dialog.dismiss()
            }
        }
        dialog.show()
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

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == AUTO_LOG_EXPORT_DIRECTORY_REQUEST) {
            handleAutoLogExportDirectoryResult(resultCode, data)
            return
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.data == null) return

        val uri = data.data ?: return
        try {
            if (requestCode == EXPORT_REQUEST) {
                SettingsBackup.writeToUri(
                    requireContext(), uri, SettingsBackup.exportToJson(mSharedPreferences)
                )
                Toast.makeText(activity, R.string.settings_exported, Toast.LENGTH_SHORT).show()
            } else if (requestCode == IMPORT_REQUEST) {
                val json = SettingsBackup.readFromUri(requireContext(), uri) ?: return

                val fileVersion = SettingsBackup.getSchemaVersion(json)
                if (fileVersion > SettingsBackup.SCHEMA_VERSION) {
                    AlertDialog.Builder(requireActivity())
                        .setMessage(R.string.settings_file_version_warning).setPositiveButton(
                            R.string.yes
                        ) { _: DialogInterface?, _: Int ->
                            doImport(json)
                        }.setNegativeButton(R.string.cancel, null).show()
                } else {
                    doImport(json)
                }
            } else if (requestCode == EXPORT_ALARMS_REQUEST) {
                val database = AlarmDatabase(requireContext())
                try {
                    AlarmBackup.writeToUri(
                        requireContext(), uri, AlarmBackup.exportToJson(database)
                    )
                } finally {
                    database.close()
                }
                Toast.makeText(activity, R.string.alarms_exported, Toast.LENGTH_SHORT).show()
            } else if (requestCode == IMPORT_ALARMS_REQUEST) {
                val json = AlarmBackup.readFromUri(requireContext(), uri) ?: return
                val fileVersion = AlarmBackup.getSchemaVersion(json)
                if (fileVersion > AlarmBackup.SCHEMA_VERSION) {
                    AlertDialog.Builder(requireActivity())
                        .setMessage(R.string.settings_file_version_warning).setPositiveButton(
                            R.string.yes
                        ) { _: DialogInterface?, _: Int ->
                            doAlarmImport(json)
                        }.setNegativeButton(R.string.cancel, null).show()
                } else {
                    doAlarmImport(json)
                }
            } else if (requestCode == EXPORT_DEVICE_DATA_REQUEST) {
                DeviceDataBackup.writeToUri(
                    requireContext(),
                    uri,
                    DeviceDataBackup.exportToJson(requireContext(), pendingDeviceDataExport)
                )
                pendingDeviceDataExport = emptySet()
                Toast.makeText(activity, R.string.device_data_exported, Toast.LENGTH_SHORT).show()
            } else if (requestCode == IMPORT_DEVICE_DATA_REQUEST) {
                val json = DeviceDataBackup.readFromUri(requireContext(), uri) ?: return
                showDeviceDataImportDialog(json)
            } else if (requestCode == IMPORT_LOGS_CSV_REQUEST) {
                val csv = CsvLogImporter.readFromUri(requireContext(), uri) ?: return
                showCsvLogImportModeDialog(csv)
            } else if (requestCode == EXPORT_GENERAL_BACKUP_REQUEST) {
                GeneralBackup.exportToUri(requireContext(), uri, mSharedPreferences)
                Toast.makeText(activity, R.string.general_backup_exported, Toast.LENGTH_SHORT)
                    .show()
            } else if (requestCode == IMPORT_GENERAL_BACKUP_REQUEST) {
                val archive = GeneralBackup.readFromUri(requireContext(), uri) ?: return
                showGeneralBackupImportDialog(archive)
            }
        } catch (e: Exception) {
            val message = when (requestCode) {
                EXPORT_ALARMS_REQUEST, IMPORT_ALARMS_REQUEST -> R.string.invalid_alarms_file
                EXPORT_DEVICE_DATA_REQUEST, IMPORT_DEVICE_DATA_REQUEST -> R.string.invalid_device_data_file

                IMPORT_LOGS_CSV_REQUEST -> R.string.invalid_csv_logs_file

                EXPORT_GENERAL_BACKUP_REQUEST, IMPORT_GENERAL_BACKUP_REQUEST -> R.string.invalid_general_backup_file

                else -> R.string.invalid_settings_file
            }
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAutoLogExportPreference() {
        if (prefScreen != R.xml.backup_restore_pref_screen) return
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
        startActivityForResult(intent, AUTO_LOG_EXPORT_DIRECTORY_REQUEST)
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
        val view = layoutInflater.inflate(R.layout.auto_log_export_dialog, null)
        val frequencySpinner = view.findViewById<Spinner>(R.id.auto_log_export_frequency)
        val modeSpinner = view.findViewById<Spinner>(R.id.auto_log_export_mode)
        val formatSpinner = view.findViewById<Spinner>(R.id.auto_log_export_format)
        val frequencyOptions = autoLogExportFrequencyOptions()
        val frequencyValues = frequencyOptions.map { it.preferenceValue }.toTypedArray()
        val modeValues = resources.getStringArray(R.array.auto_log_export_mode_values)
        val formatValues = resources.getStringArray(R.array.auto_log_export_format_values)
        val maxLogAgeHours = maxLogAgeHours()
        val enabledFrequencyValues = AutoLogExportFrequency.enabledForRetention(maxLogAgeHours)
            .map(AutoLogExportFrequency::preferenceValue).toSet()
        val frequencyEnabled = frequencyOptions.map { it.preferenceValue in enabledFrequencyValues }
        frequencySpinner.adapter = EnabledItemsArrayAdapter(
            requireContext(), frequencyOptions.map { it.label }, frequencyEnabled
        )
        val selectedFrequency = AutoLogExportFrequency.cappedForRetention(
            currentAutoLogExportFrequency(), maxLogAgeHours
        )
        frequencySpinner.setSelection(
            valueIndex(frequencyValues, selectedFrequency.preferenceValue)
        )
        modeSpinner.setSelection(
            valueIndex(
                modeValues, mSharedPreferences.getString(
                    SettingsContract.KEY_AUTO_LOG_EXPORT_MODE,
                    AutoLogExportMode.NEW_FILE.preferenceValue
                )
            )
        )
        formatSpinner.setSelection(
            valueIndex(
                formatValues, mSharedPreferences.getString(
                    SettingsContract.KEY_AUTO_LOG_EXPORT_FORMAT, LogExportFormat.CSV.preferenceValue
                )
            )
        )

        val dialog = AlertDialog.Builder(requireContext()).setTitle(
            if (wasConfigured) R.string.pref_edit_auto_log_export
            else R.string.pref_set_auto_log_export
        ).setView(view).setPositiveButton(R.string.okay) { _, _ ->
            val oldDirectory = currentAutoLogExportDirectory()
            permissionFlags?.let {
                requireContext().contentResolver.takePersistableUriPermission(directory, it)
            }
            mSharedPreferences.edit {
                putString(
                    SettingsContract.KEY_AUTO_LOG_EXPORT_FREQUENCY,
                    frequencyValues[frequencySpinner.selectedItemPosition]
                )
                putString(
                    SettingsContract.KEY_AUTO_LOG_EXPORT_MODE,
                    modeValues[modeSpinner.selectedItemPosition]
                )
                putString(
                    SettingsContract.KEY_AUTO_LOG_EXPORT_FORMAT,
                    formatValues[formatSpinner.selectedItemPosition]
                )
                putString(SettingsContract.KEY_AUTO_LOG_EXPORT_DIRECTORY, directory.toString())
                if (oldDirectory != directory) {
                    remove(SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME)
                }
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
        }.setNeutralButton(R.string.pref_auto_log_export_directory) { _, _ ->
            openAutoLogExportDirectoryPicker()
        }.setNegativeButton(
            if (wasConfigured) R.string.pref_disable_auto_log_export else R.string.cancel
        ) { _, _ ->
            if (wasConfigured) disableAutoLogExport()
        }.create()
        dialog.show()
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

    private class EnabledItemsArrayAdapter(
        context: Context, labels: List<String>, private val enabledItems: List<Boolean>
    ) : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, labels) {
        init {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        override fun areAllItemsEnabled(): Boolean = enabledItems.all { it }

        override fun isEnabled(position: Int): Boolean = enabledItems[position]

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getDropDownView(position, convertView, parent).also { view ->
                view.isEnabled = enabledItems[position]
                view.alpha = if (enabledItems[position]) 1f else 0.38f
            }
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
        runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull()
            ?: it.lastPathSegment.orEmpty()
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
            positiveLabel = R.string.pref_export_device_data,
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
            startActivityForResult(exportIntent, EXPORT_DEVICE_DATA_REQUEST)
        }
    }

    private fun openCsvLogFilePicker() {
        val importIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/*")
        startActivityForResult(importIntent, IMPORT_LOGS_CSV_REQUEST)
    }

    private fun showCsvLogImportModeDialog(csv: String) {
        showLogImportModeDialog(
            getString(R.string.csv_logs_import_warning) + "\n\n" + getString(R.string.log_import_mode_message)
        ) { logImportMode ->
            try {
                CsvLogImporter.importFromCsv(requireContext(), csv, logImportMode)
                Toast.makeText(activity, R.string.csv_logs_imported, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(activity, R.string.invalid_csv_logs_file, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showGeneralBackupImportDialog(archive: GeneralBackupArchive) {
        try {
            val availableData = GeneralBackup.getAvailableData(archive)
            val dataTypes =
                GeneralBackupDataType.entries.filter { it in availableData }.toTypedArray()
            val warning = buildString {
                append(getString(R.string.general_backup_restore_message))
                if (GeneralBackup.containsNewerSchema(archive)) {
                    append("\n\n")
                    append(getString(R.string.settings_file_version_warning))
                }
            }
            val view = layoutInflater.inflate(R.layout.device_data_selection_dialog, null)
            view.findViewById<TextView>(R.id.device_data_warning).text = warning
            val options = view.findViewById<LinearLayout>(R.id.device_data_options)
            val checkBoxes = dataTypes.map { type ->
                CheckBox(requireContext()).apply {
                    text = generalBackupItemLabel(type)
                    isChecked = true
                    options.addView(this)
                }
            }
            val dialog =
                AlertDialog.Builder(requireActivity()).setTitle(R.string.pref_import_general_backup)
                    .setView(view).setPositiveButton(R.string.pref_import_general_backup) { _, _ ->
                        val selectedData = dataTypes.filterIndexed { index, _ ->
                            checkBoxes[index].isChecked
                        }.toSet()
                        if (GeneralBackupDataType.LOGS in selectedData) {
                            showLogImportModeDialog(getString(R.string.log_import_mode_message)) { logImportMode ->
                                doGeneralBackupImport(archive, selectedData, logImportMode)
                            }
                        } else {
                            doGeneralBackupImport(archive, selectedData, LogImportMode.REPLACE)
                        }
                    }.setNegativeButton(R.string.cancel, null).create()
            for (checkBox in checkBoxes) {
                checkBox.setOnCheckedChangeListener { _, _ ->
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled =
                        checkBoxes.any(CheckBox::isChecked)
                }
            }
            dialog.setOnShowListener {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled =
                    checkBoxes.any(CheckBox::isChecked)
            }
            dialog.show()
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.invalid_general_backup_file, Toast.LENGTH_SHORT)
                .show()
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
        try {
            GeneralBackup.restore(
                requireContext(), mSharedPreferences, archive, selectedData, logImportMode
            )
            if (GeneralBackupDataType.SETTINGS in selectedData) setPreferences()
            when {
                GeneralBackupDataType.PREDICTOR_DATA in selectedData -> reloadDeviceData()
                GeneralBackupDataType.SETTINGS in selectedData -> resetService()
            }
            Toast.makeText(activity, R.string.general_backup_imported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.invalid_general_backup_file, Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun showDeviceDataImportDialog(json: String) {
        try {
            val availableData = DeviceDataBackup.getAvailableData(json)
            if (availableData.isEmpty()) {
                Toast.makeText(activity, R.string.device_data_file_empty, Toast.LENGTH_SHORT).show()
                return
            }
            val dataTypes = DeviceDataType.entries.filter { it in availableData }.toTypedArray()
            val version = DeviceDataBackup.getSchemaVersion(json)
            val warning = if (version > DeviceDataBackup.SCHEMA_VERSION) {
                getString(R.string.settings_file_version_warning) + "\n\n" + getString(R.string.device_data_backup_warning)
            } else {
                getString(R.string.device_data_backup_warning)
            }
            showDeviceDataSelectionDialog(
                title = R.string.pref_import_device_data,
                positiveLabel = R.string.pref_import_device_data,
                dataTypes = dataTypes,
                warning = warning
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
        positiveLabel: Int,
        dataTypes: Array<DeviceDataType>,
        warning: String,
        onConfirm: (Set<DeviceDataType>) -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.device_data_selection_dialog, null)
        view.findViewById<TextView>(R.id.device_data_warning).text = warning
        val options = view.findViewById<LinearLayout>(R.id.device_data_options)
        val checkBoxes = dataTypes.map { type ->
            CheckBox(requireContext()).apply {
                text = getString(
                    when (type) {
                        DeviceDataType.LOGS -> R.string.device_data_logs
                        DeviceDataType.PREDICTOR_DATA -> R.string.device_data_predictor
                    }
                )
                isChecked = true
                options.addView(this)
            }
        }
        val dialog = AlertDialog.Builder(requireActivity()).setTitle(title).setView(view)
            .setPositiveButton(positiveLabel) { _, _ ->
                onConfirm(
                    dataTypes.filterIndexed { index, _ -> checkBoxes[index].isChecked }.toSet()
                )
            }.setNegativeButton(R.string.cancel, null).create()
        for (checkBox in checkBoxes) {
            checkBox.setOnCheckedChangeListener { _, _ ->
                dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled =
                    checkBoxes.any(CheckBox::isChecked)
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled =
                checkBoxes.any(CheckBox::isChecked)
        }
        dialog.show()
    }

    private fun showLogImportModeDialog(
        message: String, onConfirm: (LogImportMode) -> Unit
    ) {
        AlertDialog.Builder(requireActivity()).setTitle(R.string.log_import_mode_title)
            .setMessage(message).setPositiveButton(R.string.log_import_add) { _, _ ->
                onConfirm(LogImportMode.ADD)
            }.setNeutralButton(R.string.log_import_replace) { _, _ ->
                onConfirm(LogImportMode.REPLACE)
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun doDeviceDataImport(
        json: String, selectedData: Set<DeviceDataType>, logImportMode: LogImportMode
    ) {
        try {
            DeviceDataBackup.importFromJson(
                requireContext(), json, selectedData, logImportMode
            )
            reloadDeviceData()
            Toast.makeText(activity, R.string.device_data_imported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.invalid_device_data_file, Toast.LENGTH_SHORT).show()
        }
    }

    private fun reloadDeviceData() {
        val outgoing = Message.obtain()
        outgoing.what = BatteryInfoService.RemoteConnection.SERVICE_RELOAD_DEVICE_DATA
        outgoing.data = SettingsSnapshot.capture(mSharedPreferences)
        try {
            serviceMessenger!!.send(outgoing)
        } catch (e: Exception) {
            BatteryInfoService.startForegroundServiceSafely(requireContext(), outgoing.data)
        }
    }

    private fun doImport(json: String) {
        try {
            mSharedPreferences.edit {
                SettingsBackup.importFromJson(this, json)
            }
            setPreferences()
            resetService()
            Toast.makeText(activity, R.string.settings_imported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.invalid_settings_file, Toast.LENGTH_SHORT).show()
        }
    }

    private fun doAlarmImport(json: String) {
        val database = AlarmDatabase(requireContext())
        try {
            AlarmBackup.importFromJson(database, json)
            Toast.makeText(activity, R.string.alarms_imported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.invalid_alarms_file, Toast.LENGTH_SHORT).show()
        } finally {
            database.close()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            category.isVisible = true
            pref.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { _: Preference? -> this.launchChangeAppLanguageIntent() }
        } else {
            category.isVisible = false
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
        val intent: Intent?
        if (!appNotifsEnabled || mainChan == null) {
            intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        } else {
            intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            intent.putExtra(Settings.EXTRA_CHANNEL_ID, mainChan!!.id)
        }

        intent.putExtra(Settings.EXTRA_APP_PACKAGE, requireActivity().packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        startActivity(intent)
    }

    private fun getMainNotifsEnabled(): Boolean {
        mainChan = mNotificationManager!!.getNotificationChannel(BatteryInfoService.CHAN_ID_MAIN)
        return mainChan != null && mainChan!!.importance > 0
    }
}
