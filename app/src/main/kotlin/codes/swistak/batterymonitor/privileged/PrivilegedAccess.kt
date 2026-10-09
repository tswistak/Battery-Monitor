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
package codes.swistak.batterymonitor.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import codes.swistak.batterymonitor.advancedstats.AdvancedBatteryStatsCollector
import codes.swistak.batterymonitor.common.CommandExecutor
import codes.swistak.batterymonitor.common.RootExecutor
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnBinderDeadListener
import rikka.shizuku.Shizuku.OnBinderReceivedListener
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener
import rikka.shizuku.Shizuku.UserServiceArgs
import rikka.shizuku.ShizukuProvider

internal object PrivilegedAccess : CommandExecutor {
    internal enum class Backend {
        ROOT, SHIZUKU
    }

    internal data class CommandResult(val output: String, val backend: Backend)

    private const val LOG_TAG = "codes.swistak.batterymonitor - PrivilegedAccess"
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 7001
    private const val COMMAND_SERVICE_SUFFIX = "privileged_commands"
    private const val COMMAND_SERVICE_TAG = "privileged_commands"

    private const val SHIZUKU_CONNECTION_TIMEOUT_MS = 4_000L
    private val shizukuLock = Object()

    private var appContext: Context? = null
    private var mainHandler: Handler? = null

    @Volatile
    private var enabled = false

    @Volatile
    var accessRevision: Long = 0L
        private set

    private var shizukuListenersRegistered = false
    private var shizukuMultiProcessEnabled = false
    private var shizukuConnection: ShizukuUserServiceConnection? = null
    private var readyListener: (() -> Unit)? = null

    @Volatile
    private var shizukuUserService: IBinder? = null

