/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.widgets

internal class WidgetUpdatePolicy {
    private var previous: WidgetSnapshot? = null
    private var lastPublish = Long.MIN_VALUE

    fun shouldPublish(
        snapshot: WidgetSnapshot, elapsedMillis: Long, force: Boolean = false
    ): Boolean {
        val old = previous
        val important =
            old == null || old.levelPercent != snapshot.levelPercent || old.status != snapshot.status || old.predictionTarget != snapshot.predictionTarget || old.targetReached != snapshot.targetReached || old.monitoringEnabled != snapshot.monitoringEnabled
        previous = snapshot
        if (!force && !important && elapsedMillis >= lastPublish && elapsedMillis - lastPublish < 60_000L) return false
        lastPublish = elapsedMillis
        return true
    }
}
