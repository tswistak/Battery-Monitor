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
package codes.swistak.batterymonitor.monitoring

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
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
import java.io.File
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

internal object BatteryCurrent {
    private const val LOG_TAG = "codes.swistak.batterymonitor - BatteryCurrent"
    private const val SYSFS_ROOT = "/sys/class/power_supply"
    private const val MAX_DISPLAY_DIGITS = 6
    private const val MAX_DECIMAL_PLACES = 3
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 7001

    private var batteryManager: BatteryManager? = null
    private var appContext: Context? = null
    private var mainHandler: Handler? = null
    private var usePrivilegedAccess = false
    private var multiplier = 1
    private var currentNowFile: File? = null
    private var currentAverageFile: File? = null
    private val shizukuLock = Any()
    private var shizukuListenersRegistered = false
    private var shizukuMultiProcessEnabled = false
    private var shizukuConnection: ShizukuConnection? = null
    private var shizukuReadyListener: (() -> Unit)? = null

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

    fun setContext(context: Context) {
        appContext = context.applicationContext
        batteryManager = appContext?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (mainHandler == null) mainHandler = Handler(Looper.getMainLooper())
        registerShizukuListenersIfNeeded()
        if (usePrivilegedAccess) ensureShizukuConnection()
    }

    @Synchronized
    fun enableShizukuMultiProcessSupport(context: Context) {
        if (shizukuMultiProcessEnabled) return

        try {
            ShizukuProvider.enableMultiProcessSupport(false)
            ShizukuProvider.requestBinderForNonProviderProcess(context.applicationContext)
            shizukuMultiProcessEnabled = true
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "Unable to initialize Shizuku in the monitoring process", error)
        }
    }

    fun setUsePrivilegedAccess(enabled: Boolean) {
        usePrivilegedAccess = enabled
        if (enabled) ensureShizukuConnection()
    }

    fun setShizukuReadyListener(listener: (() -> Unit)?) {
        shizukuReadyListener = listener
    }

    fun setMultiplier(value: Int) {
        multiplier = value
    }

    val current: Double?
        get() = read(false)

    val avgCurrent: Double?
        get() = read(true)

    private fun read(average: Boolean, appliedMultiplier: Int = multiplier): Double? {
        return readAndroidSystem(average, appliedMultiplier) ?: readFileSystem(
            average, appliedMultiplier
        ) ?: if (usePrivilegedAccess) readPrivileged(average, appliedMultiplier) else null
    }

    internal fun readForMultiplierDetection(average: Boolean): Double? {
        return read(average, appliedMultiplier = 1)
    }

    private fun readAndroidSystem(average: Boolean, appliedMultiplier: Int): Double? {
        val manager = batteryManager ?: return null
        val property = if (average) {
            BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE
        } else {
            BatteryManager.BATTERY_PROPERTY_CURRENT_NOW
        }
        val microAmps = manager.getIntProperty(property)
        if (microAmps == Int.MIN_VALUE) return null

        return scaleMicroAmps(microAmps.toLong(), appliedMultiplier)
    }

    private fun readFileSystem(average: Boolean, appliedMultiplier: Int): Double? {
        val cachedFile = if (average) currentAverageFile else currentNowFile
        val cachedValue = cachedFile?.let(::readLong)
        if (cachedValue != null) return scaleMicroAmps(cachedValue, appliedMultiplier)

        if (average) currentAverageFile = null else currentNowFile = null
        val discoveredFile = findCurrentFile(File(SYSFS_ROOT), average) ?: return null
        val microAmps = readLong(discoveredFile) ?: return null
        if (average) {
            currentAverageFile = discoveredFile
        } else {
            currentNowFile = discoveredFile
        }

        return scaleMicroAmps(microAmps, appliedMultiplier)
    }

    internal fun findCurrentFile(root: File, average: Boolean): File? {
        val fileName = if (average) "current_avg" else "current_now"
        val directories = try {
            root.listFiles()?.filter(File::isDirectory).orEmpty()
        } catch (e: SecurityException) {
            Log.d(LOG_TAG, "Unable to enumerate ${root.path}", e)
            emptyList()
        }

        return directories.asSequence().mapNotNull { directory ->
            val rank = batterySupplyRank(directory) ?: return@mapNotNull null
            val currentFile = File(directory, fileName)
            if (readLong(currentFile) == null) return@mapNotNull null
            RankedCurrentFile(currentFile, rank, directory.name.lowercase())
        }.sortedWith(compareBy<RankedCurrentFile> { it.rank }.thenBy { it.supplyName })
            .map { it.file }.firstOrNull()
    }

    private fun batterySupplyRank(directory: File): Int? {
        val supplyName = directory.name.lowercase()
        val declaredType =
            readText(File(directory, "type")) ?: readPowerSupplyType(File(directory, "uevent"))
        val hasBatteryType = declaredType.equals("Battery", ignoreCase = true)
        val hasFuelGaugeType = declaredType.equals("BMS", ignoreCase = true) || declaredType.equals(
            "Unknown", ignoreCase = true
        )
        if (declaredType != null && !hasBatteryType && !hasFuelGaugeType) return null

        return when {
            supplyName == "battery" -> 0
            hasBatteryType -> 1
            supplyName == "bms" -> 2
            "battery" in supplyName -> 3
            "fuelgauge" in supplyName || "fuel-gauge" in supplyName -> 4
            supplyName.endsWith("_fg") || supplyName.endsWith("-fg") -> 5
            else -> null
        }
    }

    private fun readPowerSupplyType(ueventFile: File): String? {
        val uevent = readText(ueventFile) ?: return null
        return uevent.lineSequence().firstOrNull { it.startsWith("POWER_SUPPLY_TYPE=") }
            ?.substringAfter('=')?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun readText(file: File): String? {
        return try {
            if (!file.isFile || !file.canRead()) return null
            file.readText().trim().takeIf(String::isNotEmpty)
        } catch (_: Exception) {
            null
        }
    }

    private fun readPrivileged(average: Boolean, appliedMultiplier: Int): Double? {
        val property = if (average) "current_average" else "current_now"
        val microAmps = readPrivilegedMicroAmps(property, RootExecutor()) {
            readViaShizuku(average)
        }
        return microAmps?.let { scaleMicroAmps(it, appliedMultiplier) }
    }

    internal fun readPrivilegedMicroAmps(
        property: String, executor: CommandExecutor, shizukuFallback: () -> Long?
    ): Long? {
        val refreshed = firstLong(executor.run("cmd battery get -f $property 2>/dev/null"))
        return refreshed ?: firstLong(
            executor.run("cmd battery get $property 2>/dev/null")
        ) ?: shizukuFallback()
    }

    private fun readViaShizuku(average: Boolean): Long? {
        val service = shizukuUserService
        if (service == null || !service.isBinderAlive) {
            synchronized(shizukuLock) {
                if (shizukuUserService === service) shizukuUserService = null
            }
            ensureShizukuConnection()
            return null
        }

        return try {
            BatteryCurrentUserService.requestCurrent(service, average)
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "Unable to read battery current through Shizuku", error)
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
        if (!usePrivilegedAccess) return
        val context = appContext ?: return
        val handler = mainHandler ?: return
        if (Looper.myLooper() != handler.looper) {
            handler.post(::ensureShizukuConnection)
            return
        }

        val canBind = try {
            Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "Shizuku is not ready for battery current access", error)
            false
        }
        if (!canBind) return

        val connection: ShizukuConnection
        synchronized(shizukuLock) {
            if (shizukuUserService?.isBinderAlive == true || shizukuConnection != null) return
            val args = buildShizukuUserServiceArgs(context)
            connection = ShizukuConnection(args)
            shizukuConnection = connection
        }

        try {
            Shizuku.bindUserService(connection.args, connection)
        } catch (error: Throwable) {
            synchronized(shizukuLock) {
                if (shizukuConnection === connection) shizukuConnection = null
            }
            Log.e(LOG_TAG, "Unable to bind battery current Shizuku user service", error)
        }
    }

    private fun buildShizukuUserServiceArgs(context: Context): UserServiceArgs {
        return UserServiceArgs(
            ComponentName(context.packageName, BatteryCurrentUserService::class.java.name)
        ).daemon(false).processNameSuffix("battery_current")
            .debuggable((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            .version(installedVersionCode(context)).tag("battery_current")
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

    private class ShizukuConnection(val args: UserServiceArgs) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            var notifyReady = false
            synchronized(shizukuLock) {
                if (shizukuConnection === this) {
                    shizukuUserService = service
                    notifyReady = true
                }
            }
            if (notifyReady) shizukuReadyListener?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(shizukuLock) {
                if (shizukuConnection === this) {
                    shizukuUserService = null
                    shizukuConnection = null
                }
            }
            ensureShizukuConnection()
        }
    }

    private fun readLong(file: File): Long? {
        return try {
            if (!file.isFile || !file.canRead()) return null
            file.bufferedReader().use { it.readLine()?.trim()?.toLongOrNull() }
        } catch (e: Exception) {
            Log.d(LOG_TAG, "Unable to read battery current from ${file.path}", e)
            null
        }
    }

    private fun firstLong(value: String?): Long? {
        return value?.trim()?.split(Regex("\\s+"))?.firstNotNullOfOrNull { it.toLongOrNull() }
    }

    internal fun scaleMicroAmps(
        microAmps: Long, appliedMultiplier: Int = multiplier
    ): Double {
        return microAmps.toDouble() * appliedMultiplier.toDouble() / 1000.0
    }

    internal fun formatMilliAmps(
        milliAmps: Double, locale: Locale = Locale.getDefault()
    ): String {
        val absoluteValue = abs(milliAmps)
        val integerDigits = if (absoluteValue < 1.0) 1 else floor(log10(absoluteValue)).toInt() + 1
        val decimalPlaces = (MAX_DISPLAY_DIGITS - integerDigits).coerceIn(0, MAX_DECIMAL_PLACES)
        return DecimalFormat("0", DecimalFormatSymbols.getInstance(locale)).apply {
            isGroupingUsed = false
            minimumFractionDigits = 0
            maximumFractionDigits = decimalPlaces
            roundingMode = RoundingMode.HALF_UP
        }.format(milliAmps)
    }

    private data class RankedCurrentFile(
        val file: File, val rank: Int, val supplyName: String
    )
}
