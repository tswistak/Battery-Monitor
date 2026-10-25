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
package codes.swistak.batterymonitor.ui.diagnostics

import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import codes.swistak.batterymonitor.monitoring.BatteryMeasurementTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DiagnosticsProjectionCacheTest {
    private val defaults = DiagnosticsFormat(1, false, Locale.US)

    @Test
    fun `raw sections merge all collector fields once using each fields latest acquisition`() {
        val basic = snapshot(-900, 10).apply {
            metadataLabels.addAll(
                listOf(
                    "BATTERY_PROPERTY_CURRENT_NOW", "computeChargeTimeRemaining"
                )
            )
            metadataValues.addAll(listOf("-900", "-1"))
            sourceReadTimes["BatteryManager / computeChargeTimeRemaining"] =
                BatteryMeasurementTime(10, 10)
        }
        val app = snapshot(-1200, 20).apply {
            accessMethod = "App UID"
            metadataLabels.addAll(
                listOf(
                    "BATTERY_PROPERTY_CURRENT_NOW", "BATTERY_PROPERTY_CHARGING_POLICY"
                )
            )
            metadataValues.addAll(listOf("  -1200\n", Int.MIN_VALUE.toString()))
            sourceReadTimes["BatteryManager / BATTERY_PROPERTY_CHARGING_POLICY"] =
                BatteryMeasurementTime(20, 20)
        }
        val root = snapshot(-1500, 15).apply {
            accessMethod = AdvancedBatterySnapshot.ACCESS_ROOT
            metadataLabels.add("BATTERY_PROPERTY_CURRENT_NOW")
            metadataValues.add("-1500")
            serviceLabels.add("root_only")
            serviceValues.add("root value")
        }
        val sources = listOf(basic, app, root)
        val cache = DiagnosticsProjectionCache()
        val raw = cache.groups(sources, defaults, 3)
        assertEquals(3, raw.size)
        assertEquals(raw.size, raw.map { it.title }.distinct().size)
        raw.forEach { group ->
            assertEquals(group.rows.size, group.rows.map { it.rawKey }.distinct().size)
            assertEquals(
                sources.asSequence().flatMap(::rawDiagnosticGroups)
                .filter { it.title == group.title }.flatMap { it.rows }.map { it.rawKey }.toSet(),
                group.rows.map { it.rawKey }.toSet()
            )
        }
        val metadata = raw.single { it.title == R.string.advanced_section_metadata }
        assertEquals(3, metadata.rows.size)
        val current = row(listOf(metadata), "BATTERY_PROPERTY_CURRENT_NOW")
        assertEquals("  -1200\n", current.rawValue)
        assertEquals("App UID", current.accessMethod)
        assertEquals("BatteryManager / BATTERY_PROPERTY_CURRENT_NOW", current.source)
        assertEquals(20L, current.observedAtMillis)
        assertEquals(20L, current.observedElapsedMillis)
        assertEquals("-1", row(raw, "computeChargeTimeRemaining").rawValue)
        assertEquals(
            Int.MIN_VALUE.toString(), row(raw, "BATTERY_PROPERTY_CHARGING_POLICY").rawValue
        )
        assertEquals("-1200", row(raw, "battery/current_now").rawValue)
        assertEquals("root value", row(raw, "root_only").rawValue)
        assertEquals(listOf(current), filterRawGroups(raw, "PROPERTY_CURRENT_NOW").single().rows)
        val report = diagnosticReport(raw, { it.toString() }, true)
        assertEquals(
            1, report.lineSequence().count { it == R.string.advanced_section_metadata.toString() })
        assertEquals(
            1, report.lineSequence().count { it.startsWith("BATTERY_PROPERTY_CURRENT_NOW:") })
        assertSame(raw, cache.groups(sources, defaults, 3))
        assertTrue(cache.groups(emptyList(), defaults, 3).isEmpty())
    }

    @Test
    fun `raw searches and tab revisits reuse prepared sources`() {
        val cache = DiagnosticsProjectionCache()
        val sources = listOf(snapshot(-900, 10))
        val raw = cache.groups(sources, defaults, 3)
        for (query in listOf("b", "BATTERY", " current_now ", "missing")) {
            val filtered = filterRawGroups(raw, query)
            assertTrue(filtered.flatMap { it.rows }
                .all { it.rawKey.contains(query.trim(), ignoreCase = true) })
            assertSame(raw, cache.groups(sources, defaults, 3))
        }
        val battery = cache.groups(sources, defaults, 1)
        cache.groups(sources, defaults, 2)
        assertSame(battery, cache.groups(sources, defaults, 1))
        assertSame(raw, cache.groups(sources, defaults, 3))
        assertTrue(cache.groups(sources, defaults, 0).isEmpty())
    }

    @Test
    fun `locale units and multiplier invalidate displayed values without rebuilding raw sources`() {
        val cache = DiagnosticsProjectionCache()
        val sources = listOf(snapshot(-900, 10))
        val raw = cache.groups(sources, defaults, 3)
        val initial = cache.groups(sources, defaults, 1)
        assertEquals("25.0°C", row(initial, "temperature").value)
        assertEquals("4.125 V", row(initial, "voltage").value)
        assertEquals("-0.9 mA", row(initial, "current_now").value)
        val changed = defaults.copy(multiplier = 1000, fahrenheit = true, locale = Locale.GERMANY)
        val reformatted = cache.groups(sources, changed, 1)
        assertNotSame(initial, reformatted)
        assertEquals("77,0°F", row(reformatted, "temperature").value)
        assertEquals("4,125 V", row(reformatted, "voltage").value)
        assertEquals("-900 mA", row(reformatted, "current_now").value)
        assertEquals("-900", row(reformatted, "current_now").rawValue)
        assertEquals(10L, row(reformatted, "current_now").observedAtMillis)
        assertSame(raw, cache.groups(sources, changed, 3))
    }

    @Test
    fun `a replacement snapshot invalidates cached rows even with the same completion time`() {
        val cache = DiagnosticsProjectionCache()
        val initial = cache.groups(listOf(snapshot(-900, 10)), defaults, 1)
        val replaced = cache.groups(listOf(snapshot(-1200, 20)), defaults, 1)
        assertNotSame(initial, replaced)
        assertEquals("-1200", row(replaced, "current_now").rawValue)
        assertEquals(20L, row(replaced, "current_now").observedAtMillis)
        assertEquals(20L, row(replaced, "current_now").observedElapsedMillis)
    }

    private fun row(groups: List<DiagnosticGroup>, key: String) =
        groups.flatMap { it.rows }.single { it.rawKey == key }

    private fun snapshot(current: Long, time: Long) = AdvancedBatterySnapshot().apply {
        accessMethod = "Android"
        capturedAtMillis = 999
        currentNowUa = current
        fieldSources["current_now"] = "BatteryManager / BATTERY_PROPERTY_CURRENT_NOW"
        sourceReadTimes[fieldSources.getValue("current_now")] = BatteryMeasurementTime(time, time)
        serviceLabels.addAll(listOf("temperature", "voltage"))
        serviceValues.addAll(listOf("250", "4125"))
        sourceReadTimes["ACTION_BATTERY_CHANGED / temperature"] = BatteryMeasurementTime(time, time)
        sourceReadTimes["ACTION_BATTERY_CHANGED / voltage"] = BatteryMeasurementTime(time, time)
        sysfsLabels.add("battery/current_now")
        sysfsValues.add(current.toString())
        sourceReadTimes["/sys/class/power_supply/battery/current_now"] =
            BatteryMeasurementTime(time, time)
    }
}
