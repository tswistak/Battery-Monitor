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
import android.app.NotificationManager
import android.content.Context
import android.content.res.Resources
import android.database.Cursor
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.DisplayStrings
import codes.swistak.batterymonitor.common.NotificationSettingsNavigator
import codes.swistak.batterymonitor.common.showToast
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.settings.TemperatureUnit
import codes.swistak.batterymonitor.settings.temperatureUnit
import kotlin.math.roundToInt

class AlarmEditFragment : PreferenceFragmentCompat() {
    companion object {
        const val KEY_ENABLED: String = "enabled"
        const val KEY_TYPE: String = "type"
        const val KEY_THRESHOLD: String = "threshold"

        const val KEY_CHAN_DISABLED: String = "alarm_chan_disabled"
        const val KEY_CHAN_SETTINGS_B: String = "channel_settings_button"

        const val EXTRA_ALARM_ID: String = "codes.swistak.batterymonitor.AlarmID"

        private val chargeEntries = arrayOf<String?>(
            "5%",
            "10%",
            "15%",
            "20%",
            "25%",
            "30%",
            "35%",
            "40%",
            "45%",
            "50%",
            "55%",
            "60%",
            "65%",
            "70%",
            "75%",
            "80%",
            "85%",
            "90%",
            "95%",
            "99%"
        )
        private val chargeValues = arrayOf<String?>(
            "5",
            "10",
            "15",
            "20",
            "25",
            "30",
            "35",
            "40",
            "45",
            "50",
            "55",
            "60",
            "65",
            "70",
            "75",
            "80",
            "85",
            "90",
            "95",
            "99"
        )
    }

    private lateinit var res: Resources

    private var mPreferenceScreen: PreferenceScreen? = null
    private var alarms: AlarmDatabase? = null
    private var mCursor: Cursor? = null
    private var mAdapter: AlarmAdapter? = null
    private var mNotificationManager: NotificationManager? = null

    private var chanDisabled = false

    fun setScreen() {
        if (this::res.isInitialized) setPreferences()
    }

    private fun setPreferences() {
        setPreferencesFromResource(R.xml.alarm_pref_screen, null)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        res = resources
        alarms = AlarmDatabase(activity)

        mNotificationManager =
            requireActivity().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        mCursor = alarms!!.getAlarm(requireActivity().intent.getIntExtra(EXTRA_ALARM_ID, -1))
        mAdapter = AlarmAdapter()

        setPreferences()
        mPreferenceScreen = preferenceScreen
        mPreferenceScreen!!.findPreference<Preference?>(KEY_CHAN_DISABLED)!!.isVisible = false
    }

    override fun onDestroy() {
        super.onDestroy()
        mCursor!!.close()
        alarms!!.close()
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()

        mCursor!!.requery()
        mCursor!!.moveToFirst()
        mAdapter!!.requery()

        matchEnabled()
        syncValuesAndSetListeners()
    }

    @Suppress("DEPRECATION")
    override fun onPause() {
        super.onPause()
        mCursor!!.deactivate()
    }

    private fun syncValuesAndSetListeners() {
        val cb = mPreferenceScreen!!.findPreference<Preference?>(KEY_ENABLED) as CheckBoxPreference?
        cb!!.setChecked(mAdapter!!.enabled)
        cb.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, newValue ->
            mAdapter!!.updateEnabled((newValue as Boolean?)!!)
            true
        }

        var lp = mPreferenceScreen!!.findPreference<Preference?>(KEY_TYPE) as ListPreference?
        lp!!.setValue(mAdapter!!.type)
        updateSummary(lp)

        lp.onPreferenceChangeListener = object : Preference.OnPreferenceChangeListener {
            override fun onPreferenceChange(pref: Preference, newValue: Any?): Boolean {
                if (mAdapter!!.type == newValue) return false

                mAdapter!!.updateType(newValue as String?)

                (pref as ListPreference).setValue(newValue)
                updateSummary(pref)

                setUpThresholdList(true)

                matchEnabled()

                return false
            }
        }

