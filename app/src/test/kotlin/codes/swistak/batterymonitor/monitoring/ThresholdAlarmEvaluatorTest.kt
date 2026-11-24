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
import codes.swistak.batterymonitor.alarms.LogicalAlarm
import codes.swistak.batterymonitor.alarms.projectAlarms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdAlarmEvaluatorTest {
    @Test
    fun `21 to 14 crosses both configured logical thresholds`() {
        val twenty = AlarmRule(1, true, "charge_drops", "20")
        val fifteen = AlarmRule(2, true, "charge_drops", "15")
        for (rules in listOf(listOf(twenty, fifteen), listOf(fifteen, twenty))) {
            val evaluator = ThresholdAlarmEvaluator()
            assertTrue(evaluator.evaluateRules(21, 250, rules).isEmpty())
            assertEquals(rules.toSet(), evaluator.evaluateRules(14, 250, rules).toSet())
        }
    }

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
                assertTrue(evaluator.evaluateRules(before, before, listOf(rule)).isEmpty())
                assertEquals(listOf(rule), evaluator.evaluateRules(after, after, listOf(rule)))
                assertTrue(evaluator.evaluateRules(after, after, listOf(rule)).isEmpty())
            }
        }
    }

    @Test
    fun `starting enabling editing and importing beyond a threshold initialize without firing`() {
        val original = AlarmRule(1, true, "charge_rises", "80")
        val evaluator = ThresholdAlarmEvaluator()
        assertTrue(evaluator.evaluateRules(85, 250, listOf(original)).isEmpty())
        evaluator.evaluateRules(79, 250, listOf(original.copy(enabled = false)))
        assertTrue(evaluator.evaluateRules(82, 250, listOf(original)).isEmpty())

        val edited = original.copy(threshold = "85")
        evaluator.evaluateRules(82, 250, listOf(edited))
        assertEquals(listOf(edited), evaluator.evaluateRules(85, 250, listOf(edited)))
        assertTrue(evaluator.evaluateRules(85, 250, listOf(original)).isEmpty())

        evaluator.reset()
        assertTrue(evaluator.evaluateRules(82, 250, listOf(original)).isEmpty())
        evaluator.evaluateRules(79, 250, listOf(original))
        assertEquals(listOf(original), evaluator.evaluateRules(82, 250, listOf(original)))
    }

    @Test
    fun `recovery and a later recrossing fire again while staying on one side does not`() {
        val rule = AlarmRule(1, true, "charge_drops", "20")
        val evaluator = ThresholdAlarmEvaluator()
        evaluator.evaluateRules(21, 250, listOf(rule))
        assertEquals(listOf(rule), evaluator.evaluateRules(20, 250, listOf(rule)))
        assertTrue(evaluator.evaluateRules(19, 250, listOf(rule)).isEmpty())
        assertTrue(evaluator.evaluateRules(20, 250, listOf(rule)).isEmpty())
        assertTrue(evaluator.evaluateRules(21, 250, listOf(rule)).isEmpty())
        assertEquals(listOf(rule), evaluator.evaluateRules(20, 250, listOf(rule)))
    }

    @Test
    fun `legacy duplicates fire once with the oldest logical ID even when that member is disabled`() {
        val first = AlarmRule(1, false, "charge_rises", "80")
        val duplicate = AlarmRule(2, true, "charge_rises", "080")
        val secondThreshold = AlarmRule(3, true, "charge_rises", "81")
        val alarms = thresholdAlarms(listOf(first, duplicate, secondThreshold))
        val evaluator = ThresholdAlarmEvaluator()
        evaluator.evaluate(79, 250, alarms)
        val crossed = evaluator.evaluate(82, 250, alarms)
        assertEquals(listOf(1, 3), crossed.map(LogicalAlarm::id))
        assertTrue(evaluator.evaluate(82, 250, alarms).isEmpty())
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
        evaluator.evaluateRules(79, 900, rules)
        assertTrue(evaluator.evaluateRules(102, 1100, rules).isEmpty())
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
            evaluator.evaluateRules(before, before, listOf(rule))
            assertEquals(listOf(rule), evaluator.evaluateRules(at, at, listOf(rule)))
        }
    }

    @Test
    fun `low battery dismissal requires opt in and recovery past each threshold`() {
        val battery = BatteryInfo().apply { percent = 16 }
        fun dismiss(threshold: Int, optIn: Boolean, repeating: Boolean = true) =
            shouldDismissAlarmAfterBatteryUpdate(
                "charge_drops", threshold, repeating, battery, 80, 10, optIn
            )
        assertTrue(dismiss(15, true))
        assertTrue(dismiss(15, true, repeating = false))
        assertFalse(dismiss(15, false))
        assertFalse(dismiss(16, true))
        assertFalse(dismiss(20, true))
    }

    @Test
    fun `session changes and recovery cancel only applicable alarms`() {
        val battery = BatteryInfo().apply {
            plugged = BatteryInfo.PLUGGED_UNPLUGGED
            health = BatteryInfo.HEALTH_GOOD
            percent = 80
            temperature = 400
        }

        fun dismiss(type: String, triggerValue: Int? = null, repeating: Boolean = true) =
            shouldDismissAlarmAfterBatteryUpdate(
                type, triggerValue, repeating, battery, 80, 10, false
            )

        assertTrue(dismiss("fully_charged"))
        assertTrue(dismiss("charge_rises", 80))
        assertTrue(dismiss("charging_limit_met", 80))
        assertFalse(dismiss("charge_drops", 80))
        assertFalse(dismiss("temp_rises", 375))
        assertTrue(dismiss("health_failure"))
        assertFalse(dismiss("health_failure", repeating = false))

        battery.plugged = BatteryInfo.PLUGGED_USB
        battery.temperature = 365
        assertTrue(dismiss("discharging_limit_met", 10))
        assertTrue(dismiss("temp_rises", 375))
        assertFalse(dismiss("temp_rises", 375, repeating = false))
        assertFalse(dismiss("charging_limit_met", 80))
        assertTrue(dismiss("charging_limit_met", 70))
    }

    private fun thresholdAlarms(rules: List<AlarmRule>): List<LogicalAlarm> =
        projectAlarms(rules).types.filter { it.definition.bounds != null }.flatMap { it.alarms }

    private fun ThresholdAlarmEvaluator.evaluateRules(
        percent: Int, temperature: Int, rules: List<AlarmRule>
    ): List<AlarmRule> =
        evaluate(percent, temperature, thresholdAlarms(rules)).map { it.members.single() }
}
