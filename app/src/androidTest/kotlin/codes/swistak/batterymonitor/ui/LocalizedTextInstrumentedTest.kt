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
package codes.swistak.batterymonitor.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LocalizedTextInstrumentedTest {
    @Test
    fun compiled_onboarding_and_help_paragraphs_keep_breaks_without_indentation() {
        val paragraphs = listOf(
            R.string.live_updates_onboarding_message_on,
            R.string.live_updates_onboarding_message_off,
            R.string.notifications_onboarding_message,
            R.string.pref_charging_target_mode_summary_automatic_help,
            R.string.battery_optimized_message
        )
        for (language in listOf("en", "pl")) {
            val context = localizedContext(language)
            for (id in paragraphs) {
                val text = if (id == R.string.pref_charging_target_mode_summary_automatic_help) {
                    context.getString(id, "80%")
                } else {
                    context.getString(id)
                }
                val label = "$language/${context.resources.getResourceEntryName(id)}"
                assertTrue("$label must retain paragraph breaks", text.contains("\n\n"))
                assertFalse("$label must compile Android newline escapes", text.contains("\\n"))
                assertTrue(
                    "$label must not indent paragraphs",
                    text.lineSequence().all { !it.startsWith(' ') && !it.startsWith('\t') })
            }
        }
    }

    @Test
    fun compiled_measurement_and_duration_resources_preserve_non_breaking_separators() {
        for (language in listOf("en", "pl")) {
            val context = localizedContext(language)
            val readings = listOf(
                context.getString(
                    R.string.current_remaining_charge,
                    "3500"
                )
            ) + listOf(R.plurals.n_hours, R.plurals.n_minutes_long).flatMap { id ->
                listOf(1, 2, 5).map { count ->
                    context.resources.getQuantityString(
                        id, count, count
                    )
                }
            }
            for (text in readings) {
                val number = requireNotNull(Regex("[0-9]+").find(text))
                assertEquals(
                    "$language compiled number/unit separator: $text",
                    '\u00a0',
                    text.getOrNull(number.range.last + 1)
                )
            }
        }
    }

    private fun localizedContext(language: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        return base.createConfigurationContext(configuration)
    }
}
