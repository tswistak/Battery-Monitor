/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import codes.swistak.batterymonitor.monitoring.charginglimit.CHARGING_LIMIT_CACHE_MS
import codes.swistak.batterymonitor.monitoring.charginglimit.CHARGING_LIMIT_MAX_AGE_MS
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingLimitAccess
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingLimitEvidenceKind
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingLimitSnapshot
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingLimitState
import codes.swistak.batterymonitor.monitoring.charginglimit.NoFixedLimitKind
import codes.swistak.batterymonitor.monitoring.charginglimit.chargingLimitEvidence
import codes.swistak.batterymonitor.settings.SettingsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ChargingLimitAlarmRefreshTest {
    @Test
    fun `expired OEM limit emits once after its real crossing is confirmed`() = with(Session()) {
        primeAt(79)
        clock += CHARGING_LIMIT_MAX_AGE_MS

        observe(80)
        assertEquals(100, predictionTarget)
        observe(80)
        assertEquals(1, work.size)
        assertTrue(chargingAlarms.isEmpty())

        completeRead()
        assertEquals(80, predictionTarget)
        assertEquals(listOf(80), chargingAlarms)
        observe(80)
        assertEquals(listOf(80), chargingAlarms)
    }

    @Test
    fun `starting at the limit cannot manufacture a crossing`() = with(Session()) {
        primeAt(80)
        clock += CHARGING_LIMIT_MAX_AGE_MS
        observe(80)
        completeRead()
        assertTrue(chargingAlarms.isEmpty())
    }

    @Test
    fun `a new limit only alarms for a real crossing of that limit`() = with(Session()) {
        primeAt(79)
        clock += CHARGING_LIMIT_MAX_AGE_MS
        readResult = fixed(85)
        observe(80)
        completeRead()
        assertTrue(chargingAlarms.isEmpty())
        observe(85)
        assertEquals(listOf(85), chargingAlarms)

        clock += CHARGING_LIMIT_MAX_AGE_MS
        readResult = fixed(80)
        observe(85)
        completeRead()
        assertEquals(listOf(85), chargingAlarms)

        clock += CHARGING_LIMIT_MAX_AGE_MS
        readResult = fixed(90)
        observe(88)
        observe(90)
        observe(88)
        completeRead()
        assertEquals(listOf(85, 90), chargingAlarms)
    }

    @Test
    fun `a higher limit confirmed before the next sample can alarm after the lower limit fired`() =
        with(Session()) {
            primeAt(79)
            observe(80)
            assertEquals(listOf(80), chargingAlarms)

            clock += CHARGING_LIMIT_CACHE_MS
            readResult = fixed(85)
            refreshLimit()
            completeRead(processUpdate = false)
            observe(85)
            observe(85)
            assertEquals(listOf(80, 85), chargingAlarms)
        }

    @Test
    fun `a higher limit can confirm a crossing while the previous limit was still cached`() =
        with(Session()) {
            primeAt(79)
            observe(80)
            clock += CHARGING_LIMIT_CACHE_MS
            readResult = fixed(85)
            observe(85)
            assertEquals(80, predictionTarget)
            assertEquals(listOf(80), chargingAlarms)

            completeRead()
            assertEquals(85, predictionTarget)
            observe(85)
            assertEquals(listOf(80, 85), chargingAlarms)
        }

    @Test
    fun `a cached crossing alarms immediately and an unchanged refresh cannot repeat it`() =
        with(Session()) {
            primeAt(79)
            clock += CHARGING_LIMIT_CACHE_MS
            observe(80)
            assertEquals(listOf(80), chargingAlarms)

            completeRead(processUpdate = false)
            assertFalse(updateScheduled)
            observe(80)
            assertEquals(listOf(80), chargingAlarms)
        }

    @Test
    fun `a cached no-limit result still tracks a crossing for a newly enabled limit`() =
        with(Session()) {
            readResult = ChargingLimitState.NoFixedLimit(
                NoFixedLimitKind.DISABLED,
                chargingLimitEvidence(ChargingLimitEvidenceKind.CONFIG_SETTING)
            )
            observe(79)
            completeRead()
            assertEquals(100, predictionTarget)

            clock += CHARGING_LIMIT_CACHE_MS
            readResult = fixed(80)
            observe(80)
            assertEquals(100, predictionTarget)
            assertTrue(chargingAlarms.isEmpty())

            completeRead()
            observe(80)
            assertEquals(listOf(80), chargingAlarms)
        }

    @Test
    fun `a sample consumes the matching completion before tracking a new read`() = with(Session()) {
        primeAt(79)
        observe(80)
        clock += CHARGING_LIMIT_CACHE_MS
        readResult = fixed(85)
        observe(85)
        completeRead(processUpdate = false)

        clock += CHARGING_LIMIT_CACHE_MS
        readResult = fixed(90)
        observe(90)
        assertEquals(listOf(80, 85), chargingAlarms)
        completeRead()
        assertEquals(listOf(80, 85, 90), chargingAlarms)
    }

    @Test
    fun `a newer result cannot confirm a crossing recorded for an earlier read`() =
        with(Session()) {
            primeAt(79)
            observe(80)
            clock += CHARGING_LIMIT_CACHE_MS
            readResult = fixed(85)
            observe(90)
            completeRead(processUpdate = false)

            clock += CHARGING_LIMIT_CACHE_MS
            readResult = fixed(90)
            refreshLimit()
            completeRead(processUpdate = false)
            observe(90)
            assertEquals(listOf(80), chargingAlarms)
        }

    @Test
    fun `revocation and owner changes discard in-flight data and pre-change crossings`() {
        for (revoke in listOf(true, false)) with(Session()) {
            primeAt(79)
            clock += CHARGING_LIMIT_MAX_AGE_MS
            observe(80)
            work.removeFirst().run()
            access = if (revoke) access.copy(privileged = false, revision = 2)
            else access.copy(providerEpoch = 2)
            observe(80)
            deliveries.removeFirst().invoke()
            assertEquals(100, predictionTarget)
            assertTrue(chargingAlarms.isEmpty())
            completeRead()
            assertEquals(80, predictionTarget)
            assertTrue(chargingAlarms.isEmpty())
            observe(79)
            observe(80)
            assertEquals(listOf(80), chargingAlarms)
        }
    }

    @Test
    fun `a failed read or confirmed no limit cannot replay crossings on retry`() {
        for (outcome in listOf(
            null, ChargingLimitState.NoFixedLimit(
                NoFixedLimitKind.DISABLED,
                chargingLimitEvidence(ChargingLimitEvidenceKind.CONFIG_SETTING)
            )
        )) with(Session()) {
            primeAt(79)
            clock += CHARGING_LIMIT_MAX_AGE_MS
            readResult = outcome
            observe(80)
            // The service can be busy processing another sample when the refresh finishes.
            completeRead(processUpdate = false)
            clock += CHARGING_LIMIT_CACHE_MS
            readResult = fixed(80)
            observe(80)
            completeRead()
            assertTrue(chargingAlarms.isEmpty())
            observe(79)
            observe(80)
            assertEquals(listOf(80), chargingAlarms)
        }
    }

    @Test
    fun `unplug clears deferred crossings while a discharging alarm still fires`() =
        with(Session()) {
            preferences.edit { putInt(SettingsContract.KEY_DISCHARGING_TARGET, 79) }
            primeAt(79)
            clock += CHARGING_LIMIT_MAX_AGE_MS
            observe(80)
            observe(80, pluggedIn = false)
            observe(79, pluggedIn = false)
            observe(80)
            completeRead()
            assertTrue(chargingAlarms.isEmpty())
            assertEquals(listOf(79), dischargingAlarms)
            observe(79)
            observe(80)
            assertEquals(listOf(80), chargingAlarms)
        }

    @Test
    fun `custom target keeps working while an obsolete OEM read completes`() = with(Session()) {
        primeAt(79)
        clock += CHARGING_LIMIT_MAX_AGE_MS
        observe(80)
        work.removeFirst().run()
        preferences.edit {
            putString(
                SettingsContract.KEY_CHARGING_TARGET_MODE,
                SettingsContract.CHARGING_TARGET_MODE_CUSTOM
            )
            putInt(SettingsContract.KEY_CUSTOM_CHARGING_TARGET, 85)
        }
        observe(84)
        deliveries.removeFirst().invoke()
        assertTrue(work.isEmpty())
        observe(85)
        assertEquals(listOf(85), chargingAlarms)
    }

    private class Session {
        private val app = RuntimeEnvironment.getApplication()
        val preferences =
            app.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)!!
        val work = ArrayDeque<Runnable>()
        val deliveries = ArrayDeque<() -> Unit>()
        var clock = 0L
        var access = ChargingLimitAccess(true, 1, 1)
        var readResult: ChargingLimitState? = fixed(80)
        var predictionTarget: Int? = null
        val chargingAlarms = mutableListOf<Int>()
        val dischargingAlarms = mutableListOf<Int>()
        private val info = BatteryInfo()
        private val predictor = Predictor(app)
        private val evaluator = TargetAlarmEvaluator()
        var updateScheduled = false
            private set
        private val snapshot = ChargingLimitSnapshot(
            worker = { work.add(it) },
            deliver = { deliveries.add(it) },
            clock = { clock },
            currentAccess = { access.takeUnless { customMode() } },
            read = { requireNotNull(readResult) },
            onTargetChanged = { updateScheduled = true })

        init {
            preferences.edit { clear() }
        }

        private fun customMode(): Boolean = preferences.getString(
            SettingsContract.KEY_CHARGING_TARGET_MODE,
            SettingsContract.CHARGING_TARGET_MODE_AUTOMATIC
        ) == SettingsContract.CHARGING_TARGET_MODE_CUSTOM

        fun primeAt(percent: Int) {
            observe(percent)
            completeRead()
            assertEquals(80, predictionTarget)
            assertTrue(chargingAlarms.isEmpty())
        }

        fun observe(percent: Int, pluggedIn: Boolean = true) {
            info.percent = percent
            info.plugged = if (pluggedIn) BatteryInfo.PLUGGED_USB else BatteryInfo.PLUGGED_UNPLUGGED
            info.status =
                if (pluggedIn) BatteryInfo.STATUS_CHARGING else BatteryInfo.STATUS_UNPLUGGED
            updateTargets()
        }

        fun refreshLimit() {
            snapshot.refresh()
        }

        fun completeRead(processUpdate: Boolean = true) {
            work.removeFirst().run()
            deliveries.removeFirst().invoke()
            if (processUpdate) {
                assertTrue("Resolved refresh should schedule an evaluation", updateScheduled)
                updateTargets()
            }
        }

        private fun updateTargets() {
            updateScheduled = false
            val reading = snapshot.refresh()
            val targets = predictor.update(info, reading.state)
            predictionTarget = targets.charging.percent
            val alarms = evaluator.evaluate(TargetAlarmUpdate(info, targets, reading))
            if (alarms.chargingLimitReached) chargingAlarms.add(targets.charging.percent)
            if (alarms.dischargingLimitReached) dischargingAlarms.add(targets.discharging.percent)
        }
    }

    private companion object {
        fun fixed(percent: Int) = ChargingLimitState.Fixed(
            percent, evidence = chargingLimitEvidence(ChargingLimitEvidenceKind.CONFIG_SETTING)
        )
    }
}
