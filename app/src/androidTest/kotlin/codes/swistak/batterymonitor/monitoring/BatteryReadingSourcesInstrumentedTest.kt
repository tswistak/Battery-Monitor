/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring

import android.os.Bundle
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryReadingSourcesInstrumentedTest {
    @Test
    fun battery_sources_survive_the_service_bundle_and_presentation_snapshot() {
        val info = BatteryInfo().apply {
            temperature = 280
            voltage = 3900
            health = BatteryInfo.HEALTH_GOOD
            remainingChargeUah = 1_500_000
            fieldSources = mapOf(
                "temperature" to "ACTION_BATTERY_CHANGED / temperature",
                "voltage" to "/sys/class/power_supply/bms/voltage_now",
                "health" to "dumpsys battery / health",
                "charge_counter" to "BatteryManager / BATTERY_PROPERTY_CHARGE_COUNTER"
            )
        }
        val bundle = info.toBundle()
        val loaded = BatteryInfo().apply { loadBundle(bundle) }
        val snapshot = MonitoringSnapshot.fromBundle(bundle)

        assertEquals(info.fieldSources, loaded.fieldSources)
        assertEquals(info.fieldSources, snapshot.fieldSources)
        assertEquals(info.temperature, snapshot.temperatureTenthsCelsius)
        assertEquals(info.voltage, snapshot.voltageMillivolts)
        assertEquals(info.health, snapshot.health)
        assertEquals(info.remainingChargeUah, snapshot.remainingChargeMicroampHours)
    }

    @Test
    fun legacy_bundles_do_not_inherit_sources_from_an_earlier_reading() {
        val info = BatteryInfo().apply {
            fieldSources = mapOf("temperature" to "ACTION_BATTERY_CHANGED / temperature")
        }

        info.loadBundle(Bundle())

        assertTrue(info.fieldSources.isEmpty())
        assertTrue(MonitoringSnapshot.fromBundle(Bundle()).fieldSources.isEmpty())
    }
}
