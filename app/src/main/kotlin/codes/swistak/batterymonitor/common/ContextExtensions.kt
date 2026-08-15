/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.common

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes

internal fun Context.showToast(
    message: CharSequence, duration: Int = Toast.LENGTH_LONG
) {
    Toast.makeText(this, message, duration).show()
}

internal fun Context.showToast(
    @StringRes message: Int, duration: Int = Toast.LENGTH_LONG
) {
    Toast.makeText(this, message, duration).show()
}
