/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.common

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri

internal object NotificationSettingsNavigator {
    fun openLiveUpdates(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && openFirstAvailable(
                context, Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(
                    Settings.EXTRA_APP_PACKAGE, context.packageName
                )
            )
        ) return true

        return openNotifications(context)
    }

    fun openNotifications(context: Context): Boolean = openFirstAvailable(
        context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
            Settings.EXTRA_APP_PACKAGE, context.packageName
        ), appDetailsIntent(context)
    )

    fun openNotificationChannel(context: Context, channelId: String): Boolean = openFirstAvailable(
        context,
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(
            Settings.EXTRA_APP_PACKAGE, context.packageName
        ).putExtra(Settings.EXTRA_CHANNEL_ID, channelId),
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
            Settings.EXTRA_APP_PACKAGE, context.packageName
        ),
        appDetailsIntent(context)
    )

    private fun openFirstAvailable(context: Context, vararg intents: Intent): Boolean =
        intents.any { intent -> runCatching { context.startActivity(intent) }.isSuccess }

    private fun appDetailsIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()
    )
}