        lp = mPreferenceScreen!!.findPreference<Preference?>(KEY_THRESHOLD) as ListPreference?
        setUpThresholdList(false)
        lp!!.setValue(mAdapter!!.threshold)
        updateSummary(lp)
        lp.onPreferenceChangeListener = object : Preference.OnPreferenceChangeListener {
            override fun onPreferenceChange(pref: Preference, newValue: Any?): Boolean {
                val value = newValue as String
                if (value == "custom") {
                    showCustomThresholdDialog((pref as ListPreference?)!!)
                    return false
                }

                if (mAdapter!!.threshold == value) return false

                mAdapter!!.updateThreshold(value)

                (pref as ListPreference).setValue(value)
                updateSummary(pref)

                return false
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showCustomThresholdDialog(lp: ListPreference) {
        val context = context ?: return

        val et = EditText(context)
        et.setInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)

        val currentVal = mAdapter!!.threshold
        val convertF = selectedTemperatureUnit(context).convertToFahrenheit

        val message: String?
        if (mAdapter!!.type!!.contains("temp")) {
            val unit = if (convertF) getString(R.string.fahrenheit) else getString(R.string.celsius)
            val template = getString(R.string.alarm_custom_temp_message)
            message = if (template.contains("%s")) template.replace("%s", unit)
            else ("$template ($unit)")
            try {
                val tenthsC = currentVal!!.toInt()
                if (convertF) {
                    et.setText((tenthsC * 9.0 / 50.0 + 32.0).roundToInt().toString())
                } else {
                    et.setText((tenthsC / 10).toString())
                }
            } catch (e: NumberFormatException) {
                et.setText(currentVal)
            }
        } else {
            message = getString(R.string.alarm_custom_charge_message)
            et.setText(currentVal)
        }

        val container = FrameLayout(context)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val margin = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics
        ).toInt()
        params.leftMargin = margin
        params.rightMargin = margin
        et.setLayoutParams(params)
        container.addView(et)

        AlertDialog.Builder(context).setTitle(lp.title).setMessage(message).setView(container)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val input = et.getText().toString()
                if (validateAndSaveThreshold(input, lp)) {
                    dialog.dismiss()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun validateAndSaveThreshold(input: String?, lp: ListPreference): Boolean {
        if (input.isNullOrEmpty()) return false
        val context = context ?: return false

        try {
            val thresholdValue = input.toInt()
            if (mAdapter!!.type!!.contains("charge")) {
                if (thresholdValue !in 0..100) return false
                mAdapter!!.updateThreshold(thresholdValue.toString())
            } else if (mAdapter!!.type!!.contains("temp")) {
                val convertF = selectedTemperatureUnit(context).convertToFahrenheit
                val tenthsC: Int = if (convertF) {
                    ((thresholdValue - 32) * 50.0 / 9.0).roundToInt()
                } else {
                    thresholdValue * 10
                }
                if (tenthsC < -500 || tenthsC > 1000) return false
                mAdapter!!.updateThreshold(tenthsC.toString())
            } else {
                mAdapter!!.updateThreshold(thresholdValue.toString())
            }

            lp.setValue(mAdapter!!.threshold)
            updateSummary(lp)
            return true
        } catch (e: NumberFormatException) {
            return false
        }
    }

    private fun matchEnabled() {
        val prefb = mPreferenceScreen!!.findPreference<Preference?>(KEY_CHAN_SETTINGS_B)

        if (chanDisabled) {
            var p = mPreferenceScreen!!.findPreference<Preference?>(KEY_ENABLED)
            p!!.isEnabled = false
            val lp =
                mPreferenceScreen!!.findPreference<Preference?>(KEY_THRESHOLD) as ListPreference?
            lp!!.isEnabled = false

            prefb!!.setSummary(R.string.alarm_chan_disabled_b)

            p = mPreferenceScreen!!.findPreference(KEY_CHAN_DISABLED)
            p!!.isVisible = true
        } else {
            var p = mPreferenceScreen!!.findPreference<Preference?>(KEY_ENABLED)
            p!!.isEnabled = true

            setUpThresholdList(false)

            prefb!!.setSummary(R.string.alarm_chan_settings_b)

            p = mPreferenceScreen!!.findPreference(KEY_CHAN_DISABLED)
            p!!.isVisible = false
        }
    }

    fun deleteAlarm() {
        alarms!!.deleteAlarm(mAdapter!!.id)
    }

    private fun updateSummary(lp: ListPreference) {
        lp.setSummary("%%")
        val formatterUsed: Boolean = lp.getSummary()!!.length != 2

        var entry = lp.getEntry() as String?
        if (entry == null) {
            val value = lp.value
            if (value != null && value != "custom") {
                entry = if (mAdapter!!.type!!.contains("charge")) {
                    "$value%"
                } else if (mAdapter!!.type!!.contains("temp")) {
                    val context = getContext()
                    val convertF =
                        context?.let(::selectedTemperatureUnit)?.convertToFahrenheit ?: false
                    try {
                        DisplayStrings.formatTemp(value.toInt(), convertF, false)
                    } catch (e: NumberFormatException) {
                        value
                    }
                } else {
                    value
                }
            }
        }

        if (entry == null) entry = ""
        if (formatterUsed) entry = entry.replace("%", "%%")

        if (lp.isEnabled) lp.setSummary(DisplayStrings.currentlySetTo + entry)
        else lp.setSummary(DisplayStrings.alarmPrefNotUsed)
    }

    private fun setUpThresholdList(resetValue: Boolean) {
        val lp = mPreferenceScreen!!.findPreference<Preference?>(KEY_THRESHOLD) as ListPreference?

        if (mAdapter!!.type == "temp_drops" || mAdapter!!.type == "temp_rises") {
            val convertF = selectedTemperatureUnit(requireContext()).convertToFahrenheit
            val entries = DisplayStrings.tempAlarmValues.map { value ->
                DisplayStrings.formatTemp(value.toInt(), convertF, false)
            }.plus(res.getString(R.string.custom)).toTypedArray()
            lp!!.entries = entries

            val values = arrayOfNulls<String>(DisplayStrings.tempAlarmValues.size + 1)
            System.arraycopy(
                DisplayStrings.tempAlarmValues, 0, values, 0, DisplayStrings.tempAlarmValues.size
            )
            values[values.size - 1] = "custom"
            lp.entryValues = values

            lp.isEnabled = true

            if (resetValue) {
                if (mAdapter!!.type == "temp_drops") mAdapter!!.updateThreshold("60")
                else mAdapter!!.updateThreshold("460")
                lp.setValue(mAdapter!!.threshold)
            }
        } else if (mAdapter!!.type == "charge_drops" || mAdapter!!.type == "charge_rises") {
            val entries = arrayOfNulls<String>(chargeEntries.size + 1)
            System.arraycopy(chargeEntries, 0, entries, 0, chargeEntries.size)
            entries[entries.size - 1] = res.getString(R.string.custom)
            lp!!.entries = entries

            val values = arrayOfNulls<String>(chargeValues.size + 1)
            System.arraycopy(chargeValues, 0, values, 0, chargeValues.size)
            values[values.size - 1] = "custom"
            lp.entryValues = values

            lp.isEnabled = true

            if (resetValue) {
                if (mAdapter!!.type == "charge_drops") mAdapter!!.updateThreshold("20")
                else mAdapter!!.updateThreshold("90")

                lp.setValue(mAdapter!!.threshold)
            }
        } else {
            lp!!.isEnabled = false
        }

        updateSummary(lp)
    }

    private fun selectedTemperatureUnit(context: Context): TemperatureUnit =
        context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
            .temperatureUnit(getString(R.string.default_temperature_unit))

    private fun isChannelDisabled(channelId: String?): Boolean {
        val channel = mNotificationManager!!.getNotificationChannel(channelId)
        return channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    private inner class AlarmAdapter {
        var id: Int = 0
        var type: String? = null
        var threshold: String? = null
        var enabled: Boolean = false

        init {
            requery()
        }

        fun requery() {
            id = mCursor!!.getInt(mCursor!!.getColumnIndexOrThrow(AlarmDatabase.KEY_ID))
            type = mCursor!!.getString(mCursor!!.getColumnIndexOrThrow(AlarmDatabase.KEY_TYPE))
            threshold =
                mCursor!!.getString(mCursor!!.getColumnIndexOrThrow(AlarmDatabase.KEY_THRESHOLD))
            enabled =
                (mCursor!!.getInt(mCursor!!.getColumnIndexOrThrow(AlarmDatabase.KEY_ENABLED)) == 1)

            chanDisabled = isChannelDisabled(type)
        }

        fun updateEnabled(b: Boolean) {
            enabled = b
            alarms!!.setEnabled(id, enabled)
        }

        fun updateType(s: String?) {
            type = s
            chanDisabled = isChannelDisabled(type)
            alarms!!.setType(id, type)
            if (type == "fully_charged" || type == "health_failure" || type == "charging_limit_met" || type == "discharging_limit_met") {
                updateThreshold("")
            }
        }

        fun updateThreshold(s: String?) {
            threshold = s
            alarms!!.setThreshold(id, threshold)
        }
    }

    fun enableNotifsButtonClick() {
        if (mAdapter!!.type == null) return
        val context = requireContext()
        if (!NotificationSettingsNavigator.openNotificationChannel(context, mAdapter!!.type!!)) {
            context.showToast(R.string.advanced_value_not_available)
        }
    }
}