    private val binderReceivedListener = OnBinderReceivedListener {
        ensureShizukuConnection()
    }
    private val binderDeadListener = OnBinderDeadListener {
        synchronized(shizukuLock) {
            shizukuUserService = null
            shizukuConnection = null
            shizukuLock.notifyAll()
        }
    }
    private val permissionResultListener =
        OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) ensureShizukuConnection()
                else disconnectShizukuConnection()
            }
        }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (mainHandler == null) mainHandler = Handler(Looper.getMainLooper())
        registerShizukuListenersIfNeeded()
        if (enabled) ensureShizukuConnection()
    }

    @Synchronized
    fun enableShizukuMultiProcessSupport(context: Context) {
        if (shizukuMultiProcessEnabled) return

        try {
            ShizukuProvider.enableMultiProcessSupport(false)
            ShizukuProvider.requestBinderForNonProviderProcess(context.applicationContext)
            shizukuMultiProcessEnabled = true
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "Unable to initialize Shizuku in this process", error)
        }
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled != value) {
            accessRevision++
            enabled = value
        }
        if (value) ensureShizukuConnection() else disconnectShizukuConnection()
    }

    fun isEnabled(): Boolean = enabled

    fun setReadyListener(listener: (() -> Unit)?) {
        readyListener = listener
    }

    override fun run(command: String): String? = runWithBackend(command)?.output

    fun readBatterySnapshot(): AdvancedBatterySnapshot? {
        if (!enabled) return null
        val revision = accessRevision
        val root = runCatching {
            val executor = RootExecutor()
            if (executor.run("id")?.contains("uid=0") != true) null
            else AdvancedBatteryStatsCollector.collectMonitoring(executor).apply {
                accessMethod = AdvancedBatterySnapshot.ACCESS_ROOT
                remoteUid = 0
            }.takeIf { it.hasStats() }
        }.getOrNull()
        if (!enabled || revision != accessRevision) return null
        if (root != null) return root

        val service = awaitShizukuService(revision) ?: return null
        return try {
            PrivilegedCommandUserService.requestBatterySnapshot(service)
                ?.let { AdvancedBatterySnapshot.fromBundle(it) }
                ?.takeIf { enabled && revision == accessRevision && it.hasStats() }
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "Unable to read a battery snapshot through Shizuku", error)
            synchronized(shizukuLock) {
                if (shizukuUserService === service) {
                    shizukuUserService = null
                    shizukuConnection = null
                    shizukuLock.notifyAll()
                }
            }
            ensureShizukuConnection()
            null
        }
    }

    private fun awaitShizukuService(revision: Long): IBinder? {
        synchronized(shizukuLock) {
            if (!enabled || revision != accessRevision) return null
            shizukuUserService?.takeIf { it.isBinderAlive }?.let { return it }
            if (shizukuUserService != null) {
                shizukuUserService = null
                shizukuConnection = null
            }
        }
        if (!ensureShizukuConnection()) return null
        val handler = mainHandler ?: return null
        if (Looper.myLooper() == handler.looper) return null
        return synchronized(shizukuLock) {
            try {
                val connection = shizukuConnection
                val connected =
                    awaitShizukuConnection(
                        timeoutMillis = SHIZUKU_CONNECTION_TIMEOUT_MS,
                        canWait = {
                            enabled && revision == accessRevision && connection != null && shizukuConnection === connection
                        },
                        isConnected = { shizukuUserService?.isBinderAlive == true },
                        awaitChange = { shizukuLock.wait(it) })
                if (connected) shizukuUserService else null
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
        }
    }

    fun runWithBackend(command: String): CommandResult? {
        if (!enabled) return null
        RootExecutor().run(command)?.let { return CommandResult(it, Backend.ROOT) }
        return runShizukuCommand(command)?.let { CommandResult(it, Backend.SHIZUKU) }
    }

    fun runShizukuCommand(command: String): String? {
        if (!enabled) return null
        val service = shizukuUserService
        if (service == null || !service.isBinderAlive) {
            synchronized(shizukuLock) {
                if (shizukuUserService === service) shizukuUserService = null
            }
            ensureShizukuConnection()
            return null
        }

        return try {
            PrivilegedCommandUserService.requestCommand(service, command)
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "Unable to run command through Shizuku", error)
            synchronized(shizukuLock) {
                if (shizukuUserService === service) {
                    shizukuUserService = null
                    shizukuConnection = null
                    shizukuLock.notifyAll()
                }
            }
            ensureShizukuConnection()
            null
        }
    }

    fun buildShizukuUserServiceArgs(
        context: Context, serviceClass: Class<*>, processNameSuffix: String, tag: String
    ): UserServiceArgs {
        return UserServiceArgs(ComponentName(context.packageName, serviceClass.name)).daemon(false)
            .processNameSuffix(processNameSuffix).debuggable(
                (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            ).version(installedVersionCode(context)).tag(tag)
    }

    private fun registerShizukuListenersIfNeeded() {
        synchronized(shizukuLock) {
            if (shizukuListenersRegistered) return
            shizukuListenersRegistered = true
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    private fun ensureShizukuConnection(): Boolean {
        if (!enabled) return false
        val context = appContext ?: return false
        val handler = mainHandler ?: return false
        val canBind = try {
            Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "Shizuku is not ready for privileged command access", error)
            false
        }
        if (!canBind) return false

        val connection: ShizukuUserServiceConnection
        synchronized(shizukuLock) {
            if (!enabled) return false
            if (shizukuUserService?.isBinderAlive == true || shizukuConnection != null) return true
            connection = ShizukuUserServiceConnection(
                context,
                PrivilegedCommandUserService::class.java,
                COMMAND_SERVICE_SUFFIX,
                COMMAND_SERVICE_TAG,
                onConnected = { connected, service ->
                    var notifyReady = false
                    synchronized(shizukuLock) {
                        if (enabled && shizukuConnection === connected) {
                            shizukuUserService = service
                            notifyReady = true
                        }
                        shizukuLock.notifyAll()
                    }
                    if (notifyReady) readyListener?.invoke()
                },
                onDisconnected = { disconnected ->
                    synchronized(shizukuLock) {
                        if (shizukuConnection === disconnected) {
                            shizukuUserService = null
                            shizukuConnection = null
                        }
                        shizukuLock.notifyAll()
                    }
                    ensureShizukuConnection()
                })
            // Publish the pending connection before posting its bind, so the worker can wait for it.
            shizukuConnection = connection
        }

        val bind = Runnable {
            try {
                val current =
                    synchronized(shizukuLock) { enabled && shizukuConnection === connection }
                if (current) connection.bind()
            } catch (error: Throwable) {
                synchronized(shizukuLock) {
                    if (shizukuConnection === connection) shizukuConnection = null
                    shizukuLock.notifyAll()
                }
                Log.e(LOG_TAG, "Unable to bind privileged command user service", error)
            }
        }
        if (Looper.myLooper() == handler.looper) bind.run()
        else if (!handler.post(bind)) {
            synchronized(shizukuLock) {
                if (shizukuConnection === connection) shizukuConnection = null
                shizukuLock.notifyAll()
            }
        }
        return true
    }

    private fun disconnectShizukuConnection() {
        val connection = synchronized(shizukuLock) {
            shizukuUserService = null
            shizukuConnection.also {
                shizukuConnection = null
                shizukuLock.notifyAll()
            }
        } ?: return
        val handler = mainHandler ?: return
        val unbind = Runnable {
            try {
                connection.unbind(remove = true)
            } catch (error: Throwable) {
                Log.w(LOG_TAG, "Unable to unbind privileged command user service", error)
            }
        }
        if (Looper.myLooper() == handler.looper) unbind.run() else handler.post(unbind)
    }

    private fun installedVersionCode(context: Context): Int {
        return try {
            PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0)
            ).toInt()
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Unable to read installed version code", error)
            1
        }
    }

}

internal class ShizukuUserServiceConnection(
    context: Context,
    serviceClass: Class<*>,
    processNameSuffix: String,
    tag: String,
    private val onConnected: (ShizukuUserServiceConnection, IBinder) -> Unit,
    private val onDisconnected: (ShizukuUserServiceConnection) -> Unit
) : ServiceConnection {
    val args: UserServiceArgs = PrivilegedAccess.buildShizukuUserServiceArgs(
        context, serviceClass, processNameSuffix, tag
    )

    fun bind() {
        Shizuku.bindUserService(args, this)
    }

    fun unbind(remove: Boolean) {
        Shizuku.unbindUserService(args, this, remove)
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder) {
        onConnected(this, service)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        onDisconnected(this)
    }
}

internal fun awaitShizukuConnection(
    timeoutMillis: Long,
    canWait: () -> Boolean,
    isConnected: () -> Boolean,
    awaitChange: (Long) -> Unit,
    clock: () -> Long = { System.nanoTime() / 1_000_000 }
): Boolean {
    val started = clock()
    while (canWait()) {
        if (isConnected()) return true
        val remaining = timeoutMillis - (clock() - started)
        if (remaining <= 0) return false
        awaitChange(remaining)
    }
    return false
}
