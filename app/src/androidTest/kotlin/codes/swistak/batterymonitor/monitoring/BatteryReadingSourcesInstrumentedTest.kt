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

import android.os.Bundle
import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringSnapshot
import codes.swistak.batterymonitor.monitoring.presentation.currentSampleFromBundle
import codes.swistak.batterymonitor.monitoring.presentation.toBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            fieldMeasurementTimes = mapOf(
                "temperature" to BatteryMeasurementTime(1_000, 100),
                "voltage" to BatteryMeasurementTime(1_100, 200),
                "health" to BatteryMeasurementTime(1_200, 300),
                "charge_counter" to BatteryMeasurementTime(1_300, 400)
            )
        }
        val bundle = info.toBundle()
        val loaded = BatteryInfo().apply { loadBundle(bundle) }
        val snapshot = MonitoringSnapshot.fromBundle(bundle)

        assertEquals(info.fieldSources, loaded.fieldSources)
        assertEquals(info.fieldSources, snapshot.fieldSources)
        assertEquals(info.fieldMeasurementTimes, loaded.fieldMeasurementTimes)
        assertEquals(info.fieldMeasurementTimes, snapshot.fieldMeasurementTimes)
        assertEquals(info.temperature, snapshot.temperatureTenthsCelsius)
        assertEquals(info.voltage, snapshot.voltageMillivolts)
        assertEquals(info.health, snapshot.health)
        assertEquals(info.remainingChargeUah, snapshot.remainingChargeMicroampHours)
    }

    @Test
    fun legacy_bundles_do_not_inherit_sources_from_an_earlier_reading() {
        val info = BatteryInfo().apply {
            fieldSources = mapOf("temperature" to "ACTION_BATTERY_CHANGED / temperature")
            fieldMeasurementTimes = mapOf("temperature" to BatteryMeasurementTime(1_000, 100))
        }

        info.loadBundle(Bundle())

        assertTrue(info.fieldSources.isEmpty())
        assertTrue(info.fieldMeasurementTimes.isEmpty())
        assertTrue(MonitoringSnapshot.fromBundle(Bundle()).fieldSources.isEmpty())
        assertTrue(MonitoringSnapshot.fromBundle(Bundle()).fieldMeasurementTimes.isEmpty())
    }

    @Test
    fun privileged_source_acquisition_times_round_trip_with_exact_source_keys() {
        val snapshot = AdvancedBatterySnapshot().apply {
            capturedAtMillis = 9_000
            currentNowUa = -420_000
            currentAverageUa = -390_000
            fieldSources["current_now"] = "/sys/class/power_supply/vendor-pack/current_now"
            fieldSources["current_average"] = "BatteryManager / BATTERY_PROPERTY_CURRENT_AVERAGE"
            sourceReadTimes["/sys/class/power_supply/vendor-pack/current_now"] =
                BatteryMeasurementTime(1_000, 100)
            sourceReadTimes["BatteryManager / BATTERY_PROPERTY_CURRENT_AVERAGE"] =
                BatteryMeasurementTime(900)
        }

        val loaded = AdvancedBatterySnapshot.fromBundle(snapshot.toBundle())
        val reading = projectPrivilegedBatteryReading(loaded)

        assertEquals(snapshot.capturedAtMillis, loaded.capturedAtMillis)
        assertEquals(snapshot.fieldSources, loaded.fieldSources)
        assertEquals(snapshot.sourceReadTimes, loaded.sourceReadTimes)
        assertEquals(
            BatteryMeasurementTime(1_000, 100), reading.fieldMeasurementTimes["current_now"]
        )
        assertEquals(BatteryMeasurementTime(900), reading.fieldMeasurementTimes["current_average"])
    }

    @Test
    fun legacy_privileged_bundle_keeps_acquisition_metadata_unknown() {
        val snapshot = AdvancedBatterySnapshot().apply {
            capturedAtMillis = 9_000
            currentNowUa = -420_000
            fieldSources["current_now"] = "cmd battery get current_now"
        }
        val bundle = snapshot.toBundle().apply { remove("source_read_times") }

        val loaded = AdvancedBatterySnapshot.fromBundle(bundle)

        assertEquals(-420_000L, loaded.currentNowUa)
        assertTrue(loaded.sourceReadTimes.isEmpty())
        assertTrue(projectPrivilegedBatteryReading(loaded).fieldMeasurementTimes.isEmpty())
    }

    @Test
    fun current_payload_preserves_raw_precision_sources_and_acquisition_times() {
        val sample = CurrentSample(
            BatteryCurrentReading(
                -420_001,
                "/sys/class/power_supply/bms/current_now",
                BatteryMeasurementTime(1_000, 100)
            ), BatteryCurrentReading(
                0,
                "BatteryManager / BATTERY_PROPERTY_CURRENT_AVERAGE",
                BatteryMeasurementTime(900, 90)
            )
        )

        assertEquals(sample, currentSampleFromBundle(sample.toBundle()))
    }

    @Test
    fun legacy_current_payload_keeps_missing_time_and_source_unknown() {
        val bundle = Bundle().apply {
            putBundle("current_now", Bundle().apply { putLong("microamps", -420_001) })
        }

        val sample = currentSampleFromBundle(bundle)

        assertEquals(BatteryCurrentReading(-420_001, null), sample.now)
        assertNull(sample.average)
        assertNull(sample.now?.acquiredAt)
    }

    @Test
    fun metadata_only_current_payload_cannot_invent_zero_current() {
        val bundle = Bundle().apply {
            for (key in listOf("current_now", "current_average")) {
                putBundle(key, Bundle().apply {
                    putString("source", "cmd battery get $key")
                    putBundle("acquired_at", BatteryMeasurementTime(1_000, 100).toBundle())
                })
            }
        }

        assertEquals(CurrentSample(null, null), currentSampleFromBundle(bundle))
        assertEquals(CurrentSample(null, null), currentSampleFromBundle(Bundle()))
    }

    @Test
    fun unsupported_current_sentinels_are_rejected_without_discarding_a_valid_zero_average() {
        for (invalid in listOf(Int.MIN_VALUE.toLong(), Long.MIN_VALUE)) {
            val bundle = Bundle().apply {
                putBundle("current_now", Bundle().apply { putLong("microamps", invalid) })
                putBundle("current_average", Bundle().apply { putLong("microamps", 0) })
            }

            val sample = currentSampleFromBundle(bundle)

            assertNull(sample.now)
            assertEquals(BatteryCurrentReading(0, null), sample.average)
        }
    }
}
