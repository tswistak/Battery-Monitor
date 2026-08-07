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
package codes.swistak.batterymonitor.advancedstats


import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.pm.PackageInfoCompat
import androidx.fragment.app.Fragment
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.common.RootExecutor
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsHelpActivity
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnBinderDeadListener
import rikka.shizuku.Shizuku.OnBinderReceivedListener
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener
import rikka.shizuku.Shizuku.UserServiceArgs
import java.util.Locale
import kotlin.math.max

class AdvancedInfoFragment : Fragment() {
    companion object {
        private const val REQUEST_CODE_SHIZUKU = 7001
        private const val TAG = "AdvancedInfoFragment"
    }

    private enum class LoadState {
        IDLE, CHECKING_ROOT, WAITING_FOR_SHIZUKU, WAITING_FOR_PERMISSION, BINDING_USER_SERVICE
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var loadState = LoadState.IDLE
    private var loadGeneration = 0
    private var activeConnection: ShizukuConnection? = null
    private var tabVisible = false

    private var statusView: TextView? = null
    private var countersSection: View? = null
    private var capacitySection: View? = null
    private var chargingSection: View? = null
    private var serviceSection: View? = null
    private var sysfsSection: View? = null
    private var metadataSection: View? = null
    private var counterRows: LinearLayout? = null
    private var capacityRows: LinearLayout? = null
    private var chargingRows: LinearLayout? = null
    private var serviceRows: LinearLayout? = null
    private var sysfsRows: LinearLayout? = null
    private var metadataRows: LinearLayout? = null

    private val binderReceivedListener: OnBinderReceivedListener = OnBinderReceivedListener {
        mainHandler.post {
            if (!isTabActive) return@post

            when (loadState) {
                LoadState.WAITING_FOR_SHIZUKU -> continueViaShizuku(loadGeneration)
                LoadState.IDLE -> refreshStats()
                else -> Unit
            }
        }
    }
    private val binderDeadListener: OnBinderDeadListener = OnBinderDeadListener {
        mainHandler.post {
            if (!isTabActive || loadState == LoadState.IDLE || loadState == LoadState.CHECKING_ROOT) {
                return@post
            }
            finishWithNoAccess("Shizuku binder died")
        }
    }
    private val permissionListener: OnRequestPermissionResultListener =
        object : OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE_SHIZUKU) return

