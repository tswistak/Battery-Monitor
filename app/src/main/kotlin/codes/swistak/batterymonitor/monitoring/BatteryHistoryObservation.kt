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
package codes.swistak.batterymonitor.monitoring

import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.monitoring.batteryvoltage.BatteryVoltageValidator

internal fun batteryHistoryObservation(
    fields: Map<String, Int>, time: Long, current: Long?, remainingCharge: Long?, percent: Int
): LogRecord {
    val plugged =
        fields["plugged"]?.takeIf { it in setOf(0, 1, 2, 4) } ?: BatteryInfo.PLUGGED_UNKNOWN
    val status = if (plugged == 0) BatteryInfo.STATUS_UNPLUGGED
    else fields["status"]?.takeIf { it in 1..5 } ?: BatteryInfo.STATUS_UNKNOWN
    val level = fields["level"]
    val scale = fields["scale"]
    return LogRecord(
        status = LogDatabase.encodeStatus(status, plugged, LogDatabase.STATUS_NEW),
        charge = percent.takeIf { level != null && scale != null && scale > 0 && level in 0..scale },
        time = time,
        temperature = fields["temperature"]?.takeIf { it in -1000..2000 },
        voltage = fields["voltage"]?.takeIf(BatteryVoltageValidator::isValidBroadcastMillivolts),
        currentMicroAmps = current?.takeIf(BatteryCurrent::isValidMicroAmps),
        remainingChargeMicroampHours = remainingCharge?.takeIf { it >= 0 })
}
