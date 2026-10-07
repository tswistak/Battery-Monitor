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
package codes.swistak.batterymonitor.ui.diagnostics

import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DiagnosticsModelTest {
    @Test
    fun `raw search matches original keys without formatting values or exposing identifiers by default`() {
        val snapshot = AdvancedBatterySnapshot().apply {
            accessMethod = "Android"
            capturedAtMillis = 42
            currentNowUa = -900000
            sysfsLabels.add("battery/serial_number")
            sysfsValues.add("  private serial\nsecond line\n")
        }
        val groups = diagnosticGroups(snapshot, 1)
        val result = filterRawGroups(groups.drop(3), " SERIAL ").single().rows.single()
        assertEquals("battery/serial_number", result.rawKey)
        assertEquals("  private serial\nsecond line\n", result.value)
        assertEquals(result.rawValue, result.value)
        assertEquals(0, result.label)
        assertEquals("", result.unit)
        assertEquals(42L, result.observedAtMillis)
        assertTrue(filterRawGroups(groups.drop(3), "private").isEmpty())
        assertFalse(diagnosticReport(groups, { it.toString() }, false).contains("private serial"))
        assertTrue(
            diagnosticReport(
                groups, { it.toString() }, true
            ).contains("  private serial\nsecond line\n")
        )
    }

    @Test
    fun `current calibration affects battery current but not the Android charger limit`() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val snapshot = AdvancedBatterySnapshot().apply {
                currentNowUa = -900; maxChargingCurrentUa = 2000000
            }
            val groups = diagnosticGroups(snapshot, 1000)
            assertEquals(
                "-900 mA",
                groups[0].rows.single { it.label == R.string.advanced_field_current_now }.value
            )
            assertEquals(
                "2000 mA",
                groups[2].rows.single { it.label == R.string.advanced_field_max_charging_current }.value
            )
            assertEquals(
                "-900",
                groups[0].rows.single { it.label == R.string.advanced_field_current_now }.rawValue
            )
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `normalized groups use available readings once and preserve each reading source and time`() {
        val android = AdvancedBatterySnapshot().apply {
            accessMethod = "Android"
            capturedAtMillis = 200
            currentNowUa = -493000
            chargeCounterUah = 2651000
            reportedCapacityPercent = 73
            fieldSources["current_now"] = "BatteryManager / BATTERY_PROPERTY_CURRENT_NOW"
            fieldSources["capacity"] = "ACTION_BATTERY_CHANGED / level / scale"
        }
        val shizuku = AdvancedBatterySnapshot().apply {
            accessMethod = AdvancedBatterySnapshot.ACCESS_SHIZUKU
            capturedAtMillis = 100
            chargeCounterUah = 2655000
            fullChargeUah = 3000000
            maxChargingCurrentUa = 0
            fieldSources["charge_full"] = "/sys/class/power_supply/battery/charge_full"
        }
        val groups = mergedDiagnosticGroups(listOf(android, shizuku), 1)
        assertEquals(3, groups.size)
        val rows = groups.flatMap { it.rows }
        assertEquals(rows.size, rows.map { it.rawKey }.distinct().size)
        val current = rows.single { it.rawKey == "current_now" }
        assertEquals("-493000", current.rawValue)
        assertEquals("BatteryManager / BATTERY_PROPERTY_CURRENT_NOW", current.source)
        assertEquals("Android", current.accessMethod)
        assertEquals(200L, current.observedAtMillis)
        assertEquals("2651000", rows.single { it.rawKey == "charge_counter" }.rawValue)
        assertEquals(
            "ACTION_BATTERY_CHANGED / level / scale", rows.single { it.rawKey == "capacity" }.source
        )
        val full = rows.single { it.rawKey == "charge_full" }
        assertEquals("3000000", full.rawValue)
        assertEquals("Shizuku", full.accessMethod)
        assertEquals(100L, full.observedAtMillis)
        assertEquals("0", rows.single { it.rawKey == "max_charging_current" }.rawValue)
        assertNull(rows.single { it.rawKey == "estimated_health" }.value)
        assertTrue(diagnosticGroupSources(groups[1]).contains("/sys/class/power_supply (Shizuku)"))
        shizuku.currentNowUa = -600000
        shizuku.capturedAtMillis = 300
        assertEquals(
            "-600000", mergedDiagnosticGroups(
                listOf(android, shizuku), 1
            )[0].rows.single { it.rawKey == "current_now" }.rawValue
        )
        assertTrue(mergedDiagnosticGroups(emptyList(), 1).isEmpty())
    }

    @Test
    fun `estimated health requires positive capacities from the same power supply`() {
        val snapshot =
            AdvancedBatterySnapshot().apply { fullChargeUah = 3000000; designChargeUah = 4000000 }
        assertNull(capacityHealth(snapshot))
        snapshot.fieldSources["charge_full"] = "/sys/class/power_supply/battery/charge_full"
        snapshot.fieldSources["charge_full_design"] =
            "/sys/class/power_supply/bms/charge_full_design"
        assertNull(capacityHealth(snapshot))
        snapshot.fieldSources["charge_full_design"] =
            "/sys/class/power_supply/battery/charge_full_design"
        assertEquals(75.0, capacityHealth(snapshot)!!, 0.0)
        snapshot.designChargeUah = 0
        assertNull(capacityHealth(snapshot))
    }
}
