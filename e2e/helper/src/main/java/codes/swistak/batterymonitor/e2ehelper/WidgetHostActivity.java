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
package codes.swistak.batterymonitor.e2ehelper;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * A real AppWidgetHost keeps the same OS-owned bindings across replacement of the provider APK.
 */
public final class WidgetHostActivity extends Activity {
    static final int HOST_ID = 0xB117;
    static final String STORE = "widget_host_instances";
    final Map<Integer, AppWidgetHostView> views = new LinkedHashMap<>();
    volatile CountDownLatch changed = new CountDownLatch(1);
    AppWidgetHost host;
    private LinearLayout content;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        setContentView(content);
        host = new AppWidgetHost(this, HOST_ID) {
            @Override
            protected AppWidgetHostView onCreateView(Context context, int id, AppWidgetProviderInfo info) {
                return new AppWidgetHostView(context) {
                    @Override
                    public void updateAppWidget(RemoteViews remoteViews) {
                        super.updateAppWidget(remoteViews);
                        changed.countDown();
                    }
                };
            }
        };
        refresh();
    }

    @Override
    public void onStart() {
        super.onStart();
        host.startListening();
    }

    @Override
    public void onStop() {
        host.stopListening();
        super.onStop();
    }

    // This E2E-only screen displays widget IDs and provider class names as diagnostics.
    @SuppressLint("SetTextI18n")
    void refresh() {
        content.removeAllViews();
        views.clear();
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        for (int slot = 0; slot < 2; slot++) {
            int id = getSharedPreferences(STORE, 0).getInt("id_" + slot, -1);
            if (id <= 0) continue;
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
            TextView label = new TextView(this);
            label.setText("Widget " + id + ": " + (info == null ? "MISSING PROVIDER" : info.provider.getClassName()));
            content.addView(label);
            if (info == null) continue;
            AppWidgetHostView view = host.createView(this, id, info);
            view.setAppWidget(id, info);
            int height = Math.round(160 * getResources().getDisplayMetrics().density);
            content.addView(view, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height));
            views.put(id, view);
        }
    }
}
