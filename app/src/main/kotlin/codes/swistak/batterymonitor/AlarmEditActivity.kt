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

import android.content.ComponentName
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class AlarmEditActivity : AppCompatActivity() {
    private var res: Resources? = null
    private var frag: AlarmEditFragment? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        res = getResources()

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

        setWindowSubtitle(res!!.getString(R.string.alarm_settings_subtitle))

        setContentView(R.layout.prefs)
        EdgeToEdgeHelper.applyIfNeeded(this)

        frag = AlarmEditFragment()

        supportFragmentManager.beginTransaction().replace(R.id.settings, frag!!, "aef").commit()

        frag!!.setScreen()
    }

    private fun setWindowSubtitle(subtitle: String?) {
        if (res!!.getBoolean(R.bool.long_activity_names)) setTitle(res!!.getString(R.string.app_full_name) + " - " + subtitle)
        else setTitle(subtitle)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.alarm_edit, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_delete) {
            frag!!.deleteAlarm()
            finish()

            return true
        }

        if (item.itemId == R.id.menu_help) {
            val comp = ComponentName(packageName, SettingsHelpActivity::class.java.getName())
            val intent: Intent = Intent().setComponent(comp).putExtra(
                SettingsActivity.Companion.EXTRA_SCREEN,
                SettingsFragment.Companion.KEY_ALARM_EDIT_SETTINGS
            )
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
        frag!!.enableNotifsButtonClick()
    }
}
