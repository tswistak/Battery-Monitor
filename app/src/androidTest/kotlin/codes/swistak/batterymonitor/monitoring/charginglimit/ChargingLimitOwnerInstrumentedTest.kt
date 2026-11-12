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

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>.
*/
package codes.swistak.batterymonitor.monitoring.charginglimit

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.privileged.PrivilegedAccess
import codes.swistak.batterymonitor.settings.SettingsContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ChargingLimitOwnerInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.getSharedPreferences(SettingsContract.SETTINGS_FILE, 0)
    private val workers = Executors.newFixedThreadPool(3)
    private val release = CountDownLatch(1)
    private val entered = CountDownLatch(1)
    private val commands = AtomicInteger()
    private val elapsed = AtomicLong()
    private lateinit var owner: ChargingLimitContentProvider
    private val readerField =
        ChargingLimitContentProvider::class.java.getDeclaredField($$"reader$delegate")
            .apply { isAccessible = true }
    private var originalReader: Any? = null
    private var originalPreference: Boolean? = null

    @Volatile
    private var block = false

    @Before
    fun setUp() {
        originalPreference =
            if (preferences.contains(SettingsContract.KEY_USE_PRIVILEGED_ACCESS)) preferences.getBoolean(
                SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false
            ) else null
        instrumentation.runOnMainSync {
            preferences.edit().putBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, true).commit()
        }
        instrumentation.waitForIdleSync()
        context.contentResolver.acquireContentProviderClient(
            ChargingLimitContentProvider.uri(
                context
            )
        ).use {
            owner = requireNotNull(it?.localContentProvider) as ChargingLimitContentProvider
        }
        originalReader = readerField.get(owner)
        installReader()
    }

    @After
    fun tearDown() {
        release.countDown()
        workers.shutdown()
        assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
        originalReader?.let { readerField.set(owner, it) }
        instrumentation.runOnMainSync {
            preferences.edit().apply {
                originalPreference?.let {
                    putBoolean(
                        SettingsContract.KEY_USE_PRIVILEGED_ACCESS, it
                    )
                } ?: remove(SettingsContract.KEY_USE_PRIVILEGED_ACCESS)
            }.commit()
        }
        instrumentation.waitForIdleSync()
    }

    private fun installReader(state: ChargingLimitState? = null) {
        val adapter = object : ChargingLimitAdapter {
            override val id = "synthetic"
            override fun supports(profile: DeviceProfile) = true
            override fun readState(
                profile: DeviceProfile,
                settings: SettingReader,
                battery: BatteryChargingStateReader,
                powerSupply: PowerSupplyReader
            ): ChargingLimitState {
                state?.let { return it }
                val result =
                    settings.read(SettingNamespace.GLOBAL, "battery_monitor_a2_synthetic_limit")
                return if (result is ReadResult.Value) ChargingLimitState.Fixed(
                    result.value.toInt(), evidence = chargingLimitEvidence(
                        ChargingLimitEvidenceKind.HARDWARE_STATE, result
                    )
                ) else unavailableSettingRead(id, result)
            }
        }
        val reader = DeviceChargingLimitProvider(
            context, privilegedAccessEnabled = {
                preferences.getBoolean(
                    SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false
                ) && PrivilegedAccess.isEnabled()
            }, privilegedCommand = {
                check(Looper.myLooper() != Looper.getMainLooper())
                commands.incrementAndGet()
                entered.countDown()
                if (block) {
                    check(release.await(10, TimeUnit.SECONDS))
                    elapsed.addAndGet(CHARGING_LIMIT_CACHE_MS * 2)
                }
                "80"
            }, adapters = listOf(adapter), elapsedRealtime = elapsed::get
        )
        readerField.set(owner, lazyOf(reader))
    }

    private fun remoteRead() = requireNotNull(
        context.contentResolver.call(
            Uri.parse("content://${context.packageName}.charging-limit-probe"), "read", null, null
        )
    )

    @Test
    fun localAndMonitoringProcessRequestsShareOneCollectionWithoutBlockingMain() {
        block = true
        val first =
            workers.submit<ChargingLimitState> { ChargingLimitContentProvider.read(context) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val remote = workers.submit<android.os.Bundle> { remoteRead() }
        val second =
            workers.submit<ChargingLimitState> { ChargingLimitContentProvider.read(context) }
        val mainResponsive = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { mainResponsive.countDown() }
        assertTrue(mainResponsive.await(2, TimeUnit.SECONDS))
        release.countDown()
        assertEquals(80, resolveDetectedChargingTarget(first.get(10, TimeUnit.SECONDS)).percent)
        assertEquals(first.get(), second.get(10, TimeUnit.SECONDS))
        val reply = remote.get(10, TimeUnit.SECONDS)
        assertNotEquals(Process.myPid(), reply.getInt("pid"))
        assertEquals(80, reply.getInt("percent"))
        assertEquals(TargetSource.DEVICE.name, reply.getString("source"))
        assertEquals(1, commands.get())
    }

    @Test
    fun revocationDuringCollectionRejectsResultAndNotifiesConsumers() {
        block = true
        val pending = workers.submit<android.os.Bundle> { remoteRead() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val notified = CountDownLatch(1)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                notified.countDown()
            }
        }
        context.contentResolver.registerContentObserver(
            ChargingLimitContentProvider.uri(context), false, observer
        )
        try {
            instrumentation.runOnMainSync {
                preferences.edit().putBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false)
                    .commit()
            }
            assertTrue(notified.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(100, pending.get(10, TimeUnit.SECONDS).getInt("percent"))
            assertEquals(100, remoteRead().getInt("percent"))
            assertEquals(1, commands.get())
        } finally {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    @Test
    fun ipcPreservesEvidenceAndUnavailableStatesAndRejectsInvalidLimits() {
        val evidence = chargingLimitEvidence(
            ChargingLimitEvidenceKind.SETTING_AND_HARDWARE,
            ReadResult.Value("80", ReadSource.PRIVILEGED_SHELL),
            ReadResult.Value("80", ReadSource.APP_FILE)
        )
        val states = listOf(
            ChargingLimitState.Fixed(85, 80, evidence),
            ChargingLimitState.NoFixedLimit(NoFixedLimitKind.ADAPTIVE, evidence),
            ChargingLimitState.Unavailable(
                ChargingLimitUnavailableReason.UNKNOWN_VALUE, "synthetic", "test"
            )
        )
        for (state in states) {
            installReader(state)
            assertEquals(state, ChargingLimitContentProvider.read(context))
            assertEquals(
                resolveDetectedChargingTarget(state).percent, remoteRead().getInt("percent")
            )
        }
        installReader(ChargingLimitState.Fixed(150, evidence = evidence))
        assertEquals(100, remoteRead().getInt("percent"))
    }

    @Test
    fun mainThreadCannotStartLocalOrIpcCollection() {
        instrumentation.runOnMainSync {
            assertThrows(IllegalStateException::class.java) {
                ChargingLimitContentProvider.read(
                    context
                )
            }
            assertThrows(IllegalStateException::class.java) { owner.call("read", null, null) }
        }
        assertEquals(0, commands.get())
    }
}
