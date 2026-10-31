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
package codes.swistak.batterymonitor.monitoring

import android.content.Intent
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.app.PersistentFragment
import codes.swistak.batterymonitor.app.StartupDialogViewModel
import codes.swistak.batterymonitor.monitoring.presentation.CurrentDemand
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentDemandLifecycleInstrumentedTest {
    @Test
    fun finishing_the_activity_clears_its_fragments_demand_and_rejects_a_queued_reading() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences(SettingsContract.SP_MAIN_FILE, 0)
        val settings = context.getSharedPreferences(SettingsContract.SETTINGS_FILE, 0)
        val preferencesBefore = SettingsSnapshot.capture(preferences)
        val settingsBefore = SettingsSnapshot.capture(settings)
        val desiredBefore = MonitoringDesiredState.read(context)
        var activity: BatteryInfoActivity? = null
        try {
            assertTrue(MonitoringDesiredState.write(context, false))
            val version = PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0)
            )
            assertTrue(
                preferences.edit().putBoolean(SettingsContract.KEY_FIRST_RUN, false)
                    .putLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, version).commit()
            )
            activity = instrumentation.startActivitySync(
                Intent(
                    context,
                    BatteryInfoActivity::class.java
                ).putExtra(BatteryInfoService.EXTRA_EDIT_ALARMS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ) as BatteryInfoActivity
            val launched = activity
            lateinit var fragment: PersistentFragment
            var generation = 0
            var token = 0L
            instrumentation.runOnMainSync {
                launched.supportFragmentManager.executePendingTransactions()
                fragment = requireNotNull(
                    launched.supportFragmentManager.findFragmentByTag(PersistentFragment.FRAG_TAG)
                ) as PersistentFragment
                fragment.monitoring.start()
                generation = fragment.monitoring.onServiceConnected()
                assertTrue(fragment.monitoring.onHandshake(generation, "lifecycle-test"))
                val demand = CurrentDemand(2_000, false)
                fragment.setCurrentDemand(demand)
                assertEquals(demand, field(fragment, "currentDemand"))
                token = field(fragment, "currentToken") as Long
                assertTrue(
                    fragment.monitoring.publishCurrent(
                        generation,
                        "lifecycle-test",
                        0,
                        token,
                        CurrentSample(BatteryCurrentReading(-400_000, "controlled"), null)
                    )
                )
                launched.finish()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(launched.isDestroyed)
                assertNull(field(fragment, "currentDemand"))
                assertFalse(fragment.monitoring.current.value.connected)
                assertFalse(
                    fragment.monitoring.publishCurrent(
                        generation,
                        "lifecycle-test",
                        1,
                        token,
                        CurrentSample(BatteryCurrentReading(-500_000, "late"), null)
                    )
                )
                assertEquals(-400_000L, fragment.monitoring.current.value.sample.now?.microAmps)
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            assertTrue(MonitoringDesiredState.write(context, desiredBefore))
            assertTrue(SettingsSnapshot.apply(settings, settingsBefore))
            assertTrue(SettingsSnapshot.apply(preferences, preferencesBefore))
        }
    }

    private fun field(fragment: PersistentFragment, name: String): Any? =
        fragment.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(fragment)
}
