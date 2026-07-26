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
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;


import androidx.appcompat.app.AlertDialog;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
//import androidx.preference.Preference.OnPreferenceClickListener;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

public class AlarmEditFragment extends PreferenceFragmentCompat {
    private Resources res;

    private PreferenceScreen mPreferenceScreen;
    private AlarmDatabase alarms;
    private Cursor mCursor;
    private AlarmAdapter mAdapter;
    private NotificationManager mNotificationManager;

    public static final String KEY_ENABLED      = "enabled";
    public static final String KEY_TYPE         = "type";
    public static final String KEY_THRESHOLD    = "threshold";

    public static final String KEY_CHAN_DISABLED   = "alarm_chan_disabled";
    public static final String KEY_CHAN_SETTINGS_B = "channel_settings_button";

    public static final String EXTRA_ALARM_ID = "codes.swistak.batterymonitor.AlarmID";

    private static final String[] chargeEntries = {
        "5%", "10%", "15%", "20%", "25%", "30%", "35%", "40%", "45%", "50%",
        "55%", "60%", "65%", "70%", "75%", "80%", "85%", "90%", "95%", "99%"};
    private static final String[] chargeValues = {
        "5", "10", "15", "20", "25", "30", "35", "40", "45", "50",
        "55", "60", "65", "70", "75", "80", "85", "90", "95", "99"};

    private boolean chanDisabled;

    public void setScreen() {
        if (res != null)
            setPreferences();
    }

    private void setPreferences() {
        setPreferencesFromResource(R.xml.alarm_pref_screen, null);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        res = getResources();
        alarms = new AlarmDatabase(getActivity());

        mNotificationManager = (NotificationManager) getActivity().getSystemService(Context.NOTIFICATION_SERVICE);

        mCursor = alarms.getAlarm(getActivity().getIntent().getIntExtra(EXTRA_ALARM_ID, -1));
        mAdapter = new AlarmAdapter();

        setPreferences();
        mPreferenceScreen = getPreferenceScreen();
        // Setting visible to false here seems, at least on Pixel 6, to default to not taking up space,
        //   but it'll animate it in if necessary.  That's kinda nice in a way, though I'd be fine either way
        //   if there weren't animations.  But given that animations seem to just happen, kinda nice is WAY
        //   better than super awful, and it's super awful, in the normal case of the channel not being
        //   disabled, to default to *showing* the message briefly, and conspicuously animating it away.  This
        //   way it's a slight win to draw more attention to the channel being disabled, if it is.  But in any
        //   case, again, it's infinitely better than the other way around: always animating it away in an
        //   annoying and confusing fashion when everything is normal.
        mPreferenceScreen.findPreference(KEY_CHAN_DISABLED).setVisible(false);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mCursor.close();
        alarms.close();
    }

    @Override
    public void onResume() {
        super.onResume();

        mCursor.requery();
        mCursor.moveToFirst();
        mAdapter.requery();

        matchEnabled();
        syncValuesAndSetListeners();
    }

    @Override
    public void onPause() {
        super.onPause();
        mCursor.deactivate();
    }

