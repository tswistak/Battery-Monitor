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
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.alarms.AlarmConfiguration
import codes.swistak.batterymonitor.alarms.AlarmConflictException
import codes.swistak.batterymonitor.alarms.AlarmDatabase
import codes.swistak.batterymonitor.alarms.AlarmRecord
import codes.swistak.batterymonitor.alarms.AlarmRestoreChannelIds
import codes.swistak.batterymonitor.alarms.AlarmSnapshot
import codes.swistak.batterymonitor.alarms.AlarmSoundSettings
import codes.swistak.batterymonitor.alarms.AlarmThresholdKey
import codes.swistak.batterymonitor.alarms.AlarmVolumeCategory
import codes.swistak.batterymonitor.alarms.LogicalAlarm
import codes.swistak.batterymonitor.alarms.projectAlarms
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.temperatureUnit
import java.io.RandomAccessFile
import java.text.NumberFormat
import java.util.UUID
import kotlin.math.roundToInt

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
    private const val MANAGED_ALARM_PREFIX = "alarm_settings_"
    private val alarmDefinitions = definitions.filter(Definition::alarm).associateBy(Definition::id)

    fun alarmChannelId(type: String, configuration: AlarmConfiguration): String =
        configuration.channelId ?: type

    fun ensureAlarmChannel(context: Context, type: String, alarmId: Int? = null): String? =
        withAlarmChannelLock(context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val snapshot = readAlarmSnapshot(context)
            val configuration = if (alarmId == null) snapshot.effective(type)
            else snapshot.custom[alarmId] ?: return@withAlarmChannelLock null
            val id = alarmChannelId(type, configuration)
            syncAlarmGroup(context, manager, createIfMissing = true)
            if (id == type) ensureChannel(context, manager, alarmDefinitions.getValue(type))
            syncManagedAlarms(context, manager, snapshot, createMissing = true)
            id.takeIf { manager.getNotificationChannel(it) != null }
        }

    fun saveAlarmSettings(
        context: Context,
        database: AlarmDatabase,
        type: String,
        alarm: LogicalAlarm?,
        expected: AlarmConfiguration?,
        sound: AlarmSoundSettings,
        replacementConfirmed: Boolean
    ) = withAlarmChannelLock(context) {
        require(alarm == null || alarm.type == type && alarm.thresholdKey != null)
        val definition = alarmDefinitions.getValue(type)
        val snapshot = database.getSnapshot()
        val current = if (alarm == null) snapshot.shared[type] else snapshot.custom[alarm.id]
        if (current != expected) throw AlarmConflictException()

        val manager = context.getSystemService(NotificationManager::class.java)
        val fallback = snapshot.effective(type)
        val currentId = alarmChannelId(type, current ?: fallback)
        val source = manager.getNotificationChannel(currentId)
        val createCustom = alarm != null && current == null
        val changeCategory = sound.volumeCategory != (current ?: fallback).sound.volumeCategory
        check(!changeCategory || createCustom || source == null || replacementConfirmed)
        val id = if (createCustom || changeCategory) newChannelId() else current?.channelId
        val staged = if (createCustom || changeCategory) id else null
        try {
            if (staged != null) {
                syncAlarmGroup(context, manager, createIfMissing = true)
                createAlarmChannel(
                    context, manager, definition, staged, alarm?.thresholdKey, sound, source
                )
            }
            val configuration = AlarmConfiguration(sound, id)
            if (alarm == null) database.saveSharedSettings(type, expected, configuration)
            else database.saveCustomSettings(alarm, expected, configuration)
        } catch (failure: Exception) {
            staged?.let(manager::deleteNotificationChannel)
            throw failure
        }
        reconcileSavedAlarms(context, manager, database)
    }

    fun alarmRestoreMayReplaceChannels(
        context: Context,
        database: AlarmDatabase,
        shared: Map<String, AlarmSoundSettings>,
        custom: Map<AlarmThresholdKey, AlarmSoundSettings>
    ): Boolean = restoreMayReplaceChannels(
        context.getSystemService(NotificationManager::class.java),
        database.getSnapshot(),
        shared,
        custom
    )

    fun restoreAlarms(
        context: Context,
        database: AlarmDatabase,
        records: List<AlarmRecord>,
        shared: Map<String, AlarmSoundSettings>,
        custom: Map<AlarmThresholdKey, AlarmSoundSettings>,
        allowChannelChanges: Boolean
    ): Boolean = withAlarmChannelLock(context) {
        val current = database.getSnapshot()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!allowChannelChanges && restoreMayReplaceChannels(manager, current, shared, custom)) {
            return@withAlarmChannelLock false
        }
        val oldCustom = customByThreshold(current)
        val staged = mutableListOf<String>()
        fun stage(
            definition: Definition,
            key: AlarmThresholdKey?,
            sound: AlarmSoundSettings,
            sourceId: String
        ): String {
            val id = newChannelId()
            staged += id
            syncAlarmGroup(context, manager, createIfMissing = true)
            createAlarmChannel(
                context,
                manager,
                definition,
                id,
                key,
                sound,
                manager.getNotificationChannel(sourceId)
            )
            return id
        }

        var committed = false
        try {
            val sharedIds = buildMap {
                for ((type, definition) in alarmDefinitions) {
                    val previous = current.effective(type)
                    val sound = shared[type] ?: AlarmSoundSettings()
                    if (sound.volumeCategory != previous.sound.volumeCategory) {
                        put(type, stage(definition, null, sound, alarmChannelId(type, previous)))
                    }
                }
            }
            val customIds = buildMap {
                for ((key, sound) in custom) {
                    val previous = oldCustom[key]
                    if (previous?.channelId == null || previous.sound.volumeCategory != sound.volumeCategory) {
                        val sourceId = previous?.channelId ?: sharedIds[key.type] ?: alarmChannelId(
                            key.type,
                            current.effective(key.type)
                        )
                        put(key, stage(alarmDefinitions.getValue(key.type), key, sound, sourceId))
                    }
                }
            }
            committed = database.replaceAllAlarms(
                records,
                shared,
                custom,
                AlarmRestoreChannelIds(sharedIds, customIds),
                expected = current
            )
        } finally {
            if (!committed) staged.forEach(manager::deleteNotificationChannel)
        }
        if (committed) reconcileSavedAlarms(context, manager, database)
        committed
    }

    fun syncAlarmChannels(context: Context) = withAlarmChannelLock(context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val snapshot = readAlarmSnapshot(context)
        syncManagedAlarms(context, manager, snapshot, createMissing = true)
        AlarmNotifications.reconcileConfiguration(context, snapshot)
    }

    private fun readAlarmSnapshot(context: Context): AlarmSnapshot {
        val database = AlarmDatabase(context)
        return try {
            database.getSnapshot()
        } finally {
            database.close()
        }
    }

    private fun reconcileSavedAlarms(
        context: Context, manager: NotificationManager, database: AlarmDatabase
    ) {
        // A cleanup error must not report a failed save after the database commits.
        runCatching {
            val snapshot = database.getSnapshot()
            syncManagedAlarms(context, manager, snapshot, createMissing = true)
            AlarmNotifications.reconcileConfiguration(context, snapshot)
        }.onFailure {
            Log.w(
                "NotificationChannels", "Could not reconcile saved alarm channels", it
            )
        }
    }

    private fun restoreMayReplaceChannels(
        manager: NotificationManager,
        current: AlarmSnapshot,
        shared: Map<String, AlarmSoundSettings>,
        custom: Map<AlarmThresholdKey, AlarmSoundSettings>
    ): Boolean = alarmDefinitions.keys.any { type ->
        val previous = current.effective(type)
        previous.sound.volumeCategory != (shared[type]
            ?: AlarmSoundSettings()).volumeCategory && manager.getNotificationChannel(
            alarmChannelId(
                type,
                previous
            )
        ) != null
    } || customByThreshold(current).any { (key, previous) ->
        previous.sound.volumeCategory != custom[key]?.volumeCategory && previous.channelId?.let {
            manager.getNotificationChannel(
                it
            )
        } != null
    }

    private fun customByThreshold(snapshot: AlarmSnapshot): Map<AlarmThresholdKey, AlarmConfiguration> =
        buildMap {
            for (alarm in projectAlarms(snapshot.rules).types.flatMap { it.alarms }) {
                val key = alarm.thresholdKey ?: continue
                val configuration = snapshot.custom[alarm.id] ?: continue
                put(key, configuration)
            }
        }

    private fun syncManagedAlarms(
        context: Context,
        manager: NotificationManager,
        snapshot: AlarmSnapshot,
        createMissing: Boolean
    ) {
        val retained = mutableSetOf<String>()
        fun sync(
            definition: Definition,
            key: AlarmThresholdKey?,
            configuration: AlarmConfiguration,
            sourceId: String
        ) {
            val id = configuration.channelId ?: return
            retained += id
            val channel = manager.getNotificationChannel(id)
            if (channel != null) {
                refreshName(manager, channel, alarmName(context, definition, key))
            } else if (createMissing) {
                syncAlarmGroup(context, manager, createIfMissing = true)
                createAlarmChannel(
                    context,
                    manager,
                    definition,
                    id,
                    key,
                    configuration.sound,
                    manager.getNotificationChannel(sourceId)
                )
            }
        }
        for ((type, definition) in alarmDefinitions) {
            val shared = snapshot.effective(type)
            sync(definition, null, shared, type)
            val original = manager.getNotificationChannel(type) ?: continue
            val name = context.getString(definition.name)
            refreshName(
                manager, original, if (shared.channelId == null) name
                else context.getString(R.string.alarm_inactive_channel_name, name)
            )
        }
        for ((key, configuration) in customByThreshold(snapshot)) {
            val definition = alarmDefinitions.getValue(key.type)
            sync(
                definition,
                key,
                configuration,
                alarmChannelId(key.type, snapshot.effective(key.type))
            )
        }
        for (channel in manager.notificationChannels) {
            if (channel.id.startsWith(MANAGED_ALARM_PREFIX) && channel.id !in retained) {
                manager.deleteNotificationChannel(channel.id)
            }
        }
    }

    private fun newChannelId(): String = MANAGED_ALARM_PREFIX + UUID.randomUUID().toString()
    
    @Synchronized
    private fun <T> withAlarmChannelLock(context: Context, operation: () -> T): T =
        RandomAccessFile(
            context.noBackupFilesDir.resolve("alarm-channels.lock"), "rw"
        ).use { file ->
            file.channel.lock().use { operation() }
        }

    private fun alarmName(
        context: Context, definition: Definition, key: AlarmThresholdKey?
    ): String {
        val type = context.getString(definition.name)
        if (key == null) return type
        val locale = context.resources.configuration.locales[0]
        val value = if (key.type == CHAN_ID_A_TDROP || key.type == CHAN_ID_A_TRISE) {
            val fahrenheit =
                context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
                    .temperatureUnit(context.getString(R.string.default_temperature_unit)).convertToFahrenheit
            val measure = if (fahrenheit) {
                Measure((key.threshold * 1.8).roundToInt() / 10.0 + 32, MeasureUnit.FAHRENHEIT)
            } else Measure(key.threshold / 10.0, MeasureUnit.CELSIUS)
            MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT).format(measure)
        } else NumberFormat.getPercentInstance(locale).format(key.threshold / 100.0)
        return context.getString(R.string.alarm_custom_channel_name, type, value)
    }

    private fun createAlarmChannel(
        context: Context,
        manager: NotificationManager,
        definition: Definition,
        id: String,
        key: AlarmThresholdKey?,
        settings: AlarmSoundSettings,
        source: NotificationChannel?
    ) {
        val channel = NotificationChannel(
            id,
            alarmName(context, definition, key),
            source?.importance ?: NotificationManager.IMPORTANCE_HIGH
        ).apply {
            group = CHAN_GROUP_ID_ALARMS
            if (source == null) {
                setDefaultAlarmOptions(this, settings.volumeCategory)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            } else {
                description = source.description
                val audio = source.audioAttributes?.let { AudioAttributes.Builder(it) }
                    ?: AudioAttributes.Builder()
                setSound(source.sound, audio.setUsage(settings.volumeCategory.usage).build())
                enableLights(source.shouldShowLights())
                lightColor = source.lightColor
                if (Build.VERSION.SDK_INT >= 35 && source.vibrationEffect != null) {
                    vibrationEffect = source.vibrationEffect
                } else vibrationPattern = source.vibrationPattern
                enableVibration(source.shouldVibrate())
                setShowBadge(source.canShowBadge())
                lockscreenVisibility = source.lockscreenVisibility
                if (manager.isNotificationPolicyAccessGranted) setBypassDnd(source.canBypassDnd())
            }
        }
        manager.createNotificationChannel(channel)
        checkNotNull(manager.getNotificationChannel(id)) { "Android did not register the alarm channel" }
    }

    private val AlarmVolumeCategory.usage: Int
        get() = when (this) {
            AlarmVolumeCategory.NOTIFICATIONS -> AudioAttributes.USAGE_NOTIFICATION
            AlarmVolumeCategory.ALARMS -> AudioAttributes.USAGE_ALARM
            AlarmVolumeCategory.MEDIA -> AudioAttributes.USAGE_MEDIA
        }

    private fun setDefaultAlarmOptions(
        channel: NotificationChannel, category: AlarmVolumeCategory
    ) {
        channel.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder().setUsage(category.usage).build()
        )
        channel.enableLights(true)
        channel.lightColor = -0xcc4a1b
        channel.enableVibration(true)
        channel.vibrationPattern = longArrayOf(0, 500, 500, 500, 500, 1000, 1000, 1000, 1000)
        channel.group = CHAN_GROUP_ID_ALARMS
    }

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

    fun refreshExistingChannelNames(
        context: Context, runManagedRefresh: (() -> Unit) -> Unit = { refresh -> refresh() }
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channels = manager.notificationChannels
        val hasManagedAlarms = channels.any { it.id.startsWith(MANAGED_ALARM_PREFIX) }
        for (channel in channels) {
            val definition = definitions.firstOrNull { it.id == channel.id } ?: continue
            if (definition.alarm && hasManagedAlarms) continue
            refreshName(context, manager, definition, channel)
        }
        syncAlarmGroup(context, manager, createIfMissing = false)
        if (hasManagedAlarms) runManagedRefresh {
            runCatching {
                withAlarmChannelLock(context) {
                    syncManagedAlarms(
                        context, manager, readAlarmSnapshot(context), createMissing = false
                    )
                }
            }.onFailure { Log.w("NotificationChannels", "Could not refresh alarm channels", it) }
        }
    }

    private fun ensureChannel(
        context: Context,
        manager: NotificationManager,
        definition: Definition,
        minimal: Boolean = false
    ) {
        val existing = manager.getNotificationChannel(definition.id)
        if (existing != null) {
            // Replaced originals get their localized name during alarm synchronization.
            if (!definition.alarm) refreshName(context, manager, definition, existing)
            return
        }
        val channel = NotificationChannel(
            definition.id,
            context.getString(definition.name),
            if (definition.alarm) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW
        )
        if (definition.alarm) {
            setDefaultAlarmOptions(channel, AlarmVolumeCategory.NOTIFICATIONS)
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
    ) = refreshName(manager, channel, context.getString(definition.name))

    private fun refreshName(
        manager: NotificationManager, channel: NotificationChannel, name: String
    ) {
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
