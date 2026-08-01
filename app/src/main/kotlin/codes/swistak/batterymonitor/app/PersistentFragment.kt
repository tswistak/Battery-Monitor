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


import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.logs.LogViewFragment
import codes.swistak.batterymonitor.monitoring.BackgroundServiceWatchdog
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import codes.swistak.batterymonitor.monitoring.CurrentInfoFragment
import codes.swistak.batterymonitor.settings.SettingsContract

class PersistentFragment : Fragment() {
    companion object {
        const val FRAG_TAG: String = "pfrag"
        const val LOG_TAG = "PersistentFragment"

        fun getInstance(fm: FragmentManager): PersistentFragment {
            var pFrag = fm.findFragmentByTag(FRAG_TAG) as PersistentFragment?

            if (pFrag == null) {
                pFrag = PersistentFragment()
                fm.beginTransaction().add(pFrag, FRAG_TAG).commit()
            }

            return pFrag
        }
    }

    private var biServiceIntent: Intent? = null
    private var serviceMessenger: Messenger? = null
    private val messageHandler = MessageHandler(this)
    private val messenger = Messenger(messageHandler)
    private var serviceConnection: BatteryInfoService.RemoteConnection? = null
    private var serviceConnected = false
    private var cif: CurrentInfoFragment? = null
    private var lvf: LogViewFragment? = null

    lateinit var settings: SharedPreferences
    lateinit var spService: SharedPreferences
    lateinit var spMain: SharedPreferences
    lateinit var res: Resources

    private var mHasShownOnboardingInThisSession = false

    private fun bindService() {
        if (!serviceConnected && activity != null) {
            requireActivity().applicationContext.bindService(
                biServiceIntent!!,
                serviceConnection!!,
                0
            )
            serviceConnected = true
        }
    }

