/*
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.settings.SettingsContract
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class VitalSignsDetailsInstrumentedTest {
    @Test
    fun expandedDetailsKeepElapsedStateOnceAndUseContextualHealthInVitalOrder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val res = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag("pl"))
            }).resources
        val elapsed = "Odłączony przez: 1d"
        val remaining = "4h"
        val temperature = "32,6°C"
        val entries = listOf(
            SettingsContract.VITAL_SIGN_TEMPERATURE to temperature,
            SettingsContract.VITAL_SIGN_STATUS_DURATION to "1d",
            SettingsContract.VITAL_SIGN_HEALTH to res.getString(R.string.health_good)
        )

        fun labeled(label: String, value: String) =
            res.getString(R.string.pref_expanded_notification_format, label, value)

        val primaryLines = listOf(
            labeled(res.getString(R.string.time_remaining), remaining), elapsed
        )

        assertEquals(
            primaryLines + listOf(
                labeled(res.getString(R.string.pref_content_temperature), temperature), labeled(
                    res.getString(R.string.notification_battery_health),
                    res.getString(R.string.notification_health_good)
                )
            ), VitalSignsDetailsFormatter.detailedText(
                res, remaining, elapsed, entries, BatteryInfo.HEALTH_GOOD
            ).lines()
        )
        assertEquals(
            primaryLines, VitalSignsDetailsFormatter.detailedText(
                res, remaining, elapsed, emptyList(), BatteryInfo.HEALTH_GOOD
            ).lines()
        )
        assertEquals(
            listOf(temperature, "1d", res.getString(R.string.health_good)).joinToString(" / "),
            VitalSignsDetailsFormatter.collapsedLine(entries)
        )
    }
}
