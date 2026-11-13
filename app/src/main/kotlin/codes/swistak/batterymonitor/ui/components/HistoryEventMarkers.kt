/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.components

import androidx.compose.ui.unit.dp
import codes.swistak.batterymonitor.logs.HistoryEvent

internal val RebootMarkerRadius = 1.5.dp
internal val RebootMarkerSpacing = 7.dp

internal val ConnectedMarkerDash = 3.dp
internal val DisconnectedMarkerDash = 7.dp
internal val PowerMarkerGap = 4.dp

internal fun historyMarkerTimes(events: List<HistoryEvent>): Map<Int, List<Long>> =
    events.groupBy { it.code }.mapValues { (_, sameType) ->
        sameType.flatMap { event ->
            if (event.first == event.last) listOf(event.first) else listOf(event.first, event.last)
        }.sorted()
    }

internal inline fun forEachRebootDot(
    top: Float, bottom: Float, radius: Float, spacing: Float, draw: (y: Float) -> Unit
) {
    var y = top + radius
    while (y <= bottom - radius) {
        draw(y)
        y += spacing
    }
}

private val markerDrawOrder =
    intArrayOf(HistoryEvent.REBOOT, HistoryEvent.CONNECTED, HistoryEvent.DISCONNECTED)

internal fun forEachHistoryEventMarker(
    sortedTimesByType: Map<Int, List<Long>>,
    left: Float,
    right: Float,
    rebootRadius: Float,
    powerStrokeWidth: Float,
    screenX: (time: Long) -> Float,
    draw: (code: Int, x: Float) -> Unit
) {
    val rebootColumns = mutableListOf<Float>()
    val overlapDistance = rebootRadius + powerStrokeWidth / 2
    fun overlapsReboot(x: Float): Boolean {
        val index = rebootColumns.binarySearch(x)
        if (index >= 0) return true
        val next = -index - 1
        return rebootColumns.getOrNull(next)
            ?.let { it - x < overlapDistance } == true || rebootColumns.getOrNull(next - 1)
            ?.let { x - it < overlapDistance } == true
    }

    for (code in markerDrawOrder) {
        val times = sortedTimesByType[code] ?: continue
        val start = times.binarySearch { screenX(it).compareTo(left) }
        val isReboot = code == HistoryEvent.REBOOT
        val separation = if (isReboot) 2 * rebootRadius else powerStrokeWidth
        var previousX = Float.NEGATIVE_INFINITY
        for (index in (if (start < 0) -start - 1 else start) until times.size) {
            val x = screenX(times[index])
            if (x > right) break
            if (isReboot) rebootColumns += x
            if (x - previousX < separation || !isReboot && overlapsReboot(x)) continue
            draw(code, x)
            previousX = x
        }
    }
}
