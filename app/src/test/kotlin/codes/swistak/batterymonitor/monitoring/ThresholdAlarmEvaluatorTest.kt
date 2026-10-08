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
package codes.swistak.batterymonitor.monitoring

import codes.swistak.batterymonitor.alarms.AlarmRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdAlarmEvaluatorTest {
    @Test
    fun `each threshold type fires at an exact or skipped crossing but not at repeated equal values`() {
        for ((rule, before, beyond) in listOf(
            Triple(AlarmRule(1, true, "charge_rises", "80"), 79, 82),
            Triple(AlarmRule(1, true, "charge_drops", "20"), 21, 18),
            Triple(AlarmRule(1, true, "temp_rises", "375"), 374, 380),
            Triple(AlarmRule(1, true, "temp_drops", "60"), 61, 55)
        )) {
            for (after in listOf(rule.threshold.toInt(), beyond)) {
                val evaluator = ThresholdAlarmEvaluator()
                assertTrue(evaluator.evaluate(before, before, listOf(rule)).isEmpty())
                assertEquals(listOf(rule), evaluator.evaluate(after, after, listOf(rule)))
                assertTrue(evaluator.evaluate(after, after, listOf(rule)).isEmpty())
            }
        }
    }

    @Test
    fun `starting enabling editing and importing beyond a threshold initialize without firing`() {
        val original = AlarmRule(1, true, "charge_rises", "80")
        val evaluator = ThresholdAlarmEvaluator()
        assertTrue(evaluator.evaluate(85, 250, listOf(original)).isEmpty())
        evaluator.evaluate(79, 250, listOf(original.copy(enabled = false)))
        assertTrue(evaluator.evaluate(82, 250, listOf(original)).isEmpty())

        val edited = original.copy(threshold = "85")
        evaluator.evaluate(82, 250, listOf(edited))
        assertEquals(listOf(edited), evaluator.evaluate(85, 250, listOf(edited)))
        assertTrue(evaluator.evaluate(85, 250, listOf(original)).isEmpty())

        evaluator.reset()
        assertTrue(evaluator.evaluate(82, 250, listOf(original)).isEmpty())
        evaluator.evaluate(79, 250, listOf(original))
        assertEquals(listOf(original), evaluator.evaluate(82, 250, listOf(original)))
    }

    @Test
    fun `recovery and a later recrossing fire again while staying on one side does not`() {
        val rule = AlarmRule(1, true, "charge_drops", "20")
        val evaluator = ThresholdAlarmEvaluator()
        evaluator.evaluate(21, 250, listOf(rule))
        assertEquals(listOf(rule), evaluator.evaluate(20, 250, listOf(rule)))
        assertTrue(evaluator.evaluate(19, 250, listOf(rule)).isEmpty())
        assertTrue(evaluator.evaluate(20, 250, listOf(rule)).isEmpty())
        assertTrue(evaluator.evaluate(21, 250, listOf(rule)).isEmpty())
        assertEquals(listOf(rule), evaluator.evaluate(20, 250, listOf(rule)))
    }

    @Test
    fun `duplicate rules remain separate while delivery stays one notification per type`() {
        val first = AlarmRule(1, true, "charge_rises", "80")
        val second = AlarmRule(2, true, "charge_rises", "81")
        val evaluator = ThresholdAlarmEvaluator()
        evaluator.evaluate(79, 250, listOf(first, second))
        assertEquals(listOf(first), evaluator.evaluate(82, 250, listOf(first, second)))
        evaluator.evaluate(79, 250, listOf(second))
        assertEquals(listOf(second), evaluator.evaluate(82, 250, listOf(second)))
    }

    @Test
    fun `invalid and unsupported rules do not fire`() {
        val rules = listOf(
            AlarmRule(1, true, "charge_rises", "101"),
            AlarmRule(2, true, "temp_rises", "1001"),
            AlarmRule(3, true, "charge_drops", "invalid"),
            AlarmRule(4, true, "future", "80")
        )
        val evaluator = ThresholdAlarmEvaluator()
        evaluator.evaluate(79, 900, rules)
        assertTrue(evaluator.evaluate(102, 1100, rules).isEmpty())
    }

    @Test
    fun `zero one hundred and temperature limits remain valid thresholds`() {
        for ((rule, before, at) in listOf(
            Triple(AlarmRule(1, true, "charge_drops", "0"), 1, 0),
            Triple(AlarmRule(1, true, "charge_rises", "100"), 99, 100),
            Triple(AlarmRule(1, true, "temp_drops", "-500"), -499, -500),
            Triple(AlarmRule(1, true, "temp_rises", "1000"), 999, 1000)
        )) {
            val evaluator = ThresholdAlarmEvaluator()
            evaluator.evaluate(before, before, listOf(rule))
            assertEquals(listOf(rule), evaluator.evaluate(at, at, listOf(rule)))
        }
    }

    @Test
    fun `low battery dismissal requires opt in recovery and the owning notification`() {
        assertTrue(shouldDismissRecoveredLowBatteryAlarm(true, 21, "charge_drops", 20))
        assertFalse(shouldDismissRecoveredLowBatteryAlarm(false, 21, "charge_drops", 20))
        assertFalse(shouldDismissRecoveredLowBatteryAlarm(true, 20, "charge_drops", 20))
        assertFalse(shouldDismissRecoveredLowBatteryAlarm(true, 19, "charge_drops", 20))
        for (other in listOf("charge_rises", "temp_rises", "health_failure", null)) {
            assertFalse(shouldDismissRecoveredLowBatteryAlarm(true, 21, other, 20))
        }
        assertFalse(shouldDismissRecoveredLowBatteryAlarm(true, 21, "charge_drops", null))
        assertFalse(shouldDismissRecoveredLowBatteryAlarm(true, 21, "charge_drops", -1))
    }
}
