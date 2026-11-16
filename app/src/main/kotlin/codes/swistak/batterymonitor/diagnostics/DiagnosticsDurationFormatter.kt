/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.diagnostics

import android.content.Context
import codes.swistak.batterymonitor.common.DurationFormatter
import java.util.Locale

internal object DiagnosticsDurationFormatter {
    fun format(context: Context, elapsedMilliseconds: Long): String {
        return format(
            context.resources.configuration.locales[0], elapsedMilliseconds, context::getString
        )
    }

    fun format(
        locale: Locale, elapsedMilliseconds: Long, unitPattern: (Int) -> String
    ): String = DurationFormatter.formatCompact(locale, elapsedMilliseconds, unitPattern)
}
