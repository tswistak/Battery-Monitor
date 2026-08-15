/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.diagnostics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

internal enum class VendorFamily(val dontKillMyAppSlug: String) {
    XIAOMI("xiaomi"), HUAWEI("huawei"), OPPO("oppo"), REALME("realme"), ONEPLUS("oneplus"), VIVO("vivo"), SAMSUNG(
        "samsung"
    ),
    ASUS("asus"), NOKIA("nokia"), LETV("other-vendors")
}

internal object BackgroundSettingsNavigator {
    fun vendorFamily(
        manufacturer: String = Build.MANUFACTURER.orEmpty(), brand: String = Build.BRAND.orEmpty()
    ): VendorFamily? {
        val maker = manufacturer.lowercase(Locale.ROOT)
        val deviceBrand = brand.lowercase(Locale.ROOT)
        return when {
            listOf(maker, deviceBrand).any {
                it.contains("xiaomi") || it.contains("redmi") || it.contains("poco")
            } -> VendorFamily.XIAOMI

            listOf(maker, deviceBrand).any {
                it.contains("huawei") || it.contains("honor")
            } -> VendorFamily.HUAWEI

            listOf(maker, deviceBrand).any { it.contains("oppo") } -> VendorFamily.OPPO
            listOf(maker, deviceBrand).any { it.contains("realme") } -> VendorFamily.REALME
            listOf(maker, deviceBrand).any { it.contains("oneplus") } -> VendorFamily.ONEPLUS

            listOf(maker, deviceBrand).any {
                it.contains("vivo") || it.contains("iqoo")
            } -> VendorFamily.VIVO

            listOf(maker, deviceBrand).any { it.contains("samsung") } -> VendorFamily.SAMSUNG
            listOf(maker, deviceBrand).any { it.contains("asus") } -> VendorFamily.ASUS
            listOf(maker, deviceBrand).any {
                it.contains("nokia") || it.contains("evenwell")
            } -> VendorFamily.NOKIA

            listOf(maker, deviceBrand).any {
                it.contains("letv") || it.contains("leeco")
            } -> VendorFamily.LETV

            else -> null
        }
    }

    fun openVendorSettings(context: Context, family: VendorFamily): Boolean {
        for (intent in intentsFor(family)) {
            if (start(context, intent)) return true
        }
        return openApplicationDetails(context)
    }

    fun openApplicationDetails(context: Context): Boolean = start(
        context, Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    )

    fun openDontKillMyApp(context: Context, family: VendorFamily?): Boolean {
        val suffix = family?.dontKillMyAppSlug?.let { "/$it" }.orEmpty()
        return start(
            context, Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com$suffix"))
        )
    }

    private fun intentsFor(family: VendorFamily): List<Intent> = componentsFor(family).map {
        Intent().setComponent(it)
    } + when (family) {
        VendorFamily.ONEPLUS -> listOf(
            Intent("com.android.settings.action.BACKGROUND_OPTIMIZE")
        )

        else -> emptyList()
    }

    private fun componentsFor(family: VendorFamily): List<ComponentName> = when (family) {
        VendorFamily.XIAOMI -> listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        )

        VendorFamily.HUAWEI -> listOf(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ), ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
        )

        VendorFamily.OPPO, VendorFamily.REALME -> listOf(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ), ComponentName(
                "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"
            ), ComponentName(
                "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"
            )
        )

        VendorFamily.ONEPLUS -> listOf(
            ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            ), ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ), ComponentName(
                "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"
            )
        )

        VendorFamily.VIVO -> listOf(
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ), ComponentName(
                "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            ), ComponentName(
                "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
            )
        )

        VendorFamily.SAMSUNG -> listOf(
            ComponentName(
                "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"
            ), ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity"
            ), ComponentName(
                "com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"
            )
        )

        VendorFamily.ASUS -> listOf(
            ComponentName(
                "com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings"
            ), ComponentName(
                "com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"
            )
        )

        VendorFamily.NOKIA -> listOf(
            ComponentName(
                "com.evenwell.powersaving.g3",
                "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity"
            )
        )

        VendorFamily.LETV -> listOf(
            ComponentName(
                "com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"
            )
        )
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: RuntimeException) {
        false
    }
}
