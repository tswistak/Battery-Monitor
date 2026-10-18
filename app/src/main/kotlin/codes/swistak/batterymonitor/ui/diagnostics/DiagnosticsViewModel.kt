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
import codes.swistak.batterymonitor.common.RootExecutor
import codes.swistak.batterymonitor.privileged.PrivilegedAccess
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
) {
    val privilegedAccessStatus: Int
        get() = when {
            !privilegedEnabled -> R.string.currently_disabled
            status != 0 -> status
            advanced?.hasStats() == true -> R.string.yes
            else -> R.string.advanced_status_no_access
        }

    fun allowsPrivilegedCollection(active: Boolean, statsVisible: Boolean): Boolean =
        active && statsVisible && privilegedEnabled

    fun startingRefresh(enabled: Boolean, statsVisible: Boolean): DiagnosticsState = copy(
        loading = true,
        status = when {
            !enabled -> R.string.currently_disabled
            statsVisible -> R.string.advanced_status_loading
            else -> settledStatus()
        },
        privilegedEnabled = enabled,
        advanced = if (enabled) advanced else null,
        stale = enabled && (stale || statsVisible && advanced != null)
    )

    fun withoutPendingLoad(): DiagnosticsState = copy(
        status = settledStatus(), loading = false, rawLoading = false, stale = advanced != null
    )

    private fun settledStatus(): Int = when (status) {
        R.string.currently_disabled, R.string.advanced_status_loading, R.string.advanced_status_waiting_permission -> 0

        else -> status
    }
}

