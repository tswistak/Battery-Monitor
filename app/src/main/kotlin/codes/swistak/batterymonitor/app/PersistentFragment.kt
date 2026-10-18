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
import android.content.Context
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
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.common.showToast
import codes.swistak.batterymonitor.monitoring.BackgroundServiceWatchdog
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import codes.swistak.batterymonitor.monitoring.MonitoringDesiredState
import codes.swistak.batterymonitor.monitoring.Predictor
import codes.swistak.batterymonitor.monitoring.PredictorStoredState
import codes.swistak.batterymonitor.monitoring.presentation.CurrentDemand
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringConnection
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringSnapshot
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.widgets.WidgetSettingsReceiver
import java.io.IOException
import java.util.concurrent.CompletableFuture

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
    internal val monitoring = MonitoringConnection()
    private var currentDemand: CurrentDemand? = null
    private var currentToken = 0L

    internal fun setCurrentDemand(demand: CurrentDemand?) {
        if (currentDemand == demand) return
        currentDemand = demand
        currentToken++
        monitoring.expectCurrent(currentToken.takeIf { demand != null })
        syncCurrentDemand()
    }

    private fun syncCurrentDemand() {
        if (serviceMessenger == null) return
        monitoring.expectCurrent(currentToken.takeIf { currentDemand != null })
        val bundle = currentDemand?.toBundle(currentToken) ?: Bundle().apply {
            putBoolean("current_active", false)
            putLong("current_token", currentToken)
        }
        sendServiceMessage(BatteryInfoService.RemoteConnection.SERVICE_SET_CURRENT_DEMAND, bundle)
    }

    internal fun refreshCurrent() {
        if (currentDemand == null) return
        sendServiceMessage(
            BatteryInfoService.RemoteConnection.SERVICE_REFRESH_CURRENT, Bundle().apply {
                putLong("current_token", currentToken)
            })
    }

    private fun monitoringDisconnected() {
        serviceMessenger = null
        monitoring.onServiceDisconnected()
    }

    lateinit var settings: SharedPreferences
    lateinit var spMain: SharedPreferences
    lateinit var res: Resources


    private fun bindService() {
        if (!serviceConnected && activity != null) {
            serviceConnected = requireActivity().applicationContext.bindService(
                biServiceIntent!!, serviceConnection!!, 0
            )
        }
    }

    private class MessageHandler(var pf: PersistentFragment) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(incoming: Message) {
            if (!pf.serviceConnected) {
                Log.i(LOG_TAG, "serviceConected is false; ignoring message: $incoming")
                return
            }

            when (incoming.what) {
                BatteryInfoService.RemoteConnection.CLIENT_SERVICE_CONNECTED -> {
                    if (!pf.monitoring.onHandshake(incoming.arg1, incoming.data)) return
                    pf.serviceMessenger = incoming.replyTo
                    pf.sendServiceMessage(BatteryInfoService.RemoteConnection.SERVICE_REGISTER_CLIENT)
                    pf.syncCurrentDemand()
                }

                BatteryInfoService.RemoteConnection.CLIENT_BATTERY_INFO_UPDATED -> {
                    if (!pf.monitoring.onSnapshot(incoming.arg1, incoming.data)) return
                }

                BatteryInfoService.RemoteConnection.CLIENT_CURRENT_UPDATED -> {
                    pf.monitoring.onCurrent(incoming.arg1, incoming.data)
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

        serviceConnection = BatteryInfoService.RemoteConnection(
            messenger,
            generation = { monitoring.generation },
            onConnected = { monitoring.onServiceConnected() },
            onDisconnected = { monitoringDisconnected() })
        biServiceIntent = Intent(activity, BatteryInfoService::class.java)

        loadSettingsFiles()
    }

    override fun onDestroy() {
        setCurrentDemand(null)
        super.onDestroy()

        if (serviceConnected && activity != null) {
            requireActivity().applicationContext.unbindService(serviceConnection!!)
            serviceConnected = false
        }
        serviceConnection?.serviceMessenger = null
        serviceMessenger = null
    }

    override fun onStart() {
        super.onStart()

        if (!spMain.getBoolean(
                SettingsContract.KEY_MIGRATED_SERVICE_DESIRED, false
            ) && !MonitoringDesiredState.initialize(requireContext())
        ) requireContext().showToast(R.string.monitoring_state_save_failed)
        if (BackgroundServiceWatchdog.isServiceDesired(requireContext())) {
            monitoring.start()
            serviceMessenger = serviceConnection?.serviceMessenger
            if (serviceMessenger != null) {
                monitoring.onServiceConnected()
                sendServiceMessage(BatteryInfoService.RemoteConnection.SERVICE_CLIENT_CONNECTED)
            }
        } else monitoring.stop()

        BackgroundServiceWatchdog.schedule(requireContext())
    }

    override fun onResume() {
        super.onResume()
        startServiceIfNeeded()
    }

    internal fun refreshMonitoringAfterPermission() = startServiceIfNeeded()

    private fun startServiceIfNeeded() {
        if (!BackgroundServiceWatchdog.isServiceDesired(requireContext())) return
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                requireActivity(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val selfHealRequested = BackgroundServiceWatchdog.selfHealIfNeeded(requireContext())
            if ((!serviceConnected || serviceConnection?.serviceMessenger == null) && !selfHealRequested) {
                BatteryInfoService.startForegroundServiceSafely(requireContext())
            }
        }
        // A notification denial must not disconnect the UI from an existing monitor.
        bindService()
    }

    override fun onStop() {
        super.onStop()

        setCurrentDemand(null)

        sendServiceMessage(BatteryInfoService.RemoteConnection.SERVICE_UNREGISTER_CLIENT)
        monitoring.stop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateResources()
    }

    private fun updateResources() {
        res = requireActivity().resources
        DisplayStrings.setResources(res)
    }

    fun loadSettingsFiles() {
        settings = requireActivity().getSharedPreferences(
            SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
        )
        spMain = requireActivity().getSharedPreferences(
            SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE
        )
    }

    fun sendServiceMessage(what: Int, data: Bundle? = null): Boolean {
        if (serviceMessenger == null) return false

        val outgoing = Message.obtain()
        outgoing.what = what
        outgoing.arg1 = monitoring.generation
        outgoing.replyTo = messenger
        if (data != null) outgoing.data = data
        try {
            serviceMessenger!!.send(outgoing)
            return true
        } catch (_: RemoteException) {
            monitoringDisconnected()
            return false
        }
    }

    internal fun predictorForBackup(): CompletableFuture<PredictorStoredState> {
        val result = CompletableFuture<PredictorStoredState>()
        val service = serviceMessenger
        if (service == null) {
            result.completeExceptionally(IOException("Monitoring service is disconnected"))
            return result
        }
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            result.completeExceptionally(IOException("Monitoring snapshot timed out"))
        }
        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what != BatteryInfoService.RemoteConnection.CLIENT_BATTERY_INFO_UPDATED) return
                runCatching {
                    val data =
                        requireNotNull(message.data.getBundle(MonitoringSnapshot.FIELD_PREDICTOR_DATA))
                    @Suppress("DEPRECATION") Predictor.readStoredState(
                        data.keySet().associateWith { data[it] })
                }.fold(result::complete, result::completeExceptionally)
            }
        })
        result.whenComplete { _, _ ->
            handler.post {
                handler.removeCallbacks(timeout)
                runCatching {
                    service.send(Message.obtain().apply {
                        what = BatteryInfoService.RemoteConnection.SERVICE_UNREGISTER_CLIENT
                        replyTo = reply
                    })
                }
            }
        }
        handler.postDelayed(timeout, 5_000L)
        runCatching {
            service.send(Message.obtain().apply {
                what = BatteryInfoService.RemoteConnection.SERVICE_REGISTER_CLIENT
                replyTo = reply
            })
        }.onFailure { result.completeExceptionally(it) }
        return result
    }

    internal fun reloadAlarmRules() {
        val context = requireContext().applicationContext
        if (!BackgroundServiceWatchdog.isServiceDesired(context)) return
        val snapshot = codes.swistak.batterymonitor.settings.SettingsSnapshot.capture(settings)
        val message = Message.obtain().apply {
            what = BatteryInfoService.RemoteConnection.SERVICE_RELOAD_SETTINGS
            data = snapshot
        }
        try {
            val service = serviceMessenger ?: error("Service is disconnected")
            service.send(message)
        } catch (_: Exception) {
            BatteryInfoService.startForegroundServiceSafely(context, snapshot)
        }
    }

    fun stopMonitoring() {
        if (!saveMonitoringDesired(false)) return
        setCurrentDemand(null)
        val context = requireContext().applicationContext
        BackgroundServiceWatchdog.cancel(context)
        monitoring.stop()
        if (serviceConnected) {
            context.unbindService(serviceConnection!!)
            serviceConnected = false
        }
        serviceConnection?.serviceMessenger = null
        serviceMessenger = null
        context.stopService(biServiceIntent)
    }

    fun startMonitoring() {
        if (!saveMonitoringDesired(true)) return
        monitoring.start()
        startServiceIfNeeded()
        BackgroundServiceWatchdog.schedule(requireContext())
    }

    private fun saveMonitoringDesired(desired: Boolean): Boolean {
        if (MonitoringDesiredState.write(requireContext(), desired)) {
            if (!desired) WidgetSettingsReceiver.refresh(requireContext())
            return true
        }
        requireContext().showToast(R.string.monitoring_state_save_failed)
        return false
    }
}
