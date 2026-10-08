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
package codes.swistak.batterymonitor.ui.alarms

import codes.swistak.batterymonitor.alarms.AlarmDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AlarmDraftTest {
    @Test
    fun `all eight alarm types have valid legacy defaults and reject unknown types`() {
        assertEquals(8, AlarmDatabase.SUPPORTED_TYPES.size)
        AlarmDatabase.SUPPORTED_TYPES.forEach { type ->
            assertTrue(
                validAlarmDraft(
                    AlarmDraft(
                        type = type, threshold = defaultAlarmThreshold(type)
                    )
                )
            )
        }
        assertEquals("20", defaultAlarmThreshold("charge_drops"))
        assertEquals("90", defaultAlarmThreshold("charge_rises"))
        assertEquals("60", defaultAlarmThreshold("temp_drops"))
        assertEquals("460", defaultAlarmThreshold("temp_rises"))
        assertFalse(validAlarmDraft(AlarmDraft(type = "estimated_time")))
        assertFalse(validAlarmDraft(AlarmDraft(threshold = "80")))
    }

    @Test
    fun `threshold validation preserves custom values and accepts only physical boundaries`() {
        for (type in listOf("charge_drops", "charge_rises")) {
            for (value in listOf("0", "23", "100")) {
                assertTrue(validAlarmDraft(AlarmDraft(type = type, threshold = value)))
            }
            for (value in listOf("", "-1", "101", "20.5", "2147483648")) {
                assertFalse(validAlarmDraft(AlarmDraft(type = type, threshold = value)))
            }
        }
        for (type in listOf("temp_drops", "temp_rises")) {
            for (value in listOf("-500", "279", "1000")) {
                assertTrue(validAlarmDraft(AlarmDraft(type = type, threshold = value)))
            }
            for (value in listOf("-501", "1001", "", "NaN")) {
                assertFalse(validAlarmDraft(AlarmDraft(type = type, threshold = value)))
            }
        }
    }

    @Test
    fun `every stored tenth Celsius survives localized input round trips in either temperature unit`() {
        for (type in listOf("temp_drops", "temp_rises")) {
            for (value in -500..1000) {
                val draft =
                    AlarmDraft(id = 7, enabled = false, type = type, threshold = value.toString())
                for (locale in listOf(Locale.US, Locale.forLanguageTag("pl"))) {
                    for (fahrenheit in listOf(false, true)) {
                        assertEquals(
                            "$type $value $locale Fahrenheit=$fahrenheit",
                            draft,
                            withAlarmThresholdInput(
                                draft,
                                alarmThresholdInput(draft, fahrenheit, locale),
                                fahrenheit,
                                locale
                            )
                        )
                    }
                }
            }
        }
        val charge = AlarmDraft(type = "charge_rises", threshold = "023")
        assertEquals(charge, withAlarmThresholdInput(charge, "23", false, Locale.US))
    }

    @Test
    fun `localized fractional temperatures are stored as tenths Celsius with unchanged record identity`() {
        val draft = AlarmDraft(id = 17, enabled = false, type = "temp_rises", threshold = "316")
        assertEquals(
            draft.copy(threshold = "279"),
            withAlarmThresholdInput(draft, "27,9", false, Locale.forLanguageTag("pl"))
        )
        assertEquals(
            draft.copy(threshold = "-1"), withAlarmThresholdInput(draft, "-0.1", false, Locale.US)
        )
        assertEquals(
            draft.copy(threshold = "333"), withAlarmThresholdInput(draft, "91.9", true, Locale.US)
        )
        assertEquals(
            draft.copy(threshold = "-500"), withAlarmThresholdInput(draft, "-58", true, Locale.US)
        )
        assertEquals(
            draft.copy(threshold = "1000"), withAlarmThresholdInput(draft, "212", true, Locale.US)
        )
        assertEquals(-50..100, alarmThresholdBounds(draft.type, false))
        assertEquals(-58..212, alarmThresholdBounds(draft.type, true))
        assertEquals(0..100, alarmThresholdBounds("charge_rises", true))
        assertEquals(31.6, alarmThresholdValue(draft, false), 0.000001)
        assertEquals(88.88, alarmThresholdValue(draft, true), 0.000001)
    }

    @Test
    fun `invalid and empty input stays in the draft and cannot save a previous valid threshold`() {
        for (locale in listOf(Locale.US, Locale.forLanguageTag("pl"))) {
            for (fahrenheit in listOf(false, true)) {
                val draft =
                    AlarmDraft(id = 6, enabled = false, type = "temp_drops", threshold = "60")
                val inputs = listOf(
                    "",
                    " ",
                    "-",
                    "27.",
                    "27,",
                    "27.00",
                    "27,00",
                    "1E2",
                    "NaN",
                    "12x",
                    "1010",
                    "1,000",
                    "1.000"
                ) + if (fahrenheit) listOf("-58.1", "212.1", "-58,1", "212,1")
                else listOf("-50.1", "100.1", "-50,1", "100,1")
                for (text in inputs) {
                    val edited = withAlarmThresholdInput(draft, text, fahrenheit, locale)
                    assertEquals(text, edited.thresholdInput)
                    assertEquals("", edited.threshold)
                    assertEquals(draft.id, edited.id)
                    assertEquals(draft.enabled, edited.enabled)
                    assertFalse("$locale $text Fahrenheit=$fahrenheit", validAlarmDraft(edited))
                    assertFalse(draft == edited)
                }
            }
        }
    }

    @Test
    fun `percentages require whole values and localized decimal separators cannot be mistaken for grouping`() {
        val draft = AlarmDraft(type = "charge_rises", threshold = "80")
        for (locale in listOf(Locale.US, Locale.forLanguageTag("pl"))) {
            for (text in listOf("0", "1", "23", "99", "100")) {
                val edited = withAlarmThresholdInput(draft, text, false, locale)
                assertEquals(text, edited.threshold)
                assertTrue(validAlarmDraft(edited))
            }
            for (text in listOf("80.0", "80,0", "1,0", "1.0", "101", "1010", "-1", "1E2", "--1")) {
                assertFalse(validAlarmDraft(withAlarmThresholdInput(draft, text, false, locale)))
            }
        }
        val temperature = draft.copy(type = "temp_rises", threshold = "460")
        assertFalse(validAlarmDraft(withAlarmThresholdInput(temperature, "27,9", false, Locale.US)))
        assertFalse(
            validAlarmDraft(
                withAlarmThresholdInput(
                    temperature, "27.9", false, Locale.forLanguageTag("pl")
                )
            )
        )
    }

    @Test
    fun `noncanonical valid input is retained without changing its physical value`() {
        val draft = AlarmDraft(type = "charge_rises", threshold = "80")
        val edited = withAlarmThresholdInput(draft, "080", false, Locale.US)
        assertEquals("80", edited.threshold)
        assertEquals("080", edited.thresholdInput)
        assertTrue(validAlarmDraft(edited))
        assertEquals("80", alarmThresholdInput(edited, false, Locale.US))
        val localized = withAlarmThresholdInput(draft, "٨٠", false, Locale.forLanguageTag("ar"))
        assertEquals("80", localized.threshold)
        assertTrue(validAlarmDraft(localized))
    }
}