internal class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val REQUEST_CODE_SHIZUKU = 7001
        private const val TAG = "Diagnostics"
    }

    private enum class LoadState { IDLE, READING_ANDROID, CHECKING_ROOT, WAITING_FOR_SHIZUKU, WAITING_FOR_PERMISSION, BINDING_USER_SERVICE }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var loadState = LoadState.IDLE

    @Volatile
    private var loadGeneration = 0

    private var activeConnection: ShizukuUserServiceConnection? = null
    private var worker: Thread? = null

    @Volatile
    private var active = false

    @Volatile
    private var rawGeneration = 0

    @Volatile
    private var statsVisible = false

    private var rawWorker: Thread? = null
    private val mutableState = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = mutableState
    private val preferences = application.getSharedPreferences(SettingsContract.SETTINGS_FILE, 0)
    private val timeout = Runnable {
        if (canLoadPrivileged(loadGeneration) && loadState != LoadState.IDLE) finishWithNoAccess(
            "Snapshot timeout", status = R.string.diag_timeout
        )
    }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        mainHandler.post {
            if (canLoadPrivileged(loadGeneration) && loadState == LoadState.WAITING_FOR_SHIZUKU) continueViaShizuku(
                loadGeneration
            )
        }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        mainHandler.post {
            if (!active) return@post
            if (loadState == LoadState.BINDING_USER_SERVICE || loadState == LoadState.WAITING_FOR_PERMISSION) {
                finishWithNoAccess("Shizuku binder died")
            } else if (state.value.advanced?.accessMethod == AdvancedBatterySnapshot.ACCESS_SHIZUKU) {
                publish {
                    copy(
                        status = if (statsVisible && loadState != LoadState.IDLE) status else R.string.advanced_status_no_access,
                        stale = true
                    )
                }
            }
        }
    }
    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            mainHandler.post {
                if (requestCode == REQUEST_CODE_SHIZUKU && canLoadPrivileged(loadGeneration) && loadState == LoadState.WAITING_FOR_PERMISSION) {
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
            publish { withoutPendingLoad() }
        }
    }

    fun setTab(tab: Int) {
        val wasStatsVisible = statsVisible
        statsVisible = tab != 0
        if (statsVisible) {
            if (active && state.value.appRaw == null && !state.value.rawLoading) refreshRaw()
            if (!wasStatsVisible && active) refreshStats()
        } else {
            rawGeneration++
            rawWorker?.interrupt()
            if (loadState != LoadState.IDLE) {
                if (loadState != LoadState.READING_ANDROID) {
                    loadGeneration++
                    cancelActiveConnection()
                    loadState = LoadState.IDLE
                }
                publish { withoutPendingLoad().copy(loading = loadState == LoadState.READING_ANDROID) }
            } else {
                publish { copy(rawLoading = false) }
            }
        }
    }

    private fun canLoadPrivileged(generation: Int): Boolean =
        state.value.allowsPrivilegedCollection(active, statsVisible) && generation == loadGeneration

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
        }.apply {
            name = "diagnostics-app-raw"
            start()
        }
    }

    fun setPrivilegedEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, enabled) }
        PrivilegedAccess.setEnabled(enabled)
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
        PrivilegedAccess.initialize(getApplication())
        PrivilegedAccess.setEnabled(privilegedEnabled)
        val generation = ++loadGeneration
        loadState = LoadState.READING_ANDROID
        publish { startingRefresh(privilegedEnabled, statsVisible) }
        worker = Thread {
            val basic =
                runCatching { AdvancedBatteryStatsCollector.collectBasic(getApplication()) }.getOrNull()
            mainHandler.post {
                if (generation != loadGeneration || !active) return@post
                publish { copy(basic = basic ?: this.basic) }
                if (canLoadPrivileged(generation)) loadPrivileged(generation)
                else {
                    loadState = LoadState.IDLE
                    publish { copy(loading = false) }
                }
            }
        }.apply {
            name = "diagnostics-android"
            start()
        }
    }

    private fun loadPrivileged(generation: Int) {
        if (!canLoadPrivileged(generation)) return
        loadState = LoadState.CHECKING_ROOT
        showStatus(R.string.advanced_status_loading)
        mainHandler.postDelayed(timeout, 45_000)
        val revision = PrivilegedAccess.accessRevision
        val allowed = {
            canLoadPrivileged(generation) && PrivilegedAccess.isEnabled() && revision == PrivilegedAccess.accessRevision
        }
        worker = Thread {
            val executor = RootExecutor(allowed)
            val snapshot = runCatching {
                if (!allowed() || executor.run("id")?.contains("uid=0") != true) null
                else AdvancedBatteryStatsCollector.collect(
                    executor, AdvancedBatterySnapshot.ACCESS_ROOT, 0, null, false
                ).takeIf { allowed() && executor.isValid }
            }.getOrNull()
            mainHandler.post {
                if (!canLoadPrivileged(generation)) return@post
                if (snapshot != null && executor.isValid) postSnapshot(
                    snapshot, generation, revision
                ) { executor.isValid } else continueViaShizuku(
                    generation
                )
            }
        }.apply {
            name = "diagnostics-root"
            start()
        }
    }

    private fun continueViaShizuku(generation: Int) {
        if (!canLoadPrivileged(generation)) return
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
        if (!canLoadPrivileged(generation) || activeConnection != null) return

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

    private fun postSnapshot(
        snapshot: AdvancedBatterySnapshot,
        generation: Int,
        accessRevision: Long? = null,
        valid: () -> Boolean = { true }
    ) {
        mainHandler.post {
            if (!canLoadPrivileged(generation)) return@post
            if (!valid() || accessRevision != null && accessRevision != PrivilegedAccess.accessRevision) {
                finishLoading(generation)
                publish { withoutPendingLoad() }
                return@post
            }

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
        if (activeConnection !== connection) return
        activeConnection = null
        try {
            connection.unbind(remove = true)
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to unbind Shizuku user service", error)
        }
    }

    private fun cancelActiveConnection() {
        worker?.interrupt()
        worker = null
        mainHandler.removeCallbacks(timeout)
        activeConnection?.let(::clearConnection)
    }

    private fun collectShizukuSnapshot(
        connection: ShizukuUserServiceConnection, service: IBinder, generation: Int
    ) {
        if (!canLoadPrivileged(generation) || activeConnection !== connection) return
        worker = Thread {
            try {
                if (!canLoadPrivileged(generation)) return@Thread
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
                mainHandler.post { clearConnection(connection) }
            }
        }.apply {
            name = "diagnostics-shizuku"
            start()
        }
    }

    override fun onCleared() {
        setActive(false)
        mainHandler.removeCallbacksAndMessages(null)
    }
}
