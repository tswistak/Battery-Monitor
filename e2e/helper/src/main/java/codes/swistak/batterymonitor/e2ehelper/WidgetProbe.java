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

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class WidgetProbe extends Probe {
    private static final String APP = "codes.swistak.batterymonitor";
    private static final String[] LEGACY_PROVIDERS = {APP + ".widgets.BatteryInfoAppWidgetProvider", APP + ".widgets.FullAppWidgetProvider"};
    private static final String[] NEW_PROVIDERS = {APP + ".widgets.StatusWidgetProvider", APP + ".widgets.StatusWidgetProvider"};

    @Override
    protected void execute(String phase) throws Exception {
        boolean bind = "bind".equals(phase) || "bindNew".equals(phase);
        boolean newUi = "bindNew".equals(phase) || "verifyNew".equals(phase);
        boolean stopped = "verifyStopped".equals(phase);
        require(bind || "verify".equals(phase) || "verifyUpdated".equals(phase) || "verifyNew".equals(phase) || stopped, "Unknown widget phase: " + phase);
        WidgetHostActivity activity = (WidgetHostActivity) startActivitySync(new Intent(getTargetContext(), WidgetHostActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        if (bind) bind(activity, newUi ? NEW_PROVIDERS : LEGACY_PROVIDERS);
        // Verification never rebinds or requests a provider update: stale or broken upgrades must fail.
        verify(activity, newUi, stopped);
        screenshot(activity, phase);
        runOnMainSync(activity::finish);
    }

    private void bind(WidgetHostActivity activity, String[] providers) throws Exception {
        AppWidgetManager manager = AppWidgetManager.getInstance(activity);
        runOnMainSync(() -> activity.host.deleteHost());
        SharedPreferences.Editor state = activity.getSharedPreferences(WidgetHostActivity.STORE, 0).edit().clear();
        for (int slot = 0; slot < providers.length; slot++) {
            int id = activity.host.allocateAppWidgetId();
            Bundle options = new Bundle();
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 160);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
            require(manager.bindAppWidgetIdIfAllowed(id, new ComponentName(APP, providers[slot]), options), "Cannot bind widget " + providers[slot] + "; grant the helper appwidget bind permission on a dedicated emulator");
            state.putInt("id_" + slot, id).putString("provider_" + slot, providers[slot]);
        }
        require(state.commit(), "Cannot persist original widget IDs and provider components");
        runOnMainSync(() -> {
            activity.refresh();
            activity.host.startListening();
        });
    }

    private void verify(WidgetHostActivity activity, boolean newUi, boolean stopped) throws Exception {
        Context appContext = activity.createPackageContext(APP, 0);
        int levelId = appContext.getResources().getIdentifier(newUi ? "widget_level" : "level", "id", APP);
        require(levelId != 0, "Missing battery level view in the actual widget layout");
        Intent battery = activity.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        require(battery != null, "No emulator battery broadcast");
        int level = arguments.containsKey("expectedLevel") ? Integer.parseInt(arguments.getString("expectedLevel")) : battery.getIntExtra("level", -1);
        require(level >= 0 && level <= 100, "Invalid expected battery level: " + level);
        long deadline = SystemClock.elapsedRealtime() + 30000;
        String[] failure = new String[]{"Widget content was never rendered"};
        while (SystemClock.elapsedRealtime() < deadline) {
            CountDownLatch changed = new CountDownLatch(1);
            activity.changed = changed;
            boolean[] ready = new boolean[1];
            runOnMainSync(() -> {
                try {
                    JSONArray actual = new JSONArray();
                    SharedPreferences state = activity.getSharedPreferences(WidgetHostActivity.STORE, 0);
                    AppWidgetManager manager = AppWidgetManager.getInstance(activity);
                    require(activity.views.size() == 2, "Expected two visible hosted widget instances; actual=" + activity.views.keySet());
                    for (int slot = 0; slot < 2; slot++) {
                        int id = state.getInt("id_" + slot, -1);
                        String provider = state.getString("provider_" + slot, "");
                        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
                        require(info != null && provider.equals(info.provider.getClassName()) && APP.equals(info.provider.getPackageName()), "Widget " + id + " lost its original provider binding " + provider);
                        AppWidgetHostView hostView = activity.views.get(id);
                        require(hostView != null && hostView.getGlobalVisibleRect(new Rect()), "Widget " + id + " is not visible");
                        View levelView = hostView.findViewById(levelId);
                        require(levelView instanceof TextView, "Widget " + id + " has no rendered battery value");
                        String text = ((TextView) levelView).getText().toString();
                        if (stopped) {
                            require("—".equals(text), "Stopped widget " + id + " expected —; actual=" + text);
                            int rootId = appContext.getResources().getIdentifier("widget_layout", "id", APP);
                            int stoppedId = appContext.getResources().getIdentifier("current_monitor_disabled", "string", APP);
                            View root = hostView.findViewById(rootId);
                            require(root != null && appContext.getString(stoppedId).contentEquals(root.getContentDescription()), "Stopped widget " + id + " has no meaningful monitoring-disabled content description");
                        } else {
                            require(text.replaceAll("[^0-9]", "").equals(Integer.toString(level)), "Widget " + id + " expected battery=" + level + "; actual=" + text);
                        }
                        require(levelView.isShown(), "Widget " + id + " battery value is hidden");
                        actual.put(new JSONObject().put("id", id).put("provider", provider).put("level", text));
                    }
                    ready[0] = true;
                    failure[0] = actual.toString();
                } catch (Throwable error) {
                    failure[0] = error.toString();
                }
            });
            if (ready[0]) {
                report("widgets=" + failure[0]);
                return;
            }
            changed.await(Math.min(1000, Math.max(1, deadline - SystemClock.elapsedRealtime())), TimeUnit.MILLISECONDS);
        }
        throw new AssertionError("Timed out waiting for actual RemoteViews content: " + failure[0]);
    }

    private void screenshot(WidgetHostActivity activity, String phase) throws Exception {
        Bitmap[] captured = new Bitmap[1];
        runOnMainSync(() -> {
            View decor = activity.getWindow().getDecorView();
            require(decor.getWidth() > 0 && decor.getHeight() > 0, "Widget host has no drawable window");
            captured[0] = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(captured[0]));
        });
        Bitmap bitmap = captured[0];
        require(bitmap != null, "Cannot capture widget host screenshot");
        File directory = getTargetContext().getExternalFilesDir("diagnostics");
        require(directory != null && (directory.isDirectory() || directory.mkdirs()), "Cannot create screenshot directory");
        File file = new File(directory, "widgets-" + phase + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) {
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Cannot encode widget screenshot");
        } finally {
            bitmap.recycle();
        }
        report("screenshot=" + file.getAbsolutePath());
    }
}
