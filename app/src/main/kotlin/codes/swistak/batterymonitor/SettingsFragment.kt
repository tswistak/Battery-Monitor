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
package codes.swistak.batterymonitor

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
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
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import codes.swistak.batterymonitor.AdvancedBatteryStatsCollector.RootExecutor
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnBinderReceivedListener
import rikka.shizuku.ShizukuProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : PreferenceFragmentCompat(), OnSharedPreferenceChangeListener {
    companion object {
        const val SETTINGS_FILE: String = "codes.swistak.batterymonitor_preferences"
        const val SP_SERVICE_FILE: String = "sp_store"
        const val SP_MAIN_FILE: String = "sp_store_main"

        const val KEY_NOTIFICATION_SETTINGS: String = "notification_settings"
        const val KEY_STATUS_BAR_ICON_SETTINGS: String = "status_bar_icon_settings"
        const val KEY_STATUS_BAR_CHIP_SETTINGS: String = "status_bar_chip_settings"
        const val KEY_CURRENT_HACK_SETTINGS: String = "current_hack_settings"
        const val KEY_ALARMS_SETTINGS: String = "alarms_settings"
        const val KEY_ALARM_EDIT_SETTINGS: String = "alarm_edit_settings"
        const val KEY_ADVANCED_INFO_HELP: String = "advanced_info_help"
        const val KEY_OTHER_SETTINGS: String = "other_settings"
        const val KEY_ENABLE_LOGGING: String = SettingsKeys.KEY_ENABLE_LOGGING
        const val KEY_CHANGE_APP_LANGUAGE_HOLDER: String = "change_app_language_holder"
        const val KEY_CHANGE_APP_LANGUAGE: String = "change_app_language"
        const val KEY_MAX_LOG_AGE: String = SettingsKeys.KEY_MAX_LOG_AGE
        const val KEY_ICON_CONTENT: String = SettingsKeys.KEY_ICON_CONTENT
        const val KEY_CONVERT_F: String = SettingsKeys.KEY_CONVERT_F
        const val KEY_NOTIFY_STATUS_DURATION: String = SettingsKeys.KEY_NOTIFY_STATUS_DURATION
        const val KEY_AUTOSTART: String = SettingsKeys.KEY_AUTOSTART
        const val KEY_PREDICTION_TYPE: String = SettingsKeys.KEY_PREDICTION_TYPE
        const val KEY_STATUS_DUR_EST: String = SettingsKeys.KEY_STATUS_DUR_EST
        const val KEY_PLUGIN_SETTINGS: String = "plugin_settings"
        const val KEY_INDICATE_CHARGING: String = SettingsKeys.KEY_INDICATE_CHARGING
        const val KEY_SHOW_ICON_UNIT: String = SettingsKeys.KEY_SHOW_ICON_UNIT
        const val KEY_CAT_STATUS_BAR_CHIP: String = "category_status_bar_chip"
        const val KEY_CHIP_CONTENT: String = SettingsKeys.KEY_CHIP_CONTENT
        const val KEY_CHIP_SWITCHING_INTERVAL: String = SettingsKeys.KEY_CHIP_SWITCHING_INTERVAL
        const val KEY_CHIP_INDICATE_CHARGING: String = SettingsKeys.KEY_CHIP_INDICATE_CHARGING
        const val KEY_LIVE_UPDATE_DISPLAY: String = SettingsKeys.KEY_LIVE_UPDATE_DISPLAY
        const val KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION: String =
            SettingsKeys.KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION
        const val KEY_RED: String = SettingsKeys.KEY_RED
        const val KEY_RED_THRESH: String = SettingsKeys.KEY_RED_THRESH
        const val KEY_AMBER: String = SettingsKeys.KEY_AMBER
        const val KEY_AMBER_THRESH: String = SettingsKeys.KEY_AMBER_THRESH
        const val KEY_GREEN: String = SettingsKeys.KEY_GREEN
        const val KEY_GREEN_THRESH: String = SettingsKeys.KEY_GREEN_THRESH
        const val KEY_TOP_LINE: String = SettingsKeys.KEY_TOP_LINE
        const val KEY_BOTTOM_LINE: String = SettingsKeys.KEY_BOTTOM_LINE
        const val KEY_TIME_REMAINING_VERBOSITY: String = SettingsKeys.KEY_TIME_REMAINING_VERBOSITY
        const val KEY_STATUS_DURATION_IN_VITAL_SIGNS: String =
            SettingsKeys.KEY_STATUS_DURATION_IN_VITAL_SIGNS
        const val KEY_CAT_CURRENT_HACK_MAIN: String = "category_current_hack_main"
        const val KEY_CAT_CURRENT_HACK_UNSUPPORTED: String = "category_current_hack_unsupported"
        const val KEY_ENABLE_CURRENT_HACK: String = SettingsKeys.KEY_ENABLE_CURRENT_HACK
        const val KEY_CURRENT_HACK_PREFER_FS: String = SettingsKeys.KEY_CURRENT_HACK_PREFER_FS
        const val KEY_CURRENT_HACK_MULTIPLIER: String = SettingsKeys.KEY_CURRENT_HACK_MULTIPLIER
        const val KEY_CAT_CURRENT_HACK_NOTIFICATION: String = "category_current_hack_notification"
        const val KEY_DISPLAY_CURRENT_IN_VITAL_STATS: String =
            SettingsKeys.KEY_DISPLAY_CURRENT_IN_VITAL_STATS
        const val KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS: String =
            SettingsKeys.KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS
        const val KEY_CAT_CURRENT_HACK_MAIN_WINDOW: String = "category_current_hack_main_window"
        const val KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW: String =
            SettingsKeys.KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW
        const val KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW: String =
            SettingsKeys.KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW
        const val KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW: String =
            SettingsKeys.KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW
        const val KEY_FIRST_RUN: String = "first_run"
        const val KEY_MIGRATED_SERVICE_DESIRED: String = "service_desired_migrated_to_sp_main"
        const val KEY_ENABLE_NOTIFS_B: String = "enable_notifications_button"
        const val KEY_ENABLE_NOTIFS_SUMMARY: String = "enable_notifications_summary"
        const val KEY_UI_COLOR: String = SettingsKeys.KEY_UI_COLOR
        const val KEY_ENABLE_ADVANCED_STATS: String = SettingsKeys.KEY_ENABLE_ADVANCED_STATS
        const val KEY_EXPORT_SETTINGS: String = "export_settings_backup"
        const val KEY_IMPORT_SETTINGS: String = "import_settings_backup"

        private const val EXPORT_REQUEST = 1
        private const val IMPORT_REQUEST = 2

        private val PARENTS = arrayOf<String?>(
            KEY_ENABLE_LOGGING,
            KEY_DISPLAY_CURRENT_IN_VITAL_STATS,
            KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW,
            KEY_RED,
            KEY_AMBER,
            KEY_GREEN
        )
        private val DEPENDENTS = arrayOf<Array<String?>?>(
            arrayOf(KEY_MAX_LOG_AGE), arrayOf(KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS), arrayOf(
                KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW, KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW
            ), arrayOf(KEY_RED_THRESH), arrayOf(KEY_AMBER_THRESH), arrayOf(KEY_GREEN_THRESH)
        )

        private val CURRENT_HACK_DEPENDENTS = arrayOf<String?>(
            KEY_CURRENT_HACK_PREFER_FS,
            KEY_CURRENT_HACK_MULTIPLIER,
            KEY_DISPLAY_CURRENT_IN_VITAL_STATS,
            KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS,
            KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW,
            KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW,
            KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW
        )

        private val INVERSE_PARENTS = arrayOf<String?>()
        private val INVERSE_DEPENDENTS = arrayOf<String?>()

        private val LIST_PREFS = arrayOf<String?>(
            KEY_AUTOSTART,
            KEY_STATUS_DUR_EST,
            KEY_RED_THRESH,
            KEY_AMBER_THRESH,
            KEY_GREEN_THRESH,
            KEY_ICON_CONTENT,
            KEY_CHIP_CONTENT,
            KEY_CHIP_SWITCHING_INTERVAL,
            KEY_LIVE_UPDATE_DISPLAY,
            KEY_CURRENT_HACK_MULTIPLIER,
            KEY_MAX_LOG_AGE,
            KEY_TOP_LINE,
            KEY_BOTTOM_LINE,
            KEY_TIME_REMAINING_VERBOSITY,
            KEY_PREDICTION_TYPE
        )

        private val RESET_SERVICE = arrayOf<String?>(
            KEY_CONVERT_F,
            KEY_NOTIFY_STATUS_DURATION,
            KEY_RED,
            KEY_RED_THRESH,
            KEY_AMBER,
            KEY_AMBER_THRESH,
            KEY_GREEN,
            KEY_GREEN_THRESH,
            KEY_INDICATE_CHARGING,
            KEY_SHOW_ICON_UNIT,
            KEY_ICON_CONTENT,
            KEY_CHIP_CONTENT,
            KEY_CHIP_SWITCHING_INTERVAL,
            KEY_CHIP_INDICATE_CHARGING,
            KEY_LIVE_UPDATE_DISPLAY,
            KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION,
            KEY_TOP_LINE,
            KEY_BOTTOM_LINE,
            KEY_ENABLE_LOGGING,
            KEY_CHANGE_APP_LANGUAGE,
            KEY_MAX_LOG_AGE,
            KEY_TIME_REMAINING_VERBOSITY,
            KEY_STATUS_DURATION_IN_VITAL_SIGNS,
            KEY_ENABLE_CURRENT_HACK,
            KEY_CURRENT_HACK_PREFER_FS,
            KEY_CURRENT_HACK_MULTIPLIER,
            KEY_DISPLAY_CURRENT_IN_VITAL_STATS,
            KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS,
            KEY_UI_COLOR,
            KEY_PREDICTION_TYPE
        )

        private val RESET_SERVICE_WITH_CANCEL_NOTIFICATION = arrayOf<String?>()

        const val EXTRA_SCREEN: String = "codes.swistak.batterymonitor.PrefScreen"
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

    private class MessageHandler(private val sa: SettingsFragment) :
        Handler(Looper.getMainLooper()) {
        override fun handleMessage(incoming: Message) {
            when (incoming.what) {
                BatteryInfoService.RemoteConnection.CLIENT_SERVICE_CONNECTED -> sa.serviceMessenger =
                    incoming.replyTo

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
        pm.setSharedPreferencesName(SETTINGS_FILE)
        pm.setSharedPreferencesMode(Context.MODE_PRIVATE)
        mSharedPreferences = requireNotNull(pm.getSharedPreferences())

        if (prefScreen > 0) setPreferences()
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

    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun resetService(cancelFirst: Boolean = false) {
        mSharedPreferences.edit().commit()

        val outgoing = Message.obtain()

        if (cancelFirst) outgoing.what =
            BatteryInfoService.RemoteConnection.SERVICE_CANCEL_NOTIFICATION_AND_RELOAD_SETTINGS
        else outgoing.what = BatteryInfoService.RemoteConnection.SERVICE_RELOAD_SETTINGS

        try {
            serviceMessenger!!.send(outgoing)
        } catch (e: Exception) {
            BatteryInfoService.startForegroundServiceSafely(requireContext())
        }
    }

    private fun setPreferences() {
        mNotificationManager =
            requireActivity().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?

        appNotifsEnabled = mNotificationManager!!.areNotificationsEnabled()
        mainNotifsEnabled = getMainNotifsEnabled()
        systemPromotedEnabled = BatteryInfoService.isLiveUpdateEnabledInSystem(requireContext())

        var pref_res = prefScreen

        if ((prefScreen == R.xml.status_bar_icon_pref_screen || prefScreen == R.xml.status_bar_chip_pref_screen || prefScreen == R.xml.notification_pref_screen) && (!appNotifsEnabled || !mainNotifsEnabled)) {
            pref_res = R.xml.main_notifs_disabled_pref_screen
        }

        setPreferencesFromResource(pref_res, null)
        mPreferenceScreen = preferenceScreen

        val liveUpdateSupported: Boolean = BatteryInfoService.supportsLiveUpdates()

        if (prefScreen == R.xml.main_pref_screen) {
            if (liveUpdateSupported) {
                val p =
                    mPreferenceScreen!!.findPreference<Preference?>(KEY_STATUS_BAR_ICON_SETTINGS)
                if (p != null) mPreferenceScreen!!.removePreference(p)
            }

            if (!liveUpdateSupported) {
                val p =
                    mPreferenceScreen!!.findPreference<Preference?>(KEY_STATUS_BAR_CHIP_SETTINGS)
                if (p != null) mPreferenceScreen!!.removePreference(p)
            }
        }

        var cat: PreferenceCategory?

        if (pref_res == R.xml.main_notifs_disabled_pref_screen) {
            val prefB = mPreferenceScreen!!.findPreference<Preference?>(KEY_ENABLE_NOTIFS_B)
            val prefS = mPreferenceScreen!!.findPreference<Preference?>(KEY_ENABLE_NOTIFS_SUMMARY)

            if (!appNotifsEnabled) {
                prefS!!.setSummary(R.string.app_notifs_disabled_summary)
                prefB!!.setSummary(R.string.app_notifs_disabled_b)
            } else {
                prefS!!.setSummary(R.string.main_notifs_disabled_summary)
                prefB!!.setSummary(R.string.main_notifs_disabled_b)
            }
        } else if (prefScreen == R.xml.notification_pref_screen) {
            val prefB = mPreferenceScreen!!.findPreference<Preference?>(KEY_ENABLE_NOTIFS_B)
            prefB!!.setSummary(R.string.pref_manage_main_channel)
        } else if (prefScreen == R.xml.status_bar_chip_pref_screen) {
            if (!liveUpdateSupported) {
                val chipCat = mPreferenceScreen!!.findPreference<Preference?>(
                    KEY_CAT_STATUS_BAR_CHIP
                ) as PreferenceCategory?
                if (chipCat != null) {
                    chipCat.removeAll()
                    chipCat.layoutResource = R.layout.none
                }
            } else {
                updateChipIntervalVisibility()
            }
        } else if (prefScreen == R.xml.current_hack_pref_screen) {
            if (CurrentHack.current == null) {
                cat =
                    mPreferenceScreen!!.findPreference<Preference?>(KEY_CAT_CURRENT_HACK_MAIN) as PreferenceCategory?
                cat!!.removeAll()
                cat.layoutResource = R.layout.none
                cat = mPreferenceScreen!!.findPreference<Preference?>(
                    KEY_CAT_CURRENT_HACK_NOTIFICATION
                ) as PreferenceCategory?
                cat!!.removeAll()
                cat.layoutResource = R.layout.none
                cat = mPreferenceScreen!!.findPreference<Preference?>(
                    KEY_CAT_CURRENT_HACK_MAIN_WINDOW
                ) as PreferenceCategory?
                cat!!.removeAll()
                cat.layoutResource = R.layout.none
            } else {
                cat = mPreferenceScreen!!.findPreference<Preference?>(
                    KEY_CAT_CURRENT_HACK_UNSUPPORTED
                ) as PreferenceCategory?
                cat!!.removeAll()
                cat.layoutResource = R.layout.none
            }
        }

        for (i in PARENTS.indices) setEnablednessOfDeps(i)

        for (i in INVERSE_PARENTS.indices) setEnablednessOfInverseDeps(i)

        for (i in LIST_PREFS.indices) updateListPrefSummary(LIST_PREFS[i]!!)

        if (prefScreen == R.xml.current_hack_pref_screen && !mSharedPreferences!!.getBoolean(
                KEY_ENABLE_CURRENT_HACK, false
            )
        ) setEnablednessOfCurrentHackDeps(false)

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

            KEY_NOTIFICATION_SETTINGS, KEY_STATUS_BAR_ICON_SETTINGS, KEY_STATUS_BAR_CHIP_SETTINGS, KEY_CURRENT_HACK_SETTINGS, KEY_OTHER_SETTINGS -> {
                val comp = ComponentName(
                    requireActivity().packageName, SettingsActivity::class.java.getName()
                )
                startActivity(Intent().setComponent(comp).putExtra(EXTRA_SCREEN, key))

                return true
            }

            KEY_EXPORT_SETTINGS -> {
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

            KEY_IMPORT_SETTINGS -> {
                val importIntent =
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                startActivityForResult(importIntent, IMPORT_REQUEST)
                return true
            }

            else -> return key == KEY_PLUGIN_SETTINGS
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == null) return
        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

        if (key == KEY_CHIP_CONTENT) {
            updateChipIntervalVisibility()
        }

        if (key == KEY_LIVE_UPDATE_DISPLAY) {
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

        if (key == KEY_CONVERT_F) {
            updateConvertFSummary()
        }

        if (key == KEY_ENABLE_CURRENT_HACK) {
            if (mSharedPreferences.getBoolean(
                    KEY_ENABLE_CURRENT_HACK, false
                )
            ) setEnablednessOfCurrentHackDeps(true)

            for (i in PARENTS.indices) setEnablednessOfDeps(i)

            if (!mSharedPreferences.getBoolean(
                    KEY_ENABLE_CURRENT_HACK, false
                )
            ) setEnablednessOfCurrentHackDeps(false)
        }

        if (key == KEY_ENABLE_ADVANCED_STATS && mSharedPreferences.getBoolean(
                KEY_ENABLE_ADVANCED_STATS, false
            )
        ) maybeRequestShizukuForAdvancedStats()

        if (key == KEY_CURRENT_HACK_PREFER_FS) CurrentHack.setPreferFS(
            mSharedPreferences.getBoolean(
                KEY_CURRENT_HACK_PREFER_FS, res.getBoolean(R.bool.default_prefer_fs_current_hack)
            )
        )

        for (i in RESET_SERVICE.indices) {
            if (key == RESET_SERVICE[i]) {
                resetService()
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

    private fun maybeRequestShizukuForAdvancedStats() {
        Thread(Runnable {
            val rootAvailable = RootExecutor().run("id") != null
            if (rootAvailable) return@Runnable
            mainHandler.post { this.requestShizukuPermissionIfNeeded() }
        }).start()
    }

    private fun requestShizukuPermissionIfNeeded() {
        if (!isAdded || activity == null) return

        ShizukuProvider.enableMultiProcessSupport(false)
        ShizukuProvider.requestBinderForNonProviderProcess(requireActivity().applicationContext)

        if (Shizuku.pingBinder()) {
            requestShizukuPermissionFromBinder()
            return
        }

        Shizuku.addBinderReceivedListenerSticky(object : OnBinderReceivedListener {
            override fun onBinderReceived() {
                Shizuku.removeBinderReceivedListener(this)
                requestShizukuPermissionFromBinder()
            }
        })
    }

    private fun requestShizukuPermissionFromBinder() {
        if (!isAdded || activity == null || Shizuku.isPreV11()) return

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return

        Shizuku.requestPermission(7001)
    }

    private fun updateConvertFSummary() {
        val pref = mPreferenceScreen!!.findPreference<Preference?>(KEY_CONVERT_F) ?: return

        pref.setSummary(
            res.getString(R.string.currently_using) + " " + (if (mSharedPreferences.getBoolean(
                    KEY_CONVERT_F, res.getBoolean(R.bool.default_convert_to_fahrenheit)
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

    private fun setEnablednessOfCurrentHackDeps(enabled: Boolean) {
        for (i in CURRENT_HACK_DEPENDENTS.indices) {
            val dependent =
                mPreferenceScreen!!.findPreference<Preference?>(CURRENT_HACK_DEPENDENTS[i]!!)
                    ?: return

            dependent.isEnabled = enabled
        }
    }

    private fun setEnablednessOfInverseDeps(index: Int) {
        val dependent =
            mPreferenceScreen!!.findPreference<Preference?>(INVERSE_DEPENDENTS[index]!!) ?: return

        dependent.isEnabled = !mSharedPreferences.getBoolean(INVERSE_PARENTS[index], false)

        updateListPrefSummary(INVERSE_DEPENDENTS[index]!!)
    }

    private fun updateChipIntervalVisibility() {
        val p =
            mPreferenceScreen!!.findPreference<Preference?>(KEY_CHIP_SWITCHING_INTERVAL) ?: return

        val isSwitching = "switching" == mSharedPreferences.getString(KEY_CHIP_CONTENT, "")
        val liveUpdatesDisabled = "never" == mSharedPreferences.getString(
            KEY_LIVE_UPDATE_DISPLAY, res.getString(R.string.default_live_update_display_mode)
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
            }
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.invalid_settings_file, Toast.LENGTH_SHORT).show()
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

    private fun setupLanguage() {
        val category: PreferenceCategory?
        val pref: Preference?
        try {
            category = mPreferenceScreen!!.findPreference(
                KEY_CHANGE_APP_LANGUAGE_HOLDER
            )
            pref = mPreferenceScreen!!.findPreference(KEY_CHANGE_APP_LANGUAGE)
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
