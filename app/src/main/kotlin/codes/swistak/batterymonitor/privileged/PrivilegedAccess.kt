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
import codes.swistak.batterymonitor.common.CommandExecutor
import codes.swistak.batterymonitor.common.RootExecutor
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnBinderDeadListener
import rikka.shizuku.Shizuku.OnBinderReceivedListener
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener
import rikka.shizuku.Shizuku.UserServiceArgs
import rikka.shizuku.ShizukuProvider

internal object PrivilegedAccess : CommandExecutor {
    private const val LOG_TAG = "codes.swistak.batterymonitor - PrivilegedAccess"
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 7001
    private const val COMMAND_SERVICE_SUFFIX = "privileged_commands"
    private const val COMMAND_SERVICE_TAG = "privileged_commands"

    private val shizukuLock = Any()
    private var appContext: Context? = null
    private var mainHandler: Handler? = null

    @Volatile
    private var enabled = false
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
        }
    }
    private val permissionResultListener =
        OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
                ensureShizukuConnection()
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

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) ensureShizukuConnection() else disconnectShizukuConnection()
    }

    fun setReadyListener(listener: (() -> Unit)?) {
        readyListener = listener
    }

    override fun run(command: String): String? {
        if (!enabled) return null
        return RootExecutor().run(command) ?: runShizukuCommand(command)
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

    private fun ensureShizukuConnection() {
        if (!enabled) return
        val context = appContext ?: return
        val handler = mainHandler ?: return
        if (Looper.myLooper() != handler.looper) {
            handler.post(::ensureShizukuConnection)
            return
        }

        val canBind = try {
            Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "Shizuku is not ready for privileged command access", error)
            false
        }
        if (!canBind) return

        val connection: ShizukuUserServiceConnection
        synchronized(shizukuLock) {
            if (shizukuUserService?.isBinderAlive == true || shizukuConnection != null) return
            connection = ShizukuUserServiceConnection(
                context,
                PrivilegedCommandUserService::class.java,
                COMMAND_SERVICE_SUFFIX,
                COMMAND_SERVICE_TAG,
                onConnected = { connected, service ->
                    var notifyReady = false
                    synchronized(shizukuLock) {
                        if (shizukuConnection === connected) {
                            shizukuUserService = service
                            notifyReady = true
                        }
                    }
                    if (notifyReady) readyListener?.invoke()
                },
                onDisconnected = { disconnected ->
                    synchronized(shizukuLock) {
                        if (shizukuConnection === disconnected) {
                            shizukuUserService = null
                            shizukuConnection = null
                        }
                    }
                    ensureShizukuConnection()
                })
            shizukuConnection = connection
        }

        try {
            connection.bind()
        } catch (error: Throwable) {
            synchronized(shizukuLock) {
                if (shizukuConnection === connection) shizukuConnection = null
            }
            Log.e(LOG_TAG, "Unable to bind privileged command user service", error)
        }
    }

    private fun disconnectShizukuConnection() {
        val handler = mainHandler ?: return
        if (Looper.myLooper() != handler.looper) {
            handler.post(::disconnectShizukuConnection)
            return
        }

        val connection = synchronized(shizukuLock) {
            shizukuUserService = null
            shizukuConnection.also { shizukuConnection = null }
        } ?: return
        try {
            connection.unbind(remove = true)
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "Unable to unbind privileged command user service", error)
        }
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
