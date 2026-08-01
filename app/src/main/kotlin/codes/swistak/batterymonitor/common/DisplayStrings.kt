/*
    Copyright (c) 2009-2020 Darshan Computing, LLC
    Modified in 2026 by Tomasz Świstak <tomasz@swistak.codes> for the Battery Monitor fork.
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General License for more details.
*/
package codes.swistak.batterymonitor.common

import android.content.Context
import android.content.res.Resources
import android.text.Html
import android.text.Spanned
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal object DisplayStrings {
    private lateinit var res: Resources

    var defUiColor: Int = 0
    var accentColor: Int = 0

    lateinit var degreeSymbol: String
    lateinit var fahrenheitSymbol: String
    lateinit var celsiusSymbol: String
    lateinit var voltSymbol: String
    lateinit var percentSymbol: String
    lateinit var since: String
    lateinit var defaultStatusDurEst: String
    lateinit var defaultRedThresh: String
    lateinit var defaultAmberThresh: String
    lateinit var defaultGreenThresh: String
    lateinit var defaultMaxLogAge: String
    lateinit var defaultPredictionType: String

    lateinit var logsEmpty: String
    lateinit var confirmClearLogs: String
    lateinit var configureLogFilter: String
    lateinit var yes: String
    lateinit var cancel: String
    lateinit var okay: String

    lateinit var currentlySetTo: String
    lateinit var alarmPrefNotUsed: String

    lateinit var alarmFullyCharged: String
    lateinit var alarmChargeDrops: String
    lateinit var alarmChargeRises: String
    lateinit var alarmTempDrops: String
    lateinit var alarmTempRises: String
    lateinit var alarmHealthFailure: String

    lateinit var inaccessibleStorage: String
    lateinit var inaccessibleWReason: String
    lateinit var readOnlyStorage: String
    lateinit var noStoragePermission: String
    lateinit var fileWritten: String

    lateinit var time: String
    lateinit var date: String
    lateinit var status: String
    lateinit var charge: String
    lateinit var temperature: String
    lateinit var temperatureF: String
    lateinit var voltage: String

    lateinit var statusBootCompleted: String

    lateinit var statuses: Array<String>
    lateinit var logStatuses: Array<String>
    lateinit var logStatusesOld: Array<String>
    lateinit var healths: Array<String>
    lateinit var pluggeds: Array<String>
    lateinit var alarmTypesDisplay: Array<String>
    lateinit var alarmTypeEntries: Array<String>
    lateinit var alarmTypeValues: Array<String>
    lateinit var tempAlarmEntries: Array<String>
    lateinit var tempAlarmValues: Array<String>
    lateinit var logFilterPrefKeys: Array<String>

    fun setResources(r: Resources) {
        res = r

        defUiColor = res.getColor(R.color.col2020, null)
        accentColor = res.getColor(R.color.accent, null)

        degreeSymbol = res.getString(R.string.degree_symbol)
        fahrenheitSymbol = res.getString(R.string.fahrenheit_symbol)
        celsiusSymbol = res.getString(R.string.celsius_symbol)
        voltSymbol = res.getString(R.string.volt_symbol)
        percentSymbol = res.getString(R.string.percent_symbol)
        since = res.getString(R.string.since)
        defaultStatusDurEst = res.getString(R.string.default_status_dur_est)
        defaultRedThresh = res.getString(R.string.default_red_thresh)
        defaultAmberThresh = res.getString(R.string.default_amber_thresh)
        defaultGreenThresh = res.getString(R.string.default_green_thresh)
        defaultMaxLogAge = res.getString(R.string.default_max_log_age)
        defaultPredictionType = res.getString(R.string.default_prediction_type)

        logsEmpty = res.getString(R.string.logs_empty)
        confirmClearLogs = res.getString(R.string.confirm_clear_logs)
        yes = res.getString(R.string.yes)
        cancel = res.getString(R.string.cancel)
        okay = res.getString(R.string.okay)

        configureLogFilter = res.getString(R.string.configure_log_filter)

        currentlySetTo = res.getString(R.string.currently_set_to)
        alarmPrefNotUsed = res.getString(R.string.alarm_pref_not_used)

        alarmFullyCharged = res.getString(R.string.alarm_fully_charged)
        alarmChargeDrops = res.getString(R.string.alarm_charge_drops)
        alarmChargeRises = res.getString(R.string.alarm_charge_rises)
        alarmTempDrops = res.getString(R.string.alarm_temp_drops)
        alarmTempRises = res.getString(R.string.alarm_temp_rises)
        alarmHealthFailure = res.getString(R.string.alarm_health_failure)

        inaccessibleStorage = res.getString(R.string.inaccessible_storage)
        inaccessibleWReason = res.getString(R.string.inaccessible_w_reason)
        readOnlyStorage = res.getString(R.string.read_only_storage)
        noStoragePermission = res.getString(R.string.no_storage_permission)
        fileWritten = res.getString(R.string.file_written)

        date = res.getString(R.string.date)
        time = res.getString(R.string.time)
        status = res.getString(R.string.status)
        charge = res.getString(R.string.charge)
        temperature = res.getString(R.string.temperature)
        temperatureF = res.getString(R.string.temperature_f)
        voltage = res.getString(R.string.voltage)

        statusBootCompleted = res.getString(R.string.status_boot_completed)

        statuses = res.getStringArray(R.array.statuses)
        logStatuses = res.getStringArray(R.array.log_statuses)
        logStatusesOld = res.getStringArray(R.array.log_statuses_old)
        healths = res.getStringArray(R.array.healths)
        pluggeds = res.getStringArray(R.array.pluggeds)
        alarmTypesDisplay = res.getStringArray(R.array.alarm_types_display)
        alarmTypeEntries = res.getStringArray(R.array.alarm_type_entries)
        alarmTypeValues = res.getStringArray(R.array.alarm_type_values)
        tempAlarmEntries = res.getStringArray(R.array.temp_alarm_entries)
        tempAlarmValues = res.getStringArray(R.array.temp_alarm_values)

        logFilterPrefKeys = res.getStringArray(R.array.log_filter_pref_keys)
    }

    fun forNHours(n: Int): String {
        return String.format(res.getQuantityString(R.plurals.for_n_hours, n), n)
    }

    fun nHoursMMinutesLong(n: Int, m: Int): String {
        return (String.format(
            res.getQuantityString(R.plurals.n_hours_long, n), n
        ) + String.format(res.getQuantityString(R.plurals.n_minutes_long, m), m))
    }

    fun nMinutesLong(n: Int): String {
        return String.format(res.getQuantityString(R.plurals.n_minutes_long, n), n)
    }

    fun nHoursMMinutesMedium(n: Int, m: Int): String {
        return (String.format(
            res.getQuantityString(R.plurals.n_hours_medium, n), n
        ) + String.format(res.getQuantityString(R.plurals.n_minutes_medium, m), m))
    }

    fun nHoursLongMMinutesMedium(n: Int, m: Int): String {
        return (String.format(
            res.getQuantityString(R.plurals.n_hours_long, n), n
        ) + String.format(res.getQuantityString(R.plurals.n_minutes_medium, m), m))
    }

    fun nHoursMMinutesShort(n: Int, m: Int): String {
        return (String.format(
            res.getQuantityString(R.plurals.n_hours_short, n), n
        ) + String.format(res.getQuantityString(R.plurals.n_minutes_short, m), m))
    }

    fun nDaysMHours(n: Int, m: Int): String {
        return (String.format(
            res.getQuantityString(R.plurals.n_days, n), n
        ) + String.format(res.getQuantityString(R.plurals.n_hours, m), m))
    }

    fun nLogItems(n: Int): String {
        return String.format(res.getQuantityString(R.plurals.n_log_items, n), n)
    }

    @JvmOverloads
    fun formatTemp(temperature: Int, convertF: Boolean, includeTenths: Boolean = true): String {
        return formatTemp(temperature, convertF, includeTenths, resourceLocale())
    }

    internal fun formatTemp(
        temperature: Int, convertF: Boolean, includeTenths: Boolean, locale: Locale
    ): String {
        val d: Double
        val s: String

        if (convertF) {
            d = (temperature * 9 / 5.0).roundToInt() / 10.0 + 32.0
            s = degreeSymbol + fahrenheitSymbol
        } else {
            d = temperature / 10.0
            s = degreeSymbol + celsiusSymbol
        }

        val value = if (includeTenths) d else d.roundToInt().toDouble()
        val fractionDigits = if (includeTenths) 1 else 0
        return formatDecimal(value, fractionDigits, fractionDigits, locale) + s
    }

    fun formatVoltage(voltage: Int): String {
        return formatVoltage(voltage, resourceLocale())
    }

    internal fun formatVoltage(voltage: Int, locale: Locale): String {
        return formatDecimal(voltage / 1000.0, 1, 3, locale) + voltSymbol
    }

    fun formatTime(context: Context, date: Date, includeSeconds: Boolean = false): String {
        val locale = context.resources.configuration.locales[0]
        val is24Hour = android.text.format.DateFormat.is24HourFormat(context)

        return formatTime(date, locale, is24Hour, includeSeconds) { requestedLocale, skeleton ->
            android.text.format.DateFormat.getBestDateTimePattern(requestedLocale, skeleton)
        }
    }

    internal fun formatTime(
        date: Date,
        locale: Locale,
        is24Hour: Boolean,
        includeSeconds: Boolean,
        bestPattern: (Locale, String) -> String
    ): String {
        val skeleton = when {
            is24Hour && includeSeconds -> "Hms"
            is24Hour -> "Hm"
            includeSeconds -> "hms"
            else -> "hm"
        }
        val pattern = bestPattern(locale, skeleton)

        return SimpleDateFormat(pattern, locale).format(date)
    }

    private fun formatDecimal(
        value: Double, minimumFractionDigits: Int, maximumFractionDigits: Int, locale: Locale
    ): String {
        return DecimalFormat("0", DecimalFormatSymbols.getInstance(locale)).apply {
            isGroupingUsed = false
            this.minimumFractionDigits = minimumFractionDigits
            this.maximumFractionDigits = maximumFractionDigits
            roundingMode = RoundingMode.HALF_UP
        }.format(value)
    }

    private fun resourceLocale(): Locale {
        return res.configuration.locales[0]
    }

    fun indexOf(a: Array<out String?>, key: String): Int {
        var i = 0
        val size = a.size
        while (i < size) {
            if (key == a[i]) return i
            i++
        }

        return -1
    }

    fun timeRemaining(info: BatteryInfo): Spanned? {
        if (info.prediction.whatHappened == BatteryInfo.Prediction.NONE) {
            return fromHtmlLegacy(
                "<font color=\"#6fc14b\">" + statuses[info.status] + "</font>"
            )
        } else {
            val predicted = info.prediction.lastRTime

            if (predicted.days > 0) return fromHtmlLegacy(
                "<font color=\"#6fc14b\">" + String.format(
                    res.getString(R.string.unit_days), predicted.days
                ) + "</font> " + "<font color=\"#33b5e5\"><small>" + String.format(
                    res.getString(R.string.unit_hours), predicted.hours
                ) + "</small></font>"
            )
            else if (predicted.hours > 0) return fromHtmlLegacy(
                "<font color=\"#6fc14b\">" + String.format(
                    res.getString(R.string.unit_hours), predicted.hours
                ) + "</font> " + "<font color=\"#33b5e5\"><small>" + String.format(
                    res.getString(R.string.unit_minutes), predicted.minutes
                ) + "</small></font>"
            )
            else return fromHtmlLegacy(
                "<font color=\"#33b5e5\"><small>" + String.format(
                    res.getQuantityString(
                        R.plurals.n_minutes_medium, predicted.minutes
                    ), predicted.minutes
                ) + "</small></font>"
            )
        }
    }

    fun timeRemainingMainScreen(info: BatteryInfo): Spanned? {
        return if (info.prediction.whatHappened == BatteryInfo.Prediction.NONE) fromHtmlLegacy(
            "&nbsp;&nbsp;&nbsp;&mdash;&nbsp;&nbsp;&nbsp;"
        )
        else timeRemaining(info)
    }

    private fun fromHtmlLegacy(source: String): Spanned {
        return Html.fromHtml(source, Html.FROM_HTML_MODE_LEGACY)
    }

    fun untilWhat(info: BatteryInfo): String {
        return when (info.prediction.whatHappened) {
            BatteryInfo.Prediction.NONE -> ""
            BatteryInfo.Prediction.UNTIL_CHARGED -> res.getString(
                R.string.activity_until_charged
            )

            else -> res.getString(R.string.activity_until_drained)
        }
    }
}
