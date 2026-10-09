/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.ui.diagnostics

import android.app.Application
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import codes.swistak.batterymonitor.advancedstats.AdvancedBatteryStatsCollector
import codes.swistak.batterymonitor.advancedstats.AdvancedStatsUserService
import codes.swistak.batterymonitor.common.CommandExecutor
import codes.swistak.batterymonitor.common.RootExecutor
import codes.swistak.batterymonitor.privileged.ShizukuUserServiceConnection
import codes.swistak.batterymonitor.settings.SettingsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

internal data class DiagnosticsState(
    val basic: AdvancedBatterySnapshot? = null,
    val advanced: AdvancedBatterySnapshot? = null,
    val appRaw: AdvancedBatterySnapshot? = null,
    val rawLoading: Boolean = false,
    val rawStatus: Int = 0,
    val status: Int = 0,
    val loading: Boolean = false,
    val stale: Boolean = false,
    val privilegedEnabled: Boolean = false
)

internal class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val REQUEST_CODE_SHIZUKU = 7001
        private const val TAG = "Diagnostics"
    }

    private enum class LoadState { IDLE, CHECKING_ROOT, WAITING_FOR_SHIZUKU, WAITING_FOR_PERMISSION, BINDING_USER_SERVICE }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var loadState = LoadState.IDLE
    private var loadGeneration = 0
    private var activeConnection: ShizukuUserServiceConnection? = null
    private var worker: Thread? = null

    @Volatile
    private var active = false

    @Volatile
    private var rawGeneration = 0
    private var statsVisible = false
    private var rawWorker: Thread? = null
    private val mutableState = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = mutableState
    private val preferences = application.getSharedPreferences(SettingsContract.SETTINGS_FILE, 0)
    private val timeout = Runnable {
        if (active && loadState != LoadState.IDLE) finishWithNoAccess(
            "Snapshot timeout", status = R.string.diag_timeout
        )
    }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        mainHandler.post {
            if (active && loadState == LoadState.WAITING_FOR_SHIZUKU) continueViaShizuku(
                loadGeneration
            )
        }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        mainHandler.post {
            if (active && (loadState == LoadState.BINDING_USER_SERVICE || loadState == LoadState.WAITING_FOR_PERMISSION || state.value.advanced?.accessMethod == AdvancedBatterySnapshot.ACCESS_SHIZUKU)) {
                finishWithNoAccess("Shizuku binder died")
            }
        }
    }
    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            mainHandler.post {
                if (requestCode == REQUEST_CODE_SHIZUKU && active && loadState == LoadState.WAITING_FOR_PERMISSION) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) bindUserService(
                        loadGeneration
                    )
                    else finishWithNoAccess("Shizuku permission denied")
                }
            }
        }

    private fun publish(change: DiagnosticsState.() -> DiagnosticsState) {
        mutableState.value = state.value.change()
    }

    private fun showStatus(resource: Int) {
        publish {
            copy(
                status = resource,
                loading = resource == R.string.advanced_status_loading,
                stale = advanced != null
            )
        }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
            refreshStats()
        } else {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            loadGeneration++
            rawGeneration++
            rawWorker?.interrupt()
            cancelActiveConnection()
            loadState = LoadState.IDLE
            publish { copy(loading = false, rawLoading = false, stale = advanced != null) }
        }
    }

    fun setTab(tab: Int) {
        val previousStatsVisible = statsVisible
        statsVisible = tab != 0
        if (statsVisible && active && state.value.appRaw == null && !state.value.rawLoading) refreshRaw()
        if (!statsVisible) {
            rawGeneration++
            rawWorker?.interrupt()
            publish { copy(rawLoading = false) }
        }
        if (!statsVisible && previousStatsVisible) {
            loadGeneration++
            cancelActiveConnection()
            loadState = LoadState.IDLE
            publish { copy(loading = false, stale = advanced != null) }
        } else if (statsVisible && !previousStatsVisible && active) refreshStats()
    }

    private fun refreshRaw() {
        if (!active || state.value.rawLoading) return
        val generation = ++rawGeneration
        publish { copy(rawLoading = true, rawStatus = 0) }
        val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
        rawWorker = Thread {
            val raw = runCatching {
                AdvancedBatteryStatsCollector.collectAppRaw(getApplication()) {
                    generation != rawGeneration || !active || Thread.currentThread().isInterrupted || android.os.SystemClock.elapsedRealtime() > deadline
                }
            }.getOrNull()
            mainHandler.post {
                if (generation == rawGeneration && active) {
                    val timedOut = android.os.SystemClock.elapsedRealtime() > deadline
                    publish {
                        copy(
                            appRaw = if (timedOut) appRaw else raw ?: appRaw,
                            rawLoading = false,
                            rawStatus = if (timedOut) R.string.diag_timeout else if (raw == null) R.string.advanced_status_no_stats else 0
                        )
                    }
                }
            }
        }.apply { name = "diagnostics-app-raw"; start() }
    }

    fun setPrivilegedEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, enabled) }
        codes.swistak.batterymonitor.privileged.PrivilegedAccess.setEnabled(enabled)
        publish { copy(privilegedEnabled = enabled, advanced = null, status = 0, stale = false) }
        resetAndRefresh()
    }

    private fun resetAndRefresh() {
        loadGeneration++
        cancelActiveConnection()
        loadState = LoadState.IDLE
        refreshStats()
    }

    fun refreshStats() {
        if (statsVisible) refreshRaw()
        if (active && loadState == LoadState.WAITING_FOR_SHIZUKU) {
            loadGeneration++
            loadState = LoadState.IDLE
        }
        if (!active || loadState != LoadState.IDLE) return
        val privilegedEnabled =
            preferences.getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false)
        val generation = ++loadGeneration
        publish {
            copy(
                loading = true,
                privilegedEnabled = privilegedEnabled,
                advanced = if (privilegedEnabled) advanced else null,
                stale = privilegedEnabled && stale
            )
        }
        worker = Thread {
            val basic =
                runCatching { AdvancedBatteryStatsCollector.collectBasic(getApplication()) }.getOrNull()
            mainHandler.post {
                if (generation != loadGeneration || !active) return@post
                publish { copy(basic = basic ?: this.basic) }
                if (statsVisible && privilegedEnabled) loadPrivileged(generation)
                else publish {
                    copy(
                        status = if (privilegedEnabled) 0 else R.string.currently_disabled,
                        loading = false,
                        stale = advanced != null
                    )
                }
            }
        }.apply { name = "diagnostics-android"; start() }
    }

    private fun loadPrivileged(generation: Int) {
        loadState = LoadState.CHECKING_ROOT
        showStatus(R.string.advanced_status_loading)
        mainHandler.postDelayed(timeout, 45_000)
        worker = Thread {
            val executor = RootExecutor()
            val snapshot = runCatching {
                if (executor.run("id")?.contains("uid=0") != true) null
                else AdvancedBatteryStatsCollector.collect(
                    object : CommandExecutor {
                        override fun run(command: String): String? =
                            if (Thread.currentThread().isInterrupted) null else executor.run(command)

                        override fun runRaw(command: String): String? =
                            if (Thread.currentThread().isInterrupted) null else executor.runRaw(
                                command
                            )
                    }, AdvancedBatterySnapshot.ACCESS_ROOT, 0, null, false
                )
            }.getOrNull()
            mainHandler.post {
                if (generation != loadGeneration || !active) return@post
                if (snapshot != null) postSnapshot(snapshot, generation) else continueViaShizuku(
                    generation
                )
            }
        }.apply { name = "diagnostics-root"; start() }
    }

    private fun continueViaShizuku(generation: Int) {
        if (generation != loadGeneration || !active) return
        mainHandler.removeCallbacks(timeout)
        mainHandler.postDelayed(timeout, 45_000)

        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            loadState = LoadState.WAITING_FOR_SHIZUKU
            mainHandler.removeCallbacks(timeout)
            showStatus(R.string.advanced_status_no_access)
            return
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            finishWithNoAccess("Unsupported Shizuku version")
            return
        }

        when {
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(
                false
            ) -> {
                bindUserService(generation)
            }

            runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(true) -> {
                finishWithNoAccess("Shizuku permission was previously denied")
            }

            else -> {
                loadState = LoadState.WAITING_FOR_PERMISSION
                showStatus(R.string.advanced_status_waiting_permission)
                try {
                    Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
                } catch (error: Throwable) {
                    finishWithNoAccess("Unable to request Shizuku permission", error)
                }
            }
        }
    }

    @Suppress("USELESS_ELVIS")
    private fun bindUserService(generation: Int) {
        if (generation != loadGeneration || !active || activeConnection != null) return

        val appContext = getApplication<Application>() ?: run {
            finishLoading(generation)
            return
        }
        val connection = ShizukuUserServiceConnection(
            appContext,
            AdvancedStatsUserService::class.java,
            processNameSuffix = "advanced_stats",
            tag = "advanced_battery_stats",
            onConnected = { connected, service ->
                collectShizukuSnapshot(connected, service, generation)
            },
            onDisconnected = { disconnected ->
                mainHandler.post {
                    if (generation == loadGeneration && activeConnection === disconnected) {
                        finishWithNoAccess("Shizuku user service disconnected")
                    }
                }
            })
        activeConnection = connection
        loadState = LoadState.BINDING_USER_SERVICE

        try {
            connection.bind()
        } catch (error: Throwable) {
            clearConnection(connection)
            finishWithNoAccess("Unable to bind Shizuku user service", error)
        }
    }

    private fun postSnapshot(snapshot: AdvancedBatterySnapshot, generation: Int) {
        mainHandler.post {
            if (generation != loadGeneration) return@post

            mainHandler.removeCallbacks(timeout)
            activeConnection?.let(::clearConnection)
            loadState = LoadState.IDLE
            if (!active) return@post

            if (!snapshot.hasStats()) {
                publish {
                    copy(
                        advanced = advanced ?: snapshot,
                        status = R.string.advanced_status_no_stats,
                        stale = advanced != null,
                        loading = false
                    )
                }
                return@post
            }

            publish { copy(advanced = snapshot, status = 0, stale = false, loading = false) }
        }
    }

    private fun finishLoading(generation: Int) {
        if (generation == loadGeneration) {
            mainHandler.removeCallbacks(timeout)
            activeConnection?.let(::clearConnection)
            loadState = LoadState.IDLE
        }
    }

    private fun finishWithNoAccess(
        message: String, error: Throwable? = null, status: Int = R.string.advanced_status_no_access
    ) {
        if (error == null) Log.w(TAG, message) else Log.e(TAG, message, error)
        loadGeneration++
        cancelActiveConnection()
        loadState = LoadState.IDLE
        if (active) showStatus(status)
    }

    private fun clearConnection(connection: ShizukuUserServiceConnection) {
        if (activeConnection === connection) activeConnection = null
    }

    private fun cancelActiveConnection() {
        worker?.interrupt()
        worker = null
        mainHandler.removeCallbacks(timeout)
        val connection = activeConnection ?: return
        activeConnection = null
        try {
            connection.unbind(remove = true)
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to unbind Shizuku user service", error)
        }
    }

    private fun collectShizukuSnapshot(
        connection: ShizukuUserServiceConnection, service: IBinder, generation: Int
    ) {
        worker = Thread {
            try {
                val snapshot = AdvancedBatterySnapshot.fromBundle(
                    requireNotNull(AdvancedStatsUserService.requestSnapshot(service))
                )
                snapshot.shizukuVersion = Shizuku.getVersion()
                postSnapshot(snapshot, generation)
            } catch (error: Throwable) {
                mainHandler.post {
                    if (generation == loadGeneration) {
                        finishWithNoAccess("Unable to retrieve Shizuku battery stats", error)
                    }
                }
            } finally {
                try {
                    connection.unbind(remove = true)
                } catch (error: Throwable) {
                    Log.w(TAG, "Unable to unbind Shizuku user service", error)
                }
            }
        }.apply { name = "diagnostics-shizuku"; start() }
    }

    override fun onCleared() {
        setActive(false); mainHandler.removeCallbacksAndMessages(null)
    }
}
