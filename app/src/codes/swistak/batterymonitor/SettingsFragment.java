/*
    Copyright (c) 2009-2020 Darshan Computing, LLC
    Modified in 2026 by Tomasz Świstak <tomasz@swistak.codes> for the Battery Monitor fork.
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

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;


import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
//import androidx.preference.Preference.OnPreferenceClickListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;

import java.util.Locale;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

public class SettingsFragment extends PreferenceFragmentCompat implements OnSharedPreferenceChangeListener {
    public static final String SETTINGS_FILE = "codes.swistak.batterymonitor_preferences";
    public static final String SP_SERVICE_FILE = "sp_store";   // Only write from Service process
    public static final String SP_MAIN_FILE = "sp_store_main"; // Only write from main process

    public static final String KEY_NOTIFICATION_SETTINGS = "notification_settings";
    public static final String KEY_STATUS_BAR_ICON_SETTINGS = "status_bar_icon_settings";
    public static final String KEY_STATUS_BAR_CHIP_SETTINGS = "status_bar_chip_settings";
    public static final String KEY_CURRENT_HACK_SETTINGS = "current_hack_settings";
    public static final String KEY_ALARMS_SETTINGS = "alarms_settings";
    public static final String KEY_ALARM_EDIT_SETTINGS = "alarm_edit_settings";
    public static final String KEY_ADVANCED_INFO_HELP = "advanced_info_help";
    public static final String KEY_OTHER_SETTINGS = "other_settings";
    public static final String KEY_ENABLE_LOGGING = SettingsKeys.KEY_ENABLE_LOGGING;
    public static final String KEY_CHANGE_APP_LANGUAGE_HOLDER = "change_app_language_holder";
    public static final String KEY_CHANGE_APP_LANGUAGE = "change_app_language";
    public static final String KEY_MAX_LOG_AGE = SettingsKeys.KEY_MAX_LOG_AGE;
    public static final String KEY_ICON_CONTENT = SettingsKeys.KEY_ICON_CONTENT;
    public static final String KEY_CONVERT_F = SettingsKeys.KEY_CONVERT_F;
    public static final String KEY_NOTIFY_STATUS_DURATION = SettingsKeys.KEY_NOTIFY_STATUS_DURATION;
    public static final String KEY_AUTOSTART = SettingsKeys.KEY_AUTOSTART;
    public static final String KEY_PREDICTION_TYPE = SettingsKeys.KEY_PREDICTION_TYPE;
    public static final String KEY_STATUS_DUR_EST = SettingsKeys.KEY_STATUS_DUR_EST;
    public static final String KEY_CAT_CHARGING_INDICATOR = "category_charging_indicator";
    public static final String KEY_PLUGIN_SETTINGS = "plugin_settings";
    public static final String KEY_INDICATE_CHARGING = SettingsKeys.KEY_INDICATE_CHARGING;
    public static final String KEY_SHOW_ICON_UNIT = SettingsKeys.KEY_SHOW_ICON_UNIT;
    public static final String KEY_CAT_STATUS_BAR_CHIP = "category_status_bar_chip";
    public static final String KEY_CHIP_CONTENT = SettingsKeys.KEY_CHIP_CONTENT;
    public static final String KEY_CHIP_SWITCHING_INTERVAL = SettingsKeys.KEY_CHIP_SWITCHING_INTERVAL;
    public static final String KEY_CHIP_INDICATE_CHARGING = SettingsKeys.KEY_CHIP_INDICATE_CHARGING;
    public static final String KEY_LIVE_UPDATE_DISPLAY = SettingsKeys.KEY_LIVE_UPDATE_DISPLAY;
    public static final String KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION = SettingsKeys.KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION;
    public static final String KEY_RED = SettingsKeys.KEY_RED;
    public static final String KEY_RED_THRESH = SettingsKeys.KEY_RED_THRESH;
    public static final String KEY_AMBER = SettingsKeys.KEY_AMBER;
    public static final String KEY_AMBER_THRESH = SettingsKeys.KEY_AMBER_THRESH;
    public static final String KEY_GREEN = SettingsKeys.KEY_GREEN;
    public static final String KEY_GREEN_THRESH = SettingsKeys.KEY_GREEN_THRESH;
    public static final String KEY_TOP_LINE = SettingsKeys.KEY_TOP_LINE;
    public static final String KEY_BOTTOM_LINE = SettingsKeys.KEY_BOTTOM_LINE;
    public static final String KEY_TIME_REMAINING_VERBOSITY = SettingsKeys.KEY_TIME_REMAINING_VERBOSITY;
    public static final String KEY_STATUS_DURATION_IN_VITAL_SIGNS = SettingsKeys.KEY_STATUS_DURATION_IN_VITAL_SIGNS;
    public static final String KEY_CAT_CURRENT_HACK_MAIN = "category_current_hack_main";
    public static final String KEY_CAT_CURRENT_HACK_UNSUPPORTED = "category_current_hack_unsupported";
    public static final String KEY_ENABLE_CURRENT_HACK = SettingsKeys.KEY_ENABLE_CURRENT_HACK;
    public static final String KEY_CURRENT_HACK_PREFER_FS = SettingsKeys.KEY_CURRENT_HACK_PREFER_FS;
    public static final String KEY_CURRENT_HACK_MULTIPLIER = SettingsKeys.KEY_CURRENT_HACK_MULTIPLIER;
    public static final String KEY_CAT_CURRENT_HACK_NOTIFICATION = "category_current_hack_notification";
    public static final String KEY_DISPLAY_CURRENT_IN_VITAL_STATS = SettingsKeys.KEY_DISPLAY_CURRENT_IN_VITAL_STATS;
    public static final String KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS = SettingsKeys.KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS;
    public static final String KEY_CAT_CURRENT_HACK_MAIN_WINDOW = "category_current_hack_main_window";
    public static final String KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW = SettingsKeys.KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW;
    public static final String KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW = SettingsKeys.KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW;
    public static final String KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW = SettingsKeys.KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW;
    public static final String KEY_FIRST_RUN = "first_run";
    public static final String KEY_MIGRATED_SERVICE_DESIRED = "service_desired_migrated_to_sp_main";
    public static final String KEY_ENABLE_NOTIFS_B = "enable_notifications_button";
    public static final String KEY_ENABLE_NOTIFS_SUMMARY = "enable_notifications_summary";
    public static final String KEY_UI_COLOR = SettingsKeys.KEY_UI_COLOR;
    public static final String KEY_ENABLE_ADVANCED_STATS = SettingsKeys.KEY_ENABLE_ADVANCED_STATS;
    public static final String KEY_EXPORT_SETTINGS = "export_settings_backup";
    public static final String KEY_IMPORT_SETTINGS = "import_settings_backup";

    private static final int EXPORT_REQUEST = 1;
    private static final int IMPORT_REQUEST = 2;

    private static final String[] PARENTS    = {KEY_ENABLE_LOGGING,
                                                KEY_DISPLAY_CURRENT_IN_VITAL_STATS,
                                                KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW,
                                                KEY_RED,
                                                KEY_AMBER,
                                                KEY_GREEN
    };
    private static final String[][] DEPENDENTS = {{KEY_MAX_LOG_AGE},
                                                  {KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS},
                                                  {KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW, KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW},
                                                  {KEY_RED_THRESH},
                                                  {KEY_AMBER_THRESH},
                                                  {KEY_GREEN_THRESH}
    };

    private static final String[] CURRENT_HACK_DEPENDENTS = {KEY_CURRENT_HACK_PREFER_FS,
                                                             KEY_CURRENT_HACK_MULTIPLIER,
                                                             KEY_DISPLAY_CURRENT_IN_VITAL_STATS,
                                                             KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS,
                                                             KEY_DISPLAY_CURRENT_IN_MAIN_WINDOW,
                                                             KEY_PREFER_CURRENT_AVG_IN_MAIN_WINDOW,
                                                             KEY_AUTO_REFRESH_CURRENT_IN_MAIN_WINDOW
    };

    private static final String[] INVERSE_PARENTS    = {
    };
    private static final String[] INVERSE_DEPENDENTS = {
    };

    private static final String[] LIST_PREFS = {KEY_AUTOSTART, KEY_STATUS_DUR_EST,
                                                KEY_RED_THRESH, KEY_AMBER_THRESH, KEY_GREEN_THRESH,
                                                 KEY_ICON_CONTENT, KEY_CHIP_CONTENT,
                                                KEY_CHIP_SWITCHING_INTERVAL,
                                                 KEY_LIVE_UPDATE_DISPLAY,
                                                KEY_CURRENT_HACK_MULTIPLIER,
                                                KEY_MAX_LOG_AGE, KEY_TOP_LINE, KEY_BOTTOM_LINE,
                                                KEY_TIME_REMAINING_VERBOSITY,
                                                KEY_PREDICTION_TYPE
    };

    private static final String[] RESET_SERVICE = {KEY_CONVERT_F, KEY_NOTIFY_STATUS_DURATION,
                                                   KEY_RED, KEY_RED_THRESH,
                                                   KEY_AMBER, KEY_AMBER_THRESH, KEY_GREEN, KEY_GREEN_THRESH,
                                                   KEY_INDICATE_CHARGING,
                                                   KEY_SHOW_ICON_UNIT,
                                                    KEY_ICON_CONTENT,
                                                    KEY_CHIP_CONTENT,
                                                    KEY_CHIP_SWITCHING_INTERVAL,
                                                    KEY_CHIP_INDICATE_CHARGING,
                                                    KEY_LIVE_UPDATE_DISPLAY,
                                                    KEY_LIVE_UPDATE_KEEP_MAIN_NOTIFICATION,
                                                   KEY_TOP_LINE, KEY_BOTTOM_LINE,
                                                   KEY_ENABLE_LOGGING,
                                                   KEY_CHANGE_APP_LANGUAGE,
                                                   KEY_MAX_LOG_AGE,
                                                   KEY_TIME_REMAINING_VERBOSITY,
                                                   KEY_STATUS_DURATION_IN_VITAL_SIGNS,
                                                   KEY_ENABLE_CURRENT_HACK,
                                                   KEY_CURRENT_HACK_PREFER_FS,
                                                   KEY_CURRENT_HACK_MULTIPLIER,
                                                   KEY_DISPLAY_CURRENT_IN_VITAL_STATS,
                                                   KEY_PREFER_CURRENT_AVG_IN_VITAL_STATS,
                                                   KEY_UI_COLOR,
                                                   KEY_PREDICTION_TYPE
    };

    private static final String[] RESET_SERVICE_WITH_CANCEL_NOTIFICATION = {
    };

    public static final String EXTRA_SCREEN = "codes.swistak.batterymonitor.PrefScreen";

    private Messenger serviceMessenger;
    private final Messenger messenger = new Messenger(new MessageHandler(this));
    private final BatteryInfoService.RemoteConnection serviceConnection = new BatteryInfoService.RemoteConnection(messenger);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Resources res;
    private PreferenceScreen mPreferenceScreen;
    private SharedPreferences mSharedPreferences;
    private NotificationManager mNotificationManager;
    private NotificationChannel mainChan;
    private boolean appNotifsEnabled;
    private boolean mainNotifsEnabled;
    private boolean systemPromotedEnabled;

    private int pref_screen;

    private static class MessageHandler extends Handler {
        private SettingsFragment sa;

        MessageHandler(SettingsFragment a) {
            sa = a;
        }

        @Override
        public void handleMessage(Message incoming) {
            switch (incoming.what) {
            case BatteryInfoService.RemoteConnection.CLIENT_SERVICE_CONNECTED:
                sa.serviceMessenger = incoming.replyTo;
                break;
            default:
                super.handleMessage(incoming);
            }
        }
    }

    public void setScreen(int screen) {
        pref_screen = screen;

        if (res != null)
            setPreferences();
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        res = getResources();

        PreferenceManager pm = getPreferenceManager();
        pm.setSharedPreferencesName(SETTINGS_FILE);
        pm.setSharedPreferencesMode(Context.MODE_MULTI_PROCESS);
        mSharedPreferences = pm.getSharedPreferences();

        if (pref_screen > 0)
            setPreferences();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mNotificationManager == null)
            mNotificationManager = (NotificationManager) getActivity().getSystemService(Context.NOTIFICATION_SERVICE);

        boolean currentAppNotifsEnabled = mNotificationManager.areNotificationsEnabled();
        boolean currentMainNotifsEnabled = getMainNotifsEnabled();
        boolean currentLiveUpdateEnabledInSystem = BatteryInfoService.isLiveUpdateEnabledInSystem(getActivity());

        if (appNotifsEnabled != currentAppNotifsEnabled ||
            mainNotifsEnabled != currentMainNotifsEnabled ||
            systemPromotedEnabled != currentLiveUpdateEnabledInSystem) { // Doesn't seem worth checking which screen
            resetService();
            setPreferences();
        }

        mSharedPreferences.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();

        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
    }

    private void resetService() {
        resetService(false);
    }

    private void resetService(boolean cancelFirst) {
        mSharedPreferences.edit().commit(); // commit() synchronously before messaging Service

        Message outgoing = Message.obtain();

        if (cancelFirst)
            outgoing.what = BatteryInfoService.RemoteConnection.SERVICE_CANCEL_NOTIFICATION_AND_RELOAD_SETTINGS;
        else
            outgoing.what = BatteryInfoService.RemoteConnection.SERVICE_RELOAD_SETTINGS;

        try {
            serviceMessenger.send(outgoing);
        } catch (Exception e) {
            BatteryInfoService.startForegroundServiceSafely(getActivity());
        }
    }

    // pref_screen is the screen we're conceptually on, while
    // pref_res is the actual resource we're loading.
    // In the case of disabled notifications, for example, we'll load the disabled notifs resource, but
    //   we still want to "be on" whichever page we're on.
    private void setPreferences() {
        mNotificationManager = (NotificationManager) getActivity().getSystemService(Context.NOTIFICATION_SERVICE);

        appNotifsEnabled = mNotificationManager.areNotificationsEnabled();
        mainNotifsEnabled = getMainNotifsEnabled();
        systemPromotedEnabled = BatteryInfoService.isLiveUpdateEnabledInSystem(getActivity());

        int pref_res = pref_screen;

        if ((pref_screen == R.xml.status_bar_icon_pref_screen ||
             pref_screen == R.xml.status_bar_chip_pref_screen ||
             pref_screen == R.xml.notification_pref_screen) &&
            (!appNotifsEnabled || !mainNotifsEnabled)) {
            pref_res = R.xml.main_notifs_disabled_pref_screen;
        }

        setPreferencesFromResource(pref_res, null);
        mPreferenceScreen = getPreferenceScreen();

        boolean liveUpdateSupported = BatteryInfoService.supportsLiveUpdates();
        boolean liveUpdateEnabledInSystem = BatteryInfoService.isLiveUpdateEnabledInSystem(getActivity());

        if (pref_screen == R.xml.main_pref_screen) {
            // Are there any Androids that support both live updates and notification icon change?
//            if (liveUpdateSupported && liveUpdateEnabledInSystem) {
            if (liveUpdateSupported) {
                Preference p = mPreferenceScreen.findPreference(KEY_STATUS_BAR_ICON_SETTINGS);
                if (p != null) mPreferenceScreen.removePreference(p);
            }

            if (!liveUpdateSupported) {
                Preference p = mPreferenceScreen.findPreference(KEY_STATUS_BAR_CHIP_SETTINGS);
                if (p != null) mPreferenceScreen.removePreference(p);
            }
        }

        PreferenceCategory cat;

        if (pref_res == R.xml.main_notifs_disabled_pref_screen) {
            Preference prefb = mPreferenceScreen.findPreference(KEY_ENABLE_NOTIFS_B);
            //prefb.setEnabled(false);
            //prefb.setOnPreferenceClickListener(notifChanBListener);
            Preference prefs = mPreferenceScreen.findPreference(KEY_ENABLE_NOTIFS_SUMMARY);

            if (!appNotifsEnabled) {
                prefs.setSummary(R.string.app_notifs_disabled_summary);
                prefb.setSummary(R.string.app_notifs_disabled_b);
            } else {
                prefs.setSummary(R.string.main_notifs_disabled_summary);
                prefb.setSummary(R.string.main_notifs_disabled_b);
            }
        } else if (pref_screen == R.xml.notification_pref_screen) {
            Preference prefb = mPreferenceScreen.findPreference(KEY_ENABLE_NOTIFS_B);
            //prefb.setEnabled(false);
            //prefb.setOnPreferenceClickListener(notifChanBListener);

            prefb.setSummary(R.string.pref_manage_main_channel);
        } else if (pref_screen == R.xml.status_bar_chip_pref_screen) {
            if (!liveUpdateSupported) {
                PreferenceCategory chipCat = (PreferenceCategory) mPreferenceScreen.findPreference(KEY_CAT_STATUS_BAR_CHIP);
                if (chipCat != null) {
                    chipCat.removeAll();
                    chipCat.setLayoutResource(R.layout.none);
                }
            } else {
                updateChipIntervalVisibility();
            }
        } else if (pref_screen == R.xml.current_hack_pref_screen) {
            if (CurrentHack.getCurrent() == null) {
                cat = (PreferenceCategory) mPreferenceScreen.findPreference(KEY_CAT_CURRENT_HACK_MAIN);
                cat.removeAll();
                cat.setLayoutResource(R.layout.none);
                cat = (PreferenceCategory) mPreferenceScreen.findPreference(KEY_CAT_CURRENT_HACK_NOTIFICATION);
                cat.removeAll();
                cat.setLayoutResource(R.layout.none);
                cat = (PreferenceCategory) mPreferenceScreen.findPreference(KEY_CAT_CURRENT_HACK_MAIN_WINDOW);
                cat.removeAll();
                cat.setLayoutResource(R.layout.none);
            } else {
                cat = (PreferenceCategory) mPreferenceScreen.findPreference(KEY_CAT_CURRENT_HACK_UNSUPPORTED);
                cat.removeAll();
                cat.setLayoutResource(R.layout.none);
            }
        }

        for (int i=0; i < PARENTS.length; i++)
            setEnablednessOfDeps(i);

        for (int i=0; i < INVERSE_PARENTS.length; i++)
            setEnablednessOfInverseDeps(i);

        for (int i=0; i < LIST_PREFS.length; i++)
            updateListPrefSummary(LIST_PREFS[i]);

        if (pref_screen == R.xml.current_hack_pref_screen && !mSharedPreferences.getBoolean(KEY_ENABLE_CURRENT_HACK, false))
            setEnablednessOfCurrentHackDeps(false);

        updateConvertFSummary();
        setupLanguage();

        Intent biServiceIntent = new Intent(getActivity(), BatteryInfoService.class);
        getActivity().bindService(biServiceIntent, serviceConnection, 0);
    }

    public boolean onPreferenceTreeClick(Preference preference) {
        String key = preference.getKey();
        if (key == null) {
            return false;
        } else if (key.equals(KEY_NOTIFICATION_SETTINGS) ||
                   key.equals(KEY_STATUS_BAR_ICON_SETTINGS) ||
                   key.equals(KEY_STATUS_BAR_CHIP_SETTINGS) ||
                   key.equals(KEY_CURRENT_HACK_SETTINGS) ||
                   key.equals(KEY_OTHER_SETTINGS)) {
            ComponentName comp = new ComponentName(getActivity().getPackageName(), SettingsActivity.class.getName());
            startActivity(new Intent().setComponent(comp).putExtra(EXTRA_SCREEN, key));

            return true;
        } else if (key.equals(KEY_EXPORT_SETTINGS)) {
            Intent exportIntent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "battery_monitor_settings.json");
            startActivityForResult(exportIntent, EXPORT_REQUEST);
            return true;
        } else if (key.equals(KEY_IMPORT_SETTINGS)) {
            Intent importIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json");
            startActivityForResult(importIntent, IMPORT_REQUEST);
            return true;
        } else
            return key.equals(KEY_PLUGIN_SETTINGS);
    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this);

        if (key.equals(KEY_CHIP_CONTENT)) {
            updateChipIntervalVisibility();
        }

        if (key.equals(KEY_LIVE_UPDATE_DISPLAY)) {
            updateChipIntervalVisibility();
        }

        for (int i=0; i < PARENTS.length; i++) {
            if (key.equals(PARENTS[i])) {
                setEnablednessOfDeps(i);
                break;
            }
        }

        for (int i=0; i < INVERSE_PARENTS.length; i++) {
            if (key.equals(INVERSE_PARENTS[i])) {
                setEnablednessOfInverseDeps(i);
                break;
            }
        }

        for (int i=0; i < LIST_PREFS.length; i++) {
            if (key.equals(LIST_PREFS[i])) {
                updateListPrefSummary(LIST_PREFS[i]);
                break;
            }
        }

        if (key.equals(KEY_CONVERT_F)) {
            updateConvertFSummary();
        }

        if (key.equals(KEY_ENABLE_CURRENT_HACK)) {
            if (mSharedPreferences.getBoolean(KEY_ENABLE_CURRENT_HACK, false))
                setEnablednessOfCurrentHackDeps(true);

            for (int i=0; i < PARENTS.length; i++)
                setEnablednessOfDeps(i);

            if (!mSharedPreferences.getBoolean(KEY_ENABLE_CURRENT_HACK, false))
                setEnablednessOfCurrentHackDeps(false);
        }

        if (key.equals(KEY_ENABLE_ADVANCED_STATS) &&
            mSharedPreferences.getBoolean(KEY_ENABLE_ADVANCED_STATS, false))
            maybeRequestShizukuForAdvancedStats();

        if (key.equals(KEY_CURRENT_HACK_PREFER_FS))
            CurrentHack.setPreferFS(mSharedPreferences.getBoolean(KEY_CURRENT_HACK_PREFER_FS,
                                                                  res.getBoolean(R.bool.default_prefer_fs_current_hack)));

        for (int i=0; i < RESET_SERVICE.length; i++) {
            if (key.equals(RESET_SERVICE[i])) {
                resetService();
                break;
            }
        }

        for (int i=0; i < RESET_SERVICE_WITH_CANCEL_NOTIFICATION.length; i++) {
            if (key.equals(RESET_SERVICE_WITH_CANCEL_NOTIFICATION[i])) {
                resetService(true);
                break;
            }
        }

        mSharedPreferences.registerOnSharedPreferenceChangeListener(this);
        setupLanguage();
    }

    private void maybeRequestShizukuForAdvancedStats() {
        new Thread(() -> {
            boolean rootAvailable = new AdvancedBatteryStatsCollector.RootExecutor().run("id") != null;
            if (rootAvailable)
                return;

            mainHandler.post(this::requestShizukuPermissionIfNeeded);
        }).start();
    }

    private void requestShizukuPermissionIfNeeded() {
        if (!isAdded() || getActivity() == null)
            return;

        ShizukuProvider.enableMultiProcessSupport(false);
        ShizukuProvider.requestBinderForNonProviderProcess(getActivity().getApplicationContext());

        if (Shizuku.pingBinder()) {
            requestShizukuPermissionFromBinder();
            return;
        }

        Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
            @Override
            public void onBinderReceived() {
                Shizuku.removeBinderReceivedListener(this);
                requestShizukuPermissionFromBinder();
            }
        });
    }

    private void requestShizukuPermissionFromBinder() {
        if (!isAdded() || getActivity() == null || Shizuku.isPreV11())
            return;

        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED)
            return;

        Shizuku.requestPermission(7001);
    }

    private void updateConvertFSummary() {
        Preference pref = mPreferenceScreen.findPreference(KEY_CONVERT_F);
        if (pref == null) return;

        pref.setSummary(res.getString(R.string.currently_using) + " " +
                        (mSharedPreferences.getBoolean(KEY_CONVERT_F, res.getBoolean(R.bool.default_convert_to_fahrenheit)) ?
                         res.getString(R.string.fahrenheit) : res.getString(R.string.celsius)));
    }

    private void setEnablednessOfDeps(int index) {
        for (int i = 0; i < DEPENDENTS[index].length; i++) {
            Preference dependent = mPreferenceScreen.findPreference(DEPENDENTS[index][i]);
            if (dependent == null) return;

            if (mSharedPreferences.getBoolean(PARENTS[index], false))
                dependent.setEnabled(true);
            else
                dependent.setEnabled(false);

            updateListPrefSummary(DEPENDENTS[index][i]);
        }
    }

    private void setEnablednessOfCurrentHackDeps(boolean enabled) {
        for (int i = 0; i < CURRENT_HACK_DEPENDENTS.length; i++) {
            Preference dependent = mPreferenceScreen.findPreference(CURRENT_HACK_DEPENDENTS[i]);

            if (dependent == null) return;

            dependent.setEnabled(enabled);
        }
    }

    private void setEnablednessOfInverseDeps(int index) {
        Preference dependent = mPreferenceScreen.findPreference(INVERSE_DEPENDENTS[index]);
        if (dependent == null) return;

        if (mSharedPreferences.getBoolean(INVERSE_PARENTS[index], false))
            dependent.setEnabled(false);
        else
            dependent.setEnabled(true);

        updateListPrefSummary(INVERSE_DEPENDENTS[index]);
    }

    // private void setEnablednessOfMutuallyExclusive(String key1, String key2) {
    //     Preference pref1 = mPreferenceScreen.findPreference(key1);
    //     Preference pref2 = mPreferenceScreen.findPreference(key2);

    //     if (pref1 == null) return;

    //     if (mSharedPreferences.getBoolean(key1, false))
    //         pref2.setEnabled(false);
    //     else if (mSharedPreferences.getBoolean(key2, false))
    //         pref1.setEnabled(false);
    //     else {
    //         pref1.setEnabled(true);
    //         pref2.setEnabled(true);
    //     }
    // }

    private void updateChipIntervalVisibility() {
        Preference p = mPreferenceScreen.findPreference(KEY_CHIP_SWITCHING_INTERVAL);
        if (p == null) return;

        boolean isSwitching = "switching".equals(mSharedPreferences.getString(KEY_CHIP_CONTENT, ""));
        boolean liveUpdatesDisabled = "never".equals(mSharedPreferences.getString(KEY_LIVE_UPDATE_DISPLAY,
                                                                                    res.getString(R.string.default_live_update_display_mode)));
        p.setVisible(isSwitching && !liveUpdatesDisabled);
    }

    private void updateListPrefSummary(String key) {
        ListPreference pref;
        try { /* Code is simplest elsewhere if we call this on all dependents, but some aren't ListPreferences. */
            pref = (ListPreference) mPreferenceScreen.findPreference(key);
        } catch (java.lang.ClassCastException e) {
            return;
        }

        if (pref == null) return;

        if (pref.isEnabled()) {
            pref.setSummary(res.getString(R.string.currently_set_to) + pref.getEntry());
        } else {
            pref.setSummary(res.getString(R.string.currently_disabled));
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != getActivity().RESULT_OK || data == null || data.getData() == null)
            return;

        Uri uri = data.getData();
        try {
            if (requestCode == EXPORT_REQUEST) {
                SettingsBackup.writeToUri(getActivity(), uri, SettingsBackup.exportToJson(mSharedPreferences));
                Toast.makeText(getActivity(), R.string.settings_exported, Toast.LENGTH_SHORT).show();
            } else if (requestCode == IMPORT_REQUEST) {
                String json = SettingsBackup.readFromUri(getActivity(), uri);
                if (json == null) return;

                int fileVersion = SettingsBackup.getSchemaVersion(json);
                if (fileVersion > SettingsBackup.SCHEMA_VERSION) {
                    new AlertDialog.Builder(getActivity())
                        .setMessage(R.string.settings_file_version_warning)
                        .setPositiveButton(R.string.yes, (DialogInterface dialog, int which) -> doImport(json))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                } else {
                    doImport(json);
                }
            }
        } catch (Exception e) {
            Toast.makeText(getActivity(), R.string.invalid_settings_file, Toast.LENGTH_SHORT).show();
        }
    }

    private void doImport(String json) {
        try {
            SharedPreferences.Editor editor = mSharedPreferences.edit();
            SettingsBackup.importFromJson(editor, json);
            editor.apply();
            setPreferences();
            resetService();
            Toast.makeText(getActivity(), R.string.settings_imported, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(getActivity(), R.string.invalid_settings_file, Toast.LENGTH_SHORT).show();
        }
    }

    private void setupLanguage() {
        PreferenceCategory category;
        Preference pref;
        try {
            category = mPreferenceScreen.findPreference(KEY_CHANGE_APP_LANGUAGE_HOLDER);
            pref = mPreferenceScreen.findPreference(KEY_CHANGE_APP_LANGUAGE);
        } catch (java.lang.ClassCastException e) {
            return;
        }

        if (category == null || pref == null) return;
        pref.setSummary(res.getString(R.string.currently_set_to) + " " + Locale.getDefault().getDisplayLanguage());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            category.setVisible(true);
            pref.setOnPreferenceClickListener(preference -> this.launchChangeAppLanguageIntent());
        } else {
            category.setVisible(false);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private boolean launchChangeAppLanguageIntent() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_LOCALE_SETTINGS);
            intent.setData(Uri.fromParts("package", getContext().getPackageName(), null));
            startActivity(intent);
            return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    public void enableNotifsButtonClick() {
        Intent intent;
        if (!appNotifsEnabled || mainChan == null) {
            intent = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        } else {
            intent = new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            intent.putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, mainChan.getId());
        }

        intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getActivity().getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        startActivity(intent);
    }

    private boolean getMainNotifsEnabled() {
        mainChan = mNotificationManager.getNotificationChannel(BatteryInfoService.CHAN_ID_MAIN);
        return mainChan != null && mainChan.getImportance() > 0;
    }
}
