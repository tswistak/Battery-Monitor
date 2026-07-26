package codes.swistak.batterymonitor

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

internal object SettingsBackup {
    const val SCHEMA_VERSION: Int = 1

    private val SCHEMA: MutableMap<String?, Class<*>?> = HashMap()

    init {
        SCHEMA[SettingsKeys.KEY_ENABLE_LOGGING] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_MAX_LOG_AGE] = String::class.java
        SCHEMA[SettingsKeys.KEY_ICON_CONTENT] = String::class.java
        SCHEMA[SettingsKeys.KEY_SHOW_ICON_UNIT] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_CONVERT_F] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_NOTIFY_STATUS_DURATION] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_AUTOSTART] = String::class.java
        SCHEMA[SettingsKeys.KEY_PREDICTION_TYPE] = String::class.java
        SCHEMA[SettingsKeys.KEY_STATUS_DUR_EST] = String::class.java
        SCHEMA[SettingsKeys.KEY_INDICATE_CHARGING] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_CHIP_CONTENT] = String::class.java
        SCHEMA[SettingsKeys.KEY_CHIP_SWITCHING_INTERVAL] = String::class.java
        SCHEMA[SettingsKeys.KEY_CHIP_INDICATE_CHARGING] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_LIVE_UPDATE_DISPLAY] = String::class.java
        SCHEMA[SettingsKeys.KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_RED] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_RED_THRESH] = String::class.java
        SCHEMA[SettingsKeys.KEY_AMBER] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_AMBER_THRESH] = String::class.java
        SCHEMA[SettingsKeys.KEY_GREEN] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_GREEN_THRESH] = String::class.java
        SCHEMA[SettingsKeys.KEY_TOP_LINE] = String::class.java
        SCHEMA[SettingsKeys.KEY_BOTTOM_LINE] = String::class.java
        SCHEMA[SettingsKeys.KEY_TIME_REMAINING_VERBOSITY] = String::class.java
        SCHEMA[SettingsKeys.KEY_STATUS_DURATION_IN_VITAL_SIGNS] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_ENABLE_CURRENT_HACK] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_CURRENT_HACK_PREFER_FS] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_CURRENT_HACK_MULTIPLIER] = String::class.java
        SCHEMA[SettingsKeys.KEY_DISPLAY_CURRENT_IN_VITAL_STATS] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW] = Boolean::class.java
        SCHEMA[SettingsKeys.KEY_UI_COLOR] = String::class.java
        SCHEMA[SettingsKeys.KEY_ENABLE_ADVANCED_STATS] = Boolean::class.java
    }

    @Throws(JSONException::class)
    fun getSchemaVersion(jsonString: String): Int {
        return JSONObject(jsonString).optInt("version", 0)
    }

    @Throws(JSONException::class)
    fun exportToJson(prefs: SharedPreferences): JSONObject {
        val settings = JSONObject()
        for (entry in prefs.all.entries) {
            if (SCHEMA.containsKey(entry.key)) settings.put(entry.key, entry.value)
        }

        val root = JSONObject()
        root.put("version", SCHEMA_VERSION)
        root.put("settings", settings)
        return root
    }

    @Throws(JSONException::class, IllegalArgumentException::class)
    fun importFromJson(editor: SharedPreferences.Editor, jsonString: String) {
        val root = JSONObject(jsonString)
        val settings = root.optJSONObject("settings") ?: return

        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!SCHEMA.containsKey(key)) continue

            val expectedType = SCHEMA.get(key)
            val value = settings.get(key)

            if (expectedType == Boolean::class.java) {
                require(value is Boolean) { "Invalid type for '$key': expected boolean" }
                editor.putBoolean(key, value)
            } else if (expectedType == String::class.java) {
                require(value is String) { "Invalid type for '$key': expected string" }
                editor.putString(key, value)
            }
        }
    }

    @Throws(IOException::class)
    fun writeToUri(context: Context, uri: Uri, json: JSONObject) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "w") ?: return
        pfd.use { pfd ->
            val fos = FileOutputStream(pfd.fileDescriptor)
            fos.write(json.toString().toByteArray(StandardCharsets.UTF_8))
            fos.close()
        }
    }

    @Throws(IOException::class)
    fun readFromUri(context: Context, uri: Uri): String? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        pfd.use { pfd ->
            val reader = BufferedReader(
                InputStreamReader(FileInputStream(pfd.fileDescriptor), StandardCharsets.UTF_8)
            )
            val sb = StringBuilder()
            var line: String?
            while ((reader.readLine().also { line = it }) != null) sb.append(line).append('\n')
            reader.close()
            return sb.toString()
        }
    }
}