    private void syncValuesAndSetListeners() {
        CheckBoxPreference cb = (CheckBoxPreference) mPreferenceScreen.findPreference(KEY_ENABLED);
        cb.setChecked(mAdapter.enabled);
        cb.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference pref, Object newValue) {
                mAdapter.setEnabled((Boolean) newValue);
                return true;
            }
        });

        ListPreference lp = (ListPreference) mPreferenceScreen.findPreference(KEY_TYPE);
        lp.setValue(mAdapter.type);
        updateSummary(lp);

        lp.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference pref, Object newValue) {
                if (mAdapter.type.equals(newValue)) return false;

                mAdapter.setType((String) newValue);

                ((ListPreference) pref).setValue((String) newValue);
                updateSummary((ListPreference) pref);

                setUpThresholdList(true);

                matchEnabled(); // Call after setUpThresholdList, to make sure to disable if necessary

                return false;
            }
        });

        lp = (ListPreference) mPreferenceScreen.findPreference(KEY_THRESHOLD);
        setUpThresholdList(false);
        lp.setValue(mAdapter.threshold);
        updateSummary(lp);
        lp.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference pref, Object newValue) {
                String val = (String) newValue;
                if (val.equals("custom")) {
                    showCustomThresholdDialog((ListPreference) pref);
                    return false;
                }

                if (mAdapter.threshold.equals(val)) return false;

                mAdapter.setThreshold(val);

                ((ListPreference) pref).setValue(val);
                updateSummary((ListPreference) pref);

                return false;
            }
        });
    }

    private void showCustomThresholdDialog(final ListPreference lp) {
        Context context = getContext();
        if (context == null) return;

        final EditText et = new EditText(context);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);

        String currentVal = mAdapter.threshold;
        boolean convertF = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(SettingsFragment.KEY_CONVERT_F, false);

        String message;
        if (mAdapter.type.contains("temp")) {
            String unit = convertF ? getString(R.string.fahrenheit) : getString(R.string.celsius);
            String template = getString(R.string.alarm_custom_temp_message);
            message = template.contains("%s")
                    ? template.replace("%s", unit)
                    : (template + " (" + unit + ")");
            try {
                int tenthsC = Integer.parseInt(currentVal);
                if (convertF) {
                    et.setText(String.valueOf(Math.round(tenthsC * 9.0 / 50.0 + 32.0)));
                } else {
                    et.setText(String.valueOf(tenthsC / 10));
                }
            } catch (NumberFormatException e) {
                et.setText(currentVal);
            }
        } else {
            message = getString(R.string.alarm_custom_charge_message);
            et.setText(currentVal);
        }

        FrameLayout container = new FrameLayout(context);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int margin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20,
                getResources().getDisplayMetrics());
        params.leftMargin = margin;
        params.rightMargin = margin;
        et.setLayoutParams(params);
        container.addView(et);

        new AlertDialog.Builder(context)
                .setTitle(lp.getTitle())
                .setMessage(message)
                .setView(container)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String input = et.getText().toString();
                        if (validateAndSaveThreshold(input, lp)) {
                            dialog.dismiss();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean validateAndSaveThreshold(String input, ListPreference lp) {
        if (input == null || input.isEmpty()) return false;
        Context context = getContext();
        if (context == null) return false;

        try {
            int val = Integer.parseInt(input);
            if (mAdapter.type.contains("charge")) {
                if (val < 0 || val > 100) return false;
                mAdapter.setThreshold(String.valueOf(val));
            } else if (mAdapter.type.contains("temp")) {
                boolean convertF = PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean(SettingsFragment.KEY_CONVERT_F, false);
                int tenthsC;
                if (convertF) {
                    tenthsC = (int) Math.round((val - 32) * 50.0 / 9.0);
                } else {
                    tenthsC = val * 10;
                }
                if (tenthsC < -500 || tenthsC > 1000) return false;
                mAdapter.setThreshold(String.valueOf(tenthsC));
            } else {
                mAdapter.setThreshold(String.valueOf(val));
            }

            lp.setValue(mAdapter.threshold);
            updateSummary(lp);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void matchEnabled() {
        Preference prefb = mPreferenceScreen.findPreference(KEY_CHAN_SETTINGS_B);

        if (chanDisabled) {
            Preference p = mPreferenceScreen.findPreference(KEY_ENABLED);
            p.setEnabled(false);
            ListPreference lp = (ListPreference) mPreferenceScreen.findPreference(KEY_THRESHOLD);
            lp.setEnabled(false);

            prefb.setSummary(R.string.alarm_chan_disabled_b);

            p = mPreferenceScreen.findPreference(KEY_CHAN_DISABLED);
            p.setVisible(true);
        } else {
            Preference p = mPreferenceScreen.findPreference(KEY_ENABLED);
            p.setEnabled(true);

            setUpThresholdList(false);

            prefb.setSummary(R.string.alarm_chan_settings_b);

            p = mPreferenceScreen.findPreference(KEY_CHAN_DISABLED);
            p.setVisible(false);
        }
    }

    public void deleteAlarm() {
        alarms.deleteAlarm(mAdapter.id);
    }

    private void updateSummary(ListPreference lp) {
        Boolean formatterUsed;

        lp.setSummary("%%");
        if (lp.getSummary().length() == 2)
            formatterUsed = false;
        else
            formatterUsed = true;

        String entry = (String) lp.getEntry();
        if (entry == null) {
            String val = lp.getValue();
            if (val != null && !val.equals("custom")) {
                if (mAdapter.type.contains("charge")) {
                    entry = val + "%";
                } else if (mAdapter.type.contains("temp")) {
                    Context context = getContext();
                    boolean convertF = false;
                    if (context != null) {
                        convertF = PreferenceManager.getDefaultSharedPreferences(context)
                                .getBoolean(SettingsFragment.KEY_CONVERT_F, false);
                    }
                    try {
                        entry = Str.formatTemp(Integer.parseInt(val), convertF, false);
                    } catch (NumberFormatException e) {
                        entry = val;
                    }
                } else {
                    entry = val;
                }
            }
        }

        if (entry == null) entry = "";
        if (formatterUsed)
            entry = entry.replace("%", "%%");

        if (lp.isEnabled())
            lp.setSummary(Str.currently_set_to + entry);
        else
            lp.setSummary(Str.alarm_pref_not_used);
    }

    private void setUpThresholdList(Boolean resetValue) {
        ListPreference lp = (ListPreference) mPreferenceScreen.findPreference(KEY_THRESHOLD);

        if (mAdapter.type.equals("temp_drops") || mAdapter.type.equals("temp_rises")) {
            String[] entries = new String[Str.temp_alarm_entries.length + 1];
            System.arraycopy(Str.temp_alarm_entries, 0, entries, 0, Str.temp_alarm_entries.length);
            entries[entries.length - 1] = res.getString(R.string.custom);
            lp.setEntries(entries);

            String[] values = new String[Str.temp_alarm_values.length + 1];
            System.arraycopy(Str.temp_alarm_values, 0, values, 0, Str.temp_alarm_values.length);
            values[values.length - 1] = "custom";
            lp.setEntryValues(values);

            lp.setEnabled(true);

            if (resetValue) {
                if (mAdapter.type.equals("temp_drops"))
                    mAdapter.setThreshold("60");
                else
                    mAdapter.setThreshold("460");
                lp.setValue(mAdapter.threshold);
            }
        } else if (mAdapter.type.equals("charge_drops") || mAdapter.type.equals("charge_rises")) {
            String[] entries = new String[chargeEntries.length + 1];
            System.arraycopy(chargeEntries, 0, entries, 0, chargeEntries.length);
            entries[entries.length - 1] = res.getString(R.string.custom);
            lp.setEntries(entries);

            String[] values = new String[chargeValues.length + 1];
            System.arraycopy(chargeValues, 0, values, 0, chargeValues.length);
            values[values.length - 1] = "custom";
            lp.setEntryValues(values);

            lp.setEnabled(true);

            if (resetValue) {
                if (mAdapter.type.equals("charge_drops"))
                    mAdapter.setThreshold("20");
                else
                    mAdapter.setThreshold("90");
                        
                lp.setValue(mAdapter.threshold);
            }
        } else {
            lp.setEnabled(false);
        }

        updateSummary(lp);
    }

    private boolean isChannelDisabled(String channelId) {
        NotificationChannel channel = mNotificationManager.getNotificationChannel(channelId);
        return channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE;
    }

    private class AlarmAdapter {
        public int id;
        String type, threshold;
        Boolean enabled;

        AlarmAdapter() {
            requery();
        }

        void requery() {
                      id = mCursor.getInt(mCursor.getColumnIndexOrThrow(AlarmDatabase.KEY_ID));
                    type = mCursor.getString(mCursor.getColumnIndexOrThrow(AlarmDatabase.KEY_TYPE));
               threshold = mCursor.getString(mCursor.getColumnIndexOrThrow(AlarmDatabase.KEY_THRESHOLD));
                 enabled = (mCursor.getInt(mCursor.getColumnIndexOrThrow(AlarmDatabase.KEY_ENABLED)) == 1);

            chanDisabled = isChannelDisabled(type);
         }

        public void setEnabled(Boolean b) {
            enabled = b;
            alarms.setEnabled(id, enabled);
        }

        public void setType(String s) {
            type = s;
            chanDisabled = isChannelDisabled(type);
            alarms.setType(id, type);
        }

        void setThreshold(String s) {
            threshold = s;
            alarms.setThreshold(id, threshold);
        }
    }

    public void enableNotifsButtonClick() {
        if (mAdapter.type == null)
            return;

        Intent intent;
        intent = new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
        intent.putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, mAdapter.type);
        intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getActivity().getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        startActivity(intent);
    }
}
