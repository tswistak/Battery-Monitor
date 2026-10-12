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

import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.alarms.AlarmConflictException
import codes.swistak.batterymonitor.alarms.AlarmDatabase
import codes.swistak.batterymonitor.alarms.AlarmGroup
import codes.swistak.batterymonitor.alarms.AlarmRule
import codes.swistak.batterymonitor.alarms.projectAlarms
import codes.swistak.batterymonitor.alarms.requireAlarmMembers
import codes.swistak.batterymonitor.alarms.thresholdCollision
import codes.swistak.batterymonitor.monitoring.ThresholdAlarmEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmGroupingTest {
    @Test
    fun `empty storage keeps the fixed catalog and no implicit records`() {
        val projection = projectAlarms(emptyList())
        assertEquals(
            listOf(
                "fully_charged",
                "charge_rises",
                "charge_drops",
                "charging_limit_met",
                "discharging_limit_met",
                "temp_rises",
                "temp_drops",
                "health_failure"
            ), projection.types.map { it.definition.type })
        assertEquals(
            AlarmDatabase.SUPPORTED_TYPES, projection.types.map { it.definition.type }.toSet()
        )
        assertEquals(
            listOf(3, 2, 2, 1),
            AlarmGroup.entries.map { group -> projection.types.count { it.definition.group == group } })
        assertTrue(projection.types.all { it.alarms.isEmpty() })
        assertEquals(0, projection.enabledCount)
    }

    @Test
    fun `duplicates are logical rows with all member ids and any enabled`() {
        val rules = listOf(
            AlarmRule(1, false, "fully_charged", "legacy"),
            AlarmRule(2, true, "fully_charged", ""),
            AlarmRule(3, false, "charge_drops", "020"),
            AlarmRule(4, true, "charge_drops", "20"),
            AlarmRule(5, true, "charge_rises", "20")
        )
        val projection = projectAlarms(rules)
        assertEquals(3, projection.enabledCount)
        val full = projection.types.single { it.definition.type == "fully_charged" }.alarms.single()
        assertEquals(listOf(1, 2), full.memberIds)
        assertTrue(full.enabled)
        val falling =
            projection.types.single { it.definition.type == "charge_drops" }.alarms.single()
        assertEquals(listOf(3, 4), falling.memberIds)
        assertTrue(falling.enabled)
        assertEquals(20, falling.threshold)
    }

    @Test
    fun `sorting follows direction independently of enabled and preserves evaluator input order`() {
        val rules = listOf(
            AlarmRule(1, true, "charge_drops", "15"),
            AlarmRule(2, true, "charge_drops", "20"),
            AlarmRule(3, false, "charge_drops", "17"),
            AlarmRule(4, false, "temp_rises", "451"),
            AlarmRule(5, true, "temp_rises", "-10")
        )
        val before = rules.toList()
        val projection = projectAlarms(rules)
        assertEquals(
            listOf(20, 17, 15),
            projection.types.single { it.definition.type == "charge_drops" }.alarms.map { it.threshold })
        assertEquals(
            listOf(-10, 451),
            projection.types.single { it.definition.type == "temp_rises" }.alarms.map { it.threshold })
        assertEquals(before, rules)
        val evaluator = ThresholdAlarmEvaluator()
        evaluator.evaluate(21, 250, rules)
        assertEquals(listOf(rules.first()), evaluator.evaluate(14, 250, rules))
    }

    @Test
    fun `invalid rules are retained explicitly and never interpreted as zero`() {
        val invalid = listOf(
            AlarmRule(1, true, "charge_rises", "bad"),
            AlarmRule(2, false, "temp_drops", "-501"),
            AlarmRule(3, true, "future", "20"),
            AlarmRule(4, true, "", "")
        )
        val projection = projectAlarms(invalid)
        assertEquals(invalid, projection.invalid)
        assertTrue(projection.types.all { it.alarms.isEmpty() })
    }

    @Test
    fun `canonical collisions include disabled records but allow another type and the edited group`() {
        val rules = listOf(
            AlarmRule(1, false, "charge_drops", "020"), AlarmRule(2, true, "charge_drops", "20")
        )
        assertEquals(
            listOf(1, 2), thresholdCollision(rules, "charge_drops", "20", emptyList())!!.memberIds
        )
        assertNull(thresholdCollision(rules, "charge_rises", "20", emptyList()))
        assertNull(thresholdCollision(rules, "charge_drops", "20", listOf(1, 2)))
    }

    @Test
    fun `member validation detects deletion insertion key changes and flag changes`() {
        val expected = listOf(
            AlarmRule(1, false, "charge_drops", "020"), AlarmRule(2, true, "charge_drops", "20")
        )
        requireAlarmMembers(expected.reversed(), expected)
        for (current in listOf(
            expected.drop(1),
            expected + AlarmRule(3, false, "charge_drops", "20"),
            listOf(expected[0].copy(threshold = "19"), expected[1]),
            listOf(expected[0].copy(enabled = true), expected[1])
        )) {
            assertThrows(AlarmConflictException::class.java) {
                requireAlarmMembers(
                    current, expected
                )
            }
        }
    }

    @Test
    fun `loading first error and stale snapshot never allow mutations or fabricate count`() {
        assertNull(AlarmsState().enabledCount)
        assertFalse(AlarmsState().canMutate)
        val firstError = AlarmsState(loading = false, readError = "error")
        assertNull(firstError.enabledCount)
        assertFalse(firstError.canMutate)
        val loaded = AlarmsState(hasSnapshot = true, loading = false)
        assertEquals(0, loaded.enabledCount)
        assertTrue(loaded.canMutate)
        assertFalse(loaded.copy(readError = "stale").canMutate)
        assertEquals(0, loaded.copy(readError = "stale").enabledCount)
        assertFalse(loaded.copy(busy = true).canMutate)
    }

    @Test
    fun `delivery availability permission group and monitor are independent of enabled configuration`() {
        val enabled = AlarmRule(1, true, "fully_charged", "")
        val state = AlarmsState(
            rules = listOf(enabled),
            projection = projectAlarms(listOf(enabled)),
            hasSnapshot = true,
            loading = false,
            notificationsBlocked = true,
            groupBlocked = true,
            monitoringDesired = false
        )
        assertEquals(1, state.enabledCount)
        assertEquals(
            R.string.alarms_channel_unavailable, alarmDeliveryLabel(
                AlarmChannelInfo(
                    available = false, blocked = false, sound = false, vibration = false
                )
            )
        )
        assertEquals(
            R.string.alarm_chan_disabled, alarmDeliveryLabel(
                AlarmChannelInfo(
                    available = true, blocked = true, sound = false, vibration = false
                )
            )
        )
        assertEquals(
            R.string.alarms_delivery_notification, alarmDeliveryLabel(
                AlarmChannelInfo(
                    available = true, blocked = false, sound = false, vibration = false
                )
            )
        )
    }

    @Test
    fun `draft context retains raw input and unchanged Fahrenheit retains the exact stored threshold`() {
        val original = AlarmDraft(
            id = 7,
            enabled = false,
            type = "temp_rises",
            threshold = "451",
            thresholdInput = "113.2",
            initialInput = "113.2",
            inputLocale = "en-US",
            inputFahrenheit = true
        )
        assertEquals("451", editThresholdInput(original, "113.2").threshold)
        val negative = editThresholdInput(original, "-58")
        assertEquals("-500", negative.threshold)
        assertFalse(negative.enabled)
        val invalid = editThresholdInput(original, "-")
        assertEquals("-", invalid.thresholdInput)
        assertEquals("", invalid.threshold)
        assertFalse(validAlarmDraft(invalid))
        assertEquals("451", editThresholdInput(invalid, "113.2").threshold)
        val localized =
            original.copy(inputLocale = "pl", inputFahrenheit = false, initialInput = "45,1")
        assertEquals("279", editThresholdInput(localized, "27,9").threshold)
        assertEquals("pl", editThresholdInput(localized, "27,9").inputLocale)
        assertEquals("451", original.threshold)
        assertEquals("", AlarmDraft(type = "charge_drops", thresholdInput = "").threshold)
    }
}
