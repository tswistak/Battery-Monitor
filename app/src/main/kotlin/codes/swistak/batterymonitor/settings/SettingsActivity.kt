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
package codes.swistak.batterymonitor.settings

import android.content.ComponentName
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.EdgeToEdgeHelper
import codes.swistak.batterymonitor.diagnostics.DiagnosticsFragment

class SettingsActivity : AppCompatActivity() {
    private var res: Resources? = null
    private var prefScreen: String? = null
    private val menuRes = R.menu.settings
    private var frag: SettingsFragment? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = getIntent()
        prefScreen = intent.getStringExtra(SettingsContract.EXTRA_SCREEN)
        res = resources

        val ab = supportActionBar
        if (ab != null) {
            ab.setHomeButtonEnabled(true)
            ab.setDisplayHomeAsUpEnabled(true)
            ab.elevation = 0f
        }

        val c = resources.getColor(R.color.windowBackground, null)
        val w = window
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        w.statusBarColor = c

        setContentView(R.layout.prefs)
        EdgeToEdgeHelper.applyIfNeeded(this)

        if (prefScreen == SettingsContract.KEY_DIAGNOSTICS_SETTINGS) {
            if (savedInstanceState == null) {
                supportFragmentManager.beginTransaction().replace(
                    R.id.settings, DiagnosticsFragment(), ""
                ).commit()
            }
            frag = null
            setWindowSubtitle(res!!.getString(R.string.diagnostics))
            return
        }

        if (savedInstanceState == null) {
            frag = SettingsFragment()
            supportFragmentManager.beginTransaction().replace(R.id.settings, frag!!, "").commit()
        } else {
            frag = supportFragmentManager.findFragmentByTag("") as SettingsFragment?
        }

        if (prefScreen == null) {
            frag!!.setScreen(R.xml.main_pref_screen)
            setWindowSubtitle(res!!.getString(R.string.settings_activity_subtitle))
        } else if (prefScreen == SettingsContract.KEY_STATUS_BAR_ICON_SETTINGS) {
            frag!!.setScreen(R.xml.status_bar_icon_pref_screen)
            setWindowSubtitle(res!!.getString(R.string.status_bar_icon_settings))
        } else if (prefScreen == SettingsContract.KEY_STATUS_BAR_CHIP_SETTINGS) {
            frag!!.setScreen(R.xml.status_bar_chip_pref_screen)
            setWindowSubtitle(res!!.getString(R.string.status_bar_chip_settings))
        } else if (prefScreen == SettingsContract.KEY_NOTIFICATION_SETTINGS) {
            frag!!.setScreen(R.xml.notification_pref_screen)
            setWindowSubtitle(res!!.getString(R.string.notification_settings))
        } else if (prefScreen == SettingsContract.KEY_CURRENT_STATE_SETTINGS) {
            frag!!.setScreen(R.xml.current_state_pref_screen)
            setWindowSubtitle(res!!.getString(R.string.tab_current_info))
        } else if (prefScreen == SettingsContract.KEY_OTHER_SETTINGS) {
            frag!!.setScreen(R.xml.other_pref_screen)
            setWindowSubtitle(res!!.getString(R.string.other_settings))
        } else if (prefScreen == SettingsContract.KEY_UNITS_FORMATTING_SETTINGS) {
            frag!!.setScreen(R.xml.units_formatting_pref_screen)
            setWindowSubtitle(res!!.getString(R.string.units_formatting_settings))
        } else if (prefScreen == SettingsContract.KEY_ADVANCED_SETTINGS) {
            frag!!.setScreen(R.xml.advanced_pref_screen)
            setWindowSubtitle(res!!.getString(R.string.advanced_settings))
        } else if (prefScreen == SettingsContract.KEY_BACKUP_RESTORE_SETTINGS) {
            frag!!.setScreen(R.xml.backup_restore_pref_screen)
            setWindowSubtitle(res!!.getString(R.string.pref_backup_restore))
        } else {
            frag!!.setScreen(R.xml.main_pref_screen)
            setWindowSubtitle(res!!.getString(R.string.settings_activity_subtitle))
        }
    }

    private fun setWindowSubtitle(subtitle: String?) {
        if (res!!.getBoolean(R.bool.long_activity_names)) setTitle(res!!.getString(R.string.app_full_name) + " - " + subtitle)
        else setTitle(subtitle)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val inflater = menuInflater
        inflater.inflate(menuRes, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_help) {
            val comp = ComponentName(packageName, SettingsHelpActivity::class.java.getName())
            val intent = Intent().setComponent(comp)

            if (prefScreen != null) intent.putExtra(SettingsContract.EXTRA_SCREEN, prefScreen)

            startActivity(intent)

            return true
        }

        if (item.itemId == android.R.id.home) {
            finish()

            return true
        }

        return super.onOptionsItemSelected(item)
    }

    fun enableNotifsButtonClick(v: View?) {
        frag?.enableNotifsButtonClick()
    }
}
