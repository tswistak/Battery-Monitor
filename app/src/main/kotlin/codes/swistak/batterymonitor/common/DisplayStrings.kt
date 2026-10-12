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
import codes.swistak.batterymonitor.settings.LongDurationFormat
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal object DisplayStrings {
    private lateinit var res: Resources

    var accentColor: Int = 0

    lateinit var degreeSymbol: String
    lateinit var fahrenheitSymbol: String
    lateinit var celsiusSymbol: String
    lateinit var voltSymbol: String
    lateinit var percentSymbol: String
    lateinit var since: String
    lateinit var defaultMaxLogAge: String
    lateinit var defaultPredictionType: String

    lateinit var alarmFullyCharged: String
    lateinit var alarmChargeDrops: String
    lateinit var alarmChargeRises: String
    lateinit var alarmTempDrops: String
    lateinit var alarmTempRises: String
    lateinit var alarmHealthFailure: String

    lateinit var statuses: Array<String>
    lateinit var healths: Array<String>
    lateinit var pluggeds: Array<String>

    fun setResources(r: Resources) {
        res = r

        accentColor = res.getColor(R.color.accent, null)

        degreeSymbol = res.getString(R.string.degree_symbol)
        fahrenheitSymbol = res.getString(R.string.fahrenheit_symbol)
        celsiusSymbol = res.getString(R.string.celsius_symbol)
        voltSymbol = res.getString(R.string.volt_symbol)
        percentSymbol = res.getString(R.string.percent_symbol)
        since = res.getString(R.string.since)
        defaultMaxLogAge = res.getString(R.string.default_max_log_age)
        defaultPredictionType = res.getString(R.string.default_prediction_type)

        alarmFullyCharged = res.getString(R.string.alarm_fully_charged)
        alarmChargeDrops = res.getString(R.string.alarm_charge_drops)
        alarmChargeRises = res.getString(R.string.alarm_charge_rises)
        alarmTempDrops = res.getString(R.string.alarm_temp_drops)
        alarmTempRises = res.getString(R.string.alarm_temp_rises)
        alarmHealthFailure = res.getString(R.string.alarm_health_failure)

        statuses = res.getStringArray(R.array.statuses)
        healths = res.getStringArray(R.array.healths)
        pluggeds = res.getStringArray(R.array.pluggeds)
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

    internal fun formatChargeDetailed(microAmpHours: Long, locale: Locale): String {
        return NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(microAmpHours / 1000.0)
    }

    fun formatChargeCompact(microAmpHours: Long): String {
        return formatChargeCompact(microAmpHours, resourceLocale())
    }

    internal fun formatChargeCompact(microAmpHours: Long, locale: Locale): String {
        return NumberFormat.getIntegerInstance(locale).format(
            (microAmpHours / 1000.0).roundToLong()
        )
    }

    fun formatTime(
        context: Context, date: Date, includeSeconds: Boolean = false, timeZone: TimeZone? = null
    ): String {
        val locale = context.resources.configuration.locales[0]
        val is24Hour = android.text.format.DateFormat.is24HourFormat(context)

        return formatTime(
            date, locale, is24Hour, includeSeconds, timeZone
        ) { requestedLocale, skeleton ->
            android.text.format.DateFormat.getBestDateTimePattern(requestedLocale, skeleton)
        }
    }

    internal fun formatTime(
        date: Date,
        locale: Locale,
        is24Hour: Boolean,
        includeSeconds: Boolean,
        timeZone: TimeZone? = null,
        bestPattern: (Locale, String) -> String
    ): String {
        val skeleton = when {
            is24Hour && includeSeconds -> "Hms"
            is24Hour -> "Hm"
            includeSeconds -> "hms"
            else -> "hm"
        }
        val pattern = bestPattern(locale, skeleton)

        return SimpleDateFormat(pattern, locale).apply {
            if (timeZone != null) this.timeZone = timeZone
        }.format(date)
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

    fun timeRemaining(
        info: BatteryInfo,
        longDurationFormat: LongDurationFormat,
        prediction: BatteryInfo.Prediction = info.prediction
    ): Spanned? {
        if (prediction.whatHappened == BatteryInfo.Prediction.NONE) {
            return fromHtmlLegacy(
                "<font color=\"#6fc14b\">" + statuses[info.status] + "</font>"
            )
        } else {
            val predicted = prediction.lastRTime

            if (predicted.days > 0 && longDurationFormat == LongDurationFormat.DAYS_AND_HOURS) {
                val (days, hours) = DurationFormatter.roundedDaysAndHours(
                    predicted.days, predicted.hours, predicted.minutes
                )
                return fromHtmlLegacy(
                    "<font color=\"#6fc14b\">" + String.format(
                        res.getString(R.string.unit_days), days
                    ) + "</font> " + "<font color=\"#33b5e5\"><small>" + String.format(
                        res.getString(R.string.unit_hours), hours
                    ) + "</small></font>"
                )
            }

            val hours = predicted.days * 24 + predicted.hours
            return if (hours > 0) {
                fromHtmlLegacy(
                    "<font color=\"#6fc14b\">" + String.format(
                        res.getString(R.string.unit_hours), hours
                    ) + "</font> " + "<font color=\"#33b5e5\"><small>" + String.format(
                        res.getString(R.string.unit_minutes), predicted.minutes
                    ) + "</small></font>"
                )
            } else {
                fromHtmlLegacy(
                    "<font color=\"#33b5e5\"><small>" + String.format(
                        res.getQuantityString(
                            R.plurals.n_minutes_medium, predicted.minutes
                        ), predicted.minutes
                    ) + "</small></font>"
                )
            }
        }
    }

    private fun fromHtmlLegacy(source: String): Spanned {
        return Html.fromHtml(source, Html.FROM_HTML_MODE_LEGACY)
    }

    fun untilWhat(
        info: BatteryInfo, prediction: BatteryInfo.Prediction = info.prediction
    ): String {
        if (prediction.targetReached) {
            if (prediction.whatHappened == BatteryInfo.Prediction.UNTIL_DRAINED) {
                return res.getString(R.string.activity_until_target, prediction.targetPercent)
            }
            return res.getString(
                R.string.activity_target_reached, prediction.targetPercent
            )
        }
        return when (prediction.whatHappened) {
            BatteryInfo.Prediction.NONE -> ""
            BatteryInfo.Prediction.UNTIL_CHARGED -> {
                if (prediction.targetPercent == 100) {
                    res.getString(R.string.activity_until_charged)
                } else {
                    res.getString(R.string.activity_until_target, prediction.targetPercent)
                }
            }

            else -> {
                if (prediction.targetPercent == 0) {
                    res.getString(R.string.activity_until_drained)
                } else {
                    res.getString(R.string.activity_until_target, prediction.targetPercent)
                }
            }
        }
    }
}
