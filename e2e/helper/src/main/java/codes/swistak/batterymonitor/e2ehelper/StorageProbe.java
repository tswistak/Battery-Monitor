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

import android.appwidget.AppWidgetManager;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public final class StorageProbe extends Probe {
    private static final String APP = "codes.swistak.batterymonitor";
    private static final String SETTINGS = APP + "_preferences";
    private static final String PREFIX = "historical-1.7.3-";
    private static final String[] NEW_CONFIGS = {"1|Dark|BatteryBlue|fahrenheit|70|TEMPERATURE,VOLTAGE|TEMPERATURE|COMPACT|HISTORY|VOLTAGE|72", "1|Light|Dynamic|celsius|100|CURRENT,REMAINING_CHARGE|NONE|ADAPTIVE|CURRENT_STATE|CURRENT|168"};

    @Override
    protected void execute(String phase) throws Exception {
        Context context = getTargetContext();
        require(APP.equals(context.getPackageName()), "Storage probe target is not the released application ID");
        switch (phase) {
            case "seedRunning":
                seed(context, true);
                break;
            case "seedStopped":
                seed(context, false);
                break;
            case "exportFixtures":
                exportFixtures(context);
                break;
            case "capture":
                capture(context);
                break;
            case "verifyRunning":
                verify(context, true);
                break;
            case "verifyStopped":
                verify(context, false);
                break;
            case "seedNewUi":
                seedNewUi(context);
                break;
            case "verifyNewUi":
                verifyNewUi(context);
                break;
            case "seedBackupData":
                seedBackupData(context);
                break;
            case "seedSentinelData":
                seedSentinelData(context);
                break;
            case "readState":
                break;
            case "stopMonitoring":
                stopCurrentMonitoring(context);
                break;
            case "startMonitoringForAlarm":
                startMonitoringForAlarm(context);
                break;
            case "installAlarmFailure":
                alarmFailure(context, true);
                break;
            case "dropAlarmFailure":
                alarmFailure(context, false);
                break;
            default:
                throw new IllegalArgumentException("Unknown storage phase: " + phase);
        }
        result.putString("stateJson", snapshot(context).toString());
    }

    private void requireHistorical(Context context) throws Exception {
        String actual = context.getPackageManager().getPackageInfo(APP, 0).versionName;
        require("1.7.3".equals(actual), "Historical fixture producer must be actual 1.7.3; was " + actual);
    }

    private void seed(Context context, boolean running) throws Exception {
        requireHistorical(context);
        stopService(context);
        SharedPreferences preferences = context.getSharedPreferences(SETTINGS, 0);
        require(preferences.edit().putBoolean("convert_to_fahrenheit", true).remove("temperature_unit").putInt("_applied_settings_migration_version", 3).putBoolean("enable_logging", true).putString("max_log_age", "-1").putString("autostart", running ? "always" : "auto").putString("icon_content", "temperature").putBoolean("show_icon_unit", true).commit(), "Historical settings commit failed");
        require(context.getSharedPreferences("sp_store_main", 0).edit().putBoolean("first_run", false).putBoolean("serviceDesired", running).putBoolean("service_desired_migrated_to_sp_main", true).commit(), "Main state commit failed");
        require(context.getSharedPreferences("sp_store", 0).edit().putBoolean("serviceDesired", running).commit(), "Service state commit failed");
        Object alarms = construct(context, "alarms.AlarmDatabase");
        try {
            Object enabled = type(context, "alarms.AlarmRecord").getConstructor(boolean.class, String.class, String.class).newInstance(true, "charge_rises", "83");
            Object disabled = type(context, "alarms.AlarmRecord").getConstructor(boolean.class, String.class, String.class).newInstance(false, "charge_drops", "17");
            require(Boolean.TRUE.equals(invoke(alarms, "replaceAllAlarms", List.class, Arrays.asList(enabled, disabled))), "Historical alarm persistence failed");
        } finally {
            invoke(alarms, "close");
        }
        seedLogs(context);
        require(context.getSharedPreferences("predictor_sp_store", 0).edit().clear().putFloat("key_ave_recharge_ac", 123456f).putFloat("key_ave_recharge_wl", 234567f).putFloat("key_ave_recharge_usb", 345678f).putInt("key_predictor_state_version", 2).commit(), "Predictor state commit failed");
        report(snapshot(context).toString());
        // The service uses a separate process, just as in the historical production manifest.
        if (running) startService(context);
    }

    private void seedLogs(Context context) throws Exception {
        Object logs = construct(context, "logs.LogDatabase");
        try {
            Class<?> record = type(context, "logs.LogRecord");
            Object first = record.getConstructor(int.class, Integer.class, long.class, Integer.class, Integer.class).newInstance(3, 72, 1700000000000L, 287, 4011);
            Object second = record.getConstructor(int.class, Integer.class, long.class, Integer.class, Integer.class).newInstance(3, 71, 1700000060000L, 291, 4007);
            invoke(logs, "replaceAllLogs", List.class, Arrays.asList(first, second));
        } finally {
            invoke(logs, "close");
        }
    }

    private void exportFixtures(Context context) throws Exception {
        requireHistorical(context);
        boolean running = context.getSharedPreferences("sp_store_main", 0).getBoolean("serviceDesired", false);
        stopService(context);
        seedLogs(context);
        File directory = new File(context.getExternalFilesDir(null), "e2e-historical");
        require(directory.isDirectory() || directory.mkdirs(), "Cannot create historical fixture directory");
        Object settings = singleton(context, "settings.backup.SettingsBackup");
        write(directory, "settings.json", invoke(settings, "exportToJson", SharedPreferences.class, context.getSharedPreferences(SETTINGS, 0)).toString());
        Object alarms = construct(context, "alarms.AlarmDatabase");
        try {
            Object backup = singleton(context, "alarms.backup.AlarmBackup");
            write(directory, "alarms.json", invoke(backup, "exportToJson", alarms.getClass(), alarms).toString());
        } finally {
            invoke(alarms, "close");
        }
        Class<?> dataType = type(context, "devicebackup.DeviceDataType");
        HashSet<Object> selection = new HashSet<>(Arrays.asList(dataType.getEnumConstants()));
        Object deviceBackup = singleton(context, "devicebackup.DeviceDataBackup");
        write(directory, "device.json", invoke(deviceBackup, "exportToJson", new Class<?>[]{Context.class, java.util.Set.class}, context, selection).toString());
        File archive = new File(directory, PREFIX + "general.zip");
        invoke(singleton(context, "devicebackup.GeneralBackup"), "exportToUri", new Class<?>[]{Context.class, Uri.class, SharedPreferences.class}, context, Uri.fromFile(archive), context.getSharedPreferences(SETTINGS, 0));
        Object logs = construct(context, "logs.LogDatabase");
        try (FileOutputStream output = new FileOutputStream(new File(directory, PREFIX + "history.csv"))) {
            List<?> records = (List<?>) invoke(logs, "getAllLogRecords");
            invoke(singleton(context, "logs.LogExport"), "writeCsv", new Class<?>[]{Context.class, java.io.OutputStream.class, List.class, boolean.class}, context, output, records, true);
        } finally {
            invoke(logs, "close");
        }
        JSONObject hashes = new JSONObject();
        for (String name : new String[]{"settings.json", "alarms.json", "device.json", "general.zip", "history.csv"}) {
            File fixture = new File(directory, PREFIX + name);
            require(fixture.length() > 0, "Historical exporter produced empty " + name);
            hashes.put(fixture.getName(), hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(fixture.toPath()))));
        }
        JSONObject provenance = new JSONObject().put("producerVersion", "1.7.3").put("producerApkSha256", "eb5d370187c3699a9d4d74d1890e8226a165ed65f66a16e6a968aa5baf19aec7").put("producer", "Unmodified historical APK code; dedicated CI signature").put("sha256", hashes);
        write(directory, "provenance.json", provenance.toString(2));
        report("fixtureDirectory=" + directory.getAbsolutePath());
        report(provenance.toString());
        if (running) startService(context);
    }

    private void capture(Context context) throws Exception {
        try (SQLiteDatabase logs = SQLiteDatabase.openDatabase(context.getDatabasePath("logs.db").getPath(), null, SQLiteDatabase.OPEN_READONLY); Cursor row = logs.rawQuery("SELECT COALESCE(MAX(_id),0),COALESCE(MAX(time),0) FROM logs", null)) {
            require(row.moveToFirst(), "Could not capture history high-water mark");
            result.putLong("latestLogId", row.getLong(0));
            result.putLong("latestLogTime", row.getLong(1));
        }
        report(snapshot(context).toString());
    }

    private static void startService(Context context) {
        context.startForegroundService(new Intent().setComponent(new ComponentName(APP, APP + ".monitoring.BatteryInfoService")));
    }

    private static void stopService(Context context) {
        ComponentName service = new ComponentName(APP, APP + ".monitoring.BatteryInfoService");
        context.stopService(new Intent().setComponent(service));
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        long deadline = SystemClock.elapsedRealtime() + 10000;
        do {
            boolean found = false;
            // Instrumentation shares the target UID, whose own services remain visible for lifecycle checks.
            @SuppressWarnings("deprecation") List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(Integer.MAX_VALUE);
            for (ActivityManager.RunningServiceInfo info : services) {
                if (service.equals(info.service)) found = true;
            }
            if (!found) {
                // The stopped historical service process caches preferences independently of the probe.
                List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
                if (processes != null) {
                    for (ActivityManager.RunningAppProcessInfo process : processes) {
                        if ((APP + ".BIS").equals(process.processName) && process.uid == Process.myUid() && process.pid != Process.myPid())
                            Process.killProcess(process.pid);
                    }
                }
                return;
            }
            // Poll the actual remote service lifecycle; never race its final database write.
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Historical monitoring service did not stop before seeding/export");
    }

    private void verify(Context context, boolean running) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(SETTINGS, 0);
        require("fahrenheit".equals(preferences.getString("temperature_unit", null)), "Expected migrated temperature_unit=fahrenheit; actual=" + preferences.getAll());
        require("-1".equals(preferences.getString("max_log_age", null)), "History retention preference was reset");
        require(preferences.getBoolean("enable_logging", false), "History logging preference was reset");
        require("temperature".equals(preferences.getString("icon_content", null)), "Notification content preference was reset");
        require(preferences.getBoolean("show_icon_unit", false), "Notification unit preference was reset");
        require((running ? "always" : "auto").equals(preferences.getString("autostart", null)), "Autostart preference was reset");
        try (SQLiteDatabase alarms = SQLiteDatabase.openDatabase(context.getDatabasePath("alarms.db").getPath(), null, SQLiteDatabase.OPEN_READONLY); Cursor rows = alarms.rawQuery("SELECT enabled,type,threshold FROM alarms ORDER BY _id", null)) {
            require(rows.getCount() == 2, "Expected two preserved alarms; actual=" + rows.getCount());
            require(rows.moveToNext() && rows.getInt(0) == 1 && "charge_rises".equals(rows.getString(1)) && "83".equals(rows.getString(2)), "Enabled alarm was changed");
            require(rows.moveToNext() && rows.getInt(0) == 0 && "charge_drops".equals(rows.getString(1)) && "17".equals(rows.getString(2)), "Disabled alarm was changed");
        }
        try (SQLiteDatabase logs = SQLiteDatabase.openDatabase(context.getDatabasePath("logs.db").getPath(), null, SQLiteDatabase.OPEN_READONLY); Cursor rows = logs.rawQuery("SELECT status,charge,time,temperature,voltage FROM logs WHERE time IN (1700000000000,1700000060000) ORDER BY time", null)) {
            require(rows.getCount() == 2, "Expected both historical measurements; actual=" + rows.getCount());
            require(rows.moveToNext() && rows.getInt(0) == 3 && rows.getInt(1) == 72 && rows.getLong(2) == 1700000000000L && rows.getInt(3) == 287 && rows.getInt(4) == 4011, "First historical measurement changed");
            require(rows.moveToNext() && rows.getInt(0) == 3 && rows.getInt(1) == 71 && rows.getLong(2) == 1700000060000L && rows.getInt(3) == 291 && rows.getInt(4) == 4007, "Second historical measurement changed");
        }
        SharedPreferences predictor = context.getSharedPreferences("predictor_sp_store", 0);
        require(predictor.getFloat("key_ave_recharge_ac", -1) == 123456f && predictor.getFloat("key_ave_recharge_wl", -1) == 234567f && predictor.getFloat("key_ave_recharge_usb", -1) == 345678f, "Historical charging predictor values changed: " + predictor.getAll());
        require(context.getSharedPreferences("sp_store_main", 0).getBoolean("serviceDesired", !running) == running, "Explicit monitoring choice changed across upgrade");
        report(snapshot(context).toString());
    }

    private void seedNewUi(Context context) throws Exception {
        int[] ids = newWidgetIds(context);
        SharedPreferences.Editor editor = context.getSharedPreferences("widget_configuration", 0).edit();
        for (int index = 0; index < ids.length; index++)
            editor.putString("widget_config_v1_" + ids[index], NEW_CONFIGS[index]);
        require(editor.commit(), "Per-widget configuration seed failed");
        require(context.getSharedPreferences(SETTINGS, 0).edit().putString("ui_brightness", "Dark").putInt("custom_charging_target", 87).putInt("discharging_target", 13).commit(), "New UI settings seed failed");
        report("newUiWidgetIds=" + Arrays.toString(ids));
    }

    private void verifyNewUi(Context context) throws Exception {
        int[] ids = newWidgetIds(context);
        SharedPreferences store = context.getSharedPreferences("widget_configuration", 0);
        Object configurationStore = construct(context, "widgets.WidgetConfigurationStore");
        Class<?> familyType = type(context, "widgets.WidgetFamily");
        Object statusFamily = familyType.getField("STATUS").get(null);
        for (int index = 0; index < ids.length; index++) {
            String actual = store.getString("widget_config_v1_" + ids[index], null);
            // Schema 2 appends Y-axis visibility while preserving every schema 1 field.
            require(NEW_CONFIGS[index].equals(actual) || (NEW_CONFIGS[index].replaceFirst("^1\\|", "2|") + "|true").equals(actual), "Widget " + ids[index] + " configuration changed or swapped: " + actual);
            Object effective = invoke(configurationStore, "load", new Class<?>[]{int.class, familyType}, ids[index], statusFamily);
            require((index == 0 ? "Dark" : "Light").equals(String.valueOf(invoke(effective, "getBrightness"))) && (index == 0 ? "fahrenheit" : "celsius").equals(invoke(effective, "getTemperatureUnit")) && Integer.valueOf(index == 0 ? 70 : 100).equals(invoke(effective, "getBackgroundOpacityPercent")), "Widget " + ids[index] + " effective configuration fell back or was mixed: " + effective);
        }
        SharedPreferences preferences = context.getSharedPreferences(SETTINGS, 0);
        require("Dark".equals(preferences.getString("ui_brightness", null)) && preferences.getInt("custom_charging_target", -1) == 87 && preferences.getInt("discharging_target", -1) == 13, "New UI preferences were reset");
    }

    private int[] newWidgetIds(Context context) {
        int[] ids = AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(APP, APP + ".widgets.StatusWidgetProvider"));
        Arrays.sort(ids);
        require(ids.length == 2, "Expected exactly two Status widgets; actual=" + Arrays.toString(ids));
        return ids;
    }

    private void seedBackupData(Context context) throws Exception {
        stopCurrentMonitoring(context);
        require(context.getSharedPreferences(SETTINGS, 0).edit().putString("temperature_unit", "fahrenheit").putString("autostart", "never").putBoolean("enable_logging", false).putString("max_log_age", "-1").putString("ui_brightness", "Dark").putString("charging_target_mode", "custom").putInt("custom_charging_target", 87).putInt("discharging_target", 13).putString("battery_current_multiplier", "1").commit(), "Current backup settings seed failed");
        replaceCurrentAlarms(context, false, false);
        replaceCurrentLogs(context, false, false);
        require(context.getSharedPreferences("predictor_sp_store", 0).edit().clear().putFloat("key_ave_discharge", 456789f).putFloat("key_ave_recharge_ac", 123456f).putFloat("key_ave_recharge_wl", 234567f).putFloat("key_ave_recharge_usb", 345678f).putInt("key_predictor_state_version", 2).commit(), "Current predictor seed failed");
    }

    private void seedSentinelData(Context context) throws Exception {
        seedBackupData(context);
        require(context.getSharedPreferences(SETTINGS, 0).edit().putString("temperature_unit", "celsius").putBoolean("enable_logging", false).putString("max_log_age", "24").putString("icon_content", "percentage").putBoolean("show_icon_unit", false).commit(), "Sentinel settings seed failed");
        replaceCurrentAlarms(context, true, false);
        replaceCurrentLogs(context, true, false);
        require(context.getSharedPreferences("predictor_sp_store", 0).edit().putFloat("key_ave_recharge_ac", 999999f).commit(), "Sentinel predictor seed failed");
    }

    private void replaceCurrentAlarms(Context context, boolean sentinel, boolean empty) throws Exception {
        Object database = construct(context, "alarms.AlarmDatabase");
        try {
            java.lang.reflect.Constructor<?> record = type(context, "alarms.AlarmRecord").getConstructor(boolean.class, String.class, String.class);
            List<?> alarms = empty ? Collections.emptyList() : sentinel ? Collections.singletonList(record.newInstance(false, "temp_rises", "444")) : Arrays.asList(record.newInstance(true, "charge_rises", "83"), record.newInstance(false, "charge_drops", "17"));
            require(Boolean.TRUE.equals(invoke(database, "replaceAllAlarms", List.class, alarms)), "Current alarm persistence failed");
        } finally {
            invoke(database, "close");
        }
    }

    private void replaceCurrentLogs(Context context, boolean sentinel, boolean empty) throws Exception {
        Object database = construct(context, "logs.LogDatabase");
        try {
            java.lang.reflect.Constructor<?> record = type(context, "logs.LogRecord").getConstructor(int.class, Integer.class, long.class, Integer.class, Integer.class, Long.class, Long.class);
            List<?> logs = empty ? Collections.emptyList() : sentinel ? Collections.singletonList(record.newInstance(3, 33, 1710000000000L, 299, 3999, null, null)) : Arrays.asList(record.newInstance(3, 72, 1700000000000L, 287, 4011, -120000L, 2100000L), record.newInstance(3, 71, 1700000060000L, 291, 4007, -140000L, 2070000L));
            invoke(database, "replaceAllLogs", List.class, logs);
        } finally {
            invoke(database, "close");
        }
    }

    private void stopCurrentMonitoring(Context context) throws Exception {
        require(Boolean.TRUE.equals(invoke(singleton(context, "monitoring.MonitoringDesiredState"), "write", new Class<?>[]{Context.class, boolean.class, boolean.class}, context, false, true)), "Could not persist explicitly stopped monitoring");
        Object watchdog = type(context, "monitoring.BackgroundServiceWatchdog").getField("Companion").get(null);
        invoke(watchdog, "cancel", Context.class, context);
        stopService(context);
        require(Boolean.FALSE.equals(invoke(singleton(context, "monitoring.MonitoringDesiredState"), "read", Context.class, context)), "Monitoring desire remained active after stop");
    }

    private void startMonitoringForAlarm(Context context) throws Exception {
        stopCurrentMonitoring(context);
        require(context.getSharedPreferences(SETTINGS, 0).edit().putBoolean("enable_logging", true).putString("max_log_age", "-1").putString("autostart", "always").commit(), "Alarm monitoring settings seed failed");
        replaceCurrentAlarms(context, false, true);
        replaceCurrentLogs(context, false, true);
        NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        require(notifications != null, "Notification service is unavailable");
        notifications.cancelAll();
        require(Boolean.TRUE.equals(invoke(singleton(context, "monitoring.MonitoringDesiredState"), "write", new Class<?>[]{Context.class, boolean.class, boolean.class}, context, true, true)), "Could not persist requested monitoring start");
        startService(context);
    }

    private void alarmFailure(Context context, boolean install) throws Exception {
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(context.getDatabasePath("alarms.db").getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            database.execSQL(install ? "CREATE TRIGGER e2e_fail_restore BEFORE INSERT ON alarms BEGIN SELECT RAISE(ABORT, 'injected alarm failure'); END" : "DROP TRIGGER IF EXISTS e2e_fail_restore");
        }
    }

    private JSONArray alarmSnapshot(Context context) throws Exception {
        JSONArray result = new JSONArray();
        if (!context.getDatabasePath("alarms.db").isFile()) return result;
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(context.getDatabasePath("alarms.db").getPath(), null, SQLiteDatabase.OPEN_READONLY); Cursor rows = database.rawQuery("SELECT enabled,type,threshold FROM alarms ORDER BY _id", null)) {
            while (rows.moveToNext())
                result.put(new JSONObject().put("enabled", rows.getInt(0) == 1).put("type", rows.getString(1)).put("threshold", rows.getString(2)));
        }
        return result;
    }

    private JSONArray logSnapshot(Context context) throws Exception {
        JSONArray result = new JSONArray();
        if (!context.getDatabasePath("logs.db").isFile()) return result;
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(context.getDatabasePath("logs.db").getPath(), null, SQLiteDatabase.OPEN_READONLY); Cursor rows = database.rawQuery("SELECT * FROM logs ORDER BY time,_id", null)) {
            while (rows.moveToNext()) {
                JSONObject record = new JSONObject();
                for (String column : new String[]{"status", "charge", "time", "temperature", "voltage", "current", "remaining_charge_uah"}) {
                    int index = rows.getColumnIndex(column);
                    record.put(column, index < 0 || rows.isNull(index) ? JSONObject.NULL : rows.getLong(index));
                }
                result.put(record);
            }
        }
        return result;
    }

    private JSONObject snapshot(Context context) throws Exception {
        boolean desired;
        try {
            desired = Boolean.TRUE.equals(invoke(singleton(context, "monitoring.MonitoringDesiredState"), "read", Context.class, context));
        } catch (ClassNotFoundException historicalVersion) {
            desired = context.getSharedPreferences("sp_store_main", 0).getBoolean("serviceDesired", false);
        }
        return new JSONObject().put("settings", new JSONObject(context.getSharedPreferences(SETTINGS, 0).getAll())).put("main", new JSONObject(context.getSharedPreferences("sp_store_main", 0).getAll())).put("predictor", new JSONObject(context.getSharedPreferences("predictor_sp_store", 0).getAll())).put("alarms", alarmSnapshot(context)).put("logs", logSnapshot(context)).put("monitoringDesired", desired);
    }

    private Class<?> type(Context context, String name) throws Exception {
        return context.getClassLoader().loadClass(APP + "." + name);
    }

    private Object singleton(Context context, String name) throws Exception {
        return type(context, name).getField("INSTANCE").get(null);
    }

    private Object construct(Context context, String name) throws Exception {
        return type(context, name).getConstructor(Context.class).newInstance(context);
    }

    private static Object invoke(Object target, String name, Class<?> argumentType, Object argument) throws Exception {
        return invoke(target, name, new Class<?>[]{argumentType}, argument);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static Object invoke(Object target, String name) throws Exception {
        return invoke(target, name, new Class<?>[0]);
    }

    private static void write(File directory, String name, String value) throws Exception {
        Files.write(new File(directory, PREFIX + name).toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
