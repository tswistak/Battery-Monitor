/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
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

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.content.pm.ApplicationInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

public class AdvancedInfoFragment extends Fragment {
    private static final int REQUEST_CODE_SHIZUKU = 7001;

    private final Handler mainHandler = new Handler();
    private boolean loading;
    private boolean shizukuInitialized;
    private boolean tabVisible;

    private TextView statusView;
    private View countersSection;
    private View capacitySection;
    private View chargingSection;
    private View serviceSection;
    private View sysfsSection;
    private View metadataSection;
    private LinearLayout counterRows;
    private LinearLayout capacityRows;
    private LinearLayout chargingRows;
    private LinearLayout serviceRows;
    private LinearLayout sysfsRows;
    private LinearLayout metadataRows;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = new Shizuku.OnBinderReceivedListener() {
        @Override
        public void onBinderReceived() {
            if (isTabActive())
                loadStats();
        }
    };
    private final Shizuku.OnBinderDeadListener binderDeadListener = new Shizuku.OnBinderDeadListener() {
        @Override
        public void onBinderDead() {
            if (isTabActive())
                showNoAccessMessage();
        }
    };
    private final Shizuku.OnRequestPermissionResultListener permissionListener = new Shizuku.OnRequestPermissionResultListener() {
        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
            if (!isTabActive() || requestCode != REQUEST_CODE_SHIZUKU)
                return;

            if (grantResult == PERMISSION_GRANTED)
                loadViaShizuku();
            else
                showNoAccessMessage();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        tabVisible = getUserVisibleHint();
        setHasOptionsMenu(true);
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionListener);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.advanced_battery, container, false);

        statusView = view.findViewById(R.id.advanced_status);
        countersSection = view.findViewById(R.id.advanced_counter_section);
        capacitySection = view.findViewById(R.id.advanced_capacity_section);
        chargingSection = view.findViewById(R.id.advanced_charging_section);
        serviceSection = view.findViewById(R.id.advanced_service_section);
        sysfsSection = view.findViewById(R.id.advanced_sysfs_section);
        metadataSection = view.findViewById(R.id.advanced_metadata_section);
        counterRows = view.findViewById(R.id.advanced_counter_rows);
        capacityRows = view.findViewById(R.id.advanced_capacity_rows);
        chargingRows = view.findViewById(R.id.advanced_charging_rows);
        serviceRows = view.findViewById(R.id.advanced_service_rows);
        sysfsRows = view.findViewById(R.id.advanced_sysfs_rows);
        metadataRows = view.findViewById(R.id.advanced_metadata_rows);

        Button refreshButton = view.findViewById(R.id.advanced_refresh);
        refreshButton.setOnClickListener(v -> loadStats());

        return view;
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        tabVisible = isVisibleToUser;

        if (tabVisible && isResumed())
            loadStats();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (tabVisible)
            loadStats();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.help_only, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_help) {
            ComponentName comp = new ComponentName(getActivity().getPackageName(), SettingsHelpActivity.class.getName());
            Intent intent = new Intent().setComponent(comp);
            intent.putExtra(SettingsActivity.EXTRA_SCREEN, SettingsFragment.KEY_ADVANCED_INFO_HELP);
            startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private boolean isTabActive() {
        return tabVisible && isResumed() && getView() != null;
    }

    private void loadStats() {
        if (!isTabActive() || loading)
            return;

        loading = true;
        showStatus(R.string.advanced_status_loading);

        new Thread(() -> {
            AdvancedBatterySnapshot rootSnapshot = AdvancedBatteryStatsCollector.collect(
                    new AdvancedBatteryStatsCollector.RootExecutor(),
                    AdvancedBatterySnapshot.ACCESS_ROOT,
                    0,
                    getActivity().getApplicationContext(),
                    false
            );

            if (rootSnapshot.hasPrivilegedStats()) {
                postSnapshot(rootSnapshot);
                return;
            }

            mainHandler.post(this::loadViaShizuku);
        }).start();
    }

    private void loadViaShizuku() {
        if (!isTabActive()) {
            loading = false;
            return;
        }

        initShizukuIfNeeded();

        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
            showNoAccessMessage();
            return;
        }

        if (Shizuku.checkSelfPermission() != PERMISSION_GRANTED) {
            showStatus(R.string.advanced_status_waiting_permission);
            loading = false;
            Shizuku.requestPermission(REQUEST_CODE_SHIZUKU);
            return;
        }

        try {
            Shizuku.bindUserService(buildUserServiceArgs(), new ShizukuConnection());
        } catch (Throwable e) {
            showNoAccessMessage();
        }
    }

    private void initShizukuIfNeeded() {
        if (shizukuInitialized)
            return;

        ShizukuProvider.enableMultiProcessSupport(false);
        ShizukuProvider.requestBinderForNonProviderProcess(getActivity().getApplicationContext());
        shizukuInitialized = true;
    }

    private Shizuku.UserServiceArgs buildUserServiceArgs() {
        return new Shizuku.UserServiceArgs(new ComponentName(getActivity().getPackageName(), AdvancedStatsUserService.class.getName()))
                .daemon(false)
                .processNameSuffix("advanced_stats")
                .debuggable((getActivity().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                .version(getInstalledVersionCode())
                .tag("advanced_battery_stats");
    }

    private int getInstalledVersionCode() {
        try {
            return getActivity().getPackageManager().getPackageInfo(getActivity().getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 1;
        }
    }

    private void postSnapshot(final AdvancedBatterySnapshot snapshot) {
        mainHandler.post(() -> {
            loading = false;
            if (!isTabActive())
                return;

            if (!snapshot.hasStats()) {
                showStatus(R.string.advanced_status_no_stats);
                return;
            }

            showStatus(0);
            renderSnapshot(snapshot);
        });
    }

    private void renderSnapshot(AdvancedBatterySnapshot snapshot) {
        clearRows();

        List<Row> rows = new ArrayList<>();
        addRow(rows, R.string.advanced_field_charge_counter, formatMicroAmpHours(snapshot.chargeCounterUah));
        addRow(rows, R.string.advanced_field_current_now, formatMicroAmps(snapshot.currentNowUa));
        addRow(rows, R.string.advanced_field_current_average, formatMicroAmps(snapshot.currentAverageUa));
        addRow(rows, R.string.advanced_field_energy_counter, formatNanoWattHours(snapshot.energyCounterNwh));
        addRow(rows, R.string.advanced_field_cycle_count, formatInteger(snapshot.cycleCount));
        addRows(countersSection, counterRows, rows);

        rows = new ArrayList<>();
        addRow(rows, R.string.advanced_field_reported_capacity, formatPercent(snapshot.reportedCapacityPercent));
        addRow(rows, R.string.advanced_field_state_of_health, formatPercent(snapshot.stateOfHealthPercent));
        addRow(rows, R.string.advanced_field_full_charge_capacity, formatMicroAmpHours(snapshot.fullChargeUah));
        addRow(rows, R.string.advanced_field_design_capacity, formatMicroAmpHours(snapshot.designChargeUah));
        addRow(rows, R.string.advanced_field_estimated_health, formatHealth(snapshot.fullChargeUah, snapshot.designChargeUah));
        addRows(capacitySection, capacityRows, rows);

        rows = new ArrayList<>();
        addRow(rows, R.string.advanced_field_charge_time_remaining, formatChargeTimeRemaining(snapshot.chargeTimeRemainingMs));
        addRow(rows, R.string.advanced_field_max_charging_current, formatMicroAmps(snapshot.maxChargingCurrentUa));
        addRow(rows, R.string.advanced_field_max_charging_voltage, formatMicroVolts(snapshot.maxChargingVoltageUv));
        addRow(rows, R.string.advanced_field_charging_policy, snapshot.chargingPolicy);
        addRow(rows, R.string.advanced_field_charging_state, snapshot.chargingState);
        addRow(rows, R.string.advanced_field_capacity_level, snapshot.capacityLevel);
        addRows(chargingSection, chargingRows, rows);

        rows = new ArrayList<>();
        for (int i = 0; i < snapshot.serviceLabels.size() && i < snapshot.serviceValues.size(); i++)
            rows.add(new Row(snapshot.serviceLabels.get(i), snapshot.serviceValues.get(i)));
        addRows(serviceSection, serviceRows, rows);

        rows = new ArrayList<>();
        for (int i = 0; i < snapshot.sysfsLabels.size() && i < snapshot.sysfsValues.size(); i++)
            rows.add(new Row(snapshot.sysfsLabels.get(i), snapshot.sysfsValues.get(i)));
        addRows(sysfsSection, sysfsRows, rows);

        rows = new ArrayList<>();
        for (int i = 0; i < snapshot.metadataLabels.size() && i < snapshot.metadataValues.size(); i++)
            rows.add(new Row(snapshot.metadataLabels.get(i), snapshot.metadataValues.get(i)));
        addRows(metadataSection, metadataRows, rows);
    }

    private void showNoAccessMessage() {
        mainHandler.post(() -> {
            loading = false;

            if (!isTabActive())
                return;

            showStatus(R.string.advanced_status_no_access);
        });
    }

    private void showStatus(int stringRes) {
        if (stringRes == 0) {
            statusView.setVisibility(View.GONE);
            return;
        }

        statusView.setVisibility(View.VISIBLE);
        statusView.setText(stringRes);
    }

    private void clearRows() {
        counterRows.removeAllViews();
        capacityRows.removeAllViews();
        chargingRows.removeAllViews();
        serviceRows.removeAllViews();
        sysfsRows.removeAllViews();
        metadataRows.removeAllViews();
        countersSection.setVisibility(View.GONE);
        capacitySection.setVisibility(View.GONE);
        chargingSection.setVisibility(View.GONE);
        serviceSection.setVisibility(View.GONE);
        sysfsSection.setVisibility(View.GONE);
        metadataSection.setVisibility(View.GONE);
    }

    private void addRows(View section, LinearLayout container, List<Row> rows) {
        if (rows.isEmpty())
            return;

        section.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(getActivity());

        for (int i = 0; i < rows.size(); i++) {
            View rowView = inflater.inflate(R.layout.advanced_battery_row, container, false);
            TextView labelView = rowView.findViewById(R.id.advanced_label);
            if (rows.get(i).labelRes != 0)
                labelView.setText(rows.get(i).labelRes);
            else
                labelView.setText(rows.get(i).labelText);
            ((TextView) rowView.findViewById(R.id.advanced_value)).setText(rows.get(i).value);
            container.addView(rowView);
        }
    }

    private void addRow(List<Row> rows, int labelRes, String value) {
        if (value != null)
            rows.add(new Row(labelRes, value));
    }

    private String formatInteger(Long value) {
        return value == null ? null : String.valueOf(value.longValue());
    }

    private String formatPercent(Integer value) {
        return value == null ? null : String.format(Locale.getDefault(), "%d%%", value);
    }

    private String formatMicroAmps(Long value) {
        if (value == null)
            return null;
        return String.format(Locale.getDefault(), "%.1f mA", value / 1000.0d);
    }

    private String formatMicroAmpHours(Long value) {
        if (value == null)
            return null;
        return String.format(Locale.getDefault(), "%.1f mAh", value / 1000.0d);
    }

    private String formatNanoWattHours(Long value) {
        if (value == null)
            return null;
        return String.format(Locale.getDefault(), "%.1f mWh", value / 1000000.0d);
    }

    private String formatMicroVolts(Long value) {
        if (value == null)
            return null;
        return String.format(Locale.getDefault(), "%.2f V", value / 1000000.0d);
    }

    private String formatHealth(Long fullChargeUah, Long designChargeUah) {
        if (fullChargeUah == null || designChargeUah == null || designChargeUah == 0L)
            return null;
        return String.format(Locale.getDefault(), "%.1f%%", (fullChargeUah * 100.0d) / designChargeUah);
    }

    private String formatChargeTimeRemaining(Long value) {
        if (value == null)
            return null;

        int roundedMinutes = (int) ((value + 30000L) / 60000L);
        int hours = roundedMinutes / 60;
        int minutes = roundedMinutes % 60;
        if (hours > 0)
            return Str.n_hours_m_minutes_long(hours, minutes);
        return Str.n_minutes_long(Math.max(minutes, 0));
    }

    private static class Row {
        final int labelRes;
        final String labelText;
        final String value;

        Row(int labelRes, String value) {
            this.labelRes = labelRes;
            this.labelText = null;
            this.value = value;
        }

        Row(String labelText, String value) {
            this.labelRes = 0;
            this.labelText = labelText;
            this.value = value;
        }
    }

    private class ShizukuConnection implements ServiceConnection {
        private final Shizuku.UserServiceArgs args = buildUserServiceArgs();

        @Override
        public void onServiceConnected(ComponentName name, final IBinder service) {
            new Thread(() -> {
                try {
                    AdvancedBatterySnapshot snapshot = AdvancedBatterySnapshot.fromBundle(AdvancedStatsUserService.requestSnapshot(service));
                    snapshot.shizukuVersion = Shizuku.getVersion();
                    postSnapshot(snapshot);
                } catch (Exception e) {
                    showNoAccessMessage();
                } finally {
                    try {
                        Shizuku.unbindUserService(args, ShizukuConnection.this, true);
                    } catch (Exception ignored) {
                    }
                }
            }).start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    }
}