    private class MessageHandler(var pf: PersistentFragment) :
        Handler(Looper.getMainLooper()) {
        override fun handleMessage(incoming: Message) {
            if (!pf.serviceConnected) {
                Log.i(LOG_TAG, "serviceConected is false; ignoring message: $incoming");
                return
            }

            when (incoming.what) {
                BatteryInfoService.RemoteConnection.CLIENT_SERVICE_CONNECTED -> {
                    pf.serviceMessenger = incoming.replyTo
                    pf.sendServiceMessage(BatteryInfoService.RemoteConnection.SERVICE_REGISTER_CLIENT)
                }

                BatteryInfoService.RemoteConnection.CLIENT_BATTERY_INFO_UPDATED -> {
                    if (pf.cif != null) pf.cif!!.batteryInfoUpdated(incoming.getData())
                    if (pf.lvf != null) pf.lvf!!.batteryInfoUpdated()
                }

                else -> super.handleMessage(incoming)
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        updateResources()
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setRetainInstance(true)

        serviceConnection = BatteryInfoService.RemoteConnection(messenger)
        biServiceIntent = Intent(activity, BatteryInfoService::class.java)

        loadSettingsFiles()
    }

    override fun onDestroy() {
        super.onDestroy()

        if (serviceConnected && activity != null) {
            requireActivity().applicationContext.unbindService(serviceConnection!!)
            serviceConnected = false
        }
    }

    override fun onStart() {
        super.onStart()

        sendServiceMessage(BatteryInfoService.RemoteConnection.SERVICE_REGISTER_CLIENT)

        spMain.edit { putBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, true) }

        if (!spMain.getBoolean(
                SettingsContract.KEY_MIGRATED_SERVICE_DESIRED, false
            )
        ) spMain.edit {
            putBoolean(SettingsContract.KEY_MIGRATED_SERVICE_DESIRED, true)
        }
    }

    override fun onResume() {
        super.onResume()

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                requireActivity(), Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (spMain.getBoolean(SettingsContract.KEY_FIRST_RUN, true)) {
                showNotificationOnboarding()
            } else if (!mHasShownOnboardingInThisSession) {
                requestNotificationPermission()
                mHasShownOnboardingInThisSession = true
            }
            return
        }

        if (spMain.getBoolean(SettingsContract.KEY_FIRST_RUN, true)) {
            spMain.edit { putBoolean(SettingsContract.KEY_FIRST_RUN, false) }
        }

        startServiceIfNeeded()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startServiceIfNeeded()
            }
        }
    }

    private fun startServiceIfNeeded() {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                requireActivity(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (!serviceConnected) {
                BatteryInfoService.startForegroundServiceSafely(requireContext())
                bindService()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 36) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS,
                    "android.permission.POST_PROMOTED_NOTIFICATIONS"
                ), 101
            )
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }

    private fun showNotificationOnboarding() {
        if (mHasShownOnboardingInThisSession) return
        mHasShownOnboardingInThisSession = true

        val dialog = OnboardingDialogFragment()
        dialog.show(getParentFragmentManager(), "onboarding")
    }

    class OnboardingDialogFragment : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val pf = getParentFragmentManager().findFragmentByTag(FRAG_TAG) as PersistentFragment?
            val builder = AlertDialog.Builder(requireContext())
            builder.setCancelable(false)

            if (BatteryInfoService.supportsLiveUpdates()) {
                val enabled: Boolean =
                    BatteryInfoService.isLiveUpdateEnabledInSystem(requireContext())
                builder.setTitle(R.string.live_updates_onboarding_title)

                if (enabled) {
                    builder.setMessage(R.string.live_updates_onboarding_message_on)
                        .setPositiveButton(
                            R.string.live_updates_onboarding_positive_on
                        ) { _, _ -> pf?.requestNotificationPermission() }
                } else {
                    builder.setMessage(R.string.live_updates_onboarding_message_off)
                        .setPositiveButton(
                            R.string.live_updates_onboarding_positive_off
                        ) { _, _ ->
                            try {
                                val action: String? = try {
                                    Settings::class.java.getField("ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS")
                                        .get(null) as String?
                                } catch (ignored: Throwable) {
                                    "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS"
                                }
                                val intent = Intent(action)
                                intent.putExtra(
                                    Settings.EXTRA_APP_PACKAGE, requireActivity().packageName
                                )
                                startActivity(intent)
                            } catch (ignored: Throwable) {
                            }
                        }.setNegativeButton(
                            R.string.live_updates_onboarding_negative_off
                        ) { _, _ -> pf?.requestNotificationPermission() }
                }
            } else {
                builder.setTitle(R.string.app_full_name)
                    .setMessage(R.string.notifications_onboarding_message).setPositiveButton(
                        android.R.string.ok
                    ) { _, _ -> pf?.requestNotificationPermission() }.setNegativeButton(
                        R.string.cancel
                    ) { _, _ ->
                        if (pf != null) {
                            pf.spMain.edit {
                                putBoolean(SettingsContract.KEY_FIRST_RUN, false)
                            }
                            pf.startServiceIfNeeded()
                        }
                    }
            }

            return builder.create()
        }

        override fun onDismiss(dialog: DialogInterface) {
            super.onDismiss(dialog)
            val pf = getParentFragmentManager().findFragmentByTag(FRAG_TAG) as PersistentFragment?
            if (pf != null) {
                pf.spMain.edit {
                    putBoolean(SettingsContract.KEY_FIRST_RUN, false)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()

        sendServiceMessage(BatteryInfoService.RemoteConnection.SERVICE_UNREGISTER_CLIENT)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateResources()
    }

    private fun updateResources() {
        res = requireActivity().resources
        DisplayStrings.setResources(res)
    }

    fun setCIF(f: CurrentInfoFragment?) {
        cif = f
    }

    fun setLVF(f: LogViewFragment?) {
        lvf = f
    }

    fun loadSettingsFiles() {
        settings = requireActivity().getSharedPreferences(
            SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
        )
        spService = requireActivity().getSharedPreferences(
            SettingsContract.SP_SERVICE_FILE, Context.MODE_PRIVATE
        )
        spMain = requireActivity().getSharedPreferences(
            SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE
        )
    }

    fun sendServiceMessage(what: Int) {
        if (serviceMessenger == null) return

        val outgoing = Message.obtain()
        outgoing.what = what
        outgoing.replyTo = messenger
        try {
            serviceMessenger!!.send(outgoing)
        } catch (e: RemoteException) {
        }
    }

    fun closeApp() {
        spMain.edit { putBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, false) }
        if (activity != null) BackgroundServiceWatchdog.cancel(requireActivity().applicationContext)

        if (activity == null) return

        if (serviceConnected) {
            requireActivity().applicationContext.unbindService(serviceConnection!!)
            requireActivity().stopService(biServiceIntent)
            serviceConnected = false
        }

        requireActivity().finish()
    }
}
