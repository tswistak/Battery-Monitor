package com.darshancomputing.BatteryIndicatorPro;

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



