package codes.swistak.batterymonitor.diagnostics

import android.os.Bundle
import android.os.Parcel
import androidx.compose.runtime.saveable.SaverScope
import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import codes.swistak.batterymonitor.monitoring.BatteryMeasurementTime
import codes.swistak.batterymonitor.ui.diagnostics.DiagnosticRowSaver
import codes.swistak.batterymonitor.ui.diagnostics.normalizedDiagnosticGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsAttemptInstrumentedTest {
    @Test
    fun snapshotIpcAndDialogRecreationKeepMeasurementAndFailedAttemptSeparate() {
        val old = AdvancedBatterySnapshot().apply {
            currentAverageUa = -250000
            fieldSources["current_average"] = "BatteryManager / BATTERY_PROPERTY_CURRENT_AVERAGE"
            sourceReadTimes[fieldSources.getValue("current_average")] =
                BatteryMeasurementTime(100, 10)
        }
        val failed = AdvancedBatterySnapshot().apply {
            recordCheck(
                "BatteryManager / BATTERY_PROPERTY_CURRENT_AVERAGE",
                listOf("current_average"),
                BatteryMeasurementTime(200, 20),
                true
            )
        }
        val retained = old.retainingWithChecksFrom(failed)
        val parcel = Parcel.obtain()
        val restored = try {
            parcel.writeBundle(retained.toBundle())
            parcel.setDataPosition(0)
            AdvancedBatterySnapshot.fromBundle(requireNotNull(parcel.readBundle(javaClass.classLoader)))
        } finally {
            parcel.recycle()
        }
        val row = normalizedDiagnosticGroups(restored, 1).flatMap { it.rows }
            .single { it.rawKey == "current_average" }
        assertEquals(100L, row.observedAtMillis)
        assertEquals(10L, row.observedElapsedMillis)
        assertEquals(200L, row.checkedAtMillis)
        assertEquals(20L, row.checkedElapsedMillis)
        assertTrue(row.checkFailed)
        assertTrue(row.retained)
        val scope = SaverScope { true }
        val saved = with(DiagnosticRowSaver) { requireNotNull(scope.save(row)) }
        assertEquals(row, DiagnosticRowSaver.restore(saved))
        val legacy = Bundle(saved).apply {
            remove("checkedAtMillis")
            remove("checkedElapsedMillis")
            remove("checkFailed")
            remove("retained")
        }
        assertEquals(0L, requireNotNull(DiagnosticRowSaver.restore(legacy)).checkedAtMillis)
    }
}
