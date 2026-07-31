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
package codes.swistak.batterymonitor

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment

class CurrentInfoFragment : Fragment() {
    companion object {
        private var pFrag: PersistentFragment? = null
        private val batteryUseIntent =
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        private val batteryChangedFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

        var awaitingNotificationUnblock: Boolean = false
        var showingNotificationBlockDialog: Boolean = false

        var awaitingUnoptimization: Boolean = false
        var showingBatteryOptimizedDialog: Boolean = false
    }

    private var dpScale = 0f

    private lateinit var rootView: View
    private var batteryUseB: Button? = null
    private var bl: BatteryLevel? = null
    private var blv: ImageView? = null
    private var currentIcon: View? = null
    private var tvTemp: TextView? = null
    private var tvHealth: TextView? = null
    private var tvVoltage: TextView? = null
    private var tvCurrent: TextView? = null
    private var pluggedIcon: ImageView? = null

    private val info = BatteryInfo()
    private val mHandler = Handler(Looper.getMainLooper())
    private val mARefresher: Runnable = Runnable {
        refreshCurrent()
        mHandler.postDelayed(mARefresher, batteryCurrentRefreshIntervalMillis())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setSizes(newConfig)
    }

    @Suppress("DEPRECATION")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        rootView = inflater.inflate(R.layout.current_info, container, false)

        bl = BatteryLevel.getLargeInstance(requireContext())
        blv = rootView.findViewById<View?>(R.id.battery_level_view) as ImageView
        blv!!.setImageBitmap(bl!!.getBitmap())

        batteryUseB = rootView.findViewById<View?>(R.id.battery_use_b) as Button

        requireNotNull(rootView.findViewById(R.id.vital_stats)).setOnClickListener(vsListener)
        currentIcon = rootView.findViewById(R.id.current_icon)

        tvTemp = rootView.findViewById<View?>(R.id.temp) as TextView
        tvHealth = rootView.findViewById<View?>(R.id.health) as TextView
        tvVoltage = rootView.findViewById<View?>(R.id.voltage) as TextView
        tvCurrent = rootView.findViewById<View?>(R.id.current) as TextView
        pluggedIcon = rootView.findViewById<View?>(R.id.plugged_icon) as ImageView

        bindButtons()

        setSizes(requireActivity().resources.configuration)

        if (!NotificationManagerCompat.from(requireActivity())
                .areNotificationsEnabled() && !showingNotificationBlockDialog
        ) {
            showingNotificationBlockDialog = true
            val df: DialogFragment = NotificationsDisabledDialogFragment()
            df.setTargetFragment(this, 0)
            df.show(parentFragmentManager, "TODO: What is this string for?3")
        }

