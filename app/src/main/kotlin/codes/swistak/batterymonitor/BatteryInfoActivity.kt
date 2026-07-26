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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.viewpager.widget.PagerTitleStrip
import androidx.viewpager.widget.ViewPager
import java.util.Locale

class BatteryInfoActivity : AppCompatActivity() {
    companion object {
        const val PR_LVF_WRITE_STORAGE: Int = 1
    }

    private var pagerAdapter: BatteryInfoPagerAdapter? = null
    private var viewPager: ViewPager? = null
    private var advancedStatsEnabled = false

    public override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.bi_main_theme)
        super.onCreate(savedInstanceState)

        supportActionBar!!.elevation = 0f
        PersistentFragment.getInstance(supportFragmentManager)

        setContentView(R.layout.battery_info)
        EdgeToEdgeHelper.applyIfNeeded(this)

        advancedStatsEnabled = isAdvancedStatsEnabled()
        pagerAdapter = BatteryInfoPagerAdapter(supportFragmentManager, advancedStatsEnabled)

        pagerAdapter!!.setContext(this)

        viewPager = findViewById<View?>(R.id.pager) as ViewPager
        viewPager!!.setAdapter(pagerAdapter)

        viewPager!!.setCurrentItem(1)
        routeIntent(intent)
    }

    override fun onResume() {
        super.onResume()

        maybeRebuildPager()

        val tabStrip = findViewById<View?>(R.id.pager_tab_strip) as PagerTitleStrip
        tabStrip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeIntent(intent)
    }

    private fun routeIntent(intent: Intent) {
        if (intent.hasExtra(BatteryInfoService.EXTRA_EDIT_ALARMS)) viewPager!!.setCurrentItem(
            pagerAdapter!!.alarmsPosition
        )
        else if (intent.hasExtra(BatteryInfoService.EXTRA_CURRENT_INFO)) viewPager!!.setCurrentItem(
            1
        )
    }

    public override fun onStart() {
        super.onStart()

        pagerAdapter!!.setContext(this)
    }

    public override fun onStop() {
        super.onStop()

        pagerAdapter!!.setContext(null)
    }

    private fun isAdvancedStatsEnabled(): Boolean {
        val settings = getSharedPreferences(SettingsFragment.SETTINGS_FILE, MODE_PRIVATE)
        return settings.getBoolean(SettingsFragment.KEY_ENABLE_ADVANCED_STATS, false)
    }

    private fun maybeRebuildPager() {
        val newAdvancedStatsEnabled = isAdvancedStatsEnabled()
        if (newAdvancedStatsEnabled == advancedStatsEnabled) return

        val currentItem = viewPager!!.currentItem
        val oldAdvancedStatsEnabled = advancedStatsEnabled

        advancedStatsEnabled = newAdvancedStatsEnabled
        pagerAdapter = BatteryInfoPagerAdapter(supportFragmentManager, advancedStatsEnabled)
        pagerAdapter!!.setContext(this)
        viewPager!!.setAdapter(pagerAdapter)

        var newCurrentItem = currentItem
        if (oldAdvancedStatsEnabled && !advancedStatsEnabled && currentItem == 2) newCurrentItem = 1
        else if (currentItem == (if (oldAdvancedStatsEnabled) 3 else 2)) newCurrentItem =
            pagerAdapter!!.alarmsPosition

        viewPager!!.setCurrentItem(newCurrentItem, false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && viewPager!!.currentItem != 1) {
            viewPager!!.setCurrentItem(1)
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String?>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PR_LVF_WRITE_STORAGE -> {
                val lvf: LogViewFragment? = pagerAdapter!!.lVF

                if (lvf != null) lvf.onRequestPermissionsResult(
                    requestCode, permissions, grantResults
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private class BatteryInfoPagerAdapter(
        fm: FragmentManager, private val showAdvancedTab: Boolean
    ) : androidx.fragment.app.FragmentPagerAdapter(fm) {
        private var context: Context? = null
        var lVF: LogViewFragment? = null
            private set

        fun setContext(c: Context?) {
            context = c
        }

        override fun getCount(): Int {
            return if (showAdvancedTab) 4 else 3
        }

        val alarmsPosition: Int
            get() = if (showAdvancedTab) 3 else 2

        override fun getItemId(position: Int): Long {
            return when (position) {
                0 -> 0
                1 -> 1
                2 -> (if (showAdvancedTab) 2 else 3).toLong()
                3 -> 3
                else -> position.toLong()
            }
        }

        override fun getItem(position: Int): Fragment {
            when (position) {
                0 -> return LogViewFragment()
                1 -> return CurrentInfoFragment()
                2 -> {
                    if (showAdvancedTab) return AdvancedInfoFragment()

                    return AlarmsFragment()
                }

                3 -> return AlarmsFragment()
                else -> throw IllegalArgumentException("Unknown page position: $position")
            }
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val fragment = super.instantiateItem(container, position) as Fragment

            if (position == 0) this.lVF = fragment as LogViewFragment

            return fragment
        }

        override fun getPageTitle(position: Int): CharSequence? {
            if (context == null) return null

            val res = context!!.resources

            when (position) {
                0 -> return res.getString(R.string.tab_history).uppercase(Locale.getDefault())
                1 -> return res.getString(R.string.tab_current_info).uppercase(Locale.getDefault())
                2 -> {
                    if (showAdvancedTab) return res.getString(R.string.tab_advanced).uppercase(
                        Locale.getDefault()
                    )

                    return res.getString(R.string.alarm_settings).uppercase(Locale.getDefault())
                }

                3 -> return res.getString(R.string.alarm_settings).uppercase(Locale.getDefault())
                else -> return null
            }
        }
    }
}
