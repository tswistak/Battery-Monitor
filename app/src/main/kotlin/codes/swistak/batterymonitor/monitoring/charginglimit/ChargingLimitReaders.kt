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
package codes.swistak.batterymonitor.monitoring.charginglimit

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import codes.swistak.batterymonitor.common.CommandExecutor
import codes.swistak.batterymonitor.common.PrivilegedShellExecutor
import java.io.File


internal class AndroidSettingReader(
    context: Context,
    private val privilegedAccessEnabled: () -> Boolean,
    private val privilegedCommand: (String) -> String?
) : SettingReader {
    private val contentResolver = context.applicationContext.contentResolver

    override fun read(namespace: SettingNamespace, key: String): ReadResult<String> {
        val direct = readDirect(namespace, key)
        return readSettingWithPrivilegedFallback(
            direct, privilegedAccessEnabled(), namespace, key, privilegedCommand
        )
    }

    private fun readDirect(namespace: SettingNamespace, key: String): ReadResult<String> {
        return try {
            val value = when (namespace) {
                SettingNamespace.SECURE -> Settings.Secure.getString(contentResolver, key)
                SettingNamespace.GLOBAL -> Settings.Global.getString(contentResolver, key)
                SettingNamespace.SYSTEM -> Settings.System.getString(contentResolver, key)
                SettingNamespace.LINEAGE_SYSTEM -> return readLineageSystem(key)
            }
            if (value == null) {
                ReadResult.Absent(ReadSource.CONTENT_RESOLVER)
            } else {
                ReadResult.Value(value, ReadSource.CONTENT_RESOLVER)
            }
        } catch (_: SecurityException) {
            ReadResult.Failed(
                ReadFailureReason.ACCESS_DENIED, ReadSource.CONTENT_RESOLVER
            )
        } catch (_: Throwable) {
            ReadResult.Failed(ReadFailureReason.EXCEPTION, ReadSource.CONTENT_RESOLVER)
        }
    }

    private fun readLineageSystem(key: String): ReadResult<String> {
        val uri = Uri.Builder().scheme("content").authority("lineagesettings").appendPath("system")
            .appendPath(key).build()
        return contentResolver.query(uri, arrayOf(LINEAGE_VALUE_COLUMN), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    ReadResult.Absent(ReadSource.CONTENT_RESOLVER)
                } else {
                    val value = cursor.getString(cursor.getColumnIndexOrThrow(LINEAGE_VALUE_COLUMN))
                    if (value == null) ReadResult.Absent(ReadSource.CONTENT_RESOLVER)
                    else ReadResult.Value(value, ReadSource.CONTENT_RESOLVER)
                }
            } ?: ReadResult.Failed(ReadFailureReason.NO_DATA, ReadSource.CONTENT_RESOLVER)
    }

    private companion object {
        const val LINEAGE_VALUE_COLUMN = "value"
    }

}

internal fun readSettingWithPrivilegedFallback(
    direct: ReadResult<String>,
    privilegedAccessEnabled: Boolean,
    namespace: SettingNamespace,
    key: String,
    privilegedCommand: (String) -> String?
): ReadResult<String> {
    if (direct is ReadResult.Value || !privilegedAccessEnabled) return direct
    val command = when (namespace) {
        SettingNamespace.LINEAGE_SYSTEM -> "content query --uri content://lineagesettings/system/$key --projection value"

        else -> "settings get ${namespace.commandName} $key"
    }
    return parsePrivilegedSettingOutput(privilegedCommand(command), namespace)
}

internal fun parsePrivilegedSettingOutput(
    output: String?, namespace: SettingNamespace? = null
): ReadResult<String> {
    output ?: return ReadResult.Failed(
        ReadFailureReason.COMMAND_FAILED, ReadSource.PRIVILEGED_SHELL
    )
    val value = output.trim()
    if (namespace == SettingNamespace.LINEAGE_SYSTEM) {
        if (value == "No result found.") {
            return ReadResult.Absent(ReadSource.PRIVILEGED_SHELL)
        }
        val marker = "value="
        val markerIndex = value.lastIndexOf(marker)
        if (markerIndex < 0) {
            return ReadResult.Failed(
                ReadFailureReason.INVALID_OUTPUT, ReadSource.PRIVILEGED_SHELL
            )
        }
        val lineageValue = value.substring(markerIndex + marker.length).trim()
        return if (lineageValue.isEmpty()) {
            ReadResult.Absent(ReadSource.PRIVILEGED_SHELL)
        } else {
            ReadResult.Value(lineageValue, ReadSource.PRIVILEGED_SHELL)
        }
    }
    return when {
        value.equals("null", ignoreCase = true) -> ReadResult.Absent(ReadSource.PRIVILEGED_SHELL)

        value.isEmpty() -> ReadResult.Failed(
            ReadFailureReason.INVALID_OUTPUT, ReadSource.PRIVILEGED_SHELL
        )

        else -> ReadResult.Value(value, ReadSource.PRIVILEGED_SHELL)
    }
}

