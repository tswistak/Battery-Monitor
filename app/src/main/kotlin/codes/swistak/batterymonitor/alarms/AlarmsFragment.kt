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
package codes.swistak.batterymonitor.alarms


import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.DataSetObserver
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.app.PersistentFragment
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.SettingsHelpActivity
import codes.swistak.batterymonitor.settings.temperatureUnit

class AlarmsFragment : Fragment() {
    companion object {
        private var pfrag: PersistentFragment? = null
    }

    private var alarms: AlarmDatabase? = null
    private var mCursor: Cursor? = null
    private var mInflater: LayoutInflater? = null
    private var mAlarmsList: LinearLayout? = null
    private var convertF = false
    private var curId = 0
    private var curIndex = 0
    private var mNotificationManager: NotificationManager? = null
    private var alarmChanGroup: NotificationChannelGroup? = null
    private var appNotifsEnabled = false
    private var alarmNotifsEnabled = false
    private val chanDisabled: MutableMap<String, Boolean> = HashMap()

    private fun getAlarmChanGroup(): NotificationChannelGroup? {
        if (Build.VERSION.SDK_INT >= 28) return mNotificationManager!!.getNotificationChannelGroup(
            BatteryInfoService.CHAN_GROUP_ID_ALARMS
        )

        val groups = mNotificationManager!!.getNotificationChannelGroups()
        return if (groups.isNotEmpty()) groups[0]
        else null
    }

    private fun getAlarmNotifsEnabled(): Boolean {
        return Build.VERSION.SDK_INT < 28 || alarmChanGroup == null || !alarmChanGroup!!.isBlocked
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        super.onCreateView(inflater, container, savedInstanceState)

        mNotificationManager =
            requireActivity().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        alarmChanGroup = getAlarmChanGroup()

        appNotifsEnabled = mNotificationManager!!.areNotificationsEnabled()
        alarmNotifsEnabled = getAlarmNotifsEnabled()

        if (!appNotifsEnabled || !alarmNotifsEnabled) {
            mInflater = inflater
            val view = mInflater!!.inflate(R.layout.alarms_no_notifs, container, false)

            val b = view.findViewById<Button>(R.id.enable_notifs_button)
            val tv = view.findViewById<TextView>(R.id.enable_notifs_summary)

            if (!appNotifsEnabled) {
                b.setText(R.string.app_notifs_disabled_b)
                tv.setText(R.string.app_notifs_alarms_disabled_summary)
            } else {
                b.setText(R.string.alarm_notifs_disabled_b)
                tv.setText(R.string.alarm_notifs_disabled_summary)
            }

            b.setOnClickListener {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, requireActivity().packageName)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                startActivity(intent)
            }

            return view
        }

        mInflater = inflater
        val view = mInflater!!.inflate(R.layout.alarms, container, false)

        mAlarmsList = view.findViewById<View?>(R.id.alarms_list) as LinearLayout?

        if (mCursor == null) {
            val addAlarmTv = view.findViewById<View?>(R.id.add_alarm_tv) as TextView
            addAlarmTv.text = "Database error!"
            return view
        }

        requireNotNull(view.findViewById<View?>(R.id.add_alarm)).setOnClickListener {
            val id = alarms!!.addAlarm()
            if (id < 0) {
                Toast.makeText(activity, "Error!", Toast.LENGTH_SHORT).show()
            }
            val comp = ComponentName(
                requireActivity().packageName, AlarmEditActivity::class.java.getName()
            )
            startActivity(
                Intent().setComponent(comp).putExtra(AlarmEditFragment.EXTRA_ALARM_ID, id)
            )
        }

