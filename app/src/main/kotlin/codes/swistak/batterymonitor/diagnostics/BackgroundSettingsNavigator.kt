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
    XIAOMI("xiaomi"), HUAWEI("huawei"), HONOR("huawei"), OPPO("oppo"), REALME("realme"), ONEPLUS("oneplus"), VIVO(
        "vivo"
    ),
    SAMSUNG("samsung"), ASUS("asus"), MEIZU("meizu"), TRANSSION("tecno"), NUBIA("other-vendors"), ZTE(
        "other-vendors"
    ),
    LENOVO("lenovo"), NOKIA("nokia"), LETV("other-vendors")
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

            listOf(maker, deviceBrand).any { it.contains("honor") } -> VendorFamily.HONOR
            listOf(maker, deviceBrand).any { it.contains("huawei") } -> VendorFamily.HUAWEI

            listOf(maker, deviceBrand).any { it.contains("oppo") } -> VendorFamily.OPPO
            listOf(maker, deviceBrand).any { it.contains("realme") } -> VendorFamily.REALME
            listOf(maker, deviceBrand).any { it.contains("oneplus") } -> VendorFamily.ONEPLUS

            listOf(maker, deviceBrand).any {
                it.contains("vivo") || it.contains("iqoo")
            } -> VendorFamily.VIVO

            listOf(maker, deviceBrand).any { it.contains("samsung") } -> VendorFamily.SAMSUNG
            listOf(maker, deviceBrand).any { it.contains("asus") } -> VendorFamily.ASUS
            listOf(maker, deviceBrand).any { it.contains("meizu") } -> VendorFamily.MEIZU
            listOf(maker, deviceBrand).any {
                it.contains("tecno") || it.contains("infinix") || it.contains("itel") || it.contains(
                    "transsion"
                )
            } -> VendorFamily.TRANSSION

            listOf(maker, deviceBrand).any { it.contains("nubia") } -> VendorFamily.NUBIA
            listOf(maker, deviceBrand).any { it.contains("zte") } -> VendorFamily.ZTE
            listOf(maker, deviceBrand).any {
                it.contains("lenovo") || it == "zui"
            } -> VendorFamily.LENOVO

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
        for (intent in intentsFor(context, family)) {
            if (start(context, intent)) return true
        }
        return openPerAppBatterySettings(context) || openApplicationDetails(context)
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

    private fun intentsFor(context: Context, family: VendorFamily): List<Intent> =
        backgroundPowerIntentsFor(
            context, family
        ) + autoStartIntentsFor(family) + vendorManagerIntentsFor(context, family)

    private fun backgroundPowerIntentsFor(
        context: Context, family: VendorFamily
    ): List<Intent> = when (family) {
        VendorFamily.XIAOMI -> listOf(
            componentIntent(
                "com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
            ).putExtra("package_name", context.packageName).putExtra(
                "package_label", context.applicationInfo.loadLabel(context.packageManager)
            )
        )

        VendorFamily.HUAWEI -> listOf(
            componentIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
        )

        VendorFamily.HONOR -> listOf(
            componentIntent(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.power.ui.HwPowerManagerActivity"
            )
        )

        VendorFamily.VIVO -> listOf(
            componentIntent(
                "com.vivo.abe",
                "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
            ), componentIntent(
                "com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity"
            )
        )

        VendorFamily.SAMSUNG -> listOf(
            componentIntent(
                "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"
            ), componentIntent(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity"
            ), componentIntent(
                "com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"
            )
        )

        VendorFamily.ASUS -> listOf(
            componentIntent(
                "com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings"
            )
        )

        VendorFamily.MEIZU -> listOf(
            componentIntent("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
            componentIntent(
                "com.meizu.safe", "com.meizu.safe.powerui.PowerAppPermissionActivity"
            )
        )

        VendorFamily.ZTE -> listOf(
            componentIntent(
                "com.zte.heartyservice", "com.zte.heartyservice.setting.ClearAppSettingsActivity"
            )
        )

        VendorFamily.LENOVO -> listOf(
            componentIntent(
                "com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity"
            )
        )

        VendorFamily.LETV -> listOf(
            componentIntent(
                "com.letv.android.letvsafe", "com.letv.android.letvsafe.BackgroundAppManageActivity"
            )
        )

        else -> emptyList()
    }

    private fun autoStartIntentsFor(family: VendorFamily): List<Intent> = when (family) {
        VendorFamily.XIAOMI -> listOf(
            componentIntent(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ), Intent("miui.intent.action.OP_AUTO_START")
        )

        VendorFamily.HUAWEI -> listOf(
            componentIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            ), componentIntent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ), Intent("huawei.intent.action.HSM_BOOTAPP_MANAGER")
        )

        VendorFamily.HONOR -> listOf(
            componentIntent(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"
            ), componentIntent(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
        )

        VendorFamily.OPPO, VendorFamily.REALME -> oPlusAutoStartIntents()

        VendorFamily.ONEPLUS -> listOf(
            componentIntent(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            )
        ) + oPlusAutoStartIntents() + Intent("com.android.settings.action.BACKGROUND_OPTIMIZE")

        VendorFamily.VIVO -> listOf(
            componentIntent(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ), componentIntent(
                "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
            ), componentIntent(
                "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            )
        )

        VendorFamily.ASUS -> listOf(
            componentIntent(
                "com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"
            ), componentIntent(
                "com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"
            ).setData(Uri.parse("mobilemanager://function/entry/AutoStart"))
        )

        VendorFamily.TRANSSION -> listOf(
            componentIntent(
                "com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"
            ), componentIntent(
                "com.transsion.phonemanager",
                "com.itel.autobootmanager.activity.AutoBootMgrActivity"
            )
        )

        VendorFamily.NUBIA -> listOf(
            componentIntent(
                "cn.nubia.security2", "cn.nubia.security.appmanage.selfstart.ui.SelfStartActivity"
            )
        )

        VendorFamily.ZTE -> listOf(
            componentIntent(
                "com.zte.heartyservice", "com.zte.heartyservice.autorun.AppAutoRunManager"
            )
        )

        VendorFamily.NOKIA -> listOf(
            componentIntent(
                "com.evenwell.powersaving.g3",
                "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity"
            )
        )

        VendorFamily.LETV -> listOf(
            componentIntent(
                "com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"
            ), Intent("com.letv.android.permissionautoboot")
        )

        else -> emptyList()
    }

    private fun vendorManagerIntentsFor(
        context: Context, family: VendorFamily
    ): List<Intent> = when (family) {
        VendorFamily.MEIZU -> listOf(
            Intent("com.meizu.safe.security.SHOW_APPSEC").putExtra(
                "packageName", context.packageName
            ), componentIntent("com.meizu.safe", "com.meizu.safe.permission.PermissionMainActivity")
        )

        VendorFamily.LENOVO -> listOf(
            componentIntent(
                "com.zui.safecenter", "com.lenovo.safecenter.MainTab.LeSafeMainActivity"
            )
        )

        else -> emptyList()
    }

    private fun oPlusAutoStartIntents(): List<Intent> = listOf(
        componentIntent(
            "com.oplus.safecenter", "com.oplus.safecenter.startupapp.view.StartupAppListActivity"
        ), componentIntent(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        ), componentIntent(
            "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"
        ), componentIntent(
            "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"
        )
    )

    private fun openPerAppBatterySettings(context: Context): Boolean = start(
        context, Intent(
            ACTION_VIEW_ADVANCED_POWER_USAGE_DETAIL, Uri.parse("package:${context.packageName}")
        )
    )

    private fun componentIntent(packageName: String, className: String): Intent =
        Intent().setComponent(ComponentName(packageName, className))

    private const val ACTION_VIEW_ADVANCED_POWER_USAGE_DETAIL =
        "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL"

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: RuntimeException) {
        false
    }
}
