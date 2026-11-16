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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryMeasurementTimeTest {
    @Test
    fun `oldest input is selected by elapsed acquisition time even after wall clock rollback`() {
        val older = BatteryMeasurementTime(5_000, 100)
        val newer = BatteryMeasurementTime(4_000, 200)

        assertEquals(older, oldestMeasurementTime(older, newer))
        assertEquals(older, oldestMeasurementTime(newer, older))
    }

    @Test
    fun `equal elapsed acquisition times consistently preserve the first input`() {
        val first = BatteryMeasurementTime(5_000, 100)
        val second = BatteryMeasurementTime(4_000, 100)

        assertEquals(first, oldestMeasurementTime(first, second))
        assertEquals(second, oldestMeasurementTime(second, first))
    }

    @Test
    fun `missing acquisition or elapsed metadata leaves calculated measurement time unknown`() {
        val known = BatteryMeasurementTime(1_000, 100)
        val wallOnly = BatteryMeasurementTime(900)

        assertNull(oldestMeasurementTime(null, known))
        assertNull(oldestMeasurementTime(known, null))
        assertNull(oldestMeasurementTime(wallOnly, known))
        assertNull(oldestMeasurementTime(known, wallOnly))
        assertNull(oldestMeasurementTime(wallOnly, wallOnly))
    }

    @Test
    fun `invalid acquisition coordinates leave calculated measurement time unknown`() {
        val known = BatteryMeasurementTime(1_000, 100)
        for (invalid in listOf(
            BatteryMeasurementTime(0, 50),
            BatteryMeasurementTime(-1, 50),
            BatteryMeasurementTime(900, -1)
        )) {
            assertNull(oldestMeasurementTime(invalid, known))
            assertNull(oldestMeasurementTime(known, invalid))
        }
    }

    @Test
    fun `zero elapsed time is a valid acquisition at device startup`() {
        val startup = BatteryMeasurementTime(900, 0)
        val later = BatteryMeasurementTime(1_000, 100)

        assertEquals(startup, oldestMeasurementTime(startup, later))
        assertEquals(startup, oldestMeasurementTime(later, startup))
    }
}