        return view
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)

        alarms = AlarmDatabase(requireActivity().applicationContext)
        mCursor = alarms!!.getAllAlarms(true)

        if (mCursor != null) mCursor!!.registerDataSetObserver(AlarmsObserver())

        pfrag = PersistentFragment.getInstance(parentFragmentManager)
        convertF = pfrag!!.settings.temperatureUnit(
            pfrag!!.res.getString(R.string.default_temperature_unit)
        ).convertToFahrenheit
    }

    private fun populateList() {
        if (!appNotifsEnabled || !alarmNotifsEnabled) return

        mAlarmsList!!.removeAllViews()

        if (mCursor != null && mCursor!!.moveToFirst()) {
            while (!mCursor!!.isAfterLast) {
                val v = mInflater!!.inflate(R.layout.alarm_item, mAlarmsList, false)
                bindView(v)
                mAlarmsList!!.addView(v, mAlarmsList!!.childCount)
                mCursor!!.moveToNext()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (mCursor != null) mCursor!!.close()
        alarms!!.close()
        if (mAlarmsList != null) mAlarmsList!!.removeAllViews()
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()

        alarmChanGroup = getAlarmChanGroup()

        if (appNotifsEnabled != mNotificationManager!!.areNotificationsEnabled() || alarmNotifsEnabled != getAlarmNotifsEnabled()) {
            val intent = Intent(
                activity, BatteryInfoActivity::class.java
            ).putExtra(BatteryInfoService.EXTRA_EDIT_ALARMS, true)
            startActivity(intent)
            requireActivity().finish()
            return
        }

        var chan: NotificationChannel?
        for (chanId in BatteryInfoService.ALARM_CHAN_IDS) {
            chan = mNotificationManager!!.getNotificationChannel(chanId)
            chanDisabled[chanId] =
                chan == null || chan.importance == NotificationManager.IMPORTANCE_NONE
        }

        convertF = pfrag!!.settings.temperatureUnit(
            pfrag!!.res.getString(R.string.default_temperature_unit)
        ).convertToFahrenheit

        if (mCursor != null) mCursor!!.requery()
    }

    @Suppress("DEPRECATION")
    override fun onPause() {
        super.onPause()
        if (mCursor != null) mCursor!!.deactivate()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        inflater.inflate(R.menu.alarms, menu)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val intent: Intent?
        if (item.itemId == R.id.menu_help) {
            val comp = ComponentName(
                requireActivity().packageName, SettingsHelpActivity::class.java.getName()
            )
            intent = Intent().setComponent(comp).putExtra(
                SettingsContract.EXTRA_SCREEN, SettingsContract.KEY_ALARMS_SETTINGS
            )
            startActivity(intent)

            return true
        }

        return super.onOptionsItemSelected(item)
    }

    @Suppress("DEPRECATION")
    override fun onContextItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.delete_alarm) {
            alarms!!.deleteAlarm(curId)
            if (mCursor != null) mCursor!!.requery()

            val childCount = mAlarmsList!!.childCount
            if (curIndex < childCount) requireNotNull(
                mAlarmsList!!.getChildAt(curIndex).findViewById<View?>(R.id.alarm_summary_box)
            ).requestFocus()
            else if (childCount > 0) requireNotNull(
                mAlarmsList!!.getChildAt(curIndex - 1).findViewById<View?>(R.id.alarm_summary_box)
            ).requestFocus()

            return true
        }

        return super.onContextItemSelected(item)
    }

    private inner class AlarmsObserver : DataSetObserver() {
        override fun onChanged() {
            super.onChanged()
            populateList()
        }
    }

    private fun bindView(view: View) {
        val summaryTv = view.findViewById<View?>(R.id.alarm_summary) as TextView
        val summaryBox = view.findViewById<View>(R.id.alarm_summary_box)
        val toggle = view.findViewById<View?>(R.id.toggle) as CompoundButton

        val id = mCursor!!.getInt(mCursor!!.getColumnIndexOrThrow(AlarmDatabase.KEY_ID))
        val type = mCursor!!.getString(mCursor!!.getColumnIndexOrThrow(AlarmDatabase.KEY_TYPE))
        val threshold =
            mCursor!!.getString(mCursor!!.getColumnIndexOrThrow(AlarmDatabase.KEY_THRESHOLD))
        val enabled =
            (mCursor!!.getInt(mCursor!!.getColumnIndexOrThrow(AlarmDatabase.KEY_ENABLED)) == 1)

        var s = DisplayStrings.alarmTypesDisplay[DisplayStrings.indexOf(
            DisplayStrings.alarmTypeValues, type
        )]
        if (type == "temp_drops" || type == "temp_rises") {
            s += " " + DisplayStrings.formatTemp(threshold.toInt(), convertF, false)
        } else if (type == "charge_drops" || type == "charge_rises") {
            s += " $threshold%"
        }
        val summary = s

        summaryTv.text = summary

        toggle.setChecked(enabled)

        toggle.setOnCheckedChangeListener { _, isChecked ->
            alarms!!.setEnabled(
                id, isChecked
            )
        }

        summaryBox.setOnCreateContextMenuListener { menu, v, _ ->
            curId = id
            curIndex = mAlarmsList!!.indexOfChild(v.parent.parent as View?)

            requireActivity().getMenuInflater().inflate(R.menu.alarm_item_context, menu)
            menu.setHeaderTitle(summary)
        }

        summaryBox.setOnKeyListener { v, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_DOWN) v.setPressed(
                true
            )

            false
        }

        summaryBox.setOnClickListener {
            val comp = ComponentName(
                requireActivity().packageName, AlarmEditActivity::class.java.getName()
            )
            startActivity(
                Intent().setComponent(comp).putExtra(AlarmEditFragment.EXTRA_ALARM_ID, id)
            )
        }

        if (chanDisabled[type] == true) toggle.setEnabled(false)
    }
}
