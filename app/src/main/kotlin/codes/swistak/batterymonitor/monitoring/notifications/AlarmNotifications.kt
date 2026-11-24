package codes.swistak.batterymonitor.monitoring.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.alarms.AlarmPlayback
import codes.swistak.batterymonitor.alarms.AlarmProjection
import codes.swistak.batterymonitor.alarms.AlarmSnapshot
import codes.swistak.batterymonitor.alarms.projectAlarms
import java.util.UUID

internal object AlarmNotifications {
    private const val ID = 7
    private const val TAG_PREFIX = "alarm:"
    private const val ACTION_STOP = "codes.swistak.batterymonitor.action.STOP_ALARM"
    private const val EXTRA_EVENT = "alarm_event"
    private const val EXTRA_TRIGGER_VALUE = "alarm_trigger_value"

    private fun tag(type: String, thresholdId: Int?): String =
        TAG_PREFIX + type + if (thresholdId == null) "" else ":$thresholdId"

    private fun StatusBarNotification.isAlarm(): Boolean =
        id == ID && (tag == null || tag.startsWith(TAG_PREFIX))

    private fun Notification.triggerValue(): Int? =
        extras.getInt(EXTRA_TRIGGER_VALUE).takeIf { extras.containsKey(EXTRA_TRIGGER_VALUE) }

    private fun Notification.isRepeating(): Boolean = flags and Notification.FLAG_INSISTENT != 0

    fun post(
        context: Context,
        snapshot: AlarmSnapshot,
        type: String,
        title: String,
        contentIntent: PendingIntent?,
        thresholdId: Int? = null,
        triggerValue: Int? = null
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val configuration = snapshot.effective(type, thresholdId)
        val channelId = NotificationChannels.alarmChannelId(type, configuration)
        if (!manager.areNotificationsEnabled()) return
        val channel = manager.getNotificationChannel(channelId) ?: return
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return

        val tag = tag(type, thresholdId)
        val repeat = configuration.sound.playback == AlarmPlayback.REPEAT
        val builder =
            Notification.Builder(context, channelId).setSmallIcon(R.drawable.stat_notify_alarm)
                .setContentTitle(title).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true).setContentIntent(contentIntent)
        val extras = Bundle().apply {
            if (triggerValue != null) putInt(EXTRA_TRIGGER_VALUE, triggerValue)
        }
        if (repeat) {
            val event = UUID.randomUUID().toString()
            extras.putString(EXTRA_EVENT, event)
            val stopIntent =
                Intent(context, AlarmStopReceiver::class.java).setAction(ACTION_STOP).setData(
                    Uri.Builder().scheme("battery-alarm").appendPath(tag).appendPath(event)
                        .build()
                )
            val stop = PendingIntent.getBroadcast(
                context, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.stat_notify_alarm),
                    context.getString(R.string.alarm_stop_action),
                    stop
                ).build()
            )
        }
        val notification = builder.addExtras(extras).build()
        if (repeat) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        try {
            manager.notify(tag, ID, notification)
        } catch (error: SecurityException) {
            Log.w(
                "AlarmNotifications", "Alarm notification permission changed before delivery", error
            )
        }
    }

    fun reconcileConfiguration(
        context: Context,
        snapshot: AlarmSnapshot,
        projection: AlarmProjection? = null,
        recovered: (type: String, value: Int?, repeating: Boolean) -> Boolean = { _, _, _ -> false }
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = manager.activeNotifications.filter { it.isAlarm() }
        if (active.isEmpty()) return

        val enabled = buildMap {
            for ((definition, alarms) in (projection ?: projectAlarms(snapshot.rules)).types) {
                for (alarm in alarms.filter { it.enabled }) {
                    put(tag(alarm.type, alarm.id.takeIf { definition.bounds != null }), alarm)
                }
            }
        }
        for (posted in active) {
            val alarm = posted.tag?.let(enabled::get)
            val notification = posted.notification
            val value = notification.triggerValue()
            val repeating = notification.isRepeating()
            val thresholdId = if (alarm?.thresholdKey != null) alarm.id else null
            val config = alarm?.let { snapshot.effective(it.type, thresholdId) }
            if (alarm == null || config == null || (thresholdId != null && alarm.threshold != value) || NotificationChannels.alarmChannelId(
                    alarm.type,
                    config
                ) != notification.channelId || (config.sound.playback == AlarmPlayback.REPEAT) != repeating || recovered(
                    alarm.type,
                    value,
                    repeating
                )
            ) manager.cancel(posted.tag, posted.id)
        }
    }

    fun stop(context: Context, intent: Intent) {
        val paths = intent.data?.pathSegments ?: return
        if (intent.action != ACTION_STOP || paths.size != 2 || !paths[0].startsWith(TAG_PREFIX)) return
        val (tag, event) = paths
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.activeNotifications.any {
                it.id == ID && it.tag == tag && it.notification.extras.getString(EXTRA_EVENT) == event
            }) manager.cancel(tag, ID)
    }
}

internal class AlarmStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) =
        AlarmNotifications.stop(context, intent)
}
