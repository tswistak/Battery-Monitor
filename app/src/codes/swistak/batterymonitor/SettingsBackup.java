package codes.swistak.batterymonitor;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

class SettingsBackup {
    static final int SCHEMA_VERSION = 1;

    private static final Map<String, Class<?>> SCHEMA = new HashMap<>();
    static {
        SCHEMA.put(SettingsKeys.KEY_ENABLE_LOGGING, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_MAX_LOG_AGE, String.class);
        SCHEMA.put(SettingsKeys.KEY_ICON_CONTENT, String.class);
        SCHEMA.put(SettingsKeys.KEY_SHOW_ICON_UNIT, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_CONVERT_F, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_NOTIFY_STATUS_DURATION, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_AUTOSTART, String.class);
        SCHEMA.put(SettingsKeys.KEY_PREDICTION_TYPE, String.class);
        SCHEMA.put(SettingsKeys.KEY_STATUS_DUR_EST, String.class);
        SCHEMA.put(SettingsKeys.KEY_INDICATE_CHARGING, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_CHIP_CONTENT, String.class);
        SCHEMA.put(SettingsKeys.KEY_CHIP_SWITCHING_INTERVAL, String.class);
        SCHEMA.put(SettingsKeys.KEY_CHIP_INDICATE_CHARGING, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_LIVE_UPDATE_DISPLAY, String.class);
        SCHEMA.put(SettingsKeys.KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_RED, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_RED_THRESH, String.class);
        SCHEMA.put(SettingsKeys.KEY_AMBER, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_AMBER_THRESH, String.class);
        SCHEMA.put(SettingsKeys.KEY_GREEN, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_GREEN_THRESH, String.class);
        SCHEMA.put(SettingsKeys.KEY_TOP_LINE, String.class);
        SCHEMA.put(SettingsKeys.KEY_BOTTOM_LINE, String.class);
        SCHEMA.put(SettingsKeys.KEY_TIME_REMAINING_VERBOSITY, String.class);
        SCHEMA.put(SettingsKeys.KEY_STATUS_DURATION_IN_VITAL_SIGNS, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_ENABLE_CURRENT_HACK, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_CURRENT_HACK_PREFER_FS, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_CURRENT_HACK_MULTIPLIER, String.class);
        SCHEMA.put(SettingsKeys.KEY_DISPLAY_CURRENT_IN_VITAL_STATS, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW, Boolean.class);
        SCHEMA.put(SettingsKeys.KEY_UI_COLOR, String.class);
        SCHEMA.put(SettingsKeys.KEY_ENABLE_ADVANCED_STATS, Boolean.class);
    }

    static int getSchemaVersion(String jsonString) throws JSONException {
        return new JSONObject(jsonString).optInt("version", 0);
    }

    static JSONObject exportToJson(SharedPreferences prefs) throws JSONException {
        JSONObject settings = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (SCHEMA.containsKey(entry.getKey()))
                settings.put(entry.getKey(), entry.getValue());
        }

        JSONObject root = new JSONObject();
        root.put("version", SCHEMA_VERSION);
        root.put("settings", settings);
        return root;
    }

    static void importFromJson(SharedPreferences.Editor editor, String jsonString)
            throws JSONException, IllegalArgumentException {
        JSONObject root = new JSONObject(jsonString);
        JSONObject settings = root.optJSONObject("settings");
        if (settings == null)
            return;

        Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!SCHEMA.containsKey(key))
                continue;

            Class<?> expectedType = SCHEMA.get(key);
            Object value = settings.get(key);

            if (expectedType == Boolean.class) {
                if (!(value instanceof Boolean))
                    throw new IllegalArgumentException("Invalid type for '" + key + "': expected boolean");
                editor.putBoolean(key, (Boolean) value);
            } else if (expectedType == String.class) {
                if (!(value instanceof String))
                    throw new IllegalArgumentException("Invalid type for '" + key + "': expected string");
                editor.putString(key, (String) value);
            }
        }
    }

    static void writeToUri(Context context, Uri uri, JSONObject json) throws IOException {
        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "w");
        if (pfd == null) return;
        try {
            FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
            fos.write(json.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
        } finally {
            pfd.close();
        }
    }

    static String readFromUri(Context context, Uri uri) throws IOException {
        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) return null;
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(pfd.getFileDescriptor()), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
                sb.append(line).append('\n');
            reader.close();
            return sb.toString();
        } finally {
            pfd.close();
        }
    }
}
