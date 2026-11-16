/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.monitoring.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.annotation.StringRes
import codes.swistak.batterymonitor.R

internal object NotificationChannels {
    const val CHAN_ID_MAIN = "main_004"
    const val CHAN_ID_LIVE_UPDATE = "live_update_001"
    const val CHAN_ID_A_CHARGED = "fully_charged"
    const val CHAN_ID_A_CHARGING_LIMIT = "charging_limit_met"
    const val CHAN_ID_A_DISCHARGING_LIMIT = "discharging_limit_met"
    const val CHAN_ID_A_CDROP = "charge_drops"
    const val CHAN_ID_A_CRISE = "charge_rises"
    const val CHAN_ID_A_TDROP = "temp_drops"
    const val CHAN_ID_A_TRISE = "temp_rises"
    const val CHAN_ID_A_HFAIL = "health_failure"
    const val CHAN_GROUP_ID_ALARMS = "alarms"

    private data class Definition(
        val id: String, @param:StringRes val name: Int, val alarm: Boolean = false
    )

    private val mainChannel = Definition(CHAN_ID_MAIN, R.string.main_notif_chan_name)
    private val definitions = listOf(
        mainChannel,
        Definition(CHAN_ID_LIVE_UPDATE, R.string.live_updates_notif_chan_name),
        Definition(CHAN_ID_A_CHARGED, R.string.alarm_type_fully_charged, alarm = true),
        Definition(
            CHAN_ID_A_CHARGING_LIMIT, R.string.alarm_type_charging_target_reached, alarm = true
        ),
        Definition(
            CHAN_ID_A_DISCHARGING_LIMIT,
            R.string.alarm_type_discharging_target_reached,
            alarm = true
        ),
        Definition(CHAN_ID_A_CDROP, R.string.alarm_type_charge_drops, alarm = true),
        Definition(CHAN_ID_A_CRISE, R.string.alarm_type_charge_rises, alarm = true),
        Definition(CHAN_ID_A_TDROP, R.string.alarm_type_temperature_drops, alarm = true),
        Definition(CHAN_ID_A_TRISE, R.string.alarm_type_temperature_rises, alarm = true),
        Definition(CHAN_ID_A_HFAIL, R.string.alarm_type_health_failure, alarm = true)
    )

    fun ensureForegroundChannelCreated(context: Context) {
        ensureChannel(
            context,
            context.getSystemService(NotificationManager::class.java),
            mainChannel,
            minimal = true
        )
    }

    fun ensureChannelsCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        syncAlarmGroup(context, manager, createIfMissing = true)
        for (definition in definitions) ensureChannel(context, manager, definition)
    }

    fun refreshExistingChannelNames(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        for (channel in manager.notificationChannels) {
            val definition = definitions.firstOrNull { it.id == channel.id } ?: continue
            refreshName(context, manager, definition, channel)
        }
        syncAlarmGroup(context, manager, createIfMissing = false)
    }

    private fun ensureChannel(
        context: Context,
        manager: NotificationManager,
        definition: Definition,
        minimal: Boolean = false
    ) {
        val existing = manager.getNotificationChannel(definition.id)
        if (existing != null) {
            refreshName(context, manager, definition, existing)
            return
        }
        val channel = NotificationChannel(
            definition.id,
            context.getString(definition.name),
            if (definition.alarm) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW
        )
        if (definition.alarm) {
            channel.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()
            )
            channel.enableLights(true)
            channel.lightColor = -0xcc4a1b
            channel.enableVibration(true)
            channel.vibrationPattern = longArrayOf(0, 500, 500, 500, 500, 1000, 1000, 1000, 1000)
            channel.group = CHAN_GROUP_ID_ALARMS
        } else {
            channel.setSound(null, null)
            channel.enableLights(false)
            channel.enableVibration(false)
            channel.setShowBadge(false)
        }
        // Preserve the original minimal channel before entering foreground mode.
        if (!minimal) channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        manager.createNotificationChannel(channel)
    }

    private fun refreshName(
        context: Context,
        manager: NotificationManager,
        definition: Definition,
        channel: NotificationChannel
    ) {
        val name = context.getString(definition.name)
        if (channel.name.toString() != name) {
            channel.name = name
            manager.createNotificationChannel(channel)
        }
    }

    private fun syncAlarmGroup(
        context: Context, manager: NotificationManager, createIfMissing: Boolean
    ) {
        val existing =
            manager.notificationChannelGroups.firstOrNull { it.id == CHAN_GROUP_ID_ALARMS }
        if (existing == null && !createIfMissing) return
        val name = context.getString(R.string.channel_group_name_alarms)
        if (existing?.name?.toString() == name) return
        val group = NotificationChannelGroup(CHAN_GROUP_ID_ALARMS, name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) group.description =
            existing?.description
        manager.createNotificationChannelGroup(group)
    }
}
