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

package codes.swistak.batterymonitor;

import android.app.Activity;
import android.os.Build;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

final class EdgeToEdgeHelper {
    private EdgeToEdgeHelper() {}

    static void applyIfNeeded(Activity activity) {
        if (Build.VERSION.SDK_INT < 35) return;

        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);

        final View root = activity.findViewById(android.R.id.content);
        if (root == null) return;

        final int initialLeft = root.getPaddingLeft();
        final int initialTop = root.getPaddingTop();
        final int initialRight = root.getPaddingRight();
        final int initialBottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());

            int topPadding = initialTop + bars.top;
            int bottomPadding = initialBottom + bars.bottom;

            v.setPadding(initialLeft + bars.left, topPadding, initialRight + bars.right, bottomPadding);
            return windowInsets;
        });

        ViewCompat.requestApplyInsets(root);
    }
}


