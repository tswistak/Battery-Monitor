/*
    Copyright (c) 2026 Tomasz Świstak

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.common

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

internal object EdgeToEdgeHelper {
    fun applyIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < 35) return

        WindowCompat.setDecorFitsSystemWindows(activity.window, false)

        val root = activity.findViewById<View?>(android.R.id.content) ?: return

        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(
            root
        ) { v: View?, windowInsets: WindowInsetsCompat? ->
            val bars = windowInsets!!.getInsets(WindowInsetsCompat.Type.systemBars())
            val topPadding = initialTop + bars.top
            val bottomPadding = initialBottom + bars.bottom

            v!!.setPadding(
                initialLeft + bars.left,
                topPadding,
                initialRight + bars.right,
                bottomPadding
            )
            windowInsets
        }

        ViewCompat.requestApplyInsets(root)
    }
}