        val pm = requireActivity().getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = requireActivity().packageName
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !showingBatteryOptimizedDialog) {
            showingBatteryOptimizedDialog = true
            val df: DialogFragment = BatteryOptimizedDialogFragment()
            df.setTargetFragment(this, 0)
            df.show(parentFragmentManager, "TODO: What is this string for?4")
        }

        return rootView
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pFrag = PersistentFragment.getInstance(parentFragmentManager)

        dpScale = requireActivity().resources.displayMetrics.density

        BatteryCurrent.setContext(requireContext())

        setHasOptionsMenu(true)
    }

    override fun onResume() {
        super.onResume()

        BatteryCurrent.setUsePrivilegedAccess(
            pFrag!!.settings.getBoolean(
                SettingsFragment.KEY_USE_PRIVILEGED_BATTERY_CURRENT, false
            )
        )
        BatteryCurrent.setMultiplier(
            pFrag!!.settings.getString(
                SettingsFragment.KEY_BATTERY_CURRENT_MULTIPLIER, "1"
            )!!.toInt()
        )
    }

    override fun onStart() {
        super.onStart()

        pFrag!!.setCIF(this)
        pFrag!!.sendServiceMessage(BatteryInfoService.RemoteConnection.SERVICE_REGISTER_CLIENT)

        if (awaitingNotificationUnblock) {
            awaitingNotificationUnblock = false
            pFrag!!.sendServiceMessage(BatteryInfoService.RemoteConnection.SERVICE_CANCEL_NOTIFICATION_AND_RELOAD_SETTINGS)
        }

        val bcIntent = requireActivity().registerReceiver(null, batteryChangedFilter)
        info.load(bcIntent)
        info.load(pFrag!!.spService)
        handleUpdatedBatteryInfo()

        if (pFrag!!.settings.getBoolean(
                SettingsFragment.KEY_ENABLE_BATTERY_CURRENT, false
            )
        ) mHandler.postDelayed(mARefresher, batteryCurrentRefreshIntervalMillis())
    }

    override fun onStop() {
        super.onStop()

        mHandler.removeCallbacks(mARefresher)
        pFrag!!.setCIF(null)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        inflater.inflate(R.menu.main, menu)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_settings) {
            mStartActivity(SettingsActivity::class.java)

            return true
        }

        if (item.itemId == R.id.menu_close) {
            val df: DialogFragment = ConfirmCloseDialogFragment()
            // Setting target to this leaks the Fragment, but that's sort of good, as it allows pressing Okay
            //  to work even if the screen rotates.  Even if it rotates many times back and forth, only the
            //  first Fragment is leaked, which will do the closing if Okay is pressed.  Once the dialog is
            //  gone (even if canceled), then the it and the leaked Fragment will be garbage collected.
            df.setTargetFragment(this, 0)
            df.show(parentFragmentManager, "TODO: What is this string for?2")

            return true
        }

        if (item.itemId == R.id.menu_help) {
            mStartActivity(HelpActivity::class.java)

            return true
        }

        return super.onOptionsItemSelected(item)
    }

    class NotificationsDisabledDialogFragment : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            return AlertDialog.Builder(activity)
                .setTitle(pFrag!!.res.getString(R.string.notifications_disabled))
                .setMessage(pFrag!!.res.getString(R.string.notifications_disabled_message))
                .setPositiveButton(
                    pFrag!!.res.getString(android.R.string.ok)
                ) { di, _ ->
                    val i = Intent()
                    i.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    i.putExtra(
                        Settings.EXTRA_APP_PACKAGE, requireActivity().packageName
                    )
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    i.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    startActivity(i)

                    awaitingNotificationUnblock = true
                    showingNotificationBlockDialog = false

                    di.cancel()
                }.setNegativeButton(
                    pFrag!!.res.getString(R.string.cancel)
                ) { di, _ ->
                    awaitingNotificationUnblock = false
                    showingNotificationBlockDialog = false

                    di.cancel()
                }.create()
        }
    }

    @SuppressLint("BatteryLife")
    class BatteryOptimizedDialogFragment : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            return AlertDialog.Builder(activity)
                .setTitle(pFrag!!.res.getString(R.string.battery_optimized))
                .setMessage(pFrag!!.res.getString(R.string.battery_optimized_message))
                .setPositiveButton(
                    pFrag!!.res.getString(android.R.string.ok)
                ) { di, id ->
                    val i = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        ("package:" + requireActivity().packageName).toUri()
                    )
                    try {
                        startActivity(i)
                    } catch (e: ActivityNotFoundException) {
                        // Some devices don't support that request, and this is better than nothing
                        val i2 = Intent()
                        i2.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        i2.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                        try {
                            startActivity(i2)
                        } catch (e2: ActivityNotFoundException) {
                            // And on truly broken devices, I guess just opening root of system settings is worth doing?
                            // Inspired by https://github.com/kontalk/androidclient/commit/be78119687940545d3613ae0d4280f4068125f6a
                            val i3 = Intent()
                            i3.setAction(Settings.ACTION_SETTINGS)
                            i3.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            i3.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                            startActivity(i3)
                        }
                    }

                    awaitingUnoptimization = true
                    showingBatteryOptimizedDialog = false

                    di.cancel()
                }.setNegativeButton(
                    pFrag!!.res.getString(R.string.cancel)
                ) { di, id ->
                    awaitingUnoptimization = false
                    showingBatteryOptimizedDialog = false

                    di.cancel()
                }.create()
        }
    }

    class ConfirmCloseDialogFragment : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            return AlertDialog.Builder(activity)
                .setTitle(pFrag!!.res.getString(R.string.confirm_close))
                .setMessage(pFrag!!.res.getString(R.string.confirm_close_hint)).setPositiveButton(
                    pFrag!!.res.getString(R.string.yes)
                ) { di, id ->
                    pFrag!!.closeApp()
                    di.cancel()
                }.setNegativeButton(
                    pFrag!!.res.getString(R.string.cancel)
                ) { di, id -> di.cancel() }.create()
        }
    }

    fun batteryInfoUpdated(bundle: Bundle) {
        info.loadBundle(bundle)
        handleUpdatedBatteryInfo()
    }

    @SuppressLint("SetTextI18n")
    private fun handleUpdatedBatteryInfo() {
        bl!!.setLevel(info.percent)
        bl!!.setColor(Str.accentColor)
        blv!!.invalidate()

        var tv = rootView.findViewById<View?>(R.id.level) as TextView
        tv.text = "" + info.percent + pFrag!!.res.getString(R.string.percent_symbol)

        tv = rootView.findViewById<View?>(R.id.time_remaining) as TextView
        tv.text = Str.timeRemainingMainScreen(info)
        tv = rootView.findViewById<View?>(R.id.until_what) as TextView
        tv.text = Str.untilWhat(info)

        val secs = ((System.currentTimeMillis() - info.lastStatusCtm) / 1000).toInt()
        val hours = secs / (60 * 60)
        val mins = (secs / 60) % 60

        var s = Str.statuses[info.lastStatus]

        if (info.lastStatus == BatteryInfo.STATUS_CHARGING) s += " " + Str.pluggeds[info.lastPlugged]

        tv = rootView.findViewById<View?>(R.id.status) as TextView
        tv.text = s

        if (info.lastPercent >= 0) {
            s = Str.since + " "

            if (info.lastStatus != BatteryInfo.STATUS_FULLY_CHARGED) s += info.lastPercent.toString() + Str.percentSymbol + ", "

            s += Str.nHoursMMinutesShort(hours, mins)

            tv = rootView.findViewById<View?>(R.id.status_duration) as TextView
            tv.text = s
        }

        val convertF: Boolean = pFrag!!.settings.getBoolean(
            SettingsFragment.KEY_CONVERT_F,
            pFrag!!.res.getBoolean(R.bool.default_convert_to_fahrenheit)
        )

        tvHealth!!.text = Str.healths[info.health]
        tvTemp!!.text = Str.formatTemp(info.temperature, convertF)
        if (info.voltage > 500) tvVoltage!!.text = Str.formatVoltage(info.voltage)

        if (info.lastStatus == BatteryInfo.STATUS_UNPLUGGED) pluggedIcon!!.setImageResource(
            R.drawable.unplugged
        )
        else pluggedIcon!!.setImageResource(R.drawable.not_unplugged)

        refreshCurrent()
    }

    private fun refreshCurrent() {
        var s = ""

        if (pFrag!!.settings.getBoolean(
                SettingsFragment.KEY_ENABLE_BATTERY_CURRENT, false
            )
        ) {
            currentIcon!!.visibility = View.VISIBLE

            var current: Double? = null

            if (pFrag!!.settings.getBoolean(
                    SettingsFragment.KEY_PREFER_AVERAGE_BATTERY_CURRENT, false
                )
            ) current = BatteryCurrent.avgCurrent
            if (current == null) current = BatteryCurrent.current
            if (current != null) s += BatteryCurrent.formatMilliAmps(current) + "mA"
        } else {
            currentIcon!!.visibility = View.INVISIBLE
        }

        tvCurrent!!.text = s
    }

    private fun batteryCurrentRefreshIntervalMillis(): Long {
        val seconds = pFrag!!.settings.getString(
            SettingsFragment.KEY_BATTERY_CURRENT_REFRESH_INTERVAL, "2"
        )?.toLongOrNull()?.coerceIn(1L, 3600L) ?: 2L
        return seconds * 1000L
    }

    /* mA TextView */
    private val vsListener: View.OnClickListener = View.OnClickListener { refreshCurrent() }

    /* Battery Use */
    private val buButtonListener: View.OnClickListener = View.OnClickListener {
        try {
            startActivity(batteryUseIntent)
        } catch (e: Exception) {
            batteryUseB!!.setEnabled(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun mStartActivity(c: Class<*>) {
        val comp = ComponentName(requireActivity().packageName, c.getName())
        startActivityForResult(Intent().setComponent(comp), 1)
    }

    private fun bindButtons() {
        if (requireActivity().packageManager.resolveActivity(batteryUseIntent, 0) == null) {
            batteryUseB!!.setEnabled(false)
        } else {
            batteryUseB!!.setOnClickListener(buButtonListener)
        }
    }

    // Sets sizes of most Views based on current dimensions
    // Must be called from onCreateView() after inflation and from onConfigurationChanged()
    private fun setSizes(config: Configuration) {
        val portrait = config.orientation == Configuration.ORIENTATION_PORTRAIT

        val screenWidth = (config.screenWidthDp * dpScale).toInt()
        val screenHeight = (config.screenHeightDp * dpScale).toInt()

        val aspectRatio = screenWidth.toFloat() / screenHeight

        val pluggedIconHeight: Int
        val timeRemainingTextHeight: Int
        val untilWhatTextHeight: Int
        val statusTextHeight: Int
        val buHeight: Int
        val buTextHeight: Int
        val vitalIconHeight: Int
        val vitalTextHeight: Int

        if (portrait) {
            if (aspectRatio > 0.53) {
                pluggedIconHeight = (screenHeight * 0.075).toInt()

                timeRemainingTextHeight = (screenHeight * 0.044).toInt()
                untilWhatTextHeight = (screenHeight * 0.028).toInt()

                statusTextHeight = (screenHeight * 0.035).toInt()

                buHeight = (screenHeight * 0.14).toInt()
                buTextHeight = (screenHeight * 0.035).toInt()

                vitalIconHeight = (screenHeight * 0.05).toInt()
                vitalTextHeight = (screenHeight * 0.03).toInt()
            } else {
                pluggedIconHeight = (screenWidth * 0.16).toInt()

                timeRemainingTextHeight = (screenWidth * 0.075).toInt()
                untilWhatTextHeight = (screenWidth * 0.05).toInt()

                statusTextHeight = (screenWidth * 0.065).toInt()

                buHeight = (screenWidth * 0.22).toInt()
                buTextHeight = (screenWidth * 0.055).toInt()

                vitalIconHeight = (screenWidth * 0.085).toInt()
                vitalTextHeight = (screenWidth * 0.055).toInt()
            }
        } else {
            pluggedIconHeight = (screenHeight * 0.11).toInt()

            timeRemainingTextHeight = (screenHeight * 0.06).toInt()
            untilWhatTextHeight = (screenHeight * 0.04).toInt()

            statusTextHeight = (screenHeight * 0.05).toInt()

            buHeight = (screenHeight * 0.18).toInt()
            buTextHeight = (screenHeight * 0.045).toInt()

            vitalIconHeight = (screenHeight * 0.08).toInt()
            vitalTextHeight = (screenHeight * 0.05).toInt()
        }

        val level = rootView.findViewById<View?>(R.id.level) as TextView
        level.setTextSize(TypedValue.COMPLEX_UNIT_PX, pluggedIconHeight.toFloat())

        val clock = rootView.findViewById<View>(R.id.clock)
        clock.setLayoutParams(
            LinearLayout.LayoutParams(
                pluggedIconHeight, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val timeRemaining = rootView.findViewById<View?>(R.id.time_remaining) as TextView
        timeRemaining.setTextSize(TypedValue.COMPLEX_UNIT_PX, timeRemainingTextHeight.toFloat())

        val untilWhat = rootView.findViewById<View?>(R.id.until_what) as TextView
        untilWhat.setTextSize(TypedValue.COMPLEX_UNIT_PX, untilWhatTextHeight.toFloat())

        val status = rootView.findViewById<View?>(R.id.status) as TextView
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, statusTextHeight.toFloat())

        val statusDuration = rootView.findViewById<View?>(R.id.status_duration) as TextView
        statusDuration.setTextSize(TypedValue.COMPLEX_UNIT_PX, untilWhatTextHeight.toFloat())

        val pluggedIcon = rootView.findViewById<View>(R.id.plugged_icon)
        pluggedIcon.setLayoutParams(
            LinearLayout.LayoutParams(
                pluggedIconHeight, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val pluggedSpacer = rootView.findViewById<View>(R.id.plugged_spacer)
        pluggedSpacer.setLayoutParams(
            LinearLayout.LayoutParams(
                pluggedIconHeight, pluggedIconHeight
            )
        )

        val buButton = rootView.findViewById<View?>(R.id.battery_use_b) as Button
        buButton.setLayoutParams(
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, buHeight
            )
        )
        buButton.setTextSize(TypedValue.COMPLEX_UNIT_PX, buTextHeight.toFloat())

        val tempIcon = rootView.findViewById<View>(R.id.temp_icon)
        tempIcon.setLayoutParams(LinearLayout.LayoutParams(vitalIconHeight, vitalIconHeight))
        val tempText = rootView.findViewById<View?>(R.id.temp) as TextView
        tempText.setLayoutParams(LinearLayout.LayoutParams(0, vitalIconHeight, 0.5f))
        tempText.setTextSize(TypedValue.COMPLEX_UNIT_PX, vitalTextHeight.toFloat())

        val healthIcon = rootView.findViewById<View>(R.id.health_icon)
        healthIcon.setLayoutParams(LinearLayout.LayoutParams(vitalIconHeight, vitalIconHeight))
        val healthText = rootView.findViewById<View?>(R.id.health) as TextView
        healthText.setLayoutParams(LinearLayout.LayoutParams(0, vitalIconHeight, 0.5f))
        healthText.setTextSize(TypedValue.COMPLEX_UNIT_PX, vitalTextHeight.toFloat())

        val voltageIcon = rootView.findViewById<View>(R.id.voltage_icon)
        voltageIcon.setLayoutParams(
            LinearLayout.LayoutParams(
                vitalIconHeight, vitalIconHeight
            )
        )
        val voltageText = rootView.findViewById<View?>(R.id.voltage) as TextView
        voltageText.setLayoutParams(LinearLayout.LayoutParams(0, vitalIconHeight, 0.5f))
        voltageText.setTextSize(TypedValue.COMPLEX_UNIT_PX, vitalTextHeight.toFloat())

        val currentIcon = rootView.findViewById<View>(R.id.current_icon)
        currentIcon.setLayoutParams(
            LinearLayout.LayoutParams(
                vitalIconHeight, vitalIconHeight
            )
        )
        val currentText = rootView.findViewById<View?>(R.id.current) as TextView
        currentText.setLayoutParams(LinearLayout.LayoutParams(0, vitalIconHeight, 0.5f))
        currentText.setTextSize(TypedValue.COMPLEX_UNIT_PX, vitalTextHeight.toFloat())

        if (!portrait && aspectRatio < 1.32) pluggedSpacer.visibility = View.GONE
        else pluggedSpacer.visibility = View.INVISIBLE
    }
}
