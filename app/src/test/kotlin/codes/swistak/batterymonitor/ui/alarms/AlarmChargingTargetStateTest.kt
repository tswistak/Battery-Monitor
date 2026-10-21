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

import android.content.SharedPreferences
import codes.swistak.batterymonitor.monitoring.BatteryInfo
import codes.swistak.batterymonitor.monitoring.PredictorCore
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingLimitEvidence
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingLimitEvidenceKind
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingLimitProvider
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingLimitState
import codes.swistak.batterymonitor.monitoring.charginglimit.ChargingLimitUnavailableReason
import codes.swistak.batterymonitor.monitoring.charginglimit.NoFixedLimitKind
import codes.swistak.batterymonitor.monitoring.charginglimit.ReadSource
import codes.swistak.batterymonitor.settings.SettingsContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.coroutines.CoroutineContext

class AlarmChargingTargetStateTest {
    private val preferences = TestPreferences()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val io = QueuedDispatcher()
    private val accessChanges = MutableStateFlow(0L)
    private val evidence = ChargingLimitEvidence(
        ChargingLimitEvidenceKind.CONFIG_SETTING, setOf(ReadSource.CONTENT_RESOLVER)
    )
    private var deviceState: ChargingLimitState = fixed(80)
    private var requiresPrivilege = false
    private var accessAvailable = true
    private var detectionFailure = false
    private var reads = 0
    private var reading = false
    private var onRead: (() -> Unit)? = null
    private var prepareCalls = 0
    private var onPrepare: (() -> Unit)? = null
    private val targetState =
        AlarmChargingTargetState(
            preferences.instance, scope, createProvider = {
            assertTrue("Provider construction must run on IO", io.running)
            var cached: ChargingLimitState? = null
            ChargingLimitProvider {
                cached ?: run {
                    assertFalse("OEM reads must not overlap", reading)
                    reading = true
                    try {
                        reads++
                        if (detectionFailure) error("OEM read failed")
                        val detected =
                            if (requiresPrivilege && (!accessAvailable || !preferences.privileged)) {
                                ChargingLimitState.Unavailable(ChargingLimitUnavailableReason.READ_FAILED)
                            } else deviceState
                        val callback = onRead
                        onRead = null
                        callback?.invoke()
                        detected.also { cached = it }
                    } finally {
                        reading = false
                    }
                }
            }
        }, accessChanges = accessChanges, prepareAccess = {
            prepareCalls++
            onPrepare?.invoke()
        }, ioDispatcher = io
        )

    @After
    fun tearDown() {
        scope.cancel()
        io.runAll()
        assertTrue(preferences.listeners.isEmpty())
    }

    @Test
    fun `unplugged prediction to twenty does not replace the eighty percent charging target`() {
        val predictor = PredictorCore(60_000f, 60_000f, 60_000f, 60_000f)
        predictor.setTargets(chargingTargetPercent = 80, dischargingTargetPercent = 20)
        predictor.setPredictionType(PredictorCore.LONG_TERM)
        val battery = BatteryInfo().apply {
            status = BatteryInfo.STATUS_UNPLUGGED
            plugged = BatteryInfo.PLUGGED_UNPLUGGED
            percent = 60
        }
        predictor.update(battery, 0)
        assertEquals(20, battery.prediction.targetPercent)

        activate()

        assertEquals(80, targetState.target.value)
    }

    @Test
    fun `plug and unplug transitions leave the observed charging target at eighty`() {
        activate()
        val predictor = PredictorCore(60_000f, 60_000f, 60_000f, 60_000f)
        predictor.setTargets(80, 20)
        val battery = BatteryInfo().apply { percent = 60 }

        for (plugged in listOf(true, false, true, false)) {
            battery.plugged =
                if (plugged) BatteryInfo.PLUGGED_USB else BatteryInfo.PLUGGED_UNPLUGGED
            battery.status =
                if (plugged) BatteryInfo.STATUS_CHARGING else BatteryInfo.STATUS_UNPLUGGED
            predictor.update(battery, 0)
            assertEquals(80, targetState.target.value)
        }
        assertEquals(1, reads)
    }

    @Test
    fun `automatic mode without a fixed limit uses one hundred without a live snapshot`() {
        deviceState = ChargingLimitState.NoFixedLimit(NoFixedLimitKind.DISABLED, evidence)
        activate()
        assertEquals(100, targetState.target.value)
    }

    @Test
    fun `custom ninety overrides eighty and switching back restores automatic eighty`() {
        activate()
        preferences.put(SettingsContract.KEY_CUSTOM_CHARGING_TARGET, 90)
        preferences.put(
            SettingsContract.KEY_CHARGING_TARGET_MODE, SettingsContract.CHARGING_TARGET_MODE_CUSTOM
        )
        io.runAll()
        assertEquals(90, targetState.target.value)
        assertEquals(1, reads)

        preferences.put(
            SettingsContract.KEY_CHARGING_TARGET_MODE,
            SettingsContract.CHARGING_TARGET_MODE_AUTOMATIC
        )
        io.runAll()
        assertEquals(80, targetState.target.value)
    }

    @Test
    fun `custom mode never constructs an OEM provider even when detection would fail`() {
        preferences.put(SettingsContract.KEY_CUSTOM_CHARGING_TARGET, 90)
        preferences.put(
            SettingsContract.KEY_CHARGING_TARGET_MODE, SettingsContract.CHARGING_TARGET_MODE_CUSTOM
        )
        detectionFailure = true
        activate()
        assertEquals(90, targetState.target.value)
        assertEquals(0, reads)
    }

