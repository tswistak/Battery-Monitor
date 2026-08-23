/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetAlarmEvaluatorTest {
    private val plugged = BatteryInfo.PLUGGED_USB
    private val unplugged = BatteryInfo.PLUGGED_UNPLUGGED

    @Test
    fun `charging limit fires when reaching the target exactly`() {
        val evaluator = TargetAlarmEvaluator()

        assertFalse(charging(evaluator, 79).chargingLimitReached)
        assertTrue(evaluator.chargingArmed)
        assertTrue(charging(evaluator, 80).chargingLimitReached)
    }

    @Test
    fun `charging limit fires when skipping over the target by one`() {
        val evaluator = TargetAlarmEvaluator()

        assertFalse(charging(evaluator, 79).chargingLimitReached)
        assertTrue(charging(evaluator, 81).chargingLimitReached)
    }

    @Test
    fun `charging limit fires when skipping over the target by four`() {
        val evaluator = TargetAlarmEvaluator()

        assertFalse(charging(evaluator, 78).chargingLimitReached)
        assertTrue(charging(evaluator, 82).chargingLimitReached)
    }

    @Test
    fun `charging limit does not fire while staying at the target`() {
        val evaluator = TargetAlarmEvaluator()

        assertFalse(charging(evaluator, 80).chargingLimitReached)
        assertFalse(charging(evaluator, 80).chargingLimitReached)
    }

    @Test
    fun `charging limit does not fire after the target was reached`() {
        val evaluator = TargetAlarmEvaluator()

        charging(evaluator, 79)
        assertTrue(charging(evaluator, 80).chargingLimitReached)
        assertFalse(charging(evaluator, 81).chargingLimitReached)
    }

    @Test
    fun `charging limit does not fire when plugged in above the target`() {
        val evaluator = TargetAlarmEvaluator()

        discharging(evaluator, 85)
        assertFalse(charging(evaluator, 85).chargingLimitReached)
        assertFalse(evaluator.chargingArmed)
    }

    @Test
    fun `charging limit does not fire when enabled above the target`() {
        val evaluator = TargetAlarmEvaluator()

        assertFalse(charging(evaluator, 85).chargingLimitReached)
        assertFalse(charging(evaluator, 85).chargingLimitReached)
    }

    @Test
    fun `charging limit fires for plugged devices that stop charging at the target`() {
        val evaluator = TargetAlarmEvaluator()

        assertFalse(charging(evaluator, 79).chargingLimitReached)
        assertTrue(charging(evaluator, 80).chargingLimitReached)
        assertFalse(charging(evaluator, 80).chargingLimitReached)
    }

    @Test
    fun `charging limit follows a custom target`() {
        val evaluator = TargetAlarmEvaluator()

        assertFalse(charging(evaluator, 84, target = 85).chargingLimitReached)
        assertTrue(charging(evaluator, 85, target = 85).chargingLimitReached)
    }

    @Test
    fun `default charging target of one hundred never fires`() {
        val evaluator = TargetAlarmEvaluator()

        assertFalse(charging(evaluator, 99, target = 100).chargingLimitReached)
        assertFalse(charging(evaluator, 100, target = 100).chargingLimitReached)
        assertFalse(charging(evaluator, 99, target = 100).chargingLimitReached)
        assertFalse(charging(evaluator, 100, target = 100).chargingLimitReached)
    }

    @Test
    fun `discharging limit fires when reaching the target exactly`() {
        val evaluator = TargetAlarmEvaluator()

        assertFalse(discharging(evaluator, 21).dischargingLimitReached)
        assertTrue(evaluator.dischargingArmed)
        assertTrue(discharging(evaluator, 20).dischargingLimitReached)
    }

    @Test
    fun `discharging limit fires when skipping over the target by one`() {
        val evaluator = TargetAlarmEvaluator()

        assertFalse(discharging(evaluator, 21).dischargingLimitReached)
        assertTrue(discharging(evaluator, 19).dischargingLimitReached)
    }

    @Test
    fun `discharging limit fires when skipping over the target by four`() {
        val evaluator = TargetAlarmEvaluator()

        assertFalse(discharging(evaluator, 22).dischargingLimitReached)
        assertTrue(discharging(evaluator, 18).dischargingLimitReached)
    }

    @Test
    fun `discharging limit does not fire while staying at the target`() {
        val evaluator = TargetAlarmEvaluator()

        assertFalse(discharging(evaluator, 20).dischargingLimitReached)
        assertFalse(discharging(evaluator, 20).dischargingLimitReached)
    }

    @Test
    fun `discharging limit does not fire after the target was reached`() {
        val evaluator = TargetAlarmEvaluator()

        discharging(evaluator, 21)
        assertTrue(discharging(evaluator, 20).dischargingLimitReached)
        assertFalse(discharging(evaluator, 19).dischargingLimitReached)
    }

    @Test
    fun `discharging limit does not fire when unplugged below the target`() {
        val evaluator = TargetAlarmEvaluator()

        charging(evaluator, 15)
        assertFalse(discharging(evaluator, 15).dischargingLimitReached)
        assertFalse(evaluator.dischargingArmed)
    }

    @Test
    fun `discharging limit does not fire when enabled below the target`() {
        val evaluator = TargetAlarmEvaluator()

        assertFalse(discharging(evaluator, 15).dischargingLimitReached)
        assertFalse(discharging(evaluator, 15).dischargingLimitReached)
    }

    @Test
    fun `discharging limit does not fire again while remaining below the target`() {
        val evaluator = TargetAlarmEvaluator()

        discharging(evaluator, 21)
        assertTrue(discharging(evaluator, 19).dischargingLimitReached)
        assertFalse(discharging(evaluator, 18).dischargingLimitReached)
        assertFalse(discharging(evaluator, 10).dischargingLimitReached)
    }

    @Test
    fun `discharging limit follows a custom target`() {
        val evaluator = TargetAlarmEvaluator()

        assertFalse(discharging(evaluator, 16, target = 15).dischargingLimitReached)
        assertTrue(discharging(evaluator, 15, target = 15).dischargingLimitReached)
    }

    @Test
    fun `default discharging target of zero never fires`() {
        val evaluator = TargetAlarmEvaluator()

        assertFalse(discharging(evaluator, 5, target = 0).dischargingLimitReached)
        assertFalse(discharging(evaluator, 0, target = 0).dischargingLimitReached)
    }

    @Test
    fun `plugged device passing through the target does not fire discharging alarm`() {
        val evaluator = TargetAlarmEvaluator()

        assertFalse(discharging(evaluator, 25, pluggedValue = plugged).dischargingLimitReached)
        assertFalse(discharging(evaluator, 19, pluggedValue = plugged).dischargingLimitReached)
    }

    @Test
    fun `charging limit fires again in a later charging session`() {
        val evaluator = TargetAlarmEvaluator()

        charging(evaluator, 70)
        assertTrue(charging(evaluator, 80).chargingLimitReached)
        assertFalse(charging(evaluator, 90).chargingLimitReached)

        discharging(evaluator, 90)
        discharging(evaluator, 60)

        charging(evaluator, 60)
        assertFalse(charging(evaluator, 70).chargingLimitReached)
        assertTrue(charging(evaluator, 80).chargingLimitReached)
    }

    @Test
    fun `discharging limit fires again in a later discharging session`() {
        val evaluator = TargetAlarmEvaluator()

        discharging(evaluator, 80)
        assertTrue(discharging(evaluator, 20).dischargingLimitReached)
        assertFalse(discharging(evaluator, 10).dischargingLimitReached)

        charging(evaluator, 10)
        charging(evaluator, 80)

        assertFalse(discharging(evaluator, 80).dischargingLimitReached)
        assertTrue(discharging(evaluator, 20).dischargingLimitReached)
    }

    @Test
    fun `lowering the charging target below the current level does not fire`() {
        val evaluator = TargetAlarmEvaluator()

        charging(evaluator, 82, target = 85)
        assertTrue(evaluator.chargingArmed)

        assertFalse(charging(evaluator, 82, target = 80).chargingLimitReached)
        assertFalse(evaluator.chargingArmed)
    }

    @Test
    fun `raising the discharging target above the current level does not fire`() {
        val evaluator = TargetAlarmEvaluator()

        discharging(evaluator, 18, target = 15)
        assertTrue(evaluator.dischargingArmed)

        assertFalse(discharging(evaluator, 18, target = 20).dischargingLimitReached)
        assertFalse(evaluator.dischargingArmed)
    }

    @Test
    fun `lowering the charging target above the current level still fires on a real crossing`() {
        val evaluator = TargetAlarmEvaluator()

        charging(evaluator, 78, target = 85)
        assertTrue(evaluator.chargingArmed)

        assertFalse(charging(evaluator, 78, target = 80).chargingLimitReached)
        assertTrue(charging(evaluator, 81, target = 80).chargingLimitReached)
    }

    @Test
    fun `raising the discharging target below the current level still fires on a real crossing`() {
        val evaluator = TargetAlarmEvaluator()

        discharging(evaluator, 25, target = 15)
        assertTrue(evaluator.dischargingArmed)

        assertFalse(discharging(evaluator, 25, target = 20).dischargingLimitReached)
        assertTrue(discharging(evaluator, 19, target = 20).dischargingLimitReached)
    }

    private fun charging(
        evaluator: TargetAlarmEvaluator, percent: Int, target: Int = 80, pluggedValue: Int = plugged
    ): TargetAlarmResult = evaluator.evaluate(
        TargetAlarmUpdate(percent, pluggedValue, target, 0)
    )

    private fun discharging(
        evaluator: TargetAlarmEvaluator,
        percent: Int,
        target: Int = 20,
        pluggedValue: Int = unplugged
    ): TargetAlarmResult = evaluator.evaluate(
        TargetAlarmUpdate(percent, pluggedValue, 100, target)
    )
}