internal class AndroidSystemPropertyReader(
    private val executor: CommandExecutor = PrivilegedShellExecutor()
) : SystemPropertyReader {
    override fun read(key: String): ReadResult<String> {
        val output = executor.run("getprop $key") ?: return ReadResult.Failed(
            ReadFailureReason.COMMAND_FAILED, ReadSource.APP_SHELL
        )
        val value = output.trim()
        return if (value.isEmpty()) {
            ReadResult.Absent(ReadSource.APP_SHELL)
        } else {
            ReadResult.Value(value, ReadSource.APP_SHELL)
        }
    }
}

internal object AndroidDeviceProfile {
    fun current(context: Context): DeviceProfile {
        val properties = AndroidSystemPropertyReader()
        val appContext = context.applicationContext
        return DeviceProfile(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            properties = mapOf(
                ChargingLimitPropertyKeys.LINEAGE_VERSION to properties.read(
                    ChargingLimitPropertyKeys.LINEAGE_VERSION
                )
            ),
            hasLineageOsFeature = DeviceProfileSignals.hasLineageFeature(appContext.packageManager),
            hasLineageSettingsProvider = DeviceProfileSignals.hasLineageProvider(
                appContext.packageManager
            ),
            hasGrapheneOsSystemPackage = DeviceProfileSignals.hasGrapheneSystemPackage(appContext)
        )
    }
}

internal class AndroidPowerSupplyReader(
    private val privilegedAccessEnabled: () -> Boolean,
    private val privilegedCommand: (String) -> String?
) : PowerSupplyReader {
    override fun read(relativePath: String): ReadResult<String> {
        if (!SAFE_RELATIVE_PATH.matches(relativePath)) {
            return ReadResult.Failed(ReadFailureReason.INVALID_OUTPUT, ReadSource.APP_FILE)
        }
        val path = File(POWER_SUPPLY_ROOT, relativePath)
        val direct = try {
            if (!path.exists()) ReadResult.Absent(ReadSource.APP_FILE)
            else ReadResult.Value(path.readText().trim(), ReadSource.APP_FILE)
        } catch (_: SecurityException) {
            ReadResult.Failed(ReadFailureReason.ACCESS_DENIED, ReadSource.APP_FILE)
        } catch (_: Throwable) {
            ReadResult.Failed(ReadFailureReason.EXCEPTION, ReadSource.APP_FILE)
        }
        if (direct is ReadResult.Value || !privilegedAccessEnabled()) return direct
        val output =
            privilegedCommand("cat ${path.absolutePath}") ?: return direct as? ReadResult.Absent
                ?: ReadResult.Failed(
                    ReadFailureReason.COMMAND_FAILED, ReadSource.PRIVILEGED_SHELL
                )
        return ReadResult.Value(output.trim(), ReadSource.PRIVILEGED_SHELL)
    }

    private companion object {
        const val POWER_SUPPLY_ROOT = "/sys/class/power_supply"
        val SAFE_RELATIVE_PATH = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    }
}

internal class AndroidBatteryChargingStateReader(context: Context) : BatteryChargingStateReader {
    private val appContext = context.applicationContext

    override fun read(): ReadResult<BatteryChargingSnapshot> {
        return try {
            val battery = appContext.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return ReadResult.Failed(ReadFailureReason.NO_DATA, ReadSource.SYSTEM_API)
            ReadResult.Value(
                BatteryChargingSnapshot(
                    plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
                    chargingState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        readChargingState(battery)
                    } else {
                        0
                    }
                ), ReadSource.SYSTEM_API
            )
        } catch (_: SecurityException) {
            ReadResult.Failed(ReadFailureReason.ACCESS_DENIED, ReadSource.SYSTEM_API)
        } catch (_: Throwable) {
            ReadResult.Failed(ReadFailureReason.EXCEPTION, ReadSource.SYSTEM_API)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun readChargingState(battery: Intent): Int =
        battery.getIntExtra(BatteryManager.EXTRA_CHARGING_STATUS, 0)
}
