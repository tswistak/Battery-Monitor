/*
    Copyright (c) 2009-2020 Darshan Computing, LLC
    Modified in 2026 by Tomasz Świstak <tomasz@swistak.codes> for the Battery Monitor fork.
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


import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.database.Cursor
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.alarms.AlarmDatabase
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.settings.ChipContentOrder
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsSnapshot
import codes.swistak.batterymonitor.settings.VitalSignsOrder
import codes.swistak.batterymonitor.widgets.BatteryInfoAppWidgetProvider
import codes.swistak.batterymonitor.widgets.CircleWidgetBackground
import codes.swistak.batterymonitor.widgets.FullAppWidgetProvider
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class BatteryInfoService : Service() {

    companion object {
        private const val LOG_TAG = "BatteryInfoService"
        private var clientMessengers: HashSet<Messenger>? = null
        private var messenger: Messenger? = null
        private val widgetIds = HashSet<Int>()
        private var widgetManager: AppWidgetManager? = null
        private const val NOTIFICATION_PRIMARY = 1
        private const val NOTIFICATION_MAIN_COMPANION = 2
        private const val NOTIFICATION_ALARM = 7
        const val CHAN_ID_MAIN: String = "main_004"
        const val CHAN_ID_LIVE_UPDATE: String = "live_update_001"
        const val CHAN_ID_A_CHARGED: String = "fully_charged"
        const val CHAN_ID_A_CDROP: String = "charge_drops"
        const val CHAN_ID_A_CRISE: String = "charge_rises"
        const val CHAN_ID_A_TDROP: String = "temp_drops"
        const val CHAN_ID_A_TRISE: String = "temp_rises"
        const val CHAN_ID_A_HFAIL: String = "health_failure"
        const val CHAN_GROUP_ID_ALARMS: String = "alarms"

        val ALARM_CHAN_IDS: Array<String> = arrayOf(
            CHAN_ID_A_CHARGED,
            CHAN_ID_A_CDROP,
            CHAN_ID_A_CRISE,
            CHAN_ID_A_TDROP,
            CHAN_ID_A_TRISE,
            CHAN_ID_A_HFAIL
        )

        private const val RC_MAIN = 100
        private const val RC_ALARMS_EDIT = 101
        const val KEY_PREVIOUS_CHARGE: String = "previous_charge"
        const val KEY_PREVIOUS_TEMP: String = "previous_temp"
        const val KEY_PREVIOUS_HEALTH: String = "previous_health"
        const val KEY_SERVICE_DESIRED: String = "serviceDesired"
        const val LAST_SDK_API: String = "last_sdk_api"

        const val EXTRA_CURRENT_INFO: String = "codes.swistak.batterymonitor.EXTRA_CURRENT_INFO"
        const val EXTRA_EDIT_ALARMS: String = "codes.swistak.batterymonitor.EXTRA_EDIT_ALARMS"

        // Carries fresh preference values to the service's separate BIS process.
        private const val EXTRA_SETTINGS_SNAPSHOT =
            "codes.swistak.batterymonitor.EXTRA_SETTINGS_SNAPSHOT"

        private const val CONTENT_PERCENTAGE = "percentage"
        private const val CONTENT_TEMPERATURE = "temperature"
        private const val LIVE_UPDATE_MODE_ALWAYS = "always"
        private const val LIVE_UPDATE_MODE_CHARGING = "charging"
        private const val LIVE_UPDATE_MODE_NEVER = "never"

        private fun sendClientMessage(clientMessenger: Messenger, what: Int, data: Bundle? = null) {
            val outgoing = Message.obtain()
            outgoing.what = what
            outgoing.replyTo = messenger
            outgoing.data = data
            try {
                clientMessenger.send(outgoing)
            } catch (e: RemoteException) {
            }
        }

        private fun colorFromHex(hex: String): Int {
            if (hex.length != 7) return 0
            if (hex[0] != '#') return 0

            var color = 0xff

            for (i in 1..6) {
                color = color shl 4
                when (val c = hex[i]) {
                    in '0'..'9' -> color += c.code - '0'.code
                    in 'A'..'F' -> color += c.code - 'A'.code + 10
                    in 'a'..'f' -> color += c.code - 'a'.code + 10
                }
            }

            return color
        }

        fun onWidgetUpdate(
            context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray
        ) {
            widgetManager = appWidgetManager

            for (i in appWidgetIds.indices) {
                widgetIds.add(appWidgetIds[i])
            }

            startForegroundServiceSafely(context)
        }

        fun startForegroundServiceSafely(context: Context, settingsSnapshot: Bundle? = null) {
            val serviceIntent = Intent(context, BatteryInfoService::class.java)
            if (settingsSnapshot != null) {
                serviceIntent.putExtra(EXTRA_SETTINGS_SNAPSHOT, settingsSnapshot)
            }

            try {
                context.startForegroundService(serviceIntent)
            } catch (e: RuntimeException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    try {
                        context.startService(serviceIntent)
                    } catch (ignored: Exception) {
                    }
                } else {
                    try {
                        context.startService(serviceIntent)
                    } catch (ignored: Exception) {
                    }
                }
            }
        }

        fun onWidgetDeleted(context: Context?, appWidgetIds: IntArray) {
            for (i in appWidgetIds.indices) {
                widgetIds.remove(appWidgetIds[i])
            }
        }

        fun supportsLiveUpdates(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
        }

        fun isLiveUpdateEnabledInSystem(context: Context): Boolean {
            if (!supportsLiveUpdates()) return true
            return NotificationManagerCompat.from(context).canPostPromotedNotifications()
        }
    }

    private val batteryChanged = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    private var currentInfoPendingIntent: PendingIntent? = null
    private var alarmsPendingIntent: PendingIntent? = null
    private var alarmsIntent: Intent? = null

    private var mNotificationManager: NotificationManager? = null
    private var alarmManager: AlarmManager? = null
    private lateinit var settings: SharedPreferences
    private lateinit var spService: SharedPreferences
    private var spsEditor: SharedPreferences.Editor? = null
    private var batteryCurrentEnabled = false

    private var vitalSignsContent: Set<String> = SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT
    private var vitalSignsOrder: List<String> = SettingsContract.ALL_VITAL_SIGNS_CONTENT
    private var chipContent: Set<String> = SettingsContract.DEFAULT_CHIP_CONTENT
    private var chipContentOrder: List<String> = SettingsContract.ALL_CHIP_CONTENT
    private var chipContentIndex = 0

    private var preferAverageBatteryCurrent = false

    private lateinit var res: Resources
    private var alarms: AlarmDatabase? = null
    private var logDb: LogDatabase? = null
    private var bl: BatteryLevel? = null
    private var cwbg: CircleWidgetBackground? = null
    private var info: BatteryInfo? = null

    private lateinit var remainingChargeReader: RemainingChargeReader

    private var now: Long = 0
    private var updatedLasts = false

    private var mainNotificationTopLine: String? = null
    private var mainNotificationBottomLine: String? = null
    private var mainNotificationForegroundStarted = false
    private val iconResCache = HashMap<String?, Int?>()
    private var predictor: Predictor? = null

    private val mHandler = Handler(Looper.getMainLooper())

    private val runPredictorUpdate = Runnable { update(null) }
    private val runChipSwitch: Runnable = object : Runnable {
        override fun run() {
            val selectedContent = selectedChipContent()
            if (selectedContent.size < 2) return
            chipContentIndex = (chipContentIndex + 1) % selectedContent.size
            val mainNotification = prepareNotification()
            if (mainNotificationForegroundStarted) {
                mNotificationManager!!.notify(NOTIFICATION_PRIMARY, mainNotification)
            }

            mHandler.postDelayed(this, chipSwitchingIntervalMillis())
        }
    }

    private val runRenotify: Runnable =
        Runnable { registerReceiver(mBatteryInfoReceiver, batteryChanged) }

    private fun setUpChannels() {
        if (mNotificationManager == null) mNotificationManager = getSystemService(
            NOTIFICATION_SERVICE
        ) as NotificationManager?

        val mainImportance = NotificationManager.IMPORTANCE_LOW
        val mainNotifChanName: CharSequence = getString(R.string.main_notif_chan_name)
        var ch = NotificationChannel(CHAN_ID_MAIN, mainNotifChanName, mainImportance)
        ch.setSound(null, null)
        ch.enableLights(false)
        ch.enableVibration(false)
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        mNotificationManager!!.createNotificationChannel(ch)

        val liveUpdatesNotifChanName: CharSequence =
            getString(R.string.live_updates_notif_chan_name)
        ch = NotificationChannel(CHAN_ID_LIVE_UPDATE, liveUpdatesNotifChanName, mainImportance)
        ch.setSound(null, null)
        ch.enableLights(false)
        ch.enableVibration(false)
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        mNotificationManager!!.createNotificationChannel(ch)

        val channelGroupNameAlarms: CharSequence = getString(R.string.channel_group_name_alarms)
        mNotificationManager!!.createNotificationChannelGroup(
            NotificationChannelGroup(
                CHAN_GROUP_ID_ALARMS, channelGroupNameAlarms
            )
        )

        val alarmChanNames = intArrayOf(
            R.string.alarm_type_fully_charged,
            R.string.alarm_type_charge_drops,
            R.string.alarm_type_charge_rises,
            R.string.alarm_type_temperature_drops,
            R.string.alarm_type_temperature_rises,
            R.string.alarm_type_health_failure
        )

        for (i in ALARM_CHAN_IDS.indices) {
            val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val chanName: CharSequence = getString(alarmChanNames[i])
            ch = NotificationChannel(
                ALARM_CHAN_IDS[i], chanName, NotificationManager.IMPORTANCE_HIGH
            )
            ch.setSound(
                ringtone,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()
            )
            ch.enableLights(true)
            ch.lightColor = -0xcc4a1b
            ch.enableVibration(true)
            ch.setVibrationPattern(longArrayOf(0, 500, 500, 500, 500, 1000, 1000, 1000, 1000))
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            ch.group = CHAN_GROUP_ID_ALARMS
            mNotificationManager!!.createNotificationChannel(ch)
        }
    }

    override fun onCreate() {
        super.onCreate()

        BatteryCurrent.enableShizukuMultiProcessSupport(this)

        mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager?
        setUpMinimalForegroundChannel()
        if (!startForegroundImmediately()) {
            stopSelf()
            return
        }

        res = resources
        DisplayStrings.setResources(res)
        logDb = LogDatabase(this)

        info = BatteryInfo()

        remainingChargeReader = RemainingChargeReader(applicationContext)

        messenger = Messenger(MessageHandler(this))
        clientMessengers = HashSet()

        predictor = Predictor(this)
        bl = BatteryLevel.getSmallInstance(this)
        cwbg = CircleWidgetBackground(this)

        alarms = AlarmDatabase(this)

        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager?

        loadSettingsFiles()
        setUpChannels()

        sdkVersioning()

        BatteryCurrent.setContext(this)
        BatteryCurrent.setShizukuReadyListener {
            mHandler.post {
                if (mainNotificationForegroundStarted) update(null)
            }
        }
        configureBatteryCurrent()
        configureChipContent()

        val currentInfoIntent = Intent(this, BatteryInfoActivity::class.java).putExtra(
            EXTRA_CURRENT_INFO, true
        )
        currentInfoPendingIntent = PendingIntent.getActivity(
            this, RC_MAIN, currentInfoIntent, PendingIntent.FLAG_IMMUTABLE
        )

        alarmsIntent =
            Intent(this, BatteryInfoActivity::class.java).putExtra(EXTRA_EDIT_ALARMS, true)

        val serviceAlarmsIntent = Intent(this, BatteryInfoService::class.java).putExtra(
            EXTRA_EDIT_ALARMS, true
        )
        alarmsPendingIntent = PendingIntent.getActivity(
            this, RC_ALARMS_EDIT, alarmsIntent, PendingIntent.FLAG_IMMUTABLE
        )

        widgetManager = AppWidgetManager.getInstance(this)

        val appWidgetProviders = arrayOf<Class<*>?>(
            BatteryInfoAppWidgetProvider::class.java, FullAppWidgetProvider::class.java
        )

        for (i in appWidgetProviders.indices) {
            val ids: IntArray =
                widgetManager!!.getAppWidgetIds(ComponentName(this, appWidgetProviders[i]!!))

            for (j in ids.indices) {
                widgetIds.add(ids[j])
            }
        }

        val bcIntent = registerReceiver(mBatteryInfoReceiver, batteryChanged)
        if (bcIntent != null) info!!.load(bcIntent, spService)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(LOG_TAG, "Foreground service timeout reached for type: $fgsType")
        stopSelf()
    }

    override fun onDestroy() {
        BatteryCurrent.setShizukuReadyListener(null)
        if (alarms != null) alarms!!.close()
        try {
            unregisterReceiver(mBatteryInfoReceiver)
        } catch (ignored: IllegalArgumentException) {
        }
        mHandler.removeCallbacks(runRenotify)
        mHandler.removeCallbacks(runChipSwitch)
        mHandler.removeCallbacks(runPredictorUpdate)
        if (mNotificationManager != null) mNotificationManager!!.cancelAll()
        if (logDb != null) logDb!!.close()
        if (cwbg != null) updateWidgets(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        mainNotificationForegroundStarted = false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        BackgroundServiceWatchdog.schedule(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!mainNotificationForegroundStarted) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        intent?.getBundleExtra(EXTRA_SETTINGS_SNAPSHOT)?.let(::applySettingsSnapshot)
        configureBatteryCurrent()
        configureChipContent()
        update(null)
        restartChipSwitcher()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return messenger!!.binder
    }

    private class MessageHandler(private val bis: BatteryInfoService) :
        Handler(Looper.getMainLooper()) {
        override fun handleMessage(incoming: Message) {
            when (incoming.what) {
                RemoteConnection.SERVICE_CLIENT_CONNECTED -> sendClientMessage(
                    incoming.replyTo, RemoteConnection.CLIENT_SERVICE_CONNECTED
                )

                RemoteConnection.SERVICE_REGISTER_CLIENT -> {
                    clientMessengers!!.add(incoming.replyTo)
                    sendClientMessage(
                        incoming.replyTo,
                        RemoteConnection.CLIENT_BATTERY_INFO_UPDATED,
                        bis.info!!.toBundle()
                    )
                }

                RemoteConnection.SERVICE_UNREGISTER_CLIENT -> clientMessengers!!.remove(
                    incoming.replyTo
                )

                RemoteConnection.SERVICE_RELOAD_SETTINGS -> bis.reloadSettings(
                    false, incoming.data
                )

                RemoteConnection.SERVICE_CANCEL_NOTIFICATION_AND_RELOAD_SETTINGS -> bis.reloadSettings(
                    true, incoming.data
                )

                else -> super.handleMessage(incoming)
            }
        }
    }

    internal class RemoteConnection(private val clientMessenger: Messenger?) : ServiceConnection {
        companion object {
            const val SERVICE_CLIENT_CONNECTED: Int = 0
            const val SERVICE_REGISTER_CLIENT: Int = 1
            const val SERVICE_UNREGISTER_CLIENT: Int = 2
            const val SERVICE_RELOAD_SETTINGS: Int = 3
            const val SERVICE_CANCEL_NOTIFICATION_AND_RELOAD_SETTINGS: Int = 4

            const val CLIENT_SERVICE_CONNECTED: Int = 0
            const val CLIENT_BATTERY_INFO_UPDATED: Int = 1
        }

        var serviceMessenger: Messenger? = null

        override fun onServiceConnected(name: ComponentName?, iBinder: IBinder?) {
            serviceMessenger = Messenger(iBinder)

            val outgoing = Message.obtain()
            outgoing.what = SERVICE_CLIENT_CONNECTED
            outgoing.replyTo = clientMessenger
            try {
                serviceMessenger!!.send(outgoing)
            } catch (e: RemoteException) {
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
        }
    }

    private fun loadSettingsFiles() {
        settings = getSharedPreferences(SettingsContract.SETTINGS_FILE, MODE_PRIVATE)
        spService = getSharedPreferences(SettingsContract.SP_SERVICE_FILE, MODE_PRIVATE)
    }

    private fun reloadSettings(cancelFirst: Boolean, settingsSnapshot: Bundle?) {
        loadSettingsFiles()
        settingsSnapshot?.let(::applySettingsSnapshot)
        configureBatteryCurrent()
        configureChipContent()

        DisplayStrings.setResources(res)

        applyNewSettings(cancelFirst)
    }

    private fun applySettingsSnapshot(snapshot: Bundle) {
        if (!SettingsSnapshot.apply(settings, snapshot)) {
            Log.e(LOG_TAG, "Failed to apply cross-process settings snapshot")
        }
    }

    private fun configureBatteryCurrent() {
        batteryCurrentEnabled = settings.getBoolean(
            SettingsContract.KEY_ENABLE_BATTERY_CURRENT, false
        )
        vitalSignsContent = settings.getStringSet(
            SettingsContract.KEY_VITAL_SIGNS_CONTENT, SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT
        )?.toSet() ?: SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT
        vitalSignsOrder = VitalSignsOrder.parse(
            settings.getString(SettingsContract.KEY_VITAL_SIGNS_ORDER, null)
        )
        preferAverageBatteryCurrent = settings.getBoolean(
            SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT, false
        )

        BatteryCurrent.setUsePrivilegedAccess(
            settings.getBoolean(
                SettingsContract.KEY_USE_PRIVILEGED_BATTERY_CURRENT, false
            )
        )
        val multiplier = settings.getString(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "1")
        BatteryCurrent.setMultiplier(multiplier?.toIntOrNull() ?: 1)
    }

    private fun configureChipContent() {
        chipContent = settings.getStringSet(
            SettingsContract.KEY_CHIP_CONTENT, SettingsContract.DEFAULT_CHIP_CONTENT
        )?.filterTo(linkedSetOf()) { it in SettingsContract.ALL_CHIP_CONTENT }
            ?.ifEmpty { SettingsContract.DEFAULT_CHIP_CONTENT }
            ?: SettingsContract.DEFAULT_CHIP_CONTENT
        chipContentOrder = ChipContentOrder.parse(
            settings.getString(SettingsContract.KEY_CHIP_CONTENT_ORDER, null)
        )
    }

    private fun applyNewSettings(cancelFirst: Boolean) {
        if (cancelFirst) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            mainNotificationForegroundStarted = false
        }

        chipContentIndex = 0
        setUpChannels()
        registerReceiver(mBatteryInfoReceiver, batteryChanged)
        update(null)

        restartChipSwitcher()
    }

    private fun restartChipSwitcher() {
        mHandler.removeCallbacks(runChipSwitch)
        if (shouldRunChipSwitcher()) {
            mHandler.postDelayed(runChipSwitch, chipSwitchingIntervalMillis())
        }
    }

    private val mBatteryInfoReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent) {
            if (Intent.ACTION_BATTERY_CHANGED != intent.action) return

            update(intent)
        }
    }

    private fun sdkVersioning() {
        val spsEditor = spService.edit()
        val settingsEditor = settings.edit()

        spsEditor.putInt(LAST_SDK_API, Build.VERSION.SDK_INT)

        spsEditor.apply()
        settingsEditor.apply()
    }

    private fun update(intent: Intent?) {
        now = System.currentTimeMillis()
        spsEditor = spService.edit()
        updatedLasts = false

        var batteryIntent = intent
        if (batteryIntent == null) batteryIntent = registerReceiver(null, batteryChanged)

        if (batteryIntent != null) info!!.load(batteryIntent, spService)

        info!!.remainingChargeUah = remainingChargeReader.readMicroAmpHours()

        predictor!!.setPredictionType(
            settings.getString(
                SettingsContract.KEY_PREDICTION_TYPE, DisplayStrings.defaultPredictionType
            )!!
        )
        predictor!!.update(info!!)
        info!!.prediction.updateRelativeTime()

        if (statusHasChanged()) handleUpdateWithChangedStatus()
        else handleUpdateWithSameStatus()

        prepareNotification()
        startForegroundWithRetry()

        if (alarms!!.anyActiveAlarms()) handleAlarms()

        updateWidgets(info)

        syncSpsEditor()

        for (messenger in clientMessengers!!) {
            sendClientMessage(
                messenger, RemoteConnection.CLIENT_BATTERY_INFO_UPDATED, info!!.toBundle()
            )
        }

        BackgroundServiceWatchdog.recordHeartbeat(this)
        BackgroundServiceWatchdog.schedule(this)
        mHandler.removeCallbacks(runPredictorUpdate)
        mHandler.postDelayed(runPredictorUpdate, 2L * 60L * 1000L)
    }

    private fun setUpMinimalForegroundChannel() {
        if (mNotificationManager == null) return

        val channel = NotificationChannel(
            CHAN_ID_MAIN,
            getString(R.string.main_notif_chan_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setSound(null, null)
        channel.enableLights(false)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        mNotificationManager!!.createNotificationChannel(channel)
    }

    private fun startForegroundImmediately(): Boolean {
        val notification: Notification =
            NotificationCompat.Builder(this, CHAN_ID_MAIN).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.background_service_starting)).setOngoing(true)
                .setShowWhen(false).build()

        try {
            startForegroundWithNotification(notification)
            mainNotificationForegroundStarted = true
            return true
        } catch (firstError: RuntimeException) {
            try {
                setUpMinimalForegroundChannel()
                startForegroundWithNotification(notification)
                mainNotificationForegroundStarted = true
                return true
            } catch (retryError: RuntimeException) {
                Log.e(LOG_TAG, "Unable to enter foreground mode during service startup", retryError)
                return false
            }
        }
    }

    private fun startForegroundWithRetry() {
        var mainNotification = prepareNotification()

        try {
            if (mainNotificationForegroundStarted) {
                mNotificationManager!!.notify(NOTIFICATION_PRIMARY, mainNotification)
            } else {
                startForegroundWithNotification(mainNotification)
                mainNotificationForegroundStarted = true
            }
        } catch (e: RuntimeException) {
            try {
                setUpChannels()
                mainNotification = prepareNotification()

                if (mainNotificationForegroundStarted) {
                    mNotificationManager!!.notify(NOTIFICATION_PRIMARY, mainNotification)
                } else {
                    startForegroundWithNotification(mainNotification)
                    mainNotificationForegroundStarted = true
                }
            } catch (retryError: RuntimeException) {
                Log.e(LOG_TAG, "Unable to enter foreground mode", retryError)
            }
        }

        updateCompanionMainNotification()
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_PRIMARY, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_PRIMARY, notification)
        }
    }

    private fun updateWidgets(info: BatteryInfo?) {
        if (info == null) {
            cwbg!!.setLevel(0)
        } else {
            bl!!.setColor(DisplayStrings.accentColor)
            cwbg!!.setColor(-0x865c01)

            bl!!.setLevel(info.percent)
            cwbg!!.setLevel(info.percent)
        }

        for (widgetId in widgetIds) {
            val rv: RemoteViews?

            val awpInfo: AppWidgetProviderInfo =
                widgetManager!!.getAppWidgetInfo(widgetId) ?: continue

            val initLayout = awpInfo.initialLayout

            if (initLayout == R.layout.circle_app_widget) {
                rv = RemoteViews(packageName, R.layout.circle_app_widget)

                if (info == null) rv.setImageViewResource(
                    R.id.circle_widget_image_view, R.drawable.empty
                )
                else rv.setImageViewBitmap(R.id.circle_widget_image_view, cwbg!!.bitmap)
            } else {
                rv = RemoteViews(packageName, R.layout.full_app_widget)

                if (info == null) {
                    rv.setImageViewResource(R.id.battery_level_view, R.drawable.empty)
                    rv.setTextViewText(R.id.fully_charged, "")
                    rv.setTextViewText(R.id.time_remaining, "")
                    rv.setTextViewText(R.id.until_what, "")
                } else {
                    rv.setImageViewBitmap(R.id.battery_level_view, bl!!.getBitmap())

                    if (info.prediction.whatHappened == BatteryInfo.Prediction.NONE) {
                        rv.setTextViewText(R.id.fully_charged, DisplayStrings.timeRemaining(info))
                        rv.setTextViewText(R.id.time_remaining, "")
                        rv.setTextViewText(R.id.until_what, "")
                    } else {
                        rv.setTextViewText(R.id.fully_charged, "")
                        rv.setTextViewText(R.id.time_remaining, DisplayStrings.timeRemaining(info))
                        rv.setTextViewText(R.id.until_what, DisplayStrings.untilWhat(info))
                    }
                }
            }

            if (info == null) rv.setTextViewText(R.id.level, "XX" + DisplayStrings.percentSymbol)
            else rv.setTextViewText(R.id.level, "" + info.percent + DisplayStrings.percentSymbol)

            rv.setOnClickPendingIntent(R.id.widget_layout, currentInfoPendingIntent)
            try {
                widgetManager!!.updateAppWidget(widgetId, rv)
            } catch (e: Exception) {
            }
        }
    }

    private fun syncSpsEditor() {
        spsEditor!!.apply()

        if (updatedLasts) {
            info!!.lastStatusCtm = now
            info!!.lastStatus = info!!.status
            info!!.lastPercent = info!!.percent
            info!!.lastPlugged = info!!.plugged
        }
    }

    private fun prepareNotification(): Notification {
        mainNotificationTopLine = lineFor(SettingsContract.KEY_TOP_LINE)
        mainNotificationBottomLine = lineFor(SettingsContract.KEY_BOTTOM_LINE)

        val requestLiveUpdateChip = shouldRequestLiveUpdateChip()
        val channelId: String = if (requestLiveUpdateChip) CHAN_ID_LIVE_UPDATE else CHAN_ID_MAIN
        val nb = NotificationCompat.Builder(this, channelId)

        nb.setSmallIcon(iconFor()).setOngoing(true).setWhen(0).setShowWhen(false)
            .setContentIntent(currentInfoPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        nb.setContentTitle(mainNotificationTopLine).setContentText(mainNotificationBottomLine)

        if (requestLiveUpdateChip) {
            var text = chipContentText()
            if (shouldShowChipChargingIndicator()) {
                text = "⚡$text"
            }

            nb.setRequestPromotedOngoing(true).setShortCriticalText(text)
        }

        val notification = nb.build()
        if (requestLiveUpdateChip) {
            val promotable = NotificationCompat.hasPromotableCharacteristics(notification)
            val promotionAllowed =
                NotificationManagerCompat.from(this).canPostPromotedNotifications()
            Log.d(
                LOG_TAG,
                ("Live Update: promotable=" + promotable + ", promotionAllowed=" + promotionAllowed)
            )
        }

        return notification
    }

    private fun prepareCompanionMainNotification(): Notification {
        val nb = Notification.Builder(this, CHAN_ID_MAIN)

        nb.setSmallIcon(iconForLegacyMainNotification()).setOngoing(true).setWhen(0)
            .setShowWhen(false).setContentIntent(currentInfoPendingIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setContentTitle(mainNotificationTopLine)
            .setContentText(mainNotificationBottomLine)

        return nb.build()
    }

    private fun updateCompanionMainNotification() {
        if (!shouldShowCompanionMainNotification()) {
            mNotificationManager!!.cancel(NOTIFICATION_MAIN_COMPANION)
            return
        }

        mNotificationManager!!.notify(
            NOTIFICATION_MAIN_COMPANION, prepareCompanionMainNotification()
        )
    }

    private fun contentPreference(key: String?, defaultValue: String?): String {
        val content = settings.getString(key, defaultValue)
        return if (CONTENT_TEMPERATURE == content) CONTENT_TEMPERATURE else CONTENT_PERCENTAGE
    }

    private fun roundedTemperatureValue(): Int {
        val convertF = settings.getBoolean(
            SettingsContract.KEY_CONVERT_F, res.getBoolean(R.bool.default_convert_to_fahrenheit)
        )
        var temp = info!!.temperature / 10.0
        if (convertF) temp = temp * 9.0 / 5.0 + 32.0
        return temp.roundToInt()
    }

    private fun iconContentValue(content: String?): Int {
        if (CONTENT_TEMPERATURE == content) {
            return roundedTemperatureValue()
        }
        return info!!.percent
    }

    private fun maxIconContentValue(content: String?): Int {
        return if (CONTENT_TEMPERATURE == content) 150 else 100
    }

    private fun selectedChipContent(): List<String> =
        chipContentOrder.filter { it in chipContent }.ifEmpty {
            listOf(SettingsContract.CHIP_CONTENT_PERCENTAGE)
        }

    private fun chipContentText(): String {
        val selectedContent = selectedChipContent()
        val content = selectedContent[chipContentIndex % selectedContent.size]
        return when (content) {
            SettingsContract.CHIP_CONTENT_TEMPERATURE -> {
                val convertF = settings.getBoolean(
                    SettingsContract.KEY_CONVERT_F,
                    res.getBoolean(R.bool.default_convert_to_fahrenheit)
                )
                DisplayStrings.formatTemp(info!!.temperature, convertF, false)
            }

            SettingsContract.CHIP_CONTENT_VOLTAGE -> {
                if (info!!.voltage > 500) DisplayStrings.formatVoltage(info!!.voltage) else "—"
            }

            SettingsContract.CHIP_CONTENT_CURRENT -> {
                var current: Double? = null
                if (preferAverageBatteryCurrent) current = BatteryCurrent.avgCurrent
                if (current == null) current = BatteryCurrent.current
                current?.let {
                    BatteryCurrent.formatMilliAmps(it, res.configuration.locales[0]) + "mA"
                } ?: "—"
            }

            SettingsContract.CHIP_CONTENT_CHARGE -> {
                info!!.remainingChargeUah?.let { remainingChargeUah ->
                    getString(
                        R.string.remaining_charge_value,
                        DisplayStrings.formatChargeCompact(remainingChargeUah)
                    )
                } ?: "—"
            }

            else -> info!!.percent.toString() + "%"
        }
    }

    private fun chipSwitchingIntervalMillis(): Long {
        val seconds = settings.getString(
            SettingsContract.KEY_CHIP_SWITCHING_INTERVAL, "5"
        )?.toLongOrNull()?.coerceIn(1L, 3600L) ?: 5L
        return seconds * 1000L
    }

    private fun liveUpdateMode(): String {
        val mode: String = settings.getString(
            SettingsContract.KEY_LIVE_UPDATE_DISPLAY,
            res.getString(R.string.default_live_update_display_mode)
        )!!

        if (LIVE_UPDATE_MODE_ALWAYS == mode || LIVE_UPDATE_MODE_CHARGING == mode || LIVE_UPDATE_MODE_NEVER == mode) {
            return mode
        }

        return LIVE_UPDATE_MODE_CHARGING
    }

    private val isChargingOrFull: Boolean
        get() = info!!.status == BatteryInfo.STATUS_CHARGING || info!!.status == BatteryInfo.STATUS_FULLY_CHARGED

    private fun shouldRequestLiveUpdateChip(): Boolean {
        if (!supportsLiveUpdates()) return false

        val mode = liveUpdateMode()
        if (LIVE_UPDATE_MODE_NEVER == mode) return false
        if (LIVE_UPDATE_MODE_ALWAYS == mode) return true

        return this.isChargingOrFull
    }

    private fun shouldKeepMainNotificationWithLiveUpdate(): Boolean {
        return settings.getBoolean(
            SettingsContract.KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION, false
        )
    }

    private fun shouldShowCompanionMainNotification(): Boolean {
        return shouldRequestLiveUpdateChip() && shouldKeepMainNotificationWithLiveUpdate()
    }

    private fun shouldRunChipSwitcher(): Boolean {
        if (!supportsLiveUpdates()) return false
        if (LIVE_UPDATE_MODE_NEVER == liveUpdateMode()) return false
        return selectedChipContent().size > 1
    }

    private fun shouldShowChipChargingIndicator(): Boolean {
        if (!settings.getBoolean(
                SettingsContract.KEY_CHIP_INDICATE_CHARGING, true
            )
        ) return false
        return info!!.status == BatteryInfo.STATUS_CHARGING || info!!.status == BatteryInfo.STATUS_FULLY_CHARGED
    }

    private fun lineFor(key: String): String {
        val req: String = settings.getString(
            key, if (key == SettingsContract.KEY_TOP_LINE) "remaining" else "vitals"
        )!!

        return when (req) {
            "remaining" -> predictionLine()
            "vitals" -> vitalStatsLine()
            "since" -> statusDurationLine()
            else -> predictionLine()
        }
    }

    private fun predictionLine(): String {
        var line: String?
        val predicted = info!!.prediction.lastRTime

        if (info!!.prediction.whatHappened == BatteryInfo.Prediction.NONE) {
            line = DisplayStrings.statuses[info!!.status]
        } else {
            if (predicted.days > 0) line =
                DisplayStrings.nDaysMHours(predicted.days, predicted.hours)
            else if (predicted.hours > 0) {
                val verbosity: String = settings.getString(
                    SettingsContract.KEY_TIME_REMAINING_VERBOSITY,
                    res.getString(R.string.default_time_remaining_verbosity)
                )!!
                line = when (verbosity) {
                    "condensed" -> DisplayStrings.nHoursMMinutesMedium(
                        predicted.hours, predicted.minutes
                    )

                    "verbose" -> DisplayStrings.nHoursMMinutesLong(
                        predicted.hours, predicted.minutes
                    )

                    else -> DisplayStrings.nHoursLongMMinutesMedium(
                        predicted.hours, predicted.minutes
                    )
                }
            } else line = DisplayStrings.nMinutesLong(predicted.minutes)

            line += if (info!!.prediction.whatHappened == BatteryInfo.Prediction.UNTIL_CHARGED) res!!.getString(
                R.string.notification_until_charged
            )
            else res.getString(R.string.notification_until_drained)
        }

        return line
    }

    private fun vitalStatsLine(): String {
        val convertF = settings.getBoolean(
            SettingsContract.KEY_CONVERT_F, res.getBoolean(R.bool.default_convert_to_fahrenheit)
        )

        val values = mutableListOf<String>()
        for (vitalSign in vitalSignsOrder) {
            if (vitalSign !in vitalSignsContent) continue
            when (vitalSign) {
                SettingsContract.VITAL_SIGN_HEALTH -> {
                    values += DisplayStrings.healths[info!!.health]
                }

                SettingsContract.VITAL_SIGN_TEMPERATURE -> {
                    values += DisplayStrings.formatTemp(info!!.temperature, convertF)
                }

                SettingsContract.VITAL_SIGN_VOLTAGE -> {
                    if (info!!.voltage > 500) values += DisplayStrings.formatVoltage(info!!.voltage)
                }

                SettingsContract.VITAL_SIGN_CURRENT -> if (batteryCurrentEnabled) {
                    var current: Double? = null
                    if (preferAverageBatteryCurrent) current = BatteryCurrent.avgCurrent
                    if (current == null) current = BatteryCurrent.current
                    if (current != null) {
                        values += BatteryCurrent.formatMilliAmps(
                            current, res.configuration.locales[0]
                        ) + "mA"
                    }
                }

                SettingsContract.VITAL_SIGN_CHARGE -> {
                    values += info!!.remainingChargeUah?.let { remainingChargeUah ->
                        getString(
                            R.string.remaining_charge_value,
                            DisplayStrings.formatChargeCompact(remainingChargeUah)
                        )
                    } ?: getString(R.string.remaining_charge_unavailable)
                }

                SettingsContract.VITAL_SIGN_STATUS_DURATION -> {
                    val statusDurationHours = (now - info!!.lastStatusCtm) / (60 * 60 * 1000f)
                    val durationHours = statusDurationHours.toInt()
                    val durationMinutes = ((statusDurationHours * 60) % 60).toInt()
                    values += DisplayStrings.nHoursMMinutesShort(durationHours, durationMinutes)
                }
            }
        }

        return values.joinToString(" / ")
    }

    private fun statusDurationLine(): String {
        val statusDuration = now - info!!.lastStatusCtm
        val statusDurationHours = ((statusDuration + (1000 * 60 * 30)) / (1000 * 60 * 60)).toInt()
        var line = DisplayStrings.statuses[info!!.status] + " "

        line += if (statusDuration < 1000 * 60 * 60) DisplayStrings.since + " " + DisplayStrings.formatTime(
            this, Date(info!!.lastStatusCtm)
        )
        else DisplayStrings.forNHours(statusDurationHours)

        return line
    }

    private fun iconFor(): Int {
        if (shouldRequestLiveUpdateChip()) {
            return R.mipmap.ic_launcher_round
        }

        return iconForLegacyMainNotification()
    }

    @SuppressLint("DiscouragedApi")
    private fun iconForLegacyMainNotification(): Int {
        val content = contentPreference(
            SettingsContract.KEY_ICON_CONTENT, res.getString(R.string.default_icon_content)
        )
        val indicateCharging = settings.getBoolean(SettingsContract.KEY_INDICATE_CHARGING, true)
        val showUnit = settings.getBoolean(SettingsContract.KEY_SHOW_ICON_UNIT, true)
        val clampedValue = max(0, min(maxIconContentValue(content), iconContentValue(content)))

        var prefix =
            if (info!!.status == BatteryInfo.STATUS_CHARGING && indicateCharging) "charging" else "plain"
        val defaultResId = R.drawable.plain000

        if (showUnit) {
            prefix += if (CONTENT_TEMPERATURE == content) "_temp" else "_percentage"
        }

        var fallbackResId = res.getIdentifier(prefix + "000", "drawable", packageName)
        if (fallbackResId == 0) fallbackResId = defaultResId

        return iconByName(prefix, clampedValue, fallbackResId)
    }

    @SuppressLint("DiscouragedApi")
    private fun iconByName(prefix: String, percent: Int, fallbackResId: Int): Int {
        val key = prefix + percent
        val cachedResId = iconResCache[key]
        if (cachedResId != null) return cachedResId

        val iconName = String.format(Locale.US, "%s%03d", prefix, percent)
        var resId = res.getIdentifier(iconName, "drawable", packageName)

        if (resId == 0) {
            val zeroName = String.format(Locale.US, "%s000", prefix)
            resId = res.getIdentifier(zeroName, "drawable", packageName)
        }

        if (resId == 0) resId = fallbackResId

        iconResCache[key] = resId
        return resId
    }

    private fun statusHasChanged(): Boolean {
        val previousCharge = spService.getInt(KEY_PREVIOUS_CHARGE, 100)

        return (info!!.lastStatus != info!!.status || info!!.lastStatusCtm >= now || info!!.lastPlugged != info!!.plugged || (info!!.plugged == BatteryInfo.PLUGGED_UNPLUGGED && info!!.percent > previousCharge + 20))
    }

    private fun handleUpdateWithChangedStatus() {
        if (settings.getBoolean(SettingsContract.KEY_ENABLE_LOGGING, true)) {
            logDb!!.logStatus(info!!, now, LogDatabase.STATUS_NEW)

            if (info!!.status != info!!.lastStatus && info!!.lastStatus == BatteryInfo.STATUS_UNPLUGGED) {
                val maxLogAge = settings.getString(
                    SettingsContract.KEY_MAX_LOG_AGE, DisplayStrings.defaultMaxLogAge
                )!!.toInt()
                if (maxLogAge >= 0) logDb!!.prune(maxLogAge)
            }
        }

        if (batteryCurrentEnabled && SettingsContract.VITAL_SIGN_CURRENT in vitalSignsContent) {
            mHandler.postDelayed(runRenotify, 1000)
            mHandler.postDelayed(runRenotify, 3000)
            mHandler.postDelayed(runRenotify, 9000)
            mHandler.postDelayed(runRenotify, 27000)
        }

        if (info!!.status != info!!.lastStatus && info!!.status == BatteryInfo.STATUS_UNPLUGGED) mNotificationManager!!.cancel(
            NOTIFICATION_ALARM
        )

        updatedLasts = true
        spsEditor!!.putLong(BatteryInfo.KEY_LAST_STATUS_CTM, now)
        spsEditor!!.putInt(BatteryInfo.KEY_LAST_STATUS, info!!.status)
        spsEditor!!.putInt(BatteryInfo.KEY_LAST_PERCENT, info!!.percent)
        spsEditor!!.putInt(BatteryInfo.KEY_LAST_PLUGGED, info!!.plugged)
        spsEditor!!.putInt(KEY_PREVIOUS_CHARGE, info!!.percent)
        spsEditor!!.putInt(KEY_PREVIOUS_TEMP, info!!.temperature)
        spsEditor!!.putInt(KEY_PREVIOUS_HEALTH, info!!.health)
    }

    private fun handleUpdateWithSameStatus() {
        if (settings.getBoolean(
                SettingsContract.KEY_ENABLE_LOGGING, true
            )
        ) logDb!!.logStatus(info!!, now, LogDatabase.STATUS_OLD)

        if (info!!.percent % 10 == 0) {
            spsEditor!!.putInt(KEY_PREVIOUS_CHARGE, info!!.percent)
            spsEditor!!.putInt(KEY_PREVIOUS_TEMP, info!!.temperature)
            spsEditor!!.putInt(KEY_PREVIOUS_HEALTH, info!!.health)
        }
    }

    private fun handleAlarms() {
        var c: Cursor?
        var nb: Notification.Builder?

        val previousCharge = spService!!.getInt(KEY_PREVIOUS_CHARGE, 100)

        if (info!!.status == BatteryInfo.STATUS_FULLY_CHARGED && info!!.status != info!!.lastStatus) {
            c = alarms!!.activeAlarmFull()
            if (c != null) {
                nb = parseAlarmCursor(c)
                nb.setContentTitle(DisplayStrings.alarmFullyCharged).setChannelId(CHAN_ID_A_CHARGED)

                nb.setVisibility(Notification.VISIBILITY_PUBLIC)

                notifyAlarm(nb.build())
                c.close()
            }
        }

        c = alarms!!.activeAlarmChargeDrops(info!!.percent, previousCharge)
        if (c != null) {
            spsEditor!!.putInt(KEY_PREVIOUS_CHARGE, info!!.percent)
            nb = parseAlarmCursor(c)
            val threshold = c.getString(c.getColumnIndexOrThrow(AlarmDatabase.KEY_THRESHOLD))
            nb.setContentTitle(DisplayStrings.alarmChargeDrops + threshold + DisplayStrings.percentSymbol)
                .setChannelId(CHAN_ID_A_CDROP)

            nb.setVisibility(Notification.VISIBILITY_PUBLIC)

            notifyAlarm(nb.build())
            c.close()
        }

        c = alarms!!.activeAlarmChargeRises(info!!.percent, previousCharge)
        if (c != null && info!!.status != BatteryInfo.STATUS_UNPLUGGED) {
            spsEditor!!.putInt(KEY_PREVIOUS_CHARGE, info!!.percent)
            nb = parseAlarmCursor(c)
            val threshold = c.getString(c.getColumnIndexOrThrow(AlarmDatabase.KEY_THRESHOLD))
            nb.setContentTitle(DisplayStrings.alarmChargeRises + threshold + DisplayStrings.percentSymbol)
                .setChannelId(CHAN_ID_A_CRISE)

            nb.setVisibility(Notification.VISIBILITY_PUBLIC)

            notifyAlarm(nb.build())
            c.close()
        }

        c = alarms!!.activeAlarmTempRises(
            info!!.temperature, spService.getInt(KEY_PREVIOUS_TEMP, 1)
        )
        if (c != null) {
            val convertF = settings.getBoolean(
                SettingsContract.KEY_CONVERT_F, res.getBoolean(R.bool.default_convert_to_fahrenheit)
            )

            spsEditor!!.putInt(KEY_PREVIOUS_TEMP, info!!.temperature)
            nb = parseAlarmCursor(c)
            val threshold = c.getString(c.getColumnIndexOrThrow(AlarmDatabase.KEY_THRESHOLD))
            nb.setContentTitle(
                DisplayStrings.alarmTempRises + DisplayStrings.formatTemp(
                    threshold.toInt(), convertF, false
                )
            ).setChannelId(CHAN_ID_A_TRISE)

            nb.setVisibility(Notification.VISIBILITY_PUBLIC)

            notifyAlarm(nb.build())
            c.close()
        }

        c = alarms!!.activeAlarmTempDrops(
            info!!.temperature, spService.getInt(KEY_PREVIOUS_TEMP, 1)
        )
        if (c != null) {
            val convertF = settings.getBoolean(
                SettingsContract.KEY_CONVERT_F, res.getBoolean(R.bool.default_convert_to_fahrenheit)
            )

            spsEditor!!.putInt(KEY_PREVIOUS_TEMP, info!!.temperature)
            nb = parseAlarmCursor(c)
            val threshold = c.getString(c.getColumnIndexOrThrow(AlarmDatabase.KEY_THRESHOLD))
            nb.setContentTitle(
                DisplayStrings.alarmTempDrops + DisplayStrings.formatTemp(
                    threshold.toInt(), convertF, false
                )
            ).setChannelId(CHAN_ID_A_TDROP)

            nb.setVisibility(Notification.VISIBILITY_PUBLIC)

            notifyAlarm(nb.build())
            c.close()
        }

        if (info!!.health > BatteryInfo.HEALTH_GOOD && info!!.health != spService!!.getInt(
                KEY_PREVIOUS_HEALTH, BatteryInfo.HEALTH_GOOD
            )
        ) {
            c = alarms!!.activeAlarmFailure()
            if (c != null) {
                spsEditor!!.putInt(KEY_PREVIOUS_HEALTH, info!!.health)
                nb = parseAlarmCursor(c)
                nb.setContentTitle(DisplayStrings.alarmHealthFailure + DisplayStrings.healths[info!!.health])
                    .setChannelId(CHAN_ID_A_HFAIL)

                nb.setVisibility(Notification.VISIBILITY_PUBLIC)

                notifyAlarm(nb.build())
                c.close()
            }
        }
    }

    private fun parseAlarmCursor(c: Cursor?): Notification.Builder {
        val nb =
            Notification.Builder(this, CHAN_ID_A_CHARGED).setSmallIcon(R.drawable.stat_notify_alarm)
                .setAutoCancel(true).setContentIntent(alarmsPendingIntent)

        return nb
    }

    private fun notifyAlarm(n: Notification?) {
        mNotificationManager!!.notify(NOTIFICATION_ALARM, n)
    }

}
