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

import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import codes.swistak.batterymonitor.diagnostics.DiagnosticsDurationFormatter
import codes.swistak.batterymonitor.monitoring.PredictorRecentState
import codes.swistak.batterymonitor.monitoring.PredictorStoredState
import java.util.Locale

internal data class DiagnosticsFormat(
    val multiplier: Int, val fahrenheit: Boolean, val locale: Locale
)

internal class DiagnosticsProjectionCache {
    private var snapshots: List<AdvancedBatterySnapshot>? = null
    private var format: DiagnosticsFormat? = null
    private var normalized: List<DiagnosticGroup>? = null
    private var battery: List<DiagnosticGroup>? = null
    private var charging: List<DiagnosticGroup>? = null
    private var raw: List<DiagnosticGroup>? = null

    @Synchronized
    fun groups(
        sources: List<AdvancedBatterySnapshot>, display: DiagnosticsFormat, tab: Int
    ): List<DiagnosticGroup> {
        require(tab in 0..3)
        if (tab == 0) return emptyList()
        if (snapshots != sources) {
            snapshots = sources.toList()
            normalized = null
            battery = null
            charging = null
            raw = null
        }
        if (tab == 3) return raw ?: mergedRawDiagnosticGroups(sources).also { raw = it }
        if (format != display) {
            format = display
            normalized = null
            battery = null
            charging = null
        }
        val merged = normalized ?: mergedDiagnosticGroups(
            sources, display.multiplier, display.fahrenheit, display.locale
        ).also { normalized = it }
        return if (tab == 1) {
            battery ?: (merged.take(2) + deviceDiagnosticGroups(
                sources, charging = false, locale = display.locale
            )).also { battery = it }
        } else {
            charging ?: (merged.drop(2) + deviceDiagnosticGroups(
                sources, charging = true, locale = display.locale
            )).also { charging = it }
        }
    }
}

internal data class PredictorDiagnosticsProjection(
    val stored: DiagnosticGroup? = null,
    val recent: DiagnosticGroup? = null,
    val combined: DiagnosticGroup? = null,
    val raw: DiagnosticGroup? = null
)

internal fun preparePredictorDiagnostics(
    stored: PredictorStoredState?,
    recent: PredictorRecentState?,
    observedAtMillis: Long,
    locale: Locale
): PredictorDiagnosticsProjection {
    val duration: (Long) -> String = { DiagnosticsDurationFormatter.format(locale, it) }
    val saved = stored?.let { predictorDiagnosticGroup(it, observedAtMillis, locale, duration) }
    val latest =
        recent?.let { recentPredictorDiagnosticGroup(it, observedAtMillis, locale, duration) }
    val combined = combinedPredictorDiagnosticGroup(saved, latest)
    return PredictorDiagnosticsProjection(
        saved,
        latest,
        combined,
        combined?.let(::rawPredictorDiagnosticGroup)?.takeIf { it.rows.isNotEmpty() })
}