                mainHandler.post {
                    if (loadState != LoadState.WAITING_FOR_PERMISSION) return@post

                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        if (isTabActive) {
                            bindUserService(loadGeneration)
                        } else {
                            loadState = LoadState.IDLE
                        }
                    } else {
                        finishWithNoAccess("Shizuku permission denied")
                    }
                }
            }
        }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tabVisible = userVisibleHint
        setHasOptionsMenu(true)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    override fun onDestroy() {
        super.onDestroy()

        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    override fun onDestroyView() {
        loadGeneration++
        cancelActiveConnection()
        loadState = LoadState.IDLE
        mainHandler.removeCallbacksAndMessages(null)
        clearViewReferences()
        super.onDestroyView()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.advanced_battery, container, false)

        statusView = view.findViewById(R.id.advanced_status)
        countersSection = view.findViewById(R.id.advanced_counter_section)
        capacitySection = view.findViewById(R.id.advanced_capacity_section)
        chargingSection = view.findViewById(R.id.advanced_charging_section)
        serviceSection = view.findViewById(R.id.advanced_service_section)
        sysfsSection = view.findViewById(R.id.advanced_sysfs_section)
        metadataSection = view.findViewById(R.id.advanced_metadata_section)
        counterRows = view.findViewById(R.id.advanced_counter_rows)
        capacityRows = view.findViewById(R.id.advanced_capacity_rows)
        chargingRows = view.findViewById(R.id.advanced_charging_rows)
        serviceRows = view.findViewById(R.id.advanced_service_rows)
        sysfsRows = view.findViewById(R.id.advanced_sysfs_rows)
        metadataRows = view.findViewById(R.id.advanced_metadata_rows)

        val refreshButton = view.findViewById<Button>(R.id.advanced_refresh)
        refreshButton.setOnClickListener { _: View? -> refreshStats() }

        return view
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun setUserVisibleHint(isVisibleToUser: Boolean) {
        super.setUserVisibleHint(isVisibleToUser)
        tabVisible = isVisibleToUser

        if (tabVisible && isResumed) refreshStats()
    }

    override fun onResume() {
        super.onResume()

        if (tabVisible) refreshStats()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.help_only, menu)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_help) {
            val comp = ComponentName(
                requireActivity().packageName, SettingsHelpActivity::class.java.getName()
            )
            val intent = Intent().setComponent(comp)
            intent.putExtra(
                SettingsContract.EXTRA_SCREEN, SettingsContract.KEY_ADVANCED_INFO_HELP
            )
            startActivity(intent)
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    private val isTabActive: Boolean
        get() = tabVisible && isResumed && view != null

    private fun refreshStats() {
        if (!isTabActive) return

        if (loadState == LoadState.WAITING_FOR_PERMISSION && Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindUserService(loadGeneration)
            return
        }
        if (loadState == LoadState.WAITING_FOR_SHIZUKU) {
            loadGeneration++
            loadState = LoadState.IDLE
        }
        if (loadState != LoadState.IDLE) return

        val appContext = context?.applicationContext ?: return
        val generation = ++loadGeneration
        loadState = LoadState.CHECKING_ROOT
        showStatus(R.string.advanced_status_loading)

        Thread {
            val rootSnapshot = AdvancedBatteryStatsCollector.collect(
                RootExecutor(), AdvancedBatterySnapshot.ACCESS_ROOT, 0, appContext, false
            )
            mainHandler.post {
                if (generation != loadGeneration || !isTabActive) return@post

                if (rootSnapshot.hasPrivilegedStats()) {
                    postSnapshot(rootSnapshot, generation)
                } else {
                    continueViaShizuku(generation)
                }
            }
        }.start()
    }

    private fun continueViaShizuku(generation: Int) {
        if (generation != loadGeneration || !isTabActive) return

        if (!Shizuku.pingBinder()) {
            loadState = LoadState.WAITING_FOR_SHIZUKU
            showStatus(R.string.advanced_status_no_access)
            return
        }
        if (Shizuku.isPreV11()) {
            finishWithNoAccess("Unsupported Shizuku version")
            return
        }

        when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                bindUserService(generation)
            }

            Shizuku.shouldShowRequestPermissionRationale() -> {
                finishWithNoAccess("Shizuku permission was previously denied")
            }

            else -> {
                loadState = LoadState.WAITING_FOR_PERMISSION
                showStatus(R.string.advanced_status_waiting_permission)
                try {
                    Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
                } catch (error: Throwable) {
                    finishWithNoAccess("Unable to request Shizuku permission", error)
                }
            }
        }
    }

    private fun bindUserService(generation: Int) {
        if (generation != loadGeneration || !isTabActive || activeConnection != null) return

        val appContext = context?.applicationContext ?: run {
            finishLoading(generation)
            return
        }
        val args = buildUserServiceArgs(appContext)
        val connection = ShizukuConnection(args, generation)
        activeConnection = connection
        loadState = LoadState.BINDING_USER_SERVICE

        try {
            Shizuku.bindUserService(args, connection)
        } catch (error: Throwable) {
            clearConnection(connection)
            finishWithNoAccess("Unable to bind Shizuku user service", error)
        }
    }

    private fun buildUserServiceArgs(context: Context): UserServiceArgs {
        return UserServiceArgs(
            ComponentName(context.packageName, AdvancedStatsUserService::class.java.getName())
        ).daemon(false).processNameSuffix("advanced_stats")
            .debuggable((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            .version(installedVersionCode(context)).tag("advanced_battery_stats")
    }

    private fun installedVersionCode(context: Context): Int {
        return try {
            PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0)
            ).toInt()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to read installed version code", error)
            1
        }
    }

    private fun postSnapshot(snapshot: AdvancedBatterySnapshot, generation: Int) {
        mainHandler.post {
            if (generation != loadGeneration) return@post

            activeConnection?.let(::clearConnection)
            loadState = LoadState.IDLE
            if (!isTabActive) return@post

            if (!snapshot.hasStats()) {
                showStatus(R.string.advanced_status_no_stats)
                return@post
            }

            showStatus(0)
            renderSnapshot(snapshot)
        }
    }

    private fun finishLoading(generation: Int) {
        if (generation == loadGeneration) {
            activeConnection?.let(::clearConnection)
            loadState = LoadState.IDLE
        }
    }

    private fun finishWithNoAccess(message: String, error: Throwable? = null) {
        if (error == null) Log.w(TAG, message) else Log.e(TAG, message, error)
        loadGeneration++
        cancelActiveConnection()
        loadState = LoadState.IDLE
        if (isTabActive) showStatus(R.string.advanced_status_no_access)
    }

    private fun clearConnection(connection: ShizukuConnection) {
        if (activeConnection === connection) activeConnection = null
    }

    private fun cancelActiveConnection() {
        val connection = activeConnection ?: return
        activeConnection = null
        try {
            Shizuku.unbindUserService(connection.args, connection, true)
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to unbind Shizuku user service", error)
        }
    }

    private fun renderSnapshot(snapshot: AdvancedBatterySnapshot) {
        clearRows()

        var rows: MutableList<Row?> = ArrayList()
        addRow(
            rows,
            R.string.advanced_field_charge_counter,
            formatMicroAmpHours(snapshot.chargeCounterUah)
        )
        addRow(rows, R.string.advanced_field_current_now, formatMicroAmps(snapshot.currentNowUa))
        addRow(
            rows,
            R.string.advanced_field_current_average,
            formatMicroAmps(snapshot.currentAverageUa)
        )
        addRow(
            rows,
            R.string.advanced_field_energy_counter,
            formatNanoWattHours(snapshot.energyCounterNwh)
        )
        addRow(rows, R.string.advanced_field_cycle_count, formatInteger(snapshot.cycleCount))
        addRows(countersSection!!, counterRows!!, rows)

        rows = ArrayList()
        addRow(
            rows,
            R.string.advanced_field_reported_capacity,
            formatPercent(snapshot.reportedCapacityPercent)
        )
        addRow(
            rows,
            R.string.advanced_field_state_of_health,
            formatPercent(snapshot.stateOfHealthPercent)
        )
        addRow(
            rows,
            R.string.advanced_field_full_charge_capacity,
            formatMicroAmpHours(snapshot.fullChargeUah)
        )
        addRow(
            rows,
            R.string.advanced_field_design_capacity,
            formatMicroAmpHours(snapshot.designChargeUah)
        )
        addRow(
            rows,
            R.string.advanced_field_estimated_health,
            formatHealth(snapshot.fullChargeUah, snapshot.designChargeUah)
        )
        addRows(capacitySection!!, capacityRows!!, rows)

        rows = ArrayList()
        addRow(
            rows,
            R.string.advanced_field_charge_time_remaining,
            formatChargeTimeRemaining(snapshot.chargeTimeRemainingMs)
        )
        addRow(
            rows,
            R.string.advanced_field_max_charging_current,
            formatMicroAmps(snapshot.maxChargingCurrentUa)
        )
        addRow(
            rows,
            R.string.advanced_field_max_charging_voltage,
            formatMicroVolts(snapshot.maxChargingVoltageUv)
        )
        addRow(rows, R.string.advanced_field_charging_policy, snapshot.chargingPolicy)
        addRow(rows, R.string.advanced_field_charging_state, snapshot.chargingState)
        addRow(rows, R.string.advanced_field_capacity_level, snapshot.capacityLevel)
        addRows(chargingSection!!, chargingRows!!, rows)

        rows = ArrayList()
        run {
            var i = 0
            while (i < snapshot.serviceLabels.size && i < snapshot.serviceValues.size) {
                rows.add(Row(snapshot.serviceLabels[i], snapshot.serviceValues[i]))
                i++
            }
        }
        addRows(serviceSection!!, serviceRows!!, rows)

        rows = ArrayList()
        run {
            var i = 0
            while (i < snapshot.sysfsLabels.size && i < snapshot.sysfsValues.size) {
                rows.add(Row(snapshot.sysfsLabels[i], snapshot.sysfsValues[i]))
                i++
            }
        }
        addRows(sysfsSection!!, sysfsRows!!, rows)

        rows = ArrayList()
        var i = 0
        while (i < snapshot.metadataLabels.size && i < snapshot.metadataValues.size) {
            rows.add(Row(snapshot.metadataLabels[i], snapshot.metadataValues[i]))
            i++
        }
        addRows(metadataSection!!, metadataRows!!, rows)
    }

    private fun showStatus(stringRes: Int) {
        if (stringRes == 0) {
            statusView!!.visibility = View.GONE
            return
        }

        statusView!!.visibility = View.VISIBLE
        statusView!!.setText(stringRes)
    }

    private fun clearViewReferences() {
        statusView = null
        countersSection = null
        capacitySection = null
        chargingSection = null
        serviceSection = null
        sysfsSection = null
        metadataSection = null
        counterRows = null
        capacityRows = null
        chargingRows = null
        serviceRows = null
        sysfsRows = null
        metadataRows = null
    }

    private fun clearRows() {
        counterRows!!.removeAllViews()
        capacityRows!!.removeAllViews()
        chargingRows!!.removeAllViews()
        serviceRows!!.removeAllViews()
        sysfsRows!!.removeAllViews()
        metadataRows!!.removeAllViews()
        countersSection!!.visibility = View.GONE
        capacitySection!!.visibility = View.GONE
        chargingSection!!.visibility = View.GONE
        serviceSection!!.visibility = View.GONE
        sysfsSection!!.visibility = View.GONE
        metadataSection!!.visibility = View.GONE
    }

    private fun addRows(section: View, container: LinearLayout, rows: MutableList<Row?>) {
        if (rows.isEmpty()) return

        section.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(activity)

        for (i in rows.indices) {
            val rowView = inflater.inflate(R.layout.advanced_battery_row, container, false)
            val labelView = rowView.findViewById<TextView>(R.id.advanced_label)
            if (rows[i]!!.labelRes != 0) labelView.setText(rows[i]!!.labelRes)
            else labelView.text = rows[i]!!.labelText
            (rowView.findViewById<View?>(R.id.advanced_value) as TextView).text = rows[i]!!.value
            container.addView(rowView)
        }
    }

    private fun addRow(rows: MutableList<Row?>, labelRes: Int, value: String?) {
        if (value != null) rows.add(Row(labelRes, value))
    }

    private fun formatInteger(value: Long?): String? {
        return value?.toString()
    }

    private fun formatPercent(value: Int?): String? {
        return if (value == null) null else String.format(Locale.getDefault(), "%d%%", value)
    }

    private fun formatMicroAmps(value: Long?): String? {
        if (value == null) return null
        return String.format(Locale.getDefault(), "%.1f mA", value / 1000.0)
    }

    private fun formatMicroAmpHours(value: Long?): String? {
        if (value == null) return null
        return getString(
            R.string.remaining_charge_value, DisplayStrings.formatChargeDetailed(value)
        )
    }

    private fun formatNanoWattHours(value: Long?): String? {
        if (value == null) return null
        return String.format(Locale.getDefault(), "%.1f mWh", value / 1000000.0)
    }

    private fun formatMicroVolts(value: Long?): String? {
        if (value == null) return null
        return String.format(Locale.getDefault(), "%.2f V", value / 1000000.0)
    }

    private fun formatHealth(fullChargeUah: Long?, designChargeUah: Long?): String? {
        if (fullChargeUah == null || designChargeUah == null || designChargeUah == 0L) return null
        return String.format(
            Locale.getDefault(), "%.1f%%", (fullChargeUah * 100.0) / designChargeUah
        )
    }

    private fun formatChargeTimeRemaining(value: Long?): String? {
        if (value == null) return null

        val roundedMinutes = ((value + 30000L) / 60000L).toInt()
        val hours = roundedMinutes / 60
        val minutes = roundedMinutes % 60
        if (hours > 0) return DisplayStrings.nHoursMMinutesLong(hours, minutes)
        return DisplayStrings.nMinutesLong(max(minutes, 0))
    }

    private class Row {
        val labelRes: Int
        val labelText: String?
        val value: String?

        constructor(labelRes: Int, value: String?) {
            this.labelRes = labelRes
            this.labelText = null
            this.value = value
        }

        constructor(labelText: String?, value: String?) {
            this.labelRes = 0
            this.labelText = labelText
            this.value = value
        }
    }

    private inner class ShizukuConnection(
        val args: UserServiceArgs, private val generation: Int
    ) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            Thread {
                try {
                    val snapshot: AdvancedBatterySnapshot = AdvancedBatterySnapshot.fromBundle(
                        requireNotNull(
                            AdvancedStatsUserService.requestSnapshot(
                                service
                            )
                        )
                    )
                    snapshot.shizukuVersion = Shizuku.getVersion()
                    postSnapshot(snapshot, generation)
                } catch (error: Throwable) {
                    mainHandler.post {
                        if (generation == loadGeneration) {
                            finishWithNoAccess("Unable to retrieve Shizuku battery stats", error)
                        }
                    }
                } finally {
                    try {
                        Shizuku.unbindUserService(args, this@ShizukuConnection, true)
                    } catch (error: Throwable) {
                        Log.w(TAG, "Unable to unbind Shizuku user service", error)
                    }
                }
            }.start()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mainHandler.post {
                if (generation == loadGeneration && activeConnection === this@ShizukuConnection) {
                    finishWithNoAccess("Shizuku user service disconnected")
                }
            }
        }
    }
}
