/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.data

import android.content.Context
import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import codes.swistak.batterymonitor.alarms.AlarmDatabase
import codes.swistak.batterymonitor.alarms.AlarmRecord
import codes.swistak.batterymonitor.logs.HistoryChartModel
import codes.swistak.batterymonitor.logs.HistoryKey
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.logs.HistoryRecord
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.logs.historyChart
import codes.swistak.batterymonitor.logs.historyQuery
import codes.swistak.batterymonitor.monitoring.BatteryCurrent
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringReading
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class SettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
    )

    fun refresh(): Bundle = SettingsSnapshot.capture(preferences)

    fun notifyService(service: Messenger?, cancelNotificationFirst: Boolean = false) {
        val snapshot = refresh()
        val message = Message.obtain().apply {
            what = if (cancelNotificationFirst) {
                BatteryInfoService.RemoteConnection.SERVICE_CANCEL_NOTIFICATION_AND_RELOAD_SETTINGS
            } else {
                BatteryInfoService.RemoteConnection.SERVICE_RELOAD_SETTINGS
            }
            data = snapshot
        }
        try {
            if (service == null) throw RemoteException("Monitoring service is disconnected")
            service.send(message)
        } catch (_: RemoteException) {
            BatteryInfoService.startForegroundServiceSafely(appContext, snapshot)
        }
    }
}

internal class LogsRepository(context: Context) {
    private val appContext = context.applicationContext

    suspend fun chart(range: HistoryRangeState): HistoryChartModel = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        database {
            readHistory(historyQuery(range)) {
                historyChart(
                    range, it.onEach { coroutine.ensureActive() })
            }
        }
    }

    suspend fun page(
        range: HistoryRangeState,
        filters: Set<String>?,
        ascending: Boolean,
        anchor: HistoryKey?,
        backwards: Boolean = false
    ): List<HistoryRecord> = withContext(Dispatchers.IO) {
        database {
            readHistory(
                historyQuery(
                    range, filters, ascending != backwards, anchor, 129
                )
            ) { it.toList() }
        }
    }

    suspend fun duration(record: HistoryRecord): Long? = withContext(Dispatchers.IO) {
        database { historyDuration(record) }
    }

    suspend fun delete(range: HistoryRangeState?) = withContext(Dispatchers.IO) {
        check(
            codes.swistak.batterymonitor.logs.AutoLogExporter.exportBeforeManualLogClearing(
                appContext
            )
        ) {
            "Automatic export failed; history was retained"
        }
        database { deleteHistory(range) }
    }

    private inline fun <T> database(block: LogDatabase.() -> T): T = LogDatabase(appContext).run {
        try {
            block()
        } finally {
            close()
        }
    }

    suspend fun read(
        afterExclusive: Long? = null, throughInclusive: Long? = null
    ): List<LogRecord> = withContext(Dispatchers.IO) {
        LogDatabase(appContext).run {
            try {
                getLogRecordsInRange(afterExclusive, throughInclusive)
            } finally {
                close()
            }
        }
    }
}

internal class AlarmsRepository(context: Context) {
    private val appContext = context.applicationContext

    suspend fun read(): List<AlarmRecord> = withContext(Dispatchers.IO) {
        AlarmDatabase(appContext).run {
            try {
                getAllAlarmRecords()
            } finally {
                close()
            }
        }
    }
}

internal class CurrentReadingRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
    )

    init {
        BatteryCurrent.setContext(context.applicationContext)
    }

    suspend fun read(): MonitoringReading<Double> = withContext(Dispatchers.IO) {
        if (!preferences.getBoolean(SettingsContract.KEY_ENABLE_BATTERY_CURRENT, false)) {
            return@withContext MonitoringReading(null, "disabled", System.currentTimeMillis())
        }
        val multiplier = preferences.getString(
            SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "1"
        )?.toIntOrNull() ?: 1
        BatteryCurrent.setMultiplier(multiplier)
        val preferredAverage = preferences.getBoolean(
            SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT, false
        )
        val average = if (preferredAverage) BatteryCurrent.avgCurrent else null
        val current = average ?: BatteryCurrent.current
        MonitoringReading(
            current,
            if (average != null) "BatteryCurrent average" else "BatteryCurrent instant",
            System.currentTimeMillis()
        )
    }
}
