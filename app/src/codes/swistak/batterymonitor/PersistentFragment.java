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

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

public class PersistentFragment extends Fragment {
    private Intent biServiceIntent;
    private Messenger serviceMessenger;
    private final MessageHandler messageHandler = new MessageHandler(this);
    private final Messenger messenger = new Messenger(messageHandler);
    private BatteryInfoService.RemoteConnection serviceConnection;
    private boolean serviceConnected;
    private CurrentInfoFragment cif;
    private LogViewFragment lvf;

    public static final String FRAG_TAG = "pfrag";

    public SharedPreferences settings;
    public SharedPreferences sp_service;
    public SharedPreferences sp_main;
    public Resources res;

    private boolean mHasShownOnboardingInThisSession = false;

    private void bindService() {
        if (! serviceConnected && getActivity() != null) {
            getActivity().getApplicationContext().bindService(biServiceIntent, serviceConnection, 0);
            serviceConnected = true;
        }
    }

    private static class MessageHandler extends Handler {
        PersistentFragment pf;

        MessageHandler(PersistentFragment f) {
            pf = f;
        }

        @Override
        public void handleMessage(Message incoming) {
            if (! pf.serviceConnected) {
                //Log.i(LOG_TAG, "serviceConected is false; ignoring message: " + incoming);
                return;
            }

            switch (incoming.what) {
            case BatteryInfoService.RemoteConnection.CLIENT_SERVICE_CONNECTED:
                pf.serviceMessenger = incoming.replyTo;
                pf.sendServiceMessage(BatteryInfoService.RemoteConnection.SERVICE_REGISTER_CLIENT);
                break;
            case BatteryInfoService.RemoteConnection.CLIENT_BATTERY_INFO_UPDATED:
                if (pf.cif != null)
                    pf.cif.batteryInfoUpdated(incoming.getData());

                if (pf.lvf != null)
                    pf.lvf.batteryInfoUpdated();

                break;
            default:
                super.handleMessage(incoming);
            }
        }
    }