    @Test
    fun `returning from background invalidates an unexpired OEM cache`() {
        activate()
        targetState.setActive(false)
        deviceState = fixed(85)
        assertEquals(80, targetState.target.value)

        activate()

        assertEquals(85, targetState.target.value)
        assertEquals(2, reads)
    }

    @Test
    fun `repeated refreshes reuse cached detection and deduplicate in flight work`() {
        targetState.setActive(true)
        repeat(20) { targetState.refresh() }
        assertEquals(0, reads)
        assertNull(targetState.target.value)
        io.runAll()
        repeat(20) {
            targetState.refresh()
            io.runAll()
        }
        assertEquals(80, targetState.target.value)
        assertEquals(1, reads)
        assertEquals(1, prepareCalls)
    }

    @Test
    fun `access setup completes before its availability event can start OEM work`() {
        onPrepare = {
            accessChanges.value++
            io.runAll()
            assertEquals(0, reads)
        }

        activate()

        assertEquals(80, targetState.target.value)
        assertEquals(1, reads)
    }

    @Test
    fun `privileged preference and availability changes invalidate cached fallback`() {
        requiresPrivilege = true
        activate()
        assertEquals(100, targetState.target.value)

        preferences.put(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, true)
        io.runAll()
        assertEquals(80, targetState.target.value)

        accessAvailable = false
        accessChanges.value++
        io.runAll()
        assertEquals(100, targetState.target.value)

        accessAvailable = true
        accessChanges.value++
        io.runAll()
        assertEquals(80, targetState.target.value)

        preferences.put(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false)
        io.runAll()
        assertEquals(100, targetState.target.value)
    }

    @Test
    fun `unavailable detection and unexpected failures replace an outdated target with the Settings fallback`() {
        activate()
        for (reason in ChargingLimitUnavailableReason.entries) {
            targetState.setActive(false)
            deviceState = ChargingLimitState.Unavailable(reason)
            activate()
            assertEquals(100, targetState.target.value)
        }
        targetState.setActive(false)
        detectionFailure = true
        activate()
        assertEquals(100, targetState.target.value)
    }

    @Test
    fun `late cancelled read cannot publish over changed custom preferences`() {
        val observed = mutableListOf<Int?>()
        scope.launch { targetState.target.collect { observed += it } }
        onRead = {
            preferences.put(SettingsContract.KEY_CUSTOM_CHARGING_TARGET, 90)
            preferences.put(
                SettingsContract.KEY_CHARGING_TARGET_MODE,
                SettingsContract.CHARGING_TARGET_MODE_CUSTOM
            )
            io.runAll()
        }

        activate()

        assertEquals(90, targetState.target.value)
        assertFalse(observed.contains(80))
        assertEquals(1, reads)
    }

    @Test
    fun `access loss during a blocking read discards the old result and serializes the next read`() {
        preferences.put(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, true)
        requiresPrivilege = true
        onRead = {
            accessAvailable = false
            accessChanges.value++
            io.runAll()
            assertEquals(1, reads)
        }

        activate()

        assertEquals(100, targetState.target.value)
        assertEquals(2, reads)
    }

    @Test
    fun `leaving Alarms cancels publication and ignores changes until the next visit`() {
        onRead = { targetState.setActive(false) }
        activate()
        assertNull(targetState.target.value)

        deviceState = fixed(85)
        accessChanges.value++
        preferences.put(SettingsContract.KEY_CUSTOM_CHARGING_TARGET, 90)
        io.runAll()
        assertEquals(1, reads)

        activate()
        assertEquals(85, targetState.target.value)
    }

    @Test
    fun `unrelated preference changes do not refresh OEM detection`() {
        activate()
        preferences.put(SettingsContract.KEY_DISCHARGING_TARGET, 20)
        io.runAll()
        assertEquals(1, reads)
        assertEquals(1, prepareCalls)
    }

    private fun activate() {
        targetState.setActive(true)
        io.runAll()
    }

    private fun fixed(percent: Int) = ChargingLimitState.Fixed(percent, evidence = evidence)

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        var running = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun runAll() {
            val wasRunning = running
            running = true
            try {
                var count = 0
                while (queue.isNotEmpty()) {
                    check(count++ < 100) { "Refresh loop did not settle" }
                    queue.removeFirst().run()
                }
            } finally {
                running = wasRunning
            }
        }
    }

    private class TestPreferences {
        private val values = mutableMapOf<String, Any>()
        val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        val privileged: Boolean get() = values[SettingsContract.KEY_USE_PRIVILEGED_ACCESS] == true
        val instance = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString", "getInt", "getBoolean" -> values[args!![0]] ?: args[1]
                "registerOnSharedPreferenceChangeListener" -> {
                    listeners += args!![0] as SharedPreferences.OnSharedPreferenceChangeListener
                    null
                }

                "unregisterOnSharedPreferenceChangeListener" -> {
                    listeners -= args!![0] as SharedPreferences.OnSharedPreferenceChangeListener
                    null
                }

                else -> error("Unexpected preference method: ${method.name}")
            }
        } as SharedPreferences

        fun put(key: String, value: Any) {
            values[key] = value
            listeners.toList().forEach { it.onSharedPreferenceChanged(instance, key) }
        }
    }
}
