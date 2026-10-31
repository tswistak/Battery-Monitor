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
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.advancedstats.AdvancedBatteryStatsCollector
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.ui.diagnostics.normalizedDiagnosticGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryReadingSourcesInstrumentedTest {
    @Test
    fun live_android_charging_source_reaches_diagnostics_without_privileged_collection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        DisplayStrings.setResources(context.resources)
        val intent = requireNotNull(
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        )
        val snapshot = AdvancedBatteryStatsCollector.collectBasic(context)
        val charging = normalizedDiagnosticGroups(snapshot, 1)[2].rows
        val row = charging.single { it.rawKey == "plugged" }

        assertEquals(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1).toString(), row.rawValue)
        assertEquals("ACTION_BATTERY_CHANGED / plugged", row.source)
        assertEquals("Android", row.accessMethod)
        assertTrue(row.valueLabel != 0)
        assertTrue(row.observedAtMillis > 0)
        val fields = snapshot.serviceLabels.zip(snapshot.serviceValues).toMap()
        val fastCharging = charging.single { it.rawKey == "fast_charging" }
        if (fields["hv_charger"] == "true" && fields["status"] == "2" && fields["plugged"] in setOf(
                "1", "2"
            )
        ) {
            assertEquals("ACTION_BATTERY_CHANGED / hv_charger", fastCharging.source)
            assertEquals(R.string.yes, fastCharging.valueLabel)
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("charging_source", row.rawValue)
            putString("source", row.source)
            putString(
                "fast_charging", fastCharging.valueLabel.takeIf { it != 0 }?.let(context::getString)
            )
            putString(
                "battery_broadcast",
                snapshot.serviceLabels.zip(snapshot.serviceValues).joinToString()
            )
        })
    }
}