    @Override
    public void onAttach(android.app.Activity a) {
        super.onAttach(a);

        updateResources();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRetainInstance(true);

        serviceConnection = new BatteryInfoService.RemoteConnection(messenger);

        biServiceIntent = new Intent(getActivity(), BatteryInfoService.class);

        loadSettingsFiles();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (serviceConnected && getActivity() != null) {
            getActivity().getApplicationContext().unbindService(serviceConnection);
            serviceConnected = false;
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        sendServiceMessage(BatteryInfoService.RemoteConnection.SERVICE_REGISTER_CLIENT);

        sp_main.edit().putBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, true).apply();

        // From now on, BootCompletedReceiver should ignore value in sp_service and use our value.
        //   We're not removing the value from sp_service, because that would require a commit, and
        //   the whole point of this is avoiding cross-process writes.
        if (! sp_main.getBoolean(SettingsFragment.KEY_MIGRATED_SERVICE_DESIRED, false))
            sp_main.edit().putBoolean(SettingsFragment.KEY_MIGRATED_SERVICE_DESIRED, true).apply();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(getActivity(), android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {

            if (sp_main.getBoolean(SettingsFragment.KEY_FIRST_RUN, true)) {
                showNotificationOnboarding();
            } else if (!mHasShownOnboardingInThisSession) {
                requestNotificationPermission();
                mHasShownOnboardingInThisSession = true;
            }
            return;
        }

        if (sp_main.getBoolean(SettingsFragment.KEY_FIRST_RUN, true)) {
            sp_main.edit().putBoolean(SettingsFragment.KEY_FIRST_RUN, false).apply();
        }

        startServiceIfNeeded();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 101) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startServiceIfNeeded();
            }
        }
    }

    private void startServiceIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(getActivity(), android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!serviceConnected) {
                BatteryInfoService.startForegroundServiceSafely(getActivity());
                bindService();
            }
        }
    }

    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS,
                                            "android.permission.POST_PROMOTED_NOTIFICATIONS"}, 101);
        } else if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
        }
    }

    private void showNotificationOnboarding() {
        if (mHasShownOnboardingInThisSession) return;
        mHasShownOnboardingInThisSession = true;

        OnboardingDialogFragment dialog = new OnboardingDialogFragment();
        dialog.show(getParentFragmentManager(), "onboarding");
    }

    public static class OnboardingDialogFragment extends androidx.fragment.app.DialogFragment {
        @Override
        public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
            final PersistentFragment pf = (PersistentFragment) getParentFragmentManager().findFragmentByTag(FRAG_TAG);
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getActivity());
            builder.setCancelable(false);

            if (BatteryInfoService.supportsLiveUpdates()) {
                boolean enabled = BatteryInfoService.isLiveUpdateEnabledInSystem(getActivity());
                builder.setTitle(R.string.live_updates_onboarding_title);

                if (enabled) {
                    builder.setMessage(R.string.live_updates_onboarding_message_on)
                           .setPositiveButton(R.string.live_updates_onboarding_positive_on, new android.content.DialogInterface.OnClickListener() {
                               @Override
                               public void onClick(android.content.DialogInterface dialog, int which) {
                                   if (pf != null) pf.requestNotificationPermission();
                               }
                           });
                } else {
                    builder.setMessage(R.string.live_updates_onboarding_message_off)
                           .setPositiveButton(R.string.live_updates_onboarding_positive_off, new android.content.DialogInterface.OnClickListener() {
                               @Override
                               public void onClick(android.content.DialogInterface dialog, int which) {
                                   try {
                                       String action;
                                       try {
                                           action = (String) android.provider.Settings.class.getField("ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS").get(null);
                                       } catch (Throwable ignored) {
                                           action = "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS";
                                       }
                                       Intent intent = new Intent(action);
                                       intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getActivity().getPackageName());
                                       startActivity(intent);
                                   } catch (Throwable ignored) {}
                               }
                           })
                           .setNegativeButton(R.string.live_updates_onboarding_negative_off, new android.content.DialogInterface.OnClickListener() {
                               @Override
                               public void onClick(android.content.DialogInterface dialog, int which) {
                                   if (pf != null) pf.requestNotificationPermission();
                               }
                           });
                }
            } else {
                builder.setTitle(R.string.app_full_name)
                       .setMessage(R.string.notifications_onboarding_message)
                       .setPositiveButton(android.R.string.ok, new android.content.DialogInterface.OnClickListener() {
                           @Override
                           public void onClick(android.content.DialogInterface dialog, int which) {
                               if (pf != null) pf.requestNotificationPermission();
                           }
                       })
                       .setNegativeButton(R.string.cancel, new android.content.DialogInterface.OnClickListener() {
                           @Override
                           public void onClick(android.content.DialogInterface dialog, int which) {
                               if (pf != null) {
                                   pf.sp_main.edit().putBoolean(SettingsFragment.KEY_FIRST_RUN, false).apply();
                                   pf.startServiceIfNeeded();
                               }
                           }
                       });
            }

            return builder.create();
        }

        @Override
        public void onDismiss(android.content.DialogInterface dialog) {
            super.onDismiss(dialog);
            PersistentFragment pf = (PersistentFragment) getParentFragmentManager().findFragmentByTag(FRAG_TAG);
            if (pf != null) {
                pf.sp_main.edit().putBoolean(SettingsFragment.KEY_FIRST_RUN, false).apply();
            }
        }
    }

    @Override
    public void onStop() {
        super.onStop();

        sendServiceMessage(BatteryInfoService.RemoteConnection.SERVICE_UNREGISTER_CLIENT);
    }

    @Override
    public void onConfigurationChanged (Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    private void updateResources() {
        res = getActivity().getResources();
        Str.setResources(res);
    }

    // Public API starts here for use by BatteryInfoActivity and any of its Fragments

    public static PersistentFragment getInstance(FragmentManager fm) {
        PersistentFragment pfrag = (PersistentFragment) fm.findFragmentByTag(FRAG_TAG);

        if (pfrag == null) {
            pfrag = new PersistentFragment();
            fm.beginTransaction().add(pfrag, FRAG_TAG).commit();
        }

        return pfrag;
    }

    public void setCIF(CurrentInfoFragment f) {
        cif = f;
    }

    public void setLVF(LogViewFragment f) {
        lvf = f;
    }

    public void loadSettingsFiles() {
        settings = getActivity().getSharedPreferences(SettingsFragment.SETTINGS_FILE, Context.MODE_MULTI_PROCESS);
        sp_service = getActivity().getSharedPreferences(SettingsFragment.SP_SERVICE_FILE, Context.MODE_MULTI_PROCESS);
        sp_main = getActivity().getSharedPreferences(SettingsFragment.SP_MAIN_FILE, Context.MODE_MULTI_PROCESS);
    }

    public void sendServiceMessage(int what) {
        if (serviceMessenger == null)
            return;

        Message outgoing = Message.obtain();
        outgoing.what = what;
        outgoing.replyTo = messenger;
        try { serviceMessenger.send(outgoing); } catch (android.os.RemoteException e) {}
    }

    public void closeApp() {
        sp_main.edit().putBoolean(BatteryInfoService.KEY_SERVICE_DESIRED, false).apply();
        if (getActivity() != null)
            BackgroundServiceWatchdog.cancel(getActivity().getApplicationContext());

        if (getActivity() == null) return;

        if (serviceConnected) {
            getActivity().getApplicationContext().unbindService(serviceConnection);
            getActivity().stopService(biServiceIntent);
            serviceConnected = false;
        }

        getActivity().finish();
    }
}
