package codes.swistak.batterymonitor;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;

class SettingsBackup {
    static final int SCHEMA_VERSION = 1;

    private static boolean isInternalKey(String key) {
        return key.equals("first_run") || key.equals("service_desired_migrated_to_sp_main");
    }

    static JSONObject exportToJson(SharedPreferences prefs) throws JSONException {
        JSONObject settings = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!isInternalKey(entry.getKey()))
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

        if (!root.has("version"))
            throw new IllegalArgumentException("backup_invalid");
        int version = root.getInt("version");
        if (version > SCHEMA_VERSION)
            throw new IllegalArgumentException("backup_too_new");

        JSONObject settings = root.getJSONObject("settings");
        Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (isInternalKey(key))
                continue;

            Object value = settings.get(key);
            if (value instanceof Boolean)
                editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer)
                editor.putInt(key, (Integer) value);
            else if (value instanceof Long)
                editor.putLong(key, (Long) value);
            else if (value instanceof Float)
                editor.putFloat(key, (Float) value);
            else if (value instanceof Double)
                editor.putFloat(key, ((Double) value).floatValue());
            else if (value instanceof String)
                editor.putString(key, (String) value);
        }
    }
}
